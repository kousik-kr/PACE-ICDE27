package edu.ipcmax.core.pcmax;

import java.util.BitSet;
import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;

/**
 * Immutable temporal forward/backward label alternative.
 *
 * <p>The profile is deliberately retained instead of reducing a label to its
 * fastest travel time.  Its induced arrival and score functions, exact path
 * handle, and membership bit sets are therefore available to a later anchor
 * join without rebuilding the path.  Bit sets are defensively copied at the
 * API boundary so parallel query workers cannot mutate a shared label.</p>
 */
public record TemporalLabelAlternative(
        CandidateProfile profile,
        Domain feasibleDepartureDomain,
        double residualBudget,
        BitSet vertexMembership,
        BitSet edgeMembership,
        String deterministicKey,
        boolean capTruncated) {
    public TemporalLabelAlternative {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(
                feasibleDepartureDomain,
                "feasibleDepartureDomain");
        if (feasibleDepartureDomain.isEmpty()
                || !Double.isFinite(residualBudget)
                || residualBudget < 0) {
            throw new IllegalArgumentException(
                    "invalid temporal label budget/domain");
        }
        Objects.requireNonNull(vertexMembership, "vertexMembership");
        Objects.requireNonNull(edgeMembership, "edgeMembership");
        Objects.requireNonNull(deterministicKey, "deterministicKey");
        vertexMembership = (BitSet) vertexMembership.clone();
        edgeMembership = (BitSet) edgeMembership.clone();
    }

    @Override
    public BitSet vertexMembership() {
        return (BitSet) vertexMembership.clone();
    }

    @Override
    public BitSet edgeMembership() {
        return (BitSet) edgeMembership.clone();
    }
}
