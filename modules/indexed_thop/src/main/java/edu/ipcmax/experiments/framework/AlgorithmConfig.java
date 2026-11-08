package edu.ipcmax.experiments.framework;

import java.util.Map;

import edu.ipcmax.core.pcmax.PaceEngineMode;
import edu.ipcmax.core.pcmax.PaceExecutionPolicy;
import edu.ipcmax.core.pcmax.PaceFeatures;
import edu.ipcmax.core.pcmax.PaceOptions;
import edu.ipcmax.core.pcmax.egpi.EgpiOptions;

/** Normalized effective configuration supplied to every algorithm. */
public record AlgorithmConfig(
        String algorithm,
        Ablation ablation,
        PaceEngineMode paceEngineMode,
        int theta,
        int pivotLimitL,
        int connectorLimitKc,
        int frontierLimitKf,
        long connectorExpansionCapMc,
        int breakpointCapMb,
        long queryWorkCapMq,
        int threads,
        int rpqStepMinutes,
        int baselineK,
        long maxEnumeratedPaths,
        long maxLabels,
        long maxExpansions,
        int maxFrontierFragments,
        boolean deterministic,
        long seed,
        long queryTimeLimitNanos,
        Map<String, String> objectiveOptions) {

    public AlgorithmConfig {
        objectiveOptions = objectiveOptions == null ? Map.of() : Map.copyOf(objectiveOptions);
    }

    /** Independent EGPI work budgets; historical L/K/theta are not reinterpreted. */
    public EgpiOptions egpiOptions() {
        EgpiOptions defaults = EgpiOptions.defaults();
        long deadline = Long.parseLong(objectiveOptions.getOrDefault(
                "egpi_query_deadline_ms", Long.toString(defaults.queryDeadlineMs())));
        if (queryTimeLimitNanos > 0) {
            long outerMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(queryTimeLimitNanos);
            if (objectiveOptions.containsKey("egpi_query_deadline_ms") && deadline > outerMillis) {
                throw new IllegalArgumentException("EGPI deadline exceeds the declared query timeout");
            }
            deadline = Math.min(deadline, outerMillis);
        }
        return new EgpiOptions(
                egpiInt("max_proposal_pops", defaults.maxProposalPops()),
                egpiInt("max_connector_searches", defaults.maxConnectorSearches()),
                egpiInt("max_exact_route_evaluations", defaults.maxExactRouteEvaluations()),
                egpiInt("max_expansion_frontier_routes", defaults.maxExpansionFrontierRoutes()),
                egpiInt("max_registry_routes", defaults.maxRegistryRoutes()),
                egpiInt("max_pivots_per_constructed_route", defaults.maxPivotsPerConstructedRoute()),
                deadline,
                egpiInt("structural_cache_capacity", defaults.structuralCacheCapacity()),
                egpiInt("temporal_profile_cache_capacity", defaults.temporalProfileCacheCapacity()),
                egpiInt("exploration_frontier_quota", defaults.explorationFrontierQuota()),
                egpiInt("departure_chunks", defaults.departureChunks()),
                egpiInt("proposal_batch_size", defaults.proposalBatchSize()),
                egpiInt("exact_unit_batch_size", defaults.exactUnitBatchSize()),
                egpiInt("proposal_lookahead", defaults.proposalLookahead()),
                egpiInt("prefix_profile_cache_capacity", defaults.prefixProfileCacheCapacity()),
                egpiLong("temporal_cache_max_bytes", defaults.temporalCacheMaxBytes()),
                egpiLong("prefix_cache_max_bytes", defaults.prefixCacheMaxBytes()));
    }

    /** Scheduling and envelope-engine policies; the budgets above are independent of them. */
    public edu.ipcmax.core.pcmax.egpi.EgpiEngineOptions egpiEngineOptions() {
        return edu.ipcmax.core.pcmax.egpi.EgpiEngineOptions.parse(
                objectiveOptions.get("egpi_engine_profile"),
                objectiveOptions.get("egpi_commit_policy"),
                objectiveOptions.get("egpi_envelope_engine"),
                objectiveOptions.get("egpi_result_form"),
                objectiveOptions.get("egpi_result_reserve_ms"));
    }

    private int egpiInt(String suffix, int defaultValue) {
        return Integer.parseInt(objectiveOptions.getOrDefault("egpi_" + suffix, Integer.toString(defaultValue)));
    }

    private long egpiLong(String suffix, long defaultValue) {
        return Long.parseLong(objectiveOptions.getOrDefault("egpi_" + suffix, Long.toString(defaultValue)));
    }

    /** Source-compatible constructor before objective-specific adapter options. */
    public AlgorithmConfig(String algorithm, Ablation ablation, PaceEngineMode paceEngineMode,
            int theta, int pivotLimitL, int connectorLimitKc, int frontierLimitKf,
            long connectorExpansionCapMc, int breakpointCapMb, long queryWorkCapMq,
            int threads, int rpqStepMinutes, int baselineK, long maxEnumeratedPaths,
            long maxLabels, long maxExpansions, int maxFrontierFragments,
            boolean deterministic, long seed, long queryTimeLimitNanos) {
        this(algorithm, ablation, paceEngineMode, theta, pivotLimitL, connectorLimitKc,
                frontierLimitKf, connectorExpansionCapMc, breakpointCapMb, queryWorkCapMq,
                threads, rpqStepMinutes, baselineK, maxEnumeratedPaths, maxLabels,
                maxExpansions, maxFrontierFragments, deterministic, seed,
                queryTimeLimitNanos, Map.of());
    }

    /** Compatibility constructor for callers predating internal query deadlines. */
    public AlgorithmConfig(
            String algorithm,
            Ablation ablation,
            PaceEngineMode paceEngineMode,
            int theta,
            int pivotLimitL,
            int connectorLimitKc,
            int frontierLimitKf,
            long connectorExpansionCapMc,
            int breakpointCapMb,
            long queryWorkCapMq,
            int threads,
            int rpqStepMinutes,
            int baselineK,
            long maxEnumeratedPaths,
            long maxLabels,
            long maxExpansions,
            int maxFrontierFragments,
            boolean deterministic,
            long seed) {
        this(
                algorithm, ablation, paceEngineMode, theta, pivotLimitL,
                connectorLimitKc, frontierLimitKf, connectorExpansionCapMc,
                breakpointCapMb, queryWorkCapMq, threads, rpqStepMinutes,
                baselineK, maxEnumeratedPaths, maxLabels, maxExpansions,
                maxFrontierFragments, deterministic, seed, 0L);
    }

    /** Compatibility constructor for the previous shared L/K CLI shape. */
    public AlgorithmConfig(
            String algorithm,
            Ablation ablation,
            int theta,
            int anchorLimit,
            int k,
            int threads,
            int rpqStepMinutes,
            int baselineK,
            long maxEnumeratedPaths,
            long maxLabels,
            long maxExpansions,
            int maxFrontierFragments,
            boolean deterministic,
            long seed) {
        this(
                algorithm,
                ablation,
                PaceEngineMode.SCALABLE,
                theta,
                anchorLimit,
                k,
                k,
                maxExpansions,
                maxFrontierFragments,
                maxExpansions,
                threads,
                rpqStepMinutes,
                baselineK,
                maxEnumeratedPaths,
                maxLabels,
                maxExpansions,
                maxFrontierFragments,
                deterministic,
                seed,
                0L);
    }

    /** Builds the feature-flagged PACE option object. */
    public PaceOptions paceOptions() {
        if (algorithm.equals("pace-b-egpi-v1") || algorithm.equals("PACE_B_EGPI_V1")) {
            throw new IllegalStateException("EGPI uses EgpiOptions, not legacy PaceOptions");
        }
        boolean bounded = algorithm.equals("pace-b") || algorithm.equals("pace-b-legacy")
                || algorithm.equals("PACE_B_LEGACY");
        if (!bounded) {
            return new PaceOptions(
                    PaceExecutionPolicy.PACE_X,
                    paceEngineMode,
                    theta,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED_WORK,
                    PaceOptions.UNBOUNDED,
                    PaceOptions.UNBOUNDED_WORK,
                    1,
                    true,
                    PaceFeatures.defaults(),
                    maxFrontierFragments);
        }
        int effectiveTheta =
                ablation == Ablation.NO_ANCHOR ? 0 : theta;
        int effectivePivotLimit =
                ablation == Ablation.ALL_ANCHORS
                        ? PaceOptions.UNBOUNDED
                        : pivotLimitL;
        int effectiveThreads =
                ablation == Ablation.SERIAL ? 1 : threads;
        boolean memo = ablation != Ablation.NO_MEMO;
        boolean safe = ablation != Ablation.NO_SAFE_DOM
                && ablation != Ablation.NO_COMPRESSION;
        boolean perCell = ablation != Ablation.GLOBAL_K;
        boolean representatives = ablation != Ablation.RANK_ONLY;
        boolean anchorLb = ablation != Ablation.NO_ANCHOR_LB;
        boolean compression = ablation != Ablation.NO_COMPRESSION;
        boolean merge = ablation != Ablation.NO_MERGE
                && ablation != Ablation.NO_COMPRESSION;
        boolean safeCorridor =
                ablation != Ablation.NO_SAFE_CORRIDOR;
        boolean pivotDiversification =
                ablation != Ablation.NO_PIVOT_DIVERSIFICATION;
        boolean connectorPortfolio =
                ablation == Ablation.LEGACY_CONNECTOR_PORTFOLIO;
        boolean connectorCache =
                memo && ablation != Ablation.NO_CONNECTOR_CACHE;
        boolean profileCache = memo;
        boolean scoreUpperBound =
                ablation != Ablation.NO_SCORE_UPPER_BOUND;
        PaceFeatures features = new PaceFeatures(
                safe,
                perCell,
                representatives,
                anchorLb,
                compression,
                merge,
                safeCorridor,
                pivotDiversification,
                connectorPortfolio,
                connectorCache,
                profileCache,
                scoreUpperBound);
        return new PaceOptions(
                PaceExecutionPolicy.PACE_B,
                paceEngineMode,
                effectiveTheta,
                effectivePivotLimit,
                connectorLimitKc,
                frontierLimitKf,
                connectorExpansionCapMc,
                breakpointCapMb,
                queryWorkCapMq,
                effectiveThreads,
                memo,
                features,
                maxFrontierFragments);
    }

    /** Historical alias for L. */
    public int anchorLimit() {
        return pivotLimitL;
    }

    /** Historical shared-K alias, now the frontier K_f. */
    public int k() {
        return frontierLimitKf;
    }

    /** Configured internal query limit, or the supplied default when absent. */
    public long queryTimeLimitOr(long defaultNanos) {
        if (queryTimeLimitNanos < 0 || defaultNanos <= 0) {
            throw new IllegalArgumentException("query time limits must be nonnegative");
        }
        return queryTimeLimitNanos == 0 ? defaultNanos : queryTimeLimitNanos;
    }
}
