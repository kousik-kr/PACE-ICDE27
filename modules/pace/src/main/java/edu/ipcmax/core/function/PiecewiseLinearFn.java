package edu.ipcmax.core.function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;

/**
 * Exact piecewise-linear travel-time function.
 *
 * <p>The stored value is travel time. The corresponding edge arrival function
 * is {@code Gamma(t) = t + travelTime(t)}.</p>
 */
public final class PiecewiseLinearFn {
    private record Segment(
            double start,
            double end,
            double startValue,
            double endValue,
            boolean startInclusive,
            boolean endInclusive) {
    }

    private final List<Breakpoint> breakpoints;
    private final Domain domain;

    /**
     * A travel-time breakpoint.
     */
    public record Breakpoint(double minute, double value) {
        /**
         * Creates a validated breakpoint.
         */
        public Breakpoint {
            if (!Double.isFinite(minute) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("breakpoint values must be finite");
            }
            minute = Domain.canonicalTime(minute);
            value = Domain.canonicalTime(value);
            if (value < 0) {
                throw new IllegalArgumentException("travel time cannot be negative");
            }
        }
    }

    /**
     * Creates a piecewise-linear travel-time function from sorted breakpoints.
     */
    public PiecewiseLinearFn(List<Breakpoint> breakpoints) {
        this(validateBreakpoints(breakpoints), null);
    }

    private PiecewiseLinearFn(List<Breakpoint> breakpoints, Domain exactDomain) {
        this.breakpoints = List.copyOf(breakpoints);
        this.domain = exactDomain == null
                ? Domain.closed(breakpoints.get(0).minute(), breakpoints.get(breakpoints.size() - 1).minute())
                : exactDomain;
    }

    /**
     * Returns the immutable breakpoint list.
     */
    public List<Breakpoint> breakpoints() {
        return breakpoints;
    }

    /**
     * Exact function domain.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Evaluates travel time at {@code minute}.
     */
    public double travelTimeAt(double minute) {
        minute = Domain.canonicalTime(minute);
        if (!domain.contains(minute)) {
            throw new IllegalArgumentException("time is outside function domain: " + minute);
        }
        return Domain.canonicalTime(evaluateUnchecked(minute));
    }

    /**
     * Evaluates arrival time {@code Gamma(t) = t + travelTime(t)}.
     */
    public double arrivalTimeAt(double minute) {
        minute = Domain.canonicalTime(minute);
        return Domain.canonicalTime(minute + travelTimeAt(minute));
    }

    /**
     * Evaluates the arrival function on the closure of this function's
     * temporal domain. This is intended for exact boundary and FIFO checks;
     * it does not make an excluded endpoint a valid edge-entry time.
     */
    public double arrivalTimeAtClosure(double minute) {
        double time = Domain.canonicalTime(minute);
        boolean inClosure = domain.intervals().stream()
                .anyMatch(interval -> time >= interval.start() && time <= interval.end());
        if (!inClosure) {
            throw new IllegalArgumentException("time is outside function-domain closure: " + time);
        }
        return Domain.canonicalTime(time + evaluateUnchecked(time));
    }

    /**
     * Returns the minimum travel time attained in the domain.
     */
    public double minTravelTime() {
        double min = Double.POSITIVE_INFINITY;
        for (Segment segment : segments(domain)) {
            min = Math.min(min, Math.min(segment.startValue(), segment.endValue()));
        }
        return min;
    }

    /**
     * Latest departure in the function domain whose arrival is no later than
     * {@code arrivalDeadline}. Returns negative infinity if none exists.
     */
    public double latestDepartureForArrival(double arrivalDeadline) {
        if (!Double.isFinite(arrivalDeadline)) {
            throw new IllegalArgumentException("arrival deadline must be finite");
        }
        Domain feasible = domainWhereArrivalAtMost(
                domain,
                ignored -> arrivalDeadline);
        if (feasible.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        Domain.Interval last = feasible.intervals().get(feasible.intervals().size() - 1);
        return last.endInclusive() ? last.end() : Math.nextDown(last.end());
    }

    /**
     * Returns true when the induced arrival function is FIFO on every valid
     * segment.
     */
    public boolean isFifo() {
        for (Segment segment : segments(domain)) {
            double leftArrival = segment.start() + segment.startValue();
            double rightArrival = segment.end() + segment.endValue();
            if (Domain.canonicalTime(rightArrival) < Domain.canonicalTime(leftArrival)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates FIFO and throws with context on failure.
     */
    public void requireFifo(String context) {
        if (!isFifo()) {
            throw new IllegalArgumentException(context + ": non-FIFO travel-time function");
        }
    }

    /**
     * First time in the function-domain closure.
     */
    public double firstMinute() {
        return domain.intervals().get(0).start();
    }

    /**
     * Last time in the function-domain closure.
     */
    public double lastMinute() {
        return domain.intervals().get(domain.intervals().size() - 1).end();
    }

    /**
     * Restricts this function exactly, retaining every internal breakpoint and
     * any disconnected domain components.
     */
    public PiecewiseLinearFn restrict(Domain requestedDomain) {
        Domain restricted = domain.intersection(requestedDomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("travel-time restriction is empty");
        }
        List<Breakpoint> restrictedBreakpoints = new ArrayList<>();
        for (Domain.Interval component : restricted.intervals()) {
            addUnique(restrictedBreakpoints, new Breakpoint(component.start(), evaluateUnchecked(component.start())));
            for (Breakpoint breakpoint : breakpoints) {
                if (breakpoint.minute() > component.start() && breakpoint.minute() < component.end()) {
                    addUnique(restrictedBreakpoints, breakpoint);
                }
            }
            addUnique(restrictedBreakpoints, new Breakpoint(component.end(), evaluateUnchecked(component.end())));
        }
        return new PiecewiseLinearFn(restrictedBreakpoints, restricted);
    }

    /**
     * Builds the exact travel-time composition for a supplied entry-time
     * mapping. Domain validity is checked when the returned operator is used.
     */
    public DoubleUnaryOperator composeTravelTime(DoubleUnaryOperator timeProfile) {
        Objects.requireNonNull(timeProfile, "timeProfile");
        return rootTime -> travelTimeAt(timeProfile.applyAsDouble(rootTime));
    }

    /**
     * Returns the continuous domain where this edge's arrival is no later than
     * {@code rhs}. The legacy operator is treated as affine between this
     * function's breakpoints; callers with a piecewise-linear right-hand side
     * should refine {@code domain} at all of its breakpoints first.
     */
    public Domain domainWhereArrivalAtMost(Domain requestedDomain, DoubleUnaryOperator rhs) {
        Objects.requireNonNull(rhs, "rhs");
        Domain restricted = domain.intersection(requestedDomain);
        Domain result = Domain.empty();
        for (Segment segment : segments(restricted)) {
            double leftDifference = segment.start() + segment.startValue()
                    - rhs.applyAsDouble(segment.start());
            double rightDifference = segment.end() + segment.endValue()
                    - rhs.applyAsDouble(segment.end());
            if (!Double.isFinite(leftDifference) || !Double.isFinite(rightDifference)) {
                throw new IllegalArgumentException("right-hand profile returned a non-finite value");
            }
            result = result.union(atMostZero(segment, leftDifference, rightDifference));
        }
        return result;
    }

    private Domain atMostZero(Segment segment, double left, double right) {
        boolean leftFeasible = Domain.canonicalTime(left) <= 0;
        boolean rightFeasible = Domain.canonicalTime(right) <= 0;
        if (segment.start() == segment.end()) {
            return leftFeasible ? Domain.closed(segment.start(), segment.end()) : Domain.empty();
        }
        if (leftFeasible && rightFeasible) {
            return Domain.of(new Domain.Interval(
                    segment.start(), segment.end(), segment.startInclusive(), segment.endInclusive()));
        }
        if (!leftFeasible && !rightFeasible) {
            return Domain.empty();
        }
        if (approximatelyEqual(left, right)) {
            return leftFeasible
                    ? Domain.of(new Domain.Interval(
                            segment.start(), segment.end(), segment.startInclusive(), segment.endInclusive()))
                    : Domain.empty();
        }

        double root = segment.start() - left * (segment.end() - segment.start()) / (right - left);
        root = Math.max(segment.start(), Math.min(segment.end(), root));
        if (leftFeasible) {
            return intervalOrEmpty(segment.start(), root, segment.startInclusive(), true);
        }
        return intervalOrEmpty(root, segment.end(), true, segment.endInclusive());
    }

    private List<Segment> segments(Domain restricted) {
        List<Segment> result = new ArrayList<>();
        for (Domain.Interval component : restricted.intervals()) {
            if (component.start() == component.end()) {
                double value = evaluateUnchecked(component.start());
                result.add(new Segment(component.start(), component.end(), value, value, true, true));
                continue;
            }
            List<Double> cuts = new ArrayList<>();
            cuts.add(component.start());
            for (Breakpoint breakpoint : breakpoints) {
                if (breakpoint.minute() > component.start() && breakpoint.minute() < component.end()) {
                    cuts.add(breakpoint.minute());
                }
            }
            cuts.add(component.end());
            cuts.sort(Double::compare);
            for (int i = 1; i < cuts.size(); i++) {
                double start = cuts.get(i - 1);
                double end = cuts.get(i);
                result.add(new Segment(
                        start,
                        end,
                        evaluateUnchecked(start),
                        evaluateUnchecked(end),
                        i == 1 ? component.startInclusive() : true,
                        i == cuts.size() - 1 ? component.endInclusive() : false));
            }
        }
        return result;
    }

    private double evaluateUnchecked(double minute) {
        minute = Domain.canonicalTime(minute);
        if (breakpoints.size() == 1) {
            return breakpoints.get(0).value();
        }
        if (minute == breakpoints.get(breakpoints.size() - 1).minute()) {
            return breakpoints.get(breakpoints.size() - 1).value();
        }

        int index = Collections.binarySearch(
                breakpoints,
                new Breakpoint(minute, 0),
                Comparator.comparingDouble(Breakpoint::minute));
        if (index >= 0) {
            return breakpoints.get(index).value();
        }

        int insertionPoint = -index - 1;
        if (insertionPoint == 0 || insertionPoint == breakpoints.size()) {
            throw new IllegalArgumentException("time is outside function-domain closure: " + minute);
        }
        Breakpoint left = breakpoints.get(insertionPoint - 1);
        Breakpoint right = breakpoints.get(insertionPoint);
        double alpha = (minute - left.minute()) / (right.minute() - left.minute());
        return left.value() + alpha * (right.value() - left.value());
    }

    private static List<Breakpoint> validateBreakpoints(List<Breakpoint> source) {
        Objects.requireNonNull(source, "breakpoints");
        if (source.isEmpty()) {
            throw new IllegalArgumentException("piecewise-linear function requires at least one breakpoint");
        }
        List<Breakpoint> copy = new ArrayList<>(source);
        copy.sort(Comparator.comparingDouble(Breakpoint::minute));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i).minute() <= copy.get(i - 1).minute()) {
                throw new IllegalArgumentException("breakpoint times must be strictly increasing");
            }
        }
        return copy;
    }

    private static Domain intervalOrEmpty(
            double start,
            double end,
            boolean startInclusive,
            boolean endInclusive) {
        start = Domain.canonicalTime(start);
        end = Domain.canonicalTime(end);
        if (start < end) {
            return Domain.of(new Domain.Interval(start, end, startInclusive, endInclusive));
        }
        if (approximatelyEqual(start, end) && startInclusive && endInclusive) {
            return Domain.closed((start + end) / 2.0, (start + end) / 2.0);
        }
        return Domain.empty();
    }

    private static void addUnique(List<Breakpoint> points, Breakpoint point) {
        if (!points.isEmpty() && points.get(points.size() - 1).minute() == point.minute()) {
            return;
        }
        points.add(point);
    }

    private static boolean approximatelyEqual(double left, double right) {
        return Domain.sameTime(left, right);
    }
}
