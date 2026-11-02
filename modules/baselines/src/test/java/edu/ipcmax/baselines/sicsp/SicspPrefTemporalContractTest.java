package edu.ipcmax.baselines.sicsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import edu.ipcmax.baselines.TemporalArrivalProfiles;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.algorithms.SicspPrefExperimentAlgorithm;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.testoracle.ExactFraction;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;

/** Exact rational preference regressions; previous mixed-model failures remain permanent fixtures. */
class SicspPrefTemporalContractTest {
    private static final List<Integer> ROOT_PATH = List.of(0, 1);
    private static final double ROOT_DECIMAL = 6.666666666667;
    private static final MathContext REFERENCE_PRECISION = new MathContext(80, RoundingMode.HALF_EVEN);

    @Test
    void exactTemporalContractRepairsTheCounterexampleAndClaimsOnlyRetainedFrontier() {
        var graph = new TinyGraphBuilder().node(1).node(2).edge(1, 2, 1).build();
        var result = new SicspPrefExperimentAlgorithm().run(graph,
                new QuerySpec(1, 2, 0, 5, 2, 1), config(), new ExperimentInstrumentation());

        assertEquals(ExactnessScope.RETAINED_FRONTIER, result.exactnessScope());
        assertFalse(result.completeProfile());
        assertFalse(result.scalars().containsKey("known_temporal_issue"));
        assertEquals(true, result.scalars().get("temporal_correctness_verified"));
        assertEquals("EXACT_RATIONAL_PREFERENCE_PROFILE-v1",
                result.scalars().get("numerical_contract"));
        assertEquals("REPAIRED_EXACT_PROFILE", result.scalars().get("numerical_repair_status"));
        assertTrue(result.profile() == null, "the runner must not evaluate a rounded compatibility profile");
        assertNotNull(result.scalars().get("objective_result"));
    }

    @Test
    void fractionalRootSeparatesRationalDecimalReplayAndComposedProfile() {
        TDGraph graph = rootGraph();
        ExactFraction root = ExactFraction.of(20).divide(ExactFraction.of(3));
        RationalReplay atRoot = rationalReplay(graph, ROOT_PATH, root);
        assertEquals(ExactFraction.of(20), atRoot.entries().get(1));
        assertEquals(ExactFraction.of(60), atRoot.arrival());
        assertEquals(10, atRoot.score());

        RationalReplay exactDecimal = rationalReplay(graph, ROOT_PATH, fraction(ROOT_DECIMAL));
        assertEquals(ExactFraction.fromDouble(20.0000000000005), exactDecimal.entries().get(1));
        assertEquals(ExactFraction.fromDouble(60.0000000000005), exactDecimal.arrival());
        DecimalReplay legacy = decimalReplay(graph, ROOT_PATH, decimal(ROOT_DECIMAL));
        assertEquals(decimal(20.000000000001), legacy.entries().get(1));
        assertEquals(decimal(60.000000000001), legacy.arrival());
        assertEquals(10, legacy.score());

        CandidateProfile full = profile(graph, ROOT_PATH, Domain.closed(0, 20), 80);
        assertEquals(60.0, full.arrivalProfile().valueAt(ROOT_DECIMAL));
        assertNotEquals(legacy.arrival().doubleValue(), full.arrivalProfile().valueAt(ROOT_DECIMAL));

        // Existing rational arrival API is a separate arithmetic model, never
        // mixed into the legacy profile used by SICSP-Pref.
        ExactArrivalProfile rational = exactProfile(graph, ROOT_PATH, Domain.closed(0, 20));
        assertEquals(ExactTime.of(60), rational.valueAtExact(ExactTime.of(20).divide(ExactTime.of(3))));
        assertEquals(ExactTime.parseDecimal("60.0000000000005"), rational.valueAt(ROOT_DECIMAL));
    }

    @Test
    void independentReplayAndEventValuesAgreeBeforeAtAndAfterTheScoreRoot() {
        TDGraph graph = rootGraph();
        CandidateProfile profile = profile(graph, ROOT_PATH, Domain.closed(0, 20), 80);
        int[] scores = {2, 10, 10};
        int index = 0;
        for (double departure : List.of(6.666666666666, ROOT_DECIMAL, 6.666666666668)) {
            DecimalReplay expected = decimalReplay(graph, ROOT_PATH, decimal(departure));
            assertEquals(scores[index++], expected.score());
            assertEquals(expected.score(), profile.scoreProfile().valueAt(departure));
            assertEquals(expected.arrival().doubleValue(),
                    SicspRouteReplay.replay(zeroWeightGraph(graph), 1, 3, departure,
                            new Path(ROOT_PATH)).arrivalTime());
        }
        SicspPrefResult result = new SicspPreferenceEngine(graph).query(
                new SicspPrefQuery(1, 3, Domain.closed(0, 20), 80));
        var exactProfile = result.exactCandidateProfiles().get(new Path(ROOT_PATH));
        assertNotNull(exactProfile);
        assertEquals(ExactTime.parseDecimal("60.0000000000005"),
                exactProfile.arrival().valueAt(ROOT_DECIMAL));
        assertEquals(ExactTime.of(20).divide(ExactTime.of(3)), rootTime());
        assertEquals(10, exactProfile.score().valueAt(
                ExactTime.fromCanonicalDouble(ROOT_DECIMAL)).intValueExact());
        List<SicspPrefBreakpointCandidate> events = new ArrayList<>(result.rawOPoints());
        events.addAll(result.rawWPoints());
        events.addAll(result.candidateGeneration().arrivalDiagnostics());
        events.addAll(result.candidateGeneration().preferenceDiagnostics());
        long compared = 0;
        for (var event : events) {
            if (event.sourceDeparture().isPresent() && event.path().isPresent()
                    && event.replayedDestinationArrival().isPresent()
                    && event.sourceDeparture().getAsDouble() >= 0
                    && event.sourceDeparture().getAsDouble() <= 20) {
                DecimalReplay expected = decimalReplay(graph, event.path().orElseThrow().arcIds(),
                        decimal(event.sourceDeparture().getAsDouble()));
                assertEquals(expected.arrival().doubleValue(), event.replayedDestinationArrival().getAsDouble());
                assertEquals(expected.score(), event.replayedPreferenceScore().orElseThrow());
                compared++;
            }
        }
        assertTrue(compared > 0);
    }

    @Test
    void originalRootBudgetEqualityAndAdjacentTicksAreRecordedWithoutTolerance() {
        TDGraph graph = rootGraph();
        DecimalReplay replay = decimalReplay(graph, ROOT_PATH, decimal(ROOT_DECIMAL));
        BigDecimal travel = replay.arrival().subtract(decimal(ROOT_DECIMAL));
        assertEquals(decimal(53.333333333334), travel);
        for (double budget : List.of(53.333333333333, 53.333333333334, 53.333333333335)) {
            boolean expectedFeasible = travel.compareTo(decimal(budget)) <= 0;
            var candidate = ExactPathProfileBuilder.budgetContext(graph, Domain.closed(0, 20),
                    budget, () -> false).replay(ROOT_PATH, 1, 3);
            assertEquals(expectedFeasible, candidate.isPresent() && candidate.orElseThrow().domain().contains(ROOT_DECIMAL));
        }
    }

    @Test
    void fullProfileRestrictionAndDirectSingletonReplayDisagree() {
        TDGraph graph = rootGraph();
        CandidateProfile full = profile(graph, ROOT_PATH, Domain.closed(0, 20), 80);
        CandidateProfile singleton = profile(graph, ROOT_PATH, Domain.closed(ROOT_DECIMAL, ROOT_DECIMAL), 80);
        assertEquals(60.0, full.arrivalProfile().restrict(Domain.closed(ROOT_DECIMAL, ROOT_DECIMAL))
                .valueAt(ROOT_DECIMAL));
        assertEquals(60.000000000001, singleton.arrivalProfile().valueAt(ROOT_DECIMAL));
        assertEquals(10, singleton.scoreProfile().valueAt(ROOT_DECIMAL));
        assertNotEquals(full.arrivalProfile().valueAt(ROOT_DECIMAL), singleton.arrivalProfile().valueAt(ROOT_DECIMAL));

        var exactFull = ExactPreferenceReplay.replay(graph, 1, 3, new Path(ROOT_PATH),
                Domain.closed(0, 20), 80).orElseThrow();
        var exactSingleton = ExactPreferenceReplay.replay(graph, 1, 3, new Path(ROOT_PATH),
                Domain.closed(ROOT_DECIMAL, ROOT_DECIMAL), 80).orElseThrow();
        assertEquals(ExactTime.parseDecimal("60.0000000000005"), exactFull.arrival().valueAt(ROOT_DECIMAL));
        assertEquals(exactFull.arrival().valueAt(ROOT_DECIMAL), exactSingleton.arrival().valueAt(ROOT_DECIMAL));
        assertEquals(exactFull.score().valueAt(ExactTime.fromCanonicalDouble(ROOT_DECIMAL)),
                exactSingleton.score().valueAt(ExactTime.fromCanonicalDouble(ROOT_DECIMAL)));
    }

    @Test
    void roundedBudgetRootIncludesAProvenInfeasibleDeparture() {
        TDGraph graph = graph(List.of(edge(0, 1, 2,
                travel(0, 1, 10, 31, 100, 31), score(0, 100, 7))));
        double budget = 21;
        DecimalReplay replay = decimalReplay(graph, List.of(0), decimal(ROOT_DECIMAL));
        assertEquals(decimal(21.000000000001), replay.arrival().subtract(decimal(ROOT_DECIMAL)));
        assertTrue(replay.arrival().subtract(decimal(ROOT_DECIMAL)).compareTo(decimal(budget)) > 0);

        SicspPrefResult result = new SicspPreferenceEngine(graph).query(
                new SicspPrefQuery(1, 2, Domain.closed(0, 9), budget));
        var exactAtDecimal = result.exactEnvelope().cellAt(ExactTime.fromCanonicalDouble(ROOT_DECIMAL)).orElseThrow();
        assertFalse(exactAtDecimal.found());
        var exactAtRoot = result.exactEnvelope().cellAt(rootTime()).orElseThrow();
        assertTrue(exactAtRoot.found());
        assertEquals(ExactTime.of(21), exactAtRoot.travelTimeAt(rootTime()));
        assertTrue(result.envelope().segmentAt(ROOT_DECIMAL).found());
        var oracle = TinyContinuousEnvelopeOracle.solveCells(graph, new QuerySpec(1, 2, 0, 9, budget, 1));
        assertTrue(oracle.stream().filter(cell -> contains(cell.start(), cell.end(),
                cell.startInclusive(), cell.endInclusive(), fraction(ROOT_DECIMAL)))
                .allMatch(cell -> cell.path().isEmpty()));
        assertTrue(oracle.stream().anyMatch(cell -> cell.start().equals(ExactFraction.of(20).divide(ExactFraction.of(3)))));
    }

    @Test
    void earlyRoundedScoreStepChangesTheWinningRouteInRawAndRetainedRegistries() {
        TDGraph graph = wrongWinnerGraph();
        double departure = 6.666666666665;
        SicspPrefResult result = new SicspPreferenceEngine(graph).query(
                new SicspPrefQuery(1, 4, Domain.closed(0, 19), 80));
        DecimalReplay chain = decimalReplay(graph, List.of(0, 1, 2), decimal(departure));
        assertEquals(decimal(59.999999999997), chain.entries().get(2));
        assertEquals(10, chain.score());
        assertEquals(15, decimalReplay(graph, List.of(3), decimal(departure)).score());
        assertEquals(List.of(3), bestLegacyAt(graph,
                SicspSmallGraphOracle.enumerateSimplePaths(graph, 1, 4), departure, 80));
        assertEquals(List.of(3), bestLegacyAt(graph, result.simpleCandidatePaths(), departure, 80));
        assertEquals(List.of(3), bestLegacyAt(graph, result.retainedCandidatePaths(), departure, 80));
        var oracle = TinyContinuousEnvelopeOracle.solveCells(graph, new QuerySpec(1, 4, 0, 19, 80, 1));
        assertEquals(List.of(3), oracle.stream().filter(cell -> contains(cell.start(), cell.end(),
                cell.startInclusive(), cell.endInclusive(), fraction(departure)))
                .findFirst().orElseThrow().path().orElseThrow());
        ExactFraction switchingPoint = ExactFraction.of(20).divide(ExactFraction.of(3));
        assertEquals(List.of(0, 1, 2), oracle.stream().filter(cell -> contains(cell.start(), cell.end(),
                cell.startInclusive(), cell.endInclusive(), switchingPoint))
                .findFirst().orElseThrow().path().orElseThrow());
        assertEquals(List.of(0, 1, 2), bestLegacyAt(graph,
                SicspSmallGraphOracle.enumerateSimplePaths(graph, 1, 4), 6.666666666668, 80));

        var reported = result.envelope().segmentAt(departure);
        assertEquals(List.of(0, 1, 2), reported.path().arcIds());
        assertEquals(20, reported.candidate().scoreProfile().valueAt(departure));
        assertNotEquals(chain.score(), reported.candidate().scoreProfile().valueAt(departure));
        var repaired = result.exactEnvelope().cellAt(ExactTime.fromCanonicalDouble(departure)).orElseThrow();
        assertTrue(repaired.found());
        assertEquals(List.of(3), repaired.path().arcIds());
        assertEquals(15, repaired.score().intValueExact());
        var exactEvent = result.exactEnvelope().cellAt(rootTime()).orElseThrow();
        assertEquals(List.of(0, 1, 2), exactEvent.path().arcIds());
        assertEquals(20, exactEvent.score().intValueExact());
    }

    @Test
    void exactScoreBoundaryAndMultipleCrossingsAgreeOnRepresentableEvents() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 2, travel(0, 2, 100, 2), score(0, 4, 1, 4, 8, 3, 8, 100, 2)),
                edge(1, 2, 3, travel(0, 1, 100, 1), score(0, 5, 0, 5, 9, 7, 9, 100, 2)),
                edge(2, 3, 4, travel(0, 1, 100, 1), score(0, 8, 2, 8, 12, 8, 12, 100, 0))));
        SicspPrefResult result = new SicspPreferenceEngine(graph).query(
                new SicspPrefQuery(1, 4, Domain.closed(0, 10), 4));
        for (double departure : List.of(0.0, 2.0, 3.0, 4.0, 5.0, 7.0, 8.0, 9.0, 10.0)) {
            DecimalReplay reference = decimalReplay(graph, List.of(0, 1, 2), decimal(departure));
            var cell = result.envelope().segmentAt(departure);
            assertTrue(cell.found());
            assertEquals(reference.score(), cell.candidate().scoreProfile().valueAt(departure));
            assertEquals(reference.arrival().doubleValue(), cell.candidate().arrivalProfile().valueAt(departure));
        }
    }

    @Test
    void plateauAndMultipleCompositionGroupingsHaveASeparateExactReference() {
        TDGraph graph = graph(List.of(
                edge(0, 1, 2, travel(0, 10, 10, 0, 100, 0), score(0, 100, 1)),
                edge(1, 2, 3, travel(0, 2, 100, 2), score(0, 10, 0, 10, 100, 9)),
                edge(2, 3, 4, travel(0, 3, 100, 3), score(0, 100, 2))));
        List<Integer> path = List.of(0, 1, 2);
        CandidateProfile full = profile(graph, path, Domain.closed(0, 10), 15);
        var a = ExactArrivalProfile.edgeArrival(graph.edges().get(0));
        var b = ExactArrivalProfile.edgeArrival(graph.edges().get(1));
        var c = ExactArrivalProfile.edgeArrival(graph.edges().get(2));
        var left = a.composeAfter(b).composeAfter(c).restrict(Domain.closed(0, 10));
        var right = a.composeAfter(b.composeAfter(c)).restrict(Domain.closed(0, 10));
        assertEquals(left.segments(), right.segments());
        var legacyA = TemporalArrivalProfiles.edgeArrivalProfile(graph.edges().get(0));
        var legacyB = TemporalArrivalProfiles.edgeArrivalProfile(graph.edges().get(1));
        var legacyC = TemporalArrivalProfiles.edgeArrivalProfile(graph.edges().get(2));
        var legacyLeft = legacyA.compose(legacyB, "ab").compose(legacyC, "abc");
        var legacyRight = legacyA.compose(legacyB.compose(legacyC, "bc"), "abc");
        for (double departure : List.of(0.0, 5.0, 10.0)) {
            assertEquals(decimal(15), decimalReplay(graph, path, decimal(departure)).arrival());
            assertEquals(15, full.arrivalProfile().valueAt(departure));
            assertEquals(12, full.scoreProfile().valueAt(departure));
            assertEquals(left.valueAt(departure), right.valueAt(departure));
            assertEquals(legacyLeft.valueAt(departure), legacyRight.valueAt(departure));
        }
    }

    private record DecimalReplay(BigDecimal arrival, int score, List<BigDecimal> entries) { }
    private record RationalReplay(ExactFraction arrival, int score, List<ExactFraction> entries) { }

    /** Reads raw edge knots and applies independent decimal HALF_EVEN rounding per operation. */
    private static DecimalReplay decimalReplay(TDGraph graph, List<Integer> path, BigDecimal departure) {
        BigDecimal now = canonical(departure);
        int score = 0;
        List<BigDecimal> entries = new ArrayList<>();
        for (int arc : path) {
            Edge edge = graph.edges().get(arc);
            entries.add(now);
            score += independentScore(edge.scoreFunction(), ExactFraction.fromDouble(now.doubleValue()));
            var knots = edge.travelTimeFunction().breakpoints();
            BigDecimal travel = null;
            for (int index = 1; index < knots.size(); index++) {
                BigDecimal leftTime = decimal(knots.get(index - 1).minute());
                BigDecimal rightTime = decimal(knots.get(index).minute());
                if (now.compareTo(leftTime) >= 0 && now.compareTo(rightTime) <= 0) {
                    BigDecimal leftValue = decimal(knots.get(index - 1).value());
                    BigDecimal difference = decimal(knots.get(index).value()).subtract(leftValue);
                    travel = leftValue.add(now.subtract(leftTime).multiply(difference)
                            .divide(rightTime.subtract(leftTime), REFERENCE_PRECISION));
                    break;
                }
            }
            if (travel == null) throw new AssertionError("reference edge support missing");
            now = canonical(now.add(canonical(travel)));
        }
        return new DecimalReplay(now, score, List.copyOf(entries));
    }

    /** Separate real-time rational semantics: no intermediate quantization. */
    private static RationalReplay rationalReplay(TDGraph graph, List<Integer> path, ExactFraction departure) {
        ExactFraction now = departure;
        int score = 0;
        List<ExactFraction> entries = new ArrayList<>();
        for (int arc : path) {
            Edge edge = graph.edges().get(arc);
            entries.add(now);
            score += independentScore(edge.scoreFunction(), now);
            var knots = edge.travelTimeFunction().breakpoints();
            ExactFraction travel = null;
            for (int index = 1; index < knots.size(); index++) {
                ExactFraction x1 = fraction(knots.get(index - 1).minute());
                ExactFraction x2 = fraction(knots.get(index).minute());
                if (now.compareTo(x1) >= 0 && now.compareTo(x2) <= 0) {
                    ExactFraction y1 = fraction(knots.get(index - 1).value());
                    travel = y1.add(now.subtract(x1).multiply(fraction(knots.get(index).value())
                            .subtract(y1)).divide(x2.subtract(x1)));
                    break;
                }
            }
            if (travel == null) throw new AssertionError("rational reference support missing");
            now = now.add(travel);
        }
        return new RationalReplay(now, score, List.copyOf(entries));
    }

    private static int independentScore(PiecewiseConstFn function, ExactFraction entry) {
        var intervals = function.intervals();
        for (int index = 0; index < intervals.size(); index++) {
            var interval = intervals.get(index);
            ExactFraction start = fraction(interval.startMinute());
            ExactFraction end = fraction(interval.endMinute());
            boolean nextOwnsEnd = index + 1 < intervals.size()
                    && fraction(intervals.get(index + 1).startMinute()).equals(end);
            if (contains(start, end, true, !nextOwnsEnd, entry)) return interval.value();
        }
        throw new AssertionError("score support missing");
    }

    private static List<Integer> bestLegacyAt(TDGraph graph, List<Path> paths, double departure, double budget) {
        return paths.stream().filter(path -> decimalReplay(graph, path.arcIds(), decimal(departure)).arrival()
                        .subtract(decimal(departure)).compareTo(decimal(budget)) <= 0)
                .sorted((left, right) -> {
                    DecimalReplay l = decimalReplay(graph, left.arcIds(), decimal(departure));
                    DecimalReplay r = decimalReplay(graph, right.arcIds(), decimal(departure));
                    int compare = Integer.compare(r.score(), l.score());
                    if (compare == 0) compare = l.arrival().compareTo(r.arrival());
                    if (compare == 0) compare = Integer.compare(left.arcIds().size(), right.arcIds().size());
                    if (compare == 0) {
                        for (int i = 0; i < left.arcIds().size() && compare == 0; i++)
                            compare = Integer.compare(left.arcIds().get(i), right.arcIds().get(i));
                    }
                    return compare;
                }).findFirst().orElseThrow().arcIds();
    }

    private static CandidateProfile profile(TDGraph graph, List<Integer> path, Domain domain, double budget) {
        return ExactPathProfileBuilder.budgetContext(graph, domain, budget, () -> false)
                .replay(path, 1, graph.edges().get(path.getLast()).target()).orElseThrow();
    }

    private static ExactArrivalProfile exactProfile(TDGraph graph, List<Integer> path, Domain domain) {
        ExactArrivalProfile result = ExactArrivalProfile.identity(domain);
        for (int arc : path) result = result.composeAfter(ExactArrivalProfile.edgeArrival(graph.edges().get(arc)));
        return result;
    }

    private static SicspNativeGraph zeroWeightGraph(TDGraph graph) {
        return new SicspNativeGraph(graph, graph.edges().stream().collect(java.util.stream.Collectors.toMap(
                Edge::arcId, edge -> SicspEdgeWeightFn.constant(edge.travelTimeFunction().domain(), 0))));
    }

    private static TDGraph rootGraph() {
        return graph(List.of(
                edge(0, 1, 2, travel(0, 10, 40, 30, 100, 40), score(0, 100, 1)),
                edge(1, 2, 3, travel(0, 40, 50, 40, 80, 10, 100, 10), score(0, 20, 1, 20, 60, 9, 60, 100, 2))));
    }

    private static TDGraph wrongWinnerGraph() {
        return graph(List.of(
                edge(0, 1, 2, travel(0, 10, 40, 30, 100, 40), score(0, 100, 0)),
                edge(1, 2, 3, travel(0, 40, 50, 40, 80, 10, 100, 10), score(0, 100, 0)),
                edge(2, 3, 4, travel(0, 1, 100, 1), new PiecewiseConstFn(List.of(
                        new PiecewiseConstFn.Interval(0, 59.999999999998, 10),
                        new PiecewiseConstFn.Interval(59.999999999998, 100, 20)))),
                edge(3, 1, 4, travel(0, 70, 100, 70), score(0, 100, 15))));
    }

    private static TDGraph graph(List<Edge> edges) {
        TreeSet<Integer> nodes = new TreeSet<>();
        edges.forEach(edge -> { nodes.add(edge.source()); nodes.add(edge.target()); });
        return new TDGraph(nodes.stream().map(id -> new Node(id, id, 0)).toList(), edges);
    }

    private static Edge edge(int arc, int source, int target, PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(arc, source, target, 1, travel.minTravelTime(), travel, score);
    }

    private static PiecewiseLinearFn travel(double... values) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) points.add(new PiecewiseLinearFn.Breakpoint(values[i], values[i + 1]));
        return new PiecewiseLinearFn(points);
    }

    private static PiecewiseConstFn score(int... values) {
        List<PiecewiseConstFn.Interval> intervals = new ArrayList<>();
        for (int i = 0; i < values.length; i += 3) intervals.add(new PiecewiseConstFn.Interval(values[i], values[i + 1], values[i + 2]));
        return new PiecewiseConstFn(intervals);
    }

    private static boolean contains(ExactFraction start, ExactFraction end, boolean startInclusive,
            boolean endInclusive, ExactFraction value) {
        return (value.compareTo(start) > 0 || startInclusive && value.equals(start))
                && (value.compareTo(end) < 0 || endInclusive && value.equals(end));
    }

    private static BigDecimal decimal(double value) { return BigDecimal.valueOf(value).setScale(12, RoundingMode.UNNECESSARY); }
    private static BigDecimal canonical(BigDecimal value) { return value.setScale(12, RoundingMode.HALF_EVEN); }
    private static ExactFraction fraction(double value) { return ExactFraction.fromDouble(value); }

    private static ExactTime rootTime() { return ExactTime.of(20).divide(ExactTime.of(3)); }

    private static AlgorithmConfig config() {
        return new AlgorithmConfig("sicsp-pref", Ablation.NONE, 2, 4, 4, 1, 0, 0,
                10_000, 100_000, 100_000, 100_000, true, 42);
    }
}
