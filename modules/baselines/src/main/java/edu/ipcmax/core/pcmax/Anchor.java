package edu.ipcmax.core.pcmax;

import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;

/**
 * A single directed score-anchor edge and its query-horizon domains.
 *
 * @param edge stable graph arc
 * @param validEntryDomain {@code V_a}, where both arrival and score are defined
 * @param positiveEntryDomain {@code D_a+}, where the score is strictly positive
 * @param lowerTravelTime minimum anchor travel time over {@code V_a}
 */
public record Anchor(
        Edge edge,
        Domain validEntryDomain,
        Domain positiveEntryDomain,
        double lowerTravelTime) {
    /** Creates a validated single-edge anchor. */
    public Anchor {
        Objects.requireNonNull(edge, "edge");
        Objects.requireNonNull(validEntryDomain, "validEntryDomain");
        Objects.requireNonNull(positiveEntryDomain, "positiveEntryDomain");
        if (validEntryDomain.isEmpty()) {
            throw new IllegalArgumentException("anchor valid-entry domain cannot be empty");
        }
        if (positiveEntryDomain.isEmpty()) {
            throw new IllegalArgumentException("anchor positive-entry domain cannot be empty");
        }
        if (!positiveEntryDomain.difference(validEntryDomain).isEmpty()) {
            throw new IllegalArgumentException("anchor positive domain must be contained in its valid domain");
        }
        if (!Double.isFinite(lowerTravelTime) || lowerTravelTime < 0) {
            throw new IllegalArgumentException("anchor lower travel time must be finite and nonnegative");
        }
    }

    /** Stable unique arc identifier. */
    public int stableArcId() {
        return edge.arcId();
    }

    /** Anchor tail {@code x}. */
    public int source() {
        return edge.source();
    }

    /** Anchor head {@code y}. */
    public int target() {
        return edge.target();
    }

    /** Concise alias for {@link #validEntryDomain()}. */
    public Domain validDomain() {
        return validEntryDomain;
    }

    /** Concise alias for {@link #positiveEntryDomain()}. */
    public Domain positiveDomain() {
        return positiveEntryDomain;
    }
}
