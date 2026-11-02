package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.ipcmax.core.cache.SingleFlightCache;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;

/**
 * Connector generation for PACE-B and PACE-X.
 *
 * <p>Finalized PACE-B selects one static lower-bound-fastest structural path
 * per request, independent of the partial candidate's visited set, then
 * rejects the extension if that selected path conflicts. PACE-X retains its
 * exhaustive connector enumeration. The legacy portfolio is available only
 * through an explicitly identified diagnostic configuration.</p>
 */
public final class BoundedConnectorGenerator {
    private static final List<StreamKind> SCHEDULE = List.of(
            StreamKind.FAST,
            StreamKind.SCORE,
            StreamKind.DIVERSITY,
            StreamKind.FAST);

    private final TDGraph graph;
    private final QueryCorridor corridor;
    private final PivotIndex pivots;
    private final QueryLowerBounds lowerBounds;
    private final EdgeTemporalSummaryStore summaries;
    private final Domain queryHorizon;
    private final PaceOptions options;
    private final PaceWorkLedger ledger;
    private final PaceExecutionMetrics metrics;
    private final SingleFlightCache<ConnectorKey, ConnectorResult>
            connectorCache;
    private final SingleFlightCache<ProfileKey, Optional<CandidateProfile>>
            profileCache;
    private final String graphTemporalIdentity;
    private final double[] connectorLowerBoundWeights;
    private final Map<Integer, ConnectorHeuristic> targetHeuristics =
            new ConcurrentHashMap<>();
    private final Set<ConnectorKey> observedRequestKeys = new HashSet<>();
    private boolean requestKeyTrackingCapped;

    public BoundedConnectorGenerator(
            TDGraph graph,
            QueryCorridor corridor,
            PivotIndex pivots,
            QueryLowerBounds lowerBounds,
            EdgeTemporalSummaryStore summaries,
            Domain queryHorizon,
            PaceOptions options,
            PaceWorkLedger ledger) {
        this(
                graph, corridor, pivots, lowerBounds, summaries,
                queryHorizon, options, ledger, PaceExecutionMetrics.none());
    }

    public BoundedConnectorGenerator(
            TDGraph graph,
            QueryCorridor corridor,
            PivotIndex pivots,
            QueryLowerBounds lowerBounds,
            EdgeTemporalSummaryStore summaries,
            Domain queryHorizon,
            PaceOptions options,
            PaceWorkLedger ledger,
            PaceExecutionMetrics metrics) {
        this.graph = graph;
        this.corridor = corridor;
        this.pivots = pivots;
        this.lowerBounds = lowerBounds;
        this.summaries = summaries;
        this.queryHorizon = queryHorizon;
        this.options = options;
        this.ledger = ledger;
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        this.graphTemporalIdentity = Integer.toHexString(
                System.identityHashCode(graph))
                + "|"
                + TemporalPathVersion.hash(
                        graph, corridor.directedArcIds());
        this.connectorLowerBoundWeights = new double[graph.edgeCount()];
        java.util.Arrays.fill(
                connectorLowerBoundWeights,
                Double.POSITIVE_INFINITY);
        for (int arcId : corridor.directedArcIds()) {
            connectorLowerBoundWeights[arcId] =
                    PaceProfiles.minimumTravelTime(
                            graph.edges().get(arcId), queryHorizon);
        }
        this.connectorCache = new SingleFlightCache<>(4_096);
        this.profileCache = new SingleFlightCache<>(16_384);
        for (String counter : List.of(
                "connector_label_witness_reconstructions",
                "connector_label_witness_reconstruction_time_ns",
                "connector_selected_paths",
                "connector_unique_paths_per_request_max",
                "connector_static_label_expansions",
                "connector_cache_hits",
                "connector_cache_misses",
                "connector_structural_rejections",
                "connector_temporal_rejections",
                "connector_identity_requests",
                "connector_path_arcs",
                "connector_generation_time_ns",
                "connector_replay_time_ns",
                "connector_portfolio_branches_entered")) {
            metrics.observeCounter(counter, 0);
        }
    }

    public ConnectorResult connect(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            double residualBudget,
            String workItem) {
        return connect(
                source,
                target,
                entryDomain,
                prefixVisited,
                new BitSet(),
                residualBudget,
                workItem);
    }

    public ConnectorResult connect(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        metrics.increment("connector_requests");
        long started = System.nanoTime();
        ConnectorResult result;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.CONNECTOR_GENERATION)) {
            result = connectTimed(
                    source, target, entryDomain, prefixVisited,
                    prefixVisitedEdges,
                    residualBudget, workItem);
        } finally {
            metrics.addCounter(
                    "connector_generation_time_ns",
                    System.nanoTime() - started);
        }
        metrics.addCounter(
                "connector_selected_paths",
                result.connectors().size());
        metrics.observeCounter(
                "connector_unique_paths_per_request_max",
                result.connectors().size());
        metrics.addCounter(
                "connector_path_arcs",
                result.connectors().stream()
                        .mapToLong(candidate ->
                                candidate.stablePathId().size())
                        .sum());
        return result;
    }

    private ConnectorResult connectTimed(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        if (entryDomain == null
                || entryDomain.isEmpty()
                || prefixVisited == null
                || prefixVisitedEdges == null
                || !Double.isFinite(residualBudget)
                || residualBudget < 0) {
            throw new IllegalArgumentException(
                    "invalid connector request");
        }
        ConnectorKey key = new ConnectorKey(
                source,
                target,
                entryDomain.toString(),
                Domain.canonicalTime(residualBudget),
                java.util.HexFormat.of().formatHex(
                        prefixVisited.toByteArray()),
                java.util.HexFormat.of().formatHex(
                        prefixVisitedEdges.toByteArray()),
                queryHorizon.toString(),
                corridor.checksum(),
                pivots.version(),
                TemporalPathVersion.SEMANTICS_VERSION,
                connectorMode(),
                graphTemporalIdentity,
                options.effectiveConnectorLimit(),
                options.connectorExpansionCapMc());
        recordRequestKey(key);
        if (!options.memoizationEnabled()
                || !options.features().connectorCacheEnabled()) {
            return compute(
                    source,
                    target,
                    entryDomain,
                    prefixVisited,
                    prefixVisitedEdges,
                    residualBudget,
                    workItem);
        }
        ConnectorResult result = connectorCache.getOrCompute(
                key,
                () -> compute(
                        source,
                        target,
                        entryDomain,
                        prefixVisited,
                        prefixVisitedEdges,
                        residualBudget,
                        workItem));
        observeCacheMetrics();
        return result;
    }

    private ConnectorResult compute(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        if (options.singleLabelFastestConnectorEnabled()) {
            return singleLabelFastestConnector(
                    source,
                    target,
                    entryDomain,
                    prefixVisited,
                    prefixVisitedEdges,
                    residualBudget,
                    workItem);
        }
        if (options.policy() == PaceExecutionPolicy.PACE_B
                && options.features().connectorPortfolioEnabled()) {
            metrics.increment("connector_portfolio_branches_entered");
        }
        return options.policy() == PaceExecutionPolicy.PACE_X
                ? exhaustive(
                        source,
                        target,
                        entryDomain,
                        prefixVisited,
                        prefixVisitedEdges,
                        residualBudget)
                : bounded(
                        source,
                        target,
                        entryDomain,
                        prefixVisited,
                        prefixVisitedEdges,
                        residualBudget,
                        workItem);
    }

    private String connectorMode() {
        return options.connectorPolicyId();
    }

    /**
     * Selects one structural path under static lower-bound edge weights.
     * Prefix conflicts are checked after selection, as specified by PACE, so
     * a conflict never triggers second-best connector search.
     */
    private ConnectorResult singleLabelFastestConnector(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        metrics.increment("connector_single_label_requests");
        if (source == target) {
            metrics.increment("connector_identity_requests");
            Optional<CandidateProfile> identity = replay(
                    List.of(),
                    source,
                    target,
                    entryDomain,
                    residualBudget);
            if (identity.isEmpty()) {
                metrics.increment("connector_temporal_rejections");
            }
            return new ConnectorResult(
                    identity.stream().toList(), 0,
                    identity.isPresent() ? 0 : 1, false);
        }
        // The connector search is restricted to this query corridor and also
        // excludes selected pivot arcs. Reuse the corridor-scoped reverse
        // labels used by the exhaustive connector path: their whole-support
        // minima are admissible for the query-horizon weights below, and the
        // reverse search visits only arcs the connector search can use.
        metrics.increment("connector_static_dijkstra_searches");
        ConnectorHeuristic backwardLabels = connectorHeuristicTo(target);
        double sourceLowerBound = backwardLabels.distance(source);
        if (!Double.isFinite(sourceLowerBound)
                || sourceLowerBound
                        > Domain.canonicalTime(residualBudget)) {
            return new ConnectorResult(List.of(), 0, 0, false);
        }

        Map<Integer, StaticPathLabel> bestLabels = new HashMap<>();
        PriorityQueue<StaticPathLabel> queue = new PriorityQueue<>(
                BoundedConnectorGenerator::compareStaticLabels);
        StaticPathLabel root = new StaticPathLabel(
                source, 0, PathPointer.empty());
        bestLabels.put(source, root);
        queue.add(root);
        long expansions = 0;
        boolean capReached = false;
        StaticPathLabel selected = null;
        while (!queue.isEmpty()) {
            PaceCancellation.checkpoint();
            if (expansions >= options.connectorExpansionCapMc()) {
                capReached = true;
                break;
            }
            StaticPathLabel current = queue.poll();
            if (bestLabels.get(current.node()) != current) {
                continue;
            }
            expansions++;
            metrics.addCounterQuiet("connector_static_label_expansions", 1);
            if (current.node() == target) {
                selected = current;
                break;
            }
            for (Edge edge : corridor.outgoingEdges(current.node())) {
                metrics.addCounterQuiet("connector_static_label_scanned_arcs", 1);
                if (pivots.isSelectedPivot(edge.arcId())) {
                    continue;
                }
                double edgeWeight =
                        connectorLowerBoundWeights[edge.arcId()];
                if (!Double.isFinite(edgeWeight)) {
                    continue;
                }
                double nextWeight = Domain.canonicalTime(
                        current.lowerWeight() + edgeWeight);
                double remainingLowerBound =
                        backwardLabels.distance(edge.target());
                if (!Double.isFinite(remainingLowerBound)
                        || Domain.canonicalTime(
                                nextWeight + remainingLowerBound)
                                > Domain.canonicalTime(residualBudget)) {
                    continue;
                }
                PathPointer nextPath = PathPointer.concat(
                        current.path(),
                        PathPointer.arc(edge.arcId()));
                StaticPathLabel candidate = new StaticPathLabel(
                        edge.target(), nextWeight, nextPath);
                StaticPathLabel known = bestLabels.get(edge.target());
                if (known == null
                        || compareStaticLabels(candidate, known) < 0) {
                    bestLabels.put(edge.target(), candidate);
                    queue.add(candidate);
                }
            }
        }
        ledger.addConnectorExpansions(expansions);
        metrics.addCounter("connector_expansions", expansions);
        if (capReached) {
            ledger.connectorCapReached(workItem);
            return new ConnectorResult(List.of(), expansions, 0, true);
        }
        if (selected == null) {
            return new ConnectorResult(List.of(), expansions, 0, false);
        }

        long witnessStarted = System.nanoTime();
        List<Integer> arcIds = selected.path().stablePathId();
        metrics.addCounter(
                "connector_label_witness_reconstruction_time_ns",
                System.nanoTime() - witnessStarted);
        metrics.increment("connector_label_witness_reconstructions");
        if (!structurallyCompatibleSelectedPath(
                source, target, arcIds,
                prefixVisited, prefixVisitedEdges)) {
            metrics.increment("connector_structural_rejections");
            return new ConnectorResult(List.of(), expansions, 1, false);
        }
        Optional<CandidateProfile> profile = replay(
                arcIds, source, target, entryDomain, residualBudget);
        if (profile.isEmpty()) {
            metrics.increment("connector_temporal_rejections");
            return new ConnectorResult(List.of(), expansions, 1, false);
        }
        return new ConnectorResult(
                List.of(profile.orElseThrow()), expansions, 0, false);
    }

    private boolean structurallyCompatibleSelectedPath(
            int source,
            int target,
            List<Integer> arcIds,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges) {
        int current = source;
        BitSet visited = new BitSet();
        visited.set(source);
        for (int arcId : arcIds) {
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current
                    || pivots.isSelectedPivot(arcId)
                    || prefixVisitedEdges.get(arcId)
                    || prefixVisited.get(edge.target())
                    || visited.get(edge.target())) {
                return false;
            }
            visited.set(edge.target());
            current = edge.target();
        }
        return current == target;
    }

    private static int compareStaticLabels(
            StaticPathLabel left,
            StaticPathLabel right) {
        int byWeight = Double.compare(
                left.lowerWeight(), right.lowerWeight());
        if (byWeight != 0) {
            return byWeight;
        }
        int byArcCount = Integer.compare(
                left.path().edgeCount(),
                right.path().edgeCount());
        if (byArcCount != 0) {
            return byArcCount;
        }
        int byArcSequence = PathPointer.STABLE_PATH_ORDER.compare(
                left.path().stablePathId(),
                right.path().stablePathId());
        return byArcSequence != 0
                ? byArcSequence
                : Integer.compare(left.node(), right.node());
    }

    private ConnectorResult exhaustive(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget) {
        List<WeightedPath> paths = new ArrayList<>();
        Counter expansions = new Counter();
        BitSet visited = (BitSet) prefixVisited.clone();
        BitSet visitedEdges = (BitSet) prefixVisitedEdges.clone();
        visited.set(source);
        Map<Integer, Double> heuristic = heuristicTo(target);
        enumerateDepthFirst(
                source,
                target,
                residualBudget,
                0,
                visited,
                visitedEdges,
                PathPointer.empty(),
                paths,
                expansions,
                heuristic);
        paths.sort(weightedPathOrder());
        List<CandidateProfile> valid = new ArrayList<>();
        long invalid = 0;
        Set<List<Integer>> deduplicated = new HashSet<>();
        for (WeightedPath path : paths) {
            List<Integer> arcIds = path.pointer().stablePathId();
            if (!deduplicated.add(arcIds)) {
                continue;
            }
            Optional<CandidateProfile> profile = replay(
                    arcIds,
                    source,
                    target,
                    entryDomain,
                    residualBudget);
            if (profile.isPresent()) {
                valid.add(profile.orElseThrow());
            } else {
                invalid++;
            }
        }
        ledger.addConnectorExpansions(expansions.value);
        return new ConnectorResult(
                valid, expansions.value, invalid, false);
    }

    private void enumerateDepthFirst(
            int current,
            int target,
            double budget,
            double pathWeight,
            BitSet visited,
            BitSet visitedEdges,
            PathPointer path,
            List<WeightedPath> output,
            Counter expansions,
            Map<Integer, Double> heuristic) {
        PaceCancellation.checkpoint();
        expansions.value++;
        if (current == target) {
            output.add(new WeightedPath(path, pathWeight));
            return;
        }
        for (Edge edge : corridor.outgoingEdges(current)) {
            if (pivots.isSelectedPivot(edge.arcId())
                    || visitedEdges.get(edge.arcId())
                    || visited.get(edge.target())) {
                continue;
            }
            double next = Domain.canonicalTime(
                    pathWeight
                            + lowerBounds.edgeWeight(edge.arcId()));
            double remaining = heuristic.getOrDefault(
                    edge.target(), Double.POSITIVE_INFINITY);
            if (!Double.isFinite(remaining)) {
                continue;
            }
            double completion = Domain.canonicalTime(
                    next + remaining);
            if (completion > Domain.canonicalTime(budget)) {
                continue;
            }
            visited.set(edge.target());
            visitedEdges.set(edge.arcId());
            enumerateDepthFirst(
                    edge.target(),
                    target,
                    budget,
                    next,
                    visited,
                    visitedEdges,
                    PathPointer.concat(
                            path,
                            PathPointer.arc(edge.arcId())),
                    output,
                    expansions,
                    heuristic);
            visitedEdges.clear(edge.arcId());
            visited.clear(edge.target());
        }
    }

    private ConnectorResult bounded(
            int source,
            int target,
            Domain entryDomain,
            BitSet prefixVisited,
            BitSet prefixVisitedEdges,
            double residualBudget,
            String workItem) {
        Map<Integer, Double> heuristic = heuristicTo(target);
        double sourceHeuristic = heuristic.getOrDefault(
                source, Double.POSITIVE_INFINITY);
        if (!Double.isFinite(sourceHeuristic)
                || sourceHeuristic
                    > Domain.canonicalTime(residualBudget)) {
            return new ConnectorResult(List.of(), 0, 0, false);
        }
        Map<StreamKind, PriorityQueue<PathState>> streams =
                new java.util.EnumMap<>(StreamKind.class);
        List<StreamKind> enabledStreams =
                options.features().connectorPortfolioEnabled()
                        ? List.of(StreamKind.values())
                        : List.of(StreamKind.FAST);
        for (StreamKind kind : enabledStreams) {
            PriorityQueue<PathState> queue =
                    new PriorityQueue<>(stateOrder(kind));
            BitSet visited = (BitSet) prefixVisited.clone();
            BitSet visitedEdges =
                    (BitSet) prefixVisitedEdges.clone();
            visited.set(source);
            queue.add(PathState.root(
                    source,
                    visited,
                    visitedEdges,
                    sourceHeuristic));
            streams.put(kind, queue);
        }
        List<CandidateProfile> valid = new ArrayList<>();
        Set<List<Integer>> emitted = new HashSet<>();
        Map<Integer, Integer> emittedEdgeUse = new HashMap<>();
        long invalid = 0;
        long expansions = 0;
        int scheduleIndex = 0;
        boolean capReached = false;
        List<StreamKind> schedule =
                options.features().connectorPortfolioEnabled()
                        ? SCHEDULE
                        : List.of(StreamKind.FAST);
        while (hasWork(streams)
                && valid.size() < options.connectorLimitKc()) {
            PaceCancellation.checkpoint();
            StreamKind kind = schedule.get(
                    scheduleIndex++ % schedule.size());
            PriorityQueue<PathState> queue = streams.get(kind);
            if (queue.isEmpty()) {
                continue;
            }
            if (expansions >= options.connectorExpansionCapMc()) {
                capReached = true;
                break;
            }
            PathState state = queue.poll();
            expansions++;
            if (state.node() == target) {
                List<Integer> arcIds =
                        state.path().stablePathId();
                if (!emitted.add(arcIds)) {
                    continue;
                }
                Optional<CandidateProfile> profile = replay(
                        arcIds,
                        source,
                        target,
                        entryDomain,
                        residualBudget);
                if (profile.isPresent()) {
                    valid.add(profile.orElseThrow());
                    for (int arcId : arcIds) {
                        emittedEdgeUse.merge(arcId, 1, Integer::sum);
                    }
                } else {
                    invalid++;
                }
                continue;
            }
            for (Edge edge : corridor.outgoingEdges(state.node())) {
                if (pivots.isSelectedPivot(edge.arcId())
                        || state.visitedEdges().get(edge.arcId())
                        || state.visited().get(edge.target())) {
                    continue;
                }
                double nextWeight = Domain.canonicalTime(
                        state.lowerWeight()
                                + lowerBounds.edgeWeight(edge.arcId()));
                double remaining =
                        heuristic.getOrDefault(
                                edge.target(),
                                Double.POSITIVE_INFINITY);
                if (!Double.isFinite(remaining)) {
                    continue;
                }
                double completion = Domain.canonicalTime(
                        nextWeight + remaining);
                if (completion
                            > Domain.canonicalTime(residualBudget)) {
                    continue;
                }
                BitSet nextVisited =
                        (BitSet) state.visited().clone();
                BitSet nextVisitedEdges =
                        (BitSet) state.visitedEdges().clone();
                nextVisited.set(edge.target());
                nextVisitedEdges.set(edge.arcId());
                double scoreCost = Domain.canonicalTime(
                        state.scoreCost()
                                + lowerBounds.edgeWeight(edge.arcId())
                                / (1.0
                                   + summaries.summary(
                                           edge.arcId()).maximumScore()));
                double diversityCost = Domain.canonicalTime(
                        state.diversityCost()
                                + lowerBounds.edgeWeight(edge.arcId())
                                * (1.0 + emittedEdgeUse.getOrDefault(
                                        edge.arcId(), 0)));
                queue.add(new PathState(
                        edge.target(),
                        PathPointer.concat(
                                state.path(),
                                PathPointer.arc(edge.arcId())),
                        nextVisited,
                        nextVisitedEdges,
                        nextWeight,
                        completion,
                        scoreCost,
                        diversityCost));
            }
        }
        ledger.addConnectorExpansions(expansions);
        metrics.addCounter("connector_expansions", expansions);
        if (capReached) {
            ledger.connectorCapReached(workItem);
        }
        return new ConnectorResult(
                valid, expansions, invalid, capReached);
    }

    private Optional<CandidateProfile> replay(
            List<Integer> arcIds,
            int source,
            int target,
            Domain domain,
            double budget) {
        long replayStarted = System.nanoTime();
        try {
            return replayTimed(
                    arcIds, source, target, domain, budget);
        } finally {
            metrics.addCounter(
                    "connector_replay_time_ns",
                    System.nanoTime() - replayStarted);
        }
    }

    private Optional<CandidateProfile> replayTimed(
            List<Integer> arcIds,
            int source,
            int target,
            Domain domain,
            double budget) {
        ProfileKey key = new ProfileKey(
                arcIds,
                source,
                target,
                domain.toString(),
                queryHorizon.toString(),
                Domain.canonicalTime(budget),
                TemporalPathVersion.hash(graph, arcIds),
                pivots.version(),
                "CONNECTOR_REPLAY");
        Optional<CandidateProfile> result;
        if (options.memoizationEnabled()
                && options.features().profileCacheEnabled()) {
            result = profileCache.getOrCompute(key, () ->
                    replayUncached(
                            arcIds,
                            source,
                            target,
                            domain,
                            budget));
        } else {
            result = replayUncached(
                    arcIds,
                    source,
                    target,
                    domain,
                    budget);
        }
        if (result.isEmpty()) {
            return result;
        }
        CandidateProfile candidate = result.orElseThrow();
        int breakpoints = candidate.arrivalProfile()
                .breakpoints().size()
                + candidate.scoreProfile().breakpoints().size();
        if (!ledger.acceptsBreakpoints(
                breakpoints,
                "connector-profile:" + arcIds)) {
            return Optional.empty();
        }
        observeCacheMetrics();
        return result;
    }

    private Optional<CandidateProfile> replayUncached(
            List<Integer> arcIds,
            int source,
            int target,
            Domain domain,
            double budget) {
        /*
         * Connector profile construction is part of connector generation.
         * Do not also charge it to canonical full-candidate replay: the
         * launch report's top-level phase categories must be disjoint.
         */
        return CanonicalPathProfileBuilder.replay(
                graph,
                queryHorizon,
                Set.copyOf(pivots.selectedArcIds()),
                arcIds,
                source,
                target,
                domain,
                budget,
                -1,
                false);
    }

    public long cacheLookups() {
        return connectorCache.lookups() + profileCache.lookups();
    }

    public long cacheHits() {
        return connectorCache.hits() + profileCache.hits();
    }

    public long cacheMisses() {
        return connectorCache.misses() + profileCache.misses();
    }

    public long cacheWaits() {
        return connectorCache.waits() + profileCache.waits();
    }

    public long cacheEvictions() {
        return connectorCache.evictions()
                + profileCache.evictions();
    }

    public long cachePeakEntries() {
        return connectorCache.peakSize()
                + profileCache.peakSize();
    }

    /** Query-local graph view used to materialize immutable temporal labels. */
    TDGraph graph() {
        return graph;
    }

    /** Whether this query permits reusable memoized temporal labels. */
    boolean memoizationEnabled() {
        return options.memoizationEnabled()
                && options.features().connectorCacheEnabled();
    }

    String cacheIdentity() {
        return graphTemporalIdentity
                + "|horizon=" + queryHorizon
                + "|corridor=" + corridor.checksum();
    }

    String pivotContext() {
        return pivots.version();
    }

    String connectorPolicyVersion() {
        return connectorMode();
    }

    boolean singleConnectorPolicy() {
        return options.singleLabelFastestConnectorEnabled();
    }

    /** Releases all query-local connector and profile-index state. */
    public void releaseCaches() {
        observeCacheMetrics();
        metrics.observeCounter(
                "cache_evictions", cacheEvictions());
        metrics.observeCounter(
                "cache_peak_entries", cachePeakEntries());
        connectorCache.clear();
        profileCache.clear();
        targetHeuristics.clear();
        synchronized (this) {
            observedRequestKeys.clear();
        }
    }

    private synchronized void recordRequestKey(ConnectorKey key) {
        if (observedRequestKeys.contains(key)) {
            return;
        }
        if (observedRequestKeys.size() >= 4_096) {
            if (!requestKeyTrackingCapped) {
                requestKeyTrackingCapped = true;
                metrics.increment("connector_unique_request_key_tracking_capped");
            }
            return;
        }
        observedRequestKeys.add(key);
        metrics.increment("connector_unique_request_keys");
    }

    private void observeCacheMetrics() {
        metrics.observeCounter(
                "connector_cache_hits", connectorCache.hits());
        metrics.observeCounter(
                "connector_cache_misses", connectorCache.misses());
        metrics.observeCounter(
                "cache_hits", cacheHits());
        metrics.observeCounter(
                "cache_misses", cacheMisses());
        metrics.observeCounter(
                "cache_lookups", cacheLookups());
        metrics.observeCounter(
                "cache_waits", cacheWaits());
        metrics.observeCounter(
                "cache_evictions", cacheEvictions());
        metrics.observeCounter(
                "cache_peak_entries", cachePeakEntries());
    }

    private Map<Integer, Double> heuristicTo(int target) {
        return connectorHeuristicTo(target).distances();
    }

    private ConnectorHeuristic connectorHeuristicTo(int target) {
        return targetHeuristics.computeIfAbsent(
                target, this::buildHeuristic);
    }

    /**
     * Exact static reverse distances in the query connector graph. The search
     * touches only corridor incoming arcs and excludes selected pivots.
     */
    private ConnectorHeuristic buildHeuristic(int target) {
        metrics.increment("connector_target_label_builds");
        Map<Integer, Double> distances = new HashMap<>();
        Map<Integer, Integer> edgeCounts = new HashMap<>();
        Map<Integer, Integer> witnessArcs = new HashMap<>();
        PriorityQueue<HeuristicLabel> queue =
                new PriorityQueue<>();
        distances.put(target, 0.0);
        edgeCounts.put(target, 0);
        queue.add(new HeuristicLabel(target, 0.0, 0));
        while (!queue.isEmpty()) {
            PaceCancellation.checkpoint();
            HeuristicLabel current = queue.poll();
            if (current.distance() != distances.getOrDefault(
                    current.node(), Double.POSITIVE_INFINITY)
                    || current.edgeCount() != edgeCounts.getOrDefault(
                            current.node(), Integer.MAX_VALUE)) {
                continue;
            }
            metrics.addCounterQuiet("connector_target_label_expansions", 1);
            for (Edge edge : corridor.incomingEdges(current.node())) {
                metrics.addCounterQuiet("connector_target_label_scanned_arcs", 1);
                if (pivots.isSelectedPivot(edge.arcId())) {
                    continue;
                }
                double candidate = Domain.canonicalTime(
                        current.distance()
                                + lowerBounds.edgeWeight(edge.arcId()));
                int candidateEdges = Math.addExact(
                        current.edgeCount(), 1);
                double knownDistance = distances.getOrDefault(
                        edge.source(), Double.POSITIVE_INFINITY);
                int knownEdges = edgeCounts.getOrDefault(
                        edge.source(), Integer.MAX_VALUE);
                boolean improves = candidate < knownDistance
                        || (candidate == knownDistance
                            && candidateEdges < knownEdges);
                boolean witnessImproves = candidate == knownDistance
                        && candidateEdges == knownEdges
                        && edge.arcId() < witnessArcs.getOrDefault(
                                edge.source(), Integer.MAX_VALUE);
                if (improves) {
                    distances.put(edge.source(), candidate);
                    edgeCounts.put(edge.source(), candidateEdges);
                    witnessArcs.put(edge.source(), edge.arcId());
                    queue.add(new HeuristicLabel(
                            edge.source(), candidate, candidateEdges));
                } else if (witnessImproves) {
                    witnessArcs.put(edge.source(), edge.arcId());
                }
            }
        }
        metrics.addCounter(
                "connector_target_label_vertices", distances.size());
        return new ConnectorHeuristic(
                target,
                Map.copyOf(distances),
                Map.copyOf(edgeCounts),
                Map.copyOf(witnessArcs));
    }

    private static boolean hasWork(
            Map<StreamKind, PriorityQueue<PathState>> streams) {
        return streams.values().stream().anyMatch(queue -> !queue.isEmpty());
    }

    private static Comparator<WeightedPath> weightedPathOrder() {
        return Comparator
                .comparingDouble(WeightedPath::lowerWeight)
                .thenComparingInt(value -> value.pointer().edgeCount())
                .thenComparing(
                        value -> value.pointer().stablePathId(),
                        PathPointer.STABLE_PATH_ORDER);
    }

    private static Comparator<PathState> stateOrder(StreamKind kind) {
        return (left, right) -> {
            int comparison = switch (kind) {
                case FAST -> Double.compare(
                        left.estimatedCompletion(),
                        right.estimatedCompletion());
                case SCORE -> Double.compare(
                        left.scoreCost(), right.scoreCost());
                case DIVERSITY -> Double.compare(
                        left.diversityCost(),
                        right.diversityCost());
            };
            if (comparison == 0) {
                comparison = Double.compare(
                        left.lowerWeight(), right.lowerWeight());
            }
            if (comparison == 0) {
                comparison = Integer.compare(
                        left.path().edgeCount(),
                        right.path().edgeCount());
            }
            if (comparison == 0) {
                comparison = PathPointer.STABLE_PATH_ORDER.compare(
                        left.path().stablePathId(),
                        right.path().stablePathId());
            }
            return comparison != 0
                    ? comparison
                    : Integer.compare(left.node(), right.node());
        };
    }

    private enum StreamKind {
        FAST,
        SCORE,
        DIVERSITY
    }

    private record WeightedPath(
            PathPointer pointer,
            double lowerWeight) {
    }

    private record HeuristicLabel(
            int node,
            double distance,
            int edgeCount)
            implements Comparable<HeuristicLabel> {
        @Override
        public int compareTo(HeuristicLabel other) {
            int comparison = Double.compare(
                    distance, other.distance);
            if (comparison != 0) {
                return comparison;
            }
            int byEdges = Integer.compare(
                    edgeCount, other.edgeCount);
            return byEdges != 0
                    ? byEdges
                    : Integer.compare(node, other.node);
        }
    }

    private record ConnectorHeuristic(
            int target,
            Map<Integer, Double> distances,
            Map<Integer, Integer> edgeCounts,
            Map<Integer, Integer> witnessArcs) {
        double distance(int source) {
            return distances.getOrDefault(
                    source, Double.POSITIVE_INFINITY);
        }

        List<Integer> witnessArcIds(
                TDGraph graph,
                int source,
                int expectedTarget) {
            if (target != expectedTarget
                    || !Double.isFinite(distance(source))) {
                return List.of();
            }
            int expectedEdges = edgeCounts.getOrDefault(source, -1);
            if (expectedEdges < 0) {
                return List.of();
            }
            List<Integer> result = new ArrayList<>(expectedEdges);
            int current = source;
            while (current != target) {
                Integer arcId = witnessArcs.get(current);
                if (arcId == null) {
                    throw new IllegalStateException(
                            "missing connector witness for vertex " + current);
                }
                Edge edge = graph.edges().get(arcId);
                if (edge.source() != current) {
                    throw new IllegalStateException(
                            "discontinuous connector witness at arc " + arcId);
                }
                result.add(arcId);
                current = edge.target();
                if (result.size() > expectedEdges) {
                    throw new IllegalStateException(
                            "cyclic connector witness from " + source
                                    + " to " + target);
                }
            }
            if (result.size() != expectedEdges) {
                throw new IllegalStateException(
                        "connector witness edge-count mismatch from "
                                + source + " to " + target);
            }
            return List.copyOf(result);
        }
    }

    private record PathState(
            int node,
            PathPointer path,
            BitSet visited,
            BitSet visitedEdges,
            double lowerWeight,
            double estimatedCompletion,
            double scoreCost,
            double diversityCost) {
        static PathState root(
                int source,
                BitSet visited,
                BitSet visitedEdges,
                double estimate) {
            return new PathState(
                    source,
                    PathPointer.empty(),
                    visited,
                    visitedEdges,
                    0,
                    estimate,
                    0,
                    0);
        }
    }

    private record ConnectorKey(
            int source,
            int target,
            String domain,
            double budget,
            String visited,
            String visitedEdges,
            String queryHorizon,
            String corridorChecksum,
            String pivotContext,
            String temporalSemanticsVersion,
            String mode,
            String graphTemporalIdentity,
            int connectorLimit,
            long expansionCap) {
    }

    private record StaticPathLabel(
            int node,
            double lowerWeight,
            PathPointer path) {
    }

    private record ProfileKey(
            List<Integer> arcIds,
            int source,
            int target,
            String domain,
            String queryHorizon,
            double budget,
            String temporalPathVersion,
            String pivotContext,
            String mode) {
        private ProfileKey {
            arcIds = List.copyOf(arcIds);
        }
    }

    private static final class Counter {
        private long value;
    }
}
