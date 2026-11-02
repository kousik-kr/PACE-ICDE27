package edu.ipcmax.core.pcmax;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * Replays one stable path edge by edge to obtain a decomposition-independent
 * temporal profile.
 *
 * <p>PACE can discover the same path through several legal anchor split trees.
 * A final path profile must therefore depend only on the ordered arc ids and
 * the current subproblem, never on the split tree that happened to discover
 * it. This builder is the single normalization boundary for that invariant.</p>
 */
final class CanonicalPathProfileBuilder {
    private CanonicalPathProfileBuilder() {
    }

    static Optional<CandidateProfile> replay(
            TDGraph graph,
            AnchorIndex anchors,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed) {
        return replay(
                graph, anchors, arcIds, source, destination, requestedDomain,
                budget, pivotId, compressed, () -> false);
    }

    static Optional<CandidateProfile> replay(
            TDGraph graph,
            AnchorIndex anchors,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed,
            BooleanSupplier cancelled) {
        if (anchors == null) {
            throw new IllegalArgumentException("anchor index is required");
        }
        return replay(
                graph,
                anchors.queryHorizon(),
                anchors.anchorArcIds(),
                arcIds,
                source,
                destination,
                requestedDomain,
                budget,
                pivotId,
                compressed,
                cancelled);
    }

    /**
     * Replays a path for the layered engine using only the selected query-wide
     * pivot set. Non-selected score-bearing arcs remain ordinary scored arcs.
     */
    static Optional<CandidateProfile> replay(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed) {
        return replay(
                graph, queryHorizon, selectedPivotArcIds, arcIds, source,
                destination, requestedDomain, budget, pivotId, compressed,
                () -> false);
    }

    static Optional<CandidateProfile> replay(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            List<Integer> arcIds,
            int source,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed,
            BooleanSupplier cancelled) {
        if (graph == null
                || queryHorizon == null
                || selectedPivotArcIds == null
                || arcIds == null
                || requestedDomain == null) {
            throw new IllegalArgumentException("graph, anchor index, path, and requested domain are required");
        }
        Domain rootDomain = requestedDomain.intersection(queryHorizon);
        if (rootDomain.isEmpty()) {
            return Optional.empty();
        }
        if (budget < 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("travel budget must be finite and nonnegative");
        }

        int current = source;
        int explicitAnchorCount = 0;
        Set<Integer> usedPivotArcIds = new HashSet<>();
        Set<Integer> vertices = new HashSet<>();
        Set<Integer> edges = new HashSet<>();
        vertices.add(source);
        TimeProfile arrival = TimeProfile.identity(rootDomain);
        ScoreProfile score = ScoreProfile.constant(rootDomain, 0);

        return continueReplay(
                graph,
                queryHorizon,
                selectedPivotArcIds,
                arcIds,
                arcIds,
                source,
                current,
                destination,
                rootDomain,
                budget,
                pivotId,
                compressed,
                arrival,
                score,
                vertices,
                edges,
                explicitAnchorCount,
                usedPivotArcIds,
                cancelled);
    }

    /**
     * Extends an exact retained prefix without replaying its temporal edges.
     *
     * <p>The prefix is still structurally verified edge by edge. Only temporal
     * composition is reused, and the final fingerprints are the same canonical
     * full-path fingerprints produced by {@link #replay}.</p>
     */
    static Optional<CandidateProfile> extend(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            CandidateProfile prefix,
            int source,
            int prefixEndpoint,
            List<Integer> suffixArcIds,
            int destination,
            Domain requestedDomain,
            double budget,
            int pivotId,
            boolean compressed) {
        if (graph == null
                || queryHorizon == null
                || selectedPivotArcIds == null
                || prefix == null
                || suffixArcIds == null
                || requestedDomain == null) {
            throw new IllegalArgumentException(
                    "graph, pivot set, prefix, suffix, and domain are required");
        }
        if (budget < 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException(
                    "travel budget must be finite and nonnegative");
        }
        Domain rootDomain = requestedDomain
                .intersection(queryHorizon)
                .intersection(prefix.domain());
        if (rootDomain.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> prefixArcIds = prefix.stablePathId();
        List<Integer> fullArcIds = new java.util.ArrayList<>(
                prefixArcIds.size() + suffixArcIds.size());
        fullArcIds.addAll(prefixArcIds);
        fullArcIds.addAll(suffixArcIds);
        fullArcIds = List.copyOf(fullArcIds);

        int current = source;
        int explicitAnchorCount = 0;
        Set<Integer> usedPivotArcIds = new HashSet<>();
        Set<Integer> vertices = new HashSet<>();
        Set<Integer> edges = new HashSet<>();
        vertices.add(source);
        for (int arcId : prefixArcIds) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException(
                        "candidate contains unknown arc id: " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (!edges.add(arcId)) {
                throw new IllegalArgumentException(
                        "candidate prefix repeats directed arc "
                                + arcId + ": " + prefixArcIds);
            }
            if (edge.source() != current) {
                throw new IllegalArgumentException(
                        "candidate prefix is discontinuous at arc " + arcId);
            }
            if (!vertices.add(edge.target())) {
                throw new IllegalArgumentException(
                        "candidate prefix is not vertex-simple: "
                                + prefixArcIds);
            }
            if (selectedPivotArcIds.contains(arcId)) {
                explicitAnchorCount++;
                usedPivotArcIds.add(arcId);
            }
            current = edge.target();
        }
        if (current != prefixEndpoint
                || explicitAnchorCount != prefix.explicitAnchorCount()
                || !usedPivotArcIds.equals(prefix.usedPivotArcIds())) {
            throw new IllegalArgumentException(
                    "retained prefix metadata is inconsistent");
        }
        TimeProfile arrival = prefix.arrivalProfile().domain()
                .equals(rootDomain)
                ? prefix.arrivalProfile()
                : prefix.arrivalProfile().restrict(rootDomain);
        ScoreProfile score = prefix.scoreProfile().domain()
                .equals(rootDomain)
                ? prefix.scoreProfile()
                : prefix.scoreProfile().restrict(rootDomain);
        TemporalProfileWork.add(
                "canonical_replay_prefix_edges_reused",
                prefixArcIds.size());
        return continueReplay(
                graph,
                queryHorizon,
                selectedPivotArcIds,
                fullArcIds,
                suffixArcIds,
                source,
                current,
                destination,
                rootDomain,
                budget,
                pivotId,
                compressed,
                arrival,
                score,
                vertices,
                edges,
                explicitAnchorCount,
                usedPivotArcIds);
    }

    private static Optional<CandidateProfile> continueReplay(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            List<Integer> fullArcIds,
            List<Integer> remainingArcIds,
            int source,
            int initialCurrent,
            int destination,
            Domain rootDomain,
            double budget,
            int pivotId,
            boolean compressed,
            TimeProfile initialArrival,
            ScoreProfile initialScore,
            Set<Integer> vertices,
            Set<Integer> edges,
            int initialExplicitAnchorCount,
            Set<Integer> initialUsedPivotArcIds) {
        return continueReplay(
                graph, queryHorizon, selectedPivotArcIds, fullArcIds,
                remainingArcIds, source, initialCurrent, destination,
                rootDomain, budget, pivotId, compressed, initialArrival,
                initialScore, vertices, edges, initialExplicitAnchorCount,
                initialUsedPivotArcIds, () -> false);
    }

    private static Optional<CandidateProfile> continueReplay(
            TDGraph graph,
            Domain queryHorizon,
            Set<Integer> selectedPivotArcIds,
            List<Integer> fullArcIds,
            List<Integer> remainingArcIds,
            int source,
            int initialCurrent,
            int destination,
            Domain rootDomain,
            double budget,
            int pivotId,
            boolean compressed,
            TimeProfile initialArrival,
            ScoreProfile initialScore,
            Set<Integer> vertices,
            Set<Integer> edges,
            int initialExplicitAnchorCount,
            Set<Integer> initialUsedPivotArcIds,
            BooleanSupplier cancelled) {
        if (cancelled == null) {
            throw new IllegalArgumentException("cancellation predicate is required");
        }
        int current = initialCurrent;
        int explicitAnchorCount = initialExplicitAnchorCount;
        Set<Integer> usedPivotArcIds =
                new HashSet<>(initialUsedPivotArcIds);
        TimeProfile arrival = initialArrival;
        ScoreProfile score = initialScore;
        TemporalProfileWork.add(
                "canonical_replay_edges", remainingArcIds.size());

        for (int arcId : remainingArcIds) {
            requireActive(cancelled);
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("candidate contains unknown arc id: " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (!edges.add(arcId)) {
                throw new IllegalArgumentException(
                        "candidate path repeats directed arc "
                                + arcId + ": " + fullArcIds);
            }
            if (edge.source() != current) {
                throw new IllegalArgumentException(
                        "candidate path is discontinuous at arc " + arcId
                                + ": expected source " + current);
            }
            if (!vertices.add(edge.target())) {
                throw new IllegalArgumentException(
                        "candidate path is not vertex-simple: " + fullArcIds);
            }
            if (selectedPivotArcIds.contains(arcId)) {
                explicitAnchorCount++;
                usedPivotArcIds.add(arcId);
            }

            Domain validEntry = PaceProfiles.validEntryDomain(
                    edge, queryHorizon);
            Domain entryDomain = arrival.preimage(validEntry, arrival.domain());
            if (entryDomain.isEmpty()) {
                return Optional.empty();
            }

            TimeProfile edgeEntry = arrival.restrict(entryDomain);
            ScoreProfile scoreBeforeEdge = score.restrict(entryDomain);
            ScoreProfile edgeScore = ScoreProfile.compose(
                    edgeEntry,
                    edge.scoreFunction(),
                    entryDomain,
                    "canonical-path-edge-score:path=" + fullArcIds
                            + ":arc=" + arcId);
            TimeProfile edgeArrival = PaceProfiles.edgeArrivalProfile(
                    edge,
                    validEntry,
                    "canonical-path-edge-arrival:path=" + fullArcIds);
            arrival = edgeEntry.compose(
                    edgeArrival,
                    "canonical-path-arrival:path=" + fullArcIds
                            + ":arc=" + arcId);
            score = scoreBeforeEdge.add(
                    edgeScore,
                    entryDomain,
                    "canonical-path-score:path=" + fullArcIds
                            + ":arc=" + arcId);
            current = edge.target();
        }

        requireActive(cancelled);

        if (current != destination) {
            throw new IllegalArgumentException(
                    "candidate path ends at " + current
                            + " instead of subproblem destination " + destination);
        }

        Domain withinHorizon = arrival.preimage(
                queryHorizon, arrival.domain());
        if (withinHorizon.isEmpty()) {
            return Optional.empty();
        }
        TimeProfile horizonArrival = arrival.restrict(withinHorizon);
        Domain feasible = horizonArrival.domainWhereTravelTimeAtMost(withinHorizon, budget);
        if (feasible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CandidateProfile(
                feasible,
                horizonArrival.restrict(feasible),
                score.restrict(feasible),
                PathPointer.of(fullArcIds),
                explicitAnchorCount,
                pivotId,
                compressed,
                usedPivotArcIds));
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "exact path profiling reached its query deadline");
        }
    }
}
