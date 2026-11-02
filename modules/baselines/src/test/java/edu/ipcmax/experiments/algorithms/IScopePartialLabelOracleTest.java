package edu.ipcmax.experiments.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.AlgorithmRegistry;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.ProfileSupport;
import edu.ipcmax.testoracle.ExactFraction;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle.TemporalCell;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Whole-cell, registry and pruning checks for the exact and approximate iSCOPE variants. */
class IScopePartialLabelOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aliasesPreserveBasicBehaviorAndOpt1MatchesTheIndependentGlobalOracle() {
        assertEquals("iscope", AlgorithmRegistry.create("iscope").id());
        assertEquals("iscope-basic", AlgorithmRegistry.create("iscope-basic").id());
        assertEquals("iscope-opt1", AlgorithmRegistry.create("iscope-opt1").id());
        assertEquals("iscope-optimized", AlgorithmRegistry.create("iscope-optimized").id());

        TDGraph graph = pruningAndScoreSwitchGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 3, 1);
        ExperimentInstrumentation basicInstrumentation = new ExperimentInstrumentation();
        ExperimentInstrumentation opt1Instrumentation = new ExperimentInstrumentation();
        AlgorithmResult historicalAlias = AlgorithmRegistry.create("iscope").run(
                graph, query, config("iscope", 100_000, 42, Map.of()), new ExperimentInstrumentation());
        AlgorithmResult basic = AlgorithmRegistry.create("iscope-basic").run(
                graph, query, config("iscope-basic", 100_000, 42, Map.of()), basicInstrumentation);
        AlgorithmResult opt1 = AlgorithmRegistry.create("iscope-opt1").run(
                graph, query, config("iscope-opt1", 100_000, 42, Map.of()), opt1Instrumentation);

        assertEquals("CONTINUOUS_PROFILE_EXHAUSTIVE_PATH_ADAPTATION-v1",
                historicalAlias.scalars().get("implementation_variant"));
        assertEquals(Boolean.TRUE, historicalAlias.scalars().get("historical_iscope_alias"));
        assertEquals(Boolean.FALSE, basic.scalars().get("historical_iscope_alias"));
        assertEquals(ExperimentStatus.CERTIFIED_COMPLETE, opt1.status());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, opt1.exactnessScope());
        assertEquals(routes(basic, "retained_route_registry"), routes(opt1, "retained_route_registry"));
        assertEquals(routes(historicalAlias, "retained_route_registry"), routes(basic, "retained_route_registry"));
        assertMatchesOracle(graph, query, exactProfile(opt1), TinyContinuousEnvelopeOracle.solveCells(graph, query));
        assertMatchesOracle(graph, query, exactProfile(basic), TinyContinuousEnvelopeOracle.solveCells(graph, query));

        assertTrue(number(opt1, "cells_safely_pruned") > 0);
        assertEquals(36L, number(basic, "dfs_expansions"));
        assertTrue(number(opt1, "edges_expanded") > 0);
        assertTrue(number(opt1, "partial_labels_created") > 0);
        assertTrue(number(opt1, "temporal_cells_split") > 0);
        assertTrue(number(opt1, "partial_function_operations") > 0);
        assertTrue(number(opt1, "peak_estimated_label_payload_bytes") > 0);
        assertTrue(number(opt1, "peak_heap_observed_bytes") > 0);
        assertTrue(((Map<?, ?>) opt1.scalars().get("objective_result")).containsKey("profile"));
        assertTrue(opt1Instrumentation.timings().containsKey("algorithm_search"));
        assertTrue(opt1Instrumentation.timings().containsKey("exact_candidate_replay_and_envelope"));
        assertEquals(Boolean.TRUE, opt1.scalars().get("source_label_initialized_before_forward_search"));
        assertEquals(0L, number(opt1, "reverse_dijkstra_preparation_invocations"));
        assertEquals(Boolean.TRUE, opt1.scalars().get("euclidean_speed_bound_used"));
        assertFalse(opt1.scalars().containsKey("lower_bound_edges_scanned"));
        if (Boolean.getBoolean("iscope.test.metrics")) {
            System.out.println("ISCOPE_TINY_METRICS basic_edges=" + number(basic, "dfs_expansions")
                    + " basic_search_ns=" + basicInstrumentation.timings().get("enumeration")
                    + " basic_finalization_ns=" + basicInstrumentation.timings().get("exact_objective_evaluation")
                    + " basic_total_ns=" + basicInstrumentation.timings().get("algorithm_total")
                    + " opt1_edges=" + number(opt1, "edges_expanded")
                    + " opt1_labels=" + number(opt1, "partial_labels_created")
                    + " opt1_pruned_cells=" + number(opt1, "cells_safely_pruned")
                    + " opt1_search_ns=" + opt1Instrumentation.timings().get("algorithm_search")
                    + " opt1_finalization_ns=" + opt1Instrumentation.timings().get("exact_candidate_replay_and_envelope")
                    + " opt1_total_ns=" + opt1.scalars().get("end_to_end_query_nanos")
                    + " opt1_estimated_live_label_bytes=" + opt1.scalars().get("peak_estimated_label_payload_bytes")
                    + " opt1_heap_bytes=" + opt1.scalars().get("peak_heap_observed_bytes"));
        }
    }

    @Test
    void exactBudgetEqualityFractionalRootsPlateausAndDisconnectedSupportArePreserved() {
        TDGraph graph = oneRouteBudgetRootGraph();
        QuerySpec query = new QuerySpec(1, 3, 0, 10, 3, 1);
        AlgorithmResult opt1 = runOpt1(graph, query);
        ExactPreferenceEnvelope envelope = exactProfile(opt1);
        assertMatchesOracle(graph, query, envelope, TinyContinuousEnvelopeOracle.solveCells(graph, query));

        ExactTime firstRoot = ExactTime.parseDecimal("2").divide(ExactTime.parseDecimal("3"));
        ExactTime secondRoot = ExactTime.parseDecimal("22").divide(ExactTime.parseDecimal("3"));
        assertTrue(envelope.cellAt(firstRoot).orElseThrow().found(), "budget equality must own first root");
        assertTrue(envelope.cellAt(secondRoot).orElseThrow().found(), "budget equality must own second root");
        assertFalse(envelope.cellAt(ExactTime.of(4)).orElseThrow().found(),
                "the middle of the exact support gap must remain NO_PATH");
        assertTrue(envelope.cells().stream().filter(ExactPreferenceEnvelope.Cell::found).count() >= 2,
                "the exact route support is disconnected");

        TDGraph fractional = fractionalRootAndFifoPlateauGraph();
        QuerySpec fractionalQuery = new QuerySpec(1, 3, 0, 20, 80, 1);
        ExactPreferenceEnvelope fractionalProfile = exactProfile(runOpt1(fractional, fractionalQuery));
        assertMatchesOracle(fractional, fractionalQuery, fractionalProfile,
                TinyContinuousEnvelopeOracle.solveCells(fractional, fractionalQuery));
        ExactTime fractionalRoot = ExactTime.parseDecimal("20").divide(ExactTime.parseDecimal("3"));
        assertTrue(fractionalProfile.cellAt(fractionalRoot).orElseThrow().found());
    }

    @Test
    void opt1HandlesNoRouteDirectedAsymmetryAndLongLooplessComposition() {
        TDGraph noRoute = graph(List.of(new Node(1, 0, 0), new Node(2, 1, 0), new Node(3, 2, 0)),
                List.of(edge(0, 1, 2, constantTravel(1), zeroScore())));
        QuerySpec noRouteQuery = new QuerySpec(1, 3, 0, 5, 10, 1);
        AlgorithmResult empty = runOpt1(noRoute, noRouteQuery);
        assertEquals(ExperimentStatus.NO_FEASIBLE_PATH, empty.status());
        assertEquals(ExactnessScope.GLOBAL_CERTIFIED, empty.exactnessScope());
        assertMatchesOracle(noRoute, noRouteQuery, exactProfile(empty),
                TinyContinuousEnvelopeOracle.solveCells(noRoute, noRouteQuery));

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (int node = 1; node <= 12; node++) nodes.add(new Node(node, node, 0));
        for (int node = 1; node < 12; node++) {
            int arc = edges.size();
            PiecewiseConstFn score = node % 2 == 0
                    ? score(0, 2, node, 2, 1440, node + 1) : zeroScore();
            edges.add(edge(arc, node, node + 1, constantTravel(0.1), score));
        }
        // A directed cycle exists in the input; a returned label may not revisit node 1.
        edges.add(edge(edges.size(), 3, 1, constantTravel(0.1), zeroScore()));
        TDGraph longGraph = graph(nodes, edges);
        QuerySpec longQuery = new QuerySpec(1, 12, 0, 5, 2, 1);
        AlgorithmResult longResult = runOpt1(longGraph, longQuery);
        assertEquals(1L, number(longResult, "completed_paths_evaluated"));
        assertEquals(11, routes(longResult, "retained_route_registry").getFirst().size());
        assertMatchesOracle(longGraph, longQuery, exactProfile(longResult),
                TinyContinuousEnvelopeOracle.solveCells(longGraph, longQuery));
    }

    @Test
    void opt1AndBasicAgreeOnASeededExhaustiveTinyDagCorpus() {
        Random random = new Random(0x4f505431L);
        for (int fixture = 0; fixture < 30; fixture++) {
            List<Node> nodes = new ArrayList<>();
            for (int node = 1; node <= 5; node++) nodes.add(new Node(node, node, 0));
            List<Edge> edges = new ArrayList<>();
            for (int source = 1; source < 5; source++) {
                for (int target = source + 1; target <= 5; target++) {
                    if (target != source + 1 && !random.nextBoolean()) continue;
                    int first = random.nextInt(11);
                    int second = random.nextInt(11);
                    edges.add(edge(edges.size(), source, target, constantTravel(1 + random.nextInt(4)),
                            score(0, 5, first, 5, 1440, second)));
                }
            }
            TDGraph graph = graph(nodes, edges);
            QuerySpec query = new QuerySpec(1, 5, 0, 10, 2 + random.nextInt(10), 1);
            AlgorithmResult basic = AlgorithmRegistry.create("iscope-basic").run(
                    graph, query, config("iscope-basic", 100_000, 2027, Map.of()), new ExperimentInstrumentation());
            AlgorithmResult opt1 = AlgorithmRegistry.create("iscope-opt1").run(
                    graph, query, config("iscope-opt1", 100_000, 2027, Map.of()), new ExperimentInstrumentation());
            assertEquals(ExactnessScope.GLOBAL_CERTIFIED, opt1.exactnessScope(), "fixture " + fixture);
            assertEquals(routes(basic, "retained_route_registry"),
                    routes(opt1, "retained_route_registry"), "route registry fixture " + fixture);
            List<TemporalCell> oracle = TinyContinuousEnvelopeOracle.solveCells(graph, query);
            assertMatchesOracle(graph, query, exactProfile(basic), oracle);
            assertMatchesOracle(graph, query, exactProfile(opt1), oracle);
        }
    }

    @Test
    void opt2IsSeededApproximateAndReportsOnlyTheExactRetainedRouteEnvelope() {
        TDGraph graph = opt2ClusteringGraph();
        QuerySpec query = new QuerySpec(1, 3, 0, 10, 20, 1);
        Map<String, String> options = Map.of("iscope_opt2_breakpoint_threshold_minutes", "2");
        AlgorithmResult first = AlgorithmRegistry.create("iscope-optimized").run(
                graph, query, config("iscope-optimized", 100_000, 721, options), new ExperimentInstrumentation());
        AlgorithmResult repeated = AlgorithmRegistry.create("iscope-optimized").run(
                graph, query, config("iscope-optimized", 100_000, 721, options), new ExperimentInstrumentation());
        Map<?, ?> objective = objective(first);
        List<List<Integer>> retained = routes(first, "retained_route_registry");

        assertEquals(ExperimentStatus.COMPLETED, first.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, first.exactnessScope());
        assertEquals("OPT2_APPROXIMATE_SEARCH_COMPLETED_NOT_CERTIFIED",
                objective.get("completion_status"));
        assertTrue(number(first, "approximate_breakpoints_merged") > 0);
        assertTrue(number(first, "approximate_roulette_draws") > 0);
        assertTrue(number(first, "approximate_search_function_pieces_after")
                < number(first, "approximate_search_function_pieces_before"));
        assertEquals(ExactTime.of(BigInteger.valueOf(1295), BigInteger.valueOf(719)).toString(),
                first.scalars().get("opt2_maximum_observed_arrival_error_minutes"));
        assertEquals("2", first.scalars().get("opt2_breakpoint_threshold_minutes"));
        assertEquals("UNIFORM_INTEGER_0_TO_100_INCLUSIVE", first.scalars().get("opt2_random_draw"));
        assertEquals(Boolean.FALSE, first.scalars().get("exact_final_replay_restores_removed_candidates"));
        assertEquals("exact-preference-envelope-v1", objective.get("exact_profile_schema"));
        assertMatchesOracle(graph, query, exactProfile(first),
                TinyContinuousEnvelopeOracle.solveCellsForPaths(graph, query, retained));
        assertEquals(ProfileSupport.resultChecksum(first), ProfileSupport.resultChecksum(repeated));
        assertEquals(routes(first, "emitted_route_registry"), routes(repeated, "emitted_route_registry"));
        assertEquals(first.scalars().get("random_seed"), repeated.scalars().get("random_seed"));

        assertEquals(BigInteger.ONE, ApproximatePartialFunctions.roulette(
                BigInteger.ONE, BigInteger.valueOf(3), 25));
        assertEquals(BigInteger.valueOf(3), ApproximatePartialFunctions.roulette(
                BigInteger.ONE, BigInteger.valueOf(3), 26));
        assertEquals(BigInteger.ZERO, ApproximatePartialFunctions.roulette(BigInteger.ZERO, BigInteger.ZERO, 50));
        assertEquals(BigInteger.ZERO, ApproximatePartialFunctions.roulette(BigInteger.ZERO, BigInteger.ZERO, 51));
    }

    @Test
    void opt2DefaultsToAuthorConfirmedFiveMinuteClusterSpan() {
        TDGraph graph = opt2ClusteringGraph();
        QuerySpec query = new QuerySpec(1, 3, 0, 10, 20, 1);
        AlgorithmResult result = AlgorithmRegistry.create("iscope-optimized").run(
                graph, query,
                config("iscope-optimized", 100_000, 721, Map.of()),
                new ExperimentInstrumentation());

        assertEquals("5", result.scalars().get("opt2_breakpoint_threshold_minutes"));
        assertEquals(ExperimentStatus.COMPLETED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
    }

    @Test
    void fiveSecondVariantCapReturnsOnlyWholeExactReplayCommits() {
        AtomicLong clock = new AtomicLong();
        IScopePartialLabelAlgorithm algorithm = new IScopePartialLabelAlgorithm(
                "iscope-opt1", () -> clock.getAndAdd(10));
        TDGraph graph = pruningAndScoreSwitchGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 3, 1);
        AlgorithmResult result = algorithm.run(graph, query,
                config("iscope-opt1", 100_000, 42, Map.of(), 300), new ExperimentInstrumentation());

        assertEquals(ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED, result.status());
        assertEquals(ExactnessScope.NOT_CERTIFIED, result.exactnessScope());
        assertEquals(Boolean.TRUE, result.scalars().get("deadline_cap_triggered"));
        assertEquals(0L, result.scalars().get("query_time_cap_protocol_seconds"));
        assertEquals(300L, result.scalars().get("query_deadline_nanos"));
        List<List<Integer>> retained = routes(result, "retained_route_registry");
        assertMatchesOracle(graph, query, exactProfile(result),
                TinyContinuousEnvelopeOracle.solveCellsForPaths(graph, query, retained));
    }

    @Test
    void sourceLabelAndForwardDfsStartWithoutReverseSearch() {
        TDGraph graph = pruningAndScoreSwitchGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 3, 1);
        ExperimentInstrumentation instrumentation = new ExperimentInstrumentation();
        IScopePartialLabelAlgorithm algorithm = new IScopePartialLabelAlgorithm("iscope-opt1");
        AlgorithmConfig config = config("iscope-opt1", 100_000, 42, Map.of());
        algorithm.prepare(graph, config);
        AlgorithmResult result = algorithm.run(graph, query, config, instrumentation);

        assertTrue(number(result, "geometric_speed_bound_preparation_nanos") >= 0);
        assertEquals(number(result, "geometric_speed_bound_preparation_nanos"),
                instrumentation.timings().get("geometric_speed_bound_preprocessing"));
        assertEquals(Boolean.TRUE, result.scalars().get("source_label_initialized_before_forward_search"));
        assertEquals(0L, number(result, "reverse_dijkstra_preparation_invocations"));
        assertTrue(number(result, "dfs_states_visited") > 0);
        assertTrue(number(result, "dfs_time_to_first_edge_expansion_nanos") >= 0);
        assertTrue(number(result, "dfs_time_to_first_destination_nanos") >= 0);
        assertTrue(number(result, "maximum_path_depth") > 0);
        assertTrue(number(result, "maximum_unique_vertices_on_path") > 0);
        assertTrue(number(result, "partial_labels_with_direct_destination_edge") > 0);
        assertTrue(number(result, "expanded_edges_to_destination") > 0);
        assertTrue(number(result, "edges_expanded") > 0);
        assertTrue(number(result, "partial_functions_constructed") > 0);
        assertTrue(number(result, "function_composition_operation_count") > 0);
        assertTrue(number(result, "temporal_function_composition_nanos") >= 0);
        assertTrue(number(result, "opt1_pruned_temporal_intervals") > 0);
        assertFalse(result.scalars().containsKey("lower_bound_edges_scanned"));
    }

    @Test
    void cancelledExactReplayDoesNotCommitAnIncompleteRouteOrEnvelope() {
        TDGraph graph = pruningAndScoreSwitchGraph();
        QuerySpec query = new QuerySpec(1, 4, 0, 10, 3, 1);
        AtomicInteger checks = new AtomicInteger();
        ExactPreferenceObjective.Evaluation evaluation = ExactPreferenceObjective.evaluateIncrementally(
                graph, query, List.of(new Path(List.of(0, 1))), 0,
                "TIME_CAPPED_NOT_CERTIFIED", "NOT_CERTIFIED", "cancel-test",
                () -> checks.incrementAndGet() > 8);

        assertFalse(evaluation.evaluationComplete());
        assertTrue(evaluation.routeProfiles().isEmpty());
        assertFalse(evaluation.profile().hasFeasibleCell());
        assertEquals(0, evaluation.objectiveResult().get("retained_route_registry") instanceof List<?> list
                ? list.size() : -1);
    }

    @Test
    void configAndSeedMetadataNameTheApproximationVariant() {
        AlgorithmConfig config = config("iscope-optimized", 17, 1234,
                Map.of("iscope_opt2_breakpoint_threshold_minutes", "1.25"));
        IScopePartialLabelAlgorithm algorithm = new IScopePartialLabelAlgorithm("iscope-optimized");
        assertEquals("iscope-optimized", algorithm.id());
        assertEquals("1.25", config.objectiveOptions().get("iscope_opt2_breakpoint_threshold_minutes"));
        assertNotEquals("iscope", algorithm.id());
    }

    private static AlgorithmResult runOpt1(TDGraph graph, QuerySpec query) {
        return AlgorithmRegistry.create("iscope-opt1").run(
                graph, query, config("iscope-opt1", 100_000, 42, Map.of()), new ExperimentInstrumentation());
    }

    private static AlgorithmConfig config(String id, long paths, long seed, Map<String, String> options) {
        return config(id, paths, seed, options, 20_000_000_000L);
    }

    private static AlgorithmConfig config(
            String id, long paths, long seed, Map<String, String> options, long deadlineNanos) {
        return new AlgorithmConfig(id, Ablation.NONE, PaceEngineMode.SCALABLE,
                0, 1, 1, 1, 1, 1_000, 1_000_000, 1,
                0, 0, paths, 100_000, 100_000, 100_000,
                true, seed, deadlineNanos, options);
    }

    private static ExactPreferenceEnvelope exactProfile(AlgorithmResult result) {
        Object profile = objective(result).get("profile");
        JsonNode json = profile instanceof JsonNode node ? node : JSON.valueToTree(profile);
        return ExactPreferenceProfileCodec.decode(json.toString());
    }

    private static Map<?, ?> objective(AlgorithmResult result) {
        return (Map<?, ?>) result.scalars().get("objective_result");
    }

    @SuppressWarnings("unchecked")
    private static List<List<Integer>> routes(AlgorithmResult result, String registryName) {
        Object values = objective(result).get(registryName);
        List<List<Integer>> routes = new ArrayList<>((List<List<Integer>>) values);
        routes.sort(Comparator.comparing(List::toString));
        return routes;
    }

    private static long number(AlgorithmResult result, String key) {
        return ((Number) result.scalars().get(key)).longValue();
    }

    private static void assertMatchesOracle(
            TDGraph graph, QuerySpec query, ExactPreferenceEnvelope actual,
            List<TemporalCell> expected) {
        TreeSet<ExactFraction> cuts = new TreeSet<>();
        for (TemporalCell cell : expected) {
            cuts.add(cell.start());
            cuts.add(cell.end());
        }
        actual.cells().forEach(cell -> {
            cuts.add(fraction(cell.interval().start()));
            cuts.add(fraction(cell.interval().end()));
        });
        List<ExactFraction> ordered = List.copyOf(cuts);
        for (int i = 0; i < ordered.size(); i++) {
            compareAt(actual, expected, ordered.get(i), true);
            if (i + 1 < ordered.size()) {
                ExactFraction left = ordered.get(i);
                ExactFraction right = ordered.get(i + 1);
                if (left.compareTo(right) < 0) {
                    compareAt(actual, expected, left.add(right).divide(ExactFraction.of(2)), false);
                }
            }
        }
        assertEquals(ExactFraction.of(query.departureStart()), ordered.getFirst());
        assertEquals(ExactFraction.of(query.departureEnd()), ordered.getLast());
    }

    private static void compareAt(
            ExactPreferenceEnvelope actual, List<TemporalCell> expected,
            ExactFraction departure, boolean point) {
        TemporalCell oracle = expected.stream().filter(cell -> contains(cell, departure))
                .findFirst().orElseThrow(() -> new AssertionError("oracle has no cell at " + departure));
        ExactPreferenceEnvelope.Cell cell = actual.cellAt(exactTime(departure)).orElseThrow(
                () -> new AssertionError("exact envelope has no cell at " + departure));
        assertEquals(oracle.path().isPresent(), cell.found(), "feasibility at " + departure);
        if (!cell.found()) return;
        assertEquals(oracle.path().orElseThrow(), cell.path().arcIds(), "route at " + departure);
        var expectedValue = oracle.value().orElseThrow();
        assertEquals(BigInteger.valueOf(expectedValue.score()), cell.score(), "score at " + departure);
        if (point) {
            assertEquals(expectedValue.arrivalSlope().multiply(departure)
                    .add(expectedValue.arrivalIntercept()), fraction(cell.arrivalAt(exactTime(departure))),
                    "arrival at owned endpoint " + departure);
        } else {
            assertEquals(expectedValue.arrivalSlope(), fraction(cell.arrivalSlope()), "arrival slope at " + departure);
            assertEquals(expectedValue.arrivalIntercept(), fraction(cell.arrivalIntercept()), "arrival intercept at " + departure);
        }
    }

    private static boolean contains(TemporalCell cell, ExactFraction time) {
        return (time.compareTo(cell.start()) > 0 || cell.startInclusive() && time.equals(cell.start()))
                && (time.compareTo(cell.end()) < 0 || cell.endInclusive() && time.equals(cell.end()));
    }

    private static ExactFraction fraction(ExactTime time) {
        String[] parts = time.toString().split("/", -1);
        return parts.length == 1
                ? ExactFraction.of(new BigInteger(parts[0]), BigInteger.ONE)
                : ExactFraction.of(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    private static ExactTime exactTime(ExactFraction value) {
        return ExactTime.of(value.numerator(), value.denominator());
    }

    private static TDGraph pruningAndScoreSwitchGraph() {
        List<Node> nodes = new ArrayList<>();
        for (int id = 1; id <= 15; id++) nodes.add(new Node(id, id, 0));
        List<Edge> edges = new ArrayList<>();
        edges.add(edge(0, 1, 2, travel(0, 1, 2, 4, 10, 1, 1440, 1), score(0, 3, 5, 3, 6, 9, 6, 1440, 2)));
        edges.add(edge(1, 2, 4, constantTravel(1), zeroScore()));
        edges.add(edge(2, 1, 3, constantTravel(1), score(0, 5, 1, 5, 1440, 8)));
        edges.add(edge(3, 3, 4, constantTravel(1), zeroScore()));
        edges.add(edge(4, 1, 5, constantTravel(1), zeroScore()));
        int arcId = 5;
        for (int target = 6; target <= 10; target++) edges.add(edge(arcId++, 5, target, constantTravel(1), zeroScore()));
        for (int source = 6; source <= 10; source++) {
            for (int target = 11; target <= 15; target++) edges.add(edge(arcId++, source, target, constantTravel(1), zeroScore()));
        }
        edges.add(edge(arcId, 2, 1, constantTravel(1), zeroScore()));
        return graph(nodes, edges);
    }

    private static TDGraph oneRouteBudgetRootGraph() {
        return graph(List.of(new Node(1, 0, 0), new Node(2, 1, 0), new Node(3, 2, 0)), List.of(
                edge(0, 1, 2, travel(0, 1, 2, 4, 10, 1, 1440, 1), zeroScore()),
                edge(1, 2, 3, constantTravel(1), zeroScore())));
    }

    private static TDGraph fractionalRootAndFifoPlateauGraph() {
        return graph(List.of(new Node(1, 0, 0), new Node(2, 1, 0), new Node(3, 2, 0)), List.of(
                edge(0, 1, 2, travel(0, 10, 40, 30, 100, 40), zeroScore()),
                edge(1, 2, 3, travel(0, 40, 50, 40, 80, 10, 100, 10),
                        score(0, 20, 1, 20, 60, 9, 60, 100, 2))));
    }

    private static TDGraph opt2ClusteringGraph() {
        return graph(List.of(new Node(1, 0, 0), new Node(2, 1, 0), new Node(3, 2, 0)), List.of(
                edge(0, 1, 2, travel(0, 1, 1, 3, 2, 3, 1440, 1), score(0, 1, 1, 1, 2, 9, 2, 1440, 2)),
                edge(1, 2, 3, constantTravel(1), zeroScore())));
    }

    private static TDGraph graph(List<Node> nodes, List<Edge> edges) {
        return new TDGraph(nodes, edges);
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel, score);
    }

    private static PiecewiseLinearFn constantTravel(double value) {
        return travel(0, value, 1440, value);
    }

    private static PiecewiseLinearFn travel(double... values) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) points.add(new PiecewiseLinearFn.Breakpoint(values[i], values[i + 1]));
        return new PiecewiseLinearFn(points);
    }

    private static PiecewiseConstFn zeroScore() {
        return score(0, 1440, 0);
    }

    private static PiecewiseConstFn score(int... values) {
        List<PiecewiseConstFn.Interval> intervals = new ArrayList<>();
        for (int i = 0; i < values.length; i += 3) {
            intervals.add(new PiecewiseConstFn.Interval(values[i], values[i + 1], values[i + 2]));
        }
        return new PiecewiseConstFn(intervals);
    }
}
