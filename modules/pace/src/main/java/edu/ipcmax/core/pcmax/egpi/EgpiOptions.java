package edu.ipcmax.core.pcmax.egpi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned EGPI work budgets. None of these limits certifies global
 * exhaustion: a run that stops because proposals are exhausted has searched
 * only the insertion tree reachable under these bounds.
 *
 * <p>Batch sizes are fixed by configuration and never derived from the worker
 * count, so the sequence of search decisions, and therefore the output when
 * no deadline intervenes, is the same for one worker and for 24.</p>
 */
public record EgpiOptions(
        int maxProposalPops, int maxConnectorSearches,
        int maxExactRouteEvaluations, int maxExpansionFrontierRoutes,
        int maxRegistryRoutes, int maxPivotsPerConstructedRoute,
        long queryDeadlineMs, int structuralCacheCapacity,
        int temporalProfileCacheCapacity, int explorationFrontierQuota,
        int departureChunks, int proposalBatchSize, int exactUnitBatchSize,
        int proposalLookahead, int prefixProfileCacheCapacity,
        long temporalCacheMaxBytes, long prefixCacheMaxBytes) {
    public static final String VERSION = "EGPI_OPTIONS_V1";

    public EgpiOptions {
        if (maxProposalPops < 1 || maxConnectorSearches < 1
                || maxExactRouteEvaluations < 1 || maxExpansionFrontierRoutes < 2
                || maxRegistryRoutes < 1 || maxPivotsPerConstructedRoute < 1
                || queryDeadlineMs < 1 || queryDeadlineMs > 86_400_000
                || structuralCacheCapacity < 1 || temporalProfileCacheCapacity < 1
                || explorationFrontierQuota < 1
                || explorationFrontierQuota >= maxExpansionFrontierRoutes
                || departureChunks < 1 || departureChunks > 1024
                || proposalBatchSize < 1 || exactUnitBatchSize < 1
                || proposalLookahead < 1 || prefixProfileCacheCapacity < 0
                || temporalCacheMaxBytes < 0 || prefixCacheMaxBytes < 0) {
            throw new IllegalArgumentException("invalid EGPI work budget or cache capacity");
        }
    }

    /** Ten-field form used by the reference command-line surface; scheduling fields take defaults. */
    public EgpiOptions(int maxProposalPops, int maxConnectorSearches,
            int maxExactRouteEvaluations, int maxExpansionFrontierRoutes,
            int maxRegistryRoutes, int maxPivotsPerConstructedRoute,
            long queryDeadlineMs, int structuralCacheCapacity,
            int temporalProfileCacheCapacity, int explorationFrontierQuota) {
        this(maxProposalPops, maxConnectorSearches, maxExactRouteEvaluations,
                maxExpansionFrontierRoutes, maxRegistryRoutes, maxPivotsPerConstructedRoute,
                queryDeadlineMs, structuralCacheCapacity, temporalProfileCacheCapacity,
                explorationFrontierQuota, DEFAULT_DEPARTURE_CHUNKS, DEFAULT_PROPOSAL_BATCH,
                DEFAULT_EXACT_UNIT_BATCH, DEFAULT_LOOKAHEAD, DEFAULT_PREFIX_CAPACITY,
                DEFAULT_TEMPORAL_BYTES, DEFAULT_PREFIX_BYTES);
    }

    static final int DEFAULT_DEPARTURE_CHUNKS = 8;
    static final int DEFAULT_PROPOSAL_BATCH = 16;
    static final int DEFAULT_EXACT_UNIT_BATCH = 16;
    static final int DEFAULT_LOOKAHEAD = 16;
    static final int DEFAULT_PREFIX_CAPACITY = 256;
    static final long DEFAULT_TEMPORAL_BYTES = 256L * 1024 * 1024;
    static final long DEFAULT_PREFIX_BYTES = 512L * 1024 * 1024;

    public static EgpiOptions defaults() {
        return new EgpiOptions(128, 96, 16, 8, 32, 3, 10_000, 512, 64, 3);
    }

    public EgpiOptions withQueryDeadlineMs(long deadline) {
        return new EgpiOptions(maxProposalPops, maxConnectorSearches, maxExactRouteEvaluations,
                maxExpansionFrontierRoutes, maxRegistryRoutes, maxPivotsPerConstructedRoute,
                deadline, structuralCacheCapacity, temporalProfileCacheCapacity,
                explorationFrontierQuota, departureChunks, proposalBatchSize, exactUnitBatchSize,
                proposalLookahead, prefixProfileCacheCapacity, temporalCacheMaxBytes, prefixCacheMaxBytes);
    }

    public EgpiOptions withDepartureChunks(int chunks) {
        return new EgpiOptions(maxProposalPops, maxConnectorSearches, maxExactRouteEvaluations,
                maxExpansionFrontierRoutes, maxRegistryRoutes, maxPivotsPerConstructedRoute,
                queryDeadlineMs, structuralCacheCapacity, temporalProfileCacheCapacity,
                explorationFrontierQuota, chunks, proposalBatchSize, exactUnitBatchSize,
                proposalLookahead, prefixProfileCacheCapacity, temporalCacheMaxBytes, prefixCacheMaxBytes);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("options_version", VERSION);
        values.put("max_proposal_pops", maxProposalPops);
        values.put("max_connector_searches", maxConnectorSearches);
        values.put("max_exact_route_evaluations", maxExactRouteEvaluations);
        values.put("max_expansion_frontier_routes", maxExpansionFrontierRoutes);
        values.put("max_registry_routes", maxRegistryRoutes);
        values.put("max_pivots_per_constructed_route", maxPivotsPerConstructedRoute);
        values.put("query_deadline_ms", queryDeadlineMs);
        values.put("structural_cache_capacity", structuralCacheCapacity);
        values.put("temporal_profile_cache_capacity", temporalProfileCacheCapacity);
        values.put("exploration_frontier_quota", explorationFrontierQuota);
        values.put("departure_chunks", departureChunks);
        values.put("proposal_batch_size", proposalBatchSize);
        values.put("exact_unit_batch_size", exactUnitBatchSize);
        values.put("proposal_lookahead", proposalLookahead);
        values.put("prefix_profile_cache_capacity", prefixProfileCacheCapacity);
        values.put("temporal_cache_max_bytes", temporalCacheMaxBytes);
        values.put("prefix_cache_max_bytes", prefixCacheMaxBytes);
        return Collections.unmodifiableMap(values);
    }
}
