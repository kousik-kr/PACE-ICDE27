package edu.ipcmax.experiments;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.LowerBoundGraph;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.core.labeling.PointForwardLabeling;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.PaceException;
import edu.ipcmax.core.pcmax.PaceAlgorithmVersion;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceWorkLedger;
import edu.ipcmax.core.pcmax.egpi.EgpiCandidateGenerator;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.LimitExceededException;
import edu.ipcmax.experiments.framework.ProfileSupport;
import edu.ipcmax.experiments.framework.QueryManifestEntry;
import edu.ipcmax.experiments.framework.QueryManifestIO;
import edu.ipcmax.experiments.algorithms.AllFpAlgorithm;
import edu.ipcmax.experiments.querygen.ManifestChecksum;

/** Unified reproducible command-line experiment driver. */
public final class PaceBench {
    private static final int QUERY_WORKER_NOT_TERMINATED = 1_000_000_000;
    private static final ObjectMapper JSON = QueryManifestIO.mapper();
    private static final List<String> TIMINGS = List.of(
            "preprocessing_total", "lower_bound_preprocessing", "anchor_index_preprocessing",
            "query_total", "cpu_total", "horizon_validation", "anchor_retrieval", "anchor_ranking",
            "connector_generation", "recursive_generation", "memo_lookup", "memo_compute",
            "temporal_stitching", "breakpoint_construction", "duplicate_removal", "safe_dominance",
            "bounded_retention", "fragment_merging", "feasibility_validation",
            "envelope_extraction", "envelope_canonicalization",
            "profile_serialization", "reference_comparison",
            "corridor_construction", "feasible_entry_band_computation",
            "score_support_lookup", "pivot_ranking_diversification",
            "forward_backward_labeling", "top_l_anchor_selection",
            "pivot_order_exploration", "candidate_assembly",
            "profile_merge",
            "functional_composition", "functional_search_control",
            "posthoc_scoring", "profiling",
            "allfp_budget_projection",
            "algorithm_total",
            "final_connector_reduction",
            "canonical_path_replay_stitching", "breakpoint_processing",
            "equality_root_computation", "fragment_restriction_merge",
            "statistics");
    private static final List<String> COUNTERS = List.of(
            "anchors_total", "anchors_examined", "anchors_rejected_valid_domain",
            "anchors_rejected_lower_bound", "anchors_retained", "connector_paths_enumerated",
            "connectors_rejected_lower_bound", "connectors_rejected_empty_domain",
            "connectors_retained", "recursive_calls", "memo_hits", "memo_misses", "memo_entries",
            "repeated_subproblems", "stitch_attempts", "stitch_rejected_path_consistency",
            "stitch_rejected_anchor_domain", "stitch_rejected_right_domain", "stitch_rejected_budget",
            "stitch_successes", "candidates_generated", "candidates_before_compression",
            "duplicate_fragments_removed", "extension_dominated_fragments_removed",
            "bounded_fragments_removed", "temporal_cells_created", "frontier_count",
            "frontier_size_sum", "frontier_size_max", "retained_fragments_total",
            "profile_breakpoints_total", "envelope_cells", "point_queries", "labels_created",
            "labels_expanded", "simple_paths_enumerated", "ksp_paths_retained",
            "corridor_nodes", "corridor_edges", "corridor_cells",
            "score_relevant_edges", "selected_pivots", "connector_calls",
            "connector_expansions", "valid_connectors", "invalid_connectors",
            "connector_cap_hits", "candidates_retained",
            "breakpoint_cap_hits", "total_candidate_work",
            "query_work_cap_hits", "frontier_cells", "peak_frontier_size",
            "memo_lookups", "memo_waits", "requested_workers",
            "observed_workers", "candidate_offers", "connector_requests",
            "breakpoints_processed", "candidate_pair_root_checks",
            "equality_roots_created", "affected_cell_evaluations",
            "frontier_retention_evaluations", "temporal_cells_split",
            "temporal_cells_merged", "dominance_comparisons",
            "dominance_structural_rejections",
            "dominance_arrival_signature_rejections",
            "retained_fragments", "dropped_fragments", "fragments_merged",
            "fragment_restrictions", "fragment_materializations",
            "fragment_materialization_cache_hits",
            "temporal_preimage_calls", "temporal_compose_calls",
            "score_compose_calls", "temporal_segments_visited",
            "temporal_cut_attempts", "temporal_cuts_created",
            "temporal_cuts_deduplicated",
            "temporal_relaxation_breakpoint_merges",
            "temporal_collinear_breakpoints_merged",
            "canonical_replay_requests", "canonical_replay_unique_requests",
            "canonical_replay_cache_hits", "canonical_replay_cache_misses",
            "canonical_replay_cache_evictions",
            "final_reduction_input_candidates",
            "final_reduction_distinct_path_ids",
            "final_reduction_observed_workers",
            "final_reduction_maximum_active_workers",
            "canonical_replay_repeated_prefixes",
            "canonical_prefix_cache_hits", "canonical_prefix_cache_misses",
            "canonical_prefix_cache_waits",
            "canonical_prefix_cache_evictions",
            "canonical_prefix_cache_peak_entries",
            "canonical_replay_edges", "canonical_replay_prefix_edges_reused",
            "canonical_replay_batches", "parallel_canonical_replay_tasks",
            "fragment_reference_cells", "fragment_reference_components",
            "fragment_reference_cells_coalesced",
            "fragment_merge_input_fragments",
            "fragment_merge_runs", "fragment_merge_maximum_run",
            "fragment_materialization_cache_evictions",
            "fragment_materialization_cache_peak_entries",
            "identical_fragment_domain_requests",
            "retained_cell_references_peak",
            "retained_profile_fragments_peak",
            "memory_peak_used_heap_bytes",
            "memory_before_graph_load_used_heap_bytes",
            "memory_after_graph_load_used_heap_bytes",
            "memory_after_preprocess_used_heap_bytes",
            "memory_after_corridor_used_heap_bytes",
            "memory_after_forward_backward_labels_used_heap_bytes",
            "memory_before_replay_used_heap_bytes",
            "memory_during_profile_merge_used_heap_bytes",
            "memory_before_final_reduction_used_heap_bytes",
            "memory_during_replay_used_heap_bytes",
            "memory_after_final_reduction_used_heap_bytes",
            "memory_after_query_used_heap_bytes",
            "query_worker_ignored_cancellation",
            "connector_label_witness_reconstructions",
            "connector_label_witness_reconstruction_time_ns",
            "connector_selected_paths",
            "connector_unique_paths_per_request_max",
            "connector_static_label_expansions",
            "connector_cache_hits", "connector_cache_misses",
            "connector_structural_rejections",
            "connector_temporal_rejections",
            "connector_identity_requests", "connector_path_arcs",
            "connector_generation_time_ns", "connector_replay_time_ns",
            "connector_portfolio_branches_entered",
            "frontier_layer_batches", "frontier_layer_batch_offers",
            "parallel_affected_cell_tasks",
            "feasible_entry_bands", "empty_feasible_entry_bands",
            "forward_lower_bound_labels",
            "backward_lower_bound_labels",
            "forward_connector_label_joins",
            "backward_suffix_label_joins",
            "candidate_path_edges_in_batch",
            "final_retained_candidate_count",
            "distinct_path_count",
            "path_edge_count_min",
            "path_edge_count_sum",
            "path_edge_count_mean",
            "path_edge_count_median",
            "path_edge_count_p95",
            "path_edge_count_max",
            "score_support_edges_examined", "mq_connector_request",
            "mq_pivot_task_admission",
            "mq_connector_label_generation",
            "mq_connector_join",
            "mq_candidate_assembly",
            "mq_path_verification",
            "mq_replay_request",
            "mq_temporal_composition",
            "mq_profile_merge",
            "mq_candidate_offer", "mq_affected_cell_evaluation",
            "mq_retention_evaluation", "mq_fragment_restriction",
            "mq_fragment_materialization",
            "mq_dominance_check", "mq_equality_root_check",
            "cache_hits", "cache_misses", "cache_lookups",
            "cache_waits", "cache_evictions", "cache_peak_entries",
            "canonical_replay_cache_peak_entries");

    private PaceBench() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = execute(args);
        } catch (Exception failure) {
            System.err.println("pace_bench: " + failure.getMessage());
            code = 2;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    static int execute(String[] args) throws Exception {
        if (args.length > 0 && "--validate-query-file".equals(args[0])) {
            if (args.length != 2) {
                throw new IllegalArgumentException(
                        "--validate-query-file requires exactly one JSONL path");
            }
            return validateQueryFile(Path.of(args[1]));
        }
        BenchOptions options = BenchOptions.parse(args);
        options.applyExactSelections();
        String normalizedJson = JSON.writeValueAsString(options.normalized());
        String configHash = ProfileSupport.sha256(normalizedJson);
        System.err.println("effective_configuration=" + normalizedJson);

        if (options.internalWorker
                && options.internalForcedStatus != null) {
            return executeForcedWithoutDataset(options, configHash);
        }

        if (!options.internalWorker
                && !options.sharedPreprocessing
                && (options.timeoutSeconds > 0
                    || options.memoryLimitMb > 0)) {
            return executeIsolated(options, configHash);
        }

        long beforeGraphLoadHeap = usedMemory();
        long loadStarted = System.nanoTime();
        LoadedDataset loaded = loadDataset(options.dataset);
        long afterGraphLoadHeap = usedMemory();
        long preprocessingNanos = System.nanoTime() - loadStarted;
        List<QueryManifestEntry> queries = options.queryFile == null
                ? generateQueries(loaded, options)
                : QueryManifestIO.read(options.queryFile);
        for (QueryManifestEntry query : queries) {
            if (!query.datasetId().equals(loaded.id)) {
                throw new IllegalArgumentException("query " + query.queryId() + " targets dataset_id "
                        + query.datasetId() + " but the loaded dataset is " + loaded.id);
            }
        }
        Set<String> completedIds = options.resume ? existingRunIds(options.outputJsonl) : Set.of();
        Map<String, String> externalReferences = options.referenceJsonl == null
                ? Map.of() : referenceChecksums(options.referenceJsonl);
        Map<AllFpReuseKey, AllFpReusableExecution> allFpReuse =
                new HashMap<>();
        try (ExperimentAlgorithm algorithm = AlgorithmRegistry.create(options.algorithm);
             ExperimentAlgorithm reference = options.referenceAlgorithm == null
                ? null : AlgorithmRegistry.create(options.referenceAlgorithm);
        ) {
        long algorithmPreparationStarted = System.nanoTime();
        algorithm.prepare(loaded.graph, options.algorithmConfig());
        if (reference != null) {
            AlgorithmConfig preparedReference = referenceConfig(
                    options, reference.id());
            reference.prepare(loaded.graph, preparedReference);
        }
        preprocessingNanos +=
                System.nanoTime() - algorithmPreparationStarted;
        Map<String, Object> datasetRecord = datasetRecord(loaded);
        Map<String, Object> systemRecord = systemRecord(options.threads);
        Files.createDirectories(parent(options.outputJsonl));
        if (options.outputCsv != null) {
            Files.createDirectories(parent(options.outputCsv));
        }
        if (options.internalWorker
                && options.internalProgressFile != null) {
            Path ready = workerReadyPath(options.internalProgressFile);
            Files.writeString(
                    ready,
                    Instant.now().toString(),
                    StandardCharsets.UTF_8);
        }

        int failures = 0;
        for (QueryManifestEntry entry : queries) {
            if (options.internalWorker) {
                failures += runOne(options, algorithm, reference, loaded.graph, entry, configHash,
                        preprocessingNanos, datasetRecord, systemRecord, options.internalWarmup,
                        options.internalRepetition, completedIds, externalReferences,
                        beforeGraphLoadHeap, afterGraphLoadHeap,
                        allFpReuse);
                break;
            }
            for (int warmup = 0; warmup < options.warmupRuns; warmup++) {
                int outcome = runOne(options, algorithm, reference, loaded.graph, entry, configHash,
                        preprocessingNanos, datasetRecord, systemRecord, true, warmup,
                        completedIds, externalReferences,
                        beforeGraphLoadHeap, afterGraphLoadHeap,
                        allFpReuse);
                if (outcome == QUERY_WORKER_NOT_TERMINATED) {
                    return 1;
                }
                failures += outcome;
                if (failures > 0 && options.failFast) {
                    return 1;
                }
            }
            List<Integer> measuredRepetitions = options.repetitionIndices.isEmpty()
                    ? java.util.stream.IntStream.range(0, options.repetitions)
                            .boxed().toList()
                    : options.repetitionIndices;
            for (int repetition : measuredRepetitions) {
                int outcome = runOne(options, algorithm, reference, loaded.graph, entry, configHash,
                        preprocessingNanos, datasetRecord, systemRecord, false, repetition,
                        completedIds, externalReferences,
                        beforeGraphLoadHeap, afterGraphLoadHeap,
                        allFpReuse);
                if (outcome == QUERY_WORKER_NOT_TERMINATED) {
                    return 1;
                }
                failures += outcome;
                if (failures > 0 && options.failFast) {
                    return 1;
                }
            }
        }
        return failures == 0 ? 0 : 1;
        }
    }

    private static int executeIsolated(BenchOptions options, String configHash) throws Exception {
        List<QueryManifestEntry> queries;
        if (options.queryFile != null) {
            queries = QueryManifestIO.read(options.queryFile);
        } else {
            LoadedDataset loaded = loadDataset(options.dataset);
            queries = generateQueries(loaded, options);
        }
        Files.createDirectories(parent(options.outputJsonl));
        if (options.outputCsv != null) {
            Files.createDirectories(parent(options.outputCsv));
        }
        Set<String> completed = options.resume ? existingRunIds(options.outputJsonl) : Set.of();
        int failures = 0;
        for (QueryManifestEntry query : queries) {
            for (int warmup = 0; warmup < options.warmupRuns; warmup++) {
                failures += runIsolated(options, query, configHash, true, warmup, completed);
                if (failures > 0 && options.failFast) {
                    return 1;
                }
            }
            List<Integer> measuredRepetitions = options.repetitionIndices.isEmpty()
                    ? java.util.stream.IntStream.range(0, options.repetitions)
                            .boxed().toList()
                    : options.repetitionIndices;
            for (int repetition : measuredRepetitions) {
                failures += runIsolated(options, query, configHash, false, repetition, completed);
                if (failures > 0 && options.failFast) {
                    return 1;
                }
            }
        }
        return failures == 0 ? 0 : 1;
    }

    /** Validate the real runner manifest parser without loading a graph or starting search. */
    private static int validateQueryFile(Path path) throws IOException {
        List<QueryManifestEntry> entries = QueryManifestIO.read(path);
        List<Map<String, Object>> queries = new ArrayList<>();
        int nullDistanceBins = 0;
        for (QueryManifestEntry entry : entries) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("query_id", entry.queryId());
            query.put("dataset_id", entry.datasetId());
            query.put("source", entry.source());
            query.put("destination", entry.destination());
            query.put("interval_start", entry.intervalStartExact());
            query.put("interval_end", entry.intervalEndExact());
            query.put("window_length", entry.windowLengthExact());
            if (entry.schemaVersion() == 4) {
                query.put("interval_start_ticks", entry.intervalStartTicks());
                query.put("interval_end_ticks", entry.intervalEndTicks());
                query.put("window_length_ticks", entry.windowLengthTicks());
                query.put("interval_time_unit", entry.intervalTimeUnit());
                query.put("endpoint_inclusion", entry.endpointInclusion());
            }
            query.put("budget", entry.budget());
            query.put("budget_slack", entry.budgetSlack());
            query.put("budget_policy", entry.budgetPolicy());
            query.put("distance_bin", entry.distanceBin());
            query.put("lower_bound_distance", entry.lowerBoundDistance());
            query.put("metadata", entry.metadata());
            if (entry.distanceBin() == null) {
                nullDistanceBins++;
            }
            queries.add(query);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "QUERY_SCHEMA_VALIDATED");
        result.put("search_started", false);
        result.put("query_count", entries.size());
        result.put("null_distance_bin_count", nullDistanceBins);
        result.put("queries", queries);
        System.out.println(JSON.writeValueAsString(result));
        return 0;
    }

    private static int runIsolated(
            BenchOptions options,
            QueryManifestEntry query,
            String configHash,
            boolean warmup,
            int repetition,
            Set<String> completed) throws Exception {
        String runId = ProfileSupport.sha256(String.join("|", options.experimentName, configHash,
                query.queryId(), Boolean.toString(warmup), Integer.toString(repetition)));
        if (completed.contains(runId)) {
            System.err.println("resume_skip=" + runId);
            return 0;
        }
        Path temporary = Files.createTempDirectory("pace-bench-query-");
        Path manifest = temporary.resolve("query.jsonl");
        Path output = temporary.resolve("result.jsonl");
        Path workerLog = temporary.resolve("worker.log");
        Path progress = temporary.resolve("progress.json");
        Path ready = workerReadyPath(progress);
        Files.writeString(manifest, JSON.writeValueAsString(query) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        List<String> command = isolatedCommand(
                options, manifest, output, progress, warmup, repetition, null,
                null);
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(workerLog.toFile()).start();
        boolean exited = waitForIsolatedWorker(
                process,
                ready,
                options.preprocessingTimeoutSeconds,
                options.timeoutSeconds,
                options.queryCleanupGraceSeconds);
        String forcedStatus = null;
        String forcedReason = null;
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            if (Files.isRegularFile(ready)) {
                forcedStatus = ExperimentStatus.TIMEOUT.name();
                forcedReason = "QueryWatchdogTimeout";
            } else {
                forcedStatus = ExperimentStatus.ERROR.name();
                forcedReason = "PreprocessingTimeout";
            }
        } else if (!Files.exists(output) || Files.size(output) == 0) {
            String workerLogText = Files.isRegularFile(workerLog)
                    ? Files.readString(workerLog, StandardCharsets.UTF_8)
                    : "";
            MissingWorkerResult missing =
                    classifyMissingWorkerResult(
                            process.exitValue(),
                            Files.isRegularFile(ready),
                            workerLogText,
                            options.memoryLimitMb);
            forcedStatus = missing.status().name();
            forcedReason = missing.reason();
        }
        if (forcedStatus != null) {
            Files.deleteIfExists(output);
            List<String> fallback = isolatedCommand(
                    options, manifest, output, progress,
                    warmup, repetition, forcedStatus, forcedReason);
            Process fallbackProcess = new ProcessBuilder(fallback)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(workerLog.toFile())).start();
            if (!fallbackProcess.waitFor(30, TimeUnit.SECONDS)) {
                fallbackProcess.destroyForcibly();
                throw new IOException("could not serialize forced " + forcedStatus
                        + " result for query " + query.queryId());
            }
        }
        if (Files.exists(workerLog)) {
            System.err.print(Files.readString(workerLog, StandardCharsets.UTF_8));
        }
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        if (lines.size() != 1) {
            throw new IOException("isolated query worker produced " + lines.size()
                    + " records for " + query.queryId());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> record = JSON.readValue(lines.get(0), LinkedHashMap.class);
        appendJson(options.outputJsonl, record);
        if (options.outputCsv != null && !warmup) {
            appendCsv(options.outputCsv, record);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) record.get("status");
        ExperimentStatus code = ExperimentStatus.valueOf(status.get("status_code").toString());
        deleteTree(temporary);
        return isFailure(code) ? 1 : 0;
    }

    static MissingWorkerResult classifyMissingWorkerResult(
            int exitCode,
            boolean queryReady,
            String workerLog,
            int memoryLimitMb) {
        String log = workerLog == null ? "" : workerLog;
        if (queryReady && log.contains(
                "query worker ignored cancellation")) {
            return new MissingWorkerResult(
                    ExperimentStatus.TIMEOUT,
                    "QueryWorkerIgnoredCancellation");
        }
        boolean explicitOutOfMemory =
                log.contains("java.lang.OutOfMemoryError")
                        || log.contains("OutOfMemoryError:");
        boolean memoryKill =
                memoryLimitMb > 0 && exitCode == 137;
        if (explicitOutOfMemory || memoryKill) {
            return new MissingWorkerResult(
                    ExperimentStatus.OUT_OF_MEMORY,
                    explicitOutOfMemory
                            ? "WorkerOutOfMemoryError"
                            : "WorkerMemoryKillExit137");
        }
        return new MissingWorkerResult(
                ExperimentStatus.ERROR,
                "WorkerExitedWithoutResultExit" + exitCode);
    }

    static record MissingWorkerResult(
            ExperimentStatus status,
            String reason) {
    }

    private static List<String> isolatedCommand(
            BenchOptions options,
            Path manifest,
            Path output,
            Path progress,
            boolean warmup,
            int repetition,
            String forcedStatus,
            String forcedReason) {
        Set<String> valued = Set.of(
                "--query-file", "--output-jsonl", "--output-csv", "--repetitions", "--warmup-runs",
                "--query-manifest-output", "--repetition-indices");
        Set<String> switches = Set.of("--resume");
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < options.commandLine.length; index++) {
            String token = options.commandLine[index];
            if (valued.contains(token)) {
                index++;
            } else if (!switches.contains(token) && !token.startsWith("--internal-")) {
                arguments.add(token);
            }
        }
        arguments.addAll(List.of(
                "--query-file", manifest.toString(),
                "--output-jsonl", output.toString(),
                "--internal-progress-file", progress.toString(),
                "--repetitions", "1", "--warmup-runs", "0",
                "--internal-worker", "--internal-repetition", Integer.toString(repetition),
                "--internal-original-command-line", Base64.getEncoder().encodeToString(
                        String.join("\u0000", options.commandLine).getBytes(StandardCharsets.UTF_8))));
        if (warmup) {
            arguments.add("--internal-warmup");
        }
        if (options.queryFile != null) {
            arguments.addAll(List.of("--internal-config-query-file", options.queryFile.toString()));
        } else {
            arguments.add("--internal-generated-queries");
        }
        if (forcedStatus != null) {
            arguments.addAll(List.of("--internal-forced-status", forcedStatus));
        }
        if (forcedReason != null) {
            arguments.addAll(List.of("--internal-forced-reason", forcedReason));
        }
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (options.memoryLimitMb > 0 && forcedStatus == null) {
            command.add("-Xmx" + options.memoryLimitMb + "m");
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), PaceBench.class.getName()));
        command.addAll(arguments);
        return command;
    }

    private static boolean waitForIsolatedWorker(
            Process process,
            Path ready,
            int preprocessingTimeoutSeconds,
            int queryTimeoutSeconds,
            int queryCleanupGraceSeconds) throws InterruptedException {
        int effectivePreprocessingTimeout = preprocessingTimeoutSeconds > 0
                ? preprocessingTimeoutSeconds
                : queryTimeoutSeconds;
        if (effectivePreprocessingTimeout <= 0) {
            process.waitFor();
            return true;
        }
        long preprocessingDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(effectivePreprocessingTimeout);
        while (process.isAlive() && !Files.isRegularFile(ready)) {
            long remaining = preprocessingDeadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            process.waitFor(
                    Math.max(1L, Math.min(
                            TimeUnit.NANOSECONDS.toMillis(remaining),
                            250L)),
                    TimeUnit.MILLISECONDS);
        }
        if (!process.isAlive()) {
            return true;
        }
        if (queryTimeoutSeconds <= 0) {
            process.waitFor();
            return true;
        }
        return process.waitFor(
                queryTimeoutSeconds + queryCleanupGraceSeconds,
                TimeUnit.SECONDS);
    }

    private static Path workerReadyPath(Path progress) {
        return progress.resolveSibling("query-ready");
    }

    private static int executeForcedWithoutDataset(
            BenchOptions options,
            String configHash) throws Exception {
        List<QueryManifestEntry> queries = QueryManifestIO.read(
                options.queryFile);
        if (queries.size() != 1) {
            throw new IOException(
                    "forced isolated result requires exactly one query");
        }
        QueryManifestEntry query = queries.get(0);
        Files.createDirectories(parent(options.outputJsonl));
        Execution outcome = forcedExecution(
                options.internalForcedStatus,
                options.internalForcedReason,
                options.internalProgressFile);
        long usedHeap = usedMemory();
        outcome.instrumentation.addCounter(
                "memory_before_graph_load_used_heap_bytes",
                usedHeap);
        outcome.instrumentation.addCounter(
                "memory_after_query_used_heap_bytes",
                usedHeap);
        String runId = ProfileSupport.sha256(String.join(
                "|",
                options.experimentName,
                configHash,
                query.queryId(),
                Boolean.toString(options.internalWarmup),
                Integer.toString(options.internalRepetition)));
        Map<String, Object> value = record(
                options,
                query,
                runId,
                configHash,
                options.internalWarmup,
                options.internalRepetition,
                null,
                unavailableDatasetRecord(options, query),
                systemRecord(options.threads),
                outcome,
                outcome.result,
                ProfileSupport.emptyQuality(),
                false,
                false,
                0);
        appendJson(options.outputJsonl, value);
        return 1;
    }

    private static Path queryProgressPath(
            BenchOptions options,
            QueryManifestEntry entry) {
        Path base = options.internalProgressFile;
        if (base == null || options.internalWorker) {
            return base;
        }
        String token = ProfileSupport.sha256(entry.queryId()).substring(0, 16);
        return base.resolveSibling(base.getFileName() + ".query-" + token + ".json");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int runOne(
            BenchOptions options,
            ExperimentAlgorithm algorithm,
            ExperimentAlgorithm reference,
            TDGraph graph,
            QueryManifestEntry entry,
            String configHash,
            long preprocessingNanos,
            Map<String, Object> datasetRecord,
            Map<String, Object> systemRecord,
            boolean warmup,
            int repetition,
            Set<String> completedIds,
            Map<String, String> externalReferences,
            long beforeGraphLoadHeap,
            long afterGraphLoadHeap,
            Map<AllFpReuseKey, AllFpReusableExecution> allFpReuse)
            throws IOException {
        String runId = ProfileSupport.sha256(String.join("|", options.experimentName, configHash,
                entry.queryId(), Boolean.toString(warmup), Integer.toString(repetition)));
        if (completedIds.contains(runId)) {
            System.err.println("resume_skip=" + runId);
            return 0;
        }
        AllFpReuseKey allFpKey = allFpReuseKey(
                options, algorithm, entry, warmup, repetition);
        AllFpReusableExecution reusable = allFpKey == null
                ? null : allFpReuse.get(allFpKey);
        Execution outcome;
        if (reusable != null) {
            long projectionStarted = System.nanoTime();
            AlgorithmResult projected = AllFpAlgorithm.withPostHocBudget(
                    reusable.execution().result(),
                    entry.budget(),
                    reusable.sourceQueryId(),
                    reusable.sourceBudget());
            outcome = copyExecution(reusable.execution(), projected);
            outcome.instrumentation().setTiming(
                    "allfp_budget_projection",
                    System.nanoTime() - projectionStarted);
        } else {
            outcome = options.internalForcedStatus == null
                    ? executeWithLimits(
                            algorithm,
                            graph,
                            entry,
                            options.algorithmConfig(),
                            options.timeoutSeconds,
                            options.memoryLimitMb,
                            options.queryCleanupGraceSeconds,
                            queryProgressPath(options, entry))
                    : forcedExecution(
                            options.internalForcedStatus,
                            options.internalForcedReason,
                            options.internalProgressFile);
            if (allFpKey != null && reusableAllFpOutcome(outcome)) {
                allFpReuse.put(
                        allFpKey,
                        new AllFpReusableExecution(
                                copyExecution(outcome, outcome.result()),
                                entry.queryId(),
                                entry.budget()));
            }
        }
        outcome.instrumentation.addCounter(
                "memory_before_graph_load_used_heap_bytes",
                beforeGraphLoadHeap);
        outcome.instrumentation.addCounter(
                "memory_after_graph_load_used_heap_bytes",
                afterGraphLoadHeap);
        EnvelopeProfile referenceProfile = null;
        long referenceNanos = 0;
        boolean queryWorkersTerminated = outcome.queryWorkerTerminated;
        if (options.internalForcedStatus == null
                && reference != null && !reference.id().equals(algorithm.id())) {
            long started = System.nanoTime();
            Execution referenceOutcome = executeWithLimits(
                    reference, graph, entry, referenceConfig(options, reference.id()),
                    options.timeoutSeconds, options.memoryLimitMb,
                    options.queryCleanupGraceSeconds, null);
            referenceNanos = System.nanoTime() - started;
            queryWorkersTerminated &= referenceOutcome.queryWorkerTerminated;
            if (referenceOutcome.result.status() == ExperimentStatus.COMPLETED
                    || referenceOutcome.result.status() == ExperimentStatus.CERTIFIED_COMPLETE
                    || referenceOutcome.result.status() == ExperimentStatus.NO_FEASIBLE_PATH) {
                referenceProfile = referenceOutcome.result.profile();
            }
        } else if (options.internalForcedStatus == null && reference != null) {
            referenceProfile = outcome.result.profile();
        }

        Map<String, Object> quality = referenceProfile == null
                ? ProfileSupport.emptyQuality()
                : ProfileSupport.quality(outcome.result.profile(), referenceProfile);
        if (referenceProfile == null && externalReferences.containsKey(entry.queryId())) {
            quality.put("reference_profile_checksum", externalReferences.get(entry.queryId()));
        }
        String actualChecksum = ProfileSupport.resultChecksum(outcome.result);
        String expectedChecksum = referenceProfile == null
                ? externalReferences.get(entry.queryId()) : ProfileSupport.checksum(referenceProfile);
        boolean referenceAvailable = expectedChecksum != null;
        boolean verified = referenceAvailable && expectedChecksum.equals(actualChecksum);
        AlgorithmResult result = outcome.result;
        if (options.verifyOutput && referenceAvailable && !verified
                && (options.algorithm.equals("pace-x") || options.algorithm.equals("pl-exact"))) {
            result = new AlgorithmResult(ExperimentStatus.ERROR, result.profile(), ExactnessScope.NOT_CERTIFIED,
                    result.scalars(), "VerificationError",
                    "exact profile checksum differs from reference");
        }

        Map<String, Object> record = record(options, entry, runId, configHash, warmup, repetition,
                preprocessingNanos, datasetRecord, systemRecord, outcome, result, quality,
                referenceAvailable, verified, referenceNanos);
        if (options.serializeProfile && ProfileSupport.canonicalResult(result) != null) {
            long started = System.nanoTime();
            Files.createDirectories(options.profileOutputDir);
            Path profilePath = options.profileOutputDir.resolve(runId + ".profile.txt");
            String canonical = ProfileSupport.canonicalResult(result);
            Files.writeString(profilePath, canonical, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) record.get("output");
            output.put("profile_file", profilePath.toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> memory = (Map<String, Object>) record.get("memory_bytes");
            memory.put("serialized_output_size", Files.size(profilePath));
            @SuppressWarnings("unchecked")
            Map<String, Object> timing = (Map<String, Object>) record.get("timing_ns");
            timing.put("profile_serialization", System.nanoTime() - started);
        }
        if (!warmup && options.anytimeOutputDir != null && !isFailure(result.status())) {
            // After the query clock stopped: intermediate answers rebuilt from the commit log.
            Map<String, Object> anytime = algorithm.afterMeasuredRun(entry.toQuerySpec(),
                    options.algorithmConfig(), result, options.anytimeOutputDir, runId,
                    options.anytimeCheckpointsMs);
            if (!anytime.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) record.get("output");
                output.put("anytime", anytime);
            }
        }
        appendJson(options.outputJsonl, record);
        if (options.outputCsv != null && !warmup) {
            appendCsv(options.outputCsv, record);
        }
        System.err.println("run_id=" + runId + " query_id=" + entry.queryId()
                + " status=" + result.status());
        if (!queryWorkersTerminated) {
            System.err.println(
                    "pace_bench: query worker ignored cancellation; "
                            + "dataset-reuse execution is unsafe");
            return QUERY_WORKER_NOT_TERMINATED;
        }
        return isFailure(result.status()) ? 1 : 0;
    }

    private static Execution forcedExecution(
            String status,
            String reason,
            Path progressPath) {
        ExperimentStatus code = ExperimentStatus.valueOf(status);
        ExperimentInstrumentation instrumentation =
                new ExperimentInstrumentation();
        boolean recovered = instrumentation.recover(progressPath);
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("progress_snapshot_recovered", recovered);
        scalars.put("last_progress_phase",
                instrumentation.currentPhase());
        String errorType = reason == null || reason.isBlank()
                ? code == ExperimentStatus.OUT_OF_MEMORY
                        ? "MemoryLimitExceeded"
                        : code.name()
                : reason;
        AlgorithmResult result = new AlgorithmResult(
                code,
                null,
                ExactnessScope.NOT_CERTIFIED,
                scalars,
                errorType,
                "isolated query worker terminated before returning an "
                        + "algorithm result (" + errorType + ")");
        long elapsed = instrumentation.elapsedNanos();
        instrumentation.setTiming("query_total", elapsed);
        return new Execution(
                result, instrumentation, elapsed,
                0, 0, 0, -1, -1, -1, true);
    }

    private static AlgorithmConfig referenceConfig(BenchOptions options, String id) {
        AlgorithmConfig base = options.algorithmConfig();
        return new AlgorithmConfig(
                id,
                edu.ipcmax.experiments.framework.Ablation.NONE,
                base.paceEngineMode(),
                base.theta(),
                Math.max(1, base.pivotLimitL()),
                Math.max(1, base.connectorLimitKc()),
                Math.max(1, base.frontierLimitKf()),
                base.connectorExpansionCapMc(),
                base.breakpointCapMb(),
                base.queryWorkCapMq(),
                1,
                id.equals("rpq") ? Math.max(1, base.rpqStepMinutes()) : base.rpqStepMinutes(),
                id.equals("ksp-profile") ? Math.max(1, base.baselineK()) : base.baselineK(),
                base.maxEnumeratedPaths(), base.maxLabels(), base.maxExpansions(),
                base.maxFrontierFragments(), base.deterministic(), base.seed());
    }

    private static Execution executeWithLimits(
            ExperimentAlgorithm algorithm,
            TDGraph graph,
            QueryManifestEntry entry,
            AlgorithmConfig config,
            int timeoutSeconds,
            int memoryLimitMb,
            int queryCleanupGraceSeconds,
            Path progressPath) {
        ExperimentInstrumentation instrumentation =
                new ExperimentInstrumentation(progressPath);
        long startMemory = usedMemory();
        long peakMemory = startMemory;
        long startRss = processRssBytes();
        long peakRss = startRss;
        long started = System.nanoTime();
        long startedCpu = processCpuTime();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pace-query-worker");
            thread.setDaemon(true);
            return thread;
        });
        Future<AlgorithmResult> future = executor.submit(
                () -> algorithm.run(graph, entry.toQuerySpec(), config, instrumentation));
        AlgorithmResult result = null;
        long queryProcessingNanos = -1;
        long cancellationStartedNanos = -1;
        long memoryLimitBytes = memoryLimitMb * 1024L * 1024L;
        long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long watchdogNanos = timeoutNanos;
        if (timeoutSeconds > 0
                && (List.of("iscope", "iscope-basic", "iscope-opt1", "iscope-optimized")
                    .contains(algorithm.id())
                    || algorithm.id().equals("allfp")
                    || algorithm.id().equals("pace-b-egpi-v1"))) {
            watchdogNanos = timeoutNanos + TimeUnit.SECONDS.toNanos(2);
        }
        while (result == null) {
            long elapsed = System.nanoTime() - started;
            long observedMemory = usedMemory();
            peakMemory = Math.max(peakMemory, observedMemory);
            long observedRss = processRssBytes();
            peakRss = Math.max(peakRss, observedRss);
            if (memoryLimitMb > 0 && observedMemory > memoryLimitBytes) {
                queryProcessingNanos = elapsed;
                cancellationStartedNanos = System.nanoTime();
                instrumentation.freezeProgressAtTerminal();
                future.cancel(true);
                result = new AlgorithmResult(ExperimentStatus.OUT_OF_MEMORY, null,
                        ExactnessScope.NOT_CERTIFIED, Map.of(),
                        "MemoryLimitExceeded",
                        "observed JVM heap exceeded configured per-query memory threshold");
                break;
            }
            if (timeoutSeconds > 0 && elapsed >= watchdogNanos) {
                queryProcessingNanos = elapsed;
                cancellationStartedNanos = System.nanoTime();
                instrumentation.freezeProgressAtTerminal();
                future.cancel(true);
                result = failure(ExperimentStatus.TIMEOUT,
                        new TimeoutException("query exceeded " + timeoutSeconds + " seconds"));
                break;
            }
            long waitNanos = TimeUnit.MILLISECONDS.toNanos(10);
            if (timeoutSeconds > 0) {
                waitNanos = Math.max(1, Math.min(waitNanos, watchdogNanos - elapsed));
            }
            try {
                result = future.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException pollingTimeout) {
                // Poll again so timeout and heap limits are enforced while the query runs.
            } catch (InterruptedException interrupted) {
                queryProcessingNanos = System.nanoTime() - started;
                cancellationStartedNanos = System.nanoTime();
                Thread.currentThread().interrupt();
                future.cancel(true);
                result = failure(ExperimentStatus.ERROR, interrupted);
            } catch (ExecutionException wrapped) {
                result = classify(wrapped.getCause());
            }
        }
        if (queryProcessingNanos < 0) {
            queryProcessingNanos = System.nanoTime() - started;
        }
        long cleanupStartedNanos = System.nanoTime();
        executor.shutdownNow();
        boolean queryWorkerTerminated;
        try {
            int terminationWaitSeconds = cancellationStartedNanos >= 0
                    ? queryCleanupGraceSeconds
                    : Math.max(1, queryCleanupGraceSeconds);
            queryWorkerTerminated = executor.awaitTermination(
                    terminationWaitSeconds, TimeUnit.SECONDS);
            if (cancellationStartedNanos >= 0 && !queryWorkerTerminated) {
                /*
                 * This method runs inside the one-query isolated child whenever
                 * a timeout or memory limit is configured. Preserve and
                 * serialize the already determined terminal result; the child
                 * main method exits non-zero immediately afterwards, which
                 * forcibly releases the daemon query thread and all
                 * query-local state. Throwing here used to discard the formal
                 * TIMEOUT result, and the outer process then misclassified the
                 * missing output as OOM merely because -Xmx was configured.
                 */
                instrumentation.addCounter(
                        "query_worker_ignored_cancellation",
                        1);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while awaiting query cleanup",
                    interrupted);
        }
        long runtime = System.nanoTime() - started;
        long cleanupNanos = cancellationStartedNanos >= 0
                ? System.nanoTime() - cancellationStartedNanos
                : System.nanoTime() - cleanupStartedNanos;
        long cpuTime = processCpuTime();
        long endMemory = usedMemory();
        long endRss = processRssBytes();
        peakRss = Math.max(peakRss, endRss);
        instrumentation.setTiming("query_total", runtime);
        instrumentation.setTiming("query_processing", queryProcessingNanos);
        instrumentation.setTiming("query_cleanup", cleanupNanos);
        instrumentation.addCounter(
                "query_worker_terminated", queryWorkerTerminated ? 1 : 0);
        instrumentation.addCounter(
                "query_cleanup_grace_seconds", queryCleanupGraceSeconds);
        if (startedCpu >= 0 && cpuTime >= startedCpu) {
            instrumentation.setTiming("cpu_total", cpuTime - startedCpu);
        }
        return new Execution(
                result,
                instrumentation,
                runtime,
                startMemory,
                endMemory,
                Math.max(peakMemory, endMemory),
                startRss,
                endRss,
                peakRss,
                queryWorkerTerminated);
    }

    private static AlgorithmResult classify(Throwable failure) {
        if (failure instanceof OutOfMemoryError) {
            return failure(ExperimentStatus.OUT_OF_MEMORY, failure);
        }
        if (failure instanceof LimitExceededException) {
            return failure(ExperimentStatus.LIMIT_EXCEEDED, failure);
        }
        if (failure instanceof PaceException pace) {
            ExperimentStatus status = switch (pace.status()) {
                case FUNCTION_HORIZON_EXCEEDED -> ExperimentStatus.FUNCTION_HORIZON_EXCEEDED;
                case LIMIT_EXCEEDED -> ExperimentStatus.LIMIT_EXCEEDED;
                default -> ExperimentStatus.ERROR;
            };
            return failure(status, failure);
        }
        if (failure instanceof IllegalArgumentException) {
            return failure(ExperimentStatus.INVALID_QUERY, failure);
        }
        return failure(ExperimentStatus.ERROR, failure);
    }

    private static AlgorithmResult failure(ExperimentStatus status, Throwable failure) {
        return new AlgorithmResult(status, null, ExactnessScope.NOT_CERTIFIED, Map.of(),
                failure.getClass().getSimpleName(), failure.getMessage());
    }

    private static Map<String, Object> record(
            BenchOptions options,
            QueryManifestEntry entry,
            String runId,
            String configHash,
            boolean warmup,
            int repetition,
            Long preprocessingNanos,
            Map<String, Object> datasetRecord,
            Map<String, Object> systemRecord,
            Execution outcome,
            AlgorithmResult result,
            Map<String, Object> quality,
            boolean referenceAvailable,
            boolean verified,
            long referenceNanos) throws IOException {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("schema_version", 3);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", runId);
        run.put("experiment_name", options.experimentName);
        run.put("timestamp_utc", Instant.now().toString());
        run.put("repetition", repetition);
        run.put("warmup", warmup);
        run.put("git_commit", git("rev-parse", "HEAD"));
        run.put("git_dirty", !git("status", "--porcelain").isBlank());
        run.put("executable_version", "0.1.0");
        run.put("build_type", System.getProperty("pace.build.type", "release"));
        run.put("command_line", List.of(options.commandLine));
        run.put("config_hash", configHash);
        run.put("source_commit", run.get("git_commit"));
        run.put("configuration_sha256", configHash);
        run.putAll(executableIdentity());
        run.put("config_hash_scope",
                "complete_effective_execution_configuration-v1");
        run.put(
                "scientific_config_hash",
                ProfileSupport.sha256(JSON.writeValueAsString(
                        scientificConfigurationRecord(options))));
        run.put("scientific_config_hash_scope",
                "algorithm_and_determinism_parameters-v1");
        top.put("run", run);
        top.put("system", systemRecord);
        top.put("dataset", datasetRecord);
        top.put("query", queryRecord(entry));
        top.put("configuration", configurationRecord(options));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("algorithm_version", algorithmVersion(options.algorithm));
        provenance.put("source_commit", run.get("git_commit"));
        provenance.put("source_dirty", run.get("git_dirty"));
        provenance.put("jar_sha256", run.get("jar_sha256"));
        provenance.put("jar_identity_status", run.get("jar_identity_status"));
        provenance.put("configuration_sha256", configHash);
        Object payloadHash = datasetRecord.get("dataset_payload_checksum");
        provenance.put("dataset_sha256", payloadHash != null
                ? payloadHash : datasetRecord.get("runtime_graph_semantic_checksum"));
        provenance.put("dataset_sha256_scope", payloadHash != null
                ? "PACE-GRAPH-CHECKSUM-v1:framed_required_input_files"
                : "canonical_runtime_graph_semantics-v1");
        Path originalManifest = options.internalConfigQueryFile != null
                ? options.internalConfigQueryFile : options.queryFile;
        provenance.put("query_manifest_sha256", originalManifest != null && Files.isRegularFile(originalManifest)
                ? sha256File(originalManifest) : null);
        provenance.put("query_manifest_identity_status", originalManifest != null && Files.isRegularFile(originalManifest)
                ? "HASHED_INPUT_MANIFEST_BYTES" : "UNAVAILABLE_GENERATED_WITHOUT_SAVED_INPUT_MANIFEST");
        top.put("provenance", provenance);
        top.put("algorithm_version", algorithmVersion(options.algorithm));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status_code", result.status().name());
        status.put("completed", result.status().completedExecution());
        status.put("terminal", result.status().terminal());
        status.put("execution_policy", executionPolicy(options.algorithm, options.timeoutSeconds));
        status.put("exactness_scope", result.exactnessScope().name());
        status.put(
                "generation_completion",
                result.scalars().get("generation_completion"));
        status.put(
                "cap_triggered",
                result.scalars().getOrDefault(
                        "cap_triggered", List.of()));
        status.put(
                "partial_output_policy",
                switch (options.algorithm) {
                    case "pace-x" -> "FAIL_CLOSED";
                    case "pace-b", "pace-b-legacy" -> "DETERMINISTIC_RETAINED_FRONTIER";
                    case "pace-b-egpi-v1" -> "LAST_ATOMICALLY_COMMITTED_EXACT_VALIDATED_ENVELOPE";
                    case "iscope", "iscope-basic" -> "ANYTIME_VALIDATED_ENVELOPE";
                    case "iscope-opt1" -> "EXACT_PARTIAL_FUNCTION_PRUNED_ENVELOPE";
                    case "iscope-optimized" -> "APPROXIMATE_SEARCH_EXACT_RETAINED_ROUTE_ENVELOPE";
                    case "allfp" -> "FASTEST_FUNCTIONAL_ENVELOPE";
                    case "sicsp-pref" -> "RETAINED_FRONTIER_UNRESOLVED";
                    case "sicsp-native" -> "NATIVE_HEURISTIC_WEIGHT_BOUNDED";
                    case "thop-partitioned" -> "EXACT_FASTEST_PROFILE";
                    default -> null;
                });
        status.put(
                "certificate_conditions",
                result.exactnessScope()
                        == ExactnessScope.GLOBAL_CERTIFIED
                        ? certificateConditions(options.algorithm)
                        : List.of());
        status.put("reference_available", referenceAvailable);
        status.put("output_verified", verified);
        status.put("exit_code", isFailure(result.status()) ? 1 : 0);
        top.put("status", status);
        Map<String, Object> timings = nullMap(TIMINGS);
        timings.put("preprocessing_total", preprocessingNanos);
        Object loadTimingValue = datasetRecord.get("load_component_timing_ns");
        long datasetLoadNanos = 0;
        if (loadTimingValue instanceof Map<?, ?> loadTiming) {
            for (Map.Entry<?, ?> component : loadTiming.entrySet()) {
                if (component.getKey() instanceof String name
                        && component.getValue() instanceof Number nanos) {
                    timings.put("preprocessing_input_" + name, nanos.longValue());
                    if (name.equals("dataset_loading_total")) {
                        datasetLoadNanos = nanos.longValue();
                    }
                }
            }
        }
        timings.put("preprocessing_dataset_loading",
                preprocessingNanos == null && datasetLoadNanos == 0
                        ? null : datasetLoadNanos);
        timings.put("preprocessing_query_independent_indexes",
                preprocessingNanos == null
                        ? null
                        : Math.max(0, preprocessingNanos - datasetLoadNanos));
        timings.putAll(outcome.instrumentation.timings());
        if (referenceNanos > 0) {
            timings.put("reference_comparison", referenceNanos);
        }
        top.put("timing_ns", timings);
        top.put("progress", outcome.instrumentation.progressState());
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("peak_rss", outcome.peakRss() < 0 ? null : outcome.peakRss());
        memory.put("start_rss", outcome.startRss() < 0 ? null : outcome.startRss());
        memory.put("end_rss", outcome.endRss() < 0 ? null : outcome.endRss());
        memory.put("peak_heap", outcome.peakMemory());
        memory.put("start_heap", outcome.startMemory());
        memory.put("end_heap", outcome.endMemory());
        memory.put("memoization_peak", null);
        memory.put("frontier_peak_estimate", null);
        memory.put("serialized_output_size", null);
        top.put("memory_bytes", memory);
        Map<String, Object> counters = nullMap(COUNTERS);
        counters.putAll(outcome.instrumentation.counters());
        counters.put("envelope_cells", result.profile() == null ? null : result.profile().segments().size());
        // Algorithm-result scalars are the authoritative logical-query view.
        // This matters for allFP budget projections, whose measured search
        // counters are copied but whose reuse markers intentionally differ.
        result.scalars().forEach(counters::put);
        top.put("counters", counters);
        top.put("output", ProfileSupport.output(result));
        top.put("quality", quality);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", result.errorType());
        error.put("message", result.errorMessage());
        error.put("stack_trace_or_context", null);
        error.put("failing_phase",
                !isFailure(result.status())
                        || outcome.instrumentation.currentPhase().isBlank()
                        ? null
                        : outcome.instrumentation.currentPhase());
        top.put("error", error);
        return top;
    }

    private static Map<String, Object> configurationRecord(BenchOptions options) {
        AlgorithmConfig config = options.algorithmConfig();
        boolean egpi = options.algorithm.equals("pace-b-egpi-v1");
        var pace = options.algorithm.startsWith("pace") && !egpi ? config.paceOptions() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", options.algorithm);
        result.put("algorithm_version", algorithmVersion(options.algorithm));
        result.put("ablation", options.ablation.id());
        result.put("execution_mode", switch (options.algorithm) {
            case "pace-x" -> "PACE_X_EXHAUSTIVE";
            case "pace-b", "pace-b-legacy" -> "PACE_B_BOUNDED";
            case "pace-b-egpi-v1" -> "PACE_B_EGPI_V1";
            case "exh-profile" -> "EXHAUSTIVE_PROFILE_ENUMERATION";
            case "pl-exact" -> "PROFILE_LABELING";
            case "rpq" -> "SAMPLED_LEFT_CLOSED_RIGHT_OPEN";
            case "ksp-profile" -> "EXACT_OVER_RETAINED_K_PATHS";
            case "iscope", "iscope-basic" -> "CONTINUOUS_PROFILE_EXHAUSTIVE_PATH_ADAPTATION-v1";
            case "iscope-opt1" -> "EXACT_PARTIAL_FUNCTION_OPT1";
            case "iscope-optimized" -> "APPROXIMATE_PARTIAL_FUNCTION_OPT1_OPT2";
            case "allfp" -> "CONTINUOUS_FUNCTIONAL_ALL_FASTEST_PATHS";
            case "sicsp-pref" -> "SEQUENTIAL_SICSP_PREFERENCE_PROFILE";
            case "sicsp-native" -> "NATIVE_WEIGHT_BOUNDED_MINIMUM_ARRIVAL_HEURISTIC";
            case "thop-partitioned" -> "PARTITIONED_EXACT_RATIONAL_FASTEST_PROFILE";
            default -> "REDUCED_OUTPUT_EVALUATION_PROFILE";
        });
        result.put("preprocessing_accounting", "EXCLUDED");
        result.put("exact_kernel", edu.ipcmax.baselines.exact.ExactKernel.mode().name());
        result.put("exact_replay_engine", edu.ipcmax.baselines.exact.preference.ExactReplayEngine.mode().name());
        result.put("exact_balanced_leaf_edges", edu.ipcmax.baselines.exact.preference.ExactReplayEngine.leafEdges());
        result.put("exact_balanced_minimum_arcs", edu.ipcmax.baselines.exact.preference.ExactReplayEngine.minimumArcs());
        result.put("objective_options", new java.util.TreeMap<>(options.objectiveOptions));
        result.put("objective", switch (options.algorithm) {
            case "thop-partitioned" -> "fastest_profile";
            case "sicsp-native" -> "minimum_arrival_under_native_weight_bound";
            default -> "preference_profile_with_travel_time_budget";
        });
        result.put("numerical_contract", egpi || List.of("iscope-opt1", "iscope-optimized").contains(options.algorithm)
                ? "EXACT_RATIONAL_PREFERENCE_PROFILE-v1"
                : options.algorithm.equals("thop-partitioned")
                        ? "exact_rational_after_decimal_canonical_input-v1"
                        : "legacy_canonical_12_decimal_profile_and_per_operation_replay-v1");
        result.put("status_schema_version", 2);
        result.put("theta", pace == null ? null : pace.theta());
        result.put("anchor_limit", pace == null ? null
                : (pace.anchorLimit() == Integer.MAX_VALUE ? "unbounded" : pace.anchorLimit()));
        result.put("k", pace == null ? null : pace.frontierLimit());
        result.put("pace_engine", pace == null ? null : pace.engineMode().name());
        result.put("pivot_limit_l", pace == null ? null
                : (pace.pivotLimitL() == Integer.MAX_VALUE
                        ? "unbounded" : pace.pivotLimitL()));
        result.put("connector_limit_kc", pace == null ? null
                : (pace.connectorLimitKc() == Integer.MAX_VALUE
                        ? "unbounded" : pace.connectorLimitKc()));
        result.put("frontier_limit_kf", pace == null ? null
                : (pace.frontierLimitKf() == Integer.MAX_VALUE
                        ? "unbounded" : pace.frontierLimitKf()));
        result.put("connector_expansion_cap_mc", pace == null ? null
                : (pace.connectorExpansionCapMc() == Long.MAX_VALUE
                        ? "unbounded" : pace.connectorExpansionCapMc()));
        result.put("breakpoint_cap_mb", pace == null ? null
                : (pace.breakpointCapMb() == Integer.MAX_VALUE
                        ? "unbounded" : pace.breakpointCapMb()));
        result.put("query_work_cap_mq", pace == null ? null
                : (pace.queryWorkCapMq() == Long.MAX_VALUE
                        ? "unbounded" : pace.queryWorkCapMq()));
        result.put(
                "query_work_accounting_contract",
                pace == null ? null
                        : PaceWorkLedger.ACCOUNTING_CONTRACT);
        result.put("rpq_step_minutes", options.algorithm.equals("rpq") ? options.rpqStepMinutes : null);
        result.put("baseline_k", options.algorithm.equals("ksp-profile") ? options.baselineK : null);
        result.put("threads", pace == null ? options.threads : pace.threadCount());
        result.put("deterministic", options.deterministic);
        result.put("timeout_seconds", options.unlimitedTimeoutExplicit
                ? "unlimited" : options.timeoutSeconds);
        result.put("timeout_policy", options.unlimitedTimeoutExplicit
                ? "EXPLICIT_UNLIMITED"
                : options.timeoutSpecified ? "EXPLICIT_FINITE" : "UNSPECIFIED_LEGACY");
        result.put("memory_limit_bytes", options.memoryLimitMb == 0 ? null : options.memoryLimitMb * 1024L * 1024L);
        result.put("max_enumerated_paths", switch (options.algorithm) {
            case "exh-profile", "ksp-profile", "interval-best", "iscope", "iscope-basic",
                    "iscope-opt1", "iscope-optimized", "sicsp-pref" ->
                    options.maxEnumeratedPaths;
            default -> null;
        });
        result.put("max_labels", options.algorithm.equals("pl-exact")
                || options.algorithm.equals("allfp") ? options.maxLabels : null);
        result.put("max_expansions", options.algorithm.equals("pl-exact")
                || options.algorithm.equals("allfp") ? options.maxExpansions : null);
        result.put("max_frontier_fragments", options.algorithm.startsWith("pace")
                ? options.maxFrontierFragments : null);
        result.put("anchor_decomposition_enabled", pace == null ? false : pace.theta() > 0);
        result.put("safe_dominance_enabled", pace == null ? options.algorithm.equals("pl-exact")
                : pace.features().safeDominanceEnabled());
        result.put("memoization_enabled", pace == null ? false : pace.memoizationEnabled());
        result.put("per_cell_retention_enabled", pace == null ? false : pace.features().perCellRetentionEnabled());
        result.put("representative_retention_enabled", pace == null ? false
                : pace.features().representativeRetentionEnabled());
        result.put("anchor_lower_bound_filter_enabled", pace == null ? false
                : pace.features().anchorLowerBoundFilterEnabled());
        result.put("compression_enabled", pace == null ? false : pace.features().compressionEnabled());
        result.put("adjacent_merge_enabled", pace == null ? false : pace.features().adjacentMergeEnabled());
        result.put("safe_corridor_enabled", pace == null ? false
                : pace.features().safeCorridorEnabled());
        result.put("pivot_diversification_enabled", pace == null ? false
                : pace.features().pivotDiversificationEnabled());
        result.put("connector_portfolio_enabled", pace == null ? false
                : pace.features().connectorPortfolioEnabled());
        result.put("connector_policy", pace == null ? null
                : pace.connectorPolicyId());
        result.put("connector_implementation_version", pace == null ? null
                : pace.singleLabelFastestConnectorEnabled()
                        ? "PACE_B_STATIC_LB_SINGLE_CONNECTOR_V1"
                        : pace.connectorPolicyId());
        result.put("connector_selection_semantics", pace == null ? null
                : pace.singleLabelFastestConnectorEnabled()
                        ? "ONE_QUERY_HORIZON_STATIC_LOWER_BOUND_PATH_THEN_EXACT_TEMPORAL_REPLAY"
                        : pace.policy() == PaceExecutionPolicy.PACE_X
                                ? "EXHAUSTIVE_FEASIBLE_CONNECTOR_PATHS"
                                : pace.engineMode()
                                        == edu.ipcmax.core.pcmax.PaceEngineMode.LEGACY
                                                ? "LEGACY_RECURSIVE_CONNECTOR_ALTERNATIVES_DIAGNOSTIC"
                                                : "LEGACY_FAST_SCORE_DIVERSITY_PORTFOLIO_DIAGNOSTIC");
        result.put("single_fastest_lower_bound_witness_enabled",
                pace != null
                        && pace.singleLabelFastestConnectorEnabled());
        result.put("single_midpoint_fastest_connector_enabled",
                false);
        result.put("temporal_replay_contract", egpi ? "EXACT_RATIONAL_LAZY_ENTRY_DOMAIN_COMPOSITION_V1"
                : List.of("iscope-opt1", "iscope-optimized").contains(options.algorithm)
                ? "EXACT_RATIONAL_PARTIAL_FUNCTION_AND_FINAL_REPLAY-v1"
                : options.algorithm.equals("thop-partitioned")
                        ? "EXACT_RATIONAL_AFTER_DECIMAL_CANONICAL_INPUT-v1"
                        : "LEGACY_CANONICAL_PROFILE_COMPOSITION-v1");
        result.put("connector_cache_enabled", pace == null ? false
                : pace.features().connectorCacheEnabled());
        result.put("profile_cache_enabled", pace == null ? false
                : pace.features().profileCacheEnabled());
        result.put("score_upper_bound_enabled", pace == null ? false
                : pace.features().scoreUpperBoundEnabled());
        result.put("parallel_enabled",
                pace != null
                        ? pace.threadCount() > 1
                        : options.algorithm.equals("allfp")
                                && options.threads > 1);
        result.put("exhaustive_connectors", options.algorithm.equals("pace-x"));
        // Anchor retention is not a certificate that theta covers every feasible anchor sequence.
        result.put("exhaustive_anchors", null);
        result.put("exhaustive_frontier", options.algorithm.equals("pace-x"));
        result.put("candidate_generation_policy", options.algorithm.startsWith("pace")
                ? PaceAlgorithmVersion.fromSelector(options.algorithm).candidateGenerationPolicy() : null);
        result.put("connector_witness_policy", egpi ? EgpiCandidateGenerator.CONNECTOR_WITNESS_POLICY
                : pace == null ? null : pace.connectorPolicyId());
        result.put("lazy_profile_cache_policy", egpi ? EgpiCandidateGenerator.LAZY_PROFILE_CACHE_POLICY
                : "NOT_EGPI");
        result.put("search_work_budget", egpi ? config.egpiOptions().asMap()
                : pace == null ? null : Map.of("pivot_limit_l", pace.pivotLimitL(),
                        "connector_expansion_cap_mc", pace.connectorExpansionCapMc(),
                        "query_work_cap_mq", pace.queryWorkCapMq()));
        // Boxed on purpose: a primitive branch would unbox the null "no deadline" case.
        Long queryDeadline = egpi ? Long.valueOf(config.egpiOptions().queryDeadlineMs())
                : options.timeoutSeconds == 0 ? null : Long.valueOf(options.timeoutSeconds * 1000L);
        result.put("query_deadline", queryDeadline);
        result.put("query_deadline_unit", "milliseconds");
        if (egpi) {
            result.put("egpi", config.egpiOptions().asMap());
            result.put("egpi_engine", config.egpiEngineOptions().asMap());
            result.put("query_work_accounting_contract", "EGPI_GLOBAL_QUERY_WORK_BUDGET_V1");
            result.put("safe_corridor_enabled", true);
            result.put("connector_policy", EgpiCandidateGenerator.CONNECTOR_WITNESS_POLICY);
            result.put("connector_implementation_version", "EGPI_STRUCTURAL_WITNESS_STORE_V1");
            result.put("connector_selection_semantics", "ONE_DETERMINISTIC_STATIC_LB_PATH_PER_COMPLETE_RESTRICTION_KEY");
            result.put("single_fastest_lower_bound_witness_enabled", true);
            result.put("connector_cache_enabled", true);
            result.put("profile_cache_enabled", true);
            result.put("parallel_enabled", options.threads > 1);
            result.put("timeout_policy", "STRICT_INTERNAL_SEARCH_DEADLINE_WITH_BOUNDED_RETURN_GRACE");
            result.put("watchdog_return_grace_ms", 2000);
        }
        return result;
    }

    private static Map<String, Object> scientificConfigurationRecord(
            BenchOptions options) {
        Map<String, Object> full = configurationRecord(options);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of(
                "algorithm",
                "ablation",
                "execution_mode",
                "objective",
                "objective_options",
                "numerical_contract",
                "status_schema_version",
                "theta",
                "pivot_limit_l",
                "connector_limit_kc",
                "frontier_limit_kf",
                "connector_expansion_cap_mc",
                "breakpoint_cap_mb",
                "query_work_cap_mq",
                "query_work_accounting_contract",
                "rpq_step_minutes",
                "baseline_k",
                "threads",
                "deterministic",
                "max_enumerated_paths",
                "max_labels",
                "max_expansions",
                "max_frontier_fragments",
                "anchor_decomposition_enabled",
                "safe_dominance_enabled",
                "memoization_enabled",
                "per_cell_retention_enabled",
                "representative_retention_enabled",
                "anchor_lower_bound_filter_enabled",
                "compression_enabled",
                "adjacent_merge_enabled",
                "safe_corridor_enabled",
                "pivot_diversification_enabled",
                "connector_portfolio_enabled",
                "connector_policy",
                "connector_implementation_version",
                "connector_selection_semantics",
                "single_fastest_lower_bound_witness_enabled",
                "single_midpoint_fastest_connector_enabled",
                "temporal_replay_contract",
                "exact_kernel",
                "exact_replay_engine",
                "exact_balanced_leaf_edges",
                "exact_balanced_minimum_arcs",
                "connector_cache_enabled",
                "profile_cache_enabled",
                "score_upper_bound_enabled")) {
            result.put(field, full.get(field));
        }
        result.put("seed", Long.toUnsignedString(options.seed));
        if (options.algorithm.equals("pace-b-egpi-v1")) {
            result.put("algorithm_version", full.get("algorithm_version"));
            result.put("egpi", full.get("egpi"));
            result.put("egpi_engine", full.get("egpi_engine"));
            result.put("candidate_generation_policy", full.get("candidate_generation_policy"));
            result.put("connector_witness_policy", full.get("connector_witness_policy"));
            result.put("lazy_profile_cache_policy", full.get("lazy_profile_cache_policy"));
            result.put("query_deadline", full.get("query_deadline"));
        }
        return result;
    }

    private static String executionPolicy(String algorithm, int timeoutSeconds) {
        return switch (algorithm) {
            case "pace-x" -> "PACE_X";
            case "pace-b", "pace-b-legacy" -> "PACE_B_LEGACY";
            case "pace-b-egpi-v1" -> "PACE_B_EGPI_V1";
            case "iscope", "iscope-basic", "iscope-opt1", "iscope-optimized" ->
                    "ISCOPE_" + timeoutSeconds + "_SECOND_CAP";
            case "allfp" -> "ALLFP_FUNCTIONAL";
            case "sicsp-pref" -> "SICSP_PREF_RETAINED_FRONTIER";
            case "sicsp-native" -> "SICSP_NATIVE_HEURISTIC";
            case "thop-partitioned" -> "THOP_PARTITIONED_EXACT_FASTEST_PROFILE";
            default -> null;
        };
    }

    private static String algorithmVersion(String algorithm) {
        return algorithm.startsWith("pace")
                ? PaceAlgorithmVersion.fromSelector(algorithm).name()
                : algorithm;
    }

    private static String sha256File(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<String, Object> executableIdentity() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var location = PaceBench.class.getProtectionDomain().getCodeSource().getLocation();
            Path artifact = Path.of(location.toURI());
            result.put("executable_path", artifact.toAbsolutePath().toString());
            if (Files.isRegularFile(artifact)) {
                result.put("jar_sha256", sha256File(artifact));
                result.put("jar_identity_status", "HASHED_RUNNING_JAR_BYTES");
            } else {
                result.put("jar_sha256", null);
                result.put("jar_identity_status", "CLASSPATH_DIRECTORY_NOT_IMMUTABLE_JAR");
            }
        } catch (Exception unavailable) {
            result.put("jar_sha256", null);
            result.put("jar_identity_status", "UNAVAILABLE:" + unavailable.getClass().getSimpleName());
        }
        return result;
    }

    private static List<String> certificateConditions(String algorithm) {
        return switch (algorithm) {
            case "pace-x" -> List.of(
                    "PACE_X",
                    "UNBOUNDED_CONNECTORS",
                    "UNBOUNDED_FRONTIERS",
                    "ALL_SCORE_PIVOTS",
                    "THETA_COVERS_SELECTED_PIVOTS",
                    "NO_CAP_REACHED");
            case "iscope", "iscope-basic" -> List.of(
                    "EXHAUSTIVE_LOOPLESS_PATH_ENUMERATION",
                    "LEGACY_CANONICAL_FULL_WINDOW_PATH_PROFILES",
                    "SCORE_MAXIMIZING_FEASIBLE_ENVELOPE",
                    "NO_CAP_REACHED");
            case "iscope-opt1" -> List.of(
                    "EXACT_RATIONAL_PARTIAL_FUNCTION_PROPAGATION",
                    "ADMISSIBLE_EXACT_EDGE_MINIMUM_SUFFIX_BOUND",
                    "EXHAUSTIVE_LOOPLESS_PATH_ENUMERATION",
                    "NO_CAP_REACHED");
            case "iscope-optimized" -> List.of();
            case "allfp" -> List.of(
                    "CONTINUOUS_FUNCTIONAL_A_STAR",
                    "ADMISSIBLE_REVERSE_MIN_EDGE_LOWER_BOUND",
                    "LOWER_BORDER_OR_QUEUE_EXHAUSTION_PROOF",
                    "NO_CAP_REACHED");
            case "thop-partitioned" -> List.of(
                    "FASTEST_PROFILE_OBJECTIVE_ONLY", "EXACT_RATIONAL_LABELS",
                    "VERSION_2_GRAPH_VALIDATED_INDEX", "SUPPORTED_DEPARTURE_DOMAIN",
                    "EXHAUSTIVE_BOUNDARY_COMBINATIONS");
            default -> List.of();
        };
    }

    private static Map<String, Object> queryRecord(QueryManifestEntry entry) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query_id", entry.queryId());
        result.put("query_family_id", entry.metadata().getOrDefault("family_id", "unknown"));
        result.put("parent_workload_family_id", entry.metadata().get("parent_workload_family_id"));
        result.put("source", entry.source());
        result.put("destination", entry.destination());
        result.put("interval_start", entry.intervalStartExact());
        result.put("interval_end", entry.intervalEndExact());
        result.put("window_length", entry.windowLengthExact());
        if (entry.schemaVersion() == 4) {
            result.put("interval_start_ticks", entry.intervalStartTicks());
            result.put("interval_end_ticks", entry.intervalEndTicks());
            result.put("window_length_ticks", entry.windowLengthTicks());
            result.put("interval_time_unit", entry.intervalTimeUnit());
            result.put("endpoint_inclusion", entry.endpointInclusion());
        }
        result.put("budget", entry.budget());
        result.put("budget_slack", entry.budgetSlack());
        result.put("budget_policy", entry.budgetPolicy());
        result.put("distance_bin", entry.distanceBin());
        result.put("lower_bound_distance", entry.lowerBoundDistance());
        result.put("fastest_travel_time_min", entry.fastestTravelTimeMin() == null
                ? entry.metadata().get("fastest_travel_time_min") : entry.fastestTravelTimeMin());
        result.put("fastest_travel_time_max", entry.fastestTravelTimeMax() == null
                ? entry.metadata().get("fastest_travel_time_max") : entry.fastestTravelTimeMax());
        result.put("query_seed", Long.toUnsignedString(entry.querySeed()));
        return result;
    }

    private static Map<String, Object> datasetRecord(LoadedDataset dataset) {
        TDGraph graph = dataset.graph;
        long parallel = graph.edges().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        edge -> edge.source() + ":" + edge.target(), java.util.stream.Collectors.counting()))
                .values().stream().mapToLong(count -> Math.max(0, count - 1)).sum();
        int maxOut = nodeIds(graph).stream().mapToInt(node -> graph.outgoingEdges(node).size()).max().orElse(0);
        long travelPieces = graph.edges().stream()
                .mapToLong(edge -> Math.max(0, edge.travelTimeFunction().breakpoints().size() - 1)).sum();
        long scorePieces = graph.edges().stream().mapToLong(edge -> edge.scoreFunction().intervals().size()).sum();
        long anchors = graph.edges().stream().filter(edge -> edge.scoreFunction().intervals().stream()
                .anyMatch(piece -> piece.value() > 0)).count();
        long zero = graph.edgeCount() - anchors;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset_id", dataset.id);
        result.put("dataset_path", dataset.path);
        result.put("runtime_graph_semantic_checksum",
                graphSemanticChecksum(graph));
        if (List.of("demo", "sicsp-figure1", "timeout-test").contains(dataset.path)) {
            result.put("dataset_identity_kind", "bundled_synthetic_semantic_definition");
            result.put("fixture_semantic_identity_sha256", graphSemanticChecksum(graph));
            result.put("fixture_semantic_identity_scope", "canonical_runtime_graph_semantics-v1");
        }
        result.put("dataset_payload_checksum",
                dataset.datasetPayloadChecksum);
        result.put("dataset_structure_checksum",
                dataset.datasetStructureChecksum);
        result.put("temporal_attribute_checksum",
                dataset.temporalAttributeChecksum);
        result.put("load_component_timing_ns", dataset.loadTimings);
        result.put("checksum_scope_version",
                "pace-explicit-dataset-checksum-scopes-v1");
        result.put("vertices", graph.nodeCount());
        result.put("edges", graph.edgeCount());
        result.put("parallel_edges", parallel);
        result.put("average_out_degree", graph.nodeCount() == 0 ? 0.0 : (double) graph.edgeCount() / graph.nodeCount());
        result.put("maximum_out_degree", maxOut);
        result.put("temporal_horizon_start", graph.edges().isEmpty() ? null
                : graph.edges().get(0).travelTimeFunction().domain().intervals().get(0).start());
        result.put("temporal_horizon_end", graph.edges().isEmpty() ? null
                : graph.edges().get(0).travelTimeFunction().domain().intervals().get(0).end());
        result.put("travel_time_piece_count", travelPieces);
        result.put("score_piece_count", scorePieces);
        result.put("average_travel_time_pieces_per_edge", graph.edgeCount() == 0 ? 0.0
                : (double) travelPieces / graph.edgeCount());
        result.put("average_score_pieces_per_edge", graph.edgeCount() == 0 ? 0.0
                : (double) scorePieces / graph.edgeCount());
        result.put("anchor_count", anchors);
        result.put("anchor_density", graph.edgeCount() == 0 ? 0.0 : (double) anchors / graph.edgeCount());
        result.put("zero_score_edge_fraction", graph.edgeCount() == 0 ? 0.0 : (double) zero / graph.edgeCount());
        result.put("topology_seed", dataset.seed);
        result.put("temporal_seed", dataset.seed);
        result.put("score_seed", dataset.seed);
        return result;
    }

    private static Map<String, Object> unavailableDatasetRecord(
            BenchOptions options,
            QueryManifestEntry query) throws IOException {
        Map<String, Object> metadata = query.metadata();
        JsonNode manifest = null;
        Path datasetPath = Path.of(options.dataset);
        Path manifestPath = datasetPath.resolve("manifest.json");
        if (Files.isRegularFile(manifestPath)) {
            manifest = JSON.readTree(manifestPath.toFile());
        }
        long nodes = manifest == null
                ? 0
                : manifest.path("num_nodes").asLong(0);
        long edges = manifest == null
                ? 0
                : manifest.path("num_arcs").asLong(0);
        long anchors = manifest == null
                ? 0
                : manifest.path("selected_score_edge_count").asLong(0);
        Double density = manifest == null
                || !manifest.has("score_edge_fraction")
                ? null
                : manifest.path("score_edge_fraction").asDouble();
        Object seed = manifest == null || !manifest.has("seed")
                ? metadata.getOrDefault("graph_seed", options.seed)
                : manifest.path("seed").asLong();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataset_id", query.datasetId());
        result.put(
                "dataset_path",
                metadata.getOrDefault("dataset_path", options.dataset));
        result.put("runtime_graph_semantic_checksum", null);
        result.put(
                "dataset_payload_checksum",
                metadata.get("dataset_payload_checksum"));
        result.put(
                "dataset_structure_checksum",
                metadata.get("dataset_checksum"));
        result.put(
                "temporal_attribute_checksum",
                metadata.get("temporal_attribute_checksum"));
        result.put(
                "checksum_scope_version",
                "pace-explicit-dataset-checksum-scopes-v1");
        result.put("vertices", nodes == 0 ? null : nodes);
        result.put("edges", edges == 0 ? null : edges);
        result.put("parallel_edges", null);
        result.put(
                "average_out_degree",
                nodes == 0 ? null : (double) edges / nodes);
        result.put("maximum_out_degree", null);
        result.put(
                "temporal_horizon_start",
                manifest == null
                        ? null
                        : manifest.path("temporal_support")
                                .path("start").asDouble());
        result.put(
                "temporal_horizon_end",
                metadata.getOrDefault(
                        "function_support_end",
                        manifest == null
                                ? null
                                : manifest.path("temporal_support")
                                        .path("end").asDouble()));
        result.put("travel_time_piece_count", null);
        result.put("score_piece_count", null);
        result.put("average_travel_time_pieces_per_edge", null);
        result.put("average_score_pieces_per_edge", null);
        result.put("anchor_count", anchors == 0 ? null : anchors);
        result.put("anchor_density", density);
        result.put(
                "zero_score_edge_fraction",
                density == null ? null : 1.0 - density);
        result.put("topology_seed", seed);
        result.put("temporal_seed", seed);
        result.put("score_seed", seed);
        return result;
    }

    private static Map<String, Object> systemRecord(int threads) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hostname", InetAddress.getLocalHost().getHostName());
        result.put("operating_system", System.getProperty("os.name"));
        result.put("kernel", System.getProperty("os.version"));
        result.put("cpu_model", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"));
        result.put("physical_cores", null);
        result.put("logical_cores", Runtime.getRuntime().availableProcessors());
        result.put("active_processor_count", Runtime.getRuntime().availableProcessors());
        result.put("jvm_input_arguments",
                java.lang.management.ManagementFactory.getRuntimeMXBean()
                        .getInputArguments());
        result.put("cpus_allowed_list", allowedCpuList());
        result.put("total_memory_bytes", null);
        result.put("compiler", "javac");
        result.put("compiler_version", System.getProperty("java.version"));
        result.put("runtime_version", System.getProperty("java.runtime.version"));
        result.put("thread_count", threads);
        result.put("process_id", ProcessHandle.current().pid());
        return result;
    }

    private static String allowedCpuList() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
                if (line.startsWith("Cpus_allowed_list:")) {
                    return line.substring("Cpus_allowed_list:".length()).trim();
                }
            }
        } catch (IOException ignored) {
            // CPU affinity is optional outside Linux procfs.
        }
        return null;
    }

    private static LoadedDataset loadDataset(String value) throws IOException {
        if (value.equals("sicsp-figure1")) {
            return new LoadedDataset(ExperimentDatasets.sicspFigureOne(),
                    "sicsp-figure1", "sicsp-figure1", 0L, null, null, null,
                    Map.of());
        }
        if (value.equals("demo") || value.equals("tiny")) {
            return new LoadedDataset(
                    ExperimentDatasets.demo(), "demo", "demo", 42L,
                    null, null, null, Map.of());
        }
        if (value.equals("timeout-test")) {
            return new LoadedDataset(
                    ExperimentDatasets.timeoutStress(), "demo",
                    "timeout-test", 42L, null, null, null, Map.of());
        }
        Map<String, Long> loadTimings = new LinkedHashMap<>();
        long totalLoadStarted = System.nanoTime();
        GeneratedGraphDataset loaded = new GeneratedGraphLoader().load(
                Path.of(value), loadTimings::put);
        Path directory = loaded.directory().toAbsolutePath().normalize();
        String datasetId = directory.getFileName().toString();
        Path parent = directory.getParent();
        if (parent != null && "variants".equals(parent.getFileName().toString())
                && parent.getParent() != null) {
            datasetId = parent.getParent().getFileName().toString();
        }
        long identityStarted = System.nanoTime();
        String graphChecksum = ManifestChecksum.graphChecksum(directory);
        String datasetChecksum = ManifestChecksum.datasetChecksum(directory);
        String temporalChecksum = ManifestChecksum.temporalAttributeChecksum(directory);
        loadTimings.put(
                "dataset_identity_checksums", System.nanoTime() - identityStarted);
        loadTimings.put("dataset_loading_total", System.nanoTime() - totalLoadStarted);
        return new LoadedDataset(
                loaded.graph(),
                datasetId,
                directory.toString(),
                loaded.manifest().seed(),
                graphChecksum,
                datasetChecksum,
                temporalChecksum,
                Map.copyOf(loadTimings));
    }

    private static List<QueryManifestEntry> generateQueries(LoadedDataset dataset, BenchOptions options)
            throws IOException {
        List<Integer> nodes = new ArrayList<>(nodeIds(dataset.graph));
        Random random = new Random(options.querySeed);
        LowerBoundGraph lower = new LowerBoundGraph(dataset.graph);
        PointForwardLabeling fastest = new PointForwardLabeling(dataset.graph);
        double horizonEnd = dataset.graph.edges().stream()
                .mapToDouble(edge -> edge.travelTimeFunction().domain().intervals().stream()
                        .mapToDouble(edu.ipcmax.core.function.Domain.Interval::end).max().orElse(0))
                .min().orElse(0);
        List<QueryManifestEntry> entries = new ArrayList<>();
        int attempts = 0;
        while (entries.size() < options.queryCount && attempts++ < options.queryCount * 100) {
            int source = nodes.get(random.nextInt(nodes.size()));
            int destination = nodes.get(random.nextInt(nodes.size()));
            if (source == destination) {
                continue;
            }
            double distance = lower.distancesFromSource(source).distance(destination);
            if (!Double.isFinite(distance)) {
                continue;
            }
            double maximumHorizonBudget = Domain.canonicalTime(
                    horizonEnd - (420 + options.windowMinutes));
            double fastestMin = Double.POSITIVE_INFINITY;
            double fastestMax = Double.NEGATIVE_INFINITY;
            if (options.budgetPolicy.equals("full-interval-feasible")) {
                boolean reachableThroughout = true;
                for (int departure = 420; departure <= 420 + options.windowMinutes; departure++) {
                    var labels = fastest.run(source, departure, maximumHorizonBudget);
                    if (!labels.reached(destination)) {
                        reachableThroughout = false;
                        break;
                    }
                    double travelTime = Domain.canonicalTime(labels.arrivalAt(destination) - departure);
                    fastestMin = Math.min(fastestMin, travelTime);
                    fastestMax = Math.max(fastestMax, travelTime);
                }
                if (!reachableThroughout) {
                    continue;
                }
            }
            double baseBudget = options.budgetPolicy.equals("full-interval-feasible")
                    ? fastestMax : distance;
            double budget = Domain.canonicalTime(Math.max(baseBudget, baseBudget * options.budgetSlack));
            if (budget > maximumHorizonBudget) {
                continue;
            }
            int index = entries.size();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("generated", true);
            if (Double.isFinite(fastestMin)) {
                metadata.put("fastest_travel_time_min", fastestMin);
                metadata.put("fastest_travel_time_max", fastestMax);
            }
            entries.add(new QueryManifestEntry(1, "generated-" + index, dataset.id,
                    source, destination, 420, 420 + options.windowMinutes, options.windowMinutes,
                    budget, options.budgetSlack, options.budgetPolicy, null,
                    distance, options.querySeed + index, metadata));
        }
        if (entries.size() != options.queryCount) {
            throw new IOException("could not generate requested reachable query count");
        }
        entries.sort(Comparator.comparingDouble(QueryManifestEntry::lowerBoundDistance)
                .thenComparingInt(QueryManifestEntry::source)
                .thenComparingInt(QueryManifestEntry::destination));
        List<QueryManifestEntry> binned = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            QueryManifestEntry entry = entries.get(index);
            int bin = Math.min(options.distanceBins - 1,
                    (int) ((long) index * options.distanceBins / entries.size()));
            binned.add(new QueryManifestEntry(
                    entry.schemaVersion(), entry.queryId(), entry.datasetId(), entry.source(),
                    entry.destination(), entry.intervalStart(), entry.intervalEnd(), entry.windowLength(),
                    entry.budget(), entry.budgetSlack(), entry.budgetPolicy(), bin,
                    entry.lowerBoundDistance(), entry.querySeed(), entry.metadata()));
        }
        entries = binned;
        if (options.queryManifestOutput != null) {
            Files.createDirectories(parent(options.queryManifestOutput));
            List<String> lines = new ArrayList<>();
            for (QueryManifestEntry entry : entries) {
                lines.add(JSON.writeValueAsString(entry));
            }
            Files.write(options.queryManifestOutput, lines, StandardCharsets.UTF_8);
        }
        return List.copyOf(entries);
    }

    private static Set<Integer> nodeIds(TDGraph graph) {
        Set<Integer> nodes = new TreeSet<>();
        for (Edge edge : graph.edges()) {
            nodes.add(edge.source());
            nodes.add(edge.target());
        }
        return nodes;
    }

    static String graphSemanticChecksum(TDGraph graph) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        for (Edge edge : graph.edges()) {
            StringBuilder canonicalEdge = new StringBuilder(256);
            canonicalEdge.append(edge.arcId()).append(':').append(edge.source()).append(':')
                    .append(edge.target()).append(':').append(edge.distance()).append(':');
            edge.travelTimeFunction().breakpoints().forEach(point -> canonicalEdge
                    .append(point.minute()).append('=').append(point.value()).append(','));
            canonicalEdge.append(':');
            edge.scoreFunction().intervals().forEach(piece -> canonicalEdge
                    .append(piece.startMinute()).append('=').append(piece.endMinute())
                    .append('=').append(piece.value()).append(','));
            canonicalEdge.append('\n');
            digest.update(canonicalEdge.toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Set<String> existingRunIds(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    ids.add(JSON.readTree(line).path("run").path("run_id").asText());
                }
            }
        }
        return Set.copyOf(ids);
    }

    private static Map<String, String> referenceChecksums(Path path) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode record = JSON.readTree(line);
                if (!record.path("run").path("warmup").asBoolean(false)) {
                    String query = record.path("query").path("query_id").asText();
                    String checksum = record.path("output").path("profile_checksum").asText(null);
                    if (checksum != null) {
                        result.putIfAbsent(query, checksum);
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void appendJson(Path path, Map<String, Object> record) throws IOException {
        String line = JSON.writeValueAsString(record) + System.lineSeparator();
        Files.writeString(path, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void appendCsv(Path path, Map<String, Object> record) throws IOException {
        boolean header = !Files.exists(path) || Files.size(path) == 0;
        @SuppressWarnings("unchecked") Map<String, Object> run = (Map<String, Object>) record.get("run");
        @SuppressWarnings("unchecked") Map<String, Object> query = (Map<String, Object>) record.get("query");
        @SuppressWarnings("unchecked") Map<String, Object> config = (Map<String, Object>) record.get("configuration");
        @SuppressWarnings("unchecked") Map<String, Object> status = (Map<String, Object>) record.get("status");
        @SuppressWarnings("unchecked") Map<String, Object> timing = (Map<String, Object>) record.get("timing_ns");
        @SuppressWarnings("unchecked") Map<String, Object> output = (Map<String, Object>) record.get("output");
        StringBuilder text = new StringBuilder();
        if (header) {
            text.append("run_id,query_id,algorithm,ablation,repetition,status_code,query_total_ns,profile_checksum\n");
        }
        text.append(run.get("run_id")).append(',').append(query.get("query_id")).append(',')
                .append(config.get("algorithm")).append(',').append(config.get("ablation")).append(',')
                .append(run.get("repetition")).append(',').append(status.get("status_code")).append(',')
                .append(timing.get("query_total")).append(',').append(output.get("profile_checksum")).append('\n');
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Map<String, Object> nullMap(List<String> names) {
        Map<String, Object> result = new LinkedHashMap<>();
        names.forEach(name -> result.put(name, null));
        return result;
    }

    private static boolean isFailure(ExperimentStatus status) {
        return status != ExperimentStatus.COMPLETED
                && status != ExperimentStatus.CERTIFIED_COMPLETE
                && status
                        != ExperimentStatus
                                .UNRESOLVED_NO_GENERATED_CANDIDATE
                && status != ExperimentStatus.NO_FEASIBLE_PATH
                && status != ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED
                && status != ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
    }

    private static AllFpReuseKey allFpReuseKey(
            BenchOptions options,
            ExperimentAlgorithm algorithm,
            QueryManifestEntry entry,
            boolean warmup,
            int repetition) {
        if (!options.sharedPreprocessing
                || options.internalForcedStatus != null
                || !algorithm.id().equals("allfp")) {
            return null;
        }
        return new AllFpReuseKey(
                entry.datasetId(),
                entry.source(),
                entry.destination(),
                entry.intervalStartExact().toPlainString(),
                entry.intervalEndExact().toPlainString(),
                warmup,
                repetition);
    }

    private static boolean reusableAllFpOutcome(Execution outcome) {
        return outcome != null
                && outcome.queryWorkerTerminated()
                && outcome.result() != null
                && outcome.result().profile() != null
                && outcome.result().status() != ExperimentStatus.ERROR
                && outcome.result().status() != ExperimentStatus.TIMEOUT
                && outcome.result().status() != ExperimentStatus.OUT_OF_MEMORY;
    }

    private static Execution copyExecution(
            Execution source,
            AlgorithmResult projected) {
        ExperimentInstrumentation instrumentation =
                new ExperimentInstrumentation();
        source.instrumentation().timings()
                .forEach(instrumentation::setTiming);
        source.instrumentation().counters()
                .forEach(instrumentation::addCounter);
        return new Execution(
                projected,
                instrumentation,
                source.runtime(),
                source.startMemory(),
                source.endMemory(),
                source.peakMemory(),
                source.startRss(),
                source.endRss(),
                source.peakRss(),
                source.queryWorkerTerminated());
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long processRssBytes() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
                if (line.startsWith("VmRSS:")) {
                    String[] fields = line.trim().split("\\s+");
                    return fields.length >= 2 ? Long.parseLong(fields[1]) * 1024L : -1;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // RSS is optional on operating systems without Linux procfs.
        }
        return -1;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean bean =
                java.lang.management.ManagementFactory
                        .getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean
                ? ((com.sun.management.OperatingSystemMXBean) bean)
                        .getProcessCpuTime()
                : -1;
    }

    private static Path parent(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.getParent() == null ? Path.of(".") : absolute.getParent();
    }

    private static String git(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(Arrays.asList(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private record LoadedDataset(
            TDGraph graph,
            String id,
            String path,
            long seed,
            String datasetPayloadChecksum,
            String datasetStructureChecksum,
            String temporalAttributeChecksum,
            Map<String, Long> loadTimings) {
    }

    private record AllFpReuseKey(
            String datasetId,
            int source,
            int destination,
            String intervalStart,
            String intervalEnd,
            boolean warmup,
            int repetition) {
    }

    private record AllFpReusableExecution(
            Execution execution,
            String sourceQueryId,
            double sourceBudget) {
    }

    private record Execution(
            AlgorithmResult result,
            ExperimentInstrumentation instrumentation,
            long runtime,
            long startMemory,
            long endMemory,
            long peakMemory,
            long startRss,
            long endRss,
            long peakRss,
            boolean queryWorkerTerminated) {
    }
}
