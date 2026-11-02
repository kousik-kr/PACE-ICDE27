package edu.ipcmax.baselines;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tiny-graph reference built from exact decimal rationals, independent of
 * TimeProfile and all THop composition code.
 */
final class RationalTemporalReference {
    private RationalTemporalReference() {
    }

    record Q(BigInteger numerator, BigInteger denominator) implements Comparable<Q> {
        static final Q ZERO = of(0);
        static final Q ONE = of(1);

        Q {
            if (denominator.signum() == 0) {
                throw new ArithmeticException("zero denominator");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }

        static Q of(long value) {
            return new Q(BigInteger.valueOf(value), BigInteger.ONE);
        }

        static Q decimal(String value) {
            BigDecimal decimal = new BigDecimal(value);
            BigInteger numerator = decimal.unscaledValue();
            int scale = decimal.scale();
            return scale >= 0
                    ? new Q(numerator, BigInteger.TEN.pow(scale))
                    : new Q(numerator.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
        }

        static Q decimal(double value) {
            return decimal(BigDecimal.valueOf(value).toPlainString());
        }

        static Q exact(ExactTime value) {
            return new Q(value.numerator(), value.denominator());
        }

        Q add(Q other) {
            return new Q(numerator.multiply(other.denominator)
                    .add(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
        }

        Q subtract(Q other) {
            return new Q(numerator.multiply(other.denominator)
                    .subtract(other.numerator.multiply(denominator)), denominator.multiply(other.denominator));
        }

        Q multiply(Q other) {
            return new Q(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
        }

        Q divide(Q other) {
            return new Q(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
        }

        String rounded(int scale) {
            return new BigDecimal(numerator).divide(
                    new BigDecimal(denominator), scale, RoundingMode.HALF_EVEN).toPlainString();
        }

        @Override
        public int compareTo(Q other) {
            return numerator.multiply(other.denominator)
                    .compareTo(other.numerator.multiply(denominator));
        }

        @Override
        public String toString() {
            return denominator.equals(BigInteger.ONE)
                    ? numerator.toString()
                    : numerator + "/" + denominator;
        }
    }

    record Affine(Q slope, Q intercept) {
        Q at(Q x) {
            return slope.multiply(x).add(intercept);
        }

        /** Returns {@code outer(this(x))}. */
        Affine then(Affine outer) {
            return new Affine(
                    outer.slope.multiply(slope),
                    outer.slope.multiply(intercept).add(outer.intercept));
        }

        Q rootFor(Q value) {
            if (slope.compareTo(Q.ZERO) == 0) {
                throw new ArithmeticException("constant affine map has no unique root");
            }
            return value.subtract(intercept).divide(slope);
        }

        Q crossing(Affine other) {
            Q slopeDifference = slope.subtract(other.slope);
            if (slopeDifference.compareTo(Q.ZERO) == 0) {
                return null;
            }
            return other.intercept.subtract(intercept).divide(slopeDifference);
        }
    }

    record Step(
            int arcId,
            Q entryTime,
            Q travelTime,
            Q arrivalTime,
            Q segmentStart,
            Q segmentEnd,
            Affine travelFunction,
            Affine arrivalFunction) {
    }

    record Replay(int source, int destination, Q departure, Q arrival, List<Integer> arcIds, List<Step> steps) {
        Q travelTime() {
            return arrival.subtract(departure);
        }
    }

    static Replay replay(TDGraph graph, int source, int destination, Q departure, List<Integer> arcIds) {
        graph.node(source);
        graph.node(destination);
        int currentNode = source;
        Q currentTime = departure;
        List<Step> steps = new ArrayList<>();
        for (int arcId : arcIds) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("unknown arc ID " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("disconnected exact route at arc " + arcId);
            }
            requireSupported(edge.travelTimeFunction().domain(), currentTime, arcId);
            Segment segment = segmentAt(edge.travelTimeFunction(), currentTime);
            Q travel = segment.travel().at(currentTime);
            Q arrival = segment.arrival().at(currentTime);
            steps.add(new Step(arcId, currentTime, travel, arrival,
                    segment.start(), segment.end(), segment.travel(), segment.arrival()));
            currentTime = arrival;
            currentNode = edge.target();
        }
        if (currentNode != destination) {
            throw new IllegalArgumentException("exact route does not reach destination");
        }
        return new Replay(source, destination, departure, currentTime, List.copyOf(arcIds), List.copyOf(steps));
    }

    static Replay bestSimpleRoute(TDGraph graph, int source, int destination, Q departure) {
        graph.node(source);
        graph.node(destination);
        if (source == destination) {
            return replay(graph, source, destination, departure, List.of());
        }
        Best best = new Best();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerate(graph, source, destination, departure, visited, new ArrayList<>(), best);
        if (best.replay == null) {
            throw new IllegalArgumentException("no supported simple route");
        }
        return best.replay;
    }

    /** Complete piecewise-affine route oracle, independent of production profile operations. */
    static Map<Integer, Profile> allDestinationProfiles(TDGraph graph, int source, Domain departureDomain) {
        graph.node(source);
        Map<Integer, Profile> result = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerateProfiles(graph, source, identity(departureDomain), visited, result);
        return Map.copyOf(result);
    }

    private static void enumerateProfiles(
            TDGraph graph, int current, Profile route, Set<Integer> visited, Map<Integer, Profile> result) {
        Profile previous = result.get(current);
        result.put(current, previous == null ? route : previous.minimum(route));
        for (Edge edge : graph.outgoingEdges(current)) {
            if (visited.contains(edge.target())) continue;
            Profile extended = route.compose(edgeProfile(edge));
            if (extended.isEmpty()) continue;
            visited.add(edge.target());
            enumerateProfiles(graph, edge.target(), extended, visited, result);
            visited.remove(edge.target());
        }
    }

    private static Profile identity(Domain domain) {
        List<ProfileSegment> segments = new ArrayList<>();
        for (Domain.Interval interval : domain.intervals()) {
            segments.add(new ProfileSegment(new Affine(Q.ONE, Q.ZERO), new Span(
                    Q.decimal(interval.start()), Q.decimal(interval.end()),
                    interval.startInclusive(), interval.endInclusive())));
        }
        return new Profile(segments);
    }

    private static Profile edgeProfile(Edge edge) {
        PiecewiseLinearFn fn = edge.travelTimeFunction();
        List<Q> knots = fn.breakpoints().stream().map(point -> Q.decimal(point.minute())).toList();
        List<Q> weights = fn.breakpoints().stream().map(point -> Q.decimal(point.value())).toList();
        List<ProfileSegment> result = new ArrayList<>();
        for (Domain.Interval legacy : fn.domain().intervals()) {
            Q start = Q.decimal(legacy.start());
            Q end = Q.decimal(legacy.end());
            TreeSet<Q> cuts = new TreeSet<>();
            cuts.add(start);
            cuts.add(end);
            for (Q knot : knots) if (knot.compareTo(start) > 0 && knot.compareTo(end) < 0) cuts.add(knot);
            List<Q> ordered = List.copyOf(cuts);
            if (ordered.size() == 1) {
                Q at = ordered.getFirst();
                Affine travel = travelAffine(at, knots, weights);
                result.add(new ProfileSegment(new Affine(Q.ONE.add(travel.slope()), travel.intercept()),
                        new Span(start, end, legacy.startInclusive(), legacy.endInclusive())));
                continue;
            }
            for (int i = 0; i + 1 < ordered.size(); i++) {
                Q low = ordered.get(i), high = ordered.get(i + 1);
                Q midpoint = low.add(high).divide(Q.of(2));
                Affine travel = travelAffine(midpoint, knots, weights);
                boolean lowIncluded = i == 0 ? legacy.startInclusive() : true;
                boolean highIncluded = i == ordered.size() - 2 ? legacy.endInclusive() : false;
                result.add(new ProfileSegment(new Affine(Q.ONE.add(travel.slope()), travel.intercept()),
                        new Span(low, high, lowIncluded, highIncluded)));
            }
        }
        return new Profile(result);
    }

    private static Affine travelAffine(Q time, List<Q> knots, List<Q> weights) {
        for (int i = 0; i + 1 < knots.size(); i++) {
            if (time.compareTo(knots.get(i)) >= 0 && time.compareTo(knots.get(i + 1)) <= 0) {
                Q slope = weights.get(i + 1).subtract(weights.get(i))
                        .divide(knots.get(i + 1).subtract(knots.get(i)));
                return new Affine(slope, weights.get(i).subtract(slope.multiply(knots.get(i))));
            }
        }
        throw new IllegalArgumentException("edge profile query outside canonical knots");
    }

    record Span(Q start, Q end, boolean startInclusive, boolean endInclusive) {
        Span {
            int order = start.compareTo(end);
            if (order > 0 || (order == 0 && !(startInclusive && endInclusive))) {
                throw new IllegalArgumentException("invalid rational support interval");
            }
        }

        boolean contains(Q value) {
            int low = value.compareTo(start), high = value.compareTo(end);
            return (low > 0 || (low == 0 && startInclusive))
                    && (high < 0 || (high == 0 && endInclusive));
        }

        boolean containsClosure(Q value) {
            return value.compareTo(start) >= 0 && value.compareTo(end) <= 0;
        }

        Span intersect(Span other) {
            Q low;
            boolean lowOwned;
            int lowOrder = start.compareTo(other.start);
            if (lowOrder > 0) { low = start; lowOwned = startInclusive; }
            else if (lowOrder < 0) { low = other.start; lowOwned = other.startInclusive; }
            else { low = start; lowOwned = startInclusive && other.startInclusive; }
            Q high;
            boolean highOwned;
            int highOrder = end.compareTo(other.end);
            if (highOrder < 0) { high = end; highOwned = endInclusive; }
            else if (highOrder > 0) { high = other.end; highOwned = other.endInclusive; }
            else { high = end; highOwned = endInclusive && other.endInclusive; }
            int order = low.compareTo(high);
            if (order < 0) return new Span(low, high, lowOwned, highOwned);
            return order == 0 && lowOwned && highOwned ? new Span(low, high, true, true) : null;
        }
    }

    record ProfileSegment(Affine affine, Span support) {
        Q value(Q at) {
            return affine.at(at);
        }
    }

    static final class Profile {
        private static final Comparator<ProfileSegment> ORDER = Comparator.comparing((ProfileSegment segment) -> segment.support().start())
                .thenComparing(segment -> !segment.support().startInclusive())
                .thenComparing(segment -> segment.support().end());
        private final List<ProfileSegment> segments;

        Profile(List<ProfileSegment> segments) {
            List<ProfileSegment> ordered = new ArrayList<>(segments);
            ordered.sort(ORDER);
            this.segments = List.copyOf(ordered);
        }

        List<ProfileSegment> segments() { return segments; }
        boolean isEmpty() { return segments.isEmpty(); }

        static Profile fromProductionForComparison(ExactArrivalProfile profile) {
            return new Profile(profile.segments().stream().map(segment -> new ProfileSegment(
                    new Affine(Q.exact(segment.slope()), Q.exact(segment.intercept())),
                    new Span(Q.exact(segment.interval().start()), Q.exact(segment.interval().end()),
                            segment.interval().startInclusive(), segment.interval().endInclusive())))
                    .toList());
        }

        Q valueAt(Q time) {
            ProfileSegment segment = segmentAt(time);
            if (segment == null) throw new IllegalArgumentException("rational profile is unsupported at " + time);
            return segment.value(time);
        }

        Profile restrict(Domain domain) {
            List<ProfileSegment> result = new ArrayList<>();
            for (ProfileSegment segment : segments) for (Domain.Interval interval : domain.intervals()) {
                Span requested = new Span(Q.decimal(interval.start()), Q.decimal(interval.end()),
                        interval.startInclusive(), interval.endInclusive());
                Span overlap = segment.support().intersect(requested);
                if (overlap != null) result.add(new ProfileSegment(segment.affine(), overlap));
            }
            return new Profile(result);
        }

        Profile compose(Profile outer) {
            List<ProfileSegment> result = new ArrayList<>();
            for (ProfileSegment inner : segments) for (ProfileSegment after : outer.segments) {
                Span overlap;
                if (inner.affine().slope().compareTo(Q.ZERO) == 0) {
                    overlap = after.support().contains(inner.affine().intercept()) ? inner.support() : null;
                } else {
                    Span preimage = preimage(inner.affine(), after.support());
                    overlap = preimage == null ? null : inner.support().intersect(preimage);
                }
                if (overlap != null) result.add(new ProfileSegment(inner.affine().then(after.affine()), overlap));
            }
            return new Profile(result);
        }

        Profile minimum(Profile other) {
            if (isEmpty()) return other;
            if (other.isEmpty()) return this;
            TreeSet<Q> cuts = new TreeSet<>();
            for (ProfileSegment segment : segments) { cuts.add(segment.support().start()); cuts.add(segment.support().end()); }
            for (ProfileSegment segment : other.segments) { cuts.add(segment.support().start()); cuts.add(segment.support().end()); }
            for (ProfileSegment a : segments) for (ProfileSegment b : other.segments) {
                Span overlap = a.support().intersect(b.support());
                if (overlap == null) continue;
                Q root = a.affine().crossing(b.affine());
                if (root != null && overlap.containsClosure(root)) cuts.add(root);
            }
            List<Q> points = List.copyOf(cuts);
            List<ProfileSegment> result = new ArrayList<>();
            for (int i = 0; i + 1 < points.size(); i++) {
                Q low = points.get(i), high = points.get(i + 1);
                if (low.equals(high)) continue;
                Q probe = low.add(high).divide(Q.of(2));
                ProfileSegment a = segmentAt(probe), b = other.segmentAt(probe);
                ProfileSegment winner = a == null ? b : b == null || a.value(probe).compareTo(b.value(probe)) <= 0 ? a : b;
                if (winner != null) result.add(new ProfileSegment(winner.affine(), new Span(low, high, false, false)));
            }
            for (Q point : points) {
                ProfileSegment a = segmentAt(point), b = other.segmentAt(point);
                if (a == null && b == null) continue;
                ProfileSegment winner = a == null ? b : b == null || a.value(point).compareTo(b.value(point)) <= 0 ? a : b;
                result.add(new ProfileSegment(new Affine(Q.ZERO, winner.value(point)), new Span(point, point, true, true)));
            }
            return new Profile(result);
        }

        Profile asTravelTime() {
            return new Profile(segments.stream().map(segment -> new ProfileSegment(
                    new Affine(segment.affine().slope().subtract(Q.ONE), segment.affine().intercept()),
                    segment.support())).toList());
        }

        boolean sameFunction(Profile other) {
            TreeSet<Q> cuts = new TreeSet<>();
            for (ProfileSegment segment : segments) { cuts.add(segment.support().start()); cuts.add(segment.support().end()); }
            for (ProfileSegment segment : other.segments) { cuts.add(segment.support().start()); cuts.add(segment.support().end()); }
            List<Q> points = List.copyOf(cuts);
            for (Q point : points) {
                ProfileSegment a = segmentAt(point), b = other.segmentAt(point);
                if ((a == null) != (b == null) || (a != null && !a.value(point).equals(b.value(point)))) return false;
            }
            for (int i = 0; i + 1 < points.size(); i++) {
                Q low = points.get(i), high = points.get(i + 1);
                if (low.equals(high)) continue;
                Q probe = low.add(high).divide(Q.of(2));
                ProfileSegment a = segmentAt(probe), b = other.segmentAt(probe);
                if ((a == null) != (b == null)) return false;
                if (a != null && (!a.affine().equals(b.affine()))) return false;
            }
            return true;
        }

        private ProfileSegment segmentAt(Q time) {
            for (ProfileSegment segment : segments) if (segment.support().contains(time)) return segment;
            return null;
        }

        private static Span preimage(Affine affine, Span target) {
            if (affine.slope().compareTo(Q.ZERO) == 0) {
                return null; // A constant preimage is handled against the inner support in compose().
            }
            Q low = target.start().subtract(affine.intercept()).divide(affine.slope());
            Q high = target.end().subtract(affine.intercept()).divide(affine.slope());
            boolean lowOwned = target.startInclusive(), highOwned = target.endInclusive();
            if (affine.slope().compareTo(Q.ZERO) < 0) {
                Q swap = low; low = high; high = swap;
                boolean owned = lowOwned; lowOwned = highOwned; highOwned = owned;
            }
            return new Span(low, high, lowOwned, highOwned);
        }
    }

    static Affine arrivalAffine(TDGraph graph, int source, List<Integer> arcIds, Q probeDeparture) {
        int currentNode = source;
        Affine route = new Affine(Q.ONE, Q.ZERO);
        for (int arcId : arcIds) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("disconnected exact route at arc " + arcId);
            }
            Q entryTime = route.at(probeDeparture);
            requireSupported(edge.travelTimeFunction().domain(), entryTime, arcId);
            Segment segment = segmentAt(edge.travelTimeFunction(), entryTime);
            route = route.then(segment.arrival());
            currentNode = edge.target();
        }
        return route;
    }

    static boolean supports(Domain domain, Q time) {
        for (Domain.Interval interval : domain.intervals()) {
            Q start = Q.decimal(interval.start());
            Q end = Q.decimal(interval.end());
            boolean afterStart = time.compareTo(start) > 0
                    || (time.compareTo(start) == 0 && interval.startInclusive());
            boolean beforeEnd = time.compareTo(end) < 0
                    || (time.compareTo(end) == 0 && interval.endInclusive());
            if (afterStart && beforeEnd) {
                return true;
            }
        }
        return false;
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            int destination,
            Q departure,
            Set<Integer> visited,
            List<Integer> path,
            Best best) {
        for (Edge edge : graph.outgoingEdges(current)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            path.add(edge.arcId());
            if (edge.target() == destination) {
                try {
                    Replay candidate = replay(graph, pathSource(graph, path), destination, departure, path);
                    if (best.replay == null || candidate.arrival().compareTo(best.replay.arrival()) < 0) {
                        best.replay = candidate;
                    }
                } catch (UnsupportedExactTime unsupported) {
                    // This route is outside at least one edge's exact support.
                }
            } else {
                visited.add(edge.target());
                enumerate(graph, edge.target(), destination, departure, visited, path, best);
                visited.remove(edge.target());
            }
            path.remove(path.size() - 1);
        }
    }

    private static int pathSource(TDGraph graph, List<Integer> path) {
        return graph.edges().get(path.get(0)).source();
    }

    private static void requireSupported(Domain domain, Q time, int arcId) {
        if (supports(domain, time)) {
            return;
        }
        throw new UnsupportedExactTime("arc " + arcId + " excludes exact entry time " + time);
    }

    private static Segment segmentAt(PiecewiseLinearFn function, Q time) {
        List<PiecewiseLinearFn.Breakpoint> points = function.breakpoints();
        for (int index = 0; index + 1 < points.size(); index++) {
            PiecewiseLinearFn.Breakpoint left = points.get(index);
            PiecewiseLinearFn.Breakpoint right = points.get(index + 1);
            Q start = Q.decimal(left.minute());
            Q end = Q.decimal(right.minute());
            if (time.compareTo(start) >= 0 && time.compareTo(end) <= 0) {
                Q leftValue = Q.decimal(left.value());
                Q rightValue = Q.decimal(right.value());
                Q slope = rightValue.subtract(leftValue).divide(end.subtract(start));
                Q intercept = leftValue.subtract(slope.multiply(start));
                Affine travel = new Affine(slope, intercept);
                return new Segment(start, end, travel,
                        new Affine(Q.ONE.add(slope), intercept));
            }
        }
        throw new UnsupportedExactTime("no interpolation segment contains exact time " + time);
    }

    private record Segment(Q start, Q end, Affine travel, Affine arrival) {
    }

    private static final class Best {
        private Replay replay;
    }

    private static final class UnsupportedExactTime extends IllegalArgumentException {
        private UnsupportedExactTime(String message) {
            super(message);
        }
    }
}
