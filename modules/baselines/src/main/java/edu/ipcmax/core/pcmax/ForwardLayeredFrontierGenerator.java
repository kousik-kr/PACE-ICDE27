package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import edu.ipcmax.core.cache.SingleFlightCache;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.GraphPartitionMetadata;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.index.ScoreSupportIndex;
import edu.ipcmax.core.pcmax.PivotIndex.Pivot;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.TemporalProfileWork;

/**
 * Final non-recursive PACE candidate engine.
 */
public final class ForwardLayeredFrontierGenerator {
    private final TDGraph graph;
    private final PaceOptions options;
    private final EdgeTemporalSummaryStore summaries;
    private final GraphPartitionMetadata partition;
    private final ScoreSupportIndex scoreIndex;
    private final PaceExecutionMetrics metrics;
    private final PaceDiagnosticTrace diagnosticTrace;
    private volatile PaceGenerationResult lastResult;

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options) {
        this(
                graph,
                options,
                QueryPreparationIndexes.buildAllowingZero(graph),
                PaceExecutionMetrics.none(),
                PaceDiagnosticTrace.none());
    }

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes) {
        this(
                graph, options, indexes,
                PaceExecutionMetrics.none(),
                PaceDiagnosticTrace.none());
    }

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes,
            PaceExecutionMetrics metrics) {
        this(
                graph, options, indexes, metrics,
                PaceDiagnosticTrace.none());
    }

    public ForwardLayeredFrontierGenerator(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes,
            PaceExecutionMetrics metrics,
            PaceDiagnosticTrace diagnosticTrace) {
        this.graph = graph;
        this.options = options;
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        this.diagnosticTrace = java.util.Objects.requireNonNull(
                diagnosticTrace, "diagnostic trace");
        if (indexes == null) {
            throw new IllegalArgumentException(
                    "query preparation indexes are required");
        }
        summaries = indexes.edgeTemporalSummaries();
        partition = indexes.graphPartition();
        scoreIndex = indexes.scoreSupport();
        lastResult = emptyResult();
    }

    public synchronized PaceGenerationResult generate(QuerySpec query) {
        if (!metrics.enabled()) {
            return generateMeasured(query);
        }
        try (TemporalProfileWork.Scope ignored =
                TemporalProfileWork.install(
                        metrics::addCounterQuiet)) {
            return generateMeasured(query);
        }
    }

    private PaceGenerationResult generateMeasured(QuerySpec query) {
        observeMemory("memory_after_preprocess_used_heap_bytes");
        Domain rootDomain = query.departureDomain();
        double horizonEnd = Domain.canonicalTime(
                query.departureEndExact().doubleValue() + query.maxTravelTime());
        Domain queryHorizon = Domain.closed(
                query.departureStartExact().doubleValue(), horizonEnd);
        QueryLowerBounds lowerBounds =
                new QueryLowerBounds(graph, summaries);
        QueryLowerBounds.Distances fromSource;
        QueryLowerBounds.Distances toDestination;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FORWARD_BACKWARD_LABELING)) {
            fromSource = lowerBounds.truncatedDistancesFrom(
                    query.source(), query.maxTravelTime());
            toDestination = lowerBounds.truncatedDistancesTo(
                    query.destination(), query.maxTravelTime());
        }
        observeMemory(
                "memory_after_forward_backward_labels_used_heap_bytes");
        QueryCorridor corridor;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CORRIDOR_CONSTRUCTION)) {
            corridor = options.features().safeCorridorEnabled()
                    ? QueryCorridor.build(
                            graph,
                            lowerBounds,
                            partition,
                            query.source(),
                            query.destination(),
                            query.maxTravelTime(),
                            fromSource,
                            toDestination)
                    : QueryCorridor.unpruned(
                            graph,
                            partition,
                            query.source(),
                            query.destination(),
                            query.maxTravelTime());
        }
        observeMemory("memory_after_corridor_used_heap_bytes");
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.HORIZON_VALIDATION)) {
            requireCorridorCoverage(corridor, queryHorizon);
        }
        PivotIndex pivots;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.TOP_L_SELECTION)) {
            pivots = PivotSelector.select(
                    graph,
                    corridor,
                    lowerBounds,
                    partition,
                    summaries,
                    scoreIndex,
                    queryHorizon,
                    options.effectiveAnchorLimit(),
                    options.features()
                            .pivotDiversificationEnabled(),
                    metrics,
                    fromSource,
                    toDestination);
        }
        metrics.observeCounter(
                "selected_pivots", pivots.selected().size());
        diagnosticTrace.configure(
                pivots.selectedArcIds(), rootDomain);
        PaceWorkLedger ledger = new PaceWorkLedger(options);
        BoundedConnectorGenerator connectors =
                new BoundedConnectorGenerator(
                        graph,
                        corridor,
                        pivots,
                        lowerBounds,
                        summaries,
                        queryHorizon,
                        options,
                        ledger,
                        metrics);
        QueryScopedConnectorLabelStore labels =
                new QueryScopedConnectorLabelStore(
                        connectors,
                        fromSource,
                        toDestination,
                        metrics);
        SafeScoreUpperBound scoreUpperBound =
                new SafeScoreUpperBound(corridor, summaries);
        MutableStats stats = new MutableStats(
                corridor, pivots, options.threadCount(), metrics);
        IPCMaxParallelExecutor executor =
                new IPCMaxParallelExecutor(
                        options.threadCount());
        ReplayStore replayStore = new ReplayStore(
                query,
                queryHorizon,
                pivots,
                ledger,
                executor,
                stats);
        IncrementalFrontier completed =
                new IncrementalFrontier(
                        graph,
                        rootDomain,
                        query.maxTravelTime(),
                        query.source(),
                        query.destination(),
                        options,
                        ledger,
                        metrics,
                        executor);

        Map<StateKey, IncrementalFrontier> current =
                new TreeMap<>();
        StateKey identityKey = StateKey.of(
                query.source(), List.of(), new BitSet());
        IncrementalFrontier identityFrontier =
                stateFrontier(
                        query,
                        query.source(),
                        ledger,
                        executor);
        identityFrontier.insert(
                PartialCandidate.identity(
                        query.source(), rootDomain).profile(),
                "layer=0:identity");
        current.put(identityKey, identityFrontier);

        boolean queryWorkStopped = false;
        try (executor) {
            int maximumDepth = Math.min(
                    options.theta(), pivots.selected().size());
            for (int depth = 0;
                    depth <= maximumDepth && !current.isEmpty();
                    depth++) {
                PaceCancellation.checkpoint();
                Map<StateKey, IncrementalFrontier> next =
                        new TreeMap<>();
                outer:
                for (Map.Entry<StateKey, IncrementalFrontier> state :
                        current.entrySet()) {
                    PaceCancellation.checkpoint();
                    StateKey key = state.getKey();
                    for (CandidateProfile profile :
                            state.getValue().candidates().candidates()) {
                        PartialCandidate partial = partial(
                                query.source(), key, profile, pivots);
                        Domain finalDomain = residualDomain(
                                partial,
                                toDestination.distance(
                                        partial.endpoint()),
                                query.maxTravelTime());
                        if (!finalDomain.isEmpty()) {
                            String finalWork = workItem(
                                    depth,
                                    partial,
                                    "FINAL");
                            if (!ledger.reserve(
                                    PaceWorkKind.CONNECTOR_JOIN,
                                    finalWork + ":join")
                                    || !ledger.reserve(
                                            PaceWorkKind
                                                    .CONNECTOR_LABEL_GENERATION,
                                            finalWork + ":labels")) {
                                queryWorkStopped = true;
                                break outer;
                            }
                            stats.connectorCalls++;
                            ConnectorResult result = trackedConnect(
                                    labels,
                                    partial,
                                    query.destination(),
                                    finalDomain,
                                    query.maxTravelTime(),
                                    0,
                                    finalWork,
                                    stats);
                            reduceFinal(
                                    partial,
                                    finalDomain,
                                    result,
                                    completed,
                                    stats,
                                    replayStore);
                            if (ledger.capStatus().reached(
                                    PaceCapKind.QUERY_WORK_M_Q)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                        } else {
                            stats.residualBudgetRejections++;
                        }
                        if (depth >= maximumDepth) {
                            continue;
                        }
                        if (options.features()
                                .scoreUpperBoundEnabled()
                                && scoreUpperBound.cannotImprove(
                                partial.profile(),
                                completed.candidates(),
                                query.maxTravelTime())) {
                            stats.scoreUpperBoundRejections++;
                            continue;
                        }
                        List<PivotExpansion> expansions;
                        try (PaceExecutionMetrics.Timer ignoredPivot =
                                metrics.phase(
                                        PaceExecutionMetrics
                                                .PIVOT_EXPLORATION)) {
                            expansions = pivotExpansions(
                                    query,
                                    lowerBounds,
                                    toDestination,
                                    pivots,
                                    partial,
                                    depth,
                                    ledger,
                                    stats);
                        }
                        if (expansions.isEmpty()) {
                            if (ledger.capStatus().reached(
                                    PaceCapKind.QUERY_WORK_M_Q)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                            continue;
                        }
                        List<Callable<PivotConnector>> tasks =
                                new ArrayList<>();
                        for (PivotExpansion expansion : expansions) {
                            tasks.add(() -> {
                                stats.workerEntered();
                                try {
                                    ConnectorResult result =
                                            labels.prefixLabels(
                                                    partial.endpoint(),
                                                    expansion.pivot().source(),
                                                    expansion.entryDomain(),
                                                    partial.visitedVertices(),
                                                    partial.visitedEdges(),
                                                    expansion.connectorBudget(),
                                                    expansion.workItem());
                                    return new PivotConnector(
                                            expansion, result);
                                } finally {
                                    stats.workerExited();
                                }
                            });
                        }
                        List<PivotConnector> results;
                        if (options.threadCount() == 1) {
                            results = new ArrayList<>(tasks.size());
                            for (Callable<PivotConnector> task : tasks) {
                                try {
                                    results.add(task.call());
                                } catch (Exception failure) {
                                    throw new IllegalStateException(
                                            "pivot expansion failed",
                                            failure);
                                }
                            }
                        } else {
                            stats.parallelTasksStarted += tasks.size();
                            results = executor.invokeAllDeterministic(tasks);
                        }
                        for (PivotConnector result : results) {
                            stats.addConnector(result.result());
                            diagnosticTrace.connectorRequest(
                                    partial.endpoint(),
                                    result.expansion().pivot().source(),
                                    result.expansion().entryDomain(),
                                    result.expansion().connectorBudget(),
                                    result.result());
                            reducePivot(
                                    partial,
                                    result,
                                    next,
                                    stats,
                                    executor,
                                    replayStore);
                            if (ledger.capStatus().reached(
                                    PaceCapKind.QUERY_WORK_M_Q)) {
                                queryWorkStopped = true;
                                break outer;
                            }
                        }
                    }
                }
                current = next;
                stats.observeFrontiers(current, completed);
                long depthRetained = next.values().stream()
                        .mapToLong(frontier -> frontier.candidates().size())
                        .sum();
                metrics.observeCounter(
                        "retained_frontier_states_depth_" + (depth + 1),
                        depthRetained);
                if (queryWorkStopped) {
                    break;
                }
            }
        }
        PaceCapStatus caps = ledger.capStatus();
        CandidateSet rootFrontier = completed.candidates();
        diagnosticTrace.finalFrontier(rootFrontier);
        completed.releaseCaches();
        current.values().forEach(
                IncrementalFrontier::releaseCaches);
        boolean exhaustiveCoverage = pivots.selected().size() == pivots.scoreRelevantArcIds().size()
                && options.theta() >= pivots.selected().size();
        PaceGenerationOutcome outcome = PaceGenerationOutcome.resolve(
                options.policy(), !rootFrontier.isEmpty(), caps, exhaustiveCoverage,
                !options.singleLabelFastestConnectorEnabled()
                        || !rootFrontier.isEmpty()
                        || caps.any());
        String outputChecksum = outputChecksum(rootFrontier);
        PaceGenerationStats snapshot;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.STATISTICS)) {
            for (PaceWorkKind kind :
                    PaceWorkKind.values()) {
                metrics.addCounter(
                        "mq_" + kind.name().toLowerCase(
                                java.util.Locale.ROOT),
                        ledger.typedWork(kind));
            }
            snapshot = stats.snapshot(
                    completed,
                    connectors,
                    ledger,
                    outputChecksum);
        }
        replayStore.release();
        labels.releaseCaches();
        connectors.releaseCaches();
        observeMemory("memory_after_query_used_heap_bytes");
        lastResult = new PaceGenerationResult(
                rootFrontier,
                outcome.completion(),
                outcome.exactness(),
                caps,
                snapshot,
                corridor.checksum(),
                pivots.selectedArcIds(),
                outputChecksum);
        return lastResult;
    }

    public PaceGenerationResult lastResult() {
        return lastResult;
    }

    private ConnectorResult trackedConnect(
            QueryScopedConnectorLabelStore labels,
            PartialCandidate partial,
            int target,
            Domain rootDomain,
            double budget,
            double reservedLowerBound,
            String workItem,
            MutableStats stats) {
        Domain entryDomain = partial.profile().arrivalProfile()
                .imageDomain(rootDomain);
        double spentMinimum = partial.profile().arrivalProfile()
                .minimumTravelTime(rootDomain);
        double connectorBudget = Domain.canonicalTime(Math.max(
                0,
                budget - spentMinimum - reservedLowerBound));
        stats.workerEntered();
        try {
            ConnectorResult result = labels.suffixLabels(
                    partial.endpoint(),
                    target,
                    entryDomain,
                    partial.visitedVertices(),
                    partial.visitedEdges(),
                    connectorBudget,
                    workItem);
            diagnosticTrace.connectorRequest(
                    partial.endpoint(), target,
                    entryDomain, connectorBudget, result);
            stats.addConnector(result);
            return result;
        } finally {
            stats.workerExited();
        }
    }

    private List<PivotExpansion> pivotExpansions(
            QuerySpec query,
            QueryLowerBounds lowerBounds,
            QueryLowerBounds.Distances toDestination,
            PivotIndex pivots,
            PartialCandidate partial,
            int depth,
            PaceWorkLedger ledger,
            MutableStats stats) {
        List<PivotExpansion> result = new ArrayList<>();
        for (Pivot pivot : pivots.selected()) {
            if (partial.coveredPivot(pivot.canonicalRank())
                    || partial.visited(pivot.target())
                    || (partial.visited(pivot.source())
                        && partial.endpoint() != pivot.source())) {
                continue;
            }
            double connectorLower = lowerBounds.distanceWithin(
                    partial.endpoint(),
                    pivot.source(),
                    query.maxTravelTime());
            double suffix = toDestination.distance(
                    pivot.target());
            double requiredAfterConnector = Domain.canonicalTime(
                    lowerBounds.edgeWeight(pivot.arcId()) + suffix);
            if (!Double.isFinite(connectorLower)
                    || !Double.isFinite(requiredAfterConnector)) {
                stats.residualBudgetRejections++;
                continue;
            }
            double required = Domain.canonicalTime(
                    connectorLower + requiredAfterConnector);
            Domain feasible = residualDomain(
                    partial, required, query.maxTravelTime());
            if (feasible.isEmpty()) {
                stats.residualBudgetRejections++;
                continue;
            }
            String item = workItem(
                    depth,
                    partial,
                    "PIVOT-" + pivot.arcId());
            metrics.increment("pivot_tasks_considered");
            boolean admitted = ledger.reserve(
                    PaceWorkKind.PIVOT_TASK_ADMISSION,
                    item + ":admit");
            if (admitted) {
                admitted = ledger.reserve(
                        PaceWorkKind.CONNECTOR_LABEL_GENERATION,
                        item + ":labels");
            }
            if (admitted) {
                admitted = ledger.reserve(
                        PaceWorkKind.CONNECTOR_JOIN,
                        item + ":join");
            }
            if (!admitted) {
                metrics.increment("pivot_tasks_rejected");
                break;
            }
            metrics.increment("pivot_tasks_admitted");
            double spentMinimum = partial.profile().arrivalProfile()
                    .minimumTravelTime(feasible);
            double connectorBudget = Domain.canonicalTime(Math.max(
                    0,
                    query.maxTravelTime()
                            - spentMinimum
                            - requiredAfterConnector));
            result.add(new PivotExpansion(
                    pivot,
                    feasible,
                    partial.profile().arrivalProfile()
                            .imageDomain(feasible),
                    connectorBudget,
                    item));
            metrics.increment("pivot_sequences_explored");
            stats.connectorCalls++;
        }
        return List.copyOf(result);
    }

    private void reduceFinal(
            PartialCandidate partial,
            Domain rootDomain,
            ConnectorResult connectors,
            IncrementalFrontier completed,
            MutableStats stats,
            ReplayStore replayStore) {
        observeMemory(
                "memory_before_final_reduction_used_heap_bytes");
        List<ReplayRequest> requests = new ArrayList<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CANDIDATE_ASSEMBLY)) {
            for (CandidateProfile connector :
                    connectors.connectors()) {
                if (!replayStore.ledger.reserve(
                        PaceWorkKind.CANDIDATE_ASSEMBLY,
                        "complete:assemble:"
                                + connector.stablePathId())) {
                    break;
                }
                PathPointer pointer = PathPointer.concat(
                        partial.profile().pathPointer(),
                        connector.pathPointer());
                diagnosticTrace.connectorOutput(
                        pointer, rootDomain, true);
                String verificationWork =
                        "complete:verify:"
                                + pointer.stablePathId();
                if (!replayStore.ledger.reserve(
                        PaceWorkKind.PATH_VERIFICATION,
                        verificationWork)) {
                    break;
                }
                if (!isSimple(
                        replayStore.query.source(),
                        pointer,
                        replayStore.query.destination())) {
                    stats.invalidConnectors++;
                    continue;
                }
                ReplayRequest request = new ReplayRequest(
                        partial.profile(),
                        partial.endpoint(),
                        connector.pathPointer(),
                        pointer,
                        rootDomain,
                        replayStore.query.destination(),
                        "complete",
                        connector,
                        replayStore.query.destination(),
                        PathPointer.empty());
                if (replayStore.admitReplay(request)) {
                    requests.add(request);
                    diagnosticTrace.completeDescriptor(
                            pointer, rootDomain);
                }
            }
        }
        metrics.addCounter(
                "final_reduction_input_candidates",
                requests.size());
        metrics.addCounter(
                "final_reduction_distinct_path_ids",
                requests.stream()
                        .map(request ->
                                request.pointer()
                                        .stablePathId())
                        .distinct()
                        .count());
        List<Optional<CandidateProfile>> replayedBatch;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FINAL_REDUCTION)) {
            replayedBatch = replayStore.replayBatch(requests);
        }
        for (int index = 0; index < requests.size(); index++) {
            ReplayRequest request = requests.get(index);
            Optional<CandidateProfile> replayed =
                    replayStore.accept(
                            request,
                            replayedBatch.get(index));
            if (replayed.isEmpty()) {
                stats.invalidConnectors++;
                continue;
            }
            diagnosticTrace.replayedCandidate(
                    replayed.orElseThrow());
            metrics.increment("canonical_replay_accepts");
            stats.candidatesGenerated++;
            metrics.increment("complete_paths_generated");
            if (completed.insert(
                    replayed.orElseThrow(),
                    "root:" + request.pointer().stablePathId())) {
                stats.candidatesRetained++;
                metrics.increment("root_candidate_retained_offers");
            }
            diagnosticTrace.rootFrontier(
                    completed.candidates());
        }
        observeMemory(
                "memory_after_final_reduction_used_heap_bytes");
    }

    private void reducePivot(
            PartialCandidate partial,
            PivotConnector connectorResult,
            Map<StateKey, IncrementalFrontier> next,
            MutableStats stats,
            IPCMaxParallelExecutor executor,
            ReplayStore replayStore) {
        PivotExpansion expansion = connectorResult.expansion();
        Pivot pivot = expansion.pivot();
        Edge pivotEdge = graph.edges().get(pivot.arcId());
        List<ReplayRequest> requests = new ArrayList<>();
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CANDIDATE_ASSEMBLY)) {
            for (CandidateProfile connector :
                    connectorResult.result().connectors()) {
                if (!replayStore.ledger.reserve(
                        PaceWorkKind.CANDIDATE_ASSEMBLY,
                        "pivot-" + pivot.arcId()
                                + ":assemble:"
                                + connector.stablePathId())) {
                    break;
                }
                PathPointer suffix = PathPointer.concat(
                        connector.pathPointer(),
                        PathPointer.arc(pivot.arcId()));
                PathPointer pointer = PathPointer.concat(
                        partial.profile().pathPointer(),
                        suffix);
                diagnosticTrace.connectorOutput(
                        pointer, expansion.rootDomain(), false);
                if (!replayStore.ledger.reserve(
                        PaceWorkKind.PATH_VERIFICATION,
                        "pivot-" + pivot.arcId()
                                + ":verify:"
                                + pointer.stablePathId())) {
                    break;
                }
                if (!isSimple(
                        replayStore.query.source(),
                        pointer,
                        pivotEdge.target())) {
                    stats.invalidConnectors++;
                    continue;
                }
                ReplayRequest request = new ReplayRequest(
                        partial.profile(),
                        partial.endpoint(),
                        suffix,
                        pointer,
                        expansion.rootDomain(),
                        pivot.target(),
                        "pivot-" + pivot.arcId(),
                        connector,
                        pivot.source(),
                        PathPointer.arc(pivot.arcId()));
                if (replayStore.admitReplay(request)) {
                    requests.add(request);
                }
            }
        }
        Map<StateKey, List<CandidateProfile>> offersByState =
                new TreeMap<>();
        List<Optional<CandidateProfile>> replayedBatch =
                replayStore.replayBatch(requests);
        for (int index = 0; index < requests.size(); index++) {
            Optional<CandidateProfile> replayed =
                    replayStore.accept(
                            requests.get(index),
                            replayedBatch.get(index));
            if (replayed.isEmpty()) {
                stats.invalidConnectors++;
                continue;
            }
            stats.candidatesGenerated++;
            metrics.increment("partial_candidates_generated");
            CandidateProfile candidate = replayed.orElseThrow();
            BitSet covered = PivotCoverage.extend(
                    partial.coveredPivots(),
                    replayStore.pivots,
                    candidate.stablePathId());
            List<Integer> sequence =
                    new ArrayList<>(partial.pivotSequence());
            sequence.add(pivot.canonicalRank());
            StateKey key = StateKey.of(
                    pivot.target(),
                    sequence,
                    covered);
            offersByState.computeIfAbsent(
                    key, ignored -> new ArrayList<>()).add(candidate);
        }
        if (offersByState.isEmpty()) {
            return;
        }
        for (Map.Entry<StateKey, List<CandidateProfile>> entry :
                offersByState.entrySet()) {
            StateKey key = entry.getKey();
            IncrementalFrontier frontier = next.computeIfAbsent(
                    key,
                    ignored -> stateFrontier(
                            replayStore.query,
                            pivot.target(),
                            replayStore.ledger,
                            executor));
            stats.candidatesRetained += frontier.insertLayer(
                    entry.getValue(),
                    "layer=" + key.depth()
                            + ":pivot=" + pivot.arcId());
            long retainedAtDepth = next.entrySet().stream()
                    .filter(state -> state.getKey().depth() == key.depth())
                    .mapToLong(state -> state.getValue().candidates().size())
                    .sum();
            metrics.observeCounter(
                    "retained_frontier_states_depth_" + key.depth(),
                    retainedAtDepth);
            diagnosticTrace.partialStateFrontier(
                    frontier.candidates());
        }
    }

    private Domain residualDomain(
            PartialCandidate candidate,
            double requiredLowerBound,
            double budget) {
        if (!Double.isFinite(requiredLowerBound)) {
            return Domain.empty();
        }
        return candidate.profile().arrivalProfile()
                .domainWhereTravelTimeAtMost(
                        candidate.profile().domain(),
                        Domain.canonicalTime(
                                budget - requiredLowerBound));
    }

    private boolean isSimple(
            int source,
            PathPointer pointer,
            int expectedEndpoint) {
        BitSet visited = new BitSet();
        BitSet traversed = new BitSet();
        visited.set(source);
        int current = source;
        for (int arcId : pointer.arcIds()) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current
                    || traversed.get(arcId)
                    || visited.get(edge.target())) {
                return false;
            }
            traversed.set(arcId);
            visited.set(edge.target());
            current = edge.target();
        }
        return current == expectedEndpoint;
    }

    private PartialCandidate partial(
            int rootSource,
            StateKey key,
            CandidateProfile profile,
            PivotIndex pivots) {
        BitSet visited = new BitSet();
        BitSet visitedEdges = new BitSet();
        BitSet covered = PivotCoverage.extend(
                key.coveredPivots(),
                pivots,
                profile.stablePathId());
        visited.set(rootSource);
        int current = rootSource;
        for (int arcId : profile.stablePathId()) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) {
                throw new IllegalStateException(
                        "retained prefix is discontinuous");
            }
            if (visitedEdges.get(arcId)
                    || visited.get(edge.target())) {
                throw new IllegalStateException(
                        "retained prefix is not loopless");
            }
            visitedEdges.set(arcId);
            visited.set(edge.target());
            current = edge.target();
        }
        return new PartialCandidate(
                key.endpoint(),
                profile,
                visited,
                visitedEdges,
                key.usedPivots(),
                covered,
                key.pivotSequence(),
                key.depth(),
                Set.of());
    }

    private IncrementalFrontier stateFrontier(
            QuerySpec query,
            int endpoint,
            PaceWorkLedger ledger,
            IPCMaxParallelExecutor executor) {
        return new IncrementalFrontier(
                graph,
                query.departureDomain(),
                query.maxTravelTime(),
                query.source(),
                endpoint,
                options,
                ledger,
                metrics,
                executor);
    }

    private void requireCorridorCoverage(
            QueryCorridor corridor,
            Domain queryHorizon) {
        if (queryHorizon.difference(
                summaries.commonFunctionSupport()).isEmpty()) {
            metrics.increment("horizon_validated_by_common_support");
            return;
        }
        for (int arcId : corridor.directedArcIds()) {
            Edge edge = graph.edges().get(arcId);
            if (!queryHorizon.difference(
                    edge.travelTimeFunction().domain()).isEmpty()
                    || !queryHorizon.difference(
                            edge.scoreFunction().domain()).isEmpty()) {
                throw new PaceException(
                        PaceStatus.FUNCTION_HORIZON_EXCEEDED,
                        "FUNCTION_HORIZON_EXCEEDED: arc_id "
                                + arcId + " does not cover "
                                + queryHorizon);
            }
        }
    }

    private static String workItem(
            int depth,
            PartialCandidate candidate,
            String action) {
        return "layer=" + depth
                + ":candidate=" + candidate.candidateId()
                + ":action=" + action;
    }

    private static String outputChecksum(CandidateSet frontier) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
        update(digest, "PACE-CANDIDATE-OUTPUT-v1");
        for (CandidateProfile candidate : frontier.candidates()) {
            update(digest, candidate.stablePathId().toString());
            update(digest, candidate.domain().toString());
            candidate.arrivalProfile().breakpoints().forEach(point -> {
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                point.minute())).array());
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                point.value())).array());
            });
            candidate.scoreProfile().intervals().forEach(interval -> {
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                interval.startMinute())).array());
                digest.update(ByteBuffer.allocate(Long.BYTES)
                        .putLong(Double.doubleToLongBits(
                                interval.endMinute())).array());
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(interval.value()).array());
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
            MessageDigest digest,
            String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private void observeMemory(String counter) {
        if (!metrics.enabled()) {
            return;
        }
        metrics.observeCounter(
                counter,
                Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory());
    }

    private PaceGenerationResult emptyResult() {
        return new PaceGenerationResult(
                new CandidateSet(),
                PaceCompletion.UNRESOLVED_NO_GENERATED_CANDIDATE,
                PaceExactnessScope.NOT_CERTIFIED,
                PaceCapStatus.none(),
                PaceGenerationStats.empty(),
                "",
                List.of(),
                "");
    }

    /**
     * Query-local, bounded replay coordinator. Unique suffix computations may
     * run concurrently; their results are always returned and inserted in the
     * original deterministic producer order.
     */
    private final class ReplayStore {
        private static final int MAXIMUM_ENTRIES = 32_768;
        private static final int REPLAY_EXECUTION_BATCH_SIZE = 256;

        private final QuerySpec query;
        private final Domain queryHorizon;
        private final PivotIndex pivots;
        private final List<Integer> selectedPivotArcIds;
        private final Set<Integer> selectedPivotArcIdSet;
        private final PaceWorkLedger ledger;
        private final IPCMaxParallelExecutor executor;
        private final MutableStats stats;
        private final boolean cacheEnabled;
        private final Set<Long> replayWorkerThreads =
                ConcurrentHashMap.newKeySet();
        private final AtomicInteger activeReplayWorkers =
                new AtomicInteger();
        private final AtomicInteger maximumReplayWorkers =
                new AtomicInteger();
        private final LinkedHashMap<ReplayKey,
                Optional<CandidateProfile>> cache =
                new LinkedHashMap<>(256, 0.75f, true);
        private final SingleFlightCache<ReplayPrefixKey,
                Optional<CandidateProfile>> prefixCache =
                new SingleFlightCache<>(8_192);
        private long peakEntries;

        ReplayStore(
                QuerySpec query,
                Domain queryHorizon,
                PivotIndex pivots,
                PaceWorkLedger ledger,
                IPCMaxParallelExecutor executor,
                MutableStats stats) {
            this.query = query;
            this.queryHorizon = queryHorizon;
            this.pivots = pivots;
            this.selectedPivotArcIds =
                    List.copyOf(pivots.selectedArcIds());
            this.selectedPivotArcIdSet =
                    Set.copyOf(selectedPivotArcIds);
            this.ledger = ledger;
            this.executor = executor;
            this.stats = stats;
            this.cacheEnabled =
                    options.memoizationEnabled()
                    && options.features().profileCacheEnabled();
        }

        List<Optional<CandidateProfile>> replayBatch(
                List<ReplayRequest> requests) {
            observeMemory(
                    "memory_before_replay_used_heap_bytes");
            metrics.increment("canonical_replay_batches");
            metrics.addCounter(
                    "canonical_replay_requests", requests.size());
            if (requests.isEmpty()) {
                return List.of();
            }
            metrics.observeCounter(
                    "candidate_path_edges_in_batch",
                    requests.stream()
                            .mapToLong(request ->
                                    request.pointer().edgeCount())
                            .sum());
            if (!cacheEnabled) {
                metrics.addCounter(
                        "canonical_replay_unique_requests",
                        requests.size());
                metrics.addCounter(
                        "canonical_replay_cache_misses",
                        requests.size());
                return executeUnique(requests);
            }

            List<Optional<CandidateProfile>> answers =
                    new ArrayList<>(requests.size());
            for (int index = 0; index < requests.size(); index++) {
                answers.add(null);
            }
            LinkedHashMap<ReplayKey, PendingReplay> pending =
                    new LinkedHashMap<>();
            Set<ReplayPrefixKey> distinctPrefixes =
                    new java.util.HashSet<>();
            long hits = 0;
            for (int index = 0;
                    index < requests.size();
                    index++) {
                ReplayRequest request = requests.get(index);
                ReplayKey key = key(request);
                distinctPrefixes.add(prefixKey(request));
                Optional<CandidateProfile> cached =
                        cache.get(key);
                if (cached != null) {
                    answers.set(index, cached);
                    hits++;
                    continue;
                }
                PendingReplay existing = pending.get(key);
                if (existing != null) {
                    existing.indices().add(index);
                    hits++;
                    continue;
                }
                List<Integer> indices = new ArrayList<>();
                indices.add(index);
                pending.put(
                        key,
                        new PendingReplay(request, indices));
            }
            metrics.addCounter(
                    "canonical_replay_cache_hits", hits);
            metrics.addCounter(
                    "canonical_replay_cache_misses",
                    pending.size());
            metrics.addCounter(
                    "canonical_replay_unique_requests",
                    pending.size());
            metrics.addCounter(
                    "canonical_replay_repeated_prefixes",
                    Math.max(
                            0,
                            requests.size()
                                    - distinctPrefixes.size()));
            /* executeUnique canonicalizes only its execution order and
             * restores this pending-map order for the caller below. */
            List<PendingReplay> unique =
                    List.copyOf(pending.values());
            List<Optional<CandidateProfile>> computed =
                    executeUnique(unique.stream()
                            .map(PendingReplay::request)
                            .toList());
            int resultIndex = 0;
            for (Map.Entry<ReplayKey, PendingReplay> entry :
                    pending.entrySet()) {
                Optional<CandidateProfile> value =
                        computed.get(resultIndex++);
                for (int index : entry.getValue().indices()) {
                    answers.set(index, value);
                }
                cache.put(entry.getKey(), value);
                peakEntries = Math.max(peakEntries, cache.size());
                trimCache();
            }
            metrics.observeCounter(
                    "canonical_replay_cache_peak_entries",
                    peakEntries);
            return List.copyOf(answers);
        }

        Optional<CandidateProfile> accept(
                ReplayRequest request,
                Optional<CandidateProfile> replayed) {
            if (replayed.isEmpty()) {
                return replayed;
            }
            CandidateProfile flat = replayed.orElseThrow();
            int breakpoints =
                    flat.arrivalProfile().breakpoints().size()
                    + flat.scoreProfile().breakpoints().size();
            if (!ledger.acceptsBreakpoints(
                    breakpoints,
                    request.context() + ":"
                            + request.pointer().stablePathId())) {
                return Optional.empty();
            }
            return Optional.of(new CandidateProfile(
                    flat.domain(),
                    flat.arrivalProfile(),
                    flat.scoreProfile(),
                    request.pointer(),
                    flat.explicitAnchorCount(),
                    flat.pivotId(),
                    flat.compressed(),
                    flat.usedPivotArcIds()));
        }

        void release() {
            metrics.observeCounter(
                    "canonical_replay_cache_peak_entries",
                    peakEntries);
            cache.clear();
            metrics.observeCounter(
                    "canonical_prefix_cache_hits",
                    prefixCache.hits());
            metrics.observeCounter(
                    "canonical_prefix_cache_misses",
                    prefixCache.misses());
            metrics.observeCounter(
                    "canonical_prefix_cache_waits",
                    prefixCache.waits());
            metrics.observeCounter(
                    "canonical_prefix_cache_evictions",
                    prefixCache.evictions());
            metrics.observeCounter(
                    "canonical_prefix_cache_peak_entries",
                    prefixCache.peakSize());
            prefixCache.clear();
            metrics.checkpoint("query_local_caches_released");
        }

        boolean admitReplay(ReplayRequest request) {
            String item = request.context() + ":"
                    + request.pointer().stablePathId();
            if (!ledger.reserve(
                    PaceWorkKind.REPLAY_REQUEST,
                    item + ":replay")) {
                return false;
            }
            int admittedEdges = reusableConnectorProfile(request)
                    ? request.reusableTail().edgeCount()
                    : cacheEnabled
                            ? request.suffix().edgeCount()
                            : request.pointer().edgeCount();
            return ledger.reserveUnits(
                    PaceWorkKind.TEMPORAL_COMPOSITION,
                    admittedEdges,
                    item + ":compose");
        }

        private List<Optional<CandidateProfile>> executeUnique(
                List<ReplayRequest> requests) {
            if (requests.isEmpty()) {
                return List.of();
            }
            /*
             * Canonicalize the execution order independently of producer
             * order, then restore the caller's order.  Fixed-size batches keep
             * the worker queue and temporary profile memory bounded while the
             * executor still performs each batch in parallel.
             */
            List<Integer> order = new ArrayList<>(requests.size());
            for (int index = 0; index < requests.size(); index++) {
                order.add(index);
            }
            order.sort(Comparator.comparing(index ->
                    key(requests.get(index)).toString()));
            List<Optional<CandidateProfile>> restored =
                    new ArrayList<>(requests.size());
            for (int index = 0; index < requests.size(); index++) {
                restored.add(null);
            }
            int batches = 0;
            long largestBatch = 0;
            for (int start = 0;
                    start < order.size();
                    start += REPLAY_EXECUTION_BATCH_SIZE) {
                int end = Math.min(
                        order.size(),
                        start + REPLAY_EXECUTION_BATCH_SIZE);
                List<Callable<Optional<CandidateProfile>>> tasks =
                        new ArrayList<>(end - start);
                List<Integer> batchOrder =
                        order.subList(start, end);
                for (int sortedIndex : batchOrder) {
                    ReplayRequest request = requests.get(sortedIndex);
                    tasks.add(() -> {
                        stats.workerEntered();
                        replayWorkerThreads.add(
                                Thread.currentThread().getId());
                        int active =
                                activeReplayWorkers.incrementAndGet();
                        maximumReplayWorkers.accumulateAndGet(
                                active, Math::max);
                        metrics.observeCounter(
                                "final_reduction_observed_workers",
                                replayWorkerThreads.size());
                        metrics.observeCounter(
                                "final_reduction_maximum_active_workers",
                                maximumReplayWorkers.get());
                        try {
                            return replayUncached(request);
                        } finally {
                            activeReplayWorkers.decrementAndGet();
                            stats.workerExited();
                        }
                    });
                }
                List<Optional<CandidateProfile>> batchResult;
                if (options.threadCount() == 1
                        || tasks.size() < 2) {
                    batchResult = new ArrayList<>(tasks.size());
                    for (Callable<Optional<CandidateProfile>> task :
                            tasks) {
                        try {
                            batchResult.add(task.call());
                        } catch (Exception failure) {
                            throw new IllegalStateException(
                                    "canonical replay failed",
                                    failure);
                        }
                    }
                } else {
                    stats.parallelTasksStarted += tasks.size();
                    metrics.addCounter(
                            "parallel_canonical_replay_tasks",
                            tasks.size());
                    batchResult = executor.invokeAllDeterministic(tasks);
                }
                for (int offset = 0; offset < batchResult.size(); offset++) {
                    restored.set(
                            batchOrder.get(offset),
                            batchResult.get(offset));
                }
                batches++;
                largestBatch = Math.max(largestBatch, tasks.size());
            }
            metrics.addCounter(
                    "canonical_replay_execution_batches", batches);
            metrics.observeCounter(
                    "canonical_replay_execution_batch_peak", largestBatch);
            return List.copyOf(restored);
        }

        private Optional<CandidateProfile> replayUncached(
                ReplayRequest request) {
            observeMemory(
                    "memory_during_replay_used_heap_bytes");
            try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                    PaceExecutionMetrics.PATH_REPLAY)) {
                if (reusableConnectorProfile(request)) {
                    Domain reusableDomain = request.domain().intersection(
                            request.reusableConnector().domain());
                    if (reusableDomain.isEmpty()) {
                        return Optional.empty();
                    }
                    CandidateProfile reusable = request.reusableConnector()
                            .domain().equals(reusableDomain)
                                    ? request.reusableConnector()
                                    : request.reusableConnector()
                                            .restrict(reusableDomain);
                    metrics.increment(
                            "canonical_connector_profile_reuses");
                    metrics.addCounter(
                            "canonical_connector_profile_reused_edges",
                            reusable.edgeCount());
                    if (request.reusableTail().edgeCount() == 0) {
                        return Optional.of(reusable);
                    }
                    return CanonicalPathProfileBuilder.extend(
                            graph,
                            queryHorizon,
                            selectedPivotArcIdSet,
                            reusable,
                            query.source(),
                            request.reusableConnectorEndpoint(),
                            request.reusableTail().stablePathId(),
                            request.endpoint(),
                            reusableDomain,
                            query.maxTravelTime(),
                            -1,
                            false);
                }
                if (!cacheEnabled) {
                    return CanonicalPathProfileBuilder.replay(
                            graph,
                            queryHorizon,
                            selectedPivotArcIdSet,
                            request.pointer().stablePathId(),
                            query.source(),
                            request.endpoint(),
                            request.domain(),
                            query.maxTravelTime(),
                            -1,
                            false);
                }
                Optional<CandidateProfile> canonicalPrefix =
                        prefixCache.getOrCompute(
                                prefixKey(request),
                                () -> CanonicalPathProfileBuilder.replay(
                                        graph,
                                        queryHorizon,
                                        selectedPivotArcIdSet,
                                        request.prefix()
                                                .stablePathId(),
                                        query.source(),
                                        request.prefixEndpoint(),
                                        request.domain(),
                                        query.maxTravelTime(),
                                        -1,
                                        false));
                observePrefixCacheMetrics();
                if (canonicalPrefix.isEmpty()) {
                    return Optional.empty();
                }
                if (request.suffix().edgeCount() == 0) {
                    return canonicalPrefix;
                }
                return CanonicalPathProfileBuilder.extend(
                        graph,
                        queryHorizon,
                        selectedPivotArcIdSet,
                        canonicalPrefix.orElseThrow(),
                        query.source(),
                        request.prefixEndpoint(),
                        request.suffix().stablePathId(),
                        request.endpoint(),
                        request.domain(),
                        query.maxTravelTime(),
                        -1,
                        false);
            }
        }

        private boolean reusableConnectorProfile(
                ReplayRequest request) {
            if (request.reusableConnector() == null
                    || request.reusableTail() == null
                    || request.prefix().edgeCount() != 0) {
                return false;
            }
            List<Integer> expected = new ArrayList<>(
                    request.reusableConnector().edgeCount()
                            + request.reusableTail().edgeCount());
            expected.addAll(
                    request.reusableConnector().stablePathId());
            expected.addAll(
                    request.reusableTail().stablePathId());
            return expected.equals(request.pointer().stablePathId());
        }

        private ReplayPrefixKey prefixKey(
                ReplayRequest request) {
            return new ReplayPrefixKey(
                    request.prefix().stablePathId(),
                    query.source(),
                    request.prefixEndpoint(),
                    request.domain(),
                    queryHorizon,
                    Domain.canonicalTime(
                            query.maxTravelTime()),
                    selectedPivotArcIds,
                    TemporalPathVersion.hash(
                            graph,
                            request.prefix().stablePathId()),
                    "CANONICAL_PREFIX_REPLAY");
        }

        private void observePrefixCacheMetrics() {
            metrics.observeCounter(
                    "canonical_prefix_cache_hits",
                    prefixCache.hits());
            metrics.observeCounter(
                    "canonical_prefix_cache_misses",
                    prefixCache.misses());
            metrics.observeCounter(
                    "canonical_prefix_cache_waits",
                    prefixCache.waits());
            metrics.observeCounter(
                    "canonical_prefix_cache_evictions",
                    prefixCache.evictions());
            metrics.observeCounter(
                    "canonical_prefix_cache_peak_entries",
                    prefixCache.peakSize());
        }

        private ReplayKey key(ReplayRequest request) {
            return new ReplayKey(
                    request.pointer().stablePathId(),
                    query.source(),
                    request.endpoint(),
                    request.domain(),
                    queryHorizon,
                    Domain.canonicalTime(
                            query.maxTravelTime()),
                    selectedPivotArcIds,
                    TemporalPathVersion.hash(
                            graph,
                            request.pointer().stablePathId()),
                    "CANONICAL_FULL_REPLAY");
        }

        private void trimCache() {
            while (cache.size() > MAXIMUM_ENTRIES) {
                ReplayKey eldest =
                        cache.keySet().iterator().next();
                cache.remove(eldest);
                metrics.increment(
                        "canonical_replay_cache_evictions");
            }
        }
    }

    private record ReplayRequest(
            CandidateProfile prefix,
            int prefixEndpoint,
            PathPointer suffix,
            PathPointer pointer,
            Domain domain,
            int endpoint,
            String context,
            CandidateProfile reusableConnector,
            int reusableConnectorEndpoint,
            PathPointer reusableTail) {
    }

    private record ReplayKey(
            List<Integer> stablePathId,
            int source,
            int endpoint,
            Domain domain,
            Domain queryHorizon,
            double budget,
            List<Integer> selectedPivotArcIds,
            String temporalPathVersion,
            String replayMode) {
        private ReplayKey {
            stablePathId = List.copyOf(stablePathId);
            selectedPivotArcIds =
                    List.copyOf(selectedPivotArcIds);
        }
    }

    private record ReplayPrefixKey(
            List<Integer> stablePathId,
            int source,
            int endpoint,
            Domain domain,
            Domain queryHorizon,
            double budget,
            List<Integer> selectedPivotArcIds,
            String temporalPathVersion,
            String replayMode) {
        private ReplayPrefixKey {
            stablePathId = List.copyOf(stablePathId);
            selectedPivotArcIds =
                    List.copyOf(selectedPivotArcIds);
        }
    }

    private record PendingReplay(
            ReplayRequest request,
            List<Integer> indices) {
    }

    private record PivotExpansion(
            Pivot pivot,
            Domain rootDomain,
            Domain entryDomain,
            double connectorBudget,
            String workItem) {
    }

    private record PivotConnector(
            PivotExpansion expansion,
            ConnectorResult result) {
    }

    private record StateKey(
            int endpoint,
            List<Integer> pivotSequence,
            List<Integer> coveredPivotRanks)
            implements Comparable<StateKey> {
        static StateKey of(
                int endpoint,
                List<Integer> pivotSequence,
                BitSet covered) {
            return new StateKey(
                    endpoint,
                    pivotSequence,
                    covered.stream().boxed().toList());
        }

        private StateKey {
            pivotSequence = List.copyOf(pivotSequence);
            coveredPivotRanks =
                    List.copyOf(coveredPivotRanks);
            if (pivotSequence.stream().distinct().count()
                    != pivotSequence.size()
                    || !coveredPivotRanks.containsAll(
                            pivotSequence)) {
                throw new IllegalArgumentException(
                        "state pivot sequence must be distinct "
                                + "and physically covered");
            }
        }

        int depth() {
            return pivotSequence.size();
        }

        BitSet usedPivots() {
            BitSet result = new BitSet();
            pivotSequence.forEach(result::set);
            return result;
        }

        BitSet coveredPivots() {
            BitSet result = new BitSet();
            coveredPivotRanks.forEach(result::set);
            return result;
        }

        @Override
        public int compareTo(StateKey other) {
            int comparison = Integer.compare(
                    depth(), other.depth());
            if (comparison == 0) {
                comparison = Integer.compare(endpoint, other.endpoint);
            }
            if (comparison != 0) {
                return comparison;
            }
            comparison = compare(
                    pivotSequence, other.pivotSequence);
            return comparison != 0
                    ? comparison
                    : compare(
                            coveredPivotRanks,
                            other.coveredPivotRanks);
        }

        private static int compare(
                List<Integer> left,
                List<Integer> right) {
            int common = Math.min(left.size(), right.size());
            for (int index = 0; index < common; index++) {
                int comparison = Integer.compare(
                        left.get(index), right.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(
                    left.size(), right.size());
        }
    }

    private static final class MutableStats {
        private final long corridorNodes;
        private final long corridorEdges;
        private final long corridorCells;
        private final long scoreRelevantEdges;
        private final long selectedPivots;
        private final int requestedWorkers;
        private final PaceExecutionMetrics metrics;
        private final AtomicInteger activeWorkers =
                new AtomicInteger();
        private final AtomicInteger maximumWorkers =
                new AtomicInteger();
        private final Set<Long> workerThreads =
                ConcurrentHashMap.newKeySet();
        private long connectorCalls;
        private long validConnectors;
        private long invalidConnectors;
        private long candidatesGenerated;
        private long candidatesRetained;
        private long residualBudgetRejections;
        private long scoreUpperBoundRejections;
        private long parallelTasksStarted;
        private long frontierCells;
        private long peakFrontierSize;

        MutableStats(
                QueryCorridor corridor,
                PivotIndex pivots,
                int requestedWorkers,
                PaceExecutionMetrics metrics) {
            corridorNodes = corridor.vertexIds().size();
            corridorEdges = corridor.directedArcIds().size();
            corridorCells = corridor.activeCellIds().size();
            scoreRelevantEdges =
                    pivots.scoreRelevantArcIds().size();
            selectedPivots = pivots.selected().size();
            this.requestedWorkers = requestedWorkers;
            this.metrics = metrics;
            metrics.observeCounter("requested_workers", requestedWorkers);
        }

        void addConnector(ConnectorResult result) {
            validConnectors += result.connectors().size();
            invalidConnectors += result.invalidConnectors();
        }

        void workerEntered() {
            workerThreads.add(Thread.currentThread().getId());
            int active = activeWorkers.incrementAndGet();
            maximumWorkers.accumulateAndGet(active, Math::max);
            metrics.observeCounter("observed_workers_live", active);
        }

        void workerExited() {
            activeWorkers.decrementAndGet();
        }

        void observeFrontiers(
                Map<StateKey, IncrementalFrontier> states,
                IncrementalFrontier completed) {
            long cells = completed.cellCount();
            long peak = completed.peakSize();
            for (IncrementalFrontier frontier : states.values()) {
                cells += frontier.cellCount();
                peak = Math.max(peak, frontier.peakSize());
            }
            frontierCells = Math.max(frontierCells, cells);
            peakFrontierSize = Math.max(
                    peakFrontierSize, peak);
        }

        PaceGenerationStats snapshot(
                IncrementalFrontier completed,
                BoundedConnectorGenerator connectors,
                PaceWorkLedger ledger,
                String checksum) {
            peakFrontierSize = Math.max(
                    peakFrontierSize, completed.peakSize());
            frontierCells = Math.max(
                    frontierCells, completed.cellCount());
            return new PaceGenerationStats(
                    0,
                    scoreRelevantEdges,
                    selectedPivots,
                    validConnectors,
                    candidatesGenerated,
                    connectors.cacheHits(),
                    connectors.cacheMisses(),
                    parallelTasksStarted,
                    corridorNodes,
                    corridorEdges,
                    corridorCells,
                    scoreRelevantEdges,
                    selectedPivots,
                    connectorCalls,
                    ledger.connectorExpansions(),
                    validConnectors,
                    invalidConnectors,
                    ledger.connectorCapHits(),
                    candidatesGenerated,
                    candidatesRetained,
                    ledger.breakpointCapHits(),
                    ledger.queryWork(),
                    ledger.queryWorkCapHits(),
                    frontierCells,
                    peakFrontierSize,
                    connectors.cacheLookups(),
                    connectors.cacheWaits(),
                    requestedWorkers,
                    Math.max(
                            maximumWorkers.get(),
                            workerThreads.isEmpty() ? 0 : 1),
                    checksum);
        }
    }
}
