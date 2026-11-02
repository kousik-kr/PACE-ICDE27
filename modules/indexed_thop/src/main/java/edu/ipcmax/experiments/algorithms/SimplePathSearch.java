package edu.ipcmax.experiments.algorithms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.LowerBoundGraph;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.experiments.framework.LimitExceededException;

/** Deterministic full-graph simple-path searches shared by independent baselines. */
final class SimplePathSearch {
    private SimplePathSearch() {
    }

    static SearchResult exhaustive(
            TDGraph graph, int source, int destination, double budget, long maxPaths) {
        LowerBoundGraph lower = new LowerBoundGraph(graph);
        LowerBoundGraph.Distances toTarget = lower.distancesToTarget(destination);
        List<WeightedPath> paths = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        long[] rejected = {0};
        dfs(graph, lower, toTarget, source, destination, budget, 0, maxPaths,
                visited, new ArrayList<>(), paths, rejected);
        paths.sort(PATH_ORDER);
        return new SearchResult(List.copyOf(paths), rejected[0], false);
    }

    /** Streams complete loopless paths in deterministic DFS order. */
    static StreamingSearchResult exhaustiveStreaming(
            TDGraph graph,
            int source,
            int destination,
            double budget,
            long maxPaths,
            BooleanSupplier stopRequested,
            CompletePathVisitor visitor) {
        if (maxPaths < 1 || stopRequested == null || visitor == null) {
            throw new IllegalArgumentException(
                    "positive path cap, stop predicate, and visitor are required");
        }
        LowerBoundGraph lower = new LowerBoundGraph(graph);
        LowerBoundGraph.Distances toTarget = lower.distancesToTarget(
                destination, stopRequested);
        return exhaustiveStreaming(
                graph, lower, toTarget, source, destination, budget,
                maxPaths, stopRequested, visitor);
    }

    /**
     * Correctness fallback: enumerate every directed vertex-simple path without
     * static lower-bound or budget pruning. Exact route replay decides feasibility.
     */
    static StreamingSearchResult exhaustiveStreamingUnpruned(
            TDGraph graph,
            int source,
            int destination,
            long maxPaths,
            BooleanSupplier stopRequested,
            CompletePathVisitor visitor) {
        if (graph == null || maxPaths < 1 || stopRequested == null || visitor == null) {
            throw new IllegalArgumentException("graph, positive path cap, stop predicate, and visitor are required");
        }
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        StreamingState state = new StreamingState();
        boolean exhausted = dfsStreamingUnpruned(
                graph, source, destination, maxPaths, visited,
                new ArrayList<>(), stopRequested, visitor, state);
        return new StreamingSearchResult(state.completePaths, state.dfsExpansions,
                0L, exhausted, state.deadlineReached, state.pathCapReached);
    }

    private static boolean dfsStreamingUnpruned(
            TDGraph graph,
            int node,
            int destination,
            long maxPaths,
            Set<Integer> visited,
            List<Integer> arcs,
            BooleanSupplier stopRequested,
            CompletePathVisitor visitor,
            StreamingState state) {
        if (stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            state.deadlineReached = true;
            return false;
        }
        if (node == destination) {
            if (state.completePaths >= maxPaths) {
                state.pathCapReached = true;
                return false;
            }
            state.completePaths++;
            return visitor.visit(new WeightedPath(List.copyOf(arcs), 0.0));
        }
        for (Edge edge : graph.outgoingEdges(node)) {
            if (stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                state.deadlineReached = true;
                return false;
            }
            state.dfsExpansions++;
            if (visited.contains(edge.target())) continue;
            visited.add(edge.target());
            arcs.add(edge.arcId());
            boolean keepGoing = dfsStreamingUnpruned(graph, edge.target(), destination,
                    maxPaths, visited, arcs, stopRequested, visitor, state);
            arcs.remove(arcs.size() - 1);
            visited.remove(edge.target());
            if (!keepGoing) return false;
        }
        return true;
    }

    /** Streams paths while reusing an already timed query-local lower-bound search. */
    static StreamingSearchResult exhaustiveStreaming(
            TDGraph graph,
            LowerBoundGraph lower,
            LowerBoundGraph.Distances toTarget,
            int source,
            int destination,
            double budget,
            long maxPaths,
            BooleanSupplier stopRequested,
            CompletePathVisitor visitor) {
        if (graph == null || lower == null || toTarget == null) {
            throw new IllegalArgumentException("graph and lower-bound labels are required");
        }
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        StreamingState state = new StreamingState();
        boolean exhausted = dfsStreaming(
                graph, lower, toTarget, source, destination, budget, 0,
                maxPaths, visited, new ArrayList<>(), stopRequested,
                visitor, state);
        return new StreamingSearchResult(
                state.completePaths,
                state.dfsExpansions,
                state.rejectedLowerBound,
                exhausted,
                state.deadlineReached,
                state.pathCapReached);
    }

    private static boolean dfsStreaming(
            TDGraph graph,
            LowerBoundGraph lower,
            LowerBoundGraph.Distances toTarget,
            int node,
            int destination,
            double budget,
            double weight,
            long maxPaths,
            Set<Integer> visited,
            List<Integer> arcs,
            BooleanSupplier stopRequested,
            CompletePathVisitor visitor,
            StreamingState state) {
        if (stopRequested.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            state.deadlineReached = true;
            return false;
        }
        if (node == destination) {
            if (state.completePaths >= maxPaths) {
                state.pathCapReached = true;
                return false;
            }
            state.completePaths++;
            return visitor.visit(new WeightedPath(List.copyOf(arcs), weight));
        }
        for (Edge edge : graph.outgoingEdges(node)) {
            if (stopRequested.getAsBoolean()
                    || Thread.currentThread().isInterrupted()) {
                state.deadlineReached = true;
                return false;
            }
            state.dfsExpansions++;
            if (visited.contains(edge.target())) {
                continue;
            }
            double next = Domain.canonicalTime(
                    weight + lower.weight(edge.arcId()));
            double remaining = toTarget.distance(edge.target());
            if (!Double.isFinite(remaining)
                    || Domain.canonicalTime(next + remaining)
                            > Domain.canonicalTime(budget)) {
                state.rejectedLowerBound++;
                continue;
            }
            visited.add(edge.target());
            arcs.add(edge.arcId());
            boolean keepGoing = dfsStreaming(
                    graph, lower, toTarget, edge.target(), destination,
                    budget, next, maxPaths, visited, arcs,
                    stopRequested, visitor, state);
            arcs.remove(arcs.size() - 1);
            visited.remove(edge.target());
            if (!keepGoing) {
                return false;
            }
        }
        return true;
    }

    private static void dfs(
            TDGraph graph,
            LowerBoundGraph lower,
            LowerBoundGraph.Distances toTarget,
            int node,
            int destination,
            double budget,
            double weight,
            long maxPaths,
            Set<Integer> visited,
            List<Integer> arcs,
            List<WeightedPath> output,
            long[] rejected) {
        if (node == destination) {
            if (output.size() >= maxPaths) {
                throw new LimitExceededException("maximum enumerated paths exceeded: " + maxPaths);
            }
            output.add(new WeightedPath(List.copyOf(arcs), weight));
            return;
        }
        for (Edge edge : graph.outgoingEdges(node)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            double next = Domain.canonicalTime(weight + lower.weight(edge.arcId()));
            double remaining = toTarget.distance(edge.target());
            if (!Double.isFinite(remaining)
                    || Domain.canonicalTime(next + remaining) > Domain.canonicalTime(budget)) {
                rejected[0]++;
                continue;
            }
            visited.add(edge.target());
            arcs.add(edge.arcId());
            dfs(graph, lower, toTarget, edge.target(), destination, budget, next, maxPaths,
                    visited, arcs, output, rejected);
            arcs.remove(arcs.size() - 1);
            visited.remove(edge.target());
        }
    }

    static SearchResult lowerBoundOrder(
            TDGraph graph, int source, int destination, double budget, long maxCompleted) {
        LowerBoundGraph lower = new LowerBoundGraph(graph);
        LowerBoundGraph.Distances toTarget = lower.distancesToTarget(destination);
        PriorityQueue<State> queue = new PriorityQueue<>(STATE_ORDER);
        queue.add(new State(source, List.of(), Set.of(source), 0, toTarget.distance(source)));
        List<WeightedPath> completed = new ArrayList<>();
        long rejected = 0;
        boolean limitReached = false;
        while (!queue.isEmpty()) {
            State state = queue.poll();
            if (state.node == destination) {
                completed.add(new WeightedPath(state.arcs, state.weight));
                if (completed.size() >= maxCompleted) {
                    limitReached = !queue.isEmpty();
                    break;
                }
                continue;
            }
            for (Edge edge : graph.outgoingEdges(state.node)) {
                if (state.vertices.contains(edge.target())) {
                    continue;
                }
                double next = Domain.canonicalTime(state.weight + lower.weight(edge.arcId()));
                double remaining = toTarget.distance(edge.target());
                if (!Double.isFinite(remaining)
                        || Domain.canonicalTime(next + remaining) > Domain.canonicalTime(budget)) {
                    rejected++;
                    continue;
                }
                List<Integer> arcs = new ArrayList<>(state.arcs);
                arcs.add(edge.arcId());
                Set<Integer> vertices = new HashSet<>(state.vertices);
                vertices.add(edge.target());
                queue.add(new State(edge.target(), List.copyOf(arcs), Set.copyOf(vertices), next,
                        Domain.canonicalTime(next + remaining)));
            }
        }
        completed.sort(PATH_ORDER);
        return new SearchResult(List.copyOf(completed), rejected, limitReached);
    }

    static final Comparator<WeightedPath> PATH_ORDER = Comparator
            .comparingDouble(WeightedPath::weight)
            .thenComparingInt(path -> path.arcs().size())
            .thenComparing(WeightedPath::arcs, SimplePathSearch::compareArcs);

    private static final Comparator<State> STATE_ORDER = Comparator
            .comparingDouble(State::estimate)
            .thenComparingDouble(State::weight)
            .thenComparingInt(state -> state.arcs().size())
            .thenComparing(State::arcs, SimplePathSearch::compareArcs)
            .thenComparingInt(State::node);

    private static int compareArcs(List<Integer> left, List<Integer> right) {
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            int comparison = Integer.compare(left.get(index), right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    record WeightedPath(List<Integer> arcs, double weight) {
    }

    record SearchResult(List<WeightedPath> paths, long rejectedLowerBound, boolean limitReached) {
    }

    @FunctionalInterface
    interface CompletePathVisitor {
        /** Returns false to stop the DFS after this complete path. */
        boolean visit(WeightedPath path);
    }

    record StreamingSearchResult(
            long completePaths,
            long dfsExpansions,
            long rejectedLowerBound,
            boolean exhausted,
            boolean deadlineReached,
            boolean pathCapReached) {
    }

    private static final class StreamingState {
        private long completePaths;
        private long dfsExpansions;
        private long rejectedLowerBound;
        private boolean deadlineReached;
        private boolean pathCapReached;
    }

    private record State(
            int node, List<Integer> arcs, Set<Integer> vertices, double weight, double estimate) {
    }
}
