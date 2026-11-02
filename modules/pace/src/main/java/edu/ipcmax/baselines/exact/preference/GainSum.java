package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTime;

import java.util.ArrayList;
import java.util.List;

/**
 * The running total of {@link ScoreStepEnvelope.Gain}s credited to one route. The total is never formed
 * exactly while the search runs: whether it is positive is known from the gains, its display doubles come from
 * the sum of the gains' fixed-point approximations (certified, exact when the approximation cannot decide the
 * rounding), and the exact sums are formed on demand from the recorded gains. Not thread-safe; owned by the
 * coordinator until the search ends.
 */
public final class GainSum {
    private final List<ScoreStepEnvelope.Gain> gains = new ArrayList<>();
    private FixedPoint.Approx coverage = FixedPoint.Approx.ZERO;
    private FixedPoint.Approx preference = FixedPoint.Approx.ZERO;
    private boolean improved;

    public void add(ScoreStepEnvelope.Gain gain) {
        if (!gain.improved()) return;
        gains.add(gain);
        improved = true;
        coverage = coverage.plus(gain.approximateCoverage());
        preference = preference.plus(gain.approximatePreference());
    }

    public boolean isEmpty() { return gains.isEmpty(); }

    /** True when the total coverage or preference is positive. */
    public boolean improved() { return improved; }

    /** {@code coverage().toDisplayDouble()}, without forming the exact total when the approximation decides it. */
    public double coverageDisplay() {
        return gains.isEmpty() ? 0 : FixedPoint.display(coverage, this::coverage);
    }

    /** {@code preference().toDisplayDouble()}, without forming the exact total when the approximation decides it. */
    public double preferenceDisplay() {
        return gains.isEmpty() ? 0 : FixedPoint.display(preference, this::preference);
    }

    /** The exact total coverage gain. */
    public ExactTime coverage() {
        ExactTime total = ExactTime.ZERO;
        for (ScoreStepEnvelope.Gain gain : gains) total = total.add(gain.coverage());
        return total;
    }

    /** The exact total preference gain. */
    public ExactTime preference() {
        ExactTime total = ExactTime.ZERO;
        for (ScoreStepEnvelope.Gain gain : gains) total = total.add(gain.preference());
        return total;
    }
}
