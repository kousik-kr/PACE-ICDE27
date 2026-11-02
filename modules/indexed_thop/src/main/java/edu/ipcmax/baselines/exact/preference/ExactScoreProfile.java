package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.Edge;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Piecewise-constant score values on exact rational, endpoint-owned domains. */
public final class ExactScoreProfile {
    public record Segment(BigInteger value, ExactTemporalDomain.Interval interval) {
        public Segment {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(interval, "interval");
            if (value.signum() < 0) throw new IllegalArgumentException("score cannot be negative");
        }
    }

    private static final Comparator<Segment> ORDER = Comparator
            .comparing((Segment segment) -> segment.interval().start())
            .thenComparing(segment -> !segment.interval().startInclusive())
            .thenComparing(segment -> segment.interval().end());

    private final List<Segment> segments;
    private final ExactTemporalDomain domain;

    private ExactScoreProfile(Collection<Segment> supplied) {
        this(supplied, null);
    }

    private ExactScoreProfile(Collection<Segment> supplied, BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        List<Segment> ordered = new ArrayList<>(supplied);
        ordered.sort((left, right) -> {
            checkCancelled(cancellationRequested);
            return ORDER.compare(left, right);
        });
        ExactTime furthestEnd = null;
        boolean furthestEndIncluded = false;
        List<ExactTemporalDomain.Interval> intervals = new ArrayList<>(ordered.size());
        for (Segment segment : ordered) {
            checkCancelled(cancellationRequested);
            var interval = segment.interval();
            if (furthestEnd != null) {
                int order = interval.start().compareTo(furthestEnd);
                if (order < 0 || (order == 0 && interval.startInclusive() && furthestEndIncluded)) {
                    throw new IllegalArgumentException("score segments overlap on owned endpoints");
                }
                int endOrder = interval.end().compareTo(furthestEnd);
                if (endOrder > 0) {
                    furthestEnd = interval.end();
                    furthestEndIncluded = interval.endInclusive();
                } else if (endOrder == 0) {
                    furthestEndIncluded |= interval.endInclusive();
                }
            } else {
                furthestEnd = interval.end();
                furthestEndIncluded = interval.endInclusive();
            }
            intervals.add(interval);
        }
        this.segments = List.copyOf(ordered);
        this.domain = ExactTemporalDomain.of(intervals, cancellationRequested);
    }

    public static ExactScoreProfile empty() {
        return new ExactScoreProfile(List.of());
    }

    public static ExactScoreProfile ofSegments(Collection<Segment> segments) {
        return new ExactScoreProfile(segments);
    }

    public static ExactScoreProfile ofSegments(Collection<Segment> segments, BooleanSupplier cancellationRequested) {
        return new ExactScoreProfile(segments, cancellationRequested);
    }

    public static ExactScoreProfile constant(ExactTemporalDomain domain, BigInteger value) {
        return constant(domain, value, null);
    }

    public static ExactScoreProfile constant(
            ExactTemporalDomain domain, BigInteger value, BooleanSupplier cancellationRequested) {
        List<Segment> segments = new ArrayList<>();
        for (var interval : domain.intervals()) {
            checkCancelled(cancellationRequested);
            segments.add(new Segment(value, interval));
        }
        return ofSegments(segments, cancellationRequested);
    }

    /** Converts already-canonicalized input score pieces to exact rational supports. */
    public static ExactScoreProfile edgeScore(Edge edge) {
        return edgeScore(edge, () -> false);
    }

    public static ExactScoreProfile edgeScore(Edge edge, BooleanSupplier cancellationRequested) {
        PiecewiseConstFn function = edge.scoreFunction();
        List<PiecewiseConstFn.Interval> pieces = function.intervals();
        List<Segment> result = new ArrayList<>();
        ExactTemporalDomain edgeDomain = ExactTemporalDomain.fromLegacy(function.domain());
        for (int i = 0; i < pieces.size(); i++) {
            checkCancelled(cancellationRequested);
            PiecewiseConstFn.Interval piece = pieces.get(i);
            ExactTime start = ExactTime.fromCanonicalDouble(piece.startMinute());
            ExactTime end = ExactTime.fromCanonicalDouble(piece.endMinute());
            boolean endIncluded = i + 1 == pieces.size()
                    || pieces.get(i + 1).startMinute() != piece.endMinute();
            ExactTemporalDomain.Interval interval = piece.startMinute() == piece.endMinute()
                    ? ExactTemporalDomain.Interval.singleton(start)
                    : new ExactTemporalDomain.Interval(start, end, true, endIncluded);
            for (ExactTemporalDomain.Interval support : edgeDomain.intervals()) {
                checkCancelled(cancellationRequested);
                ExactTemporalDomain.Interval supported = interval.intersection(support);
                if (supported != null) result.add(new Segment(BigInteger.valueOf(piece.value()), supported));
            }
        }
        return ofSegments(result, cancellationRequested);
    }

    public List<Segment> segments() { return segments; }
    public ExactTemporalDomain domain() { return domain; }
    public boolean isEmpty() { return segments.isEmpty(); }
    public long pieceCount() { return segments.size(); }

    public BigInteger valueAt(ExactTime time) {
        return valueAt(time, () -> false);
    }

    public BigInteger valueAt(ExactTime time, BooleanSupplier cancellationRequested) {
        for (Segment segment : segments) {
            checkCancelled(cancellationRequested);
            if (segment.interval().contains(time)) return segment.value();
        }
        throw new IllegalArgumentException("time is outside exact score support: " + time);
    }

    /** Pulls score steps back through every exact affine cell of an arrival profile. */
    public ExactScoreProfile pullback(ExactArrivalProfile mapping) {
        return pullback(mapping, () -> false);
    }

    public ExactScoreProfile pullback(ExactArrivalProfile mapping, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_score_profile_pullbacks");
        List<Segment> result = new ArrayList<>();
        for (var affine : mapping.segments()) {
            checkCancelled(cancellationRequested);
            // Only score pieces meeting the closed image of this affine piece can
            // produce a nonempty overlap; every other pair yields null.
            ExactTime first = affine.valueAtClosure(affine.interval().start());
            ExactTime last = affine.valueAtClosure(affine.interval().end());
            ExactTime low = first.compareTo(last) <= 0 ? first : last;
            ExactTime high = first.compareTo(last) <= 0 ? last : first;
            for (int j = firstEndingAtOrAfter(segments, low); j < segments.size(); j++) {
                checkCancelled(cancellationRequested);
                Segment score = segments.get(j);
                if (score.interval().start().compareTo(high) > 0) break;
                ExactTemporalDomain.Interval preimage = preimage(
                        affine.slope(), affine.intercept(), score.interval(), affine.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = affine.interval().intersection(preimage);
                if (overlap != null) result.add(new Segment(score.value(), overlap));
            }
        }
        return ofSegments(result, cancellationRequested);
    }

    /** Reference all-pairs pullback; kept to differential-test {@link #pullback}. */
    ExactScoreProfile pullbackAllPairs(ExactArrivalProfile mapping) {
        List<Segment> result = new ArrayList<>();
        for (var affine : mapping.segments()) {
            for (Segment score : segments) {
                ExactTemporalDomain.Interval preimage = preimage(
                        affine.slope(), affine.intercept(), score.interval(), affine.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = affine.interval().intersection(preimage);
                if (overlap != null) result.add(new Segment(score.value(), overlap));
            }
        }
        return ofSegments(result);
    }

    private static int firstEndingAtOrAfter(List<Segment> sorted, ExactTime time) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted.get(middle).interval().end().compareTo(time) < 0) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Pointwise sum, using unbounded integers for the accumulated score. */
    public ExactScoreProfile plus(ExactScoreProfile other) {
        return plus(other, () -> false);
    }

    public ExactScoreProfile plus(ExactScoreProfile other, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_score_profile_compositions");
        List<Segment> result = new ArrayList<>();
        // Both piece lists are disjoint and sorted, so only closure-overlapping
        // pairs need an intersection; a right piece ending before one left piece
        // also ends before every later left piece.
        int from = 0;
        for (Segment left : segments) {
            checkCancelled(cancellationRequested);
            while (from < other.segments.size()
                    && other.segments.get(from).interval().end().compareTo(left.interval().start()) < 0) from++;
            for (int j = from; j < other.segments.size(); j++) {
                checkCancelled(cancellationRequested);
                Segment right = other.segments.get(j);
                if (right.interval().start().compareTo(left.interval().end()) > 0) break;
                ExactTemporalDomain.Interval overlap = left.interval().intersection(right.interval());
                if (overlap != null) result.add(new Segment(left.value().add(right.value()), overlap));
            }
        }
        return ofSegments(result, cancellationRequested);
    }

    /** Reference all-pairs sum; kept to differential-test {@link #plus}. */
    ExactScoreProfile plusAllPairs(ExactScoreProfile other) {
        List<Segment> result = new ArrayList<>();
        for (Segment left : segments) {
            for (Segment right : other.segments) {
                ExactTemporalDomain.Interval overlap = left.interval().intersection(right.interval());
                if (overlap != null) result.add(new Segment(left.value().add(right.value()), overlap));
            }
        }
        return ofSegments(result);
    }

    public ExactScoreProfile restrict(ExactTemporalDomain requested) {
        return restrict(requested, () -> false);
    }

    public ExactScoreProfile restrict(ExactTemporalDomain requested, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_profile_restrictions");
        List<Segment> result = new ArrayList<>();
        for (Segment segment : segments) {
            checkCancelled(cancellationRequested);
            for (ExactTemporalDomain.Interval interval : requested.intervals()) {
                checkCancelled(cancellationRequested);
                ExactTemporalDomain.Interval overlap = segment.interval().intersection(interval);
                if (overlap != null) result.add(new Segment(segment.value(), overlap));
            }
        }
        return ofSegments(result, cancellationRequested);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("exact score-profile operation cancelled");
        }
    }

    public int maximumRationalComponentBitLength() {
        int result = 0;
        for (Segment segment : segments) {
            result = Math.max(result, Math.max(segment.value().abs().bitLength(),
                    Math.max(segment.interval().start().numerator().abs().bitLength(),
                            Math.max(segment.interval().start().denominator().bitLength(),
                                    Math.max(segment.interval().end().numerator().abs().bitLength(),
                                            segment.interval().end().denominator().bitLength())))));
        }
        return result;
    }

    private static ExactTemporalDomain.Interval preimage(
            ExactTime slope, ExactTime intercept, ExactTemporalDomain.Interval target,
            ExactTemporalDomain.Interval source) {
        if (slope.signum() == 0) return target.contains(intercept) ? source : null;
        ExactTime low = target.start().subtract(intercept).divide(slope);
        ExactTime high = target.end().subtract(intercept).divide(slope);
        boolean lowIncluded = target.startInclusive();
        boolean highIncluded = target.endInclusive();
        if (slope.signum() < 0) {
            ExactTime swap = low; low = high; high = swap;
            boolean included = lowIncluded; lowIncluded = highIncluded; highIncluded = included;
        }
        return new ExactTemporalDomain.Interval(low, high, lowIncluded, highIncluded);
    }
}
