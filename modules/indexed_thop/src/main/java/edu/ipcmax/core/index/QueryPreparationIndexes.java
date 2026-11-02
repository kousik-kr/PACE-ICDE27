package edu.ipcmax.core.index;

import java.util.Objects;

import edu.ipcmax.core.graph.TDGraph;

/**
 * Immutable bundle of the query-side indexes required before PACE execution.
 *
 * <p>The bundle is derived from the repository's canonical {@link TDGraph};
 * it does not copy graph topology or parse source files.</p>
 */
public record QueryPreparationIndexes(
        EdgeTemporalSummaryStore edgeTemporalSummaries,
        GraphPartitionMetadata graphPartition,
        ScoreSupportIndex scoreSupport) {
    public static final String SCHEMA_VERSION =
            "pace-query-preparation-index-v1";

    public QueryPreparationIndexes {
        Objects.requireNonNull(
                edgeTemporalSummaries, "edgeTemporalSummaries");
        Objects.requireNonNull(graphPartition, "graphPartition");
        Objects.requireNonNull(scoreSupport, "scoreSupport");
    }

    /** Builds the complete deterministic index bundle. */
    public static QueryPreparationIndexes build(TDGraph graph) {
        return build(graph, false);
    }

    /**
     * Builds indexes for exhaustive tiny fixtures that legally contain
     * zero-time arcs. Production dataset preparation continues to use
     * {@link #build(TDGraph)} and its strict-positive validation.
     */
    public static QueryPreparationIndexes buildAllowingZero(
            TDGraph graph) {
        return build(graph, true);
    }

    private static QueryPreparationIndexes build(
            TDGraph graph,
            boolean allowZeroTravelTime) {
        Objects.requireNonNull(graph, "graph");
        EdgeTemporalSummaryStore summaries = allowZeroTravelTime
                ? EdgeTemporalSummaryStore.buildAllowingZero(graph)
                : EdgeTemporalSummaryStore.build(graph);
        GraphPartitionMetadata partition =
                GraphPartitionMetadata.partition(graph);
        return new QueryPreparationIndexes(
                summaries,
                partition,
                ScoreSupportIndex.build(summaries, partition));
    }
}
