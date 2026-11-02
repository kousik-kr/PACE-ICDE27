package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTime;

import java.math.BigInteger;
import java.util.List;

/**
 * The exact change an add made to the covered measure and the integrated preference of an envelope. Whether an
 * add improved anything is known without arithmetic; the amounts are rational sums over the ranges that rose, and
 * are computed the first time they are asked for, so a caller that never needs them (an anytime commit whose
 * decisions are credited elsewhere) pays nothing for them.
 */
public final class EnvelopeGain {
    /** A range whose maximum score rose by {@code delta}; {@code newlyCovered} when it had no winner before. */
    public record Range(ExactTime start, ExactTime end, BigInteger delta, boolean newlyCovered) { }

    public static final EnvelopeGain NONE = eager(ExactTime.ZERO, ExactTime.ZERO);

    private final List<Range> ranges;
    private final boolean improved;
    private volatile ExactTime coverage;
    private volatile ExactTime preference;

    private EnvelopeGain(List<Range> ranges, boolean improved, ExactTime coverage, ExactTime preference) {
        this.ranges = ranges;
        this.improved = improved;
        this.coverage = coverage;
        this.preference = preference;
    }

    public static EnvelopeGain eager(ExactTime coverage, ExactTime preference) {
        return new EnvelopeGain(List.of(), coverage.signum() > 0 || preference.signum() > 0, coverage, preference);
    }

    /** Gains described by the ranges that rose, evaluated on demand. */
    public static EnvelopeGain lazy(List<Range> ranges) {
        return ranges.isEmpty() ? NONE : new EnvelopeGain(List.copyOf(ranges), true, null, null);
    }

    /** True when some range was newly covered or rose in score; needs no arithmetic. */
    public boolean improved() { return improved; }

    public ExactTime coverage() {
        ExactTime value = coverage;
        if (value == null) {
            compute();
            value = coverage;
        }
        return value;
    }

    public ExactTime preference() {
        ExactTime value = preference;
        if (value == null) {
            compute();
            value = preference;
        }
        return value;
    }

    private synchronized void compute() {
        if (coverage != null && preference != null) return;
        ExactTime covered = ExactTime.ZERO;
        ExactTime integrated = ExactTime.ZERO;
        // Contiguous newly covered ranges are one gap: one subtraction measures the run.
        ExactTime runStart = null, runEnd = null;
        for (Range range : ranges) {
            if (range.newlyCovered()) {
                if (runStart != null && runEnd.equals(range.start())) {
                    runEnd = range.end();
                } else {
                    if (runStart != null) covered = covered.add(runEnd.subtract(runStart));
                    runStart = range.start();
                    runEnd = range.end();
                }
            }
            integrated = integrated.add(range.end().subtract(range.start())
                    .multiply(ExactTime.of(range.delta(), BigInteger.ONE)));
        }
        if (runStart != null) covered = covered.add(runEnd.subtract(runStart));
        preference = integrated;
        coverage = covered;
    }
}
