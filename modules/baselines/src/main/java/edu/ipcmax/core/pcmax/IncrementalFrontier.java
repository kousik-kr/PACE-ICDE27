package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;

/**
 * Incremental normalization, deduplication, dominance, bounded retention, and
 * adjacent-fragment merging for one endpoint/layer state.
 */
public final class IncrementalFrontier {
    private final TDGraph graph;
    private final Domain domain;
    private final double budget;
    private final int source;
    private final int destination;
    private final PaceOptions options;
    private final PaceWorkLedger ledger;
    private final PaceExecutionMetrics metrics;
    private final IPCMaxParallelExecutor cellExecutor;
    private final FrontierCompressor.DominanceMemo
            dominanceMemo =
            new FrontierCompressor.DominanceMemo();
    private final Map<MaterializationKey, CandidateProfile>
            materializationCache =
            new LinkedHashMap<>(256, 0.75f, true);
    private final int materializationCacheLimit;
    private long materializationCachePeak;
    /** Immutable per-cell contributions reused when a cell is unaffected. */
    private Map<CellState, List<CandidateProfile>>
            materializedCellContributions = new IdentityHashMap<>();
    private CandidateSet retained = new CandidateSet();
    private List<CellState> cells;
    private long insertions;
    private long peakSize;

    public IncrementalFrontier(
            TDGraph graph,
            Domain domain,
            double budget,
            int source,
            int destination,
            PaceOptions options,
            PaceWorkLedger ledger) {
        this(
                graph, domain, budget, source, destination,
                options, ledger, PaceExecutionMetrics.none(),
                null);
    }

    public IncrementalFrontier(
            TDGraph graph,
            Domain domain,
            double budget,
            int source,
            int destination,
            PaceOptions options,
            PaceWorkLedger ledger,
            PaceExecutionMetrics metrics) {
        this(
                graph, domain, budget, source, destination,
                options, ledger, metrics, null);
    }

    IncrementalFrontier(
            TDGraph graph,
            Domain domain,
            double budget,
            int source,
            int destination,
            PaceOptions options,
            PaceWorkLedger ledger,
            PaceExecutionMetrics metrics,
            IPCMaxParallelExecutor cellExecutor) {
        this.graph = graph;
        this.domain = domain;
        this.budget = budget;
        this.source = source;
        this.destination = destination;
        this.options = options;
        this.ledger = ledger;
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        this.cellExecutor = cellExecutor;
        long requestedCacheEntries = options.policy()
                == PaceExecutionPolicy.PACE_X
                ? 16_384L
                : Math.max(
                        1_024L,
                        (long) options.effectiveFrontierLimit()
                                * 512L);
        this.materializationCacheLimit = (int) Math.min(
                16_384L,
                Math.min(
                        options.maxFrontierFragments(),
                        requestedCacheEntries));
        this.cells = domain.intervals().stream()
                .map(interval -> new CellState(
                        interval, List.of()))
                .toList();
    }

    /**
     * Inserts one exact candidate and immediately reduces the affected
     * frontier. Returns true when at least one fragment of the stable path is
     * retained.
     */
    public boolean insert(
            CandidateProfile candidate,
            String workItem) {
        metrics.increment("candidate_offer_attempts");
        if (!ledger.reserve(
                PaceWorkKind.CANDIDATE_OFFER,
                workItem + ":offer")) {
            metrics.increment("candidate_rejected_offers");
            return false;
        }
        metrics.increment("candidate_offers");
        metrics.addCounterQuiet("frontier_cells_revisited", cells.size());
        boolean duplicatePath = retained.candidates().stream().anyMatch(
                value -> value.stablePathId().equals(candidate.stablePathId()));
        if (duplicatePath) {
            metrics.increment("candidate_deduplicated_offers");
        }
        Domain accepted = candidate.domain().intersection(domain);
        if (accepted.isEmpty()) {
            metrics.increment("candidate_rejected_offers");
            return false;
        }
        CandidateProfile normalized = candidate.domain().equals(accepted)
                ? candidate : candidate.restrict(accepted);
        int breakpointCount =
                normalized.arrivalProfile().breakpoints().size()
                + normalized.scoreProfile().breakpoints().size();
        if (!ledger.acceptsBreakpoints(breakpointCount, workItem)) {
            metrics.increment("candidate_rejected_offers");
            return false;
        }
        if (retained.size() >= options.maxFrontierFragments()) {
            ledger.emergencyFrontierGuard(workItem);
            metrics.increment("candidate_rejected_offers");
            return false;
        }
        int cellsBefore = cells.size();
        try (PaceExecutionMetrics.Timer ignoredProfileMerge =
                     metrics.phase(
                             PaceExecutionMetrics.PROFILE_MERGE)) {
            metrics.increment("frontier_merge_updates");
            metrics.increment("frontier_compression_calls");
            if (!options.features().compressionEnabled()
                    || !options.features().perCellRetentionEnabled()) {
                insertUsingNonProductionAblation(normalized);
            } else {
                insertIncrementally(normalized, workItem);
            }
        } catch (PaceWorkLimitReachedException limit) {
            metrics.increment("candidate_rejected_offers");
            return false;
        }
        insertions++;
        peakSize = Math.max(peakSize, retained.size());
        metrics.addCounterQuiet(
                "frontier_cells_created",
                Math.max(0, cells.size() - cellsBefore));
        metrics.observeCounter(
                "frontier_peak_single_state_cells", cells.size());
        metrics.observeCounter(
                "frontier_peak_single_state_candidates", retained.size());
        boolean acceptedCandidate = retained.candidates().stream().anyMatch(
                value -> value.stablePathId().equals(
                normalized.stablePathId()));
        metrics.increment(acceptedCandidate
                ? "candidate_accepted_offers"
                : "candidate_rejected_offers");
        return acceptedCandidate;
    }

    /**
     * Reduces a deterministic same-layer offer cohort in its canonical
     * producer order. The cohort boundary lets the generator avoid repeated
     * state lookup and is deliberately semantics-preserving: every prefix is
     * the same incremental frontier that the batch oracle tests.
     */
    public long insertLayer(
            List<CandidateProfile> offers,
            String layerWorkItem) {
        java.util.Objects.requireNonNull(
                offers, "offers");
        metrics.increment("frontier_layer_batches");
        metrics.addCounter(
                "frontier_layer_batch_offers",
                offers.size());
        long retainedOffers = 0;
        for (int index = 0;
                index < offers.size();
                index++) {
            if (insert(
                    offers.get(index),
                    layerWorkItem + ":offer=" + index)) {
                retainedOffers++;
            }
            if (ledger.capStatus().reached(
                    PaceCapKind.QUERY_WORK_M_Q)) {
                break;
            }
        }
        return retainedOffers;
    }

    public CandidateSet candidates() {
        CandidateSet copy = new CandidateSet();
        copy.addAll(retained);
        copy.setTemporalCells(
                cells.stream().map(CellState::interval).toList());
        return copy;
    }

    public long insertions() {
        return insertions;
    }

    public long peakSize() {
        return peakSize;
    }

    public int cellCount() {
        return cells.size();
    }

    private void insertUsingNonProductionAblation(
            CandidateProfile normalized) {
        CandidateSet next = new CandidateSet();
        next.addAll(retained);
        next.add(normalized);
        retained = FrontierCompressor.compress(
                graph,
                next,
                domain,
                budget,
                options.effectiveFrontierLimit(),
                options.policy(),
                source,
                destination,
                options.features(),
                metrics);
        List<Domain.Interval> partition = retained.isEmpty()
                ? List.of()
                : ProfileCellPartition.cells(
                        domain,
                        retained.candidates(),
                        true,
                        metrics);
        List<CellState> rebuilt = new ArrayList<>();
        for (Domain.Interval interval : partition) {
            rebuilt.add(new CellState(
                    interval,
                    referencesForInterval(
                            retained.candidates(), interval)));
        }
        cells = List.copyOf(rebuilt);
    }

    private void insertIncrementally(
            CandidateProfile normalized,
            String workItem) {
        CandidateSet offered = new CandidateSet();
        offered.addAll(retained);
        offered.add(normalized);
        List<CandidateProfile> candidates =
                FrontierCompressor.normalizeCandidates(
                        offered, domain);
        /*
         * Charge and validate the new pairwise equality work, then construct
         * the same canonical partition as the batch oracle. Historical cuts
         * from candidates that are no longer retained must be allowed to
         * disappear; otherwise K_f=1 can preserve stale cells forever.
         */
        affectedCuts(
                normalized,
                retained.candidates(),
                workItem);
        List<Domain.Interval> canonicalPartition =
                ProfileCellPartition.cells(
                        domain,
                        candidates,
                        true,
                        metrics);
        Map<Domain.Interval, CellState> unchanged =
                new HashMap<>();
        for (CellState state : cells) {
            unchanged.put(state.interval(), state);
        }
        List<CellPlan> plans = new ArrayList<>();
        for (Domain.Interval interval :
                canonicalPartition) {
            CellState state = unchanged.get(interval);
            boolean offeredActive = !Domain.of(interval)
                    .intersection(normalized.domain())
                    .isEmpty();
            if (state != null && !offeredActive) {
                plans.add(CellPlan.ready(state));
            } else {
                plans.add(CellPlan.evaluate(interval));
            }
        }
        metrics.addCounter(
                "temporal_cells_split",
                Math.max(0,
                        canonicalPartition.size()
                                - cells.size()));
        metrics.addCounter(
                "temporal_cells_merged",
                Math.max(0,
                        cells.size()
                                - canonicalPartition.size()));
        metrics.addCounter(
                "temporal_cells_created",
                canonicalPartition.stream()
                        .filter(interval ->
                                !unchanged.containsKey(interval))
                        .count());

        List<CellState> updated =
                evaluatePlans(
                        plans, candidates, workItem);
        updated.sort(CELL_ORDER);
        /*
         * Preserve canonical semantic cut points even when the retained path-id
         * set happens to be unchanged across a boundary. Envelope extraction
         * reuses this partition, so score/arrival breakpoints may not be erased.
         * Adjacent fragments of one path are still merged below.
         */
        List<CellState> newCells = List.copyOf(updated);
        CandidateSet newRetained;
        metrics.observeCounter(
                "memory_during_profile_merge_used_heap_bytes",
                Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory());
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                     PaceExecutionMetrics.FRAGMENT_MERGE);
             PaceExecutionMetrics.Timer ignoredProfileMerge =
                     metrics.phase(PaceExecutionMetrics.PROFILE_MERGE)) {
            newRetained = rebuildRetained(
                    newCells);
        }
        cells = newCells;
        retained = newRetained;
    }

    /**
     * Evaluates independent cells concurrently only when a conservative
     * reservation bound proves that no worker can encounter M_q. Otherwise the
     * canonical sequential path is retained. Results are always reduced in
     * temporal-plan order.
     */
    private List<CellState> evaluatePlans(
            List<CellPlan> plans,
            List<CandidateProfile> candidates,
            String workItem) {
        long requested = plans.stream()
                .filter(CellPlan::requiresEvaluation)
                .count();
        boolean parallelSafe =
                cellExecutor != null
                && options.threadCount() > 1
                && requested > 1
                && worstCaseCellWork(
                        requested, candidates.size())
                <= ledger.remainingQueryWork();
        if (!parallelSafe) {
            List<CellState> result =
                    new ArrayList<>(plans.size());
            for (CellPlan plan : plans) {
                result.add(plan.requiresEvaluation()
                        ? evaluateCell(
                                candidates,
                                plan.interval(),
                                workItem)
                        : plan.ready());
            }
            return result;
        }
        List<Callable<CellState>> tasks =
                new ArrayList<>(plans.size());
        for (CellPlan plan : plans) {
            tasks.add(() -> plan.requiresEvaluation()
                    ? evaluateCell(
                            candidates,
                            plan.interval(),
                            workItem)
                    : plan.ready());
        }
        metrics.addCounter(
                "parallel_affected_cell_tasks", requested);
        return new ArrayList<>(
                cellExecutor.invokeAllDeterministic(tasks));
    }

    private static long worstCaseCellWork(
            long cells,
            int candidateCount) {
        try {
            long pairs = Math.multiplyExact(
                    (long) candidateCount,
                    Math.max(0L, candidateCount - 1L));
            long perCell = Math.addExact(
                    4L, Math.multiplyExact(6L, pairs));
            return Math.multiplyExact(cells, perCell);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Rebuilds only the global retained-domain index.
     *
     * <p>Each cell stores only source-profile references plus exact endpoint
     * ownership. Restricted pieces are cached by lineage/domain and the same
     * deterministic adjacent merge as the batch oracle is applied here.</p>
     */
    private CandidateSet rebuildRetained(
            List<CellState> states) {
        List<CandidateProfile> pieces =
                new ArrayList<>();
        Map<CellState, List<CandidateProfile>> nextContributions =
                new IdentityHashMap<>();
        long referenceCells = 0;
        for (CellState state : states) {
            referenceCells += state.references().size();
            List<CandidateProfile> contribution =
                    materializedCellContributions.get(state);
            if (contribution != null) {
                metrics.increment("sparse_cells_reused");
                metrics.addCounter(
                        "sparse_reused_fragments",
                        contribution.size());
            } else {
                metrics.increment("sparse_cells_rebuilt");
                contribution = materializeCellContribution(state);
            }
            contribution = List.copyOf(contribution);
            nextContributions.put(state, contribution);
            pieces.addAll(contribution);
        }
        materializedCellContributions = nextContributions;
        metrics.observeCounter(
                "sparse_contribution_cells_peak",
                nextContributions.size());
        metrics.addCounter(
                "sparse_contribution_fragments",
                pieces.size());
        metrics.addCounter(
                "fragment_reference_cells", referenceCells);
        metrics.addCounter(
                "fragment_reference_components", referenceCells);
        metrics.addCounter(
                "fragment_reference_cells_coalesced",
                0);
        metrics.observeCounter(
                "fragment_materialization_cache_peak_entries",
                materializationCachePeak);
        metrics.observeCounter(
                "retained_cell_references_peak",
                referenceCells);
        metrics.observeCounter(
                "retained_profile_fragments_peak",
                pieces.size());
        return FrontierCompressor.mergeCandidateFragments(
                pieces,
                metrics,
                ledger,
                "retained");
    }

    /** Materializes exactly one affected cell, preserving endpoint ownership. */
    private List<CandidateProfile> materializeCellContribution(
            CellState state) {
        List<CandidateProfile> contribution = new ArrayList<>();
        for (FrontierCompressor.RetainedCellReference reference :
                state.references()) {
            RetainedKey key = RetainedKey.of(reference.source());
            Domain retainedDomain = Domain.of(reference.cell());
            MaterializationKey materializationKey =
                    new MaterializationKey(key, retainedDomain);
            CandidateProfile materialized =
                    materializationCache.get(materializationKey);
            if (materialized == null) {
                CandidateProfile restricted = reference.source();
                if (!reference.source().domain().equals(retainedDomain)) {
                    if (!ledger.reserve(
                            PaceWorkKind.FRAGMENT_RESTRICTION,
                            "retained:" + key.stablePathId()
                                    + ":restrict")) {
                        throw PaceWorkLimitReachedException.INSTANCE;
                    }
                    metrics.increment("fragment_restrictions");
                    restricted = reference.source().restrict(retainedDomain);
                }
                if (!ledger.reserve(
                        PaceWorkKind.FRAGMENT_MATERIALIZATION,
                        "retained:" + key.stablePathId()
                                + ":materialize")) {
                    throw PaceWorkLimitReachedException.INSTANCE;
                }
                metrics.increment("fragment_materializations");
                materialized = new CandidateProfile(
                        restricted.domain(),
                        restricted.arrivalProfile(),
                        restricted.scoreProfile(),
                        restricted.pathPointer(),
                        restricted.explicitAnchorCount(),
                        restricted.pivotId(),
                        true,
                        restricted.usedPivotArcIds());
                materializationCache.put(
                        materializationKey, materialized);
                materializationCachePeak = Math.max(
                        materializationCachePeak,
                        materializationCache.size());
                trimMaterializationCache();
            } else {
                metrics.increment("fragment_materialization_cache_hits");
                metrics.increment("identical_fragment_domain_requests");
            }
            contribution.add(materialized);
        }
        return contribution;
    }

    /** Clears bounded query-local materialization and dominance state. */
    void releaseCaches() {
        metrics.observeCounter(
                "fragment_materialization_cache_peak_entries",
                materializationCachePeak);
        materializationCache.clear();
        materializedCellContributions = new IdentityHashMap<>();
        dominanceMemo.clear();
    }

    private void trimMaterializationCache() {
        while (materializationCache.size()
                > materializationCacheLimit) {
            MaterializationKey eldest =
                    materializationCache.keySet()
                            .iterator().next();
            materializationCache.remove(eldest);
            metrics.increment(
                    "fragment_materialization_cache_evictions");
        }
    }

    private List<Double> affectedCuts(
            CandidateProfile offered,
            List<CandidateProfile> existing,
            String workItem) {
        List<Double> cuts = new ArrayList<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.BREAKPOINT_PROCESSING)) {
            addBreakpoints(cuts, offered);
            for (CandidateProfile candidate : existing) {
                if (!candidate.domain()
                        .intersection(offered.domain()).isEmpty()) {
                    addBreakpoints(cuts, candidate);
                }
            }
            metrics.addCounter(
                    "breakpoints_processed", cuts.size());
        }
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.EQUALITY_ROOTS)) {
            for (CandidateProfile candidate : existing) {
                Domain overlap = candidate.domain()
                        .intersection(offered.domain());
                if (overlap.isEmpty()) {
                    continue;
                }
                if (!ledger.reserve(
                        PaceWorkKind.EQUALITY_ROOT_CHECK,
                        workItem + ":root:"
                                + candidate.stablePathId())) {
                    throw PaceWorkLimitReachedException.INSTANCE;
                }
                metrics.increment("candidate_pair_root_checks");
                List<Double> roots =
                        ProfileCellPartition.travelEqualityBreakpoints(
                                offered,
                                candidate,
                                overlap);
                cuts.addAll(roots);
                metrics.addCounter(
                        "equality_roots_created", roots.size());
            }
        }
        return ProfileCellPartition.uniqueSorted(cuts);
    }

    private static void addBreakpoints(
            List<Double> output,
            CandidateProfile candidate) {
        output.addAll(candidate.domain().breakpoints());
        candidate.arrivalProfile().breakpoints().forEach(
                breakpoint -> output.add(breakpoint.minute()));
        output.addAll(candidate.scoreProfile().breakpoints());
    }

    private CellState evaluateCell(
            List<CandidateProfile> candidates,
            Domain.Interval interval,
            String workItem) {
        if (!ledger.reserve(
                PaceWorkKind.AFFECTED_CELL_EVALUATION,
                workItem + ":cell:"
                        + Domain.canonicalTick(interval.start())
                        + ":" + Domain.canonicalTick(
                                interval.end()))) {
            throw PaceWorkLimitReachedException.INSTANCE;
        }
        metrics.increment("affected_cell_evaluations");
        List<FrontierCompressor.RetainedCellReference>
                references =
                FrontierCompressor
                        .retainPartitionCellReferences(
                        graph,
                        candidates,
                        interval,
                        domain,
                        budget,
                        options.effectiveFrontierLimit(),
                        options.policy(),
                        source,
                        destination,
                        options.features(),
                        dominanceMemo,
                        metrics,
                        ledger,
                        workItem);
        return new CellState(interval, references);
    }

    private static List<FrontierCompressor.RetainedCellReference>
            referencesForInterval(
            List<CandidateProfile> candidates,
            Domain.Interval interval) {
        Domain requested = Domain.of(interval);
        List<FrontierCompressor.RetainedCellReference>
                result = new ArrayList<>();
        for (CandidateProfile candidate : candidates) {
            Domain overlap = candidate.domain()
                    .intersection(requested);
            for (Domain.Interval component :
                    overlap.intervals()) {
                result.add(
                        new FrontierCompressor
                                .RetainedCellReference(
                                candidate, component));
            }
        }
        return List.copyOf(result);
    }

    private static final Comparator<CellState> CELL_ORDER =
            Comparator.comparingDouble(
                            (CellState value) ->
                                    value.interval().start())
                    .thenComparing(value ->
                            !value.interval().startInclusive())
                    .thenComparingDouble(
                            value -> value.interval().end());

    private record CellState(
            Domain.Interval interval,
            List<FrontierCompressor.RetainedCellReference>
                    references) {
        CellState {
            references = List.copyOf(references);
        }
    }

    private record CellPlan(
            CellState ready,
            Domain.Interval interval) {
        static CellPlan ready(CellState state) {
            return new CellPlan(state, null);
        }

        static CellPlan evaluate(
                Domain.Interval interval) {
            return new CellPlan(null, interval);
        }

        boolean requiresEvaluation() {
            return interval != null;
        }
    }

    private record RetainedKey(
            List<Integer> stablePathId,
            int explicitAnchorCount,
            Set<Integer> usedPivotArcIds,
            String arrivalFingerprint,
            String scoreFingerprint) {
        static RetainedKey of(
                CandidateProfile candidate) {
            return new RetainedKey(
                    candidate.stablePathId(),
                    candidate.explicitAnchorCount(),
                    candidate.usedPivotArcIds(),
                    baseFingerprint(
                            candidate.arrivalProfile()
                                    .fingerprint()),
                    baseFingerprint(
                            candidate.scoreProfile()
                                    .fingerprint()));
        }

        RetainedKey {
            stablePathId = List.copyOf(stablePathId);
            usedPivotArcIds = Set.copyOf(
                    usedPivotArcIds);
        }
    }

    private static String baseFingerprint(
            String fingerprint) {
        int restriction =
                fingerprint.indexOf("|restrict:");
        return restriction < 0
                ? fingerprint
                : fingerprint.substring(0, restriction);
    }

    private record MaterializationKey(
            RetainedKey retainedKey,
            Domain domain) {
    }

}
