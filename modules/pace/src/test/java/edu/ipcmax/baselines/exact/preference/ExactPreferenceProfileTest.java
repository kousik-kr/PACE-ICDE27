package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.testoracle.ExactFraction;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactPreferenceProfileTest {
    private static final ExactTime ROOT = ExactTime.of(20).divide(ExactTime.of(3));

    @Test
    void objectiveUsesPreferenceScoreAndBudgetUsesTravelTimeNeverStaticDistance() {
        TDGraph graph = graph(List.of(
                edgeWithDistance(0, 1, 2, 1000, travel(0, 5, 20, 5), scores(0, 20, 4)),
                edgeWithDistance(1, 1, 2, 1, travel(0, 12, 20, 12), scores(0, 20, 100)),
                edgeWithDistance(2, 1, 2, 800, travel(0, 8, 20, 8), scores(0, 20, 9))));
        var queryDomain = Domain.closed(0, 10);
        Map<Path, ExactPreferencePathProfile> profiles = new LinkedHashMap<>();
        for (int arcId = 0; arcId < 3; arcId++) {
            Path path = new Path(List.of(arcId));
            profiles.put(path, ExactPreferenceReplay.replay(
                    graph, 1, 2, path, queryDomain, 10).orElseThrow());
        }

        assertTrue(profiles.get(new Path(List.of(0))).feasibleAt(ExactTime.of(5)));
        assertFalse(profiles.get(new Path(List.of(1))).feasibleAt(ExactTime.of(5)));
        assertTrue(profiles.get(new Path(List.of(2))).feasibleAt(ExactTime.of(5)));
        var envelope = ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(queryDomain), profiles);
        assertEquals(List.of(2), envelope.cellAt(ExactTime.of(5)).orElseThrow().path().arcIds());
        assertEquals(BigInteger.valueOf(9), envelope.cellAt(ExactTime.of(5)).orElseThrow().score());
    }

    @Test
    void multiedgePathReadsEachPreferenceAtItsActualEdgeEntryTime() {
        TDGraph graph = graph(List.of(
                edgeWithDistance(0, 1, 2, 1000, travel(0, 5, 30, 5), scores(0, 30, 3)),
                edgeWithDistance(1, 2, 3, 1, travel(0, 2, 30, 2),
                        scores(0, 10, 1, 10, 30, 20))));
        var profile = ExactPreferenceReplay.replay(
                graph, 1, 3, new Path(List.of(0, 1)), Domain.closed(0, 10), 10).orElseThrow();

        // Departure 5 reaches the second edge at 10, which belongs to its score-20 cell.
        assertEquals(BigInteger.valueOf(23), profile.score().valueAt(ExactTime.of(5)));
        assertEquals(BigInteger.valueOf(4), profile.score().valueAt(ExactTime.of(0)));
    }

    @Test
    void rationalReplayMatchesAnIndependentEdgeByEdgeOracleAtRootsAndDecimals() {
        TDGraph graph = rootGraph();
        Path path = new Path(List.of(0, 1));
        var profile = ExactPreferenceReplay.replay(graph, 1, 3, path, Domain.closed(0, 20), 80)
                .orElseThrow();
        for (String spelling : List.of("0", "6.666666666666", "20/3", "6.666666666667", "20")) {
            ExactTime departure = parse(spelling);
            Reference expected = independentReplay(graph, path.arcIds(), fraction(departure));
            assertEquals(expected.arrival(), fraction(profile.arrival().valueAtExact(departure)), spelling);
            assertEquals(BigInteger.valueOf(expected.score()), profile.score().valueAt(departure), spelling);
        }
        assertEquals(ExactTime.parseDecimal("60.0000000000005"),
                profile.arrival().valueAtExact(ExactTime.parseDecimal("6.666666666667")));
        assertEquals(ExactTime.of(60), profile.arrival().valueAtExact(ROOT));
        assertEquals(10, profile.score().valueAt(ROOT).intValueExact());
    }

    @Test
    void exactScorePreimagesAreRightContinuousAndNestedArrivalCompositionIsAssociative() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 2, travel(0, 1, 100, 1), scores(0, 5, 2, 5, 20, 9)),
                edge(1, 2, 3, travel(0, 3, 100, 3), scores(0, 20, 4)),
                edge(2, 3, 4, travel(0, 2, 100, 2), scores(0, 20, 1))));
        Path path = new Path(List.of(0, 1, 2));
        var result = ExactPreferenceReplay.replay(graph, 1, 4, path, Domain.closed(0, 20), 10).orElseThrow();
        assertEquals(BigInteger.valueOf(14), result.score().valueAt(ExactTime.of(5)));
        assertEquals(BigInteger.valueOf(7), result.score().valueAt(ExactTime.of(4)));

        ExactArrivalProfile a = ExactArrivalProfile.edgeArrival(graph.edges().get(0));
        ExactArrivalProfile b = ExactArrivalProfile.edgeArrival(graph.edges().get(1));
        ExactArrivalProfile c = ExactArrivalProfile.edgeArrival(graph.edges().get(2));
        assertTrue(a.composeAfter(b).composeAfter(c).sameFunction(a.composeAfter(b.composeAfter(c))));
        assertTrue(result.arrival().sameFunction(a.composeAfter(b).composeAfter(c)
                .restrict(result.arrival().domain())));
    }

    @Test
    void budgetRootsOwnEqualityButNeverEitherAdjacentCanonicalDecimal() {
        TDGraph graph = graph(List.of(edge(0, 1, 2, travel(0, 1, 10, 31), scores(0, 10, 7))));
        var path = ExactPreferenceReplay.replay(graph, 1, 2, new Path(List.of(0)),
                Domain.closed(0, 9), 21).orElseThrow();
        assertTrue(path.feasibleDomain().contains(ROOT));
        assertTrue(path.feasibleDomain().contains(ExactTime.parseDecimal("6.666666666666")));
        assertFalse(path.feasibleDomain().contains(ExactTime.parseDecimal("6.666666666667")));
        assertFalse(path.feasibleDomain().contains(ExactTime.parseDecimal("6.666666666668")));
        assertEquals(1, path.budgetRootOperations());
        assertEquals(ExactTime.of(21), path.arrival().valueAtExact(ROOT).subtract(ROOT));
    }

    @Test
    void exactEnvelopeRepairsWrongWinnerAndMatchesIndependentOracleOnWholeCells() {
        TDGraph graph = wrongWinnerGraph();
        List<Path> paths = List.of(new Path(List.of(0, 1, 2)), new Path(List.of(3)));
        Map<Path, ExactPreferencePathProfile> profiles = new LinkedHashMap<>();
        for (Path path : paths) {
            profiles.put(path, ExactPreferenceReplay.replay(graph, 1, 4, path,
                    Domain.closed(0, 19), 80).orElseThrow());
        }
        var envelope = ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(Domain.closed(0, 19)), profiles);
        var beforeRoot = envelope.cellAt(ExactTime.parseDecimal("6.666666666665")).orElseThrow();
        assertEquals(List.of(3), beforeRoot.path().arcIds());
        assertEquals(BigInteger.valueOf(15), beforeRoot.score());
        var atRoot = envelope.cellAt(ROOT).orElseThrow();
        assertEquals(List.of(0, 1, 2), atRoot.path().arcIds());
        assertEquals(BigInteger.valueOf(20), atRoot.score());

        QuerySpec query = new QuerySpec(1, 4, 0, 19, 80, 1);
        List<TinyContinuousEnvelopeOracle.TemporalCell> independent =
                TinyContinuousEnvelopeOracle.solveCellsForPaths(graph, query,
                        paths.stream().map(Path::arcIds).toList());
        for (var cell : independent) {
            ExactTime start = parse(cell.start().toString());
            ExactTime end = parse(cell.end().toString());
            ExactTime sample = start.equals(end) ? start : start.add(end).divide(ExactTime.of(2));
            var actual = envelope.cellAt(sample).orElseThrow();
            assertEquals(cell.path().map(Path::new).orElse(null), actual.path(), "cell=" + cell);
            if (cell.path().isPresent()) {
                assertEquals(BigInteger.valueOf(cell.value().orElseThrow().score()), actual.score());
                var value = cell.value().orElseThrow();
                ExactTime oracleArrival = parse(value.arrivalSlope().toString()).multiply(sample)
                        .add(parse(value.arrivalIntercept().toString()));
                assertEquals(oracleArrival, actual.arrivalAt(sample));
                if (!start.equals(end)) {
                    assertEquals(parse(value.arrivalSlope().toString()), actual.arrivalSlope());
                    assertEquals(parse(value.arrivalIntercept().toString()), actual.arrivalIntercept());
                }
            }
        }
    }

    @Test
    void tieBreaksUseTravelTimeThenEdgeCountThenStableArcIds() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 4, travel(0, 5, 10, 5), scores(0, 10, 8)),
                edge(1, 1, 2, travel(0, 3, 10, 3), scores(0, 10, 4)),
                edge(2, 2, 4, travel(0, 2, 10, 2), scores(0, 10, 4)),
                edge(3, 1, 4, travel(0, 5, 10, 5), scores(0, 10, 8))));
        List<Path> paths = List.of(new Path(List.of(0)), new Path(List.of(1, 2)), new Path(List.of(3)));
        Map<Path, ExactPreferencePathProfile> profiles = new LinkedHashMap<>();
        paths.forEach(path -> profiles.put(path, ExactPreferenceReplay.replay(graph, 1, 4, path,
                Domain.closed(0, 10), 5).orElseThrow()));
        var envelope = ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(Domain.closed(0, 10)), profiles);
        assertEquals(List.of(0), envelope.cellAt(ExactTime.of(5)).orElseThrow().path().arcIds());
    }

    @Test
    void exactTravelCrossingAndFifoPlateauOwnTheCorrectRoutesAndEntryScores() {
        TDGraph crossingGraph = graph(List.of(
                edge(0, 1, 2, travel(0, 2, 10, 2), scores(0, 10, 9)),
                edge(1, 1, 2, travel(0, 4, 10, 0), scores(0, 10, 9))));
        Map<Path, ExactPreferencePathProfile> crossingProfiles = new LinkedHashMap<>();
        for (Path path : List.of(new Path(List.of(0)), new Path(List.of(1)))) {
            crossingProfiles.put(path, ExactPreferenceReplay.replay(crossingGraph, 1, 2, path,
                    Domain.closed(0, 10), 10).orElseThrow());
        }
        var crossing = ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(Domain.closed(0, 10)),
                crossingProfiles);
        assertEquals(List.of(0), crossing.cellAt(ExactTime.of(5)).orElseThrow().path().arcIds());
        assertEquals(List.of(1), crossing.cellAt(ExactTime.of(6)).orElseThrow().path().arcIds());

        TDGraph plateauGraph = graph(List.of(
                edge(0, 1, 2, travel(0, 10, 10, 0, 20, 10), scores(0, 20, 2)),
                edge(1, 2, 3, travel(0, 0, 20, 0), scores(0, 10, 1, 10, 20, 9))));
        var plateau = ExactPreferenceReplay.replay(plateauGraph, 1, 3, new Path(List.of(0, 1)),
                Domain.closed(0, 20), 10).orElseThrow();
        assertEquals(ExactTime.of(10), plateau.arrival().valueAtExact(ExactTime.of(5)));
        assertEquals(BigInteger.valueOf(11), plateau.score().valueAt(ExactTime.of(5)));
        assertEquals(BigInteger.valueOf(11), plateau.score().valueAt(ExactTime.of(10)));
    }

    @Test
    void disconnectedSupportSingletonIdentityEmptyRegistryAndUnsupportedEdgesAreExact() {
        Domain separated = Domain.of(new Domain.Interval(0, 2, true, false),
                new Domain.Interval(2, 2), new Domain.Interval(8, 10, true, true));
        PiecewiseLinearFn restricted = travel(0, 1, 10, 1).restrict(separated);
        PiecewiseConstFn score = PiecewiseConstFn.constant(separated, 3);
        TDGraph graph = graph(List.of(edge(0, 1, 2, restricted, score)));
        var route = ExactPreferenceReplay.replay(graph, 1, 2, new Path(List.of(0)),
                Domain.closed(0, 10), 1).orElseThrow();
        assertTrue(route.feasibleDomain().contains(ExactTime.of(1)));
        assertTrue(route.feasibleDomain().contains(ExactTime.of(2)));
        assertFalse(route.arrival().domain().contains(ExactTime.of(5)));
        var envelope = ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(Domain.closed(0, 10)), Map.of(new Path(List.of(0)), route));
        assertFalse(envelope.cellAt(ExactTime.of(5)).orElseThrow().found());

        TDGraph identityGraph = graph(List.of());
        var identity = ExactPreferenceReplay.replay(identityGraph, 1, 1, Path.empty(),
                Domain.closed(4, 4), 0).orElseThrow();
        assertEquals(BigInteger.ZERO, identity.score().valueAt(ExactTime.of(4)));
        assertEquals(ExactTime.of(4), identity.arrival().valueAtExact(ExactTime.of(4)));
        assertFalse(ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(Domain.closed(4, 4)), Map.of())
                .cellAt(ExactTime.of(4)).orElseThrow().found());

        assertThrows(IllegalArgumentException.class, () -> ExactPreferenceReplay.replay(
                graph, 1, 2, new Path(List.of(0, 0)), Domain.closed(0, 10), 10));
    }

    @Test
    void codecRoundTripPreservesRootsEndpointOwnershipPathsAndDeterministicBytes() {
        TDGraph graph = rootGraph();
        Map<Path, ExactPreferencePathProfile> profiles = new LinkedHashMap<>();
        Path path = new Path(List.of(0, 1));
        profiles.put(path, ExactPreferenceReplay.replay(graph, 1, 3, path,
                Domain.closed(0, 20), 80).orElseThrow());
        var envelope = ExactPreferenceEnvelope.overRetained(
                edu.ipcmax.baselines.exact.ExactTemporalDomain.fromLegacy(Domain.closed(0, 20)), profiles);
        String encoded = ExactPreferenceProfileCodec.encode(envelope);
        var restored = ExactPreferenceProfileCodec.decode(encoded);
        assertEquals(envelope.domain(), restored.domain());
        assertEquals(envelope.cells(), restored.cells());
        assertEquals(encoded, ExactPreferenceProfileCodec.encode(restored));
        assertTrue(encoded.contains("20/3"));
        assertTrue(encoded.contains("\"start_inclusive\":false"));
    }

    @Test
    void pathValidationRejectsDiscontinuityAndBudgetScoresUseUnboundedIntegers() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 2, travel(0, 0, 10, 0), scores(0, 10, Integer.MAX_VALUE)),
                edge(1, 2, 3, travel(0, 0, 10, 0), scores(0, 10, Integer.MAX_VALUE))));
        var profile = ExactPreferenceReplay.replay(graph, 1, 3, new Path(List.of(0, 1)),
                Domain.closed(0, 10), 0).orElseThrow();
        assertEquals(BigInteger.valueOf(Integer.MAX_VALUE).multiply(BigInteger.TWO),
                profile.score().valueAt(ExactTime.of(5)));
        assertThrows(IllegalArgumentException.class, () -> ExactPreferenceReplay.replay(
                graph, 1, 3, new Path(List.of(1)), Domain.closed(0, 10), 0));
    }

    @Test
    void exactProfileHasNoBreakpointCapOrHiddenRoundingTolerance() {
        int pieces = 512;
        List<PiecewiseConstFn.Interval> intervals = new ArrayList<>();
        for (int i = 0; i < pieces; i++) intervals.add(new PiecewiseConstFn.Interval(i, i + 1, i));
        TDGraph graph = graph(List.of(edge(0, 1, 2, travel(0, 0, pieces, 0),
                new PiecewiseConstFn(intervals))));
        var exact = ExactPreferenceReplay.replay(graph, 1, 2, new Path(List.of(0)),
                Domain.closed(0, pieces), 0).orElseThrow();
        assertEquals(pieces, exact.score().pieceCount());
        assertEquals(pieces - 1, exact.scoreEventPreimages());
        assertEquals(BigInteger.valueOf(256), exact.score().valueAt(ExactTime.of(256)));
        assertEquals(BigInteger.valueOf(511), exact.score().valueAt(ExactTime.of(512)));
        assertTrue(exact.arrival().maximumRationalComponentBitLength() > 0);
    }

    private record Reference(ExactFraction arrival, int score) { }

    private static Reference independentReplay(TDGraph graph, List<Integer> arcs, ExactFraction departure) {
        ExactFraction time = departure;
        int score = 0;
        for (int arc : arcs) {
            Edge edge = graph.edges().get(arc);
            score += independentScore(edge.scoreFunction(), time);
            var points = edge.travelTimeFunction().breakpoints();
            ExactFraction travel = null;
            for (int i = 1; i < points.size(); i++) {
                ExactFraction left = fraction(points.get(i - 1).minute());
                ExactFraction right = fraction(points.get(i).minute());
                if (time.compareTo(left) >= 0 && time.compareTo(right) <= 0) {
                    ExactFraction leftValue = fraction(points.get(i - 1).value());
                    ExactFraction slope = fraction(points.get(i).value()).subtract(leftValue)
                            .divide(right.subtract(left));
                    travel = leftValue.add(slope.multiply(time.subtract(left)));
                    break;
                }
            }
            if (travel == null) throw new AssertionError("independent oracle found unsupported time");
            time = time.add(travel);
        }
        return new Reference(time, score);
    }

    private static int independentScore(PiecewiseConstFn function, ExactFraction time) {
        var pieces = function.intervals();
        for (int i = 0; i < pieces.size(); i++) {
            var piece = pieces.get(i);
            ExactFraction start = fraction(piece.startMinute());
            ExactFraction end = fraction(piece.endMinute());
            boolean nextOwnsEnd = i + 1 < pieces.size()
                    && fraction(pieces.get(i + 1).startMinute()).equals(end);
            if ((time.compareTo(start) >= 0 && time.compareTo(end) < 0)
                    || (time.equals(end) && !nextOwnsEnd)) return piece.value();
            if (start.equals(end) && time.equals(start)) return piece.value();
        }
        throw new AssertionError("independent oracle found undefined score");
    }

    private static ExactFraction fraction(ExactTime time) { return parseFraction(time.toString()); }
    private static ExactFraction fraction(double time) { return ExactFraction.fromDouble(time); }
    private static ExactTime parse(String value) {
        String[] parts = value.split("/");
        return parts.length == 1 ? ExactTime.parseDecimal(value)
                : ExactTime.of(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }
    private static ExactFraction parseFraction(String value) {
        String[] parts = value.split("/");
        return parts.length == 1 ? ExactFraction.of(Long.parseLong(parts[0]))
                : ExactFraction.of(Long.parseLong(parts[0])).divide(ExactFraction.of(Long.parseLong(parts[1])));
    }
    private static ExactTime parseTime(double value) { return ExactTime.fromCanonicalDouble(value); }

    private static TDGraph rootGraph() {
        return graph(List.of(
                edge(0, 1, 2, travel(0, 10, 40, 30, 100, 40), scores(0, 100, 1)),
                edge(1, 2, 3, travel(0, 40, 50, 40, 80, 10, 100, 10),
                        scores(0, 20, 1, 20, 60, 9, 60, 100, 2))));
    }

    private static TDGraph wrongWinnerGraph() {
        return graph(List.of(
                edge(0, 1, 2, travel(0, 10, 40, 30, 100, 40), scores(0, 100, 0)),
                edge(1, 2, 3, travel(0, 40, 50, 40, 80, 10, 100, 10), scores(0, 100, 0)),
                edge(2, 3, 4, travel(0, 1, 100, 1), new PiecewiseConstFn(List.of(
                        new PiecewiseConstFn.Interval(0, 59.999999999998, 10),
                        new PiecewiseConstFn.Interval(59.999999999998, 100, 20)))),
                edge(3, 1, 4, travel(0, 70, 100, 70), scores(0, 100, 15))));
    }

    private static TDGraph graph(List<Edge> edges) {
        TreeSet<Integer> ids = new TreeSet<>();
        edges.forEach(edge -> { ids.add(edge.source()); ids.add(edge.target()); });
        if (ids.isEmpty()) ids.add(1);
        return new TDGraph(ids.stream().map(id -> new Node(id, id, 0)).toList(), edges);
    }
    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel, score);
    }
    private static Edge edgeWithDistance(int id, int source, int target, long distance,
            PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(id, source, target, distance, travel.minTravelTime(), travel, score);
    }
    private static PiecewiseLinearFn travel(double... values) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) points.add(new PiecewiseLinearFn.Breakpoint(values[i], values[i + 1]));
        return new PiecewiseLinearFn(points);
    }
    private static PiecewiseConstFn scores(double... values) {
        List<PiecewiseConstFn.Interval> pieces = new ArrayList<>();
        for (int i = 0; i < values.length; i += 3) {
            pieces.add(new PiecewiseConstFn.Interval(values[i], values[i + 1], (int) values[i + 2]));
        }
        return new PiecewiseConstFn(pieces);
    }
}
