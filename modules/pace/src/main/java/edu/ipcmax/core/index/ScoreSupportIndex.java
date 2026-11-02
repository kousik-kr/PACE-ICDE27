package edu.ipcmax.core.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore.EdgeTemporalSummary;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore.PositiveScoreInterval;

/**
 * Cell-local index of score-bearing directed arcs and positive support.
 */
public final class ScoreSupportIndex {
    private final EdgeTemporalSummaryStore summaries;
    private final GraphPartitionMetadata partition;
    private final Map<String, List<Integer>> positiveArcsByCell;

    private ScoreSupportIndex(
            EdgeTemporalSummaryStore summaries,
            GraphPartitionMetadata partition,
            Map<String, List<Integer>> positiveArcsByCell) {
        this.summaries = summaries;
        this.partition = partition;
        this.positiveArcsByCell = positiveArcsByCell;
    }

    /** Builds a deterministic per-cell score index. */
    public static ScoreSupportIndex build(
            EdgeTemporalSummaryStore summaries,
            GraphPartitionMetadata partition) {
        Objects.requireNonNull(summaries, "summaries");
        Objects.requireNonNull(partition, "partition");
        TreeMap<String, List<Integer>> result = new TreeMap<>();
        for (GraphPartitionMetadata.Cell cell : partition.cells()) {
            List<Integer> positive = new ArrayList<>();
            for (int arcId : cell.directedArcIds()) {
                if (summaries.summary(arcId).maximumScore() > 0) {
                    positive.add(arcId);
                }
            }
            result.put(cell.cellId(), List.copyOf(positive));
        }
        return new ScoreSupportIndex(
                summaries,
                partition,
                Collections.unmodifiableMap(result));
    }

    public GraphPartitionMetadata partition() {
        return partition;
    }

    /** Score-bearing directed arcs in ascending arc-ID order. */
    public List<Integer> scoreBearingArcIds(String cellId) {
        List<Integer> arcs = positiveArcsByCell.get(cellId);
        if (arcs == null) {
            throw new IllegalArgumentException("unknown stable cell ID: " + cellId);
        }
        return arcs;
    }

    /**
     * Deterministic top-k retrieval for a requested exact time range.
     *
     * <p>Order is maximum score in the requested range descending, followed by
     * stable directed arc ID ascending.</p>
     */
    public List<RankedScoreEdge> topK(
            String cellId,
            Domain requestedTimeRange,
            int k) {
        Objects.requireNonNull(requestedTimeRange, "requestedTimeRange");
        if (requestedTimeRange.isEmpty()) {
            throw new IllegalArgumentException("requested time range cannot be empty");
        }
        if (k < 0) {
            throw new IllegalArgumentException("k cannot be negative");
        }
        List<RankedScoreEdge> ranked = new ArrayList<>();
        for (int arcId : scoreBearingArcIds(cellId)) {
            EdgeTemporalSummary summary = summaries.summary(arcId);
            int maximum = 0;
            List<PositiveScoreInterval> overlapping = new ArrayList<>();
            for (PositiveScoreInterval interval : summary.positiveScoreIntervals()) {
                if (interval.overlaps(requestedTimeRange)) {
                    overlapping.add(interval);
                    maximum = Math.max(maximum, interval.score());
                }
            }
            if (maximum > 0) {
                ranked.add(new RankedScoreEdge(
                        arcId, maximum, List.copyOf(overlapping)));
            }
        }
        ranked.sort(Comparator
                .comparingInt(RankedScoreEdge::maximumScore).reversed()
                .thenComparingInt(RankedScoreEdge::arcId));
        return List.copyOf(ranked.subList(0, Math.min(k, ranked.size())));
    }

    /**
     * Verifies that variants ordered from lowest to highest density contain
     * nested score-bearing directed-arc sets.
     */
    public static void requireNested(List<ScoreSupportIndex> variants) {
        Objects.requireNonNull(variants, "variants");
        Set<Integer> previous = Set.of();
        for (int index = 0; index < variants.size(); index++) {
            ScoreSupportIndex variant = Objects.requireNonNull(
                    variants.get(index), "variant");
            Set<Integer> current = new HashSet<>();
            variant.positiveArcsByCell.values().forEach(current::addAll);
            if (!current.containsAll(previous)) {
                Set<Integer> missing = new HashSet<>(previous);
                missing.removeAll(current);
                throw new IllegalArgumentException(
                        "score-density variant " + index
                                + " is not nested; missing directed arc IDs "
                                + missing.stream().sorted().limit(10).toList());
            }
            previous = current;
        }
    }

    /** One deterministic time-range ranking result. */
    public record RankedScoreEdge(
            int arcId,
            int maximumScore,
            List<PositiveScoreInterval> overlappingPositiveIntervals) {
        public RankedScoreEdge {
            if (arcId < 0 || maximumScore <= 0) {
                throw new IllegalArgumentException("invalid ranked score edge");
            }
            overlappingPositiveIntervals = List.copyOf(
                    overlappingPositiveIntervals);
        }
    }
}
