package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.sicsp.*;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.experiments.framework.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Standalone native weight-bounded minimum-arrival reproduction, preserving the paper heuristic. */
public final class SicspNativeExperimentAlgorithm implements ExperimentAlgorithm {
    private TDGraph preparedGraph;
    private Map<String, String> preparedOptions;
    private SicspNativeGraph nativeGraph;
    private SicspNativeEngine engine;
    private double weightBound;
    private String weightsChecksum;
    private long preparationNanos;

    @Override public String id() { return "sicsp-native"; }

    @Override public synchronized void prepare(TDGraph graph, AlgorithmConfig config) {
        if (engine != null && graph == preparedGraph && config.objectiveOptions().equals(preparedOptions)) return;
        close();
        long started = System.nanoTime();
        Path path = Path.of(ObjectiveAdapterIO.required(config, "sicsp_weights"));
        weightBound = Double.parseDouble(ObjectiveAdapterIO.required(config, "sicsp_weight_bound"));
        if (!Double.isFinite(weightBound) || weightBound < 0) {
            throw new IllegalArgumentException("native SICSP weight bound must be finite and nonnegative");
        }
        try {
            var snapshot = ObjectiveAdapterIO.readVersionOne(path, config, "sicsp_weights");
            var root = snapshot.root();
            if (!root.path("semantics").asText().equals("native_sicsp_edge_weight")) {
                throw new IOException("weights must explicitly declare native_sicsp_edge_weight semantics");
            }
            if (!root.path("arcs").isObject()) throw new IOException("per-arc native weight functions are required");
            Map<Integer, SicspEdgeWeightFn> weights = new TreeMap<>();
            var arcs = root.path("arcs").fields();
            while (arcs.hasNext()) {
                var arc = arcs.next();
                if (!arc.getValue().isArray()) throw new IOException("arc weight must be an array of pieces");
                var pieces = new ArrayList<SicspEdgeWeightFn.Piece>();
                for (var piece : arc.getValue()) {
                    for (String key : new String[]{"start", "end", "weight"}) {
                        if (!piece.path(key).isNumber()) throw new IOException("numeric weight piece field required: " + key);
                    }
                    if (!piece.path("start_inclusive").isBoolean() || !piece.path("end_inclusive").isBoolean()) {
                        throw new IOException("weight piece endpoint ownership must be explicit");
                    }
                    pieces.add(new SicspEdgeWeightFn.Piece(new Domain.Interval(
                            piece.path("start").doubleValue(), piece.path("end").doubleValue(),
                            piece.path("start_inclusive").booleanValue(), piece.path("end_inclusive").booleanValue()),
                            piece.path("weight").doubleValue()));
                }
                weights.put(Integer.parseInt(arc.getKey()), new SicspEdgeWeightFn(pieces));
            }
            nativeGraph = new SicspNativeGraph(graph, weights);
            engine = new SicspNativeEngine(nativeGraph);
            preparedGraph = graph;
            preparedOptions = Map.copyOf(config.objectiveOptions());
            weightsChecksum = snapshot.sha256();
            preparationNanos = System.nanoTime() - started;
        } catch (IOException failure) {
            close();
            throw new IllegalArgumentException("native SICSP preparation failed: " + failure.getMessage(), failure);
        }
    }

    @Override public synchronized AlgorithmResult run(TDGraph graph, QuerySpec query,
            AlgorithmConfig config, ExperimentInstrumentation instrumentation) {
        if (engine == null) prepare(graph, config);
        if (graph != preparedGraph || !config.objectiveOptions().equals(preparedOptions)) {
            throw new IllegalArgumentException("native SICSP prepared graph/options differ; call prepare explicitly");
        }
        SicspNativeEngine activeEngine = engine;
        double activeBound = weightBound;
        String activeWeightsChecksum = weightsChecksum;
        long activePreparationNanos = preparationNanos;
        var result = activeEngine.query(new SicspNativeQuery(query.source(), query.destination(),
                query.departureDomain(), activeBound));
        var stats = result.statistics();
        instrumentation.setTiming("native_query_execution", stats.queryExecutionNanos());
        instrumentation.setTiming("native_candidate_generation", stats.candidateGenerationNanos());
        instrumentation.setTiming("native_dominance", stats.dominanceNanos());
        instrumentation.setTiming("native_profile_construction", stats.profileConstructionNanos());
        instrumentation.setTiming("native_selection", stats.selectionNanos());
        instrumentation.addCounter("forward_search_calls", stats.forwardDijkstraCalls());
        instrumentation.addCounter("reverse_search_calls", stats.reverseDijkstraCalls());
        instrumentation.addCounter("o_points_generated", stats.oPointsGenerated());
        instrumentation.addCounter("w_points_generated", stats.wPointsGenerated());
        instrumentation.addCounter("candidate_routes_generated", stats.candidateRoutesGenerated());
        instrumentation.addCounter("dominance_comparisons", stats.dominanceComparisons());
        instrumentation.addCounter("dominated_points_removed", stats.dominatedPointsRemoved());
        instrumentation.addCounter("time_breakpoints_examined", stats.timeBreakpointsExamined());
        instrumentation.addCounter("weight_breakpoints_examined", stats.weightBreakpointsExamined());
        instrumentation.addCounter("profile_discrepancies", result.profileDiscrepancies().size());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("objective", "minimum_arrival_under_native_weight_bound");
        output.put("feasible", result.selection().isPresent());
        output.put("native_weight_bound", activeBound);
        result.selection().ifPresent(selection -> {
            output.put("selected_departure", selection.sourceDeparture());
            output.put("earliest_candidate_arrival", selection.destinationArrival());
            output.put("accumulated_native_weight", selection.accumulatedWeight());
            output.put("selected_route_arc_ids", selection.path().arcIds());
            output.put("weight_bound_feasible", selection.accumulatedWeight() <= activeBound);
        });
        output.put("global_optimum_certified", false);
        output.put("profile_discrepancy_count", result.profileDiscrepancies().size());
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("objective", "minimum_arrival_under_native_weight_bound");
        scalars.put("numerical_contract", "legacy_canonical_12_decimal_per_operation-v1");
        scalars.put("status_schema_version", 2);
        scalars.put("native_heuristic", true);
        scalars.put("native_weight_input_sha256", activeWeightsChecksum);
        scalars.put("native_weight_source", "explicit_per_arc_weight_functions");
        scalars.put("native_preparation_nanos", activePreparationNanos);
        scalars.put("pace_budget_applied", false);
        scalars.put("pace_scores_used_as_weights", false);
        scalars.put("profile_discrepancies", result.profileDiscrepancies().stream().map(Object::toString).toList());
        scalars.put("objective_result", output);
        return new AlgorithmResult(result.selection().isPresent() ? ExperimentStatus.COMPLETED
                : ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE,
                null, ExactnessScope.NOT_CERTIFIED, scalars, null, null);
    }

    @Override public void close() {
        engine = null;
        nativeGraph = null;
        preparedGraph = null;
        preparedOptions = null;
        weightsChecksum = null;
    }
}
