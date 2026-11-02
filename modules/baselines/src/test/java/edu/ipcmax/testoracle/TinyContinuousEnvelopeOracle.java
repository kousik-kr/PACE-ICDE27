package edu.ipcmax.testoracle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;

/**
 * Independent exhaustive continuous oracle for tiny graphs.
 *
 * <p>This implementation reads graph function breakpoints but does not call the
 * production PACE stitching, compression, profile, validation, or envelope code.</p>
 */
public final class TinyContinuousEnvelopeOracle {
    private static final int MAX_PATHS = 10_000;

    private TinyContinuousEnvelopeOracle() {
    }

    /** A maximal oracle envelope segment. An empty path denotes NO_PATH. */
    public record Segment(
            ExactFraction start,
            ExactFraction end,
            boolean startInclusive,
            boolean endInclusive,
            Optional<List<Integer>> path) {
        public Segment {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(path, "path");
            path = path.map(List::copyOf);
        }
    }

    /** Exact value on one cell; arrival(t) = arrivalSlope*t + arrivalIntercept. */
    public record CellValue(
            int score, ExactFraction arrivalSlope, ExactFraction arrivalIntercept) {
    }

    /**
     * An unmerged temporal cell, including every event, budget root, and tie root.
     * Point cells own event endpoints; nonpoint cells are open at both ends.
     * Empty path/value denotes NO_PATH. This exposes the complete function, not
     * only the path identity retained by {@link Segment}.
     */
    public record TemporalCell(
            ExactFraction start,
            ExactFraction end,
            boolean startInclusive,
            boolean endInclusive,
            Optional<List<Integer>> path,
            Optional<CellValue> value) {
        public TemporalCell {
            path = path.map(List::copyOf);
            if (path.isPresent() != value.isPresent()) {
                throw new IllegalArgumentException("path and value must agree");
            }
        }
    }

    /** Complete rational cells over every independently enumerated simple path. */
    public static List<TemporalCell> solveCells(TDGraph graph, QuerySpec query) {
        return solveCellsForPaths(graph, query,
                enumerateSimplePaths(graph, query.source(), query.destination()));
    }

    /**
     * Independent envelope for an explicitly supplied candidate registry. Used
     * to validate capped output without asserting global path completeness.
     */
    public static List<TemporalCell> solveCellsForPaths(
            TDGraph graph, QuerySpec query, List<List<Integer>> paths) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(query, "query");
        graph.node(query.source());
        graph.node(query.destination());
        for (List<Integer> path : paths) {
            int node = query.source();
            Set<Integer> visited = new HashSet<>();
            visited.add(node);
            for (int arcId : path) {
                Edge edge = graph.edges().get(arcId);
                if (edge.source() != node || !visited.add(edge.target())) {
                    throw new IllegalArgumentException("not a vertex-simple query path");
                }
                node = edge.target();
            }
            if (node != query.destination()) {
                throw new IllegalArgumentException("path does not reach query destination");
            }
        }
        ExactFraction start = ExactFraction.of(query.departureStart());
        ExactFraction end = ExactFraction.of(query.departureEnd());
        ExactFraction budget = ExactFraction.fromDouble(query.maxTravelTime());
        requireGraphHorizon(graph, start, end.add(budget));
        TreeSet<ExactFraction> cuts = new TreeSet<>();
        cuts.add(start);
        cuts.add(end);
        for (List<Integer> path : paths) {
            cuts.addAll(buildPathCuts(graph, path, start, end, budget));
        }
        addTravelTieCuts(graph, paths, budget, cuts);
        List<ExactFraction> ordered = List.copyOf(cuts);
        List<TemporalCell> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            ExactFraction point = ordered.get(i);
            result.add(temporalCell(graph, paths, budget, point, point));
            if (i + 1 < ordered.size()) {
                result.add(temporalCell(graph, paths, budget, point, ordered.get(i + 1)));
            }
        }
        return List.copyOf(result);
    }

    private static TemporalCell temporalCell(
            TDGraph graph, List<List<Integer>> paths, ExactFraction budget,
            ExactFraction start, ExactFraction end) {
        boolean point = start.equals(end);
        ExactFraction sample = point ? start : midpoint(start, end);
        Optional<List<Integer>> selected = bestPathAt(graph, paths, sample, budget);
        Optional<CellValue> value = selected.map(path -> {
            Replay replay = point ? replayPoint(graph, path, sample)
                    : replayAffine(graph, path, sample);
            return new CellValue(replay.score(), replay.arrival().slope(),
                    replay.arrival().intercept());
        });
        return new TemporalCell(start, end, point, point, selected, value);
    }

    /** Solves a query over all vertex-simple paths and the complete real departure interval. */
    public static List<Segment> solve(TDGraph graph, QuerySpec query) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(query, "query");
        graph.node(query.source());
        graph.node(query.destination());

        ExactFraction start = ExactFraction.of(query.departureStart());
        ExactFraction end = ExactFraction.of(query.departureEnd());
        ExactFraction budget = ExactFraction.fromDouble(query.maxTravelTime());
        requireGraphHorizon(graph, start, end.add(budget));

        List<List<Integer>> paths = enumerateSimplePaths(graph, query.source(), query.destination());
        TreeSet<ExactFraction> globalCuts = new TreeSet<>();
        globalCuts.add(start);
        globalCuts.add(end);

        for (List<Integer> path : paths) {
            TreeSet<ExactFraction> pathCuts = buildPathCuts(graph, path, start, end, budget);
            globalCuts.addAll(pathCuts);
        }
        addTravelTieCuts(graph, paths, budget, globalCuts);
        return buildMaximalSegments(graph, paths, budget, globalCuts);
    }

    private static TreeSet<ExactFraction> buildPathCuts(
            TDGraph graph,
            List<Integer> path,
            ExactFraction start,
            ExactFraction end,
            ExactFraction budget) {
        TreeSet<ExactFraction> cuts = new TreeSet<>();
        cuts.add(start);
        cuts.add(end);

        for (int edgeIndex = 0; edgeIndex < path.size(); edgeIndex++) {
            List<ExactFraction> snapshot = List.copyOf(cuts);
            TreeSet<ExactFraction> additions = new TreeSet<>();
            Edge next = graph.edges().get(path.get(edgeIndex));
            List<ExactFraction> events = edgeEvents(next);
            for (int i = 1; i < snapshot.size(); i++) {
                ExactFraction left = snapshot.get(i - 1);
                ExactFraction right = snapshot.get(i);
                if (left.equals(right)) {
                    continue;
                }
                ExactFraction midpoint = midpoint(left, right);
                Replay prefix = replayAffine(graph, path.subList(0, edgeIndex), midpoint);
                if (prefix == null) {
                    continue;
                }
                for (ExactFraction event : events) {
                    addStrictInteriorPreimage(additions, prefix.arrival(), event, left, right);
                }
            }
            cuts.addAll(additions);
        }

        List<ExactFraction> snapshot = List.copyOf(cuts);
        for (int i = 1; i < snapshot.size(); i++) {
            ExactFraction left = snapshot.get(i - 1);
            ExactFraction right = snapshot.get(i);
            if (left.equals(right)) {
                continue;
            }
            Replay replay = replayAffine(graph, path, midpoint(left, right));
            if (replay == null) {
                continue;
            }
            Affine travel = replay.arrival().subtractIdentity();
            if (travel.slope().signum() != 0) {
                ExactFraction root = budget.subtract(travel.intercept()).divide(travel.slope());
                if (strictlyInside(root, left, right)) {
                    cuts.add(root);
                }
            }
        }
        return cuts;
    }

    private static void addTravelTieCuts(
            TDGraph graph,
            List<List<Integer>> paths,
            ExactFraction budget,
            TreeSet<ExactFraction> cuts) {
        List<ExactFraction> snapshot = List.copyOf(cuts);
        TreeSet<ExactFraction> additions = new TreeSet<>();
        for (int cell = 1; cell < snapshot.size(); cell++) {
            ExactFraction left = snapshot.get(cell - 1);
            ExactFraction right = snapshot.get(cell);
            if (left.equals(right)) {
                continue;
            }
            ExactFraction sample = midpoint(left, right);
            List<Replay> feasible = new ArrayList<>();
            for (List<Integer> path : paths) {
                Replay replay = replayAffine(graph, path, sample);
                if (isFeasible(replay, sample, budget)) {
                    feasible.add(replay);
                }
            }
            for (int i = 0; i < feasible.size(); i++) {
                for (int j = i + 1; j < feasible.size(); j++) {
                    if (feasible.get(i).score() != feasible.get(j).score()) {
                        continue;
                    }
                    Affine difference = feasible.get(i).arrival().subtract(feasible.get(j).arrival());
                    if (difference.slope().signum() == 0) {
                        continue;
                    }
                    ExactFraction root = difference.intercept().negate().divide(difference.slope());
                    if (strictlyInside(root, left, right)) {
                        additions.add(root);
                    }
                }
            }
        }
        cuts.addAll(additions);
    }

    private static List<Segment> buildMaximalSegments(
            TDGraph graph,
            List<List<Integer>> paths,
            ExactFraction budget,
            TreeSet<ExactFraction> cuts) {
        List<ExactFraction> ordered = List.copyOf(cuts);
        List<Atom> atoms = new ArrayList<>();
        if (ordered.size() == 1) {
            ExactFraction point = ordered.get(0);
            atoms.add(new Atom(point, point, true, true, bestPathAt(graph, paths, point, budget)));
        } else {
            for (int i = 0; i < ordered.size(); i++) {
                ExactFraction point = ordered.get(i);
                atoms.add(new Atom(point, point, true, true, bestPathAt(graph, paths, point, budget)));
                if (i + 1 < ordered.size()) {
                    ExactFraction next = ordered.get(i + 1);
                    atoms.add(new Atom(
                            point,
                            next,
                            false,
                            false,
                            bestPathAt(graph, paths, midpoint(point, next), budget)));
                }
            }
        }

        List<Segment> result = new ArrayList<>();
        for (Atom atom : atoms) {
            Segment next = atom.toSegment();
            if (result.isEmpty()) {
                result.add(next);
                continue;
            }
            Segment previous = result.get(result.size() - 1);
            if (!previous.path().equals(next.path()) || !previous.end().equals(next.start())) {
                result.add(next);
                continue;
            }
            result.set(result.size() - 1, new Segment(
                    previous.start(),
                    next.end(),
                    previous.startInclusive(),
                    next.endInclusive(),
                    previous.path()));
        }
        return List.copyOf(result);
    }

    private static Optional<List<Integer>> bestPathAt(
            TDGraph graph,
            List<List<Integer>> paths,
            ExactFraction departure,
            ExactFraction budget) {
        PointCandidate best = null;
        for (List<Integer> path : paths) {
            Replay replay = replayPoint(graph, path, departure);
            if (!isFeasible(replay, departure, budget)) {
                continue;
            }
            PointCandidate candidate = new PointCandidate(path, replay.score(), replay.arrival().apply(departure));
            if (best == null || compare(candidate, best, departure) < 0) {
                best = candidate;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.path());
    }

    private static int compare(PointCandidate left, PointCandidate right, ExactFraction departure) {
        int comparison = Integer.compare(right.score(), left.score());
        if (comparison != 0) {
            return comparison;
        }
        ExactFraction leftTravel = left.arrival().subtract(departure);
        ExactFraction rightTravel = right.arrival().subtract(departure);
        comparison = leftTravel.compareTo(rightTravel);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.path().size(), right.path().size());
        if (comparison != 0) {
            return comparison;
        }
        return comparePathIds(left.path(), right.path());
    }

    private static Replay replayPoint(TDGraph graph, List<Integer> path, ExactFraction departure) {
        ExactFraction arrival = departure;
        int score = 0;
        for (int arcId : path) {
            Edge edge = graph.edges().get(arcId);
            Integer edgeScore = scoreAt(edge.scoreFunction(), arrival);
            ExactFraction travel = travelAt(edge.travelTimeFunction(), arrival);
            if (edgeScore == null || travel == null) {
                return null;
            }
            score += edgeScore;
            arrival = arrival.add(travel);
        }
        return new Replay(Affine.constant(arrival), score);
    }

    private static Replay replayAffine(TDGraph graph, List<Integer> path, ExactFraction sample) {
        Affine arrival = Affine.identity();
        int score = 0;
        for (int arcId : path) {
            Edge edge = graph.edges().get(arcId);
            ExactFraction entry = arrival.apply(sample);
            Integer edgeScore = scoreAt(edge.scoreFunction(), entry);
            if (edgeScore == null) {
                return null;
            }
            score += edgeScore;
            if (arrival.slope().signum() == 0) {
                ExactFraction travel = travelAt(edge.travelTimeFunction(), entry);
                if (travel == null) {
                    return null;
                }
                arrival = Affine.constant(entry.add(travel));
                continue;
            }
            Affine edgeArrival = edgeArrivalAffineAt(edge.travelTimeFunction(), entry);
            if (edgeArrival == null) {
                return null;
            }
            arrival = edgeArrival.compose(arrival);
        }
        return new Replay(arrival, score);
    }

    private static Affine edgeArrivalAffineAt(PiecewiseLinearFn function, ExactFraction entry) {
        if (!contains(function.domain(), entry)) {
            return null;
        }
        List<PiecewiseLinearFn.Breakpoint> points = function.breakpoints();
        for (int i = 1; i < points.size(); i++) {
            ExactFraction leftTime = fraction(points.get(i - 1).minute());
            ExactFraction rightTime = fraction(points.get(i).minute());
            if (entry.compareTo(leftTime) < 0 || entry.compareTo(rightTime) > 0) {
                continue;
            }
            ExactFraction leftValue = fraction(points.get(i - 1).value());
            ExactFraction rightValue = fraction(points.get(i).value());
            ExactFraction travelSlope = rightValue.subtract(leftValue)
                    .divide(rightTime.subtract(leftTime));
            ExactFraction travelIntercept = leftValue.subtract(travelSlope.multiply(leftTime));
            return new Affine(ExactFraction.ONE.add(travelSlope), travelIntercept);
        }
        return null;
    }

    private static ExactFraction travelAt(PiecewiseLinearFn function, ExactFraction entry) {
        if (!contains(function.domain(), entry)) {
            return null;
        }
        List<PiecewiseLinearFn.Breakpoint> points = function.breakpoints();
        for (PiecewiseLinearFn.Breakpoint point : points) {
            if (fraction(point.minute()).equals(entry)) {
                return fraction(point.value());
            }
        }
        Affine edgeArrival = edgeArrivalAffineAt(function, entry);
        return edgeArrival == null ? null : edgeArrival.apply(entry).subtract(entry);
    }

    private static Integer scoreAt(PiecewiseConstFn function, ExactFraction entry) {
        if (!contains(function.domain(), entry)) {
            return null;
        }
        List<PiecewiseConstFn.Interval> intervals = function.intervals();
        for (int i = 0; i < intervals.size(); i++) {
            PiecewiseConstFn.Interval interval = intervals.get(i);
            ExactFraction start = fraction(interval.startMinute());
            ExactFraction end = fraction(interval.endMinute());
            if (start.equals(end) && entry.equals(start)) {
                return interval.value();
            }
            if (entry.compareTo(start) >= 0 && entry.compareTo(end) < 0) {
                return interval.value();
            }
            if (entry.equals(end) && !startsAt(intervals, i + 1, entry)) {
                return interval.value();
            }
        }
        return null;
    }

    private static boolean startsAt(
            List<PiecewiseConstFn.Interval> intervals,
            int index,
            ExactFraction time) {
        return index < intervals.size()
                && fraction(intervals.get(index).startMinute()).equals(time);
    }

    private static boolean isFeasible(Replay replay, ExactFraction departure, ExactFraction budget) {
        return replay != null
                && replay.arrival().apply(departure).subtract(departure).compareTo(budget) <= 0;
    }

    private static List<ExactFraction> edgeEvents(Edge edge) {
        TreeSet<ExactFraction> events = new TreeSet<>();
        edge.travelTimeFunction().breakpoints().forEach(point -> events.add(fraction(point.minute())));
        edge.travelTimeFunction().domain().intervals().forEach(interval -> {
            events.add(fraction(interval.start()));
            events.add(fraction(interval.end()));
        });
        edge.scoreFunction().intervals().forEach(interval -> {
            events.add(fraction(interval.startMinute()));
            events.add(fraction(interval.endMinute()));
        });
        edge.scoreFunction().domain().intervals().forEach(interval -> {
            events.add(fraction(interval.start()));
            events.add(fraction(interval.end()));
        });
        return List.copyOf(events);
    }

    private static void addStrictInteriorPreimage(
            Set<ExactFraction> output,
            Affine mapping,
            ExactFraction target,
            ExactFraction left,
            ExactFraction right) {
        if (mapping.slope().signum() == 0) {
            return;
        }
        ExactFraction root = target.subtract(mapping.intercept()).divide(mapping.slope());
        if (strictlyInside(root, left, right)) {
            output.add(root);
        }
    }

    private static boolean strictlyInside(
            ExactFraction value,
            ExactFraction left,
            ExactFraction right) {
        return value.compareTo(left) > 0 && value.compareTo(right) < 0;
    }

    private static boolean contains(Domain domain, ExactFraction value) {
        for (Domain.Interval interval : domain.intervals()) {
            ExactFraction start = fraction(interval.start());
            ExactFraction end = fraction(interval.end());
            boolean afterStart = value.compareTo(start) > 0
                    || (value.equals(start) && interval.startInclusive());
            boolean beforeEnd = value.compareTo(end) < 0
                    || (value.equals(end) && interval.endInclusive());
            if (afterStart && beforeEnd) {
                return true;
            }
        }
        return false;
    }

    private static void requireGraphHorizon(
            TDGraph graph,
            ExactFraction start,
            ExactFraction end) {
        for (Edge edge : graph.edges()) {
            if (!covers(edge.travelTimeFunction().domain(), start, end)
                    || !covers(edge.scoreFunction().domain(), start, end)) {
                throw new IllegalArgumentException(
                        "graph functions do not cover query horizon on arc " + edge.arcId());
            }
        }
    }

    private static boolean covers(Domain domain, ExactFraction start, ExactFraction end) {
        ExactFraction cursor = start;
        boolean cursorCovered = false;
        for (Domain.Interval interval : domain.intervals()) {
            ExactFraction intervalStart = fraction(interval.start());
            ExactFraction intervalEnd = fraction(interval.end());
            if (intervalEnd.compareTo(cursor) < 0) {
                continue;
            }
            if (!cursorCovered) {
                if (intervalStart.compareTo(cursor) > 0 || !contains(domain, cursor)) {
                    return false;
                }
            } else if (intervalStart.compareTo(cursor) > 0
                    || (intervalStart.equals(cursor)
                    && !interval.startInclusive()
                    && !contains(domain, cursor))) {
                return false;
            }
            if (intervalEnd.compareTo(end) > 0
                    || (intervalEnd.equals(end) && interval.endInclusive())) {
                return contains(domain, end);
            }
            cursor = intervalEnd;
            cursorCovered = interval.endInclusive();
        }
        return false;
    }

    private static List<List<Integer>> enumerateSimplePaths(
            TDGraph graph,
            int source,
            int destination) {
        List<List<Integer>> paths = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerate(graph, source, destination, visited, new ArrayList<>(), paths);
        return List.copyOf(paths);
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            int destination,
            Set<Integer> visited,
            List<Integer> arcs,
            List<List<Integer>> output) {
        if (current == destination) {
            output.add(List.copyOf(arcs));
            if (output.size() > MAX_PATHS) {
                throw new IllegalArgumentException("tiny-graph oracle path limit exceeded");
            }
            return;
        }
        for (Edge edge : graph.outgoingEdges(current)) {
            if (!visited.add(edge.target())) {
                continue;
            }
            arcs.add(edge.arcId());
            enumerate(graph, edge.target(), destination, visited, arcs, output);
            arcs.remove(arcs.size() - 1);
            visited.remove(edge.target());
        }
    }

    private static int comparePathIds(List<Integer> left, List<Integer> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static ExactFraction midpoint(ExactFraction left, ExactFraction right) {
        return left.add(right).divide(ExactFraction.of(2));
    }

    private static ExactFraction fraction(double value) {
        return ExactFraction.fromDouble(value);
    }

    private record Affine(ExactFraction slope, ExactFraction intercept) {
        private static Affine identity() {
            return new Affine(ExactFraction.ONE, ExactFraction.ZERO);
        }

        private static Affine constant(ExactFraction value) {
            return new Affine(ExactFraction.ZERO, value);
        }

        private ExactFraction apply(ExactFraction value) {
            return slope.multiply(value).add(intercept);
        }

        private Affine compose(Affine inner) {
            return new Affine(
                    slope.multiply(inner.slope),
                    slope.multiply(inner.intercept).add(intercept));
        }

        private Affine subtract(Affine other) {
            return new Affine(slope.subtract(other.slope), intercept.subtract(other.intercept));
        }

        private Affine subtractIdentity() {
            return new Affine(slope.subtract(ExactFraction.ONE), intercept);
        }
    }

    private record Replay(Affine arrival, int score) {
    }

    private record PointCandidate(List<Integer> path, int score, ExactFraction arrival) {
        private PointCandidate {
            path = List.copyOf(path);
        }
    }

    private record Atom(
            ExactFraction start,
            ExactFraction end,
            boolean startInclusive,
            boolean endInclusive,
            Optional<List<Integer>> path) {
        private Segment toSegment() {
            return new Segment(start, end, startInclusive, endInclusive, path);
        }
    }
}
