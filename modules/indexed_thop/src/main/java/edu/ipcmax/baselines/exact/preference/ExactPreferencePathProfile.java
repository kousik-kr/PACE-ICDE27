package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.validate.Path;

import java.util.Objects;

/** Exact route replay, edge-entry score sum, and travel-budget support. */
public record ExactPreferencePathProfile(
        Path path,
        ExactArrivalProfile arrival,
        ExactScoreProfile score,
        ExactTemporalDomain feasibleDomain,
        ExactTime budget,
        long scoreEventPreimages,
        long budgetRootOperations) {
    public ExactPreferencePathProfile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(feasibleDomain, "feasibleDomain");
        Objects.requireNonNull(budget, "budget");
        if (!arrival.domain().equals(score.domain())) {
            throw new IllegalArgumentException("exact arrival and score supports must agree");
        }
        if (!feasibleDomain.difference(arrival.domain()).isEmpty()) {
            throw new IllegalArgumentException("feasible domain must be contained in route support");
        }
    }

    public boolean feasibleAt(ExactTime departure) {
        return feasibleDomain.contains(departure);
    }
}
