package edu.ipcmax.experiments;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import edu.ipcmax.experiments.framework.Ablation;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.core.pcmax.PaceEngineMode;

/** Parsed and validated command-line options for {@link PaceBench}. */
final class BenchOptions {
    String algorithm;
    Ablation ablation = Ablation.NONE;
    String dataset;
    Path queryFile;
    Path outputJsonl;
    Path outputCsv;
    String experimentName = "pace-experiment";
    int repetitions = 1;
    List<Integer> repetitionIndices = List.of();
    int warmupRuns;
    int threads = 1;
    int timeoutSeconds;
    int queryCleanupGraceSeconds = 30;
    boolean queryCleanupGraceSpecified;
    boolean timeoutSpecified;
    boolean unlimitedTimeoutExplicit;
    int preprocessingTimeoutSeconds;
    int memoryLimitMb;
    long seed = 42;
    boolean deterministic;
    boolean verifyOutput;
    boolean failFast;
    boolean resume;
    boolean sharedPreprocessing;
    int theta = 2;
    int anchorLimit = 32;
    int k = 16;
    int connectorLimitKc = 16;
    int frontierLimitKf = 16;
    long connectorExpansionCapMc = 5_000_000;
    int breakpointCapMb = 1_000_000;
    long queryWorkCapMq = 250_000_000;
    PaceEngineMode paceEngineMode = PaceEngineMode.SCALABLE;
    int rpqStepMinutes;
    int baselineK;
    long maxEnumeratedPaths = 100_000;
    long maxLabels = 1_000_000;
    long maxExpansions = 5_000_000;
    int maxFrontierFragments = 1_000_000;
    Path referenceJsonl;
    String referenceAlgorithm;
    boolean collectPhaseTimings;
    boolean collectMemory;
    boolean collectInternalCounters;
    boolean serializeProfile;
    Path profileOutputDir;
    Path anytimeOutputDir;
    java.util.List<Long> anytimeCheckpointsMs = java.util.List.of();
    /** Process-wide exact arithmetic selections; {@code null} leaves the executable's default. */
    String exactKernel;
    String exactReplayEngine;
    Integer exactBalancedLeafEdges;
    Integer exactBalancedMinimumArcs;
    int queryCount;
    long querySeed = 42;
    int distanceBins = 1;
    int windowMinutes = 10;
    double budgetSlack = 1.5;
    String budgetPolicy = "tight";
    Path queryManifestOutput;
    String[] commandLine;
    boolean internalWorker;
    int internalRepetition;
    boolean internalWarmup;
    Path internalConfigQueryFile;
    boolean internalGeneratedQueries;
    String internalForcedStatus;
    String internalForcedReason;
    Path internalProgressFile;
    final Map<String, String> objectiveOptions = new LinkedHashMap<>();

    static BenchOptions parse(String[] args) {
        BenchOptions options = new BenchOptions();
        options.commandLine = Arrays.copyOf(args, args.length);
        List<String> tokens = new ArrayList<>(List.of(args));
        for (int index = 0; index < tokens.size(); index++) {
            String option = tokens.get(index);
            switch (option) {
                case "--algorithm" -> options.algorithm = AlgorithmRegistry.canonicalId(value(tokens, ++index, option));
                case "--ablation" -> options.ablation = Ablation.parse(value(tokens, ++index, option));
                case "--dataset" -> options.dataset = value(tokens, ++index, option);
                case "--query-file" -> options.queryFile = Path.of(value(tokens, ++index, option));
                case "--output-jsonl" -> options.outputJsonl = Path.of(value(tokens, ++index, option));
                case "--output-csv" -> options.outputCsv = Path.of(value(tokens, ++index, option));
                case "--experiment-name" -> options.experimentName = value(tokens, ++index, option);
                case "--repetitions" -> options.repetitions = integer(tokens, ++index, option);
                case "--repetition-indices" -> options.repetitionIndices =
                        integerList(tokens, ++index, option);
                case "--warmup-runs" -> options.warmupRuns = integer(tokens, ++index, option);
                case "--threads" -> options.threads = integer(tokens, ++index, option);
                case "--timeout-seconds" -> {
                    String timeout = value(tokens, ++index, option);
                    options.timeoutSpecified = true;
                    if (timeout.equalsIgnoreCase("unlimited")) {
                        options.timeoutSeconds = 0;
                        options.unlimitedTimeoutExplicit = true;
                    } else {
                        options.timeoutSeconds = Integer.parseInt(timeout);
                        options.unlimitedTimeoutExplicit = false;
                    }
                }
                case "--query-cleanup-grace-seconds" -> {
                    options.queryCleanupGraceSeconds = integer(tokens, ++index, option);
                    options.queryCleanupGraceSpecified = true;
                }
                case "--preprocessing-timeout-seconds" ->
                        options.preprocessingTimeoutSeconds =
                                integer(tokens, ++index, option);
                case "--memory-limit-mb" -> options.memoryLimitMb = integer(tokens, ++index, option);
                case "--seed" -> options.seed = unsignedLong(tokens, ++index, option);
                case "--deterministic" -> options.deterministic = true;
                case "--verify-output" -> options.verifyOutput = true;
                case "--fail-fast" -> options.failFast = true;
                case "--resume" -> options.resume = true;
                case "--shared-preprocessing" ->
                        options.sharedPreprocessing = true;
                case "--theta" -> options.theta = integer(tokens, ++index, option);
                case "--anchor-limit", "--pivot-limit-l" ->
                        options.anchorLimit = integer(tokens, ++index, option);
                case "--k" -> {
                    options.k = integer(tokens, ++index, option);
                    options.connectorLimitKc = options.k;
                    options.frontierLimitKf = options.k;
                }
                case "--connector-limit-kc" ->
                        options.connectorLimitKc = integer(tokens, ++index, option);
                case "--frontier-limit-kf" -> {
                    options.frontierLimitKf = integer(tokens, ++index, option);
                    options.k = options.frontierLimitKf;
                }
                case "--connector-expansion-cap-mc" ->
                        options.connectorExpansionCapMc =
                                positiveLong(tokens, ++index, option);
                case "--breakpoint-cap-mb" ->
                        options.breakpointCapMb =
                                integer(tokens, ++index, option);
                case "--query-work-cap-mq" ->
                        options.queryWorkCapMq =
                                positiveLong(tokens, ++index, option);
                case "--pace-engine" ->
                        options.paceEngineMode = PaceEngineMode.valueOf(
                                value(tokens, ++index, option)
                                        .trim().toUpperCase(
                                                java.util.Locale.ROOT));
                case "--rpq-step-minutes" -> options.rpqStepMinutes = wholeMinutes(tokens, ++index, option);
                case "--baseline-k" -> options.baselineK = integer(tokens, ++index, option);
                case "--thop-index-dir", "--thop-partitions", "--thop-domain-start",
                        "--thop-domain-end", "--thop-lifecycle", "--sicsp-weights",
                        "--sicsp-weight-bound", "--iscope-opt2-breakpoint-threshold-minutes" -> options.objectiveOptions.put(
                                option.substring(2).replace('-', '_'), value(tokens, ++index, option));
                case "--egpi-max-proposal-pops", "--egpi-max-connector-searches",
                        "--egpi-max-exact-route-evaluations", "--egpi-max-expansion-frontier-routes",
                        "--egpi-max-registry-routes", "--egpi-max-pivots-per-constructed-route",
                        "--egpi-query-deadline-ms", "--egpi-structural-cache-capacity",
                        "--egpi-temporal-profile-cache-capacity", "--egpi-exploration-frontier-quota",
                        "--egpi-departure-chunks", "--egpi-proposal-batch-size",
                        "--egpi-exact-unit-batch-size", "--egpi-proposal-lookahead",
                        "--egpi-prefix-profile-cache-capacity", "--egpi-temporal-cache-max-bytes",
                        "--egpi-prefix-cache-max-bytes" ->
                        options.objectiveOptions.put(option.substring(2).replace('-', '_'),
                                value(tokens, ++index, option));
                case "--egpi-engine-profile", "--egpi-commit-policy", "--egpi-envelope-engine", "--egpi-result-form",
                        "--egpi-result-reserve-ms" ->
                        options.objectiveOptions.put(option.substring(2).replace('-', '_'),
                                value(tokens, ++index, option));
                case "--exact-kernel" -> options.exactKernel = value(tokens, ++index, option);
                case "--exact-replay-engine" -> options.exactReplayEngine = value(tokens, ++index, option);
                case "--exact-balanced-leaf-edges" -> options.exactBalancedLeafEdges = integer(tokens, ++index, option);
                case "--exact-balanced-minimum-arcs" -> options.exactBalancedMinimumArcs = integer(tokens, ++index, option);
                case "--anytime-output-dir" -> options.anytimeOutputDir = Path.of(value(tokens, ++index, option));
                case "--anytime-checkpoints-ms" -> {
                    java.util.List<Long> checkpoints = new java.util.ArrayList<>();
                    for (String part : value(tokens, ++index, option).split(",")) {
                        long ms = Long.parseLong(part.trim());
                        if (ms < 0) throw new IllegalArgumentException("--anytime-checkpoints-ms must be nonnegative");
                        checkpoints.add(ms);
                    }
                    options.anytimeCheckpointsMs = java.util.List.copyOf(checkpoints);
                }
                case "--max-enumerated-paths" -> options.maxEnumeratedPaths = positiveLong(tokens, ++index, option);
                case "--max-labels" -> options.maxLabels = positiveLong(tokens, ++index, option);
                case "--max-expansions" -> options.maxExpansions = positiveLong(tokens, ++index, option);
                case "--max-frontier-fragments" -> options.maxFrontierFragments = integer(tokens, ++index, option);
                case "--reference-jsonl" -> options.referenceJsonl = Path.of(value(tokens, ++index, option));
                case "--reference-algorithm" -> options.referenceAlgorithm = AlgorithmRegistry.canonicalId(value(tokens, ++index, option));
                case "--collect-phase-timings" -> options.collectPhaseTimings = true;
                case "--collect-memory" -> options.collectMemory = true;
                case "--collect-internal-counters" -> options.collectInternalCounters = true;
                case "--serialize-profile" -> options.serializeProfile = true;
                case "--profile-output-dir" -> options.profileOutputDir = Path.of(value(tokens, ++index, option));
                case "--query-count" -> options.queryCount = integer(tokens, ++index, option);
                case "--query-seed" -> options.querySeed = unsignedLong(tokens, ++index, option);
                case "--distance-bins" -> options.distanceBins = integer(tokens, ++index, option);
                case "--window-minutes" -> options.windowMinutes = wholeMinutes(tokens, ++index, option);
                case "--budget-slack" -> options.budgetSlack = decimal(tokens, ++index, option);
                case "--budget-policy" -> options.budgetPolicy = value(tokens, ++index, option);
                case "--query-manifest-output" -> options.queryManifestOutput = Path.of(value(tokens, ++index, option));
                case "--internal-worker" -> options.internalWorker = true;
                case "--internal-repetition" -> options.internalRepetition = integer(tokens, ++index, option);
                case "--internal-warmup" -> options.internalWarmup = true;
                case "--internal-config-query-file" -> options.internalConfigQueryFile =
                        Path.of(value(tokens, ++index, option));
                case "--internal-generated-queries" -> options.internalGeneratedQueries = true;
                case "--internal-forced-status" -> options.internalForcedStatus = value(tokens, ++index, option);
                case "--internal-forced-reason" -> options.internalForcedReason =
                        value(tokens, ++index, option);
                case "--internal-progress-file" -> options.internalProgressFile =
                        Path.of(value(tokens, ++index, option));
                case "--internal-original-command-line" -> options.commandLine = new String(
                        Base64.getDecoder().decode(value(tokens, ++index, option)), StandardCharsets.UTF_8)
                        .split("\\u0000", -1);
                default -> throw new IllegalArgumentException("unknown argument: " + option);
            }
        }
        options.validate();
        return options;
    }

    /**
     * Applies the requested exact-arithmetic selections to the process. Selections that were not
     * requested keep the executable's defaults, so a run without these flags is unaffected.
     */
    void applyExactSelections() {
        // The R22 EGPI profile bundles the R22 exact arithmetic; explicit flags override the bundle.
        boolean r22Profile = algorithm != null && algorithm.equals("pace-b-egpi-v1")
                && "r22".equalsIgnoreCase(objectiveOptions.getOrDefault("egpi_engine_profile", "r21"));
        if (r22Profile && exactKernel == null) exactKernel = "r22";
        if (r22Profile && exactReplayEngine == null) exactReplayEngine = "balanced";
        if (exactKernel != null) {
            edu.ipcmax.baselines.exact.ExactKernel.configure(edu.ipcmax.baselines.exact.ExactKernel.parse(exactKernel));
        }
        if (exactReplayEngine != null || exactBalancedLeafEdges != null || exactBalancedMinimumArcs != null) {
            var mode = exactReplayEngine == null
                    ? edu.ipcmax.baselines.exact.preference.ExactReplayEngine.mode()
                    : edu.ipcmax.baselines.exact.preference.ExactReplayEngine.parse(exactReplayEngine);
            edu.ipcmax.baselines.exact.preference.ExactReplayEngine.configure(mode,
                    exactBalancedLeafEdges == null
                            ? edu.ipcmax.baselines.exact.preference.ExactReplayEngine.leafEdges() : exactBalancedLeafEdges,
                    exactBalancedMinimumArcs == null
                            ? edu.ipcmax.baselines.exact.preference.ExactReplayEngine.minimumArcs() : exactBalancedMinimumArcs);
        }
    }

    private void validate() {
        if (algorithm == null) {
            throw new IllegalArgumentException("--algorithm is required");
        }
        AlgorithmRegistry.create(algorithm);
        if (algorithm.equals("pace-b-egpi-v1")) {
            if (!timeoutSpecified) timeoutSeconds = 10;
            if (!queryCleanupGraceSpecified) queryCleanupGraceSeconds = 2;
            if (unlimitedTimeoutExplicit || timeoutSeconds < 1) {
                throw new IllegalArgumentException("EGPI requires a finite outer query timeout");
            }
            if (threads > 24) throw new IllegalArgumentException("EGPI supports at most 24 workers");
            algorithmConfig().egpiOptions();
        }
        if (referenceAlgorithm != null) {
            AlgorithmRegistry.create(referenceAlgorithm);
        }
        if (dataset == null) {
            throw new IllegalArgumentException("--dataset is required");
        }
        if (queryFile == null && queryCount < 1) {
            throw new IllegalArgumentException("--query-file or --query-count is required");
        }
        if (outputJsonl == null) {
            throw new IllegalArgumentException("--output-jsonl is required");
        }
        if (repetitions < 1 || warmupRuns < 0 || threads < 1 || theta < 0) {
            throw new IllegalArgumentException("repetitions/threads must be positive and theta/warmups nonnegative");
        }
        if (repetitionIndices.stream().anyMatch(
                    value -> value < 0 || value >= repetitions)
                || repetitionIndices.stream().distinct().count()
                        != repetitionIndices.size()) {
            throw new IllegalArgumentException(
                    "--repetition-indices must be unique values in [0,repetitions)");
        }
        if (timeoutSeconds < 0
                || preprocessingTimeoutSeconds < 0
                || queryCleanupGraceSeconds < 0
                || memoryLimitMb < 0
                || maxFrontierFragments < 1) {
            throw new IllegalArgumentException("resource limits cannot be negative and frontier guard must be positive");
        }
        if (timeoutSpecified && timeoutSeconds == 0 && !unlimitedTimeoutExplicit) {
            throw new IllegalArgumentException(
                    "--timeout-seconds 0 is ambiguous; use the explicit token 'unlimited'");
        }
        if (distanceBins < 1 || windowMinutes < 1) {
            throw new IllegalArgumentException("distance bins and query window must be positive");
        }
        if (!Double.isFinite(budgetSlack) || budgetSlack <= 0) {
            throw new IllegalArgumentException("--budget-slack must be finite and positive");
        }
        if (!isLegacyPaceB() && ablation != Ablation.NONE) {
            throw new IllegalArgumentException("ablations require --algorithm pace-b");
        }
        if (algorithm.equals("pace-x") && ablation != Ablation.NONE) {
            throw new IllegalArgumentException("PACE-X cannot use heuristic ablations");
        }
        if (isLegacyPaceB()
                && (anchorLimit < 0
                    || connectorLimitKc < 1
                    || frontierLimitKf < 1
                    || connectorExpansionCapMc < 1
                    || breakpointCapMb < 1
                    || queryWorkCapMq < 1)) {
            throw new IllegalArgumentException(
                    "PACE-B requires L >= 0 and positive K_c, K_f, M_c, M_b, M_q");
        }
        if (algorithm.equals("rpq") && rpqStepMinutes < 1) {
            throw new IllegalArgumentException("RPQ requires a positive whole-minute --rpq-step-minutes");
        }
        if (List.of("iscope", "iscope-basic", "iscope-opt1", "iscope-optimized").contains(algorithm)
                && timeoutSeconds != 5 && timeoutSeconds != 10) {
            throw new IllegalArgumentException(
                    algorithm + " requires a declared --timeout-seconds 5 or 10 protocol");
        }
        if (objectiveOptions.containsKey("iscope_opt2_breakpoint_threshold_minutes")) {
            if (!algorithm.equals("iscope-optimized")) {
                throw new IllegalArgumentException(
                        "--iscope-opt2-breakpoint-threshold-minutes requires --algorithm iscope-optimized");
            }
            java.math.BigDecimal threshold = new java.math.BigDecimal(
                    objectiveOptions.get("iscope_opt2_breakpoint_threshold_minutes"));
            if (threshold.signum() <= 0) {
                throw new IllegalArgumentException("Opt2 breakpoint threshold must be positive");
            }
        }
        if (algorithm.equals("ksp-profile") && baselineK < 1) {
            throw new IllegalArgumentException("KSP requires --baseline-k >= 1");
        }
        if (algorithm.equals("thop-partitioned")) {
            requireObjectiveOption("thop_index_dir");
            requireObjectiveOption("thop_partitions");
            double start = Double.parseDouble(requireObjectiveOption("thop_domain_start"));
            double end = Double.parseDouble(requireObjectiveOption("thop_domain_end"));
            if (!Double.isFinite(start) || !Double.isFinite(end) || end < start) {
                throw new IllegalArgumentException("THop requires a finite ordered index domain");
            }
            String lifecycle = objectiveOptions.getOrDefault("thop_lifecycle", "load");
            if (!List.of("load", "build", "load-or-build").contains(lifecycle)) {
                throw new IllegalArgumentException("THop lifecycle must be load, build or load-or-build");
            }
            if (!sharedPreprocessing && (timeoutSeconds > 0 || memoryLimitMb > 0)) {
                throw new IllegalArgumentException("THop with resource limits requires --shared-preprocessing to retain prepared index state");
            }
            if (referenceAlgorithm != null || referenceJsonl != null || verifyOutput) {
                throw new IllegalArgumentException("THop fastest-profile output requires its objective-specific reference workflow");
            }
        }
        if (algorithm.equals("sicsp-native")) {
            requireObjectiveOption("sicsp_weights");
            double bound = Double.parseDouble(requireObjectiveOption("sicsp_weight_bound"));
            if (!Double.isFinite(bound) || bound < 0) {
                throw new IllegalArgumentException("native SICSP requires finite nonnegative --sicsp-weight-bound");
            }
            if (referenceAlgorithm != null || referenceJsonl != null || verifyOutput) {
                throw new IllegalArgumentException("native SICSP uses its separate weight-bounded reproduction workflow");
            }
        }
        for (String key : objectiveOptions.keySet()) {
            if ((key.startsWith("thop_") && !algorithm.equals("thop-partitioned"))
                    || (key.startsWith("sicsp_") && !algorithm.equals("sicsp-native"))
                    || (key.startsWith("egpi_") && !algorithm.equals("pace-b-egpi-v1"))) {
                throw new IllegalArgumentException("objective option " + key + " is incompatible with " + algorithm);
            }
        }
        if (!budgetPolicy.equals("tight") && !budgetPolicy.equals("full-interval-feasible")) {
            throw new IllegalArgumentException("unknown --budget-policy: " + budgetPolicy);
        }
        if (serializeProfile && profileOutputDir == null) {
            throw new IllegalArgumentException("--serialize-profile requires --profile-output-dir");
        }
        if (ablation == Ablation.NO_ANCHOR) {
            theta = 0;
        }
        if (ablation == Ablation.SERIAL) {
            threads = 1;
        }
        captureObjectiveInputChecksum("thop_partitions");
        captureObjectiveInputChecksum("sicsp_weights");
    }

    private void captureObjectiveInputChecksum(String key) {
        String value = objectiveOptions.get(key);
        if (value == null) return;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = java.nio.file.Files.newInputStream(Path.of(value))) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            objectiveOptions.put(key + "_sha256", java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("cannot hash objective input " + value, failure);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    AlgorithmConfig algorithmConfig() {
        return new AlgorithmConfig(
                algorithm,
                ablation,
                paceEngineMode,
                theta,
                anchorLimit,
                connectorLimitKc,
                frontierLimitKf,
                connectorExpansionCapMc,
                breakpointCapMb,
                queryWorkCapMq,
                threads,
                rpqStepMinutes,
                baselineK,
                maxEnumeratedPaths,
                maxLabels,
                maxExpansions,
                maxFrontierFragments,
                deterministic,
                seed,
                timeoutSeconds == 0
                        ? 0L
                        : TimeUnit.SECONDS.toNanos(timeoutSeconds),
                objectiveOptions);
    }

    Map<String, Object> normalized() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", algorithm);
        result.put("ablation", ablation.id());
        result.put("dataset", dataset);
        Path normalizedQueryFile = internalConfigQueryFile == null ? queryFile : internalConfigQueryFile;
        result.put("query_file", internalGeneratedQueries || normalizedQueryFile == null
                ? null : normalizedQueryFile.toAbsolutePath().normalize().toString());
        result.put("theta", theta);
        result.put("anchor_limit", anchorLimit);
        result.put("k", k);
        result.put("pivot_limit_l", anchorLimit);
        result.put("connector_limit_kc", connectorLimitKc);
        result.put("frontier_limit_kf", frontierLimitKf);
        result.put("connector_expansion_cap_mc", connectorExpansionCapMc);
        result.put("breakpoint_cap_mb", breakpointCapMb);
        result.put("query_work_cap_mq", queryWorkCapMq);
        result.put("pace_engine", paceEngineMode.name());
        result.put("threads", threads);
        result.put("rpq_step_minutes", rpqStepMinutes == 0 ? null : rpqStepMinutes);
        result.put("baseline_k", baselineK == 0 ? null : baselineK);
        result.put("timeout_seconds", timeoutSeconds);
        result.put("query_cleanup_grace_seconds", queryCleanupGraceSeconds);
        result.put("repetition_indices", repetitionIndices.isEmpty()
                ? null : repetitionIndices);
        result.put(
                "preprocessing_timeout_seconds",
                preprocessingTimeoutSeconds);
        result.put("shared_preprocessing", sharedPreprocessing);
        result.put("memory_limit_mb", memoryLimitMb);
        result.put("max_enumerated_paths", maxEnumeratedPaths);
        result.put("max_labels", maxLabels);
        result.put("max_expansions", maxExpansions);
        result.put("max_frontier_fragments", maxFrontierFragments);
        result.put("deterministic", deterministic);
        result.put("seed", Long.toUnsignedString(seed));
        result.put("verify_output", verifyOutput);
        result.put("reference_jsonl", referenceJsonl == null
                ? null : referenceJsonl.toAbsolutePath().normalize().toString());
        result.put("reference_algorithm", referenceAlgorithm);
        result.put("collect_phase_timings", collectPhaseTimings);
        result.put("collect_memory", collectMemory);
        result.put("collect_internal_counters", collectInternalCounters);
        result.put("serialize_profile", serializeProfile);
        result.put("objective_options", new java.util.TreeMap<>(objectiveOptions));
        if (exactKernel != null) result.put("exact_kernel_request", exactKernel);
        if (exactReplayEngine != null) result.put("exact_replay_engine_request", exactReplayEngine);
        if (exactBalancedLeafEdges != null) result.put("exact_balanced_leaf_edges_request", exactBalancedLeafEdges);
        if (exactBalancedMinimumArcs != null) result.put("exact_balanced_minimum_arcs_request", exactBalancedMinimumArcs);
        if (queryFile == null || internalGeneratedQueries) {
            result.put("query_count", queryCount);
            result.put("query_seed", Long.toUnsignedString(querySeed));
            result.put("distance_bins", distanceBins);
            result.put("window_minutes", windowMinutes);
            result.put("budget_slack", budgetSlack);
            result.put("budget_policy", budgetPolicy);
        }
        return result;
    }

    private String requireObjectiveOption(String key) {
        String value = objectiveOptions.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + key.replace('_', '-') + " is required for " + algorithm);
        }
        return value;
    }

    private boolean isLegacyPaceB() {
        return algorithm.equals("pace-b") || algorithm.equals("pace-b-legacy-r13");
    }

    private static String value(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return tokens.get(index);
    }

    private static int integer(List<String> tokens, int index, String option) {
        return Integer.parseInt(value(tokens, index, option));
    }

    private static long positiveLong(List<String> tokens, int index, String option) {
        long parsed = Long.parseLong(value(tokens, index, option));
        if (parsed < 1) {
            throw new IllegalArgumentException(option + " must be positive");
        }
        return parsed;
    }

    private static long unsignedLong(List<String> tokens, int index, String option) {
        return Long.parseUnsignedLong(value(tokens, index, option));
    }

    private static double decimal(List<String> tokens, int index, String option) {
        return Double.parseDouble(value(tokens, index, option));
    }

    private static int wholeMinutes(List<String> tokens, int index, String option) {
        double parsed = decimal(tokens, index, option);
        if (parsed != Math.rint(parsed) || parsed < 1 || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(option + " must be a positive whole-minute value");
        }
        return (int) parsed;
    }

    private static List<Integer> integerList(
            List<String> tokens,
            int index,
            String option) {
        String raw = value(tokens, index, option);
        if (raw.isBlank()) {
            throw new IllegalArgumentException(option + " cannot be empty");
        }
        try {
            return Arrays.stream(raw.split(",", -1))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    option + " must be a comma-separated integer list", failure);
        }
    }
}
