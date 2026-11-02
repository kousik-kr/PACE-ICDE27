package edu.ipcmax.baselines.exact;

import edu.ipcmax.core.function.Domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Exact rational domain, including disconnected intervals and owned endpoints. */
public final class ExactTemporalDomain {
    public record Interval(ExactTime start, ExactTime end, boolean startInclusive, boolean endInclusive) {
        public Interval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            int order = start.compareTo(end);
            if (order > 0 || (order == 0 && !(startInclusive && endInclusive))) {
                throw new IllegalArgumentException("invalid exact temporal interval");
            }
        }

        public static Interval closed(ExactTime start, ExactTime end) {
            return new Interval(start, end, true, true);
        }

        public static Interval singleton(ExactTime at) {
            return closed(at, at);
        }

        public boolean isSingleton() {
            return start.equals(end);
        }

        public boolean contains(ExactTime value) {
            int from = value.compareTo(start);
            int to = value.compareTo(end);
            return (from > 0 || (from == 0 && startInclusive))
                    && (to < 0 || (to == 0 && endInclusive));
        }

        public boolean containsClosure(ExactTime value) {
            return value.compareTo(start) >= 0 && value.compareTo(end) <= 0;
        }

        public Interval intersection(Interval other) {
            ExactTime low;
            boolean lowIncluded;
            int lowOrder = start.compareTo(other.start);
            if (lowOrder > 0) {
                low = start;
                lowIncluded = startInclusive;
            } else if (lowOrder < 0) {
                low = other.start;
                lowIncluded = other.startInclusive;
            } else {
                low = start;
                lowIncluded = startInclusive && other.startInclusive;
            }
            ExactTime high;
            boolean highIncluded;
            int highOrder = end.compareTo(other.end);
            if (highOrder < 0) {
                high = end;
                highIncluded = endInclusive;
            } else if (highOrder > 0) {
                high = other.end;
                highIncluded = other.endInclusive;
            } else {
                high = end;
                highIncluded = endInclusive && other.endInclusive;
            }
            int compare = low.compareTo(high);
            if (compare < 0) {
                return new Interval(low, high, lowIncluded, highIncluded);
            }
            return compare == 0 && lowIncluded && highIncluded ? singleton(low) : null;
        }
    }

    private static final Comparator<Interval> ORDER = Comparator.comparing(Interval::start)
            .thenComparing(interval -> !interval.startInclusive())
            .thenComparing(Interval::end)
            .thenComparing(interval -> !interval.endInclusive());

    private final List<Interval> intervals;

    private ExactTemporalDomain(List<Interval> intervals) {
        this(intervals, null);
    }

    private ExactTemporalDomain(List<Interval> intervals, BooleanSupplier cancellationRequested) {
        this.intervals = normalize(intervals, cancellationRequested);
    }

    public static ExactTemporalDomain empty() {
        return new ExactTemporalDomain(List.of());
    }

    public static ExactTemporalDomain of(Interval... intervals) {
        return new ExactTemporalDomain(List.of(intervals));
    }

    public static ExactTemporalDomain of(List<Interval> intervals) {
        return new ExactTemporalDomain(intervals);
    }

    /** Cancellable normalization, preserving exact endpoint ownership. */
    public static ExactTemporalDomain of(List<Interval> intervals, BooleanSupplier cancellationRequested) {
        return new ExactTemporalDomain(intervals, cancellationRequested);
    }

    /** Legacy domain bounds have already crossed the repository input canonicalization boundary. */
    public static ExactTemporalDomain fromLegacy(Domain domain) {
        Objects.requireNonNull(domain, "domain");
        List<Interval> result = new ArrayList<>();
        for (Domain.Interval interval : domain.intervals()) {
            result.add(new Interval(
                    ExactTime.fromCanonicalDouble(interval.start()),
                    ExactTime.fromCanonicalDouble(interval.end()),
                    interval.startInclusive(), interval.endInclusive()));
        }
        return of(result);
    }

    public List<Interval> intervals() {
        return intervals;
    }

    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    public boolean contains(ExactTime value) {
        for (Interval interval : intervals) {
            if (interval.contains(value)) return true;
            if (value.compareTo(interval.start()) < 0) return false;
        }
        return false;
    }

    public ExactTemporalDomain union(ExactTemporalDomain other) {
        return union(other, null);
    }

    public ExactTemporalDomain union(ExactTemporalDomain other, BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        List<Interval> combined = new ArrayList<>(intervals);
        combined.addAll(other.intervals);
        return of(combined, cancellationRequested);
    }

    public ExactTemporalDomain intersection(ExactTemporalDomain other) {
        return intersection(other, () -> false);
    }

    public ExactTemporalDomain intersection(ExactTemporalDomain other, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_interval_intersections");
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < intervals.size() && j < other.intervals.size()) {
            checkCancelled(cancellationRequested);
            Interval a = intervals.get(i);
            Interval b = other.intervals.get(j);
            Interval overlap = a.intersection(b);
            if (overlap != null) result.add(overlap);
            int ends = a.end().compareTo(b.end());
            if (ends <= 0) i++;
            if (ends >= 0) j++;
        }
        return of(result, cancellationRequested);
    }

    public ExactTemporalDomain difference(ExactTemporalDomain other) {
        return difference(other, () -> false);
    }

    public ExactTemporalDomain difference(ExactTemporalDomain other, BooleanSupplier cancellationRequested) {
        List<Interval> fragments = new ArrayList<>(intervals);
        for (Interval cut : other.intervals) {
            checkCancelled(cancellationRequested);
            List<Interval> next = new ArrayList<>();
            for (Interval source : fragments) {
                checkCancelled(cancellationRequested);
                subtract(source, cut, next);
            }
            fragments = next;
        }
        return of(fragments, cancellationRequested);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("exact temporal-domain operation cancelled");
        }
    }

    /** Returns a rounded legacy view; rational event locations cannot survive this projection. */
    public Domain roundedLegacyProjection() {
        List<Domain.Interval> rounded = new ArrayList<>();
        for (Interval interval : intervals) {
            double start = interval.start().toDisplayDouble();
            double end = interval.end().toDisplayDouble();
            if (start == end) {
                if (interval.startInclusive() && interval.endInclusive()) {
                    rounded.add(new Domain.Interval(start, end));
                }
            } else {
                rounded.add(new Domain.Interval(start, end,
                        interval.startInclusive(), interval.endInclusive()));
            }
        }
        return rounded.isEmpty() ? Domain.empty() : Domain.of(rounded.toArray(Domain.Interval[]::new));
    }

    private static void subtract(Interval source, Interval cut, List<Interval> output) {
        Interval overlap = source.intersection(cut);
        if (overlap == null) {
            output.add(source);
            return;
        }
        if (source.start().compareTo(overlap.start()) < 0
                || (source.start().equals(overlap.start()) && source.startInclusive() && !overlap.startInclusive())) {
            addIfNonempty(output, new Interval(source.start(), overlap.start(), source.startInclusive(), !overlap.startInclusive()));
        }
        if (overlap.end().compareTo(source.end()) < 0
                || (overlap.end().equals(source.end()) && source.endInclusive() && !overlap.endInclusive())) {
            addIfNonempty(output, new Interval(overlap.end(), source.end(), !overlap.endInclusive(), source.endInclusive()));
        }
    }

    private static void addIfNonempty(List<Interval> intervals, Interval interval) {
        if (interval.start().compareTo(interval.end()) < 0
                || (interval.startInclusive() && interval.endInclusive())) intervals.add(interval);
    }

    private static List<Interval> normalize(List<Interval> supplied, BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        List<Interval> sorted = new ArrayList<>(supplied);
        sorted.sort((left, right) -> {
            checkCancelled(cancellationRequested);
            return ORDER.compare(left, right);
        });
        List<Interval> result = new ArrayList<>();
        for (Interval next : sorted) {
            checkCancelled(cancellationRequested);
            if (result.isEmpty()) {
                result.add(next);
                continue;
            }
            Interval prior = result.get(result.size() - 1);
            int touching = next.start().compareTo(prior.end());
            if (touching < 0 || (touching == 0 && (prior.endInclusive() || next.startInclusive()))) {
                ExactTime end;
                boolean endIncluded;
                int endOrder = next.end().compareTo(prior.end());
                if (endOrder > 0) {
                    end = next.end(); endIncluded = next.endInclusive();
                } else if (endOrder < 0) {
                    end = prior.end(); endIncluded = prior.endInclusive();
                } else {
                    end = prior.end(); endIncluded = prior.endInclusive() || next.endInclusive();
                }
                result.set(result.size() - 1, new Interval(prior.start(), end,
                        prior.startInclusive(), endIncluded));
            } else {
                result.add(next);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExactTemporalDomain domain && intervals.equals(domain.intervals);
    }

    @Override
    public int hashCode() {
        return intervals.hashCode();
    }

    @Override
    public String toString() {
        return intervals.toString();
    }
}
