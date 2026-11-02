package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.QueryPreparationIndexes;

/**
 * Shared safe query-region reduction for candidate generators that do not use
 * the legacy layered frontier.
 *
 * <p>It performs the same steps as the scalable PACE generator, in the same
 * order: budget-truncated lower-bound labels from the source and to the
 * destination, the corridor built from exactly those labels (an arc is kept
 * iff {@code d(s,x)+tau_lb(e)+d(y,d)<=B}), and validation that every corridor
 * function covers the query horizon. No route is excluded by any heuristic
 * here. The legacy generator keeps its own inline copy so that frozen PACE-X
 * and R13 executions are byte-for-byte unchanged.</p>
 */
public final class SafeQueryRegion {
    private final TDGraph graph;
    private final QuerySpec query;
    private final QueryPreparationIndexes indexes;
    private final QueryLowerBounds lowerBounds;
    private final QueryLowerBounds.Distances fromSource;
    private final QueryLowerBounds.Distances toDestination;
    private final QueryCorridor corridor;
    private final Domain queryHorizon;

    private SafeQueryRegion(TDGraph graph, QuerySpec query, QueryPreparationIndexes indexes,
            QueryLowerBounds lowerBounds, QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination, QueryCorridor corridor, Domain queryHorizon) {
        this.graph = graph;
        this.query = query;
        this.indexes = indexes;
        this.lowerBounds = lowerBounds;
        this.fromSource = fromSource;
        this.toDestination = toDestination;
        this.corridor = corridor;
        this.queryHorizon = queryHorizon;
    }

    /** Prepares the safe corridor; throws {@link PaceException} if a function misses the horizon. */
    public static SafeQueryRegion prepare(TDGraph graph, QueryPreparationIndexes indexes, QuerySpec query) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(indexes, "indexes");
        Objects.requireNonNull(query, "query");
        double horizonEnd = Domain.canonicalTime(
                query.departureEndExact().doubleValue() + query.maxTravelTime());
        Domain queryHorizon = Domain.closed(query.departureStartExact().doubleValue(), horizonEnd);
        QueryLowerBounds lowerBounds = new QueryLowerBounds(graph, indexes.edgeTemporalSummaries());
        QueryLowerBounds.Distances fromSource = lowerBounds.truncatedDistancesFrom(
                query.source(), query.maxTravelTime());
        QueryLowerBounds.Distances toDestination = lowerBounds.truncatedDistancesTo(
                query.destination(), query.maxTravelTime());
        QueryCorridor corridor = QueryCorridor.build(graph, lowerBounds, indexes.graphPartition(),
                query.source(), query.destination(), query.maxTravelTime(), fromSource, toDestination);
        requireCorridorCoverage(graph, indexes.edgeTemporalSummaries(), corridor, queryHorizon);
        return new SafeQueryRegion(graph, query, indexes, lowerBounds, fromSource, toDestination,
                corridor, queryHorizon);
    }

    private static void requireCorridorCoverage(TDGraph graph, EdgeTemporalSummaryStore summaries,
            QueryCorridor corridor, Domain queryHorizon) {
        if (queryHorizon.difference(summaries.commonFunctionSupport()).isEmpty()) return;
        for (int arcId : corridor.directedArcIds()) {
            Edge edge = graph.edges().get(arcId);
            if (!queryHorizon.difference(edge.travelTimeFunction().domain()).isEmpty()
                    || !queryHorizon.difference(edge.scoreFunction().domain()).isEmpty()) {
                throw new PaceException(PaceStatus.FUNCTION_HORIZON_EXCEEDED,
                        "FUNCTION_HORIZON_EXCEEDED: arc_id " + arcId + " does not cover " + queryHorizon);
            }
        }
    }

    public TDGraph graph() { return graph; }
    public QuerySpec query() { return query; }
    public QueryPreparationIndexes indexes() { return indexes; }
    public QueryLowerBounds lowerBounds() { return lowerBounds; }
    public QueryLowerBounds.Distances fromSource() { return fromSource; }
    public QueryLowerBounds.Distances toDestination() { return toDestination; }
    public QueryCorridor corridor() { return corridor; }
    public Domain queryHorizon() { return queryHorizon; }

    /** Score-bearing arcs of the corridor in ascending arc-ID order, via the prepared score index. */
    public List<Integer> scoreBearingCorridorArcs() {
        LinkedHashSet<Integer> arcs = new LinkedHashSet<>();
        for (String cellId : corridor.activeCellIds()) {
            for (int arcId : indexes.scoreSupport().scoreBearingArcIds(cellId)) {
                if (corridor.containsArc(arcId)) arcs.add(arcId);
            }
        }
        List<Integer> sorted = new ArrayList<>(arcs);
        sorted.sort(Integer::compare);
        return List.copyOf(sorted);
    }

    /**
     * Exact entry times at which the arc can lie on some budget-feasible route
     * under the corridor's lower-bound semantics; empty means certified unusable.
     */
    public Domain feasibleEntryDomain(int arcId) {
        return QueryFeasibleEntryDomain.compute(corridor, lowerBounds, fromSource, toDestination,
                graph.edges().get(arcId), queryHorizon);
    }
}
