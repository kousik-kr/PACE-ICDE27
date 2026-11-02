package edu.ipcmax.core.pcmax.egpi;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceTransferProfile;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Query-local exact connector and pivot transfer profiles, computed lazily.
 *
 * <p>Construction does no replay. A transfer is a function of edge-entry time
 * only, so it is keyed by its complete directed arc sequence, the temporal
 * data identity, the numerical mode and the exact rational entry domain;
 * the search restriction that produced a structural witness is deliberately
 * not part of the key. A request is answered from an identical domain, by
 * restricting a cached transfer whose domain covers it, or by one exact
 * computation on exactly the requested domain; partially covering entries are
 * never stitched. Equal concurrent requests share one computation.</p>
 */
public final class LazyConnectorProfileCache {
    public static final String POLICY = "EXACT_ENTRY_DOMAIN_LAZY_TRANSFER_V1";
    public static final String NUMERICAL_MODE = "CANONICAL_RATIONAL_EDGE_ENTRY_NO_WAIT_V1";

    private record PathKey(List<Integer> edges, String temporalIdentity) {
        PathKey { edges = List.copyOf(edges); }
    }

    private record Key(PathKey path, ExactTemporalDomain domain) { }

    private final TDGraph graph;
    private final String temporalIdentity;
    private final BooleanSupplier defaultCancellation;
    private final BoundedExactCache<Key, ExactPreferenceTransferProfile> cache;
    private final Map<PathKey, Set<Key>> byPath = new HashMap<>();
    private final TreeMap<String, Long> counters = new TreeMap<>();

    public LazyConnectorProfileCache(TDGraph graph, String temporalIdentity, int capacity) {
        this(graph, temporalIdentity, capacity, ExactCacheSupport.DEFAULT_MAX_PAYLOAD_BYTES, null);
    }

    public LazyConnectorProfileCache(TDGraph graph, String temporalIdentity, int capacity,
            long maximumPayloadBytes, BooleanSupplier defaultCancellation) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.temporalIdentity = Objects.requireNonNull(temporalIdentity, "temporal identity");
        this.defaultCancellation = defaultCancellation;
        this.cache = new BoundedExactCache<>(capacity, maximumPayloadBytes,
                profile -> ExactCacheSupport.payloadBytes(profile, null), this::forget);
        for (String key : List.of("temporal_profiles_requested", "lazy_profiles_newly_computed",
                "exact_domain_cache_hits", "domain_restrictions_reused", "avoided_profile_recomputations",
                "temporal_cache_misses", "temporal_cache_single_flight_waits", "lazy_profile_compute_ns",
                "pivot_transfer_requests", "connector_transfer_requests")) counters.put(key, 0L);
    }

    public String temporalIdentity() { return temporalIdentity; }

    public ExactPreferenceTransferProfile getExactProfile(PathWitness witness, ExactTemporalDomain entryDomain) {
        return getExactProfile(witness, entryDomain, null);
    }

    /** Exact transfer of a structural connector on (a superset-restriction of) its required entry domain. */
    public ExactPreferenceTransferProfile getExactProfile(PathWitness witness,
            ExactTemporalDomain entryDomain, BooleanSupplier cancel) {
        Objects.requireNonNull(witness, "witness");
        synchronized (this) { add("connector_transfer_requests", 1); }
        return get(witness.source(), witness.destination(), witness.directedEdgeIds(), entryDomain, cancel);
    }

    /** Pivot arcs use the same exact transfer engine and cache. */
    public ExactPreferenceTransferProfile getExactEdgeProfile(
            int directedEdgeId, ExactTemporalDomain entryDomain, BooleanSupplier cancel) {
        var edge = graph.edges().get(directedEdgeId);
        synchronized (this) { add("pivot_transfer_requests", 1); }
        return get(edge.source(), edge.target(), List.of(directedEdgeId), entryDomain, cancel);
    }

    private ExactPreferenceTransferProfile get(int source, int destination, List<Integer> edges,
            ExactTemporalDomain domain, BooleanSupplier suppliedCancel) {
        Objects.requireNonNull(domain, "entry domain");
        BooleanSupplier cancel = () -> (defaultCancellation != null && defaultCancellation.getAsBoolean())
                || (suppliedCancel != null && suppliedCancel.getAsBoolean());
        ExactCacheSupport.check(cancel);
        PathKey path = new PathKey(edges, temporalIdentity);
        Key key = new Key(path, domain);
        synchronized (this) { add("temporal_profiles_requested", 1); }
        ExactPreferenceTransferProfile exact = cache.getIfPresent(key);
        if (exact != null) {
            synchronized (this) {
                add("exact_domain_cache_hits", 1);
                add("avoided_profile_recomputations", 1);
            }
            return exact;
        }
        for (Key candidate : coveringCandidates(path)) {
            ExactCacheSupport.check(cancel);
            if (!domain.difference(candidate.domain(), cancel).isEmpty()) continue;
            ExactPreferenceTransferProfile covering = cache.getIfPresent(candidate);
            if (covering == null) continue;
            synchronized (this) {
                add("domain_restrictions_reused", 1);
                add("avoided_profile_recomputations", 1);
            }
            return covering.restrict(domain, cancel);
        }
        long started = System.nanoTime();
        BoundedExactCache.Lookup<ExactPreferenceTransferProfile> lookup = cache.get(key,
                () -> ExactPreferenceReplay.replayTransferExact(
                        graph, source, destination, new Path(edges), domain, cancel), cancel);
        synchronized (this) {
            if (lookup.outcome() == BoundedExactCache.Outcome.COMPUTED) {
                add("temporal_cache_misses", 1);
                add("lazy_profiles_newly_computed", 1);
                add("lazy_profile_compute_ns", System.nanoTime() - started);
                byPath.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(key);
            } else {
                add("temporal_cache_single_flight_waits", 1);
                add("avoided_profile_recomputations", 1);
            }
        }
        return lookup.value();
    }

    private synchronized List<Key> coveringCandidates(PathKey path) {
        Set<Key> keys = byPath.get(path);
        return keys == null ? List.of() : new ArrayList<>(keys);
    }

    private synchronized void forget(Key key, ExactPreferenceTransferProfile ignored) {
        Set<Key> keys = byPath.get(key.path());
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) byPath.remove(key.path());
    }

    private void add(String counter, long amount) { counters.merge(counter, amount, Long::sum); }

    public Map<String, Long> stats() {
        TreeMap<String, Long> result;
        synchronized (this) { result = new TreeMap<>(counters); }
        result.put("temporal_cache_entries", (long) cache.size());
        result.put("temporal_cache_payload_bytes", cache.bytes());
        result.put("temporal_cache_peak_payload_bytes", cache.peakBytes());
        result.put("temporal_cache_evictions", cache.evictions());
        result.put("temporal_cache_oversize_not_cached", cache.oversize());
        result.put("temporal_cache_in_flight", (long) cache.inFlightCount());
        return Map.copyOf(result);
    }
}
