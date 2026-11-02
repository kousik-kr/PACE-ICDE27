package edu.ipcmax.baselines.exact;

import edu.ipcmax.baselines.ExactTemporalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.core.profile.TimeProfile;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Piecewise-affine function whose support and all coefficients are rational. */
public final class ExactArrivalProfile {
    private static final Comparator<ExactAffineSegment> SEGMENT_ORDER = Comparator
            .comparing((ExactAffineSegment segment) -> segment.interval().start())
            .thenComparing(segment -> !segment.interval().startInclusive())
            .thenComparing(segment -> segment.interval().end());

    private final List<ExactAffineSegment> segments;
    private final ExactTemporalDomain domain;

    private ExactArrivalProfile(Collection<ExactAffineSegment> supplied) {
        this(supplied, null);
    }

    private ExactArrivalProfile(Collection<ExactAffineSegment> supplied, BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        List<ExactAffineSegment> ordered = new ArrayList<>(supplied);
        ordered.sort((left, right) -> {
            checkCancelled(cancellationRequested);
            return SEGMENT_ORDER.compare(left, right);
        });
        List<ExactTemporalDomain.Interval> intervals = new ArrayList<>(ordered.size());
        ExactTime furthestEnd = null;
        boolean furthestEndIncluded = false;
        for (ExactAffineSegment segment : ordered) {
            checkCancelled(cancellationRequested);
            ExactTemporalDomain.Interval interval = segment.interval();
            if (furthestEnd != null) {
                int begins = interval.start().compareTo(furthestEnd);
                if (begins < 0 || (begins == 0 && interval.startInclusive() && furthestEndIncluded)) {
                    throw new IllegalArgumentException("exact profile segments must have disjoint owned domains");
                }
                int extendsEnd = interval.end().compareTo(furthestEnd);
                if (extendsEnd > 0) {
                    furthestEnd = interval.end();
                    furthestEndIncluded = interval.endInclusive();
                } else if (extendsEnd == 0) {
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

    public static ExactArrivalProfile empty() {
        return new ExactArrivalProfile(List.of());
    }

    public static ExactArrivalProfile ofSegments(Collection<ExactAffineSegment> segments) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                ExactArrivalProfile built = new ExactArrivalProfile(segments);
                ThopProfile.observeProfile(built.segments.size());
                return built;
            } finally {
                ThopProfile.end(ThopProfile.P_OFSEGMENTS, started);
            }
        }
        return new ExactArrivalProfile(segments);
    }

    public static ExactArrivalProfile ofSegments(
            Collection<ExactAffineSegment> segments, BooleanSupplier cancellationRequested) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                ExactArrivalProfile built = new ExactArrivalProfile(segments, cancellationRequested);
                ThopProfile.observeProfile(built.segments.size());
                return built;
            } finally {
                ThopProfile.end(ThopProfile.P_OFSEGMENTS, started);
            }
        }
        return new ExactArrivalProfile(segments, cancellationRequested);
    }

    public static ExactArrivalProfile identity(Domain domain) {
        return identity(ExactTemporalDomain.fromLegacy(domain));
    }

    public static ExactArrivalProfile identity(ExactTemporalDomain domain) {
        return identity(domain, null);
    }

    public static ExactArrivalProfile identity(ExactTemporalDomain domain, BooleanSupplier cancellationRequested) {
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            checkCancelled(cancellationRequested);
            result.add(new ExactAffineSegment(ExactTime.ONE, ExactTime.ZERO, interval));
        }
        return ofSegments(result, cancellationRequested);
    }

    /** Converts the accepted edge decimals into exact affine arrival pieces. */
    public static ExactArrivalProfile edgeArrival(Edge edge) {
        return edgeArrival(edge, () -> false);
    }

    public static ExactArrivalProfile edgeArrival(Edge edge, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(edge, "edge");
        PiecewiseLinearFn function = edge.travelTimeFunction();
        List<PiecewiseLinearFn.Breakpoint> points = function.breakpoints();
        List<ExactTime> knotTimes = new ArrayList<>(points.size());
        List<ExactTime> knotValues = new ArrayList<>(points.size());
        for (PiecewiseLinearFn.Breakpoint point : points) {
            checkCancelled(cancellationRequested);
            knotTimes.add(ExactTime.fromCanonicalDouble(point.minute()));
            knotValues.add(ExactTime.fromCanonicalDouble(point.value()));
        }
        List<ExactAffineSegment> result = new ArrayList<>();
        ExactTemporalDomain edgeDomain = ExactTemporalDomain.fromLegacy(function.domain());
        for (ExactTemporalDomain.Interval component : edgeDomain.intervals()) {
            checkCancelled(cancellationRequested);
            TreeSet<ExactTime> cuts = new TreeSet<>();
            cuts.add(component.start());
            cuts.add(component.end());
            for (ExactTime knot : knotTimes) {
                checkCancelled(cancellationRequested);
                if (knot.compareTo(component.start()) > 0 && knot.compareTo(component.end()) < 0) cuts.add(knot);
            }
            List<ExactTime> orderedCuts = List.copyOf(cuts);
            if (orderedCuts.size() == 1) {
                checkCancelled(cancellationRequested);
                ExactTime time = orderedCuts.getFirst();
                ExactTime travel = exactTravelAt(time, knotTimes, knotValues);
                result.add(new ExactAffineSegment(ExactTime.ZERO, time.add(travel), component));
                continue;
            }
            for (int i = 0; i + 1 < orderedCuts.size(); i++) {
                checkCancelled(cancellationRequested);
                ExactTime start = orderedCuts.get(i);
                ExactTime end = orderedCuts.get(i + 1);
                if (start.equals(end)) continue;
                ExactTime midpoint = start.add(end).divide(ExactTime.of(2));
                int knot = interpolationSegment(midpoint, knotTimes);
                ExactTime travelSlope = knotValues.get(knot + 1).subtract(knotValues.get(knot))
                        .divide(knotTimes.get(knot + 1).subtract(knotTimes.get(knot)));
                ExactTime travelIntercept = knotValues.get(knot).subtract(travelSlope.multiply(knotTimes.get(knot)));
                boolean startIncluded = i == 0 ? component.startInclusive() : true;
                boolean endIncluded = i == orderedCuts.size() - 2 ? component.endInclusive() : false;
                ExactTemporalDomain.Interval interval = new ExactTemporalDomain.Interval(
                        start, end, startIncluded, endIncluded);
                result.add(new ExactAffineSegment(
                        ExactTime.ONE.add(travelSlope), travelIntercept, interval));
            }
        }
        return ofSegments(result, cancellationRequested);
    }

    public List<ExactAffineSegment> segments() {
        return segments;
    }

    public ExactTemporalDomain domain() {
        return domain;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public long pieceCount() {
        return segments.size();
    }

    public long breakpointCount() {
        TreeSet<ExactTime> points = new TreeSet<>();
        for (ExactAffineSegment segment : segments) {
            points.add(segment.interval().start());
            points.add(segment.interval().end());
        }
        return points.size();
    }

    /** Largest bit length among every numerator and denominator in this profile. */
    public int maximumRationalComponentBitLength() {
        int result = 0;
        for (ExactAffineSegment segment : segments) {
            result = Math.max(result, Math.max(
                    Math.max(segment.slope().numerator().abs().bitLength(), segment.slope().denominator().bitLength()),
                    Math.max(segment.intercept().numerator().abs().bitLength(), segment.intercept().denominator().bitLength())));
            result = Math.max(result, Math.max(
                    Math.max(segment.interval().start().numerator().abs().bitLength(), segment.interval().start().denominator().bitLength()),
                    Math.max(segment.interval().end().numerator().abs().bitLength(), segment.interval().end().denominator().bitLength())));
        }
        return result;
    }

    /** Double query inputs cross the existing canonicalization boundary once here. */
    public ExactTime valueAt(double publicDepartureTime) {
        double canonical = Domain.canonicalTime(publicDepartureTime);
        return valueAtExact(ExactTime.fromCanonicalDouble(canonical));
    }

    public ExactTime valueAtExact(ExactTime time) {
        return valueAtExact(time, () -> false);
    }

    public ExactTime valueAtExact(ExactTime time, BooleanSupplier cancellationRequested) {
        for (ExactAffineSegment segment : segments) {
            checkCancelled(cancellationRequested);
            if (segment.interval().contains(time)) return segment.valueAt(time);
        }
        throw new IllegalArgumentException("time is outside exact profile support: " + time);
    }

    public ExactArrivalProfile restrict(Domain requested) {
        return restrict(ExactTemporalDomain.fromLegacy(requested));
    }

    public ExactArrivalProfile restrict(ExactTemporalDomain requested) {
        return restrict(requested, () -> false);
    }

    public ExactArrivalProfile restrict(ExactTemporalDomain requested, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_profile_restrictions");
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactAffineSegment segment : segments) {
            checkCancelled(cancellationRequested);
            for (ExactTemporalDomain.Interval interval : requested.intervals()) {
                checkCancelled(cancellationRequested);
                ExactAffineSegment restricted = segment.restrict(interval);
                if (restricted != null) result.add(restricted);
            }
        }
        return ofSegments(result, cancellationRequested);
    }

    /** Exact root domain on which this profile maps into {@code target}. */
    public ExactTemporalDomain preimage(ExactTemporalDomain target) {
        return preimage(target, () -> false);
    }

    public ExactTemporalDomain preimage(ExactTemporalDomain target, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_temporal_preimages");
        Objects.requireNonNull(target, "target");
        List<ExactTemporalDomain.Interval> result = new ArrayList<>();
        for (ExactAffineSegment segment : segments) {
            checkCancelled(cancellationRequested);
            for (ExactTemporalDomain.Interval targetInterval : target.intervals()) {
                checkCancelled(cancellationRequested);
                ExactTemporalDomain.Interval roots = preimage(segment, targetInterval);
                if (roots == null) continue;
                ExactTemporalDomain.Interval overlap = segment.interval().intersection(roots);
                if (overlap != null) result.add(overlap);
            }
        }
        return ExactTemporalDomain.of(result, cancellationRequested);
    }

    /** Returns {@code outer(this(t))} on exactly the preimage-supported domain. */
    public ExactArrivalProfile composeAfter(ExactArrivalProfile outer) {
        return composeAfter(outer, () -> false);
    }

    public ExactArrivalProfile composeAfter(
            ExactArrivalProfile outer, BooleanSupplier cancellationRequested) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                return composeAfterUninstrumented(outer, cancellationRequested);
            } finally {
                ThopProfile.end(ThopProfile.P_COMPOSE, started);
            }
        }
        return composeAfterUninstrumented(outer, cancellationRequested);
    }

    private ExactArrivalProfile composeAfterUninstrumented(
            ExactArrivalProfile outer, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_temporal_compositions");
        Objects.requireNonNull(outer, "outer");
        if (isEmpty() || outer.isEmpty()) return empty();
        TemporalProfileWork.increment("thop_temporal_compositions");
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactAffineSegment inner : segments) {
            checkCancelled(cancellationRequested);
            // Only outer pieces meeting the closed image of this piece can produce
            // a nonempty overlap; every other pair yields null in the all-pairs form.
            ExactTime first = inner.valueAtClosure(inner.interval().start());
            ExactTime last = inner.valueAtClosure(inner.interval().end());
            ExactTime low = first.compareTo(last) <= 0 ? first : last;
            ExactTime high = first.compareTo(last) <= 0 ? last : first;
            for (int j = firstEndingAtOrAfter(outer.segments, low); j < outer.segments.size(); j++) {
                checkCancelled(cancellationRequested);
                ExactAffineSegment after = outer.segments.get(j);
                if (after.interval().start().compareTo(high) > 0) break;
                ExactTemporalDomain.Interval preimage = preimage(inner, after.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = inner.interval().intersection(preimage);
                if (overlap != null) result.add(inner.then(after, overlap));
            }
        }
        return ofSegments(result, cancellationRequested).canonicalized();
    }

    /** Reference all-pairs composition; kept to differential-test {@link #composeAfter}. */
    ExactArrivalProfile composeAfterAllPairs(ExactArrivalProfile outer) {
        if (isEmpty() || outer.isEmpty()) return empty();
        List<ExactAffineSegment> result = new ArrayList<>();
        for (ExactAffineSegment inner : segments) {
            for (ExactAffineSegment after : outer.segments) {
                ExactTemporalDomain.Interval preimage = preimage(inner, after.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = inner.interval().intersection(preimage);
                if (overlap != null) result.add(inner.then(after, overlap));
            }
        }
        return ofSegments(result).canonicalized();
    }

    /**
     * Index of the first piece whose interval ends at or after {@code time}.
     * Pieces of a profile are disjoint and sorted by start, so their ends are
     * nondecreasing.
     */
    public static int firstEndingAtOrAfter(List<ExactAffineSegment> sorted, ExactTime time) {
        int low = 0;
        int high = sorted.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted.get(middle).interval().end().compareTo(time) < 0) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("exact arrival-profile operation cancelled");
        }
    }

    public ExactArrivalProfile pointwiseMinimum(ExactArrivalProfile other) {
        return ExactProfileEnvelope.minimum(this, other);
    }

    public ExactArrivalProfile asTravelTime() {
        List<ExactAffineSegment> result = segments.stream()
                .map(segment -> new ExactAffineSegment(
                        segment.slope().subtract(ExactTime.ONE), segment.intercept(), segment.interval()))
                .toList();
        return ofSegments(result);
    }

    /** Converts a travel-time function {@code w(t)} to arrival {@code t+w(t)}. */
    public ExactArrivalProfile asArrivalProfile() {
        List<ExactAffineSegment> result = segments.stream()
                .map(segment -> new ExactAffineSegment(
                        segment.slope().add(ExactTime.ONE), segment.intercept(), segment.interval()))
                .toList();
        return ofSegments(result);
    }

    public ExactTemporalDomain imageDomain() {
        return imageDomain(null);
    }

    /** Exact entry-time image, including plateaus, singleton times and open endpoints. */
    public ExactTemporalDomain imageDomain(BooleanSupplier cancellationRequested) {
        List<ExactTemporalDomain.Interval> images = new ArrayList<>();
        for (ExactAffineSegment segment : segments) {
            checkCancelled(cancellationRequested);
            ExactTemporalDomain.Interval input = segment.interval();
            if (segment.slope().signum() == 0) {
                images.add(ExactTemporalDomain.Interval.singleton(segment.valueAtClosure(input.start())));
                continue;
            }
            ExactTime first = segment.valueAtClosure(input.start());
            ExactTime last = segment.valueAtClosure(input.end());
            if (segment.slope().signum() > 0) {
                images.add(new ExactTemporalDomain.Interval(
                        first, last, input.startInclusive(), input.endInclusive()));
            } else {
                images.add(new ExactTemporalDomain.Interval(
                        last, first, input.endInclusive(), input.startInclusive()));
            }
        }
        return ExactTemporalDomain.of(images, cancellationRequested);
    }

    public ExactTime minimumValueOnClosure() {
        if (isEmpty()) throw new IllegalStateException("empty profile has no finite minimum");
        ExactTime result = null;
        for (ExactAffineSegment segment : segments) {
            ExactTime start = segment.valueAtClosure(segment.interval().start());
            ExactTime end = segment.valueAtClosure(segment.interval().end());
            if (result == null || start.compareTo(result) < 0) result = start;
            if (end.compareTo(result) < 0) result = end;
        }
        return result;
    }

    /** Representation switch for A/B measurements only: {@code -Dthop.noCanonicalization=true} restores the unmerged v2.1 behaviour. */
    static final boolean CANONICALIZE = !Boolean.getBoolean("thop.noCanonicalization");

    /**
     * The same function in canonical form. Adjacent pieces that carry the identical affine function are merged, and a singleton piece whose value
     * equals the affine function of the piece it touches is absorbed into that piece (endpoint ownership is preserved exactly: the merged piece owns
     * the point the singleton owned). Genuine discontinuities are kept. Envelope and composition operations create a singleton at every cut and
     * keep every input endpoint as a cut, so without this the number of pieces only grows through label propagation although the function stays simple
     * (stored NY-CTRL fastest profiles of 1,763-2,367 pieces are 2-3 pieces canonically).
     */
    public ExactArrivalProfile canonicalized() {
        if (!CANONICALIZE || segments.size() < 2) return this;
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                return canonicalizedUninstrumented();
            } finally {
                ThopProfile.end(ThopProfile.P_CANONICALIZE, started);
            }
        }
        return canonicalizedUninstrumented();
    }

    private ExactArrivalProfile canonicalizedUninstrumented() {
        ArrayList<ExactAffineSegment> out = new ArrayList<>(segments.size());
        for (ExactAffineSegment next : segments) {
            out.add(next);
            while (out.size() >= 2) {
                ExactAffineSegment merged = tryMerge(out.get(out.size() - 2), out.get(out.size() - 1));
                if (merged == null) break;
                out.remove(out.size() - 1);
                out.set(out.size() - 1, merged);
            }
        }
        return out.size() == segments.size() ? this : ofSegments(out);
    }

    private static ExactAffineSegment tryMerge(ExactAffineSegment a, ExactAffineSegment b) {
        ExactTemporalDomain.Interval ai = a.interval();
        ExactTemporalDomain.Interval bi = b.interval();
        // the two pieces must meet at a point owned by exactly one of them; otherwise merging would add or remove a departure
        if (!ai.end().equals(bi.start()) || ai.endInclusive() == bi.startInclusive()) return null;
        boolean sameFunction = a.slope().equals(b.slope()) && a.intercept().equals(b.intercept());
        if (sameFunction) {
            return new ExactAffineSegment(a.slope(), a.intercept(), new ExactTemporalDomain.Interval(ai.start(), bi.end(), ai.startInclusive(), bi.endInclusive()));
        }
        if (bi.isSingleton() && !ai.isSingleton() && a.valueAtClosure(bi.start()).equals(b.valueAtClosure(bi.start()))) {
            return new ExactAffineSegment(a.slope(), a.intercept(), new ExactTemporalDomain.Interval(ai.start(), bi.end(), ai.startInclusive(), true));
        }
        if (ai.isSingleton() && !bi.isSingleton() && b.valueAtClosure(ai.start()).equals(a.valueAtClosure(ai.start()))) {
            return new ExactAffineSegment(b.slope(), b.intercept(), new ExactTemporalDomain.Interval(ai.start(), bi.end(), true, bi.endInclusive()));
        }
        return null;
    }

    public boolean sameFunction(ExactArrivalProfile other) {
        return ExactProfileEnvelope.sameFunction(this, other);
    }

    public boolean dominates(ExactArrivalProfile other) {
        return ExactProfileEnvelope.dominates(this, other);
    }

    /** Rounded compatibility projection. Exact callers should use this profile directly. */
    public ExactTemporalProfile roundedLegacyProjection() {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                return roundedLegacyProjectionUninstrumented();
            } finally {
                ThopProfile.end(ThopProfile.P_PROJECTION, started);
            }
        }
        return roundedLegacyProjectionUninstrumented();
    }

    private ExactTemporalProfile roundedLegacyProjectionUninstrumented() {
        ExactTemporalProfile result = ExactTemporalProfile.empty();
        for (ExactAffineSegment segment : segments) {
            ExactTemporalDomain.Interval interval = segment.interval();
            double start = interval.start().toDisplayDouble();
            double end = interval.end().toDisplayDouble();
            boolean startIncluded = interval.startInclusive();
            boolean endIncluded = interval.endInclusive();
            if (start > end) continue;
            if (start == end) {
                if (!(startIncluded && endIncluded)) continue;
                startIncluded = true;
                endIncluded = true;
            }
            Domain domain = Domain.of(new Domain.Interval(start, end, startIncluded, endIncluded));
            List<TimeProfile.Breakpoint> points = start == end
                    ? List.of(new TimeProfile.Breakpoint(start,
                            segment.valueAtClosure(interval.start()).toDisplayDouble()))
                    : List.of(
                            new TimeProfile.Breakpoint(start, segment.valueAtClosure(interval.start()).toDisplayDouble()),
                            new TimeProfile.Breakpoint(end, segment.valueAtClosure(interval.end()).toDisplayDouble()));
            TimeProfile projected = TimeProfile.piecewise(domain, points, "rounded-exact-thop-projection");
            ExactTemporalProfile cell = ExactTemporalProfile.from(projected);
            result = result.isEmpty() ? cell : result.pointwiseMinimum(cell);
        }
        return result;
    }

    /** Writes exact segment support, affine coefficients and ownership. */
    public void writeTo(DataOutput output) throws IOException {
        output.writeInt(segments.size());
        for (ExactAffineSegment segment : segments) {
            ExactTemporalDomain.Interval interval = segment.interval();
            writeTime(output, interval.start());
            writeTime(output, interval.end());
            output.writeBoolean(interval.startInclusive());
            output.writeBoolean(interval.endInclusive());
            writeTime(output, segment.slope());
            writeTime(output, segment.intercept());
        }
    }

    public static ExactArrivalProfile readFrom(DataInput input) throws IOException {
        int count = input.readInt();
        if (count < 0) throw new IOException("negative exact profile segment count");
        List<ExactAffineSegment> segments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ExactTime start = readTime(input);
            ExactTime end = readTime(input);
            boolean startInclusive = input.readBoolean();
            boolean endInclusive = input.readBoolean();
            ExactTime slope = readTime(input);
            ExactTime intercept = readTime(input);
            segments.add(new ExactAffineSegment(slope, intercept,
                    new ExactTemporalDomain.Interval(start, end, startInclusive, endInclusive)));
        }
        try {
            // a faithful read: the stored piece counts are integrity-checked against the construction counters; query indexes canonicalize afterwards (THopIndex.fromExistingLabels)
            return ofSegments(segments);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("invalid exact profile segments", malformed);
        }
    }

    static ExactTemporalDomain.Interval preimage(
            ExactAffineSegment inner, ExactTemporalDomain.Interval target) {
        if (ThopProfile.ENABLED) {
            long started = ThopProfile.begin();
            try {
                return preimageUninstrumented(inner, target);
            } finally {
                ThopProfile.end(ThopProfile.P_PREIMAGE, started);
            }
        }
        return preimageUninstrumented(inner, target);
    }

    private static ExactTemporalDomain.Interval preimageUninstrumented(
            ExactAffineSegment inner, ExactTemporalDomain.Interval target) {
        ExactTime slope = inner.slope();
        if (slope.signum() == 0) {
            return target.contains(inner.intercept()) ? inner.interval() : null;
        }
        ExactTime low = target.start().subtract(inner.intercept()).divide(slope);
        ExactTime high = target.end().subtract(inner.intercept()).divide(slope);
        boolean lowIncluded = target.startInclusive();
        boolean highIncluded = target.endInclusive();
        if (slope.signum() < 0) {
            ExactTime swap = low; low = high; high = swap;
            boolean included = lowIncluded; lowIncluded = highIncluded; highIncluded = included;
        }
        return new ExactTemporalDomain.Interval(low, high, lowIncluded, highIncluded);
    }

    private static ExactTime exactTravelAt(ExactTime time, List<ExactTime> knots, List<ExactTime> values) {
        if (knots.size() == 1) {
            if (!time.equals(knots.getFirst())) {
                throw new IllegalArgumentException("single-point edge queried outside its only knot");
            }
            return values.getFirst();
        }
        int segment = interpolationSegment(time, knots);
        ExactTime slope = values.get(segment + 1).subtract(values.get(segment))
                .divide(knots.get(segment + 1).subtract(knots.get(segment)));
        return values.get(segment).add(slope.multiply(time.subtract(knots.get(segment))));
    }

    private static int interpolationSegment(ExactTime time, List<ExactTime> knots) {
        for (int index = 0; index + 1 < knots.size(); index++) {
            if (time.compareTo(knots.get(index)) >= 0 && time.compareTo(knots.get(index + 1)) <= 0) return index;
        }
        if (time.compareTo(knots.getFirst()) < 0 || time.compareTo(knots.getLast()) > 0) {
            throw new IllegalArgumentException("edge temporal domain exceeds its canonical breakpoint range");
        }
        return Math.max(0, knots.size() - 2);
    }

    private static void writeTime(DataOutput output, ExactTime value) throws IOException {
        writeBigInteger(output, value.numerator());
        writeBigInteger(output, value.denominator());
    }

    private static ExactTime readTime(DataInput input) throws IOException {
        BigInteger numerator = readBigInteger(input);
        BigInteger denominator = readBigInteger(input);
        try {
            return ExactTime.of(numerator, denominator);
        } catch (ArithmeticException invalid) {
            throw new IOException("invalid exact rational", invalid);
        }
    }

    private static void writeBigInteger(DataOutput output, BigInteger value) throws IOException {
        byte[] bytes = value.toByteArray();
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static BigInteger readBigInteger(DataInput input) throws IOException {
        int length = input.readInt();
        if (length <= 0) throw new IOException("invalid exact integer byte count");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new BigInteger(bytes);
    }

    @Override
    public String toString() {
        return segments.toString();
    }
}
