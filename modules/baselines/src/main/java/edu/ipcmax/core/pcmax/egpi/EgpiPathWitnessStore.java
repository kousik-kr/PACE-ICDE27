package edu.ipcmax.core.pcmax.egpi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.IPCMaxParallelExecutor;
import edu.ipcmax.core.pcmax.QueryCorridor;
import edu.ipcmax.core.pcmax.QueryLowerBounds;

/**
 * Query-scoped static path labels and a bounded, single-flight structural
 * witness cache. No temporal profile is created by this class.
 *
 * <p>Every witness is the canonical static lower-bound shortest path inside
 * the safe corridor under its exact exclusions: minimum canonical-tick cost,
 * then fewer arcs, then the lexicographically smallest directed arc-ID
 * sequence in traversal order ({@link PathWitness#POLICY}). It is not a
 * time-dependent fastest path.</p>
 *
 * <p>A cached witness {@code W} for exclusions {@code F1} is reused for a
 * request with exclusions {@code F2} only when {@code F1 ⊆ F2} and {@code W}
 * avoids {@code F2}: paths avoiding {@code F2} are a subset of those avoiding
 * {@code F1}, so {@code W} is then also the canonical minimum under
 * {@code F2}. The unrestricted witness is the case {@code F1 = ∅}.</p>
 *
 * <p>{@link #resolve} is deterministic for a fixed request order: hits,
 * reuses and connector-search budget reservations are decided sequentially,
 * then the missing searches may run in parallel and are inserted in request
 * order.</p>
 */
public final class EgpiPathWitnessStore {
    public static final String TIE_POLICY = "static-ticks12-cost-hops-lexicographic-arcs-v1";
    public static final String WEIGHT_VERSION = "query-lower-bounds-canonical-ticks12-v1";

    /** One fully specified structural connector request. */
    public record Request(int from, int to, Set<Integer> forbiddenVertices,
            Set<Integer> forbiddenArcIds, String structuralRestriction) {
        public Request {
            Objects.requireNonNull(forbiddenVertices, "forbiddenVertices");
            Objects.requireNonNull(forbiddenArcIds, "forbiddenArcIds");
            Objects.requireNonNull(structuralRestriction, "structuralRestriction");
        }
    }

    /** How a request was answered. Only {@link #SEARCH} consumes connector-search budget. */
    public enum Source {
        CACHE_HIT, SINGLE_FLIGHT_WAIT, FORBIDDEN_ENDPOINT, TRIVIAL, LABEL_RECONSTRUCTION,
        UNRESTRICTED_REUSE, RESTRICTED_SUBSET_REUSE, UNRESTRICTED_UNREACHABLE, SEARCH,
        SEARCH_BUDGET_REJECTED
    }

    public record Resolution(Optional<PathWitness> witness, Source source) {
        public boolean budgetRejected() { return source == Source.SEARCH_BUDGET_REJECTED; }
    }

    private final TDGraph graph;
    private final QueryCorridor corridor;
    private final QueryLowerBounds weights;
    private final int source;
    private final int destination;
    private final int capacity;
    private final int maxConnectorSearches;
    private final BooleanSupplier cancellation;
    private final NodeIndex index;
    private final long[] arcWeightTicks;
    private final String graphIdentity;
    private final Labels forward;
    private final Labels backward;
    private final LinkedHashMap<RequestKey, Optional<PathWitness>> cache = new LinkedHashMap<>();
    private final Map<Endpoints, List<RequestKey>> cachedByEndpoints = new HashMap<>();
    private final Map<RequestKey, CompletableFuture<Optional<PathWitness>>> inFlight = new HashMap<>();
    private final ConcurrentLinkedQueue<Labels> scratch = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<String, Long> counters = new LinkedHashMap<>();
    private long cachePayloadBytes;
    private long peakCachePayloadBytes;
    private int connectorSearches;

    public EgpiPathWitnessStore(TDGraph graph, QueryCorridor corridor, QueryLowerBounds lowerBounds,
            int source, int destination, int capacity, int maxConnectorSearches,
            BooleanSupplier cancellationRequested) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.corridor = Objects.requireNonNull(corridor, "corridor");
        this.weights = Objects.requireNonNull(lowerBounds, "lowerBounds");
        this.cancellation = Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        if (capacity < 0 || maxConnectorSearches < 0) {
            throw new IllegalArgumentException("cache and work limits must be nonnegative");
        }
        graph.node(source);
        graph.node(destination);
        if (source != corridor.source() || destination != corridor.destination()) {
            throw new IllegalArgumentException("corridor endpoints differ from query endpoints");
        }
        this.source = source;
        this.destination = destination;
        this.capacity = capacity;
        this.maxConnectorSearches = maxConnectorSearches;
        this.index = new NodeIndex(corridor, source, destination);
        this.arcWeightTicks = new long[graph.edgeCount()];
        for (int arc : corridor.directedArcIds()) {
            long weight = Domain.canonicalTick(weights.edgeWeight(arc));
            if (weight < 0) throw new IllegalArgumentException("negative static weight");
            arcWeightTicks[arc] = weight;
        }
        this.graphIdentity = computeGraphIdentity();
        long start = System.nanoTime();
        this.forward = new Labels(index.size, false);
        label(forward, source, -1, Set.of(), Set.of());
        counters.put("forward_label_build_ns", System.nanoTime() - start);
        counters.put("forward_label_builds", 1L);
        counters.put("forward_labels_built", (long) forward.reached);
        start = System.nanoTime();
        this.backward = new Labels(index.size, true);
        label(backward, destination, -1, Set.of(), Set.of());
        counters.put("backward_label_build_ns", System.nanoTime() - start);
        counters.put("backward_label_builds", 1L);
        counters.put("backward_labels_built", (long) backward.reached);
        counters.put("static_labeling_ns", counters.get("forward_label_build_ns")
                + counters.get("backward_label_build_ns"));
        // Precisely counts the primitive label-array payload; JVM headers and index maps are excluded.
        counters.put("structural_label_array_payload_bytes",
                2L * index.size * (Long.BYTES + 3 * Integer.BYTES));
        counters.put("corridor_indexed_vertices", (long) index.size);
    }

    public Optional<PathWitness> forward(int vertex) {
        return witness(source, vertex, Set.of(), Set.of(), "");
    }

    public Optional<PathWitness> backward(int vertex) {
        return witness(vertex, destination, Set.of(), Set.of(), "");
    }

    public double sourceDistance(int vertex) { return distance(forward, vertex); }
    public double destinationDistance(int vertex) { return distance(backward, vertex); }
    public boolean sourceReachable(int vertex) { return Double.isFinite(sourceDistance(vertex)); }
    public boolean destinationReachable(int vertex) { return Double.isFinite(destinationDistance(vertex)); }
    public String graphIdentity() { return graphIdentity; }
    public int source() { return source; }
    public int destination() { return destination; }
    public synchronized int connectorSearches() { return connectorSearches; }
    public synchronized int remainingConnectorSearches() { return maxConnectorSearches - connectorSearches; }

    /**
     * Returns the canonical witness for one complete request.
     *
     * @throws ConnectorSearchLimitException if a new search is needed after the budget is spent
     */
    public Optional<PathWitness> witness(int from, int to,
            Set<Integer> forbiddenVertices, Set<Integer> forbiddenArcIds,
            String structuralRestriction) {
        Resolution resolution = resolve(List.of(new Request(from, to, forbiddenVertices,
                forbiddenArcIds, structuralRestriction)), null).getFirst();
        if (resolution.budgetRejected()) throw new ConnectorSearchLimitException(maxConnectorSearches);
        return resolution.witness();
    }

    /**
     * Resolves requests in order. Missing searches are run on {@code executor}
     * when it is supplied and more than one search is needed.
     */
    public List<Resolution> resolve(List<Request> requests, IPCMaxParallelExecutor executor) {
        Objects.requireNonNull(requests, "requests");
        Resolution[] results = new Resolution[requests.size()];
        LinkedHashMap<RequestKey, Missing> missing = new LinkedHashMap<>();
        List<Integer> waitIndices = new ArrayList<>();
        List<CompletableFuture<Optional<PathWitness>>> waits = new ArrayList<>();
        synchronized (this) {
            try {
                planRequests(requests, results, missing, waitIndices, waits);
            } catch (RuntimeException failure) {
                // No search of this batch has started: release its reservations.
                for (Missing search : missing.values()) {
                    inFlight.remove(search.key);
                    search.future.completeExceptionally(failure);
                    connectorSearches--;
                }
                throw failure;
            }
        }
        List<Missing> searches = List.copyOf(missing.values());
        List<SearchOutcome> outcomes = runSearches(searches, executor);
        RuntimeException failure = null;
        synchronized (this) {
            for (int s = 0; s < searches.size(); s++) {
                Missing search = searches.get(s);
                SearchOutcome outcome = outcomes.get(s);
                inFlight.remove(search.key);
                if (outcome.failure() != null) {
                    search.future.completeExceptionally(outcome.failure());
                    if (failure == null) failure = outcome.failure();
                    continue;
                }
                add("connector_search_ns", outcome.nanos());
                Optional<PathWitness> value = retain(search.key, outcome.witness());
                search.future.complete(value);
                for (int index : search.indices) results[index] = new Resolution(value, Source.SEARCH);
            }
        }
        if (failure != null) throw failure;
        for (int w = 0; w < waits.size(); w++) {
            results[waitIndices.get(w)] = new Resolution(
                    ExactCacheSupport.await(waits.get(w), cancellation), Source.SINGLE_FLIGHT_WAIT);
        }
        return List.of(results);
    }

    /** Sequential, deterministic hit/reuse/budget decisions; caller holds the monitor. */
    private void planRequests(List<Request> requests, Resolution[] results,
            LinkedHashMap<RequestKey, Missing> missing, List<Integer> waitIndices,
            List<CompletableFuture<Optional<PathWitness>>> waits) {
        add("witness_resolve_batches", 1);
        for (int i = 0; i < requests.size(); i++) {
            checkCancelled();
            Request request = requests.get(i);
            graph.node(request.from());
            graph.node(request.to());
            increment("structural_witnesses_requested");
            RequestKey key = key(request.from(), request.to(), request.forbiddenVertices(),
                    request.forbiddenArcIds(), request.structuralRestriction());
            Optional<PathWitness> hit = cache.get(key);
            if (hit != null) {
                increment("witness_cache_hits");
                results[i] = new Resolution(hit, Source.CACHE_HIT);
                continue;
            }
            CompletableFuture<Optional<PathWitness>> pending = inFlight.get(key);
            if (pending != null) {
                increment("witness_cache_hits");
                increment("witness_single_flight_waits");
                waitIndices.add(i);
                waits.add(pending);
                continue;
            }
            Missing duplicate = missing.get(key);
            if (duplicate != null) {
                increment("witness_cache_hits");
                increment("witness_batch_duplicate_requests");
                duplicate.indices.add(i);
                continue;
            }
            increment("witness_cache_misses");
            Resolution answered = answerWithoutSearch(request, key);
            if (answered != null) {
                results[i] = answered;
                continue;
            }
            if (connectorSearches >= maxConnectorSearches) {
                increment("connector_search_budget_rejections");
                results[i] = new Resolution(Optional.empty(), Source.SEARCH_BUDGET_REJECTED);
                continue;
            }
            connectorSearches++;
            increment("connector_searches");
            boolean restricted = !request.forbiddenVertices().isEmpty() || !request.forbiddenArcIds().isEmpty();
            increment(restricted ? "restricted_connector_searches" : "intermediate_connector_searches");
            CompletableFuture<Optional<PathWitness>> future = new CompletableFuture<>();
            inFlight.put(key, future);
            Missing search = new Missing(request, key, future);
            search.indices.add(i);
            missing.put(key, search);
        }
    }

    /** Answers from endpoints, labels or a reusable cached witness; {@code null} means search. */
    private Resolution answerWithoutSearch(Request request, RequestKey key) {
        int from = request.from();
        int to = request.to();
        Set<Integer> vertices = request.forbiddenVertices();
        Set<Integer> arcs = request.forbiddenArcIds();
        String restriction = restrictionIdentity(vertices, arcs, request.structuralRestriction());
        if (vertices.contains(from) || vertices.contains(to)) {
            return new Resolution(retain(key, Optional.empty()), Source.FORBIDDEN_ENDPOINT);
        }
        if (from == to) {
            return new Resolution(retain(key, Optional.of(PathWitness.of(graph, from, to, List.of(), 0,
                    graphIdentity, restriction))), Source.TRIVIAL);
        }
        if (index.at(from) < 0 || index.at(to) < 0) {
            return new Resolution(retain(key, Optional.empty()), Source.UNRESTRICTED_UNREACHABLE);
        }
        boolean restricted = !vertices.isEmpty() || !arcs.isEmpty();
        if (!restricted && request.structuralRestriction().isEmpty()) {
            if (from == source || to == destination) {
                return new Resolution(retain(key, reconstructKnown(from, to, restriction)),
                        Source.LABEL_RECONSTRUCTION);
            }
            return null;
        }
        RequestKey unrestrictedKey = key(from, to, Set.of(), Set.of(), "");
        Optional<PathWitness> unrestricted = cache.get(unrestrictedKey);
        if (unrestricted == null && (from == source || to == destination)) {
            unrestricted = retain(unrestrictedKey,
                    reconstructKnown(from, to, restrictionIdentity(Set.of(), Set.of(), "")));
        }
        if (unrestricted != null) {
            if (unrestricted.isEmpty()) {
                return new Resolution(retain(key, Optional.empty()), Source.UNRESTRICTED_UNREACHABLE);
            }
            if (valid(unrestricted.get(), vertices, arcs)) {
                increment("unrestricted_witness_reuses");
                return new Resolution(retain(key, Optional.of(
                        unrestricted.get().withRestrictionIdentity(restriction))), Source.UNRESTRICTED_REUSE);
            }
            increment("unrestricted_witness_conflicts");
        }
        for (RequestKey cached : cachedByEndpoints.getOrDefault(new Endpoints(from, to), List.of())) {
            checkCancelled();
            if (cached.forbiddenVertices().isEmpty() && cached.forbiddenArcs().isEmpty()) continue;
            Optional<PathWitness> candidate = cache.get(cached);
            if (candidate == null || candidate.isEmpty()) continue;
            if (vertices.containsAll(cached.forbiddenVertices()) && arcs.containsAll(cached.forbiddenArcs())
                    && valid(candidate.get(), vertices, arcs)) {
                increment("restricted_subset_witness_reuses");
                return new Resolution(retain(key, Optional.of(
                        candidate.get().withRestrictionIdentity(restriction))), Source.RESTRICTED_SUBSET_REUSE);
            }
        }
        return null;
    }

    private List<SearchOutcome> runSearches(List<Missing> searches, IPCMaxParallelExecutor executor) {
        List<Callable<SearchOutcome>> tasks = new ArrayList<>(searches.size());
        for (Missing search : searches) tasks.add(() -> search(search.request));
        if (executor == null || tasks.size() < 2) {
            List<SearchOutcome> result = new ArrayList<>(tasks.size());
            for (Callable<SearchOutcome> task : tasks) {
                try {
                    result.add(task.call());
                } catch (Exception impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
            return result;
        }
        synchronized (this) { add("parallel_connector_searches", tasks.size()); }
        try {
            return executor.invokeAllDeterministic(tasks);
        } catch (IllegalStateException interrupted) {
            CancellationException cancelled = new CancellationException("EGPI connector searches interrupted");
            cancelled.initCause(interrupted);
            List<SearchOutcome> failed = new ArrayList<>(tasks.size());
            for (int i = 0; i < tasks.size(); i++) failed.add(new SearchOutcome(null, 0, cancelled));
            return failed;
        }
    }

    /** One static search under the exact exclusions; never mutates the cache. */
    private SearchOutcome search(Request request) {
        long started = System.nanoTime();
        Labels labels = scratch.poll();
        if (labels == null) labels = new Labels(index.size, false);
        try {
            label(labels, request.from(), request.to(), request.forbiddenVertices(), request.forbiddenArcIds());
            Optional<PathWitness> witness = reconstruct(labels, request.from(), request.to(),
                    restrictionIdentity(request.forbiddenVertices(), request.forbiddenArcIds(),
                            request.structuralRestriction()));
            return new SearchOutcome(witness, System.nanoTime() - started, null);
        } catch (RuntimeException failure) {
            return new SearchOutcome(null, System.nanoTime() - started, failure);
        } finally {
            labels.reset();
            scratch.offer(labels);
        }
    }

    private Optional<PathWitness> reconstructKnown(int from, int to, String restriction) {
        return from == source ? reconstruct(forward, from, to, restriction)
                : reconstruct(backward, from, to, restriction);
    }

    private Optional<PathWitness> reconstruct(Labels labels, int from, int to, String restriction) {
        int terminal = labels.reverse ? from : to;
        int at = index.at(terminal);
        if (at < 0 || labels.distance[at] == Long.MAX_VALUE) return Optional.empty();
        checkCancelled();
        long start = System.nanoTime();
        List<Integer> arcs = arcs(labels, terminal);
        PathWitness result = PathWitness.of(graph, from, to, arcs,
                Domain.timeFromTick(labels.distance[at]), graphIdentity, restriction);
        synchronized (this) {
            increment("structural_witness_reconstructions");
            add("structural_witness_reconstruction_ns", System.nanoTime() - start);
        }
        return Optional.of(result);
    }

    private List<Integer> arcs(Labels labels, int terminal) {
        List<Integer> result = new ArrayList<>();
        int current = terminal;
        while (current != labels.root) {
            checkCancelled();
            int at = index.at(current);
            if (at < 0 || labels.parentArc[at] < 0 || result.size() > index.size) {
                throw new IllegalStateException("invalid structural label chain");
            }
            result.add(labels.parentArc[at]);
            current = labels.parentVertex[at];
        }
        if (!labels.reverse) Collections.reverse(result);
        return result;
    }

    private boolean valid(PathWitness witness, Set<Integer> vertices, Set<Integer> arcs) {
        for (int vertex : witness.vertexIds()) {
            checkCancelled();
            if (vertices.contains(vertex)) return false;
        }
        for (int arc : witness.directedEdgeIds()) {
            checkCancelled();
            if (arcs.contains(arc) || !corridor.containsArc(arc)) return false;
        }
        return true;
    }

    private void label(Labels result, int root, int stop,
            Set<Integer> forbiddenVertices, Set<Integer> forbiddenArcs) {
        checkCancelled();
        result.root = root;
        int rootIndex = index.at(root);
        result.touch(rootIndex);
        result.distance[rootIndex] = 0;
        result.hops[rootIndex] = 0;
        result.reached = 1;
        boolean reverse = result.reverse;
        PriorityQueue<QueueLabel> queue = new PriorityQueue<>();
        queue.add(new QueueLabel(root, 0, 0));
        long expansions = 0;
        long scanned = 0;
        try {
            while (!queue.isEmpty()) {
                checkCancelled();
                QueueLabel current = queue.remove();
                int currentIndex = index.at(current.vertex);
                if (current.cost != result.distance[currentIndex] || current.hops != result.hops[currentIndex]) continue;
                expansions++;
                if (current.vertex == stop) break;
                for (Edge edge : reverse ? corridor.incomingEdges(current.vertex) : corridor.outgoingEdges(current.vertex)) {
                    scanned++;
                    int next = reverse ? edge.source() : edge.target();
                    if (forbiddenArcs.contains(edge.arcId()) || forbiddenVertices.contains(next)) continue;
                    long weight = arcWeightTicks[edge.arcId()];
                    if (Long.MAX_VALUE - current.cost <= weight) continue;
                    long candidate = current.cost + weight;
                    int hops = current.hops + 1;
                    int nextIndex = index.at(next);
                    if (nextIndex < 0) continue;
                    boolean better = candidate < result.distance[nextIndex]
                            || (candidate == result.distance[nextIndex] && hops < result.hops[nextIndex]);
                    boolean tied = candidate == result.distance[nextIndex] && hops == result.hops[nextIndex];
                    if (!better && !(tied && canonicalCandidateWins(result, current.vertex, next, edge.arcId()))) continue;
                    if (result.distance[nextIndex] == Long.MAX_VALUE) {
                        result.reached++;
                        result.touch(nextIndex);
                    }
                    result.parentVertex[nextIndex] = current.vertex;
                    result.parentArc[nextIndex] = edge.arcId();
                    if (better) {
                        result.distance[nextIndex] = candidate;
                        result.hops[nextIndex] = hops;
                        queue.add(new QueueLabel(next, candidate, hops));
                    }
                }
            }
        } finally {
            synchronized (this) {
                add("static_label_expansions", expansions);
                add("static_label_arcs_scanned", scanned);
            }
        }
    }

    private boolean canonicalCandidateWins(Labels labels, int via, int target, int arc) {
        List<Integer> candidate = new ArrayList<>(arcs(labels, via));
        if (labels.reverse) candidate.addFirst(arc); else candidate.add(arc);
        List<Integer> existing = arcs(labels, target);
        for (int i = 0; i < candidate.size(); i++) {
            int order = Integer.compare(candidate.get(i), existing.get(i));
            if (order != 0) return order < 0;
        }
        return false;
    }

    private double distance(Labels labels, int vertex) {
        int at = index.at(vertex);
        return at < 0 || labels.distance[at] == Long.MAX_VALUE
                ? Double.POSITIVE_INFINITY : Domain.timeFromTick(labels.distance[at]);
    }

    private RequestKey key(int from, int to, Set<Integer> vertices, Set<Integer> arcs, String restriction) {
        return new RequestKey(graph, graphIdentity, corridor.checksum(), WEIGHT_VERSION,
                from, to, List.copyOf(new TreeSet<>(vertices)), List.copyOf(new TreeSet<>(arcs)),
                restriction, TIE_POLICY);
    }

    /** Full canonical text, not a lossy set hash. Request keys also retain the actual lists. */
    public static String restrictionIdentity(Set<Integer> vertices, Set<Integer> arcs, String restriction) {
        return "vertices=" + new TreeSet<>(vertices) + ";arcs=" + new TreeSet<>(arcs)
                + ";structural=" + restriction.length() + ":" + restriction;
    }

    private Optional<PathWitness> retain(RequestKey key, Optional<PathWitness> value) {
        checkCancelled();
        if (capacity == 0) return value;
        Optional<PathWitness> prior = cache.putIfAbsent(key, value);
        if (prior != null) return prior;
        cachedByEndpoints.computeIfAbsent(new Endpoints(key.source(), key.destination()),
                ignored -> new ArrayList<>()).add(key);
        cachePayloadBytes += payload(key, value);
        while (cache.size() > capacity) {
            var oldest = cache.entrySet().iterator();
            var entry = oldest.next();
            cachePayloadBytes -= payload(entry.getKey(), entry.getValue());
            oldest.remove();
            Endpoints endpoints = new Endpoints(entry.getKey().source(), entry.getKey().destination());
            List<RequestKey> siblings = cachedByEndpoints.get(endpoints);
            siblings.remove(entry.getKey());
            if (siblings.isEmpty()) cachedByEndpoints.remove(endpoints);
            increment("witness_cache_evictions");
        }
        peakCachePayloadBytes = Math.max(peakCachePayloadBytes, cachePayloadBytes);
        counters.merge("witness_cache_peak_entries", (long) cache.size(), Math::max);
        return value;
    }

    private static long payload(RequestKey key, Optional<PathWitness> witness) {
        long bytes = 2L * Integer.BYTES
                + (long) Integer.BYTES * (key.forbiddenVertices.size() + key.forbiddenArcs.size())
                + key.structuralRestriction.getBytes(StandardCharsets.UTF_8).length;
        if (witness.isPresent()) {
            PathWitness w = witness.get();
            bytes += (long) Integer.BYTES * (w.directedEdgeIds().size() + w.vertexIds().size())
                    + Double.BYTES + w.restrictionIdentity().getBytes(StandardCharsets.UTF_8).length;
        }
        return bytes;
    }

    public synchronized Map<String, Long> statistics() {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>(counters);
        for (String name : List.of("structural_witnesses_requested", "witness_cache_hits", "witness_cache_misses",
                "structural_witness_reconstructions", "connector_searches", "restricted_connector_searches",
                "intermediate_connector_searches", "unrestricted_witness_reuses", "unrestricted_witness_conflicts",
                "restricted_subset_witness_reuses", "witness_single_flight_waits", "witness_cache_evictions",
                "connector_search_budget_rejections", "parallel_connector_searches")) result.putIfAbsent(name, 0L);
        result.put("witness_cache_entries", (long) cache.size());
        result.put("structural_cache_payload_bytes", cachePayloadBytes);
        result.put("structural_cache_peak_payload_bytes", peakCachePayloadBytes);
        result.put("temporal_profiles_computed_during_labeling", 0L);
        return Collections.unmodifiableMap(result);
    }

    private void increment(String key) { add(key, 1); }
    private void add(String key, long value) { counters.merge(key, value, Long::sum); }
    private void checkCancelled() {
        if (Thread.currentThread().isInterrupted() || cancellation.getAsBoolean()) {
            throw new CancellationException("EGPI structural witness work cancelled");
        }
    }

    private String computeGraphIdentity() {
        MessageDigest hash = PathWitness.newDigest();
        hash.update(("egpi-eligible-structural-graph-v1:" + corridor.checksum()).getBytes(StandardCharsets.UTF_8));
        ByteBuffer data = ByteBuffer.allocate(3 * Integer.BYTES + Long.BYTES);
        for (int arc : corridor.directedArcIds()) {
            checkCancelled();
            Edge edge = graph.edges().get(arc);
            data.clear();
            data.putInt(arc).putInt(edge.source()).putInt(edge.target()).putLong(arcWeightTicks[arc]);
            hash.update(data.array());
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    public static final class ConnectorSearchLimitException extends RuntimeException {
        public ConnectorSearchLimitException(int maximum) { super("EGPI connector search limit reached: " + maximum); }
    }

    private record RequestKey(TDGraph graph, String graphIdentity, String corridorIdentity,
            String weightVersion, int source, int destination, List<Integer> forbiddenVertices,
            List<Integer> forbiddenArcs, String structuralRestriction, String tiePolicy) { }

    private record Endpoints(int from, int to) { }

    private record SearchOutcome(Optional<PathWitness> witness, long nanos, RuntimeException failure) { }

    private static final class Missing {
        final Request request;
        final RequestKey key;
        final CompletableFuture<Optional<PathWitness>> future;
        final List<Integer> indices = new ArrayList<>();
        Missing(Request request, RequestKey key, CompletableFuture<Optional<PathWitness>> future) {
            this.request = request;
            this.key = key;
            this.future = future;
        }
    }

    private record QueueLabel(int vertex, long cost, int hops) implements Comparable<QueueLabel> {
        public int compareTo(QueueLabel other) {
            int c = Long.compare(cost, other.cost);
            if (c == 0) c = Integer.compare(hops, other.hops);
            return c != 0 ? c : Integer.compare(vertex, other.vertex);
        }
    }

    /** Dense corridor-indexed labels; scratch instances reset only the slots they touched. */
    private static final class Labels {
        int root;
        final boolean reverse;
        final long[] distance;
        final int[] hops;
        final int[] parentVertex;
        final int[] parentArc;
        int[] touched = new int[16];
        int touchedCount;
        int reached;

        Labels(int size, boolean reverse) {
            this.reverse = reverse;
            distance = new long[size];
            hops = new int[size];
            parentVertex = new int[size];
            parentArc = new int[size];
            Arrays.fill(distance, Long.MAX_VALUE);
            Arrays.fill(hops, Integer.MAX_VALUE);
            Arrays.fill(parentVertex, -1);
            Arrays.fill(parentArc, -1);
        }

        void touch(int slot) {
            if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touched.length * 2);
            touched[touchedCount++] = slot;
        }

        void reset() {
            for (int i = 0; i < touchedCount; i++) {
                int slot = touched[i];
                distance[slot] = Long.MAX_VALUE;
                hops[slot] = Integer.MAX_VALUE;
                parentVertex[slot] = -1;
                parentArc[slot] = -1;
            }
            touchedCount = 0;
            reached = 0;
        }
    }

    /** Compact index over corridor vertices plus the query endpoints. */
    private static final class NodeIndex {
        private static final int MAX_DENSE_ID = 50_000_000;
        final int size;
        private final int[] dense;
        private final Map<Integer, Integer> sparse;

        NodeIndex(QueryCorridor corridor, int source, int destination) {
            List<Integer> vertices = new ArrayList<>(corridor.vertexIds().size() + 2);
            vertices.addAll(corridor.vertexIds());
            if (!vertices.contains(source)) vertices.add(source);
            if (!vertices.contains(destination)) vertices.add(destination);
            size = vertices.size();
            int maximum = 0;
            for (int vertex : vertices) maximum = Math.max(maximum, vertex);
            if (maximum <= MAX_DENSE_ID) {
                dense = new int[maximum + 1];
                Arrays.fill(dense, -1);
                for (int slot = 0; slot < size; slot++) dense[vertices.get(slot)] = slot;
                sparse = null;
            } else {
                dense = null;
                sparse = new HashMap<>(Math.max(16, size * 2));
                for (int slot = 0; slot < size; slot++) sparse.put(vertices.get(slot), slot);
            }
        }

        int at(int vertex) {
            if (dense != null) return vertex >= 0 && vertex < dense.length ? dense[vertex] : -1;
            return sparse.getOrDefault(vertex, -1);
        }
    }
}
