package edu.ipcmax.baselines;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact-rational implementation of the turning-point deletion loop in
 * Li, Wang and Zhou, ICDE 2019, Algorithm 1 (Bottom-Up Approximation).
 *
 * <p>THop stores arrival mappings, while Algorithm 1 compresses the travel-time
 * cost function. This class therefore simplifies {@code arrival(t) - t} and
 * converts the retained cost segments back to arrival mappings. The paper's
 * printed Error function sums {@code interpolated - original} at the original
 * turning points between two retained endpoints. This class implements that
 * signed sum literally. It does not reinterpret it as an absolute or maximum
 * error, and callers must not treat it as a pointwise error guarantee.
 * Disconnected support, singleton cells, and discontinuous joins are
 * structural boundaries and are never compressed across.</p>
 */
public final class BottomUpProfileCompression {
    private BottomUpProfileCompression() { }

    public static Result compress(ExactArrivalProfile input, ExactTime epsilon) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(epsilon, "epsilon");
        if (epsilon.signum() < 0) throw new IllegalArgumentException("epsilon must be nonnegative");
        long started = System.nanoTime();
        List<ExactAffineSegment> output = new ArrayList<>();
        long removed = 0;
        long iterations = 0;
        ExactTime maximumAbsoluteBreakpointDeviation = ExactTime.ZERO;

        List<ExactAffineSegment> current = new ArrayList<>();
        for (ExactAffineSegment segment : input.segments()) {
            if (!current.isEmpty() && !joinsContinuously(current.getLast(), segment)) {
                ComponentResult result = compressComponent(current, epsilon);
                output.addAll(result.segments());
                removed += result.removed();
                iterations += result.iterations();
                maximumAbsoluteBreakpointDeviation = max(
                        maximumAbsoluteBreakpointDeviation,
                        result.maximumAbsoluteBreakpointDeviation());
                current.clear();
            }
            current.add(segment);
        }
        if (!current.isEmpty()) {
            ComponentResult result = compressComponent(current, epsilon);
            output.addAll(result.segments());
            removed += result.removed();
            iterations += result.iterations();
            maximumAbsoluteBreakpointDeviation = max(
                    maximumAbsoluteBreakpointDeviation,
                    result.maximumAbsoluteBreakpointDeviation());
        }
        ExactArrivalProfile compressed = ExactArrivalProfile.ofSegments(output);
        if (!input.domain().equals(compressed.domain())) {
            throw new IllegalStateException("bottom-up compression changed temporal support");
        }
        return new Result(input, compressed, removed, iterations,
                System.nanoTime() - started, maximumAbsoluteBreakpointDeviation);
    }

    private static ComponentResult compressComponent(
            List<ExactAffineSegment> source, ExactTime epsilon) {
        if (source.size() == 1 && source.getFirst().interval().isSingleton()) {
            return new ComponentResult(List.copyOf(source), 0, 0, ExactTime.ZERO);
        }
        List<Knot> original = new ArrayList<>(source.size() + 1);
        ExactAffineSegment first = source.getFirst();
        original.add(knot(first, first.interval().start()));
        for (ExactAffineSegment segment : source) {
            original.add(knot(segment, segment.interval().end()));
        }
        // Adjacent zero-width segments or duplicate coordinates are structural.
        for (int i = 1; i < original.size(); i++) {
            if (original.get(i - 1).time().compareTo(original.get(i).time()) >= 0) {
                return new ComponentResult(List.copyOf(source), 0, 0, ExactTime.ZERO);
            }
        }

        List<Integer> retained = new ArrayList<>(original.size());
        for (int i = 0; i < original.size(); i++) retained.add(i);
        long removed = 0;
        long iterations = 0;
        while (retained.size() > 2) {
            ExactTime minimum = null;
            int removeAt = -1;
            for (int position = 1; position + 1 < retained.size(); position++) {
                int left = retained.get(position - 1);
                int right = retained.get(position + 1);
                ExactTime error = error(original, left, right);
                if (minimum == null || error.compareTo(minimum) < 0) {
                    minimum = error;
                    removeAt = position;
                }
            }
            iterations++;
            if (minimum == null || minimum.compareTo(epsilon) >= 0) break;
            retained.remove(removeAt);
            removed++;
        }

        List<ExactAffineSegment> result = new ArrayList<>(retained.size() - 1);
        for (int position = 0; position + 1 < retained.size(); position++) {
            int leftIndex = retained.get(position);
            int rightIndex = retained.get(position + 1);
            Knot left = original.get(leftIndex);
            Knot right = original.get(rightIndex);
            ExactTime costSlope = right.value().subtract(left.value())
                    .divide(right.time().subtract(left.time()));
            ExactTime costIntercept = left.value().subtract(costSlope.multiply(left.time()));
            ExactTime arrivalSlope = ExactTime.ONE.add(costSlope);
            boolean startInclusive = position == 0
                    ? source.getFirst().interval().startInclusive()
                    : rightOwnership(source, left.time());
            boolean endInclusive = position + 2 == retained.size()
                    ? source.getLast().interval().endInclusive()
                    : !rightOwnership(source, right.time());
            result.add(new ExactAffineSegment(arrivalSlope, costIntercept,
                    new ExactTemporalDomain.Interval(left.time(), right.time(),
                            startInclusive, endInclusive)));
        }
        ExactTime maximumDeviation = ExactTime.ZERO;
        for (int index = 0; index < original.size(); index++) {
            Knot knot = original.get(index);
            ExactTime approximated = valueAtOutputClosure(result, knot.time());
            ExactTime exactArrival = knot.time().add(knot.value());
            ExactTime deviation = approximated.subtract(exactArrival);
            maximumDeviation = max(maximumDeviation,
                    deviation.signum() < 0 ? deviation.negate() : deviation);
        }
        return new ComponentResult(List.copyOf(result), removed, iterations, maximumDeviation);
    }

    private static ExactTime error(List<Knot> original, int leftIndex, int rightIndex) {
        Knot left = original.get(leftIndex);
        Knot right = original.get(rightIndex);
        ExactTime duration = right.time().subtract(left.time());
        ExactTime sum = ExactTime.ZERO;
        for (int index = leftIndex + 1; index <= rightIndex; index++) {
            Knot point = original.get(index);
            ExactTime interpolated = left.value().add(
                    right.value().subtract(left.value())
                            .multiply(point.time().subtract(left.time()))
                            .divide(duration));
            sum = sum.add(interpolated.subtract(point.value()));
        }
        return sum;
    }

    private static Knot knot(ExactAffineSegment arrival, ExactTime time) {
        return new Knot(time, arrival.valueAtClosure(time).subtract(time));
    }

    private static boolean joinsContinuously(ExactAffineSegment left, ExactAffineSegment right) {
        ExactTemporalDomain.Interval a = left.interval();
        ExactTemporalDomain.Interval b = right.interval();
        if (!a.end().equals(b.start())) return false;
        if (!a.endInclusive() && !b.startInclusive()) return false;
        return left.valueAtClosure(a.end()).equals(right.valueAtClosure(b.start()));
    }

    private static boolean rightOwnership(List<ExactAffineSegment> source, ExactTime at) {
        for (ExactAffineSegment segment : source) {
            if (segment.interval().start().equals(at)) return segment.interval().startInclusive();
            if (segment.interval().start().compareTo(at) > 0) break;
        }
        for (ExactAffineSegment segment : source) {
            if (segment.interval().end().equals(at)) return false;
        }
        throw new IllegalStateException("retained breakpoint has no support owner: " + at);
    }

    private static ExactTime valueAtOutputClosure(List<ExactAffineSegment> output, ExactTime at) {
        for (ExactAffineSegment segment : output) {
            if (segment.interval().containsClosure(at)) return segment.valueAtClosure(at);
        }
        throw new IllegalStateException("output profile has no closure at input breakpoint " + at);
    }

    private static ExactTime max(ExactTime left, ExactTime right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    private record Knot(ExactTime time, ExactTime value) { }

    private record ComponentResult(
            List<ExactAffineSegment> segments,
            long removed,
            long iterations,
            ExactTime maximumAbsoluteBreakpointDeviation) { }

    public record Result(
            ExactArrivalProfile input,
            ExactArrivalProfile profile,
            long removedBreakpoints,
            long iterations,
            long elapsedNanos,
            ExactTime maximumAbsoluteBreakpointDeviation) {
        public Result {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(maximumAbsoluteBreakpointDeviation,
                    "maximumAbsoluteBreakpointDeviation");
        }

        public long inputPieces() { return input.pieceCount(); }
        public long outputPieces() { return profile.pieceCount(); }
    }
}
