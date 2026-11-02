package edu.ipcmax.core.cache;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;

/**
 * Canonical per-query memoization key for PACE recursion.
 */
public final class MemoKey {
    private final int source;
    private final int destination;
    private final Domain domain;
    private final String normalizedDomain;
    private final int remainingAnchorBudget;
    private final PaceExecutionPolicy policy;
    private final int effectiveAnchorLimit;
    private final int effectiveCandidateLimit;
    private final double travelBudget;
    private final Domain queryHorizon;
    private final String normalizedQueryHorizon;
    private final String graphVersion;
    private final String anchorIndexVersion;

    /** Creates a complete PACE memoization key. */
    public MemoKey(
            int source,
            int destination,
            Domain domain,
            int remainingAnchorBudget,
            PaceExecutionPolicy policy,
            int effectiveAnchorLimit,
            int effectiveCandidateLimit,
            double travelBudget,
            Domain queryHorizon,
            String graphVersion,
            String anchorIndexVersion) {
        this.source = source;
        this.destination = destination;
        this.domain = Objects.requireNonNull(domain, "domain");
        this.normalizedDomain = canonicalDomain(domain);
        this.remainingAnchorBudget = remainingAnchorBudget;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.effectiveAnchorLimit = effectiveAnchorLimit;
        this.effectiveCandidateLimit = effectiveCandidateLimit;
        this.travelBudget = canonicalTime(travelBudget);
        this.queryHorizon = Objects.requireNonNull(queryHorizon, "queryHorizon");
        this.normalizedQueryHorizon = canonicalDomain(queryHorizon);
        this.graphVersion = Objects.requireNonNull(graphVersion, "graphVersion");
        this.anchorIndexVersion = Objects.requireNonNull(anchorIndexVersion, "anchorIndexVersion");
        if (remainingAnchorBudget < 0) {
            throw new IllegalArgumentException("remaining anchor budget cannot be negative");
        }
        if (travelBudget < 0 || !Double.isFinite(travelBudget)) {
            throw new IllegalArgumentException("travel budget must be finite and nonnegative");
        }
    }

    /**
     * Backward-compatible constructor for the former interval-labeling key shape.
     */
    public MemoKey(
            int source,
            int destination,
            Domain domain,
            String departureProfileFingerprint,
            String deadlineProfileFingerprint,
            int theta,
            boolean exactMode,
            int pivotStep,
            boolean mergeBreakpoints,
            int k) {
        this(
                source,
                destination,
                domain,
                theta,
                exactMode ? PaceExecutionPolicy.PACE_X : PaceExecutionPolicy.PACE_B,
                exactMode ? Integer.MAX_VALUE : Math.max(1, pivotStep),
                exactMode ? Integer.MAX_VALUE : Math.max(1, k),
                0,
                domain,
                "legacy-departure=" + departureProfileFingerprint + ";deadline=" + deadlineProfileFingerprint,
                "legacy-pivot=" + pivotStep + ";merge=" + mergeBreakpoints);
    }

    public int source() {
        return source;
    }

    public int destination() {
        return destination;
    }

    public Domain domain() {
        return domain;
    }

    public int remainingAnchorBudget() {
        return remainingAnchorBudget;
    }

    /** Backward-compatible alias. */
    public int theta() {
        return remainingAnchorBudget;
    }

    public PaceExecutionPolicy policy() {
        return policy;
    }

    public int effectiveAnchorLimit() {
        return effectiveAnchorLimit;
    }

    public int effectiveCandidateLimit() {
        return effectiveCandidateLimit;
    }

    public double travelBudget() {
        return travelBudget;
    }

    public Domain queryHorizon() {
        return queryHorizon;
    }

    public String graphVersion() {
        return graphVersion;
    }

    public String anchorIndexVersion() {
        return anchorIndexVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemoKey key)) {
            return false;
        }
        return source == key.source
                && destination == key.destination
                && remainingAnchorBudget == key.remainingAnchorBudget
                && effectiveAnchorLimit == key.effectiveAnchorLimit
                && effectiveCandidateLimit == key.effectiveCandidateLimit
                && Double.compare(travelBudget, key.travelBudget) == 0
                && policy == key.policy
                && normalizedDomain.equals(key.normalizedDomain)
                && normalizedQueryHorizon.equals(key.normalizedQueryHorizon)
                && graphVersion.equals(key.graphVersion)
                && anchorIndexVersion.equals(key.anchorIndexVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                source,
                destination,
                normalizedDomain,
                remainingAnchorBudget,
                policy,
                effectiveAnchorLimit,
                effectiveCandidateLimit,
                travelBudget,
                normalizedQueryHorizon,
                graphVersion,
                anchorIndexVersion);
    }

    @Override
    public String toString() {
        return "MemoKey[" + source + "->" + destination
                + ",D=" + normalizedDomain
                + ",ell=" + remainingAnchorBudget
                + ",mode=" + policy
                + ",L=" + effectiveAnchorLimit
                + ",K=" + effectiveCandidateLimit
                + ",B=" + travelBudget
                + ",Tq=" + normalizedQueryHorizon + ']';
    }

    private static String canonicalDomain(Domain domain) {
        Domain normalized = domain.intervals().isEmpty()
                ? Domain.empty()
                : Domain.of(domain.intervals().toArray(Domain.Interval[]::new));
        StringBuilder result = new StringBuilder();
        for (Domain.Interval interval : normalized.intervals()) {
            if (!result.isEmpty()) {
                result.append(';');
            }
            result.append(interval.startInclusive() ? '[' : '(')
                    .append(canonicalNumber(interval.start()))
                    .append(':')
                    .append(canonicalNumber(interval.end()))
                    .append(interval.endInclusive() ? ']' : ')');
        }
        return result.toString();
    }

    private static double canonicalTime(double value) {
        return BigDecimal.valueOf(value).setScale(Domain.TIME_SCALE, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static String canonicalNumber(double value) {
        return BigDecimal.valueOf(value)
                .setScale(Domain.TIME_SCALE, RoundingMode.HALF_EVEN)
                .stripTrailingZeros()
                .toPlainString();
    }
}
