package edu.ipcmax.baselines.sicsp;

import java.util.List;

/** Raw, pre-dominance breakpoint candidates produced by the shared SICSP search core. */
public record SicspCandidateGenerationResult(
        List<SicspBreakpointCandidate> arrivalPoints,
        List<SicspBreakpointCandidate> arrivalDiagnostics,
        List<SicspBreakpointCandidate> weightPoints,
        List<SicspBreakpointCandidate> weightDiagnostics,
        long edgesConsidered,
        long timeBreakpointsExamined,
        long weightBreakpointsExamined,
        long reverseDijkstraCalls,
        long forwardDijkstraCalls,
        long duplicateCandidates,
        long candidateGenerationNanos,
        long peakSampledHeapBytes) {
    public SicspCandidateGenerationResult {
        arrivalPoints = List.copyOf(arrivalPoints);
        arrivalDiagnostics = List.copyOf(arrivalDiagnostics);
        weightPoints = List.copyOf(weightPoints);
        weightDiagnostics = List.copyOf(weightDiagnostics);
    }
}
