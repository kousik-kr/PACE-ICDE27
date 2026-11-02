package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Piecewise-constant, nonnegative edge weight used by native SICSP. */
public final class SicspEdgeWeightFn {
    /** A constant value on an exact endpoint-aware time interval. */
    public record Piece(Domain.Interval interval, double value) {
        public Piece {
            Objects.requireNonNull(interval, "interval");
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("weight must be finite and nonnegative");
            }
            value = Domain.canonicalTime(value);
        }
    }

    /** A right-continuous breakpoint value. */
    public record Breakpoint(double minute, double value) {
        public Breakpoint {
            if (!Double.isFinite(minute) || !Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("weight breakpoint must be finite and nonnegative");
            }
            minute = Domain.canonicalTime(minute);
            value = Domain.canonicalTime(value);
        }
    }

    private final List<Piece> pieces;
    private final Domain domain;
    private final List<Double> breakpoints;

    public SicspEdgeWeightFn(List<Piece> suppliedPieces) {
        if (suppliedPieces == null || suppliedPieces.isEmpty()) {
            throw new IllegalArgumentException("at least one weight piece is required");
        }
        List<Piece> ordered = new ArrayList<>(suppliedPieces);
        ordered.sort(Comparator.comparingDouble((Piece piece) -> piece.interval().start())
                .thenComparing(piece -> !piece.interval().startInclusive()));
        Domain support = Domain.empty();
        for (int i = 0; i < ordered.size(); i++) {
            Domain current = Domain.of(ordered.get(i).interval());
            for (int j = 0; j < i; j++) {
                if (!current.intersection(Domain.of(ordered.get(j).interval())).isEmpty()) {
                    throw new IllegalArgumentException("weight pieces overlap");
                }
            }
            support = support.union(current);
        }
        this.pieces = List.copyOf(ordered);
        this.domain = support;
        TreeSet<Double> cuts = new TreeSet<>();
        for (Piece piece : ordered) {
            cuts.add(piece.interval().start());
            cuts.add(piece.interval().end());
        }
        this.breakpoints = List.copyOf(cuts);
    }

    /** Creates a constant edge weight over an exact support domain. */
    public static SicspEdgeWeightFn constant(Domain domain, double value) {
        Objects.requireNonNull(domain, "domain");
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("weight support cannot be empty");
        }
        return new SicspEdgeWeightFn(domain.intervals().stream()
                .map(interval -> new Piece(interval, value))
                .toList());
    }

    /** Builds right-continuous pieces over each connected component of {@code domain}. */
    public static SicspEdgeWeightFn fromBreakpoints(Domain domain, List<Breakpoint> breakpoints) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(breakpoints, "breakpoints");
        if (domain.isEmpty() || breakpoints.isEmpty()) {
            throw new IllegalArgumentException("nonempty domain and breakpoints are required");
        }
        List<Breakpoint> ordered = new ArrayList<>(breakpoints);
        ordered.sort(Comparator.comparingDouble(Breakpoint::minute));
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i - 1).minute() == ordered.get(i).minute()) {
                throw new IllegalArgumentException("weight breakpoint times must be unique");
            }
        }
        if (ordered.get(0).minute() > domain.intervals().get(0).start()) {
            throw new IllegalArgumentException("weight breakpoints do not define the start of the function domain");
        }

        List<Piece> result = new ArrayList<>();
        for (Domain.Interval component : domain.intervals()) {
            List<Double> cuts = new ArrayList<>();
            cuts.add(component.start());
            for (Breakpoint breakpoint : ordered) {
                if (breakpoint.minute() > component.start() && breakpoint.minute() < component.end()) {
                    cuts.add(breakpoint.minute());
                }
            }
            cuts.add(component.end());
            if (component.start() == component.end()) {
                result.add(new Piece(component, valueImmediatelyAtOrBefore(ordered, component.start())));
                continue;
            }
            for (int i = 1; i < cuts.size(); i++) {
                double start = cuts.get(i - 1);
                double end = cuts.get(i);
                boolean isLast = i == cuts.size() - 1;
                Domain.Interval pieceDomain = new Domain.Interval(
                        start,
                        end,
                        true,
                        isLast && component.endInclusive());
                result.add(new Piece(pieceDomain, valueImmediatelyAtOrBefore(ordered, start)));
            }
        }
        return new SicspEdgeWeightFn(result);
    }

    private static double valueImmediatelyAtOrBefore(List<Breakpoint> breakpoints, double time) {
        double value = breakpoints.get(0).value();
        for (Breakpoint breakpoint : breakpoints) {
            if (breakpoint.minute() > time) {
                break;
            }
            value = breakpoint.value();
        }
        return value;
    }

    public List<Piece> pieces() {
        return pieces;
    }

    public Domain domain() {
        return domain;
    }

    /** All support boundaries and weight-change times, in increasing order. */
    public List<Double> breakpoints() {
        return breakpoints;
    }

    public double valueAt(double time) {
        for (Piece piece : pieces) {
            if (piece.interval().contains(time)) {
                return piece.value();
            }
        }
        throw new IllegalArgumentException("time is outside edge-weight domain: " + time);
    }
}
