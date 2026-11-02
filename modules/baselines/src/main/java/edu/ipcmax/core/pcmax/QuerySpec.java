package edu.ipcmax.core.pcmax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import edu.ipcmax.core.function.Domain;

/** Query configuration for a source-destination departure interval. */
public final class QuerySpec {
    private final int source;
    private final int destination;
    private final BigDecimal departureStartExact;
    private final BigDecimal departureEndExact;
    private final double maxTravelTime;
    private final int granularityMinutes;

    /** Creates a query with integral-minute endpoints (legacy constructor). */
    public QuerySpec(
            int source,
            int destination,
            int departureStart,
            int departureEnd,
            double maxTravelTime,
            int granularityMinutes) {
        this(source, destination, BigDecimal.valueOf(departureStart),
                BigDecimal.valueOf(departureEnd), maxTravelTime,
                granularityMinutes);
    }

    /** Creates a query using the repository's exact 10^-12-minute time ticks. */
    public QuerySpec(
            int source,
            int destination,
            BigDecimal departureStart,
            BigDecimal departureEnd,
            double maxTravelTime,
            int granularityMinutes) {
        this.source = source;
        this.destination = destination;
        this.departureStartExact = canonicalEndpoint(departureStart);
        this.departureEndExact = canonicalEndpoint(departureEnd);
        this.maxTravelTime = Domain.canonicalTime(maxTravelTime);
        this.granularityMinutes = granularityMinutes;
        if (source <= 0 || destination <= 0) {
            throw new IllegalArgumentException(
                    "source and destination must be positive node ids");
        }
        if (this.departureEndExact.compareTo(this.departureStartExact) < 0) {
            throw new IllegalArgumentException(
                    "departure interval must satisfy start <= end");
        }
        if (!Double.isFinite(this.maxTravelTime) || this.maxTravelTime < 0) {
            throw new IllegalArgumentException(
                    "max travel time must be finite and non-negative");
        }
        if (granularityMinutes <= 0) {
            throw new IllegalArgumentException("granularity must be positive");
        }
    }

    private static BigDecimal canonicalEndpoint(BigDecimal value) {
        Objects.requireNonNull(value, "departure endpoint");
        BigDecimal ticks = value.setScale(Domain.TIME_SCALE, RoundingMode.UNNECESSARY);
        double canonical = Domain.canonicalTime(ticks.doubleValue());
        if (BigDecimal.valueOf(canonical).compareTo(ticks) != 0) {
            throw new IllegalArgumentException(
                    "departure endpoint is not representable by the canonical 10^-12-minute time model");
        }
        return ticks;
    }

    public int source() { return source; }
    public int destination() { return destination; }
    public double maxTravelTime() { return maxTravelTime; }
    public int granularityMinutes() { return granularityMinutes; }

    /** Legacy integral-minute accessor; fractional endpoints must use the exact accessor. */
    public int departureStart() { return departureStartExact.intValueExact(); }

    /** Legacy integral-minute accessor; fractional endpoints must use the exact accessor. */
    public int departureEnd() { return departureEndExact.intValueExact(); }

    public BigDecimal departureStartExact() { return departureStartExact; }
    public BigDecimal departureEndExact() { return departureEndExact; }

    /** Discrete departure domain for this query. */
    public Domain departureDomain() {
        return Domain.closed(departureStartExact.doubleValue(), departureEndExact.doubleValue());
    }

    /** True when the minute belongs to the configured integral departure grid. */
    public boolean isOnGrid(int minute) {
        return BigDecimal.valueOf(minute).compareTo(departureStartExact) >= 0
                && BigDecimal.valueOf(minute).compareTo(departureEndExact) <= 0
                && BigDecimal.valueOf(minute).subtract(departureStartExact)
                        .remainder(BigDecimal.valueOf(granularityMinutes)).signum() == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof QuerySpec query)) return false;
        return source == query.source
                && destination == query.destination
                && Double.compare(maxTravelTime, query.maxTravelTime) == 0
                && granularityMinutes == query.granularityMinutes
                && departureStartExact.compareTo(query.departureStartExact) == 0
                && departureEndExact.compareTo(query.departureEndExact) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, destination, departureStartExact.stripTrailingZeros(),
                departureEndExact.stripTrailingZeros(), maxTravelTime, granularityMinutes);
    }

    @Override
    public String toString() {
        return "QuerySpec[source=" + source + ", destination=" + destination
                + ", departureStart=" + departureStartExact.toPlainString()
                + ", departureEnd=" + departureEndExact.toPlainString()
                + ", maxTravelTime=" + maxTravelTime
                + ", granularityMinutes=" + granularityMinutes + "]";
    }
}
