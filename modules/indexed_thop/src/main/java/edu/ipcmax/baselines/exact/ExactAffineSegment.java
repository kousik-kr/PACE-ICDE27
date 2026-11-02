package edu.ipcmax.baselines.exact;

import java.util.Objects;

/** Exact affine function {@code slope * t + intercept} on an owned interval. */
public record ExactAffineSegment(
        ExactTime slope,
        ExactTime intercept,
        ExactTemporalDomain.Interval interval) {
    public ExactAffineSegment {
        Objects.requireNonNull(slope, "slope");
        Objects.requireNonNull(intercept, "intercept");
        Objects.requireNonNull(interval, "interval");
    }

    public ExactTime valueAt(ExactTime time) {
        if (!interval.contains(time)) {
            throw new IllegalArgumentException("time is outside exact affine segment: " + time);
        }
        return valueAtClosure(time);
    }

    public ExactTime valueAtClosure(ExactTime time) {
        if (!interval.containsClosure(time)) {
            throw new IllegalArgumentException("time is outside exact affine segment closure: " + time);
        }
        return slope.multiply(time).add(intercept);
    }

    public ExactAffineSegment restrict(ExactTemporalDomain.Interval restricted) {
        ExactTemporalDomain.Interval overlap = interval.intersection(restricted);
        if (overlap == null) return null;
        return new ExactAffineSegment(slope, intercept, overlap);
    }

    public ExactAffineSegment then(ExactAffineSegment outer, ExactTemporalDomain.Interval resultInterval) {
        return new ExactAffineSegment(
                outer.slope.multiply(slope),
                outer.slope.multiply(intercept).add(outer.intercept),
                resultInterval);
    }
}
