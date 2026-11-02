package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** SICSP event provenance plus PACE replay values at its generated departure. */
public record SicspPrefBreakpointCandidate(
        SicspBreakpointCandidate.Type type,
        int eventArcId,
        double eventTime,
        OptionalDouble sourceDeparture,
        OptionalDouble actualPrefixArrival,
        OptionalDouble intendedDestinationArrival,
        OptionalDouble replayedDestinationArrival,
        OptionalDouble replayedTravelTime,
        OptionalInt replayedPreferenceScore,
        Optional<Path> path,
        boolean paceVertexSimple,
        String paceRejectionReason,
        SicspBreakpointCandidate.Status status,
        String detail) {
    public SicspPrefBreakpointCandidate {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceDeparture, "sourceDeparture");
        Objects.requireNonNull(actualPrefixArrival, "actualPrefixArrival");
        Objects.requireNonNull(intendedDestinationArrival, "intendedDestinationArrival");
        Objects.requireNonNull(replayedDestinationArrival, "replayedDestinationArrival");
        Objects.requireNonNull(replayedTravelTime, "replayedTravelTime");
        Objects.requireNonNull(replayedPreferenceScore, "replayedPreferenceScore");
        Objects.requireNonNull(path, "path");
        paceRejectionReason = paceRejectionReason == null ? "" : paceRejectionReason;
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }
}
