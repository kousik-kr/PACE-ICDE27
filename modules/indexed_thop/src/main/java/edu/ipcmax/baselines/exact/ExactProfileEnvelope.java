package edu.ipcmax.baselines.exact;

import edu.ipcmax.core.profile.TemporalProfileWork;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Exact lower-envelope, dominance and crossing operations for rational profiles. */
public final class ExactProfileEnvelope {
    private ExactProfileEnvelope() {
    }

    public static ExactArrivalProfile minimum(ExactArrivalProfile left, ExactArrivalProfile right) {
        return minimum(left, right, true);
    }

    /** Reference all-pairs implementation retained for differential testing. */
    static ExactArrivalProfile minimumAllPairs(ExactArrivalProfile left, ExactArrivalProfile right) {
        return minimum(left, right, false);
    }

    private static ExactArrivalProfile minimum(
            ExactArrivalProfile left, ExactArrivalProfile right, boolean sweep) {
        if (ThopProfile.ENABLED && !left.isEmpty() && !right.isEmpty()) {
            long started = ThopProfile.begin();
            try {
                return minimumUninstrumented(left, right, sweep);
            } finally {
                ThopProfile.end(ThopProfile.P_MINIMUM, started);
            }
        }
        return minimumUninstrumented(left, right, sweep);
    }

    private static ExactArrivalProfile minimumUninstrumented(
            ExactArrivalProfile left, ExactArrivalProfile right, boolean sweep) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        TemporalProfileWork.increment("thop_envelope_operations");

        TreeSet<ExactTime> cuts = new TreeSet<>();
        addEndpoints(cuts, left);
        addEndpoints(cuts, right);
        List<ExactAffineSegment> leftSegments = left.segments();
        List<ExactAffineSegment> rightSegments = right.segments();
        if (sweep) {
            // Both lists are sorted, disjoint interval sequences. Skip pairs
            // whose closures are separated before doing rational intersections.
            int firstPossibleRight = 0;
            for (ExactAffineSegment a : leftSegments) {
                while (firstPossibleRight < rightSegments.size()
                        && rightSegments.get(firstPossibleRight).interval().end()
                                .compareTo(a.interval().start()) < 0) {
                    firstPossibleRight++;
                }
                for (int j = firstPossibleRight; j < rightSegments.size(); j++) {
                    ExactAffineSegment b = rightSegments.get(j);
                    if (b.interval().start().compareTo(a.interval().end()) > 0) break;
                    addCrossing(cuts, a, b);
                }
            }
        } else {
            for (ExactAffineSegment a : leftSegments) {
                for (ExactAffineSegment b : rightSegments) addCrossing(cuts, a, b);
            }
        }

        List<ExactTime> ordered = List.copyOf(cuts);
        List<ExactAffineSegment> result = new ArrayList<>();
        for (int i = 0; i + 1 < ordered.size(); i++) {
            ExactTime start = ordered.get(i);
            ExactTime end = ordered.get(i + 1);
            if (start.equals(end)) continue;
            ExactTime probe = start.add(end).divide(ExactTime.of(2));
            ExactAffineSegment winner = lowerAtOpenPoint(left, right, probe);
            if (winner != null) {
                result.add(new ExactAffineSegment(winner.slope(), winner.intercept(),
                        new ExactTemporalDomain.Interval(start, end, false, false)));
            }
        }
        for (ExactTime cut : ordered) {
            ExactTime leftValue = valueIfSupported(left, cut);
            ExactTime rightValue = valueIfSupported(right, cut);
            if (leftValue == null && rightValue == null) continue;
            ExactTime value = leftValue == null ? rightValue
                    : rightValue == null || leftValue.compareTo(rightValue) <= 0 ? leftValue : rightValue;
            result.add(new ExactAffineSegment(ExactTime.ZERO, value,
                    ExactTemporalDomain.Interval.singleton(cut)));
        }
        return ExactArrivalProfile.ofSegments(result).canonicalized();
    }

    private static void addCrossing(
            TreeSet<ExactTime> cuts, ExactAffineSegment a, ExactAffineSegment b) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                addCrossingUninstrumented(cuts, a, b);
            } finally {
                ThopProfile.end(ThopProfile.P_CROSSING, started);
            }
            return;
        }
        addCrossingUninstrumented(cuts, a, b);
    }

    private static void addCrossingUninstrumented(
            TreeSet<ExactTime> cuts, ExactAffineSegment a, ExactAffineSegment b) {
        ExactTemporalDomain.Interval overlap = a.interval().intersection(b.interval());
        if (overlap == null || a.slope().equals(b.slope())) return;
        ExactTime crossing = b.intercept().subtract(a.intercept())
                .divide(a.slope().subtract(b.slope()));
        if (overlap.containsClosure(crossing)) cuts.add(crossing);
    }

    /** Every exact root where two overlapping affine pieces have equal values. */
    public static List<ExactTime> breakpointCrossings(ExactArrivalProfile left, ExactArrivalProfile right) {
        return breakpointCrossings(left, right, () -> false);
    }

    public static List<ExactTime> breakpointCrossings(
            ExactArrivalProfile left, ExactArrivalProfile right, BooleanSupplier cancellationRequested) {
        TreeSet<ExactTime> crossings = new TreeSet<>();
        List<ExactAffineSegment> others = right.segments();
        // Both piece lists are disjoint and sorted; pairs whose closures do not
        // meet have no overlap and are skipped without changing the result.
        int from = 0;
        for (ExactAffineSegment a : left.segments()) {
            checkCancelled(cancellationRequested);
            while (from < others.size()
                    && others.get(from).interval().end().compareTo(a.interval().start()) < 0) from++;
            for (int j = from; j < others.size(); j++) {
                checkCancelled(cancellationRequested);
                ExactAffineSegment b = others.get(j);
                if (b.interval().start().compareTo(a.interval().end()) > 0) break;
                ExactTemporalDomain.Interval overlap = a.interval().intersection(b.interval());
                if (overlap == null || a.slope().equals(b.slope())) continue;
                if (certainlyNoCrossing(a, b, overlap)) continue;
                ExactTime root = b.intercept().subtract(a.intercept())
                        .divide(a.slope().subtract(b.slope()));
                if (overlap.containsClosure(root)) crossings.add(root);
            }
        }
        return List.copyOf(crossings);
    }

    /**
     * Rigorous floating-point filter. With {@code D(t) = a(t) - b(t)} affine
     * and nonconstant, the root lies in the overlap's closure exactly when
     * {@code D(lo)} and {@code D(hi)} do not have the same strict sign. The
     * doubles below are within a relative 2^-50 of the exact values, so the
     * error of each evaluated {@code D} is far below the bound used; only a
     * pair whose two signs are certain is skipped. Every other pair takes the
     * exact path, so the returned roots are unchanged.
     */
    private static boolean certainlyNoCrossing(
            ExactAffineSegment a, ExactAffineSegment b, ExactTemporalDomain.Interval overlap) {
        double sa = approximate(a.slope()), sb = approximate(b.slope());
        double ia = approximate(a.intercept()), ib = approximate(b.intercept());
        double lo = approximate(overlap.start()), hi = approximate(overlap.end());
        int atLow = certainSign(sa, sb, ia, ib, lo);
        return atLow != 0 && atLow == certainSign(sa, sb, ia, ib, hi);
    }

    private static int certainSign(double sa, double sb, double ia, double ib, double t) {
        double value = (sa - sb) * t + (ia - ib);
        double scale = (Math.abs(sa) + Math.abs(sb)) * Math.abs(t) + Math.abs(ia) + Math.abs(ib);
        double bound = scale * 0x1p-40 + Double.MIN_NORMAL;
        if (!Double.isFinite(value) || !Double.isFinite(bound)) return 0;
        return value > bound ? 1 : value < -bound ? -1 : 0;
    }

    /** A double within a relative 2^-50 of {@code x}; see {@link ExactTimeApproximation}. */
    static double approximate(ExactTime x) {
        return ExactTimeApproximation.of(x);
    }

    /** Reference all-pairs crossing scan; kept to differential-test {@link #breakpointCrossings}. */
    static List<ExactTime> breakpointCrossingsAllPairs(ExactArrivalProfile left, ExactArrivalProfile right) {
        TreeSet<ExactTime> crossings = new TreeSet<>();
        for (ExactAffineSegment a : left.segments()) {
            for (ExactAffineSegment b : right.segments()) {
                ExactTemporalDomain.Interval overlap = a.interval().intersection(b.interval());
                if (overlap == null || a.slope().equals(b.slope())) continue;
                ExactTime root = b.intercept().subtract(a.intercept())
                        .divide(a.slope().subtract(b.slope()));
                if (overlap.containsClosure(root)) crossings.add(root);
            }
        }
        return List.copyOf(crossings);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("exact profile envelope operation cancelled");
        }
    }

    public static boolean dominates(ExactArrivalProfile left, ExactArrivalProfile right) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                return dominatesUninstrumented(left, right);
            } finally {
                ThopProfile.end(ThopProfile.P_DOMINATES, started);
            }
        }
        return dominatesUninstrumented(left, right);
    }

    private static boolean dominatesUninstrumented(ExactArrivalProfile left, ExactArrivalProfile right) {
        if (!right.domain().difference(left.domain()).isEmpty()) return false;
        if (right.isEmpty()) return true;
        TreeSet<ExactTime> cuts = new TreeSet<>();
        addEndpoints(cuts, left);
        addEndpoints(cuts, right);
        List<ExactTime> ordered = List.copyOf(cuts);
        for (ExactTime cut : ordered) {
            ExactTime rv = valueIfSupported(right, cut);
            if (rv == null) continue;
            ExactTime lv = valueIfSupported(left, cut);
            if (lv == null || lv.compareTo(rv) > 0) return false;
        }
        for (int i = 0; i + 1 < ordered.size(); i++) {
            ExactTime start = ordered.get(i), end = ordered.get(i + 1);
            if (start.equals(end)) continue;
            ExactTime probe = start.add(end).divide(ExactTime.of(2));
            ExactAffineSegment r = segmentAt(right, probe);
            if (r == null) continue;
            ExactAffineSegment l = segmentAt(left, probe);
            if (l == null) return false;
            if (l.valueAtClosure(start).compareTo(r.valueAtClosure(start)) > 0
                    || l.valueAtClosure(end).compareTo(r.valueAtClosure(end)) > 0) return false;
        }
        return true;
    }

    public static boolean sameFunction(ExactArrivalProfile left, ExactArrivalProfile right) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                return sameFunctionUninstrumented(left, right);
            } finally {
                ThopProfile.end(ThopProfile.P_SAME_FUNCTION, started);
            }
        }
        return sameFunctionUninstrumented(left, right);
    }

    private static boolean sameFunctionUninstrumented(ExactArrivalProfile left, ExactArrivalProfile right) {
        if (!left.domain().equals(right.domain())) return false;
        if (left.isEmpty()) return true;
        TreeSet<ExactTime> cuts = new TreeSet<>();
        addEndpoints(cuts, left);
        addEndpoints(cuts, right);
        List<ExactTime> ordered = List.copyOf(cuts);
        for (ExactTime cut : ordered) {
            ExactTime a = valueIfSupported(left, cut);
            ExactTime b = valueIfSupported(right, cut);
            if ((a == null) != (b == null) || (a != null && !a.equals(b))) return false;
        }
        for (int i = 0; i + 1 < ordered.size(); i++) {
            ExactTime start = ordered.get(i), end = ordered.get(i + 1);
            if (start.equals(end)) continue;
            ExactTime probe = start.add(end).divide(ExactTime.of(2));
            ExactAffineSegment a = segmentAt(left, probe);
            ExactAffineSegment b = segmentAt(right, probe);
            if ((a == null) != (b == null)) return false;
            if (a != null && (!a.slope().equals(b.slope()) || !a.intercept().equals(b.intercept()))) return false;
        }
        return true;
    }

    private static ExactAffineSegment lowerAtOpenPoint(
            ExactArrivalProfile left, ExactArrivalProfile right, ExactTime probe) {
        ExactAffineSegment a = segmentAt(left, probe);
        ExactAffineSegment b = segmentAt(right, probe);
        if (a == null) return b;
        if (b == null) return a;
        return a.valueAt(probe).compareTo(b.valueAt(probe)) <= 0 ? a : b;
    }

    private static ExactTime valueIfSupported(ExactArrivalProfile profile, ExactTime point) {
        ExactAffineSegment segment = segmentAt(profile, point);
        return segment == null ? null : segment.valueAt(point);
    }

    private static ExactAffineSegment segmentAt(ExactArrivalProfile profile, ExactTime point) {
        List<ExactAffineSegment> segments = profile.segments();
        int low = 0;
        int high = segments.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (segments.get(middle).interval().start().compareTo(point) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int candidate = low - 1;
        if (candidate < 0) return null;
        ExactAffineSegment segment = segments.get(candidate);
        if (segment.interval().contains(point)) return segment;
        // Adjacent intervals may share a cut where only one side owns it.
        if (segment.interval().start().equals(point) && candidate > 0) {
            ExactAffineSegment previous = segments.get(candidate - 1);
            if (previous.interval().contains(point)) return previous;
        }
        return null;
    }

    private static void addEndpoints(TreeSet<ExactTime> cuts, ExactArrivalProfile profile) {
        for (ExactAffineSegment segment : profile.segments()) {
            cuts.add(segment.interval().start());
            cuts.add(segment.interval().end());
        }
    }
}
