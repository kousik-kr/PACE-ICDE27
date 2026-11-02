package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;

import java.util.Objects;

/** Native SICSP interval query: earliest arrival subject to accumulated weight. */
public record SicspNativeQuery(
        int source,
        int destination,
        Domain departureDomain,
        double weightBound) {
    public SicspNativeQuery {
        if (source <= 0 || destination <= 0) {
            throw new IllegalArgumentException("source and destination IDs must be positive");
        }
        Objects.requireNonNull(departureDomain, "departureDomain");
        if (departureDomain.isEmpty()) {
            throw new IllegalArgumentException("departure interval cannot be empty");
        }
        if (!Double.isFinite(weightBound) || weightBound < 0.0) {
            throw new IllegalArgumentException("native weight bound must be finite and nonnegative");
        }
        weightBound = Domain.canonicalTime(weightBound);
    }

    public SicspNativeQuery(int source, int destination, double start, double end, double weightBound) {
        this(source, destination, Domain.closed(start, end), weightBound);
        if (end < start) {
            throw new IllegalArgumentException("departure interval end must be >= start");
        }
    }
}
