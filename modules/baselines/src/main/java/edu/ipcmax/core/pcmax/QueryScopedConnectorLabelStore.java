package edu.ipcmax.core.pcmax;

import java.util.BitSet;
import java.util.HexFormat;

import edu.ipcmax.core.cache.SingleFlightCache;
import edu.ipcmax.core.function.Domain;

/**
 * Query-scoped temporal forward/backward label index.
 *
 * <p>Lower-bound labels are still supplied by {@link QueryLowerBounds}; this
 * class adds reusable exact temporal connector labels inside the already-built
 * corridor. A request is single-flight keyed by its graph, connector policy,
 * complete temporal domain, residual budget, and looplessness masks. The
 * finalized PACE-B policy retains at most one structural connector per
 * request.</p>
 */
final class QueryScopedConnectorLabelStore {
    private static final int MAXIMUM_LABEL_ENTRIES = 8_192;

    private final BoundedConnectorGenerator connectors;
    private final QueryLowerBounds.Distances fromSource;
    private final QueryLowerBounds.Distances toDestination;
    private final PaceExecutionMetrics metrics;
    private final boolean cacheEnabled;
    private final SingleFlightCache<LabelKey, TemporalLabelPortfolio>
            forwardCache = new SingleFlightCache<>(
                    MAXIMUM_LABEL_ENTRIES);
    private final SingleFlightCache<LabelKey, TemporalLabelPortfolio>
            backwardCache = new SingleFlightCache<>(
                    MAXIMUM_LABEL_ENTRIES);

    QueryScopedConnectorLabelStore(
            BoundedConnectorGenerator connectors,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination,
            PaceExecutionMetrics metrics) {
        this.connectors = java.util.Objects.requireNonNull(
                connectors, "connectors");
        this.fromSource = java.util.Objects.requireNonNull(
                fromSource, "fromSource");
        this.toDestination = java.util.Objects.requireNonNull(
                toDestination, "toDestination");
        this.metrics = java.util.Objects.requireNonNull(
                metrics, "metrics");
        this.cacheEnabled = connectors.memoizationEnabled();
        metrics.observeCounter(
                "forward_lower_bound_labels",
                fromSource.size());
        metrics.observeCounter(
                "backward_lower_bound_labels",
                toDestination.size());
    }

    QueryLowerBounds.Distances fromSource() {
        return fromSource;
    }

    QueryLowerBounds.Distances toDestination() {
        return toDestination;
    }

    ConnectorResult prefixLabels(
            int source,
            int target,
            Domain entryDomain,
            BitSet visitedVertices,
            BitSet visitedEdges,
            double residualBudget,
            String workItem) {
        return label(
                true,
                source,
                target,
                entryDomain,
                visitedVertices,
                visitedEdges,
                residualBudget,
                workItem);
    }

    ConnectorResult suffixLabels(
            int source,
            int destination,
            Domain entryDomain,
            BitSet visitedVertices,
            BitSet visitedEdges,
            double residualBudget,
            String workItem) {
        return label(
                false,
                source,
                destination,
                entryDomain,
                visitedVertices,
                visitedEdges,
                residualBudget,
                workItem);
    }

    private ConnectorResult label(
            boolean forward,
            int source,
            int target,
            Domain entryDomain,
            BitSet visitedVertices,
            BitSet visitedEdges,
            double residualBudget,
            String workItem) {
        if (entryDomain == null || entryDomain.isEmpty()
                || visitedVertices == null || visitedEdges == null) {
            throw new IllegalArgumentException(
                    "invalid temporal label request");
        }
        metrics.increment(forward
                ? "forward_connector_label_joins"
                : "backward_suffix_label_joins");
        LabelKey key = LabelKey.of(
                source,
                target,
                entryDomain,
                visitedVertices,
                visitedEdges,
                residualBudget,
                connectors.cacheIdentity(),
                connectors.pivotContext(),
                connectors.connectorPolicyVersion());
        SingleFlightCache<LabelKey, TemporalLabelPortfolio> cache =
                forward ? forwardCache : backwardCache;
        TemporalLabelPortfolio portfolio;
        if (!cacheEnabled) {
            metrics.increment("label_fallback_calls");
            portfolio = build(
                    source,
                    target,
                    entryDomain,
                    visitedVertices,
                    visitedEdges,
                    residualBudget,
                    workItem);
        } else {
            portfolio = cache.getOrCompute(
                    key,
                    () -> build(
                            source,
                            target,
                            entryDomain,
                            visitedVertices,
                            visitedEdges,
                            residualBudget,
                            workItem));
            metrics.observeCounter(
                    forward
                            ? "forward_label_cache_hits"
                            : "backward_label_cache_hits",
                    cache.hits());
            metrics.observeCounter(
                    forward
                            ? "forward_label_cache_misses"
                            : "backward_label_cache_misses",
                    cache.misses());
            metrics.observeCounter(
                    forward
                            ? "forward_label_cache_waits"
                            : "backward_label_cache_waits",
                    cache.waits());
        }
        metrics.addCounter(
                "label_served_connectors",
                portfolio.alternativeCount());
        if (connectors.singleConnectorPolicy()
                && portfolio.alternativeCount() > 1) {
            throw new IllegalStateException(
                    "single-connector PACE-B label returned multiple paths");
        }
        metrics.addCounter(
                "label_avoided_expansions",
                portfolio.expansions());
        metrics.observeCounter(
                "label_alternatives_peak",
                portfolio.alternativeCount());
        if (portfolio.capTruncated()) {
            metrics.increment("label_cap_truncations");
        }
        return portfolio.connectorResult();
    }

    private TemporalLabelPortfolio build(
            int source,
            int target,
            Domain entryDomain,
            BitSet visitedVertices,
            BitSet visitedEdges,
            double residualBudget,
            String workItem) {
        metrics.increment("temporal_label_builds");
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.TEMPORAL_LABEL_BUILD)) {
            TemporalLabelPortfolio result =
                    TemporalLabelPortfolio.fromConnectorResult(
                            connectors.graph(),
                            source,
                            target,
                            residualBudget,
                            connectors.connect(
                                    source,
                                    target,
                                    entryDomain,
                                    visitedVertices,
                                    visitedEdges,
                                    residualBudget,
                                    workItem));
            metrics.addCounter(
                    "temporal_label_alternatives",
                    result.alternativeCount());
            metrics.addCounter("temporal_label_vertices", 2);
            metrics.observeCounter(
                    "temporal_label_alternatives_per_vertex_peak",
                    result.alternativeCount());
            metrics.addCounter(
                    "temporal_label_dominance_prunes",
                    0);
            metrics.observeCounter(
                    "temporal_label_memory_bytes",
                    Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory());
            return result;
        }
    }

    void releaseCaches() {
        metrics.observeCounter(
                "forward_label_cache_evictions",
                forwardCache.evictions());
        metrics.observeCounter(
                "backward_label_cache_evictions",
                backwardCache.evictions());
        metrics.observeCounter(
                "temporal_label_cache_peak_entries",
                forwardCache.peakSize() + backwardCache.peakSize());
        forwardCache.clear();
        backwardCache.clear();
        metrics.checkpoint("temporal_label_caches_released");
    }

    private record LabelKey(
            int source,
            int target,
            String entryDomain,
            double residualBudget,
            String visitedVertices,
            String visitedEdges,
            String graphTemporalIdentity,
            String pivotContext,
            String connectorPolicyVersion) {
        static LabelKey of(
                int source,
                int target,
                Domain entryDomain,
                BitSet visitedVertices,
                BitSet visitedEdges,
                double residualBudget,
                String graphTemporalIdentity,
                String pivotContext,
                String connectorPolicyVersion) {
            return new LabelKey(
                    source,
                    target,
                    entryDomain.toString(),
                    Domain.canonicalTime(residualBudget),
                    HexFormat.of().formatHex(
                            visitedVertices.toByteArray()),
                    HexFormat.of().formatHex(
                        visitedEdges.toByteArray()),
                    graphTemporalIdentity,
                    pivotContext,
                    connectorPolicyVersion);
        }
    }
}
