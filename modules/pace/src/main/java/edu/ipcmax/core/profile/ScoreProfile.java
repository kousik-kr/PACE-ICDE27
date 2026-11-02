package edu.ipcmax.core.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;

/**
 * Right-continuous piecewise-constant integer score profile over an exact root
 * departure domain.
 */
public final class ScoreProfile {
    /**
     * Exact score interval. Ordinary pieces are {@code [start,end)}; a
     * zero-length piece represents a defined singleton endpoint.
     */
    public record Interval(double startMinute, double endMinute, int value) {
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

    @FunctionalInterface
    public interface Evaluator {
        int applyAsInt(double time);
    }

    private final Domain domain;
    private final String fingerprint;
    private final List<Interval> intervals;
    private final List<Double> breakpoints;

    /**
     * Creates a score profile that is constant on each connected domain
     * component. Callers with internal score changes must use
     * {@link #piecewise}.
     */
    public ScoreProfile(Domain domain, Evaluator evaluator, String fingerprint) {
        this(domain, inferConstantIntervals(domain, evaluator), fingerprint, true);
    }

    private ScoreProfile(
            Domain domain,
            List<Interval> intervals,
            String fingerprint,
            boolean alreadyValidated) {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("score profile domain cannot be null or empty");
        }
        this.domain = domain;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.intervals = List.copyOf(alreadyValidated ? intervals : validateAndSort(intervals));
        if (this.intervals.isEmpty()) {
            throw new IllegalArgumentException("score profile requires at least one interval");
        }
        this.breakpoints = breakpointValues(this.intervals);
    }

    /**
     * Constant score profile.
     */
    public static ScoreProfile constant(Domain domain, int score) {
        if (score < 0) {
            throw new IllegalArgumentException("score cannot be negative");
        }
        return new ScoreProfile(
                domain,
                inferConstantIntervals(domain, ignored -> score),
                "score-constant:" + score + ":" + domain.intervals(),
                true);
    }

    /**
     * Builds an exact piecewise-constant score profile.
     */
    public static ScoreProfile piecewise(
            Domain domain,
            List<Interval> intervals,
            String fingerprint) {
        return new ScoreProfile(domain, validateAndSort(intervals), fingerprint, true);
    }

    /**
     * Evaluates score at a root departure time.
     */
    public int valueAt(int rootDepartureTime) {
        return valueAt((double) rootDepartureTime);
    }

    /**
     * Evaluates score at a contained start time.
     */
    public int valueAt(double rootDepartureTime) {
        rootDepartureTime = Domain.canonicalTime(rootDepartureTime);
        if (!domain.contains(rootDepartureTime)) {
            throw new IllegalArgumentException("time is outside score profile domain: " + rootDepartureTime);
        }
        return lookup(rootDepartureTime, intervals);
    }

    /**
     * Evaluates the right-continuous score on the closure of its domain.
     *
     * <p>This supports exact reasoning about open cell interiors whose two
     * endpoints are adjacent canonical ticks. It does not make an excluded
     * endpoint a valid query departure.</p>
     */
    public int valueAtClosure(double rootDepartureTime) {
        rootDepartureTime = Domain.canonicalTime(
                rootDepartureTime);
        if (!inDomainClosure(rootDepartureTime)) {
            throw new IllegalArgumentException(
                    "time is outside score-profile-domain closure: "
                            + rootDepartureTime);
        }
        return lookup(rootDepartureTime, intervals);
    }

    /**
     * Domain where this profile is valid.
     */
    public Domain domain() {
        return domain;
    }

    /**
     * Returns exact score intervals.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Returns distinct score breakpoints.
     */
    public List<Double> breakpoints() {
        return breakpoints;
    }

    /**
     * Exact piecewise metadata is always available.
     */
    public boolean isPiecewise() {
        return true;
    }

    /**
     * Stable fingerprint for memoization.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Restricts this score profile exactly.
     */
    public ScoreProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted score profile domain is empty");
        }
        return build(
                restricted,
                breakpoints(),
                this::valueAtClosure,
                fingerprint + "|restrict:" + restricted.intervals());
    }

    /**
     * Adds another score profile exactly over the supplied domain.
     */
    public ScoreProfile add(
            ScoreProfile other,
            Domain rootDomain,
            String composedFingerprint) {
        Objects.requireNonNull(other, "other");
        Domain restricted = domain.intersection(other.domain).intersection(rootDomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("score addition domain is empty");
        }
        List<Double> cuts = new ArrayList<>(breakpoints());
        cuts.addAll(other.breakpoints());
        return build(
                restricted,
                cuts,
                time -> valueAtClosure(time)
                        + other.valueAtClosure(time),
                composedFingerprint);
    }

    /**
     * Pulls this score profile back through an exact arrival profile.
     */
    public ScoreProfile compose(TimeProfile arrivalProfile, String composedFingerprint) {
        Objects.requireNonNull(arrivalProfile, "arrivalProfile");
        Domain feasible = arrivalProfile.preimage(domain, arrivalProfile.domain());
        if (feasible.isEmpty()) {
            throw new IllegalArgumentException("score pullback domain is empty");
        }

        List<Double> cuts = pullbackCuts(arrivalProfile, feasible, breakpoints());
        return build(
                feasible,
                cuts,
                time -> valueAtClosure(
                        arrivalProfile.valueAtClosure(time)),
                composedFingerprint);
    }

    /**
     * Pulls an edge score function back through an exact arrival profile.
     */
    public static ScoreProfile compose(
            TimeProfile arrivalProfile,
            PiecewiseConstFn edgeScore,
            Domain rootDomain,
            String fingerprint) {
        Objects.requireNonNull(arrivalProfile, "arrivalProfile");
        Objects.requireNonNull(edgeScore, "edgeScore");
        TemporalProfileWork.increment("score_compose_calls");
        Domain requested = arrivalProfile.domain().intersection(rootDomain);
        Domain feasible = arrivalProfile.preimage(edgeScore.domain(), requested);
        if (feasible.isEmpty()) {
            throw new IllegalArgumentException("score composition domain is empty");
        }

        List<Double> cuts = pullbackCuts(arrivalProfile, feasible, edgeScore.breakpoints());
        return build(
                feasible,
                cuts,
                time -> edgeScore.valueAt(
                        arrivalProfile.valueAtClosure(time)),
                fingerprint);
    }

    private static List<Double> pullbackCuts(
            TimeProfile arrivalProfile,
            Domain feasible,
            List<Double> valueBreakpoints) {
        List<Double> cuts = new ArrayList<>(feasible.breakpoints());
        for (double valueBreakpoint : valueBreakpoints) {
            Domain roots = arrivalProfile.preimage(
                    Domain.closed(valueBreakpoint, valueBreakpoint),
                    feasible);
            cuts.addAll(roots.breakpoints());
        }
        return cuts;
    }

    private static ScoreProfile build(
            Domain domain,
            List<Double> requestedCuts,
            Evaluator evaluator,
            String fingerprint) {
        List<Interval> result = new ArrayList<>();
        for (Domain.Interval component : domain.intervals()) {
            if (component.start() == component.end()) {
                appendInterval(result, new Interval(
                        component.start(),
                        component.end(),
                        evaluator.applyAsInt(component.start())));
                continue;
            }

            List<Double> cuts = cutsInside(component, requestedCuts);
            for (int i = 1; i < cuts.size(); i++) {
                double start = cuts.get(i - 1);
                double end = cuts.get(i);
                double sample = start + (end - start) / 2.0;
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
        return new ScoreProfile(domain, validateAndSort(result), fingerprint, true);
    }

    private static List<Interval> inferConstantIntervals(
            Domain domain,
            Evaluator evaluator) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(evaluator, "evaluator");
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("score profile domain cannot be empty");
        }
        List<Interval> result = new ArrayList<>();
        for (Domain.Interval component : domain.intervals()) {
            if (component.start() == component.end()) {
                result.add(new Interval(
                        component.start(),
                        component.end(),
                        evaluator.applyAsInt(component.start())));
                continue;
            }
            double sample = component.start() + (component.end() - component.start()) / 2.0;
            result.add(new Interval(component.start(), component.end(), evaluator.applyAsInt(sample)));
            if (component.endInclusive()) {
                int endpointValue = evaluator.applyAsInt(component.end());
                if (result.get(result.size() - 1).value() != endpointValue) {
                    result.add(new Interval(component.end(), component.end(), endpointValue));
                }
            }
        }
        return validateAndSort(result);
    }

    private static List<Double> cutsInside(
            Domain.Interval component,
            List<Double> requestedCuts) {
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

    private boolean inDomainClosure(double time) {
        for (Domain.Interval component :
                domain.intervals()) {
            if (time >= component.start()
                    && time <= component.end()) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsAt(List<Interval> intervals, int index, double time) {
        return index < intervals.size() && intervals.get(index).startMinute() == time;
    }

    private static List<Interval> validateAndSort(List<Interval> source) {
        Objects.requireNonNull(source, "intervals");
        if (source.isEmpty()) {
            throw new IllegalArgumentException("score profile requires at least one interval");
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
