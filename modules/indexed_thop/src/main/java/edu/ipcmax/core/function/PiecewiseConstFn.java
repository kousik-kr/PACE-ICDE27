package edu.ipcmax.core.function;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleUnaryOperator;

/**
 * Piecewise-constant nonnegative integer score function.
 *
 * <p>Pieces are right-continuous: an ordinary piece is {@code [start,end)}.
 * The final endpoint of each connected function-domain component belongs to
 * its last piece.  A zero-length piece is permitted only to represent an
 * explicitly defined singleton endpoint.</p>
 */
public final class PiecewiseConstFn {
    private final List<Interval> intervals;
    private final Domain domain;
    private final List<Double> breakpoints;

    /**
     * A half-open score interval {@code [startMinute,endMinute)}.
     */
    public record Interval(double startMinute, double endMinute, int value) {
        /**
         * Creates a validated interval.
         */
        public Interval {
            if (!Double.isFinite(startMinute) || !Double.isFinite(endMinute)) {
                throw new IllegalArgumentException("score interval bounds must be finite");
            }
            startMinute = Domain.canonicalTime(startMinute);
            endMinute = Domain.canonicalTime(endMinute);
            if (endMinute < startMinute) {
                throw new IllegalArgumentException("score interval end must be >= start");
            }
            if (value < 0) {
                throw new IllegalArgumentException("score value cannot be negative");
            }
        }

        private boolean isPoint() {
            return startMinute == endMinute;
        }
    }

    /**
     * Creates a piecewise-constant score function from ordered,
     * non-overlapping intervals. Gaps are allowed and remain outside the
     * function domain.
     */
    public PiecewiseConstFn(List<Interval> intervals) {
        this(validateAndSort(intervals), null);
    }

    private PiecewiseConstFn(List<Interval> intervals, Domain exactDomain) {
        if (intervals.isEmpty()) {
            throw new IllegalArgumentException("score function requires at least one interval");
        }
        this.intervals = List.copyOf(intervals);
        this.domain = exactDomain == null ? deriveDomain(intervals) : exactDomain;
        if (this.domain.isEmpty()) {
            throw new IllegalArgumentException("score function domain cannot be empty");
        }
        this.breakpoints = breakpointValues(this.intervals);
    }

    /**
     * Returns a full-day zero score function.
     */
    public static PiecewiseConstFn zeroFullDay() {
        return new PiecewiseConstFn(List.of(new Interval(0, 1440, 0)));
    }

    /** Creates an exact constant score function over an endpoint-aware domain. */
    public static PiecewiseConstFn constant(Domain domain, int value) {
        Objects.requireNonNull(domain, "domain");
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("constant score domain cannot be empty");
        }
        if (value < 0) {
            throw new IllegalArgumentException("score value cannot be negative");
        }
        return build(domain, domain.breakpoints(), ignored -> value);
    }

    /**
     * Returns the immutable interval list.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Returns the exact function domain, including endpoint ownership.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Evaluates the score at {@code minute}.
     */
    public int valueAt(double minute) {
        minute = Domain.canonicalTime(minute);
        if (!domain.contains(minute)) {
            throw new IllegalArgumentException("time is outside score function domain: " + minute);
        }
        return lookup(minute, intervals);
    }

    /**
     * Returns true when score can be positive over the given half-open range.
     */
    public boolean hasPositiveValueIn(TimeRange range) {
        if (range.isEmpty()) {
            return false;
        }
        Domain query = Domain.halfOpen(range.startMinute(), range.endMinute());
        return !positiveDomain().intersection(query).isEmpty();
    }

    /**
     * Maximum score value in this function.
     */
    public int maxValue() {
        int max = 0;
        for (Interval interval : intervals) {
            max = Math.max(max, interval.value());
        }
        return max;
    }

    /**
     * Returns the exact domain where the score is positive.
     */
    public Domain positiveDomain() {
        Domain positive = Domain.empty();
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            if (interval.value() == 0) {
                continue;
            }
            Domain support;
            if (interval.isPoint()) {
                support = Domain.closed(interval.startMinute(), interval.endMinute());
            } else {
                boolean ownsEnd = !hasPieceStartingAt(i + 1, interval.endMinute());
                support = Domain.of(new Domain.Interval(
                        interval.startMinute(),
                        interval.endMinute(),
                        true,
                        ownsEnd));
            }
            positive = positive.union(support.intersection(domain));
        }
        return positive;
    }

    /**
     * Restricts this function to an exact continuous domain.
     */
    public PiecewiseConstFn restrict(Domain requestedDomain) {
        Domain restricted = domain.intersection(requestedDomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("score restriction is empty");
        }
        return build(restricted, breakpoints(), this::valueAt);
    }

    /**
     * Adds two score functions exactly over a continuous domain.
     */
    public PiecewiseConstFn add(PiecewiseConstFn other, Domain requestedDomain) {
        Objects.requireNonNull(other, "other");
        Domain restricted = domain.intersection(other.domain).intersection(requestedDomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("score addition domain is empty");
        }
        List<Double> cuts = new ArrayList<>(breakpoints());
        cuts.addAll(other.breakpoints());
        return build(restricted, cuts, time -> valueAt(time) + other.valueAt(time));
    }

    /**
     * Pulls this score function back through a mapping that is affine on each
     * connected root-domain component.
     *
     * <p>The legacy operator signature cannot carry breakpoint metadata. PACE
     * uses {@code ScoreProfile.compose}, which supplies exact arrival-profile
     * breakpoints. This method nevertheless performs continuous affine
     * pullback rather than integer-minute sampling.</p>
     */
    public PiecewiseConstFn compose(DoubleUnaryOperator timeProfile, Domain rootDomain) {
        Objects.requireNonNull(timeProfile, "timeProfile");
        Domain feasible = affinePreimage(timeProfile, rootDomain, domain);
        if (feasible.isEmpty()) {
            throw new IllegalArgumentException("score composition domain is empty");
        }

        List<Double> cuts = new ArrayList<>(feasible.breakpoints());
        for (Domain.Interval root : feasible.intervals()) {
            if (root.start() == root.end()) {
                continue;
            }
            double y0 = timeProfile.applyAsDouble(root.start());
            double y1 = timeProfile.applyAsDouble(root.end());
            if (approximatelyEqual(y0, y1)) {
                continue;
            }
            for (double scoreBreakpoint : breakpoints()) {
                double alpha = (scoreBreakpoint - y0) / (y1 - y0);
                if (alpha > 0.0 && alpha < 1.0) {
                    cuts.add(root.start() + alpha * (root.end() - root.start()));
                }
            }
        }
        return build(feasible, cuts, time -> valueAt(timeProfile.applyAsDouble(time)));
    }

    /**
     * Maximum score over an exact continuous domain.
     */
    public int maxValue(Domain requestedDomain) {
        Domain restricted = domain.intersection(requestedDomain);
        int max = 0;
        for (Domain.Interval component : restricted.intervals()) {
            if (component.startInclusive()) {
                max = Math.max(max, valueAt(component.start()));
            }
            if (component.endInclusive()) {
                max = Math.max(max, valueAt(component.end()));
            }
            for (Interval interval : intervals) {
                double sampleStart = Math.max(component.start(), interval.startMinute());
                double sampleEnd = Math.min(component.end(), interval.endMinute());
                if (sampleStart < sampleEnd) {
                    max = Math.max(max, interval.value());
                }
            }
        }
        return max;
    }

    /**
     * Breakpoint times.
     */
    public List<Double> breakpoints() {
        return breakpoints;
    }

    private boolean hasPieceStartingAt(int fromIndex, double time) {
        for (int i = fromIndex; i < intervals.size(); i++) {
            double start = intervals.get(i).startMinute();
            if (approximatelyEqual(start, time)) {
                return true;
            }
            if (start > time) {
                return false;
            }
        }
        return false;
    }

    private static PiecewiseConstFn build(
            Domain domain,
            List<Double> requestedCuts,
            DoubleToIntFunction evaluator) {
        List<Interval> result = new ArrayList<>();
        for (Domain.Interval component : domain.intervals()) {
            if (component.start() == component.end()) {
                appendInterval(result, new Interval(component.start(), component.end(), evaluator.applyAsInt(component.start())));
                continue;
            }

            List<Double> cuts = cutsInside(component, requestedCuts);
            for (int i = 1; i < cuts.size(); i++) {
                double start = cuts.get(i - 1);
                double end = cuts.get(i);
                double sample = midpoint(start, end);
                appendInterval(result, new Interval(start, end, evaluator.applyAsInt(sample)));
            }

            if (component.endInclusive()) {
                int endpointValue = evaluator.applyAsInt(component.end());
                Interval last = result.get(result.size() - 1);
                if (last.endMinute() == component.end() && last.value() != endpointValue) {
                    result.add(new Interval(component.end(), component.end(), endpointValue));
                }
            }
        }
        return new PiecewiseConstFn(validateAndSort(result), domain);
    }

    private static List<Double> cutsInside(Domain.Interval component, List<Double> requestedCuts) {
        TreeSet<Long> ticks = new TreeSet<>();
        ticks.add(Domain.canonicalTick(component.start()));
        for (double cut : requestedCuts) {
            if (cut > component.start() && cut < component.end()) {
                ticks.add(Domain.canonicalTick(cut));
            }
        }
        ticks.add(Domain.canonicalTick(component.end()));
        return ticks.stream().map(Domain::timeFromTick).toList();
    }

    private static Domain affinePreimage(
            DoubleUnaryOperator mapping,
            Domain rootDomain,
            Domain targetDomain) {
        Domain result = Domain.empty();
        for (Domain.Interval root : rootDomain.intervals()) {
            if (root.start() == root.end()) {
                double image = mapping.applyAsDouble(root.start());
                if (targetDomain.contains(image)) {
                    result = result.union(Domain.closed(root.start(), root.end()));
                }
                continue;
            }
            double y0 = mapping.applyAsDouble(root.start());
            double y1 = mapping.applyAsDouble(root.end());
            if (!Double.isFinite(y0) || !Double.isFinite(y1)) {
                throw new IllegalArgumentException("time profile returned a non-finite value");
            }
            if (approximatelyEqual(y0, y1)) {
                if (targetDomain.contains(y0)) {
                    result = result.union(Domain.of(root));
                }
                continue;
            }

            boolean increasing = y1 > y0;
            Domain image = Domain.of(new Domain.Interval(
                    Math.min(y0, y1),
                    Math.max(y0, y1),
                    increasing ? root.startInclusive() : root.endInclusive(),
                    increasing ? root.endInclusive() : root.startInclusive()));
            Domain overlap = image.intersection(targetDomain);
            for (Domain.Interval target : overlap.intervals()) {
                double xAtStart = root.start() + (target.start() - y0) * (root.end() - root.start()) / (y1 - y0);
                double xAtEnd = root.start() + (target.end() - y0) * (root.end() - root.start()) / (y1 - y0);
                if (increasing) {
                    result = result.union(Domain.of(new Domain.Interval(
                            xAtStart, xAtEnd, target.startInclusive(), target.endInclusive())));
                } else {
                    result = result.union(Domain.of(new Domain.Interval(
                            xAtEnd, xAtStart, target.endInclusive(), target.startInclusive())));
                }
            }
        }
        return result;
    }

    private static int lookup(double time, List<Interval> intervals) {
        time = Domain.canonicalTime(time);
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            if (interval.isPoint()) {
                if (time == interval.startMinute()) {
                    return interval.value();
                }
                continue;
            }
            if (time >= interval.startMinute() && time < interval.endMinute()) {
                return interval.value();
            }
            if (time == interval.endMinute() && !startsAt(intervals, i + 1, time)) {
                return interval.value();
            }
        }
        throw new IllegalStateException("score intervals do not cover time " + time);
    }

    private static boolean startsAt(List<Interval> intervals, int index, double time) {
        return index < intervals.size() && intervals.get(index).startMinute() == time;
    }

    private static List<Interval> validateAndSort(List<Interval> source) {
        Objects.requireNonNull(source, "intervals");
        if (source.isEmpty()) {
            throw new IllegalArgumentException("score function requires at least one interval");
        }
        List<Interval> copy = new ArrayList<>(source);
        copy.sort(Comparator.comparingDouble(Interval::startMinute)
                .thenComparingDouble(Interval::endMinute));
        for (int i = 1; i < copy.size(); i++) {
            Interval previous = copy.get(i - 1);
            Interval current = copy.get(i);
            if (current.startMinute() < previous.endMinute()
                    || (previous.isPoint() && current.startMinute() == previous.startMinute())) {
                throw new IllegalArgumentException("score intervals must be non-overlapping and ordered");
            }
        }
        return copy;
    }

    private static Domain deriveDomain(List<Interval> intervals) {
        List<Domain.Interval> components = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            Interval interval = intervals.get(i);
            if (interval.isPoint()) {
                components.add(new Domain.Interval(interval.startMinute(), interval.endMinute()));
                continue;
            }
            boolean ownsEnd = !startsAt(intervals, i + 1, interval.endMinute());
            components.add(new Domain.Interval(
                    interval.startMinute(),
                    interval.endMinute(),
                    true,
                    ownsEnd));
        }
        return Domain.of(components.toArray(Domain.Interval[]::new));
    }

    private static void appendInterval(List<Interval> intervals, Interval interval) {
        if (!intervals.isEmpty()) {
            Interval last = intervals.get(intervals.size() - 1);
            if (last.endMinute() == interval.startMinute() && last.value() == interval.value()) {
                intervals.set(intervals.size() - 1,
                        new Interval(last.startMinute(), interval.endMinute(), last.value()));
                return;
            }
        }
        intervals.add(interval);
    }

    private static double midpoint(double start, double end) {
        return start + (end - start) / 2.0;
    }

    private static List<Double> breakpointValues(
            List<Interval> source) {
        TreeSet<Long> ticks = new TreeSet<>();
        for (Interval interval : source) {
            ticks.add(Domain.canonicalTick(
                    interval.startMinute()));
            ticks.add(Domain.canonicalTick(
                    interval.endMinute()));
        }
        return ticks.stream().map(Domain::timeFromTick).toList();
    }

    private static boolean approximatelyEqual(double left, double right) {
        return Domain.sameTime(left, right);
    }
}
