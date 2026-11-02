package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;

import java.util.Objects;

/** PACE preference-maximization query over a continuous departure domain. */
public record SicspPrefQuery(
        int source,
        int destination,
        Domain departureDomain,
        double travelTimeBudget) {
    public SicspPrefQuery {
        if (source <= 0 || destination <= 0) {
            throw new IllegalArgumentException("source and destination IDs must be positive");
        }
        Objects.requireNonNull(departureDomain, "departureDomain");
        if (departureDomain.isEmpty()) {
            throw new IllegalArgumentException("departure domain cannot be empty");
        }
        if (!Double.isFinite(travelTimeBudget) || travelTimeBudget < 0) {
            throw new IllegalArgumentException("travel-time budget must be finite and nonnegative");
        }
        travelTimeBudget = Domain.canonicalTime(travelTimeBudget);
    }
}
