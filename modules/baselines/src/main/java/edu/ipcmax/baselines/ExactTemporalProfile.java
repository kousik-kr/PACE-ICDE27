package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Exact piecewise-linear function that can include discontinuities at
 * temporal support boundaries.
 *
 * <p>{@link TimeProfile} deliberately represents continuous functions on a
 * connected domain. A route envelope may be discontinuous when the route that
 * owns an endpoint differs from the route available immediately beside it.
 * This class represents that function as disjoint open-interval and singleton
 * cells, while delegating interpolation and composition within each cell to
 * {@code TimeProfile}.</p>
 */
public final class ExactTemporalProfile {
    private final List<TimeProfile> cells;

    private ExactTemporalProfile(Collection<TimeProfile> suppliedCells) {
        List<TimeProfile> ordered = new ArrayList<>();
        for (TimeProfile profile : suppliedCells) {
            if (profile == null || profile.domain().isEmpty()) {
                continue;
            }
            for (Domain.Interval interval : profile.domain().intervals()) {
                ordered.add(profile.restrict(Domain.of(interval)));
            }
        }
        ordered.sort(Comparator
                .comparingDouble((TimeProfile profile) -> profile.domain().intervals().get(0).start())
                .thenComparing(profile -> !profile.domain().intervals().get(0).startInclusive())
                .thenComparingDouble(profile -> profile.domain().intervals().get(0).end()));
        for (int left = 0; left < ordered.size(); left++) {
            for (int right = left + 1; right < ordered.size(); right++) {
                if (!ordered.get(left).domain().intersection(ordered.get(right).domain()).isEmpty()) {
                    throw new IllegalArgumentException("profile cells must have disjoint owned domains");
                }
            }
        }
        this.cells = List.copyOf(ordered);
    }

    /** Empty function, used for an unreachable source-destination pair. */
    public static ExactTemporalProfile empty() {
        return new ExactTemporalProfile(List.of());
    }

    /** Identity arrival mapping over the supplied departure domain. */
    public static ExactTemporalProfile identity(Domain domain) {
        Objects.requireNonNull(domain, "domain");
        List<TimeProfile> result = new ArrayList<>();
        for (Domain.Interval interval : domain.intervals()) {
            result.add(TimeProfile.piecewise(
                    Domain.of(interval),
                    interval.start() == interval.end()
                            ? List.of(new TimeProfile.Breakpoint(interval.start(), interval.start()))
                            : List.of(
                                    new TimeProfile.Breakpoint(interval.start(), interval.start()),
                                    new TimeProfile.Breakpoint(interval.end(), interval.end())),
                    "identity-cell:" + interval));
        }
        return new ExactTemporalProfile(result);
    }

    /** Converts each connected edge-function support interval to its arrival map. */
    public static ExactTemporalProfile edgeArrival(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        List<TimeProfile> result = new ArrayList<>();
        for (Domain.Interval interval : edge.travelTimeFunction().domain().intervals()) {
            TreeSet<Double> cuts = new TreeSet<>();
            cuts.add(interval.start());
            cuts.add(interval.end());
            edge.travelTimeFunction().breakpoints().stream()
                    .map(point -> Domain.canonicalTime(point.minute()))
                    .filter(point -> point > interval.start() && point < interval.end())
                    .forEach(cuts::add);
            List<TimeProfile.Breakpoint> points = cuts.stream()
                    .map(time -> new TimeProfile.Breakpoint(
                            time,
                            edge.travelTimeFunction().arrivalTimeAtClosure(time)))
                    .toList();
            result.add(TimeProfile.piecewise(
                    Domain.of(interval), points, "edge-arrival:" + edge.arcId() + ":" + interval));
        }
        return new ExactTemporalProfile(result);
    }

    /** Builds a wrapper around an existing profile, splitting its domain cells. */
    public static ExactTemporalProfile from(TimeProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new ExactTemporalProfile(List.of(profile));
    }

    /** Rebuilds an exact profile from disjoint serialized function cells. */
    static ExactTemporalProfile fromCells(Collection<TimeProfile> cells) {
        Objects.requireNonNull(cells, "cells");
        return new ExactTemporalProfile(cells);
    }

    /** Whether this function has no supported temporal cells. */
    public boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Disjoint cells carrying the exact function. */
    public List<TimeProfile> cells() {
        return cells;
    }

    /** Union of the valid input times. */
    public Domain domain() {
        Domain result = Domain.empty();
        for (TimeProfile cell : cells) {
            result = result.union(cell.domain());
        }
        return result;
    }

    /** Number of stored affine pieces, including singleton endpoint cells. */
    public long pieceCount() {
        return cells.stream().mapToLong(cell -> Math.max(1, cell.breakpoints().size() - 1)).sum();
    }

    /** Number of stored profile breakpoints. */
    public long breakpointCount() {
        return cells.stream().mapToLong(cell -> cell.breakpoints().size()).sum();
    }

    /** Evaluates the function at a supported time. */
    public double valueAt(double time) {
        for (TimeProfile cell : cells) {
            if (cell.domain().contains(time)) {
                return cell.valueAt(time);
            }
        }
        throw new IllegalArgumentException("time is outside profile support: " + time);
    }

    /** Earliest value over all cells; the input profiles here are FIFO arrivals. */
    public double minimumValue() {
        if (cells.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        double result = Double.POSITIVE_INFINITY;
        for (TimeProfile cell : cells) {
            for (TimeProfile.Breakpoint point : cell.breakpoints()) {
                result = Math.min(result, point.value());
            }
        }
        return Domain.canonicalTime(result);
    }

    /**
     * Restricts the function, returning an empty profile if the support does
     * not intersect the requested domain.
     */
    public ExactTemporalProfile restrict(Domain requested) {
        Objects.requireNonNull(requested, "requested");
        List<TimeProfile> result = new ArrayList<>();
        for (TimeProfile cell : cells) {
            Domain intersection = cell.domain().intersection(requested);
            for (Domain.Interval interval : intersection.intervals()) {
                result.add(cell.restrict(Domain.of(interval)));
            }
        }
        return new ExactTemporalProfile(result);
    }

    /**
     * Composes this inner mapping with {@code outer}: result(t) is
     * {@code outer(this(t))}. This is arrival-time composition for consecutive
     * path segments.
     */
    public ExactTemporalProfile composeAfter(ExactTemporalProfile outer) {
        Objects.requireNonNull(outer, "outer");
        if (isEmpty() || outer.isEmpty()) {
            return empty();
        }
        TemporalProfileWork.increment("thop_temporal_compositions");
        List<TimeProfile> result = new ArrayList<>();
        for (TimeProfile innerCell : cells) {
            for (TimeProfile outerCell : outer.cells) {
                TimeProfile composed = innerCell.composeOrNull(
                        outerCell,
                        "composed:" + innerCell.fingerprint() + ":" + outerCell.fingerprint(),
                        () -> false);
                if (composed != null) {
                    result.add(composed);
                }
            }
        }
        return new ExactTemporalProfile(result);
    }

    /** Exact pointwise lower envelope over both functions' supported times. */
    public ExactTemporalProfile pointwiseMinimum(ExactTemporalProfile other) {
        Objects.requireNonNull(other, "other");
        if (isEmpty()) {
            return other;
        }
        if (other.isEmpty()) {
            return this;
        }
        TemporalProfileWork.increment("thop_envelope_operations");

        TreeSet<Double> baseCuts = new TreeSet<>();
        addCuts(baseCuts, cells);
        addCuts(baseCuts, other.cells);
        List<Double> base = List.copyOf(baseCuts);
        TreeSet<Double> cuts = new TreeSet<>(baseCuts);

        for (int index = 1; index < base.size(); index++) {
            double start = base.get(index - 1);
            double end = base.get(index);
            if (start == end) {
                continue;
            }
            List<TimeProfile> active = new ArrayList<>();
            active.addAll(cellsCoveringOpenInterval(cells, start, end));
            active.addAll(cellsCoveringOpenInterval(other.cells, start, end));
            for (int left = 0; left < active.size(); left++) {
                for (int right = left + 1; right < active.size(); right++) {
                    addCrossing(cuts, active.get(left), active.get(right), start, end);
                }
            }
        }

        List<Double> allCuts = List.copyOf(cuts);
        List<TimeProfile> result = new ArrayList<>();
        for (int index = 1; index < allCuts.size(); index++) {
            double start = allCuts.get(index - 1);
            double end = allCuts.get(index);
            if (start == end) {
                continue;
            }
            List<TimeProfile> active = new ArrayList<>();
            active.addAll(cellsCoveringOpenInterval(cells, start, end));
            active.addAll(cellsCoveringOpenInterval(other.cells, start, end));
            if (active.isEmpty()) {
                continue;
            }
            double probe = start + (end - start) / 2.0;
            TimeProfile winner = active.get(0);
            double best = interpolate(winner, start, end, probe);
            for (int candidateIndex = 1; candidateIndex < active.size(); candidateIndex++) {
                TimeProfile candidate = active.get(candidateIndex);
                double candidateValue = interpolate(candidate, start, end, probe);
                if (candidateValue < best) {
                    winner = candidate;
                    best = candidateValue;
                }
            }
            result.add(TimeProfile.piecewise(
                    Domain.open(start, end),
                    List.of(
                            new TimeProfile.Breakpoint(start, winner.valueAtClosure(start)),
                            new TimeProfile.Breakpoint(end, winner.valueAtClosure(end))),
                    "minimum-open:" + start + ":" + end));
        }

        for (double cut : allCuts) {
            Double left = valueAtOwnedPoint(cells, cut);
            Double right = valueAtOwnedPoint(other.cells, cut);
            if (left == null && right == null) {
                continue;
            }
            double value = left == null ? right : right == null ? left : Math.min(left, right);
            result.add(TimeProfile.piecewise(
                    Domain.closed(cut, cut),
                    List.of(new TimeProfile.Breakpoint(cut, value)),
                    "minimum-point:" + cut));
        }
        return new ExactTemporalProfile(result);
    }

    /** Converts an arrival mapping A(t) to the travel-time function A(t)-t. */
    public ExactTemporalProfile asTravelTime() {
        List<TimeProfile> result = new ArrayList<>();
        for (TimeProfile cell : cells) {
            List<TimeProfile.Breakpoint> points = cell.breakpoints().stream()
                    .map(point -> new TimeProfile.Breakpoint(
                            point.minute(), point.value() - point.minute()))
                    .toList();
            result.add(TimeProfile.piecewise(
                    cell.domain(), points, "travel-time:" + cell.fingerprint()));
        }
        return new ExactTemporalProfile(result);
    }

    /** Exact structural comparison over all cells and open temporal slabs. */
    public boolean sameFunction(ExactTemporalProfile other) {
        if (other == null || !domain().equals(other.domain())) {
            return false;
        }
        TreeSet<Double> cuts = new TreeSet<>();
        addCuts(cuts, cells);
        addCuts(cuts, other.cells);
        List<Double> ordered = List.copyOf(cuts);
        for (double cut : ordered) {
            if (!domain().contains(cut)) {
                continue;
            }
            if (Domain.canonicalTime(valueAt(cut))
                    != Domain.canonicalTime(other.valueAt(cut))) {
                return false;
            }
        }
        for (int index = 1; index < ordered.size(); index++) {
            double start = ordered.get(index - 1);
            double end = ordered.get(index);
            if (start == end) {
                continue;
            }
            TimeProfile left = firstCellCoveringOpenInterval(cells, start, end);
            TimeProfile right = firstCellCoveringOpenInterval(other.cells, start, end);
            if ((left == null) != (right == null)) {
                return false;
            }
            if (left != null
                    && (Domain.canonicalTime(left.valueAtClosure(start))
                            != Domain.canonicalTime(right.valueAtClosure(start))
                    || Domain.canonicalTime(left.valueAtClosure(end))
                            != Domain.canonicalTime(right.valueAtClosure(end)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether this profile is no worse than {@code other} everywhere
     * where {@code other} is defined. Support is part of the comparison: a
     * profile cannot dominate departures at which it is unavailable.
     *
     * <p>Both profiles are piecewise linear. Comparing their values at every
     * owned singleton and at both limits of each open slab split by all
     * breakpoints is therefore an exact full-domain test; no temporal samples
     * are used.</p>
     */
    public boolean dominates(ExactTemporalProfile other) {
        Objects.requireNonNull(other, "other");
        if (!other.domain().difference(domain()).isEmpty()) {
            return false;
        }
        if (other.isEmpty()) {
            return true;
        }

        TreeSet<Double> cuts = new TreeSet<>();
        addCuts(cuts, cells);
        addCuts(cuts, other.cells);
        List<Double> ordered = List.copyOf(cuts);

        for (double cut : ordered) {
            if (!other.domain().contains(cut)) {
                continue;
            }
            if (Domain.canonicalTime(valueAt(cut))
                    > Domain.canonicalTime(other.valueAt(cut))) {
                return false;
            }
        }

        for (int index = 1; index < ordered.size(); index++) {
            double start = ordered.get(index - 1);
            double end = ordered.get(index);
            if (start == end) {
                continue;
            }
            TimeProfile candidate = firstCellCoveringOpenInterval(other.cells, start, end);
            if (candidate == null) {
                continue;
            }
            TimeProfile dominator = firstCellCoveringOpenInterval(cells, start, end);
            if (dominator == null
                    || Domain.canonicalTime(dominator.valueAtClosure(start))
                            > Domain.canonicalTime(candidate.valueAtClosure(start))
                    || Domain.canonicalTime(dominator.valueAtClosure(end))
                            > Domain.canonicalTime(candidate.valueAtClosure(end))) {
                return false;
            }
        }
        return true;
    }

    private static void addCuts(TreeSet<Double> cuts, List<TimeProfile> profiles) {
        for (TimeProfile profile : profiles) {
            for (Domain.Interval interval : profile.domain().intervals()) {
                cuts.add(interval.start());
                cuts.add(interval.end());
            }
            profile.breakpoints().stream()
                    .map(TimeProfile.Breakpoint::minute)
                    .forEach(cuts::add);
        }
    }

    private static List<TimeProfile> cellsCoveringOpenInterval(
            List<TimeProfile> profiles, double start, double end) {
        List<TimeProfile> result = new ArrayList<>();
        for (TimeProfile profile : profiles) {
            if (coversOpenInterval(profile, start, end)) {
                result.add(profile);
            }
        }
        return result;
    }

    private static TimeProfile firstCellCoveringOpenInterval(
            List<TimeProfile> profiles, double start, double end) {
        for (TimeProfile profile : profiles) {
            if (coversOpenInterval(profile, start, end)) {
                return profile;
            }
        }
        return null;
    }

    private static boolean coversOpenInterval(TimeProfile profile, double start, double end) {
        for (Domain.Interval interval : profile.domain().intervals()) {
            if (interval.start() <= start && interval.end() >= end && start < end) {
                return true;
            }
        }
        return false;
    }

    private static Double valueAtOwnedPoint(List<TimeProfile> profiles, double time) {
        Double best = null;
        for (TimeProfile profile : profiles) {
            if (profile.domain().contains(time)) {
                double value = profile.valueAt(time);
                best = best == null ? value : Math.min(best, value);
            }
        }
        return best;
    }

    private static void addCrossing(
            TreeSet<Double> cuts,
            TimeProfile left,
            TimeProfile right,
            double start,
            double end) {
        double leftStart = left.valueAtClosure(start);
        double leftEnd = left.valueAtClosure(end);
        double rightStart = right.valueAtClosure(start);
        double rightEnd = right.valueAtClosure(end);
        double startDifference = Domain.canonicalTime(leftStart - rightStart);
        double endDifference = Domain.canonicalTime(leftEnd - rightEnd);
        int startSign = Double.compare(startDifference, 0.0);
        int endSign = Double.compare(endDifference, 0.0);
        if (startSign == 0 || endSign == 0 || startSign == endSign) {
            return;
        }
        double crossing = Domain.canonicalTime(
                start - startDifference * (end - start) / (endDifference - startDifference));
        if (crossing > start && crossing < end) {
            cuts.add(crossing);
        }
    }

    private static double interpolate(TimeProfile profile, double start, double end, double time) {
        double startValue = profile.valueAtClosure(start);
        double endValue = profile.valueAtClosure(end);
        if (end == start) {
            return startValue;
        }
        double alpha = (time - start) / (end - start);
        return startValue + alpha * (endValue - startValue);
    }
}
