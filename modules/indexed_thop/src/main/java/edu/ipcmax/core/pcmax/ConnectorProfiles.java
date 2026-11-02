package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.Path;

/**
 * Exact temporal profiles for simple paths in the query-specific anchor-free graph.
 */
public final class ConnectorProfiles {
    private final TDGraph graph;
    private final AnchorIndex anchors;
    private final QueryLowerBounds lowerBounds;
    private final PaceOptions options;

    /** Creates a connector provider for one query. */
    public ConnectorProfiles(
            TDGraph graph,
            AnchorIndex anchors,
            QueryLowerBounds lowerBounds,
            PaceOptions options) {
        this.graph = graph;
        this.anchors = anchors;
        this.lowerBounds = lowerBounds;
        this.options = options;
    }

    /**
     * Generates connector profiles for {@code (u,v,D,B)} according to the configured policy.
     */
    public CandidateSet generate(int source, int destination, Domain domain, double budget) {
        if (budget < 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("connector budget must be finite and nonnegative");
        }
        CandidateSet result = new CandidateSet();
        if (domain == null || domain.isEmpty()) {
            return result;
        }
        if (source == destination) {
            Domain identityDomain = domain.intersection(anchors.queryHorizon());
            if (!identityDomain.isEmpty()) {
                result.add(new CandidateProfile(
                        identityDomain,
                        TimeProfile.identity(identityDomain),
                        ScoreProfile.constant(identityDomain, 0),
                        PathPointer.empty(),
                        0,
                        -1,
                        false));
            }
            return result;
        }

        List<WeightedPath> paths = options.policy() == PaceExecutionPolicy.PACE_X
                ? enumerateExhaustive(source, destination, budget)
                : enumerateBounded(
                        source,
                        destination,
                        domain,
                        budget,
                        options.connectorLimitKc());
        for (WeightedPath path : paths) {
            buildCandidate(path.path(), domain, budget).ifPresent(result::add);
            if (options.policy() == PaceExecutionPolicy.PACE_B
                    && result.size() >= options.connectorLimitKc()) {
                break;
            }
        }
        return result;
    }

    private List<WeightedPath> enumerateExhaustive(int source, int destination, double budget) {
        QueryLowerBounds.Distances toDestination = lowerBounds.distancesTo(destination, anchors.anchorArcIds());
        List<WeightedPath> paths = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerateDepthFirst(
                source,
                destination,
                budget,
                0,
                toDestination,
                visited,
                new ArrayList<>(),
                paths);
        paths.sort(weightedPathOrder());
        return paths;
    }

    private void enumerateDepthFirst(
            int current,
            int destination,
            double budget,
            double pathWeight,
            QueryLowerBounds.Distances toDestination,
            Set<Integer> visited,
            List<Integer> arcIds,
            List<WeightedPath> paths) {
        if (current == destination) {
            paths.add(new WeightedPath(new Path(arcIds), pathWeight));
            return;
        }
        for (Edge edge : graph.outgoingEdges(current)) {
            if (anchors.isAnchorArc(edge.arcId()) || visited.contains(edge.target())) {
                continue;
            }
            double nextWeight = Domain.canonicalTime(
                    pathWeight + lowerBounds.edgeWeight(edge.arcId()));
            double remaining = toDestination.distance(edge.target());
            if (!Double.isFinite(remaining)) {
                continue;
            }
            double completionBound = Domain.canonicalTime(
                    nextWeight + remaining);
            if (nextWeight > Domain.canonicalTime(budget)
                    || completionBound > Domain.canonicalTime(budget)) {
                continue;
            }
            visited.add(edge.target());
            arcIds.add(edge.arcId());
            enumerateDepthFirst(
                    edge.target(),
                    destination,
                    budget,
                    nextWeight,
                    toDestination,
                    visited,
                    arcIds,
                    paths);
            arcIds.remove(arcIds.size() - 1);
            visited.remove(edge.target());
        }
    }

    private List<WeightedPath> enumerateBounded(
            int source,
            int destination,
            Domain domain,
            double budget,
            int limit) {
        QueryLowerBounds.Distances toDestination = lowerBounds.distancesTo(destination, anchors.anchorArcIds());
        if (!Double.isFinite(toDestination.distance(source))
                || toDestination.distance(source) > Domain.canonicalTime(budget)) {
            return List.of();
        }
        PriorityQueue<PathState> queue = new PriorityQueue<>(pathStateOrder());
        queue.add(new PathState(
                source,
                List.of(),
                Set.of(source),
                0,
                toDestination.distance(source)));
        List<WeightedCandidate> feasible = new ArrayList<>();
        double cutoff = Double.POSITIVE_INFINITY;
        while (!queue.isEmpty()) {
            PathState state = queue.poll();
            if (feasible.size() >= limit
                    && Domain.canonicalTime(state.estimatedCompletion())
                    > Domain.canonicalTime(cutoff)) {
                break;
            }
            if (state.node() == destination) {
                Path path = new Path(state.arcIds());
                Optional<CandidateProfile> candidate = buildCandidate(path, domain, budget);
                candidate.ifPresent(value -> feasible.add(new WeightedCandidate(
                        new WeightedPath(path, state.pathWeight()), value)));
                feasible.sort(Comparator.comparing(WeightedCandidate::path, weightedPathOrder()));
                if (feasible.size() >= limit) {
                    cutoff = feasible.get(limit - 1).path().lowerWeight();
                }
                continue;
            }
            for (Edge edge : graph.outgoingEdges(state.node())) {
                if (anchors.isAnchorArc(edge.arcId()) || state.vertices().contains(edge.target())) {
                    continue;
                }
                double nextWeight = Domain.canonicalTime(
                        state.pathWeight() + lowerBounds.edgeWeight(edge.arcId()));
                double remaining = toDestination.distance(edge.target());
                if (!Double.isFinite(remaining)) {
                    continue;
                }
                double estimate = Domain.canonicalTime(
                        nextWeight + remaining);
                if (nextWeight > Domain.canonicalTime(budget)
                        || estimate > Domain.canonicalTime(budget)) {
                    continue;
                }
                List<Integer> arcs = new ArrayList<>(state.arcIds());
                arcs.add(edge.arcId());
                Set<Integer> vertices = new HashSet<>(state.vertices());
                vertices.add(edge.target());
                queue.add(new PathState(
                        edge.target(),
                        List.copyOf(arcs),
                        Set.copyOf(vertices),
                        nextWeight,
                        estimate));
            }
        }
        feasible.sort(Comparator.comparing(WeightedCandidate::path, weightedPathOrder()));
        return feasible.stream().limit(limit).map(WeightedCandidate::path).toList();
    }

    private Optional<CandidateProfile> buildCandidate(Path path, Domain requestedDomain, double budget) {
        for (int arcId : path.arcIds()) {
            if (anchors.isAnchorArc(arcId)) {
                throw new IllegalStateException("connector contains anchor arc " + arcId);
            }
        }
        Edge first = graph.edges().get(path.arcIds().get(0));
        Edge last = graph.edges().get(path.arcIds().get(path.arcIds().size() - 1));
        Optional<CandidateProfile> candidate = CanonicalPathProfileBuilder.replay(
                graph,
                anchors,
                path.arcIds(),
                first.source(),
                last.target(),
                requestedDomain,
                budget,
                -1,
                false);
        candidate.ifPresent(value -> {
            if (value.explicitAnchorCount() != 0) {
                throw new IllegalStateException("connector canonicalization introduced an anchor");
            }
        });
        return candidate;
    }

    private static Comparator<WeightedPath> weightedPathOrder() {
        return Comparator.comparingDouble(WeightedPath::lowerWeight)
                .thenComparingInt(item -> item.path().arcIds().size())
                .thenComparing(item -> item.path().arcIds(), ConnectorProfiles::comparePathIds);
    }

    private static Comparator<PathState> pathStateOrder() {
        return Comparator.comparingDouble(PathState::estimatedCompletion)
                .thenComparingDouble(PathState::pathWeight)
                .thenComparingInt(item -> item.arcIds().size())
                .thenComparing(PathState::arcIds, ConnectorProfiles::comparePathIds)
                .thenComparingInt(PathState::node);
    }

    private static int comparePathIds(List<Integer> first, List<Integer> second) {
        int common = Math.min(first.size(), second.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(first.get(i), second.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    private record WeightedPath(Path path, double lowerWeight) {
    }

    private record WeightedCandidate(WeightedPath path, CandidateProfile candidate) {
    }

    private record PathState(
            int node,
            List<Integer> arcIds,
            Set<Integer> vertices,
            double pathWeight,
            double estimatedCompletion) {
    }
}
