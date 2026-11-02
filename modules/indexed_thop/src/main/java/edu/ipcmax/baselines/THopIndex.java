package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * In-memory time-dependent 2-hop label index (THop, Section IV).
 *
 * <p>For each ordered pair (u,v), an exact profile search from u contributes
 * {@code (v,F(u,v))} to {@code Lout(u)} and {@code (u,F(u,v))} to
 * {@code Lin(v)}. {@link #build(TDGraph, Domain)} keeps the unpruned M2A
 * reference builder; {@link #buildPruned(TDGraph, Domain)} implements the
 * incremental Section IV-A pruning. Queries intersect hub IDs and compose the
 * two arrival mappings before taking their exact lower envelope. Preference
 * scores and travel-time budgets are not inputs to this class.</p>
 */
public final class THopIndex {
    private final TDGraph graph;
    private final Domain indexDomain;
    private final Map<Integer, Map<Integer, ExactArrivalProfile>> outgoingLabels;
    private final Map<Integer, Map<Integer, ExactArrivalProfile>> incomingLabels;
    private final IndexStatistics statistics;
    private final PreprocessingStatistics preprocessingStatistics;
    private final List<Integer> hubOrder;
    private final boolean prunedConstruction;
    private final BottomUpCompressionStatistics bottomUpCompressionStatistics;

    private THopIndex(
            TDGraph graph,
            Domain indexDomain,
            Map<Integer, Map<Integer, ExactArrivalProfile>> outgoingLabels,
            Map<Integer, Map<Integer, ExactArrivalProfile>> incomingLabels,
            IndexStatistics statistics,
            PreprocessingStatistics preprocessingStatistics,
            List<Integer> hubOrder,
            boolean prunedConstruction,
            BottomUpCompressionStatistics bottomUpCompressionStatistics) {
        this.graph = graph;
        this.indexDomain = indexDomain;
        this.outgoingLabels = freeze(outgoingLabels);
        this.incomingLabels = freeze(incomingLabels);
        this.statistics = statistics;
        this.preprocessingStatistics = preprocessingStatistics;
        this.hubOrder = List.copyOf(hubOrder);
        this.prunedConstruction = prunedConstruction;
        this.bottomUpCompressionStatistics = bottomUpCompressionStatistics;
    }

    /** Creates a query-ready index around labels produced by an offline builder. */
    static THopIndex fromExistingLabels(
            TDGraph graph,
            Domain indexDomain,
            Map<Integer, Map<Integer, ExactArrivalProfile>> outgoingLabels,
            Map<Integer, Map<Integer, ExactArrivalProfile>> incomingLabels,
            List<Integer> hubOrder) {
        // the loaders have already validated the labels exactly as stored; the query index works on the same functions without the redundant cuts older indexes carry
        outgoingLabels = canonicalLabels(outgoingLabels);
        incomingLabels = canonicalLabels(incomingLabels);
        long labels = countLabels(outgoingLabels) + countLabels(incomingLabels);
        long pieces = countPieces(outgoingLabels) + countPieces(incomingLabels);
        long breakpoints = countBreakpoints(outgoingLabels) + countBreakpoints(incomingLabels);
        long estimatedBytes = estimateBytes(labels, pieces, breakpoints);
        IndexStatistics stats = new IndexStatistics(
                0, labels, pieces, breakpoints, estimatedBytes, usedHeapBytes(), 0, 0, 0, 0);
        PreprocessingStatistics preprocessing = new PreprocessingStatistics(
                0, hubOrder.size(), 0, 0, 0, labels, 0, 0, 0, 0,
                pieces, estimatedBytes, usedHeapBytes(), 0, 0);
        return new THopIndex(
                graph, indexDomain, outgoingLabels, incomingLabels, stats, preprocessing, hubOrder, true, null);
    }

    private static Map<Integer, Map<Integer, ExactArrivalProfile>> canonicalLabels(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        Map<Integer, Map<Integer, ExactArrivalProfile>> result = new java.util.HashMap<>(labels.size() * 2);
        for (Map.Entry<Integer, Map<Integer, ExactArrivalProfile>> vertex : labels.entrySet()) {
            Map<Integer, ExactArrivalProfile> copy = new java.util.HashMap<>(vertex.getValue().size() * 2);
            for (Map.Entry<Integer, ExactArrivalProfile> label : vertex.getValue().entrySet()) copy.put(label.getKey(), label.getValue().canonicalized());
            result.put(vertex.getKey(), copy);
        }
        return result;
    }

    /** Builds an index over the union of all edge departure-function domains. */
    public static THopIndex build(TDGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Domain domain = Domain.empty();
        for (Edge edge : graph.edges()) {
            domain = domain.union(edge.travelTimeFunction().domain());
        }
        if (domain.isEmpty()) {
            throw new IllegalArgumentException(
                    "an explicit index domain is required when the graph has no temporal edges");
        }
        return build(graph, domain);
    }

    /** Builds a complete unpruned index over {@code indexDomain}. */
    public static THopIndex build(TDGraph graph, Domain indexDomain) {
        return build(graph, indexDomain, Map.of());
    }

    /** Internal builder for augmented partitions with exact shortcut profiles. */
    static THopIndex build(
            TDGraph graph, Domain indexDomain, Map<Integer, ExactArrivalProfile> exactArcArrivals) {
        Objects.requireNonNull(graph, "graph");
        validateIndexDomain(graph, indexDomain);

        long started = System.nanoTime();
        long sampledPeak = usedHeapBytes();
        long labelsExpanded = 0;
        long labelsGenerated = 0;
        long compositions = 0;
        long envelopes = 0;
        long profilesConstructed = 0;
        Map<Integer, Map<Integer, ExactArrivalProfile>> out = new HashMap<>();
        Map<Integer, Map<Integer, ExactArrivalProfile>> in = new HashMap<>();
        for (int node : graph.nodeIds()) {
            out.put(node, new HashMap<>());
            in.put(node, new HashMap<>());
        }

        FifoFastestPathProfileSearch search = new FifoFastestPathProfileSearch(graph, exactArcArrivals);
        for (int source : graph.nodeIds()) {
            FifoFastestPathProfileSearch.SearchResult result = search.search(source, indexDomain);
            FifoFastestPathProfileSearch.SearchStatistics searchStats = result.statistics();
            labelsExpanded += searchStats.searchStatesExpanded();
            labelsGenerated += searchStats.searchStatesGenerated();
            profilesConstructed += searchStats.searchStatesGenerated();
            compositions += searchStats.temporalCompositions();
            envelopes += searchStats.envelopeOperations();
            sampledPeak = Math.max(sampledPeak, searchStats.sampledPeakUsedHeapBytes());
            for (Map.Entry<Integer, ExactArrivalProfile> pair : result.exactProfiles().entrySet()) {
                int destination = pair.getKey();
                ExactArrivalProfile profile = pair.getValue();
                out.get(source).put(destination, profile);
                in.get(destination).put(source, profile);
            }
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
        }

        long labelCount = countLabels(out) + countLabels(in);
        long pieces = countPieces(out) + countPieces(in);
        long breakpoints = countBreakpoints(out) + countBreakpoints(in);
        long estimatedBytes = estimateBytes(labelCount, pieces, breakpoints);
        IndexStatistics stats = new IndexStatistics(
                System.nanoTime() - started,
                labelCount,
                pieces,
                breakpoints,
                estimatedBytes,
                sampledPeak,
                labelsExpanded,
                labelsGenerated,
                compositions,
                envelopes);
        PreprocessingStatistics preprocessing = new PreprocessingStatistics(
                stats.constructionNanos(),
                graph.nodeCount(),
                graph.nodeCount(),
                0,
                profilesConstructed,
                labelCount,
                0,
                0,
                labelsExpanded,
                0,
                pieces,
                estimatedBytes,
                sampledPeak,
                compositions,
                envelopes);
        return new THopIndex(
                graph, indexDomain, out, in, stats, preprocessing, graph.nodeIds(), false, null);
    }

    /**
     * Builds the paper's incremental, dominance-pruned THop index using a
     * deterministic distance-to-barycenter hub order.
     */
    public static THopIndex buildPruned(TDGraph graph, Domain indexDomain) {
        Objects.requireNonNull(graph, "graph");
        return buildPruned(graph, indexDomain, barycenterHubOrder(graph), Map.of());
    }

    /**
     * Builds a pruned index using the supplied explicit hub permutation. This
     * overload supports reproducibility and controlled hub-order comparisons.
     */
    public static THopIndex buildPruned(
            TDGraph graph, Domain indexDomain, List<Integer> suppliedHubOrder) {
        return buildPruned(graph, indexDomain, suppliedHubOrder, Map.of());
    }

    static THopIndex buildPruned(
            TDGraph graph, Domain indexDomain, List<Integer> suppliedHubOrder,
            Map<Integer, ExactArrivalProfile> exactArcArrivals) {
        Objects.requireNonNull(graph, "graph");
        validateIndexDomain(graph, indexDomain);
        List<Integer> order = validateHubOrder(graph, suppliedHubOrder);
        new FifoFastestPathProfileSearch(graph, exactArcArrivals); // validates exact FIFO and support gaps

        long started = System.nanoTime();
        long sampledPeak = usedHeapBytes();
        long profilesConstructed = 0;
        long forwardExpansions = 0;
        long backwardExpansions = 0;
        long searchExpansionsSkipped = 0;
        long forwardCalls = 0;
        long backwardCalls = 0;
        long forwardCompositions = 0;
        long backwardCompositions = 0;
        long forwardEnvelopes = 0;
        long backwardEnvelopes = 0;
        long[] dominanceComparisons = {0};
        long[] maxProfilePieces = {0};
        int[] maxRationalBits = {0};

        Map<Integer, Map<Integer, ExactArrivalProfile>> out = new HashMap<>();
        Map<Integer, Map<Integer, ExactArrivalProfile>> in = new HashMap<>();
        for (int node : graph.nodeIds()) {
            out.put(node, new HashMap<>());
            in.put(node, new HashMap<>());
        }

        ExactTemporalDomain terminalArrivalDomain = PrunedFifoProfileSearch.terminalArrivalDomain(
                graph, exactArcArrivals);
        Set<Integer> processedHubs = new LinkedHashSet<>();
        Set<LabelKey> prunedLabels = new HashSet<>();
        for (int hub : order) {
            if (ThopBuildControl.ENABLED) ThopBuildControl.checkStop("inner:hub=" + hub + ":completed_hubs=" + processedHubs.size());
            long hubStarted = System.nanoTime();
            long hubCpuStarted = ThopBuildProgress.ENABLED ? ThopBuildProgress.processCpuNanos() : -1L;
            long hubDominanceStarted = dominanceComparisons[0];
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=inner_hub_start hub=" + hub
                        + " completed_hubs=" + processedHubs.size()
                        + " total_hubs=" + order.size()
                        + " graph_vertices=" + graph.nodeCount()
                        + " graph_arcs=" + graph.edgeCount());
            }
            ExactArrivalProfile self = ExactArrivalProfile.identity(indexDomain);
            out.get(hub).put(hub, self);
            in.get(hub).put(hub, self);

            PrunedFifoProfileSearch.SearchResult forward = PrunedFifoProfileSearch.run(
                    graph,
                    hub,
                    self,
                    PrunedFifoProfileSearch.Direction.FORWARD,
                    exactArcArrivals,
                    (vertex, profile) -> {
                        if (vertex == hub) {
                            return true;
                        }
                        ExactArrivalProfile previous = labelEnvelope(
                                out, in, hub, vertex, processedHubs);
                        dominanceComparisons[0]++;
                        if (previous.dominates(profile)) {
                            in.get(vertex).remove(hub);
                            prunedLabels.add(new LabelKey(Direction.FORWARD, hub, vertex));
                            return false;
                        }
                        maxProfilePieces[0] = Math.max(maxProfilePieces[0], profile.pieceCount());
                        maxRationalBits[0] = Math.max(maxRationalBits[0],
                                profile.maximumRationalComponentBitLength());
                        in.get(vertex).put(hub, profile);
                        return true;
                    });
            forwardCalls++;
            profilesConstructed += forward.statistics().searchStatesGenerated();
            forwardExpansions += forward.statistics().searchStatesExpanded();
            searchExpansionsSkipped += forward.statistics().searchExpansionsSkipped();
            forwardCompositions += forward.statistics().temporalCompositions();
            forwardEnvelopes += forward.statistics().envelopeOperations();
            sampledPeak = Math.max(sampledPeak, forward.statistics().sampledPeakUsedHeapBytes());
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=inner_hub_forward_complete hub=" + hub
                        + " processed_labels=" + forward.statistics().searchStatesExpanded()
                        + " generated_profiles=" + forward.statistics().searchStatesGenerated()
                        + " dominance_comparisons=" + (dominanceComparisons[0] - hubDominanceStarted)
                        + " pruned_before_expansion=" + forward.statistics().searchExpansionsSkipped()
                        + " temporal_compositions=" + forward.statistics().temporalCompositions()
                        + " profile_minimum_operations=" + forward.statistics().envelopeOperations());
            }

            ExactArrivalProfile terminalIdentity = ExactArrivalProfile.identity(terminalArrivalDomain);
            PrunedFifoProfileSearch.SearchResult backward = PrunedFifoProfileSearch.run(
                    graph,
                    hub,
                    terminalIdentity,
                    PrunedFifoProfileSearch.Direction.BACKWARD,
                    exactArcArrivals,
                    (vertex, profile) -> {
                        if (vertex == hub) {
                            return true;
                        }
                        ExactArrivalProfile previous = labelEnvelope(
                                out, in, vertex, hub, processedHubs);
                        dominanceComparisons[0]++;
                        if (previous.dominates(profile)) {
                            out.get(vertex).remove(hub);
                            prunedLabels.add(new LabelKey(Direction.BACKWARD, hub, vertex));
                            return false;
                        }
                        maxProfilePieces[0] = Math.max(maxProfilePieces[0], profile.pieceCount());
                        maxRationalBits[0] = Math.max(maxRationalBits[0],
                                profile.maximumRationalComponentBitLength());
                        out.get(vertex).put(hub, profile);
                        return true;
                    });
            backwardCalls++;
            profilesConstructed += backward.statistics().searchStatesGenerated();
            backwardExpansions += backward.statistics().searchStatesExpanded();
            searchExpansionsSkipped += backward.statistics().searchExpansionsSkipped();
            backwardCompositions += backward.statistics().temporalCompositions();
            backwardEnvelopes += backward.statistics().envelopeOperations();
            sampledPeak = Math.max(sampledPeak, backward.statistics().sampledPeakUsedHeapBytes());
            processedHubs.add(hub);
            sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=inner_hub_complete hub=" + hub
                        + " completed_hubs=" + processedHubs.size()
                        + " forward_expanded=" + forward.statistics().searchStatesExpanded()
                        + " backward_expanded=" + backward.statistics().searchStatesExpanded()
                        + " forward_generated=" + forward.statistics().searchStatesGenerated()
                        + " backward_generated=" + backward.statistics().searchStatesGenerated()
                        + " dominance_comparisons=" + (dominanceComparisons[0] - hubDominanceStarted)
                        + " forward_pruned=" + forward.statistics().searchExpansionsSkipped()
                        + " backward_pruned=" + backward.statistics().searchExpansionsSkipped()
                        + " temporal_compositions=" + (forward.statistics().temporalCompositions()
                                + backward.statistics().temporalCompositions())
                        + " profile_minimum_operations=" + (forward.statistics().envelopeOperations()
                                + backward.statistics().envelopeOperations())
                        + " retained_directed_labels_so_far=" + (countLabels(out) + countLabels(in))
                        + " total_profile_segments_so_far=" + (countPieces(out) + countPieces(in))
                        + " max_profile_segments_seen=" + maxProfilePieces[0]
                        + " max_rational_component_bits_seen=" + maxRationalBits[0]
                        + " hub_wall_ns=" + (System.nanoTime() - hubStarted)
                        + " hub_process_cpu_ns=" + (hubCpuStarted < 0 ? -1
                                : ThopBuildProgress.processCpuNanos() - hubCpuStarted));
            }
        }

        long constructionNanos = System.nanoTime() - started;
        long labelCount = countLabels(out) + countLabels(in);
        long pieces = countPieces(out) + countPieces(in);
        long breakpoints = countBreakpoints(out) + countBreakpoints(in);
        long estimatedBytes = estimateBytes(labelCount, pieces, breakpoints);
        long totalCompositions = forwardCompositions + backwardCompositions;
        long totalEnvelopes = forwardEnvelopes + backwardEnvelopes;
        long totalExpansions = forwardExpansions + backwardExpansions;
        long labelsPrunedCount = prunedLabels.stream()
                .filter(key -> key.direction() == Direction.FORWARD
                        ? !in.get(key.vertex()).containsKey(key.hub())
                        : !out.get(key.vertex()).containsKey(key.hub()))
                .count();
        if (ThopBuildProgress.ENABLED) {
            ThopBuildProgress.log("stage=inner_index_complete completed_hubs=" + processedHubs.size()
                    + " labels=" + labelCount + " profile_segments=" + pieces
                    + " breakpoints=" + breakpoints + " labels_pruned=" + labelsPrunedCount
                    + " dominance_comparisons=" + dominanceComparisons[0]
                    + " temporal_compositions=" + totalCompositions
                    + " profile_minimum_operations=" + totalEnvelopes
                    + " max_profile_segments_seen=" + maxProfilePieces[0]
                    + " max_rational_component_bits_seen=" + maxRationalBits[0]
                    + " elapsed_wall_ns=" + constructionNanos);
        }
        IndexStatistics stats = new IndexStatistics(
                constructionNanos,
                labelCount,
                pieces,
                breakpoints,
                estimatedBytes,
                sampledPeak,
                totalExpansions,
                profilesConstructed,
                totalCompositions,
                totalEnvelopes);
        PreprocessingStatistics preprocessing = new PreprocessingStatistics(
                constructionNanos,
                processedHubs.size(),
                forwardCalls,
                backwardCalls,
                profilesConstructed,
                labelCount,
                labelsPrunedCount,
                searchExpansionsSkipped,
                forwardExpansions,
                backwardExpansions,
                pieces,
                estimatedBytes,
                sampledPeak,
                totalCompositions,
                totalEnvelopes);
        return new THopIndex(graph, indexDomain, out, in, stats, preprocessing, order, true, null);
    }

    private static void validateIndexDomain(TDGraph graph, Domain indexDomain) {
        if (indexDomain == null || indexDomain.isEmpty()) {
            throw new IllegalArgumentException("a nonempty index domain is required");
        }
        Domain commonEdgeDomain = null;
        for (Edge edge : graph.edges()) {
            Domain edgeDomain = edge.travelTimeFunction().domain();
            if (commonEdgeDomain == null) {
                commonEdgeDomain = edgeDomain;
            } else if (!commonEdgeDomain.equals(edgeDomain)) {
                throw new IllegalArgumentException(
                        "THop requires a common edge-function support domain for exact label composition");
            }
            if (!edgeDomain.difference(indexDomain).isEmpty()) {
                throw new IllegalArgumentException(
                        "index domain must cover every edge function domain; arc_id=" + edge.arcId());
            }
        }
    }

    private static List<Integer> barycenterHubOrder(TDGraph graph) {
        List<Integer> nodes = graph.nodeIds();
        if (nodes.isEmpty()) {
            return List.of();
        }
        long firstX = graph.node(nodes.get(0)).x();
        long firstY = graph.node(nodes.get(0)).y();
        boolean coincident = nodes.stream().allMatch(node ->
                graph.node(node).x() == firstX && graph.node(node).y() == firstY);
        if (coincident) {
            return nodes;
        }

        double centerX = nodes.stream().mapToDouble(node -> graph.node(node).x()).average().orElse(0);
        double centerY = nodes.stream().mapToDouble(node -> graph.node(node).y()).average().orElse(0);
        return nodes.stream().sorted((left, right) -> {
            double leftDistance = Math.hypot(
                    graph.node(left).x() - centerX, graph.node(left).y() - centerY);
            double rightDistance = Math.hypot(
                    graph.node(right).x() - centerX, graph.node(right).y() - centerY);
            int comparison = Double.compare(leftDistance, rightDistance);
            return comparison != 0 ? comparison : Integer.compare(left, right);
        }).toList();
    }

    private static List<Integer> validateHubOrder(TDGraph graph, List<Integer> suppliedHubOrder) {
        if (suppliedHubOrder == null || suppliedHubOrder.size() != graph.nodeCount()
                || new HashSet<>(suppliedHubOrder).size() != graph.nodeCount()
                || !new HashSet<>(suppliedHubOrder).equals(new HashSet<>(graph.nodeIds()))) {
            throw new IllegalArgumentException("hub order must be a permutation of all graph vertices");
        }
        suppliedHubOrder.forEach(graph::node);
        return List.copyOf(suppliedHubOrder);
    }

    private static ExactArrivalProfile labelEnvelope(
            Map<Integer, Map<Integer, ExactArrivalProfile>> out,
            Map<Integer, Map<Integer, ExactArrivalProfile>> in,
            int source,
            int destination,
            Set<Integer> eligibleHubs) {
        ExactArrivalProfile result = ExactArrivalProfile.empty();
        for (int hub : eligibleHubs) {
            ExactArrivalProfile sourceToHub = out.get(source).get(hub);
            ExactArrivalProfile hubToDestination = in.get(destination).get(hub);
            if (sourceToHub == null || hubToDestination == null) {
                continue;
            }
            result = result.pointwiseMinimum(sourceToHub.composeAfter(hubToDestination));
        }
        return result;
    }

    private enum Direction {
        FORWARD,
        BACKWARD
    }

    private record LabelKey(Direction direction, int hub, int vertex) {
    }

    public TDGraph graph() {
        return graph;
    }

    public Domain indexDomain() {
        return indexDomain;
    }

    /** Rounded legacy label projections; rational event roots are lost in this view. */
    public Map<Integer, ExactTemporalProfile> outgoingLabels(int vertex) {
        graph.node(vertex);
        return roundedLabels(outgoingLabels.get(vertex));
    }

    /** Rounded legacy label projections; rational event roots are lost in this view. */
    public Map<Integer, ExactTemporalProfile> incomingLabels(int vertex) {
        graph.node(vertex);
        return roundedLabels(incomingLabels.get(vertex));
    }

    /** Authoritative exact labels {@code F(vertex,hub,t)}. */
    public Map<Integer, ExactArrivalProfile> exactOutgoingLabels(int vertex) {
        graph.node(vertex);
        return outgoingLabels.get(vertex);
    }

    /** Authoritative exact labels {@code F(hub,vertex,t)}. */
    public Map<Integer, ExactArrivalProfile> exactIncomingLabels(int vertex) {
        graph.node(vertex);
        return incomingLabels.get(vertex);
    }

    Map<Integer, Map<Integer, ExactArrivalProfile>> outgoingLabelsSnapshot() {
        return outgoingLabels;
    }

    Map<Integer, Map<Integer, ExactArrivalProfile>> incomingLabelsSnapshot() {
        return incomingLabels;
    }

    public IndexStatistics statistics() {
        return statistics;
    }

    /** Detailed construction counters for the selected build mode. */
    public PreprocessingStatistics preprocessingStatistics() {
        return preprocessingStatistics;
    }

    /** Deterministic hub order used to construct this index. */
    public List<Integer> hubOrder() {
        return hubOrder;
    }

    /** Whether this index was constructed with incremental THop pruning. */
    public boolean isPrunedConstruction() {
        return prunedConstruction;
    }

    /**
     * Returns a separately selectable approximate index whose stored label
     * profiles are reduced with the paper's Section VI-B Algorithm 1.
     * The original index remains unchanged and is the exact/reference mode.
     */
    public THopIndex withBottomUpCompression(ExactTime epsilon) {
        Objects.requireNonNull(epsilon, "epsilon");
        if (bottomUpCompressionStatistics != null) {
            throw new IllegalStateException("bottom-up compression has already been applied to this index");
        }
        long started = System.nanoTime();
        CompressionMap outgoing = compressLabels(outgoingLabels, epsilon);
        CompressionMap incoming = compressLabels(incomingLabels, epsilon);
        long elapsed = System.nanoTime() - started;
        long pieces = countPieces(outgoing.labels()) + countPieces(incoming.labels());
        long breakpoints = countBreakpoints(outgoing.labels()) + countBreakpoints(incoming.labels());
        long labels = countLabels(outgoing.labels()) + countLabels(incoming.labels());
        long estimatedBytes = estimateBytes(labels, pieces, breakpoints);
        IndexStatistics compressedIndexStats = new IndexStatistics(
                statistics.constructionNanos() + elapsed,
                labels,
                pieces,
                breakpoints,
                estimatedBytes,
                Math.max(statistics.sampledPeakUsedHeapBytes(), usedHeapBytes()),
                statistics.searchStatesExpanded(),
                statistics.searchStatesGenerated(),
                statistics.temporalCompositions(),
                statistics.envelopeOperations());
        PreprocessingStatistics compressedPreparationStats = new PreprocessingStatistics(
                preprocessingStatistics.constructionNanos() + elapsed,
                preprocessingStatistics.processedHubs(),
                preprocessingStatistics.forwardSearchCalls(),
                preprocessingStatistics.backwardSearchCalls(),
                preprocessingStatistics.profilesConstructed(),
                preprocessingStatistics.labelsRetained(),
                preprocessingStatistics.labelsPruned(),
                preprocessingStatistics.searchExpansionsSkipped(),
                preprocessingStatistics.forwardSearchExpansions(),
                preprocessingStatistics.backwardSearchExpansions(),
                pieces,
                estimatedBytes,
                compressedIndexStats.sampledPeakUsedHeapBytes(),
                preprocessingStatistics.temporalCompositions(),
                preprocessingStatistics.envelopeOperations());
        BottomUpCompressionStatistics compressionStats = new BottomUpCompressionStatistics(
                epsilon,
                outgoing.inputPieces() + incoming.inputPieces(),
                pieces,
                outgoing.removedBreakpoints() + incoming.removedBreakpoints(),
                outgoing.maximumAbsoluteBreakpointDeviation().compareTo(
                        incoming.maximumAbsoluteBreakpointDeviation()) >= 0
                        ? outgoing.maximumAbsoluteBreakpointDeviation()
                        : incoming.maximumAbsoluteBreakpointDeviation(),
                elapsed);
        return new THopIndex(graph, indexDomain, outgoing.labels(), incoming.labels(),
                compressedIndexStats, compressedPreparationStats, hubOrder,
                prunedConstruction, compressionStats);
    }

    /** True only for the separately compressed approximate mode. */
    public boolean isBottomUpCompressed() {
        return bottomUpCompressionStatistics != null;
    }

    public java.util.Optional<BottomUpCompressionStatistics> bottomUpCompressionStatistics() {
        return java.util.Optional.ofNullable(bottomUpCompressionStatistics);
    }

    private static CompressionMap compressLabels(
            Map<Integer, Map<Integer, ExactArrivalProfile>> source, ExactTime epsilon) {
        Map<Integer, Map<Integer, ExactArrivalProfile>> result = new HashMap<>();
        long inputPieces = 0;
        long removed = 0;
        ExactTime maximumDeviation = ExactTime.ZERO;
        for (Map.Entry<Integer, Map<Integer, ExactArrivalProfile>> row : source.entrySet()) {
            Map<Integer, ExactArrivalProfile> compressed = new HashMap<>();
            for (Map.Entry<Integer, ExactArrivalProfile> label : row.getValue().entrySet()) {
                BottomUpProfileCompression.Result operation = BottomUpProfileCompression.compress(
                        label.getValue(), epsilon);
                compressed.put(label.getKey(), operation.profile());
                inputPieces += operation.inputPieces();
                removed += operation.removedBreakpoints();
                if (operation.maximumAbsoluteBreakpointDeviation().compareTo(maximumDeviation) > 0) {
                    maximumDeviation = operation.maximumAbsoluteBreakpointDeviation();
                }
            }
            result.put(row.getKey(), compressed);
        }
        return new CompressionMap(freeze(result), inputPieces, removed, maximumDeviation);
    }

    private record CompressionMap(
            Map<Integer, Map<Integer, ExactArrivalProfile>> labels,
            long inputPieces,
            long removedBreakpoints,
            ExactTime maximumAbsoluteBreakpointDeviation) { }

    /**
     * Answers the fastest-path profile over the entire requested interval.
     * The returned profile's values are travel times; endpoint and support
     * ownership are retained exactly.
     */
    public QueryResult query(int source, int destination, Domain queryInterval) {
        graph.node(source);
        graph.node(destination);
        if (queryInterval == null || queryInterval.isEmpty()) {
            throw new IllegalArgumentException("a nonempty query interval is required");
        }
        if (!queryInterval.difference(indexDomain).isEmpty()) {
            throw new IllegalArgumentException("query interval lies outside the index temporal domain");
        }
        long started = System.nanoTime();
        Map<String, Long> work = new HashMap<>();
        long[] sampledPeak = {usedHeapBytes()};
        int commonHubs;
        ExactArrivalProfile arrivals = ExactArrivalProfile.empty();
        TreeSet<Integer> intersection = new TreeSet<>(outgoingLabels.get(source).keySet());
        intersection.retainAll(incomingLabels.get(destination).keySet());
        commonHubs = intersection.size();

        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install(
                (name, amount) -> {
                    work.merge(name, amount, Long::sum);
                    sampledPeak[0] = Math.max(sampledPeak[0], usedHeapBytes());
                })) {
            for (int hub : intersection) {
                ExactArrivalProfile sourceToHub = outgoingLabels.get(source).get(hub);
                ExactArrivalProfile hubToDestination = incomingLabels.get(destination).get(hub);
                ExactArrivalProfile candidate = sourceToHub
                        .composeAfter(hubToDestination)
                        .restrict(queryInterval);
                arrivals = arrivals.pointwiseMinimum(candidate);
            }
        }

        ExactArrivalProfile travelTimes = arrivals.asTravelTime();
        return new QueryResult(
                source,
                destination,
                queryInterval,
                travelTimes.roundedLegacyProjection(),
                travelTimes,
                new QueryStatistics(
                        System.nanoTime() - started,
                        commonHubs,
                        work.getOrDefault("thop_temporal_compositions", 0L),
                        work.getOrDefault("thop_envelope_operations", 0L),
                        travelTimes.pieceCount(),
                        Math.max(sampledPeak[0], usedHeapBytes())));
    }

    /** Arrival-time profile for internal composition by partitioned indexes. */
    ExactTemporalProfile queryArrivalProfile(
            int source, int destination, Domain queryInterval) {
        return queryExactArrivalProfile(source, destination, queryInterval).roundedLegacyProjection();
    }

    ExactArrivalProfile queryExactArrivalProfile(
            int source, int destination, Domain queryInterval) {
        graph.node(source);
        graph.node(destination);
        if (queryInterval == null || queryInterval.isEmpty()) {
            throw new IllegalArgumentException("a nonempty query interval is required");
        }
        if (!queryInterval.difference(indexDomain).isEmpty()) {
            throw new IllegalArgumentException("query interval lies outside the index temporal domain");
        }
        ExactArrivalProfile arrivals = ExactArrivalProfile.empty();
        TreeSet<Integer> intersection = new TreeSet<>(outgoingLabels.get(source).keySet());
        intersection.retainAll(incomingLabels.get(destination).keySet());
        for (int hub : intersection) {
            ExactArrivalProfile candidate = outgoingLabels.get(source).get(hub)
                    .composeAfter(incomingLabels.get(destination).get(hub))
                    .restrict(queryInterval);
            arrivals = arrivals.pointwiseMinimum(candidate);
        }
        return arrivals;
    }

    int commonHubCount(int source, int destination) {
        graph.node(source);
        graph.node(destination);
        TreeSet<Integer> intersection = new TreeSet<>(outgoingLabels.get(source).keySet());
        intersection.retainAll(incomingLabels.get(destination).keySet());
        return intersection.size();
    }

    private static Map<Integer, ExactTemporalProfile> roundedLabels(Map<Integer, ExactArrivalProfile> labels) {
        Map<Integer, ExactTemporalProfile> result = new HashMap<>();
        labels.forEach((hub, profile) -> result.put(hub, profile.roundedLegacyProjection()));
        return Map.copyOf(result);
    }

    private static Map<Integer, Map<Integer, ExactArrivalProfile>> freeze(
            Map<Integer, Map<Integer, ExactArrivalProfile>> source) {
        Map<Integer, Map<Integer, ExactArrivalProfile>> result = new HashMap<>();
        source.forEach((vertex, labels) -> result.put(vertex, Map.copyOf(labels)));
        return Map.copyOf(result);
    }

    private static long countLabels(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        return labels.values().stream().mapToLong(Map::size).sum();
    }

    private static long countPieces(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        return labels.values().stream()
                .flatMap(map -> map.values().stream())
                .mapToLong(ExactArrivalProfile::pieceCount)
                .sum();
    }

    private static long countBreakpoints(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        return labels.values().stream()
                .flatMap(map -> map.values().stream())
                .mapToLong(ExactArrivalProfile::breakpointCount)
                .sum();
    }

    private static long estimateBytes(long labels, long pieces, long breakpoints) {
        // A documented payload estimate, not JVM object-layout instrumentation.
        return 48L * labels + 32L * pieces + 16L * breakpoints;
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public record IndexStatistics(
            long constructionNanos,
            long labelCount,
            long temporalFunctionPieces,
            long temporalBreakpoints,
            long estimatedIndexBytes,
            long sampledPeakUsedHeapBytes,
            long searchStatesExpanded,
            long searchStatesGenerated,
            long temporalCompositions,
            long envelopeOperations) {
    }

    public record QueryStatistics(
            long queryNanos,
            int commonHubCount,
            long temporalCompositions,
            long envelopeOperations,
            long resultFunctionPieces,
            long sampledPeakUsedHeapBytes) {
    }

    /** Construction metrics kept separate from query latency. */
    public record PreprocessingStatistics(
            long constructionNanos,
            int processedHubs,
            long forwardSearchCalls,
            long backwardSearchCalls,
            long profilesConstructed,
            long labelsRetained,
            long labelsPruned,
            long searchExpansionsSkipped,
            long forwardSearchExpansions,
            long backwardSearchExpansions,
            long temporalFunctionPieces,
            long estimatedIndexBytes,
            long sampledPeakUsedHeapBytes,
            long temporalCompositions,
            long envelopeOperations) {
    }

    public record BottomUpCompressionStatistics(
            ExactTime epsilon,
            long functionPiecesBefore,
            long functionPiecesAfter,
            long turningPointsRemoved,
            ExactTime maximumAbsoluteBreakpointDeviation,
            long compressionNanos) { }

    public record QueryResult(
            int source,
            int destination,
            Domain queryInterval,
            ExactTemporalProfile travelTimeProfile,
            ExactArrivalProfile exactTravelTimeProfile,
            QueryStatistics statistics) {
        public boolean hasReachableDepartures() {
            return !exactTravelTimeProfile.isEmpty();
        }

        /** Final HALF_EVEN-rounded exact travel time at a supported departure. */
        public double travelTimeAt(double departureTime) {
            return exactTravelTimeProfile.valueAt(departureTime).toDisplayDouble();
        }

        public edu.ipcmax.baselines.exact.ExactTime exactTravelTimeAt(double departureTime) {
            return exactTravelTimeProfile.valueAt(departureTime);
        }
    }
}
