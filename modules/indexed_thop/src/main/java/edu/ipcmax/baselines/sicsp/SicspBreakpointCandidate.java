package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Auditable event candidate from ICSP_Arr or the inferred ICSP_Wgh routine. */
public record SicspBreakpointCandidate(
        Type type,
        int eventArcId,
        double eventTime,
        OptionalDouble sourceDeparture,
        OptionalDouble actualPrefixArrival,
        OptionalDouble intendedDestinationArrival,
        OptionalDouble replayedDestinationArrival,
        OptionalDouble accumulatedWeight,
        Optional<Path> path,
        Status status,
        String detail) {
    public enum Type {
        ARRIVAL_BREAKPOINT,
        WEIGHT_BREAKPOINT
    }

    public enum Status {
        RETAINED,
        NO_PREFIX,
        NO_SUFFIX,
        EVENT_OUTSIDE_EDGE_DOMAIN,
        SOURCE_DEPARTURE_OUTSIDE_QUERY,
        PREFIX_EVENT_MISMATCH,
        PATH_REPLAY_FAILED,
        FORWARD_ARRIVAL_MISMATCH,
        DUPLICATE_CANDIDATE
    }

    public SicspBreakpointCandidate {
        Objects.requireNonNull(type, "type");
        if (eventArcId < 0 || !Double.isFinite(eventTime)) {
            throw new IllegalArgumentException("candidate event must identify a finite edge breakpoint");
        }
        Objects.requireNonNull(sourceDeparture, "sourceDeparture");
        Objects.requireNonNull(actualPrefixArrival, "actualPrefixArrival");
        Objects.requireNonNull(intendedDestinationArrival, "intendedDestinationArrival");
        Objects.requireNonNull(replayedDestinationArrival, "replayedDestinationArrival");
        Objects.requireNonNull(accumulatedWeight, "accumulatedWeight");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public boolean isTemporallyConsistent() {
        return status == Status.RETAINED
                || status == Status.DUPLICATE_CANDIDATE
                || (status == Status.SOURCE_DEPARTURE_OUTSIDE_QUERY
                && !detail.contains("instead of event")
                && !detail.contains("replay failed")
                && !detail.contains("differs from forward Dijkstra"));
    }

    /** A consistent event route can supply a profile even when its LST is outside the query interval. */
    public boolean isProfileRouteCandidate() {
        return isTemporallyConsistent() && path.isPresent()
                && replayedDestinationArrival.isPresent() && accumulatedWeight.isPresent();
    }

    public boolean isGeneratedPoint() {
        return sourceDeparture.isPresent()
                && replayedDestinationArrival.isPresent()
                && accumulatedWeight.isPresent()
                && path.isPresent()
                && (status == Status.RETAINED || status == Status.DUPLICATE_CANDIDATE);
    }
}
