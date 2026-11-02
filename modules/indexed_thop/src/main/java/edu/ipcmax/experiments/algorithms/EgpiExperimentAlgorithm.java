package edu.ipcmax.experiments.algorithms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.pcmax.PACE;
import edu.ipcmax.core.pcmax.PaceAlgorithmVersion;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.egpi.EgpiAnytime;
import edu.ipcmax.core.pcmax.egpi.EgpiCandidateGenerator;
import edu.ipcmax.core.pcmax.egpi.EgpiResult;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** EGPI adapter; publishes only the last atomically committed exact envelope. */
public final class EgpiExperimentAlgorithm implements ExperimentAlgorithm {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The last query's committed state and where its clock started, for the post-run diagnostics. */
    private record LastRun(EgpiResult result, long runStartNanoTime) { }

    private TDGraph preparedGraph;
    private QueryPreparationIndexes preparedIndexes;
    private volatile LastRun lastRun;

    @Override public String id() { return "pace-b-egpi-v1"; }

    @Override public synchronized void prepare(TDGraph graph, AlgorithmConfig config) {
        if (preparedGraph == graph && preparedIndexes != null) return;
        preparedIndexes = PACE.preparedIndexes(graph);
        preparedGraph = graph;
    }

    @Override public AlgorithmResult run(TDGraph graph, QuerySpec query,
            AlgorithmConfig config, ExperimentInstrumentation instrumentation) {
        long started = System.nanoTime();
        lastRun = null;
        prepare(graph, config);
        var options = config.egpiOptions();
        var engine = config.egpiEngineOptions();
        PaceExecutionMetrics metrics = PaceExecutionMetrics.live(instrumentation::accept);
        EgpiResult result;
        try (ExactArithmeticDiagnostics.Scope ignored = ExactArithmeticDiagnostics.install(metrics)) {
            try {
                result = new EgpiCandidateGenerator(graph, preparedIndexes, options, engine, config.threads())
                        .generate(query);
            } finally {
                instrumentation.accept(metrics.snapshot());
                metrics.close();
            }
        }
        lastRun = new LastRun(result, started);
        result.counters().forEach(instrumentation::addCounter);
        instrumentation.setTiming("egpi_search", result.elapsedNanos());
        boolean feasible = result.envelope().hasFeasibleCell();
        String stop = String.valueOf(result.stopReason());
        boolean staticCertificate = stop.equals("NO_STRUCTURAL_ROUTE_WITHIN_STATIC_BOUND");
        // The replay reserve stops because too little of the deadline remains: a time cap.
        boolean timeCapped = result.deadlineReached() || stop.equals("DEADLINE") || stop.equals("CANCELLED")
                || stop.equals("REPLAY_BUDGET_RESERVE");
        boolean workCapped = stop.startsWith("MAX_");
        ExperimentStatus status = staticCertificate ? ExperimentStatus.NO_FEASIBLE_PATH
                : timeCapped ? ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED
                : workCapped ? ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED
                : feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE;
        ExactnessScope scope = feasible ? ExactnessScope.RETAINED_FRONTIER : ExactnessScope.NOT_CERTIFIED;
        long exactStarted = System.nanoTime();
        // Serializing the committed envelope is not search work; keep a pending
        // interrupt from aborting it and restore the flag afterwards.
        boolean interrupted = Thread.interrupted();
        ExactPreferenceObjective.Evaluation exact;
        try {
            var published = engine.resultForm() == edu.ipcmax.core.pcmax.egpi.EgpiEngineOptions.ResultForm.CANONICAL_CELLS_V1
                    ? result.envelope().canonicalized() : result.envelope();
            // The canonical result form reads only the route set: no per-route profile is merged for the answer.
            exact = engine.resultForm() == edu.ipcmax.core.pcmax.egpi.EgpiEngineOptions.ResultForm.CANONICAL_CELLS_V1
                    ? ExactPreferenceObjective.fromValidatedRoutes(published, result.registry().keySet(),
                            System.nanoTime() - started, status.name(), scope.name(),
                            EgpiCandidateGenerator.REGISTRY_SCOPE, true)
                    : ExactPreferenceObjective.fromValidated(published, result.registry(),
                            System.nanoTime() - started, status.name(), scope.name(),
                            EgpiCandidateGenerator.REGISTRY_SCOPE, true);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        instrumentation.setTiming("egpi_result_serialization", System.nanoTime() - exactStarted);
        Map<String, Object> objective = new LinkedHashMap<>(exact.objectiveResult());
        objective.put("unassigned_cell_semantics", "UNRESOLVED_NOT_PROVEN_INFEASIBLE");
        objective.put("unresolved_departure_domain_exact", result.unresolvedDomain().toString());
        objective.put("validated_registry_routes", result.registry().size());
        objective.put("full_domain_covered", result.fullDomainCovered());
        objective.put("outcome", feasible ? "VALIDATED_REGISTERED_CANDIDATE_ENVELOPE"
                : staticCertificate ? "STATIC_LOWER_BOUND_CERTIFIES_NO_ROUTE_WITHIN_BUDGET"
                : "UNRESOLVED_NO_VALIDATED_PROFILE");
        // Full coverage is reserved for a fully validated departure domain; unresolved is never infeasible.
        String coverageStatus = staticCertificate ? "STATIC_BOUND_CERTIFIES_NO_ROUTE"
                : !feasible ? "UNRESOLVED_NO_VALIDATED_PROFILE"
                : result.fullDomainCovered() ? "FULL_VALIDATED_COVERAGE" : "PARTIAL_VALIDATED_COVERAGE";
        String terminationCause = stop.equals("REPLAY_BUDGET_RESERVE") ? "WALL_CLOCK_RESERVE"
                : timeCapped ? "WALL_CLOCK_DEADLINE" : workCapped ? "WORK_CAP" : "SEARCH_EXHAUSTED";
        objective.put("coverage_status", coverageStatus);
        objective.put("termination_cause", terminationCause);
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("algorithm_version", PaceAlgorithmVersion.PACE_B_EGPI_V1.name());
        scalars.put("candidate_generation_policy", PaceAlgorithmVersion.PACE_B_EGPI_V1.candidateGenerationPolicy());
        scalars.put("connector_witness_policy", EgpiCandidateGenerator.CONNECTOR_WITNESS_POLICY);
        scalars.put("lazy_profile_cache_policy", EgpiCandidateGenerator.LAZY_PROFILE_CACHE_POLICY);
        scalars.put("search_work_budget", options.asMap());
        scalars.put("query_deadline_ms", options.queryDeadlineMs());
        scalars.put("deadline_reached", result.deadlineReached());
        scalars.put("stop_reason", stop);
        scalars.put("coverage_status", coverageStatus);
        scalars.put("termination_cause", terminationCause);
        scalars.put("generation_completion", stop);
        scalars.put("caps_triggered", result.capsTriggered());
        scalars.put("cap_triggered", timeCapped || workCapped
                ? (result.capsTriggered().isEmpty() ? List.of(stop) : result.capsTriggered()) : List.of());
        scalars.put("output_feasible", feasible);
        scalars.put("output_loopless", true);
        scalars.put("output_validation_contract", "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1");
        scalars.put("exact_profile_schema", "exact-preference-envelope-v1");
        scalars.put("exact_profile_checksum", objective.get("exact_profile_checksum"));
        scalars.put("objective_result", objective);
        scalars.put("final_retained_candidate_count", result.registry().size());
        scalars.put("distinct_path_count", result.registry().size());
        scalars.put("egpi_route_trace", result.routeTrace());
        scalars.put("egpi_engine", engine.asMap());
        scalars.put("egpi_clock_offset_ns", result.originNanoTime() - started);
        scalars.put("egpi_unit_events", result.unitEvents().stream().map(EgpiAnytime.UnitEvent::asMap).toList());
        scalars.put("egpi_epoch_events", result.epochEvents().stream().map(EgpiAnytime.EpochEvent::asMap).toList());
        instrumentation.setTiming("algorithm_total", System.nanoTime() - started);
        return new AlgorithmResult(status, null, scope, scalars, null, null);
    }

    /**
     * Rebuilds the committed state at each requested time of the query clock (nanoseconds
     * since {@code run()} started) from the immutable commit log and writes it, with the
     * final state, under {@code directory}. Runs after the timed run; a snapshot never
     * contains a unit committed after its time.
     */
    @Override public Map<String, Object> afterMeasuredRun(QuerySpec query, AlgorithmConfig config,
            AlgorithmResult measured, java.nio.file.Path directory, String runId, List<Long> checkpointsMs) {
        LastRun last = lastRun;
        lastRun = null;
        if (last == null) return Map.of();
        EgpiResult result = last.result();
        EgpiAnytime.Timeline timeline = result.timeline();
        long offsetNs = result.originNanoTime() - last.runStartNanoTime();
        List<Long> times = new ArrayList<>(checkpointsMs);
        java.util.Collections.sort(times);
        try {
            Files.createDirectories(directory);
            List<Integer> counts = new ArrayList<>();
            for (long ms : times) counts.add(timeline.commitsBy(ms * 1_000_000L - offsetNs));
            counts.add(timeline.commits().size());
            Map<Integer, EgpiAnytime.Snapshot> byCount = timeline.snapshotsAfter(counts);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i <= times.size(); i++) {
                boolean fin = i == times.size();
                EgpiAnytime.Snapshot snapshot = byCount.get(counts.get(i));
                Map<String, Object> file = new LinkedHashMap<>(snapshot.checkpointMap());
                file.put("run_id", runId);
                file.put("query", query.toString());
                file.put("search_stop_reason", result.stopReason());
                file.put("search_deadline_reached", result.deadlineReached());
                file.put("observed_ns", fin ? timeline.commits().isEmpty() ? 0 : snapshot.lastCommitNs()
                        : times.get(i) * 1_000_000L - offsetNs);
                file.put("checkpoint", fin ? "FINAL" : "T" + times.get(i));
                file.put("checkpoint_ms", fin ? null : times.get(i));
                file.put("clock_offset_ns", offsetNs);
                String name = "anytime_" + runId.substring(0, 16) + (fin ? "_final" : "_t" + times.get(i) + "ms") + ".json";
                Files.writeString(directory.resolve(name), JSON.writeValueAsString(file) + "\n", StandardCharsets.UTF_8);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("checkpoint", file.get("checkpoint"));
                row.put("checkpoint_ms", file.get("checkpoint_ms"));
                row.put("file", directory.resolve(name).toString());
                row.put("commit_count", snapshot.commitCount());
                row.put("coverage_fraction", snapshot.coverageFraction());
                row.put("average_preference", snapshot.averagePreference());
                row.put("covered_measure_exact", snapshot.coveredMeasure().toString());
                row.put("integrated_preference_exact", snapshot.integratedPreference().toString());
                row.put("cells", snapshot.envelope().cells().size());
                row.put("distinct_winners", snapshot.winners().size());
                row.put("registered_routes", snapshot.registeredRoutes().size());
                row.put("full_coverage", snapshot.fullCoverage());
                rows.add(row);
            }
            EgpiAnytime.Snapshot fin = byCount.get(timeline.commits().size());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema", EgpiAnytime.SCHEMA);
            out.put("clock", "nanoseconds since the adapter run() started; commit_ns + clock_offset_ns");
            out.put("checkpoints", rows);
            // exact running totals per commit: evaluated here, after the query clock stopped
            out.put("commit_timeline", timeline.commitRows());
            // The rebuilt final state must equal the envelope the search published (in the form it published it).
            boolean canonicalForm = config.egpiEngineOptions().resultForm()
                    == edu.ipcmax.core.pcmax.egpi.EgpiEngineOptions.ResultForm.CANONICAL_CELLS_V1;
            var rebuilt = canonicalForm ? fin.envelope().canonicalized() : fin.envelope();
            var published = canonicalForm ? result.envelope().canonicalized() : result.envelope();
            out.put("final_state_equals_published_envelope",
                    edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec.encode(rebuilt)
                            .equals(edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec.encode(published)));
            return out;
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
