package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Function-valued forward or reverse-backward search used by incremental THop.
 * Reverse search traverses original incoming arcs and composes each arc's
 * arrival map before the already-computed suffix profile.
 */
final class PrunedFifoProfileSearch {
    enum Direction {
        FORWARD,
        BACKWARD
    }

    private PrunedFifoProfileSearch() {
    }

    static SearchResult run(
            TDGraph graph,
            int root,
            ExactArrivalProfile rootProfile,
            Direction direction,
            BiPredicate<Integer, ExactArrivalProfile> shouldExpand) {
        return run(graph, root, rootProfile, direction, Map.of(), shouldExpand);
    }

    static SearchResult run(
            TDGraph graph,
            int root,
            ExactArrivalProfile rootProfile,
            Direction direction,
            Map<Integer, ExactArrivalProfile> exactArcArrivals,
            BiPredicate<Integer, ExactArrivalProfile> shouldExpand) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(rootProfile, "rootProfile");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(shouldExpand, "shouldExpand");
        Objects.requireNonNull(exactArcArrivals, "exactArcArrivals");
        graph.node(root);

        long started = System.nanoTime();
        long initialHeap = usedHeapBytes();
        long[] workCounts = {0, 0};
        Map<Integer, ExactArrivalProfile> best = new HashMap<>();
        best.put(root, rootProfile);
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator
                .comparing(QueueEntry::minimumArrival)
                .thenComparingInt(QueueEntry::node));
        Set<Integer> queued = new HashSet<>();
        queue.add(new QueueEntry(root, rootProfile.minimumValueOnClosure()));
        queued.add(root);

        long expanded = 0;
        long generated = 1;
        long skipped = 0;
        long sampledPeak = initialHeap;
        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install((name, amount) -> {
            if (name.equals("thop_temporal_compositions")) {
                workCounts[0] += amount;
            } else if (name.equals("thop_envelope_operations")) {
                workCounts[1] += amount;
            }
        })) {
            while (!queue.isEmpty()) {
                QueueEntry entry = queue.poll();
                queued.remove(entry.node());
                ExactArrivalProfile prefixOrSuffix = best.get(entry.node());
                expanded++;
                if (!shouldExpand.test(entry.node(), prefixOrSuffix)) {
                    skipped++;
                    continue;
                }

                var edges = direction == Direction.FORWARD
                        ? graph.outgoingEdges(entry.node())
                        : graph.incomingEdges(entry.node());
                for (Edge edge : edges) {
                    ExactArrivalProfile edgeArrival = exactArcArrivals.get(edge.arcId());
                    if (edgeArrival == null) edgeArrival = ExactArrivalProfile.edgeArrival(edge);
                    int adjacent = direction == Direction.FORWARD ? edge.target() : edge.source();
                    ExactArrivalProfile candidate = direction == Direction.FORWARD
                            ? prefixOrSuffix.composeAfter(edgeArrival)
                            : edgeArrival.composeAfter(prefixOrSuffix);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    ExactArrivalProfile current = best.get(adjacent);
                    ExactArrivalProfile merged = current == null
                            ? candidate
                            : current.pointwiseMinimum(candidate);
                    if (current != null && current.sameFunction(merged)) {
                        continue;
                    }
                    best.put(adjacent, merged);
                    generated++;
                    if (queued.add(adjacent)) {
                        queue.add(new QueueEntry(adjacent, merged.minimumValueOnClosure()));
                    }
                    sampledPeak = Math.max(sampledPeak, usedHeapBytes());
                }
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }
        }

        return new SearchResult(
                Map.copyOf(best),
                new SearchStatistics(
                        System.nanoTime() - started,
                        expanded,
                        generated,
                        skipped,
                        workCounts[0],
                        workCounts[1],
                        sampledPeak));
    }

    static ExactTemporalDomain terminalArrivalDomain(TDGraph graph) {
        return terminalArrivalDomain(graph, Map.of());
    }

    static ExactTemporalDomain terminalArrivalDomain(
            TDGraph graph, Map<Integer, ExactArrivalProfile> exactArcArrivals) {
        ExactTemporalDomain result = ExactTemporalDomain.empty();
        for (Edge edge : graph.edges()) {
            ExactArrivalProfile arrival = exactArcArrivals.get(edge.arcId());
            if (arrival == null) arrival = ExactArrivalProfile.edgeArrival(edge);
            result = result.union(arrival.imageDomain());
        }
        return result.isEmpty()
                ? ExactTemporalDomain.of(ExactTemporalDomain.Interval.singleton(ExactTime.ZERO))
                : result;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record QueueEntry(int node, ExactTime minimumArrival) {
    }

    record SearchStatistics(
            long elapsedNanos,
            long searchStatesExpanded,
            long searchStatesGenerated,
            long searchExpansionsSkipped,
            long temporalCompositions,
            long envelopeOperations,
            long sampledPeakUsedHeapBytes) {
    }

    record SearchResult(
            Map<Integer, ExactArrivalProfile> profiles,
            SearchStatistics statistics) {
    }
}
