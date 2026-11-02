package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.testoracle.ExactFraction;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SicspPreferenceEngineTest {
    @Test
    void preferenceDominancePreservesStrictOAndSkylineEqualityConventions() {
        assertTrue(SicspPreferenceDominance.strictlyDominates(5, 8, 6, 7));
        assertFalse(SicspPreferenceDominance.strictlyDominates(5, 8, 5, 7));
        assertFalse(SicspPreferenceDominance.strictlyDominates(5, 7, 6, 7));
        assertTrue(SicspPreferenceDominance.skylineDominates(5, 8, 6, 8));
        assertTrue(SicspPreferenceDominance.skylineDominates(5, 9, 5, 8));
        assertFalse(SicspPreferenceDominance.skylineDominates(5, 8, 5, 8));
    }

    @Test
    void adaptsOnlyTheObjectiveAndKeepsNativeFastestResultAndEventPaths() {
        TDGraph temporal = graph(List.of(1, 2, 4),
                edge(0, 1, 4, constantTravel(0, 20, 2), constantScore(0, 20, 2)),
                edge(1, 1, 2, constantTravel(0, 20, 5), constantScore(0, 20, 4)),
                edge(2, 2, 4, constantTravel(0, 20, 5), constantScore(0, 20, 6)));
        SicspPrefQuery prefQuery = new SicspPrefQuery(1, 4, Domain.closed(0, 10), 10);

        SicspPrefResult preference = new SicspPreferenceEngine(temporal).query(prefQuery);
        SicspNativeResult nativeResult = new SicspNativeEngine(nativeGraph(temporal)).query(
                new SicspNativeQuery(1, 4, 0, 10, 10));

        assertEquals(List.of(1, 2), preference.envelope().segmentAt(0).path().arcIds());
        assertEquals(10, preference.envelope().segmentAt(0).candidate().scoreProfile().valueAt(0));
        assertEquals(List.of(0), nativeResult.selection().orElseThrow().path().arcIds());
        assertEquals(2.0, nativeResult.selection().orElseThrow().destinationArrival());
        assertEquals(eventSignatures(nativeResult.arrivalCandidates().generatedPoints()),
                prefEventSignatures(preference.rawOPoints()));
        assertEquals(eventSignatures(nativeResult.weightCandidates().generatedPoints()),
                prefEventSignatures(preference.rawWPoints()));
        assertTrue(preference.rawWPoints().stream()
                .anyMatch(candidate -> candidate.eventArcId() == 1
                        && candidate.sourceDeparture().orElseThrow() == 0.0
                        && candidate.replayedTravelTime().orElseThrow() == 10.0
                        && candidate.replayedPreferenceScore().orElseThrow() == 10));
        assertRetainedFrontierEnvelopeIsExact(preference);
        assertEquals(globalOracle(temporal, prefQuery).segments().stream()
                        .map(segment -> segment.path().arcIds()).toList(),
                preference.envelope().segments().stream()
                        .map(segment -> segment.path().arcIds()).toList());
    }

    @Test
    void preferenceBreakpointsCanChangeTheSelectedRouteInsideTheInterval() {
        TDGraph temporal = graph(List.of(1, 2, 4),
                edge(0, 1, 4, constantTravel(0, 20, 2),
                        scores(new PiecewiseConstFn.Interval(0, 5, 0),
                                new PiecewiseConstFn.Interval(5, 20, 10))),
                edge(1, 1, 2, constantTravel(0, 20, 3), constantScore(0, 20, 4)),
                edge(2, 2, 4, constantTravel(0, 20, 3), constantScore(0, 20, 4)));
        SicspPrefQuery query = new SicspPrefQuery(1, 4, Domain.closed(0, 10), 8);

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(query);

        assertEquals(List.of(1, 2), result.envelope().segmentAt(0).path().arcIds());
        assertEquals(List.of(0), result.envelope().segmentAt(5).path().arcIds());
        assertEquals(10, result.envelope().segmentAt(5).candidate().scoreProfile().valueAt(5));
        assertTrue(result.candidateGeneration().preferenceBreakpointsExamined() > 0);
        assertRetainedFrontierEnvelopeIsExact(result);
        assertEnvelopeMatches(globalOracle(temporal, query), result.envelope(),
                List.of(0.0, 2.5, 5.0, 7.5, 10.0));
    }

    @Test
    void budgetEqualityAndSingletonDepartureCellsRemainFeasible() {
        TDGraph temporal = graph(List.of(1, 2), edge(0, 1, 2,
                constantTravel(0, 10, 5), constantScore(0, 10, 7)));
        SicspPrefQuery query = new SicspPrefQuery(1, 2, Domain.closed(0, 0), 5);

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(query);

        assertEquals(1, result.envelope().segments().size());
        assertTrue(result.envelope().segmentAt(0).found());
        assertEquals(List.of(0), result.envelope().segmentAt(0).path().arcIds());
        assertEquals(1.0, result.statistics().feasibleCoverage());
        assertEquals(0.0, result.statistics().integratedPreferenceScore());
        assertRetainedFrontierEnvelopeIsExact(result);
    }

    @Test
    void equalPreferencesUseTravelTimeThenStablePathOrder() {
        TDGraph temporal = graph(List.of(1, 2, 4),
                edge(0, 1, 4, constantTravel(0, 10, 2), constantScore(0, 10, 8)),
                edge(1, 1, 2, constantTravel(0, 10, 1), constantScore(0, 10, 4)),
                edge(2, 2, 4, constantTravel(0, 10, 1), constantScore(0, 10, 4)));
        SicspPrefQuery query = new SicspPrefQuery(1, 4, Domain.closed(0, 5), 2);

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(query);

        assertEquals(List.of(0), result.envelope().segmentAt(0).path().arcIds());
        assertEquals(8, result.envelope().segmentAt(0).candidate().scoreProfile().valueAt(0));
        assertEquals(2.0, result.envelope().segmentAt(0).candidate().travelTimeAt(0));
        assertRetainedFrontierEnvelopeIsExact(result);
    }

    @Test
    void travelBudgetCrossingKeepsTheEqualityPointAndTheInfeasibleCell() {
        TDGraph temporal = graph(List.of(1, 2), edge(0, 1, 2,
                travel(0, 5, 10, 1), constantScore(0, 10, 3)));
        SicspPrefQuery query = new SicspPrefQuery(1, 2, Domain.closed(0, 10), 3);

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(query);

        assertFalse(result.envelope().segmentAt(4.0).found());
        assertTrue(result.envelope().segmentAt(5.0).found());
        assertTrue(result.envelope().segmentAt(5.0).interval().startInclusive());
        assertEquals(3.0, result.envelope().segmentAt(5.0).candidate().travelTimeAt(5.0));
        assertTrue(result.statistics().feasibleCoverage() > 0.49);
        assertTrue(result.statistics().feasibleCoverage() < 0.51);
        assertRetainedFrontierEnvelopeIsExact(result);
    }

    @Test
    void fifoPlateauAndPreferenceAtActualEdgeEntryArePreserved() {
        PiecewiseLinearFn plateau = travel(0, 10, 10, 0, 20, 10);
        TDGraph plateauGraph = graph(List.of(1, 2), edge(0, 1, 2,
                plateau, constantScore(0, 20, 2)));
        SicspPrefResult plateauResult = new SicspPreferenceEngine(plateauGraph).query(
                new SicspPrefQuery(1, 2, Domain.closed(0, 20), 10));
        assertEquals(10.0, plateauResult.envelope().segmentAt(5).candidate()
                .arrivalProfile().valueAt(5));
        assertEquals(20.0, plateauResult.envelope().segmentAt(15).candidate()
                .arrivalProfile().valueAt(15));

        TDGraph entryScoreGraph = graph(List.of(1, 2, 3),
                edge(0, 1, 2, constantTravel(0, 20, 5), constantScore(0, 20, 1)),
                edge(1, 2, 3, constantTravel(0, 20, 1),
                        scores(new PiecewiseConstFn.Interval(0, 5, 0),
                                new PiecewiseConstFn.Interval(5, 20, 10))));
        SicspPrefResult entryScoreResult = new SicspPreferenceEngine(entryScoreGraph).query(
                new SicspPrefQuery(1, 3, Domain.closed(0, 5), 6));
        assertEquals(11, entryScoreResult.envelope().segmentAt(0)
                .candidate().scoreProfile().valueAt(0));
        assertTrue(entryScoreResult.rawWPoints().stream()
                .anyMatch(candidate -> candidate.eventArcId() == 1 && candidate.eventTime() == 5.0));
    }

    @Test
    void rootRoundingRemainsAOneTickDocumentedBoundaryCase() {
        PiecewiseLinearFn first = travel(0, 10, 40, 30, 100, 40);
        PiecewiseLinearFn second = travel(0, 40, 50, 40, 80, 10, 100, 10);
        TDGraph temporal = graph(List.of(1, 2, 3),
                edge(0, 1, 2, first, constantScore(0, 100, 1)),
                edge(1, 2, 3, second,
                        scores(new PiecewiseConstFn.Interval(0, 20, 1),
                                new PiecewiseConstFn.Interval(20, 60, 9),
                                new PiecewiseConstFn.Interval(60, 100, 2))));
        double roundedRoot = 6.666666666667;
        CandidateProfile profile = ExactPathProfileBuilder.budgetContext(
                temporal, Domain.closed(0, 30), 80, () -> false)
                .replay(List.of(0, 1), 1, 3).orElseThrow();
        double replayPrefixArrival = first.arrivalTimeAt(roundedRoot);
        double replayArrival = second.arrivalTimeAt(replayPrefixArrival);

        assertEquals(20.000000000001, replayPrefixArrival);
        assertEquals(60.000000000001, replayArrival);
        assertEquals(60.0, profile.arrivalProfile().valueAt(roundedRoot));
        assertEquals(1e-12, replayArrival - profile.arrivalProfile().valueAt(roundedRoot), 5e-15);
        assertEquals(10, profile.scoreProfile().valueAt(roundedRoot));
    }

    @Test
    void retainedRegistryWithNoBudgetFeasibleProfileReturnsExplicitNoPathCells() {
        TDGraph temporal = graph(List.of(1, 2), edge(0, 1, 2,
                constantTravel(0, 10, 5), constantScore(0, 10, 7)));

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(
                new SicspPrefQuery(1, 2, Domain.closed(0, 5), 4));

        assertEquals(List.of(new Path(List.of(0))), result.rawCandidatePaths());
        assertEquals(result.rawCandidatePaths(), result.simpleCandidatePaths());
        assertEquals(result.rawCandidatePaths(), result.retainedCandidatePaths());
        assertTrue(result.feasibleCandidateProfiles().isEmpty());
        assertTrue(result.envelope().segments().stream().allMatch(segment -> !segment.found()));
        assertEquals(1, result.statistics().candidatesWithEmptyFeasibleDomains());
    }

    @Test
    void cyclicNativeWitnessIsRejectedAtThePaceBoundary() {
        TDGraph temporal = graph(List.of(1, 2, 3, 4),
                edge(0, 1, 2, constantTravel(0, 10, 1), constantScore(0, 10, 1)),
                edge(1, 2, 3, constantTravel(0, 10, 1), constantScore(0, 10, 1)),
                edge(2, 3, 2, travel(0, 2, 2, 0, 10, 8), constantScore(0, 10, 1)),
                edge(3, 2, 4, constantTravel(0, 10, 1), constantScore(0, 10, 1)));

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(
                new SicspPrefQuery(1, 4, Domain.closed(0, 5), 10));

        assertTrue(result.rawCandidatePaths().stream()
                .anyMatch(path -> path.arcIds().equals(List.of(0, 1, 2, 3))));
        assertTrue(result.rejectedNonSimpleEvents().stream()
                .anyMatch(candidate -> candidate.path().orElseThrow().arcIds()
                        .equals(List.of(0, 1, 2, 3))
                        && candidate.paceRejectionReason().startsWith("REPEATED_VERTEX:")));
        assertTrue(result.rawOPoints().stream()
                .filter(candidate -> candidate.path().orElseThrow().arcIds()
                        .equals(List.of(0, 1, 2, 3)))
                .allMatch(candidate -> !candidate.paceVertexSimple()));
        assertFalse(result.retainedCandidatePaths().stream()
                .anyMatch(path -> path.arcIds().equals(List.of(0, 1, 2, 3))));
    }

    @Test
    void exhaustiveOracleFindsAHighPreferenceRouteMissedByTheSicspEventHeuristic() {
        TDGraph temporal = graph(List.of(1, 2, 3, 4, 5, 6, 7),
                edge(0, 1, 2, constantTravel(0, 30, 1), constantScore(0, 30, 0)),
                edge(1, 2, 4, constantTravel(0, 30, 1), constantScore(0, 30, 0)),
                edge(2, 1, 3, constantTravel(0, 30, 3), constantScore(0, 30, 10)),
                edge(3, 3, 4, constantTravel(0, 30, 1), constantScore(0, 30, 10)),
                edge(4, 4, 5, constantTravel(0, 30, 1), constantScore(0, 30, 0)),
                edge(5, 5, 7, constantTravel(0, 30, 1), constantScore(0, 30, 0)),
                edge(6, 4, 6, constantTravel(0, 30, 2), constantScore(0, 30, 10)),
                edge(7, 6, 7, constantTravel(0, 30, 4), constantScore(0, 30, 10)));
        SicspPrefQuery query = new SicspPrefQuery(1, 7, Domain.closed(0, 20), 10);
        Path globallyBest = new Path(List.of(2, 3, 6, 7));

        SicspPrefResult result = new SicspPreferenceEngine(temporal).query(query);
        EnvelopeProfile oracle = globalOracle(temporal, query);

        assertTrue(allSimplePaths(temporal, 1, 7).contains(globallyBest));
        assertFalse(result.rawCandidatePaths().contains(globallyBest));
        assertEquals(globallyBest.arcIds(), oracle.segmentAt(0).path().arcIds());
        assertEquals(40, oracle.segmentAt(0).candidate().scoreProfile().valueAt(0));
        assertTrue(result.statistics().integratedPreferenceScore() < integratedScore(oracle));
        assertRetainedFrontierEnvelopeIsExact(result);

        QuerySpec exactQuery = new QuerySpec(1, 7, 0, 20, 10, 1);
        ExactPreferenceEnvelope rawEnvelope = exactEnvelope(temporal, exactQuery,
                result.simpleCandidatePaths());
        ExactPreferenceEnvelope retainedEnvelope = result.exactEnvelope();
        List<Path> globallyEnumerated = allSimplePaths(temporal, 1, 7);
        ExactPreferenceEnvelope globalEnvelope = exactEnvelope(temporal, exactQuery, globallyEnumerated);
        assertExactEnvelopeMatchesIndependentCells(temporal, exactQuery,
                result.simpleCandidatePaths(), rawEnvelope);
        assertExactEnvelopeMatchesIndependentCells(temporal, exactQuery,
                result.budgetFeasibleRetainedPaths(), retainedEnvelope);
        assertExactEnvelopeMatchesIndependentCells(temporal, exactQuery,
                globallyEnumerated, globalEnvelope);
        assertEquals(result.retainedCandidatePaths().stream()
                        .filter(result.budgetFeasibleRetainedPaths()::contains).sorted(SicspPathOrder::compare).toList(),
                result.budgetFeasibleRetainedPaths());
        ExactTime departure = ExactTime.ZERO;
        assertEquals(globallyBest.arcIds(), globalEnvelope.cellAt(departure).orElseThrow().path().arcIds());
        assertFalse(result.budgetFeasibleRetainedPaths().contains(globallyBest));
        assertFalse(retainedEnvelope.cellAt(departure).orElseThrow().path().arcIds()
                .equals(globallyBest.arcIds()));
    }

    private static ExactPreferenceEnvelope exactEnvelope(
            TDGraph graph, QuerySpec query, List<Path> paths) {
        Map<Path, ExactPreferencePathProfile> profiles = new java.util.LinkedHashMap<>();
        for (Path path : paths) {
            ExactPreferenceReplay.replay(graph, query.source(), query.destination(), path,
                    Domain.closed(query.departureStart(), query.departureEnd()), query.maxTravelTime())
                    .ifPresent(profile -> profiles.put(path, profile));
        }
        return ExactPreferenceEnvelope.overRetained(
                ExactTemporalDomain.fromLegacy(Domain.closed(query.departureStart(), query.departureEnd())), profiles);
    }

    private static void assertExactEnvelopeMatchesIndependentCells(
            TDGraph graph, QuerySpec query, List<Path> paths, ExactPreferenceEnvelope actual) {
        List<TinyContinuousEnvelopeOracle.TemporalCell> expected =
                TinyContinuousEnvelopeOracle.solveCellsForPaths(graph, query,
                        paths.stream().map(Path::arcIds).toList());
        for (var cell : expected) {
            ExactFraction sample = cell.start().equals(cell.end()) ? cell.start()
                    : cell.start().add(cell.end()).divide(ExactFraction.of(2));
            ExactTime departure = exactTime(sample);
            var selected = actual.cellAt(departure).orElseThrow(() ->
                    new AssertionError("missing exact cell at " + departure));
            assertEquals(cell.path().map(Path::new).orElse(null), selected.path(), "departure=" + departure);
            if (cell.path().isPresent()) {
                var value = cell.value().orElseThrow();
                assertEquals(BigInteger.valueOf(value.score()), selected.score(), "departure=" + departure);
                ExactTime arrival = exactTime(value.arrivalSlope()).multiply(departure)
                        .add(exactTime(value.arrivalIntercept()));
                assertEquals(arrival, selected.arrivalAt(departure), "departure=" + departure);
            }
        }
    }

    private static ExactTime exactTime(ExactFraction value) {
        String[] parts = value.toString().split("/", -1);
        return parts.length == 1 ? ExactTime.of(new BigInteger(parts[0]), BigInteger.ONE)
                : ExactTime.of(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    private static void assertRetainedFrontierEnvelopeIsExact(SicspPrefResult result) {
        CandidateSet retained = new CandidateSet();
        retained.addAllCandidates(List.copyOf(result.feasibleCandidateProfiles().values()));
        EnvelopeProfile exactRetained = EnvelopeExtractor.extract(retained, result.envelope().domain());
        assertEnvelopeMatches(exactRetained, result.envelope(), List.of(
                result.envelope().domain().intervals().get(0).start(),
                midpoint(result.envelope().domain().intervals().get(0).start(),
                        result.envelope().domain().intervals().get(0).end()),
                result.envelope().domain().intervals().get(0).end()));
    }

    private static void assertEnvelopeMatches(
            EnvelopeProfile expected,
            EnvelopeProfile actual,
            List<Double> departures) {
        for (double departure : departures) {
            var left = expected.segmentAt(departure);
            var right = actual.segmentAt(departure);
            assertEquals(left.found(), right.found(), "departure=" + departure);
            if (!left.found()) {
                continue;
            }
            assertEquals(left.path().arcIds(), right.path().arcIds(), "departure=" + departure);
            assertEquals(left.candidate().scoreProfile().valueAt(departure),
                    right.candidate().scoreProfile().valueAt(departure), "departure=" + departure);
            assertEquals(left.candidate().travelTimeAt(departure),
                    right.candidate().travelTimeAt(departure), 1e-9, "departure=" + departure);
        }
    }

    private static EnvelopeProfile globalOracle(TDGraph graph, SicspPrefQuery query) {
        CandidateSet candidates = new CandidateSet();
        var replay = ExactPathProfileBuilder.budgetContext(
                graph, query.departureDomain(), query.travelTimeBudget(), () -> false);
        for (Path path : allSimplePaths(graph, query.source(), query.destination())) {
            replay.replay(path.arcIds(), query.source(), query.destination()).ifPresent(candidates::add);
        }
        return EnvelopeExtractor.extract(candidates, query.departureDomain());
    }

    private static List<Path> allSimplePaths(TDGraph graph, int source, int destination) {
        if (source == destination) {
            return List.of(Path.empty());
        }
        List<Path> paths = new ArrayList<>();
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
            List<Integer> path,
            List<Path> paths) {
        for (Edge edge : graph.outgoingEdges(current)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            path.add(edge.arcId());
            if (edge.target() == destination) {
                paths.add(new Path(path));
            } else {
                visited.add(edge.target());
                enumerate(graph, edge.target(), destination, visited, path, paths);
                visited.remove(edge.target());
            }
            path.remove(path.size() - 1);
        }
    }

    private static List<String> eventSignatures(List<SicspBreakpointCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.type() + ":" + candidate.eventArcId() + ":"
                        + candidate.eventTime() + ":" + candidate.sourceDeparture().orElseThrow()
                        + ":" + candidate.replayedDestinationArrival().orElseThrow()
                        + ":" + candidate.path().orElseThrow().arcIds())
                .sorted()
                .toList();
    }

    private static List<String> prefEventSignatures(List<SicspPrefBreakpointCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> candidate.type() + ":" + candidate.eventArcId() + ":"
                        + candidate.eventTime() + ":" + candidate.sourceDeparture().orElseThrow()
                        + ":" + candidate.replayedDestinationArrival().orElseThrow()
                        + ":" + candidate.path().orElseThrow().arcIds())
                .sorted()
                .toList();
    }

    private static double integratedScore(EnvelopeProfile profile) {
        double score = 0;
        for (var segment : profile.segments()) {
            if (!segment.found()) {
                continue;
            }
            for (var piece : segment.candidate().scoreProfile().intervals()) {
                double left = Math.max(segment.interval().start(), piece.startMinute());
                double right = Math.min(segment.interval().end(), piece.endMinute());
                if (right > left) {
                    score += (right - left) * piece.value();
                }
            }
        }
        return score;
    }

    private static double midpoint(double left, double right) {
        return left + (right - left) / 2.0;
    }

    private static TDGraph graph(List<Integer> nodeIds, Edge... edges) {
        List<Node> nodes = nodeIds.stream().map(id -> new Node(id, 0, 0)).toList();
        return new TDGraph(nodes, List.of(edges));
    }

    private static Edge edge(
            int id,
            int source,
            int target,
            PiecewiseLinearFn travel,
            PiecewiseConstFn score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel, score);
    }

    private static PiecewiseLinearFn constantTravel(double start, double end, double value) {
        return travel(start, value, end, value);
    }

    private static PiecewiseLinearFn travel(double... points) {
        List<PiecewiseLinearFn.Breakpoint> breakpoints = new ArrayList<>();
        for (int i = 0; i < points.length; i += 2) {
            breakpoints.add(new PiecewiseLinearFn.Breakpoint(points[i], points[i + 1]));
        }
        return new PiecewiseLinearFn(breakpoints);
    }

    private static PiecewiseConstFn constantScore(double start, double end, int value) {
        return PiecewiseConstFn.constant(Domain.closed(start, end), value);
    }

    private static PiecewiseConstFn scores(PiecewiseConstFn.Interval... intervals) {
        return new PiecewiseConstFn(List.of(intervals));
    }

    private static SicspNativeGraph nativeGraph(TDGraph graph) {
        Map<Integer, SicspEdgeWeightFn> weights = new java.util.LinkedHashMap<>();
        for (Edge edge : graph.edges()) {
            weights.put(edge.arcId(), SicspEdgeWeightFn.constant(
                    edge.travelTimeFunction().domain(), edge.scoreFunction().maxValue()));
        }
        return new SicspNativeGraph(graph, weights);
    }

}
