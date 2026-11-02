package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Exact functional fastest-path search over a continuous departure domain.
 *
 * <p>When all edge functions share one temporal support domain, this uses a
 * function-valued, priority-queue label-correcting search: each relaxation
 * composes complete profiles and merges them with an exact lower envelope. With
 * differing support domains, it expands vertex-simple route labels so a fast
 * but unsupported prefix cannot hide a slower prefix that can enter a later
 * edge's support. Neither mode samples departures or limits breakpoints.</p>
 *
 * <p>The cited THop paper describes this step as a fastest-path search and
 * cites Li et al. for its functional-search complexity. The cited work does
 * not specify a full all-destinations FPP API with partial edge support and
 * exact endpoint ownership. The common-support mode follows its functional
 * relaxation strategy. The partial-support fallback uses finite simple-route
 * label expansion to preserve the repository's unavailable-time semantics;
 * that fallback has an exponential worst case.</p>
 */
public final class FifoFastestPathProfileSearch {
    private final TDGraph graph;
    private final Map<Integer, ExactArrivalProfile> exactArcArrivals;

    public FifoFastestPathProfileSearch(TDGraph graph) {
        this(graph, Map.of());
    }

    FifoFastestPathProfileSearch(TDGraph graph, Map<Integer, ExactArrivalProfile> exactArcArrivals) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.exactArcArrivals = Map.copyOf(exactArcArrivals);
        validateFifoGraph(graph, this.exactArcArrivals);
    }

    /** Searches from {@code source} for all destinations over the given domain. */
    public SearchResult search(int source, Domain departureDomain) {
        graph.node(source);
        if (departureDomain == null || departureDomain.isEmpty()) {
            throw new IllegalArgumentException("a nonempty departure domain is required");
        }
        long started = System.nanoTime();
        Map<String, Long> work = new HashMap<>();
        long initialHeap = usedHeapBytes();
        ExactArrivalProfile identity = ExactArrivalProfile.identity(departureDomain);
        Map<Integer, ExactArrivalProfile> best = new HashMap<>();
        ProfileSearchCounts counts;

        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install(
                (name, amount) -> work.merge(name, amount, Long::sum))) {
            counts = hasCommonSupportDomain()
                    ? runProfileLabelCorrecting(source, identity, best, initialHeap)
                    : runSimpleRouteExpansion(source, identity, best, initialHeap);
        }

        return new SearchResult(
                source,
                departureDomain,
                best,
                new SearchStatistics(
                        System.nanoTime() - started,
                        counts.expanded(),
                        counts.generated(),
                        work.getOrDefault("thop_temporal_compositions", 0L),
                        work.getOrDefault("thop_envelope_operations", 0L),
                        counts.sampledPeakUsedHeapBytes()));
    }

    /** Function-valued label-correcting search, valid for shared edge support. */
    private ProfileSearchCounts runProfileLabelCorrecting(
            int source,
            ExactArrivalProfile identity,
            Map<Integer, ExactArrivalProfile> best,
            long initialHeap) {
        best.put(source, identity);
        PriorityQueue<ProfileQueueEntry> queue = new PriorityQueue<>(Comparator
                .comparing(ProfileQueueEntry::minimumArrival)
                .thenComparingInt(ProfileQueueEntry::node));
        Set<Integer> queued = new HashSet<>();
        queue.add(new ProfileQueueEntry(source, identity.minimumValueOnClosure()));
        queued.add(source);
        long expanded = 0;
        long generated = 1;
        long sampledPeak = initialHeap;

        while (!queue.isEmpty()) {
            ProfileQueueEntry entry = queue.poll();
            queued.remove(entry.node());
            ExactArrivalProfile prefix = best.get(entry.node());
            expanded++;
            for (Edge edge : graph.outgoingEdges(entry.node())) {
                ExactArrivalProfile candidate = prefix.composeAfter(edgeArrival(edge));
                if (candidate.isEmpty()) {
                    continue;
                }
                ExactArrivalProfile current = best.get(edge.target());
                ExactArrivalProfile merged = current == null
                        ? candidate
                        : current.pointwiseMinimum(candidate);
                if (current != null && current.sameFunction(merged)) {
                    continue;
                }
                best.put(edge.target(), merged);
                generated++;
                if (queued.add(edge.target())) {
                    queue.add(new ProfileQueueEntry(edge.target(), merged.minimumValueOnClosure()));
                }
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }
        return new ProfileSearchCounts(expanded, generated, sampledPeak);
    }

    /** Partial-support fallback; every expanded function has a simple route witness. */
    private ProfileSearchCounts runSimpleRouteExpansion(
            int source,
            ExactArrivalProfile identity,
            Map<Integer, ExactArrivalProfile> best,
            long initialHeap) {
        PriorityQueue<RouteLabel> queue = new PriorityQueue<>(LABEL_ORDER);
        RouteLabel initial = new RouteLabel(source, identity, List.of(), List.of(source), Set.of(source));
        queue.add(initial);
        Map<StateKey, List<ExactArrivalProfile>> exactDuplicateStates = new HashMap<>();
        exactDuplicateStates.put(new StateKey(source, initial.visitedNodes()), new ArrayList<>(List.of(identity)));
        long expanded = 0;
        long generated = 1;
        long sampledPeak = initialHeap;

        while (!queue.isEmpty()) {
            RouteLabel label = queue.poll();
            expanded++;
            ExactArrivalProfile prior = best.get(label.node());
            if (prior == null) {
                best.put(label.node(), label.profile());
            } else {
                best.put(label.node(), prior.pointwiseMinimum(label.profile()));
            }
            for (Edge edge : graph.outgoingEdges(label.node())) {
                if (label.visitedNodes().contains(edge.target())) {
                    continue;
                }
                ExactArrivalProfile candidate = label.profile().composeAfter(
                edgeArrival(edge));
                if (candidate.isEmpty()) {
                    continue;
                }
                List<Integer> arcs = append(label.arcIds(), edge.arcId());
                List<Integer> nodes = append(label.nodeSequence(), edge.target());
                Set<Integer> visited = new HashSet<>(label.visitedNodes());
                visited.add(edge.target());
                Set<Integer> immutableVisited = Set.copyOf(visited);
                StateKey state = new StateKey(edge.target(), immutableVisited);
                List<ExactArrivalProfile> equivalent = exactDuplicateStates.computeIfAbsent(
                        state, ignoredState -> new ArrayList<>());
                if (equivalent.stream().anyMatch(candidate::sameFunction)) {
                    continue;
                }
                equivalent.add(candidate);
                queue.add(new RouteLabel(edge.target(), candidate, arcs, nodes, immutableVisited));
                generated++;
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }
        return new ProfileSearchCounts(expanded, generated, sampledPeak);
    }

    private boolean hasCommonSupportDomain() {
        ExactTemporalDomain common = null;
        for (Edge edge : graph.edges()) {
            ExactTemporalDomain candidate = edgeArrival(edge).domain();
            if (common == null) {
                common = candidate;
            } else if (!common.equals(candidate)) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> append(List<Integer> prefix, int value) {
        List<Integer> result = new ArrayList<>(prefix.size() + 1);
        result.addAll(prefix);
        result.add(value);
        return List.copyOf(result);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private ExactArrivalProfile edgeArrival(Edge edge) {
        ExactArrivalProfile override = exactArcArrivals.get(edge.arcId());
        return override == null ? ExactArrivalProfile.edgeArrival(edge) : override;
    }

    private static void validateFifoGraph(
            TDGraph graph, Map<Integer, ExactArrivalProfile> exactArcArrivals) {
        for (Edge edge : graph.edges()) {
            ExactArrivalProfile override = exactArcArrivals.get(edge.arcId());
            ExactArrivalProfile arrival = override == null ? ExactArrivalProfile.edgeArrival(edge) : override;
            List<ExactAffineSegment> segments = arrival.segments();
            for (ExactAffineSegment segment : segments) {
                if (segment.slope().signum() < 0) {
                    throw new IllegalArgumentException("arc_id " + edge.arcId() + ": non-FIFO arrival function");
                }
            }
            List<ExactAffineSegment> ordered = arrival.segments();
            for (int index = 1; index < ordered.size(); index++) {
                ExactAffineSegment prior = ordered.get(index - 1);
                ExactAffineSegment next = ordered.get(index);
                ExactTime priorArrival = prior.valueAtClosure(prior.interval().end());
                ExactTime nextArrival = next.valueAtClosure(next.interval().start());
                if (priorArrival.compareTo(nextArrival) > 0) {
                    throw new IllegalArgumentException(
                            "arc_id " + edge.arcId()
                                    + ": arrival function is non-FIFO across a profile boundary");
                }
            }
        }
    }

    private static final Comparator<RouteLabel> LABEL_ORDER = Comparator
            .comparing((RouteLabel label) -> label.profile().minimumValueOnClosure())
            .thenComparingInt(label -> label.arcIds().size())
            .thenComparing(RouteLabel::arcIds, FifoFastestPathProfileSearch::compareArcIds)
            .thenComparingInt(RouteLabel::node);

    private static int compareArcIds(List<Integer> left, List<Integer> right) {
        int common = Math.min(left.size(), right.size());
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(left.get(index), right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record ProfileQueueEntry(int node, ExactTime minimumArrival) {
    }

    private record ProfileSearchCounts(long expanded, long generated, long sampledPeakUsedHeapBytes) {
    }

    private record StateKey(int node, Set<Integer> visitedNodes) {
    }

    private record RouteLabel(
            int node,
            ExactArrivalProfile profile,
            List<Integer> arcIds,
            List<Integer> nodeSequence,
            Set<Integer> visitedNodes) {
    }

    public record SearchStatistics(
            long elapsedNanos,
            long searchStatesExpanded,
            long searchStatesGenerated,
            long temporalCompositions,
            long envelopeOperations,
            long sampledPeakUsedHeapBytes) {
    }

    /** Immutable all-destination profiles from one source. */
    public static final class SearchResult {
        private final int source;
        private final Domain departureDomain;
        private final Map<Integer, ExactTemporalProfile> profiles;
        private final Map<Integer, ExactArrivalProfile> exactProfiles;
        private final SearchStatistics statistics;

        private SearchResult(
                int source,
                Domain departureDomain,
                Map<Integer, ExactArrivalProfile> profiles,
                SearchStatistics statistics) {
            this.source = source;
            this.departureDomain = departureDomain;
            Map<Integer, ExactTemporalProfile> rounded = new HashMap<>();
            profiles.forEach((node, profile) -> rounded.put(node, profile.roundedLegacyProjection()));
            this.profiles = Map.copyOf(rounded);
            this.exactProfiles = Map.copyOf(profiles);
            this.statistics = statistics;
        }

        public int source() {
            return source;
        }

        public Domain departureDomain() {
            return departureDomain;
        }

        /** Rounded legacy projection. Use {@link #exactProfileTo(int)} for authoritative results. */
        public Optional<ExactTemporalProfile> profileTo(int destination) {
            return Optional.ofNullable(profiles.get(destination));
        }

        /** Rounded legacy projections for source compatibility. */
        public Map<Integer, ExactTemporalProfile> profiles() {
            return profiles;
        }

        public Optional<ExactArrivalProfile> exactProfileTo(int destination) {
            return Optional.ofNullable(exactProfiles.get(destination));
        }

        public Map<Integer, ExactArrivalProfile> exactProfiles() {
            return exactProfiles;
        }

        public SearchStatistics statistics() {
            return statistics;
        }
    }
}
