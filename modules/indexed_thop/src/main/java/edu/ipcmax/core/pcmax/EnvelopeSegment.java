package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.validate.Path;

/**
 * One elementary interval in the extracted departure-time-to-path envelope.
 */
public record EnvelopeSegment(Domain.Interval interval, CandidateProfile candidate) {
    /**
     * Creates a segment. A null candidate represents bottom.
     */
    public EnvelopeSegment {
        if (interval == null) {
            throw new IllegalArgumentException("envelope interval is required");
        }
    }

    /**
     * True when this segment has a feasible path.
     */
    public boolean found() {
        return candidate != null;
    }

    /**
     * True when this segment explicitly represents an uncovered NO_PATH cell.
     */
    public boolean noPath() {
        return candidate == null;
    }

    /**
     * Exact endpoint-aware containment.
     */
    public boolean contains(double departure) {
        return interval.contains(departure);
    }

    /**
     * Materialized path, or the empty path for bottom.
     */
    public Path path() {
        return found() ? candidate.pathPointer().toPath() : Path.empty();
    }

    /**
     * True when both segments select the same stable path, including NO_PATH.
     */
    public boolean sameAssignment(EnvelopeSegment other) {
        if (candidate == null || other.candidate == null) {
            return candidate == null && other.candidate == null;
        }
        return candidate.stablePathId().equals(other.candidate.stablePathId());
    }
}
