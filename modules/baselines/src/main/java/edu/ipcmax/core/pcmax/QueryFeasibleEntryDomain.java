package edu.ipcmax.core.pcmax;

import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;

/**
 * Exact edge-entry domain under the query corridor's lower-bound witness
 * semantics and the edge's FIFO temporal function.
 */
final class QueryFeasibleEntryDomain {
    private QueryFeasibleEntryDomain() {
    }

    /**
     * Computes the exact set of entry times {@code t} for which a departure
     * {@code r} exists in the query interval and satisfies:
     *
     * <pre>
     * r + d_lb(S,u) <= t
     * arrival_e(t) + d_lb(v,D) <= r + B
     * </pre>
     *
     * <p>The existential departure is eliminated analytically. The resulting
     * piecewise domains are evaluated by the repository's exact FIFO
     * root/domain operations; no time sampling is used.</p>
     */
    static Domain compute(
            QueryCorridor corridor,
            QueryLowerBounds lowerBounds,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination,
            Edge edge,
            Domain queryHorizon) {
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(lowerBounds, "lowerBounds");
        Objects.requireNonNull(fromSource, "fromSource");
        Objects.requireNonNull(toDestination, "toDestination");
        Objects.requireNonNull(edge, "edge");
        Objects.requireNonNull(queryHorizon, "queryHorizon");
        if (queryHorizon.isEmpty()
                || queryHorizon.intervals().size() != 1) {
            throw new IllegalArgumentException(
                    "query horizon must be one non-empty interval");
        }
        double prefix = fromSource.distance(edge.source());
        double suffix = toDestination.distance(edge.target());
        if (!Double.isFinite(prefix)
                || !Double.isFinite(suffix)) {
            return Domain.empty();
        }
        Domain.Interval horizon =
                queryHorizon.intervals().get(0);
        double departureStart = horizon.start();
        double departureEnd = Domain.canonicalTime(
                horizon.end() - corridor.budget());
        double earliestEntry = Domain.canonicalTime(
                departureStart + prefix);
        if (earliestEntry > horizon.end()) {
            return Domain.empty();
        }
        Domain possible = PaceProfiles.validEntryDomain(
                        edge, queryHorizon)
                .intersection(Domain.closed(
                        earliestEntry, horizon.end()));
        if (possible.isEmpty()) {
            return Domain.empty();
        }

        double latestAbsoluteArrival = Domain.canonicalTime(
                departureEnd + corridor.budget() - suffix);
        possible = edge.travelTimeFunction()
                .domainWhereArrivalAtMost(
                        possible,
                        ignored -> latestAbsoluteArrival);
        if (possible.isEmpty()) {
            return Domain.empty();
        }

        double maximumEdgeTravel = Domain.canonicalTime(
                corridor.budget() - prefix - suffix);
        if (maximumEdgeTravel < 0) {
            return Domain.empty();
        }
        return edge.travelTimeFunction()
                .domainWhereArrivalAtMost(
                        possible,
                        entry -> Domain.canonicalTime(
                                entry + maximumEdgeTravel));
    }
}
