package edu.ipcmax.core.pcmax;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;

/** Shared exact temporal replay for independently enumerated paths. */
public final class ExactPathProfileBuilder {
    private ExactPathProfileBuilder() {
    }

    /**
     * Composes edge functions at their actual entry times and restricts the path to
     * the exact function-horizon and budget-feasible departure domain.
     */
    public static Optional<CandidateProfile> replay(
            TDGraph graph,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain departureDomain,
            double budget) {
        if (departureDomain == null || departureDomain.isEmpty()) {
            return Optional.empty();
        }
        return context(graph, departureDomain, budget).replay(arcIds, source, destination);
    }

    /** Prepares immutable per-query replay state once for algorithms evaluating many paths. */
    public static ReplayContext context(TDGraph graph, Domain departureDomain, double budget) {
        return context(graph, departureDomain, budget, () -> false);
    }

    /** Prepares replay state with cooperative cancellation between temporal edges. */
    public static ReplayContext context(
            TDGraph graph,
            Domain departureDomain,
            double budget,
            BooleanSupplier cancelled) {
        if (graph == null || departureDomain == null || departureDomain.isEmpty()) {
            throw new IllegalArgumentException("graph and a nonempty departure domain are required");
        }
        if (cancelled == null) {
            throw new IllegalArgumentException("cancellation predicate is required");
        }
        double start = departureDomain.intervals().get(0).start();
        double end = departureDomain.intervals().get(departureDomain.intervals().size() - 1).end();
        Domain horizon = Domain.closed(start, Domain.canonicalTime(end + budget));
        return new ReplayContext(
                graph,
                AnchorIndex.create(graph, horizon),
                departureDomain,
                budget,
                cancelled);
    }

    /**
     * Prepares exact budget-constrained replay without constructing an anchor
     * index.  Path-enumeration baselines do not select pivots, so an
     * {@code AnchorIndex} would be query-local work with no effect on their
     * arrival, score, feasibility, or looplessness semantics.
     */
    public static HorizonReplayContext budgetContext(
            TDGraph graph,
            Domain departureDomain,
            double budget,
            BooleanSupplier cancelled) {
        if (graph == null || departureDomain == null || departureDomain.isEmpty()
                || cancelled == null || budget < 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException(
                    "graph, departure domain, finite budget, and cancellation predicate are required");
        }
        double start = departureDomain.intervals().get(0).start();
        double end = departureDomain.intervals()
                .get(departureDomain.intervals().size() - 1).end();
        return new HorizonReplayContext(
                graph,
                Domain.closed(start, Domain.canonicalTime(end + budget)),
                departureDomain,
                budget,
                cancelled);
    }

    /** Shared exact replay context; it does not enumerate or retain candidate paths. */
    public record ReplayContext(
            TDGraph graph,
            AnchorIndex anchors,
            Domain departureDomain,
            double budget,
            BooleanSupplier cancelled) {
        public Optional<CandidateProfile> replay(
                List<Integer> arcIds, int source, int destination) {
            return CanonicalPathProfileBuilder.replay(
                    graph, anchors, arcIds, source, destination, departureDomain,
                    budget, -1, false, cancelled);
        }
    }

    /**
     * Prepares exact replay constrained only by common temporal support.
     * This is used by preference-free routing, where a PC-Max budget must not
     * affect path selection or path-profile construction.
     */
    public static HorizonReplayContext horizonContext(
            TDGraph graph,
            Domain departureDomain,
            double supportEnd,
            BooleanSupplier cancelled) {
        if (graph == null || departureDomain == null || departureDomain.isEmpty()
                || cancelled == null || !Double.isFinite(supportEnd)) {
            throw new IllegalArgumentException(
                    "graph, departure domain, support end, and cancellation predicate are required");
        }
        double start = departureDomain.intervals().get(0).start();
        if (supportEnd < departureDomain.intervals()
                .get(departureDomain.intervals().size() - 1).end()) {
            throw new IllegalArgumentException(
                    "departure domain extends beyond common temporal support");
        }
        return new HorizonReplayContext(
                graph,
                Domain.closed(start, supportEnd),
                departureDomain,
                Domain.canonicalTime(supportEnd - start),
                cancelled);
    }

    /** Exact score/arrival replay with no PC-Max budget dependency. */
    public record HorizonReplayContext(
            TDGraph graph,
            Domain queryHorizon,
            Domain departureDomain,
            double horizonTravelLimit,
            BooleanSupplier cancelled) {
        public Optional<CandidateProfile> replay(
                List<Integer> arcIds,
                int source,
                int destination) {
            return CanonicalPathProfileBuilder.replay(
                    graph,
                    queryHorizon,
                    Set.of(),
                    arcIds,
                    source,
                    destination,
                    departureDomain,
                    horizonTravelLimit,
                    -1,
                    false,
                    cancelled);
        }
    }
}
