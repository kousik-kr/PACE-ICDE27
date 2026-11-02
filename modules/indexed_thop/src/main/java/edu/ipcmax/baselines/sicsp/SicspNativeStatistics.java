package edu.ipcmax.baselines.sicsp;

/** Query counters, phase timings, and sampled memory for SICSP auditing. */
public record SicspNativeStatistics(
        long edgesConsidered,
        long timeBreakpointsExamined,
        long weightBreakpointsExamined,
        long reverseDijkstraCalls,
        long forwardDijkstraCalls,
        long oPointsGenerated,
        long wPointsGenerated,
        long candidateRoutesGenerated,
        long duplicateCandidates,
        long dominanceComparisons,
        long dominatedPointsRemoved,
        long temporalFunctionPieces,
        long feasibleDepartureRegions,
        long candidateGenerationNanos,
        long dominanceNanos,
        long profileConstructionNanos,
        long selectionNanos,
        long queryExecutionNanos,
        long peakSampledHeapBytes) {
}
