package edu.ipcmax.experiments.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.testoracle.ExactFraction;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle;
import edu.ipcmax.testoracle.TinyContinuousEnvelopeOracle.TemporalCell;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Cross-adapter check: exact arithmetic over each registry is separated from search coverage. */
class PreferenceAdaptersCrossOracleTest {
    private static final List<String> METHODS = List.of("pace-x", "pace-b", "iscope", "sicsp-pref");

    @Test
    void wrongWinnerAndFalseBudgetFixturesSeparateRegistryArithmeticFromSearchCoverage() {
        validateFixture(wrongWinnerGraph(), new QuerySpec(1, 4, 0, 19, 80, 1));
        validateFixture(falseBudgetGraph(), new QuerySpec(1, 2, 0, 9, 21, 1));
    }

    private static void validateFixture(TDGraph graph, QuerySpec query) {
        List<TemporalCell> global = TinyContinuousEnvelopeOracle.solveCells(graph, query);
        Map<String, AlgorithmResult> outputs = new LinkedHashMap<>();
        Map<String, Integer> globalGenerationGapCells = new LinkedHashMap<>();
        for (String method : METHODS) {
            ExperimentAlgorithm algorithm = switch (method) {
                case "pace-x", "pace-b" -> new PaceExperimentAlgorithm(method);
                case "iscope" -> new IScopeAlgorithm();
                case "sicsp-pref" -> new SicspPrefExperimentAlgorithm();
                default -> throw new IllegalStateException(method);
            };
            AlgorithmResult result = algorithm.run(
                    graph, query, config(method), new ExperimentInstrumentation());
            outputs.put(method, result);
            @SuppressWarnings("unchecked")
            Map<String, Object> objective = (Map<String, Object>) result.scalars()
                    .get("objective_result");
            assertEquals("exact-preference-envelope-v1", objective.get("exact_profile_schema"));
            assertEquals(result.exactnessScope().name(), objective.get("exactness_scope"));

            List<List<Integer>> generated = paths(objective.get("generated_route_registry"));
            List<List<Integer>> emitted = paths(objective.get("emitted_route_registry"));
            List<List<Integer>> retained = paths(objective.get("retained_route_registry"));
            List<List<Integer>> algorithmRetained = paths(
                    objective.getOrDefault("algorithm_retained_route_registry", retained));
            List<List<Integer>> selected = paths(objective.get("selected_route_registry"));
            assertTrue(generated.containsAll(emitted), method + " emitted routes must come from its generated registry");
            assertTrue(algorithmRetained.containsAll(emitted), method + " emitted routes must come from its retained registry");
            assertTrue(emitted.containsAll(retained), method + " exact-feasible routes must be emitted routes");

            // The production common evaluator and the independent whole-cell oracle
            // operate on exactly the emitted registry. Any gap to `global` below is
            // therefore search coverage, not a route-evaluation arithmetic mismatch.
            var exact = ExactPreferenceObjective.evaluate(
                    graph, query, emitted.stream().map(Path::new).toList(), 0,
                    String.valueOf(objective.get("completion_status")),
                    result.exactnessScope().name(), "cross-method-fixture");
            assertEquals(objective.get("exact_profile_checksum"),
                    exact.objectiveResult().get("exact_profile_checksum"));
            assertWholeProfile(exact.profile(), TinyContinuousEnvelopeOracle.solveCellsForPaths(
                    graph, query, emitted));
            assertTrue(selected.stream().allMatch(emitted::contains));

            // Run and retain the independently enumerated global optimum separately.
            // This deliberately does not upgrade any adapter's search certificate.
            int generationOnlyDifferences = 0;
            for (TemporalCell globalCell : global) {
                ExactTime sample = globalCell.start().equals(globalCell.end())
                        ? exact(globalCell.start())
                        : exact(globalCell.start().add(globalCell.end())
                                .divide(ExactFraction.of(2)));
                ExactPreferenceEnvelope.Cell selectedCell = exact.profile().cellAt(sample).orElseThrow();
                List<Integer> globalPath = globalCell.path().orElse(null);
                List<Integer> selectedPath = selectedCell.found()
                        ? selectedCell.path().arcIds() : null;
                if (!java.util.Objects.equals(globalPath, selectedPath)) {
                    generationOnlyDifferences++;
                } else if (globalCell.value().isPresent()) {
                    var value = globalCell.value().orElseThrow();
                    assertEquals(BigInteger.valueOf(value.score()), selectedCell.score());
                    assertEquals(exact(value.arrivalSlope()).multiply(sample)
                                    .add(exact(value.arrivalIntercept())),
                            selectedCell.arrivalAt(sample));
                }
            }
            globalGenerationGapCells.put(method, generationOnlyDifferences);
        }
        assertEquals(java.util.Set.copyOf(METHODS), globalGenerationGapCells.keySet());
        assertEquals(ExactnessScope.NOT_CERTIFIED, outputs.get("pace-x").exactnessScope());
        if ("COMPLETE".equals(outputs.get("pace-x").scalars().get("generation_completion"))) {
            assertEquals(ExactnessScope.NOT_CERTIFIED,
                    outputs.get("pace-x").exactnessScope(),
                    "a legacy exhaustive claim cannot become a rational global certificate");
        }
        assertEquals(ExactnessScope.RETAINED_FRONTIER, outputs.get("pace-b").exactnessScope());
    }

    private static void assertWholeProfile(
            ExactPreferenceEnvelope actual, List<TemporalCell> expected) {
        for (TemporalCell cell : expected) {
            ExactTime sample = cell.start().equals(cell.end())
                    ? exact(cell.start())
                    : exact(cell.start().add(cell.end()).divide(ExactFraction.of(2)));
            ExactPreferenceEnvelope.Cell observed = actual.cellAt(sample).orElseThrow();
            assertEquals(cell.path().orElse(null),
                    observed.found() ? observed.path().arcIds() : null);
            if (cell.value().isPresent()) {
                var value = cell.value().orElseThrow();
                assertEquals(BigInteger.valueOf(value.score()), observed.score());
                assertEquals(exact(value.arrivalSlope()).multiply(sample)
                                .add(exact(value.arrivalIntercept())), observed.arrivalAt(sample),
                        "arrival value at " + sample + " for " + cell.path());
                if (!cell.start().equals(cell.end())) {
                    assertEquals(exact(value.arrivalSlope()), observed.arrivalSlope(),
                            "arrival slope at " + sample + " for " + cell.path());
                    assertEquals(exact(value.arrivalIntercept()), observed.arrivalIntercept(),
                            "arrival intercept at " + sample + " for " + cell.path());
                }
            }
        }
    }

    private static List<List<Integer>> paths(Object value) {
        if (!(value instanceof List<?> rows)) return List.of();
        return rows.stream().map(row -> ((List<?>) row).stream()
                .map(item -> ((Number) item).intValue()).toList()).toList();
    }

    private static ExactTime exact(ExactFraction value) {
        String[] parts = value.toString().split("/", 2);
        return parts.length == 1
                ? ExactTime.of(new BigInteger(parts[0]), BigInteger.ONE)
                : ExactTime.of(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    private static AlgorithmConfig config(String algorithm) {
        return new AlgorithmConfig(algorithm, Ablation.NONE, 4, 8, 8, 1,
                5, 2, 10_000, 100_000, 500_000, 100_000, true, 42);
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

    private static TDGraph falseBudgetGraph() {
        return graph(List.of(edge(0, 1, 2, travel(0, 1, 10, 31, 100, 31), score(0, 100, 7))));
    }

    private static TDGraph graph(List<Edge> edges) {
        TreeSet<Integer> nodes = new TreeSet<>();
        edges.forEach(edge -> { nodes.add(edge.source()); nodes.add(edge.target()); });
        return new TDGraph(nodes.stream().map(id -> new Node(id, id, 0)).toList(), edges);
    }

    private static Edge edge(int arc, int source, int target,
            PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(arc, source, target, 1, travel.minTravelTime(), travel, score);
    }

    private static PiecewiseLinearFn travel(double... values) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            points.add(new PiecewiseLinearFn.Breakpoint(values[i], values[i + 1]));
        }
        return new PiecewiseLinearFn(points);
    }

    private static PiecewiseConstFn score(int... values) {
        List<PiecewiseConstFn.Interval> intervals = new ArrayList<>();
        for (int i = 0; i < values.length; i += 3) {
            intervals.add(new PiecewiseConstFn.Interval(values[i], values[i + 1], values[i + 2]));
        }
        return new PiecewiseConstFn(intervals);
    }
}
