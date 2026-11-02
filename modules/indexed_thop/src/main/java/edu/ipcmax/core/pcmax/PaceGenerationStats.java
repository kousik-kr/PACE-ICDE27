package edu.ipcmax.core.pcmax;

/**
 * Deterministic counters from the latest PACE generation.
 *
 * <p>Wall/CPU/memory observations belong to the experiment harness. Every
 * value here describes canonical algorithm work and is therefore expected to
 * be identical across worker counts.</p>
 */
public record PaceGenerationStats(
        long recursionCalls,
        long anchorsConsidered,
        long anchorsRetained,
        long connectorCandidates,
        long stitchedCandidates,
        long cacheHits,
        long cacheMisses,
        long parallelTasksStarted,
        long corridorNodes,
        long corridorEdges,
        long corridorCells,
        long scoreRelevantEdges,
        long selectedPivots,
        long connectorCalls,
        long connectorExpansions,
        long validConnectors,
        long invalidConnectors,
        long connectorCapHits,
        long candidatesGenerated,
        long candidatesRetained,
        long breakpointCapHits,
        long totalWork,
        long queryWorkCapHits,
        long frontierCells,
        long peakFrontierSize,
        long cacheLookups,
        long cacheWaits,
        int requestedWorkers,
        int observedWorkers,
        String outputChecksum) {

    /** Compatibility constructor for the legacy recursive generator. */
    public PaceGenerationStats(
            long recursionCalls,
            long anchorsConsidered,
            long anchorsRetained,
            long connectorCandidates,
            long stitchedCandidates,
            long cacheHits,
            long cacheMisses,
            long parallelTasksStarted) {
        this(
                recursionCalls,
                anchorsConsidered,
                anchorsRetained,
                connectorCandidates,
                stitchedCandidates,
                cacheHits,
                cacheMisses,
                parallelTasksStarted,
                0, 0, 0, 0, anchorsRetained,
                0, 0, connectorCandidates, 0, 0,
                stitchedCandidates, 0, 0, 0, 0, 0, 0,
                cacheHits + cacheMisses, 0,
                1, parallelTasksStarted > 0 ? 1 : 0, "");
    }

    /** Zero-valued snapshot. */
    public static PaceGenerationStats empty() {
        return new PaceGenerationStats(
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, "");
    }
}
