package edu.ipcmax.experiments.algorithms;

import java.util.concurrent.CancellationException;

import java.util.Map;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.LegacyPaceCandidateGenerator;
import edu.ipcmax.core.pcmax.PaceAlgorithmVersion;
import edu.ipcmax.core.pcmax.PaceCompletion;
import edu.ipcmax.core.pcmax.PaceExactnessScope;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.LooplessChecker;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** Adapter preserving the finalized shared PACE-X/PACE-B implementation. */
public final class PaceExperimentAlgorithm implements ExperimentAlgorithm {
    private final String id;
    private volatile TDGraph preparedGraph;
    private volatile QueryPreparationIndexes preparedIndexes;

    public PaceExperimentAlgorithm(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
        if (preparedGraph == graph && preparedIndexes != null) {
            return;
        }
        preparedIndexes = PACE.preparedIndexes(graph);
        preparedGraph = graph;
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long algorithmStarted = System.nanoTime();
        prepare(graph, config);
        PaceExecutionMetrics metrics =
                PaceExecutionMetrics.live(instrumentation::accept);
        try (ExactArithmeticDiagnostics.Scope ignored =
                     ExactArithmeticDiagnostics.install(metrics)) {
            try {
                return runWithMetrics(
                        graph, query, config, instrumentation,
                        algorithmStarted, metrics);
            } finally {
                instrumentation.accept(metrics.snapshot());
                metrics.close();
            }
        }
    }

    private AlgorithmResult runWithMetrics(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation,
            long algorithmStarted,
            PaceExecutionMetrics metrics) {
        PACE pace = new PACE(
                graph,
                config.paceOptions(),
                preparedIndexes,
                metrics);
        var generator = new LegacyPaceCandidateGenerator(pace, PaceAlgorithmVersion.fromSelector(id));
        edu.ipcmax.core.pcmax.EnvelopeProfile profile = generator.run(query);
        var generation = pace.lastGenerationResult();
        var stats = pace.stats();
        instrumentation.addCounter("recursive_calls", stats.recursionCalls());
        instrumentation.addCounter("anchors_examined", stats.anchorsConsidered());
        instrumentation.addCounter("anchors_retained", stats.anchorsRetained());
        instrumentation.addCounter("connector_paths_enumerated", stats.connectorCandidates());
        instrumentation.addCounter("stitch_successes", stats.stitchedCandidates());
        instrumentation.addCounter("memo_hits", stats.cacheHits());
        instrumentation.addCounter("memo_misses", stats.cacheMisses());
        instrumentation.addCounter("parallel_tasks_started", stats.parallelTasksStarted());
        instrumentation.addCounter("corridor_nodes", stats.corridorNodes());
        instrumentation.addCounter("corridor_edges", stats.corridorEdges());
        instrumentation.addCounter("corridor_cells", stats.corridorCells());
        instrumentation.addCounter("score_relevant_edges", stats.scoreRelevantEdges());
        instrumentation.addCounter("selected_pivots", stats.selectedPivots());
        instrumentation.addCounter("connector_calls", stats.connectorCalls());
        instrumentation.addCounter("connector_expansions", stats.connectorExpansions());
        instrumentation.addCounter("valid_connectors", stats.validConnectors());
        instrumentation.addCounter("invalid_connectors", stats.invalidConnectors());
        instrumentation.addCounter("connector_cap_hits", stats.connectorCapHits());
        instrumentation.addCounter("candidates_generated", stats.candidatesGenerated());
        instrumentation.addCounter("candidates_retained", stats.candidatesRetained());
        instrumentation.addCounter("breakpoint_cap_hits", stats.breakpointCapHits());
        instrumentation.addCounter("total_candidate_work", stats.totalWork());
        instrumentation.addCounter("query_work_cap_hits", stats.queryWorkCapHits());
        instrumentation.addCounter("frontier_cells", stats.frontierCells());
        instrumentation.addCounter("peak_frontier_size", stats.peakFrontierSize());
        instrumentation.addCounter("memo_lookups", stats.cacheLookups());
        instrumentation.addCounter("memo_waits", stats.cacheWaits());
        instrumentation.addCounter("requested_workers", stats.requestedWorkers());
        instrumentation.addCounter("observed_workers", stats.observedWorkers());
        java.util.List<Path> emittedRoutes = generation.frontier().candidates().stream()
                .map(candidate -> new Path(candidate.stablePathId()))
                .distinct().toList();
        metrics.addCounterQuiet(
                "complete_paths_before_exact_replay", emittedRoutes.size());
        PaceExactnessScope searchExactness = generation.exactnessScope();
        ExactnessScope exactness = id.equals("pace-x")
                ? ExactnessScope.NOT_CERTIFIED
                : searchExactness == PaceExactnessScope.GLOBAL_CERTIFIED
                        ? ExactnessScope.RETAINED_FRONTIER
                        : searchExactness == PaceExactnessScope.RETAINED_FRONTIER
                                ? ExactnessScope.RETAINED_FRONTIER
                                : ExactnessScope.NOT_CERTIFIED;
        long exactStarted = System.nanoTime();
        ExactPreferenceObjective.Evaluation exact;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                "exact_objective_evaluation")) {
            exact = ExactPreferenceObjective.evaluateIncrementally(
                    graph, query, emittedRoutes, System.nanoTime() - algorithmStarted,
                    generation.completion().name(), exactness.name(),
                    !id.equals("pace-x") ? "PACE_B_RETAINED_FRONTIER" : "PACE_X_EMITTED_FRONTIER",
                    Thread.currentThread()::isInterrupted);
        }
        if (!exact.evaluationComplete()) {
            throw new CancellationException(
                    "exact objective evaluation cancelled before completion");
        }
        long exactNanos = System.nanoTime() - exactStarted;
        instrumentation.setTiming("exact_objective_evaluation", exactNanos);
        java.util.Map<String, Object> exactObjectiveResult = new java.util.LinkedHashMap<>(
                exact.objectiveResult());
        exactObjectiveResult.put("end_to_end_query_nanos", System.nanoTime() - algorithmStarted);
        boolean feasible = exact.profile().hasFeasibleCell();
        ExperimentStatus status = switch (generation.completion()) {
            case COMPLETE -> feasible
                    ? ExperimentStatus.COMPLETED
                    : ExperimentStatus
                            .UNRESOLVED_NO_GENERATED_CANDIDATE;
            case UNRESOLVED_NO_GENERATED_CANDIDATE ->
                    ExperimentStatus
                            .UNRESOLVED_NO_GENERATED_CANDIDATE;
            case NO_FEASIBLE_PATH -> ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE;
            case RESOURCE_TRUNCATED, ABORTED ->
                    ExperimentStatus.LIMIT_EXCEEDED;
        };
        // Exact final replay validates only emitted routes. Existing search-time
        // corridor, lower-bound and temporal pruning still uses the legacy model.
        if (generation.completion() == PaceCompletion.COMPLETE && !feasible) {
            status = ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE;
        } else if (id.equals("pace-x") && status == ExperimentStatus.COMPLETED) {
            status = ExperimentStatus.COMPLETED;
        }
        Map<String, Object> scalars = new java.util.LinkedHashMap<>();
        scalars.put("algorithm_version", generator.algorithmVersion());
        scalars.put("candidate_generation_policy", PaceAlgorithmVersion.fromSelector(id).candidateGenerationPolicy());
        scalars.put("generation_completion", generation.completion().name());
        scalars.put("cap_triggered", generation.capStatus().triggered().stream()
                .map(Enum::name).sorted().toList());
        scalars.put("first_cap_work_item",
                generation.capStatus().firstCanonicalWorkItem());
        scalars.put("corridor_checksum", generation.corridorChecksum());
        scalars.put("selected_pivot_arc_ids",
                generation.selectedPivotArcIds());
        scalars.put("output_checksum", generation.outputChecksum());
        scalars.put("output_feasible", feasible);
        scalars.put(
                "output_loopless",
                profile.segments().stream()
                        .filter(segment -> segment.found())
                        .allMatch(segment -> LooplessChecker.isLoopless(
                                graph, segment.path())));
        scalars.put(
                "output_validation_contract",
                "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1");
        scalars.put("legacy_projection_schema", "LEGACY_CANONICAL_PROFILE_12DP-v1");
        scalars.put("exact_profile_schema", "exact-preference-envelope-v1");
        scalars.put("exact_profile_checksum", exact.objectiveResult().get("exact_profile_checksum"));
        scalars.put("exact_objective_evaluation_nanos", exactNanos);
        scalars.put("retained_route_provenance", generation.frontier().candidates().stream()
                .map(candidate -> Map.of(
                        "path_arc_ids", candidate.stablePathId(),
                        "recursion_depth", candidate.recursionDepth(),
                        "pivot_id", candidate.pivotId(),
                        "compressed", candidate.compressed(),
                        "used_pivot_arc_ids", candidate.usedPivotArcIds()))
                .toList());
        scalars.put("objective_result", exactObjectiveResult);
        addRetainedPathStatistics(
                scalars, generation.frontier());
        return new AlgorithmResult(
                status,
                profile,
                exactness,
                scalars,
                null,
                null);
    }

    private static void addRetainedPathStatistics(
            Map<String, Object> scalars,
            edu.ipcmax.core.profile.CandidateSet frontier) {
        java.util.Map<java.util.List<Integer>, Integer> distinct =
                new java.util.TreeMap<>(
                        edu.ipcmax.core.profile.PathPointer
                                .STABLE_PATH_ORDER);
        for (edu.ipcmax.core.profile.CandidateProfile candidate :
                frontier.candidates()) {
            distinct.putIfAbsent(
                    candidate.stablePathId(),
                    candidate.edgeCount());
        }
        java.util.List<Integer> counts =
                distinct.values().stream().sorted().toList();
        scalars.put(
                "final_retained_candidate_count",
                frontier.size());
        scalars.put("distinct_path_count", distinct.size());
        if (counts.isEmpty()) {
            scalars.put("path_edge_count_min", 0);
            scalars.put("path_edge_count_sum", 0);
            scalars.put("path_edge_count_mean", 0.0);
            scalars.put("path_edge_count_median", 0.0);
            scalars.put("path_edge_count_p95", 0);
            scalars.put("path_edge_count_max", 0);
            return;
        }
        long sum = counts.stream()
                .mapToLong(Integer::longValue).sum();
        double median = counts.size() % 2 == 1
                ? counts.get(counts.size() / 2)
                : (counts.get(counts.size() / 2 - 1)
                    + counts.get(counts.size() / 2)) / 2.0;
        int p95Index = Math.max(
                0,
                (int) Math.ceil(
                        0.95 * counts.size()) - 1);
        scalars.put("path_edge_count_min", counts.get(0));
        scalars.put("path_edge_count_sum", sum);
        scalars.put(
                "path_edge_count_mean",
                (double) sum / counts.size());
        scalars.put("path_edge_count_median", median);
        scalars.put(
                "path_edge_count_p95",
                counts.get(p95Index));
        scalars.put(
                "path_edge_count_max",
                counts.get(counts.size() - 1));
    }
}
