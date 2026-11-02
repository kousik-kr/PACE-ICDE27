package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The maximum score over the budget-feasible parts of an append-only set of route entries, as a
 * step function over exact departure times.
 *
 * <p>The exact envelope orders winners by score first, so the coverage and the integrated
 * preference of the envelope, and the exact change an added entry makes to both, depend on the
 * entries' scores and feasible domains only; arrival times and tie-breaks never enter. This
 * structure computes exactly those changes, with no arrival function and no crossing, and
 * reproduces for any add order the gains {@link IncrementalExactPreferenceEnvelope.Update} reports
 * when the same entries are added to the full envelope in that order. EGPI uses it to credit
 * improvements to routes in batch order at an epoch boundary even when the units of the batch were
 * published in completion order.</p>
 *
 * <p>Only open ranges of positive length are represented: singleton departures have measure zero
 * and never contribute to a gain. Ranges are ordered by {@link Cut}s, which decide almost every
 * comparison from double approximations with a certain margin and otherwise compare exactly, so the
 * structure is as exact as an {@code ExactTime}-keyed map and much cheaper for large coefficients.
 * Exact arithmetic is spent only on the lengths that actually change. Not thread-safe; owned by the
 * coordinator.</p>
 */
public final class ScoreStepEnvelope {
    /** Exact change of the covered measure and of the integrated preference caused by one add. */
    public record Gain(ExactTime coverage, ExactTime preference) {
        public static final Gain NONE = new Gain(ExactTime.ZERO, ExactTime.ZERO);

        public boolean improved() { return coverage.signum() > 0 || preference.signum() > 0; }
    }

    private record Step(Cut end, BigInteger score) { }

    /** A range whose maximum score rose to {@code score} by {@code delta}; {@code newlyCovered} if it was uncovered. */
    private record Raised(Cut start, Cut end, BigInteger delta, boolean newlyCovered) { }

    /** Disjoint covered open ranges (start, end) with their maximum score, keyed by start. */
    private final TreeMap<Cut, Step> steps = new TreeMap<>();
    /** Every gain, in add order. The running totals are formed on demand: their denominators grow with every add. */
    private final List<Gain> gains = new ArrayList<>();

    public ExactTime coveredMeasure() {
        ExactTime total = ExactTime.ZERO;
        for (Gain gain : gains) total = total.add(gain.coverage());
        return total;
    }

    public ExactTime integratedPreference() {
        ExactTime total = ExactTime.ZERO;
        for (Gain gain : gains) total = total.add(gain.preference());
        return total;
    }

    public int stepCount() { return steps.size(); }

    /** Adds one validated entry and returns the exact gain it contributes. */
    public Gain add(ExactPreferencePathProfile entry) {
        List<ExactTemporalDomain.Interval> feasible = entry.feasibleDomain().intervals();
        List<ExactScoreProfile.Segment> scores = entry.score().segments();
        Cut[] scoreStart = new Cut[scores.size()], scoreEnd = new Cut[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            scoreStart[i] = new Cut(scores.get(i).interval().start());
            scoreEnd[i] = new Cut(scores.get(i).interval().end());
        }
        List<Raised> raised = new ArrayList<>();
        int first = 0;
        for (ExactTemporalDomain.Interval interval : feasible) {
            if (interval.isSingleton()) continue;
            Cut feasibleStart = new Cut(interval.start()), feasibleEnd = new Cut(interval.end());
            while (first < scores.size() && scoreEnd[first].compareTo(feasibleStart) <= 0) first++;
            for (int k = first; k < scores.size() && scoreStart[k].compareTo(feasibleEnd) < 0; k++) {
                Cut start = max(feasibleStart, scoreStart[k]);
                Cut end = min(feasibleEnd, scoreEnd[k]);
                if (start.compareTo(end) < 0) raise(start, end, scores.get(k).value(), raised);
            }
        }
        ExactTime coverage = ExactTime.ZERO;
        ExactTime preference = ExactTime.ZERO;
        // Adjacent newly covered ranges are one gap: a single subtraction measures the whole run.
        Cut runStart = null, runEnd = null;
        for (Raised range : raised) {
            if (range.newlyCovered()) {
                if (runStart != null && runEnd.sameAs(range.start())) {
                    runEnd = range.end();
                } else {
                    if (runStart != null) coverage = coverage.add(runEnd.value.subtract(runStart.value));
                    runStart = range.start();
                    runEnd = range.end();
                }
            }
            ExactTime length = range.end().value.subtract(range.start().value);
            preference = preference.add(length.multiply(ExactTime.of(range.delta(), BigInteger.ONE)));
        }
        if (runStart != null) coverage = coverage.add(runEnd.value.subtract(runStart.value));
        Gain gain = new Gain(coverage, preference);
        gains.add(gain);
        return gain;
    }

    /** Raises the step function on the open range (lo, hi) to at least {@code score}, recording what rose. */
    private void raise(Cut lo, Cut hi, BigInteger score, List<Raised> raised) {
        List<Map.Entry<Cut, Step>> overlapping = new ArrayList<>();
        Map.Entry<Cut, Step> first = steps.floorEntry(lo);
        if (first != null && first.getValue().end().compareTo(lo) > 0) overlapping.add(first);
        overlapping.addAll(steps.subMap(lo, false, hi, false).entrySet());
        Cut cursor = lo;
        for (Map.Entry<Cut, Step> entry : overlapping) {
            Cut start = entry.getKey();
            Step step = entry.getValue();
            if (start.compareTo(cursor) > 0) {
                // An uncovered gap before this step is newly covered.
                raised.add(new Raised(cursor, start, score, true));
                steps.put(cursor, new Step(start, score));
            }
            Cut from = max(start, lo);
            Cut to = min(step.end(), hi);
            if (score.compareTo(step.score()) > 0) {
                raised.add(new Raised(from, to, score.subtract(step.score()), false));
                steps.remove(start);
                if (start.compareTo(from) < 0) steps.put(start, new Step(from, step.score()));
                steps.put(from, new Step(to, score));
                if (to.compareTo(step.end()) < 0) steps.put(to, new Step(step.end(), step.score()));
            }
            cursor = to;
        }
        if (cursor.compareTo(hi) < 0) {
            raised.add(new Raised(cursor, hi, score, true));
            steps.put(cursor, new Step(hi, score));
        }
    }

    private static Cut max(Cut left, Cut right) { return left.compareTo(right) >= 0 ? left : right; }
    private static Cut min(Cut left, Cut right) { return left.compareTo(right) <= 0 ? left : right; }
}
