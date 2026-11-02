package edu.ipcmax.baselines.sicsp;

import java.util.List;
import java.util.Objects;

/** O-point candidates, retained points, and rejected event diagnostics. */
public record SicspArrivalCandidates(
        List<SicspBreakpointCandidate> generatedPoints,
        List<SicspBreakpointCandidate> retainedPoints,
        List<SicspBreakpointCandidate> dominatedPoints,
        List<SicspBreakpointCandidate> eventDiagnostics) {
    public SicspArrivalCandidates {
        generatedPoints = List.copyOf(Objects.requireNonNull(generatedPoints, "generatedPoints"));
        retainedPoints = List.copyOf(Objects.requireNonNull(retainedPoints, "retainedPoints"));
        dominatedPoints = List.copyOf(Objects.requireNonNull(dominatedPoints, "dominatedPoints"));
        eventDiagnostics = List.copyOf(Objects.requireNonNull(eventDiagnostics, "eventDiagnostics"));
    }
}
