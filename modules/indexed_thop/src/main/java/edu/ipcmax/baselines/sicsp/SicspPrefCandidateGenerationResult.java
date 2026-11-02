package edu.ipcmax.baselines.sicsp;

import java.util.List;

/** Shared SICSP search output with score and travel values replayed for PACE. */
public record SicspPrefCandidateGenerationResult(
        List<SicspPrefBreakpointCandidate> arrivalPoints,
        List<SicspPrefBreakpointCandidate> arrivalDiagnostics,
        List<SicspPrefBreakpointCandidate> preferencePoints,
        List<SicspPrefBreakpointCandidate> preferenceDiagnostics,
        long edgesConsidered,
        long timeBreakpointsExamined,
        long preferenceBreakpointsExamined,
        long reverseDijkstraCalls,
        long forwardDijkstraCalls,
        long duplicateCandidates,
        long candidateGenerationNanos,
        long peakSampledHeapBytes) {
    public SicspPrefCandidateGenerationResult {
        arrivalPoints = List.copyOf(arrivalPoints);
        arrivalDiagnostics = List.copyOf(arrivalDiagnostics);
        preferencePoints = List.copyOf(preferencePoints);
        preferenceDiagnostics = List.copyOf(preferenceDiagnostics);
    }
}
