package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

import java.util.List;
import java.util.Objects;

/** W-point candidates, retained skyline points, and event diagnostics. */
public record SicspWeightCandidates(
        List<SicspBreakpointCandidate> generatedPoints,
        List<SicspBreakpointCandidate> retainedPoints,
        List<SicspBreakpointCandidate> dominatedPoints,
        List<SicspBreakpointCandidate> eventDiagnostics,
        List<WPointPiece> weightPieces) {
    private record WPointGroup(Path path, double weight) {
    }

    /** Horizontal W-point connection for one path and one accumulated weight. */
    public record WPointPiece(Path path, double startDeparture, double endDeparture, double weight) {
    }

    public SicspWeightCandidates {
        generatedPoints = List.copyOf(Objects.requireNonNull(generatedPoints, "generatedPoints"));
        retainedPoints = List.copyOf(Objects.requireNonNull(retainedPoints, "retainedPoints"));
        dominatedPoints = List.copyOf(Objects.requireNonNull(dominatedPoints, "dominatedPoints"));
        eventDiagnostics = List.copyOf(Objects.requireNonNull(eventDiagnostics, "eventDiagnostics"));
        weightPieces = List.copyOf(Objects.requireNonNull(weightPieces, "weightPieces"));
    }

    /** Follows the prose rule: connect retained W-points only when their weights agree. */
    public static List<WPointPiece> constructWeightPieces(List<SicspBreakpointCandidate> retainedPoints) {
        var byPathAndWeight = new java.util.LinkedHashMap<WPointGroup, List<SicspBreakpointCandidate>>();
        for (SicspBreakpointCandidate candidate : retainedPoints) {
            if (candidate.type() != SicspBreakpointCandidate.Type.WEIGHT_BREAKPOINT
                    || candidate.path().isEmpty()
                    || candidate.sourceDeparture().isEmpty()
                    || candidate.accumulatedWeight().isEmpty()) {
                continue;
            }
            WPointGroup key = new WPointGroup(
                    candidate.path().orElseThrow(), candidate.accumulatedWeight().orElseThrow());
            byPathAndWeight.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(candidate);
        }
        List<WPointPiece> result = new java.util.ArrayList<>();
        for (List<SicspBreakpointCandidate> group : byPathAndWeight.values()) {
            group.sort(java.util.Comparator.comparingDouble(
                    candidate -> candidate.sourceDeparture().orElseThrow()));
            for (int i = 1; i < group.size(); i++) {
                SicspBreakpointCandidate left = group.get(i - 1);
                SicspBreakpointCandidate right = group.get(i);
                double start = left.sourceDeparture().orElseThrow();
                double end = right.sourceDeparture().orElseThrow();
                if (end > start) {
                    result.add(new WPointPiece(left.path().orElseThrow(), start, end,
                            left.accumulatedWeight().orElseThrow()));
                }
            }
        }
        result.sort(java.util.Comparator.comparingDouble(WPointPiece::startDeparture)
                .thenComparing(WPointPiece::path, SicspPathOrder::compare)
                .thenComparingDouble(WPointPiece::weight));
        return List.copyOf(result);
    }
}
