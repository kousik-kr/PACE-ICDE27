package edu.ipcmax.core.labeling;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.LowerBoundOracle;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Point-departure earliest-arrival labeling for FIFO time-dependent graphs.
 */
public final class PointForwardLabeling {
    private final TDGraph graph;

    /**
     * Creates a point forward labeler.
     */
    public PointForwardLabeling(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Computes earliest arrivals from a source at a fixed departure time.
     */
    public Result run(int source, int departureTime, double maxTravelTime) {
        return run(source, departureTime, maxTravelTime, null);
    }

    /**
     * Computes earliest arrivals from a source at an exact, possibly
     * fractional, departure time.
     */
    public Result run(
            int source,
            double departureTime,
            double maxTravelTime) {
        return run(source, departureTime, maxTravelTime, null, null, null);
    }

    /**
     * Computes an exact fixed-departure fastest path and stops after the
     * requested target receives its final FIFO label.
     */
    public Result runToTarget(
            int source,
            int target,
            int departureTime,
            double maxTravelTime) {
        graph.node(target);
        return run(
                source,
                departureTime,
                maxTravelTime,
                Set.of(target),
                null,
                null);
    }

    /**
     * Computes an exact restricted-subgraph fastest path at a possibly
     * fractional departure time.
     *
     * <p>The predicate is evaluated for every prospective directed edge.
     * It is the caller's responsibility to encode a fixed subgraph (for
     * example, a query corridor with selected pivots and previously visited
     * vertices removed). The FIFO labeling itself remains exact in that
     * subgraph.</p>
     */
    public Result runToTarget(
            int source,
            int target,
            double departureTime,
            double maxTravelTime,
            Predicate<Edge> allowedEdge) {
        graph.node(target);
        if (allowedEdge == null) {
            throw new IllegalArgumentException(
                    "allowed-edge predicate is required");
        }
        return run(
                source,
                departureTime,
                maxTravelTime,
                Set.of(target),
                null,
                allowedEdge);
    }

    /**
     * Computes an exact fixed-departure fastest path with an admissible
     * reverse lower-bound potential.
     *
     * <p>The potential must contain distances to {@code target}. The
     * time-dependent FIFO search remains exact because every edge's actual
     * travel time is at least its lower-bound weight.</p>
     */
    public Result runToTarget(
            int source,
            int target,
            int departureTime,
            double maxTravelTime,
            LowerBoundOracle.Labels reverseLowerBounds) {
        graph.node(target);
        if (reverseLowerBounds == null) {
            throw new IllegalArgumentException(
                    "reverse lower-bound labels are required");
        }
        return run(
                source,
                departureTime,
                maxTravelTime,
                Set.of(target),
                reverseLowerBounds,
                null);
    }

    /**
     * Computes exact fixed-departure fastest paths to a target set and stops
     * after every requested target receives its final FIFO label.
     */
    public Result runToTargets(
            int source,
            Set<Integer> targets,
            int departureTime,
            double maxTravelTime) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one target is required");
        }
        for (int target : targets) {
            graph.node(target);
        }
        return run(
                source,
                departureTime,
                maxTravelTime,
                Set.copyOf(targets),
                null,
                null);
    }

    private Result run(
            int source,
            int departureTime,
            double maxTravelTime,
            Set<Integer> targets) {
        return run(
                source,
                departureTime,
                maxTravelTime,
                targets,
                null,
                null);
    }

    private Result run(
            int source,
            double departureTime,
            double maxTravelTime,
            Set<Integer> targets,
            LowerBoundOracle.Labels reverseLowerBounds,
            Predicate<Edge> allowedEdge) {
        if (!Double.isFinite(departureTime)
                || !Double.isFinite(maxTravelTime)
                || maxTravelTime < 0) {
            throw new IllegalArgumentException(
                    "departure and travel budget must be finite and nonnegative");
        }
        Map<Integer, Double> arrival = new HashMap<>();
        Map<Integer, Integer> predecessorArc = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>();
        Set<Integer> remainingTargets = targets == null
                ? null : new HashSet<>(targets);
        arrival.put(source, (double) departureTime);
        double sourcePotential = lowerBound(
                reverseLowerBounds, source);
        if (!Double.isFinite(sourcePotential)) {
            return new Result(source, arrival, predecessorArc, graph);
        }
        queue.add(new Label(
                source,
                departureTime,
                departureTime + sourcePotential));
        double deadline = departureTime + maxTravelTime;

        while (!queue.isEmpty()) {
            Label label = queue.poll();
            if (label.arrivalTime > arrival.getOrDefault(label.node, Double.POSITIVE_INFINITY)) {
                continue;
            }
            if (remainingTargets != null
                    && remainingTargets.remove(label.node)
                    && remainingTargets.isEmpty()) {
                break;
            }
            for (Edge edge : graph.outgoingEdges(label.node)) {
                if (allowedEdge != null && !allowedEdge.test(edge)) {
                    continue;
                }
                double potential = lowerBound(
                        reverseLowerBounds, edge.target());
                if (!Double.isFinite(potential)) {
                    continue;
                }
                double nextArrival;
                try {
                    nextArrival = edge.travelTimeFunction().arrivalTimeAt(label.arrivalTime);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (nextArrival > deadline) {
                    continue;
                }
                double best = arrival.getOrDefault(edge.target(), Double.POSITIVE_INFINITY);
                if (nextArrival < best || (nextArrival == best && edge.arcId() < predecessorArc.getOrDefault(edge.target(), Integer.MAX_VALUE))) {
                    arrival.put(edge.target(), nextArrival);
                    predecessorArc.put(edge.target(), edge.arcId());
                    queue.add(new Label(
                            edge.target(),
                            nextArrival,
                            nextArrival + potential));
                }
            }
        }

        return new Result(source, arrival, predecessorArc, graph);
    }

    private static double lowerBound(
            LowerBoundOracle.Labels labels,
            int node) {
        return labels == null ? 0.0 : labels.distance(node);
    }

    private record Label(
            int node,
            double arrivalTime,
            double estimatedTargetArrival) implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int estimateCompare = Double.compare(
                    estimatedTargetArrival,
                    other.estimatedTargetArrival);
            if (estimateCompare != 0) {
                return estimateCompare;
            }
            int timeCompare = Double.compare(
                    arrivalTime, other.arrivalTime);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Integer.compare(node, other.node);
        }
    }

    /**
     * Earliest-arrival labels and path witnesses.
     */
    public static final class Result {
        private final int source;
        private final Map<Integer, Double> arrival;
        private final Map<Integer, Integer> predecessorArc;
        private final TDGraph graph;

        private Result(int source, Map<Integer, Double> arrival, Map<Integer, Integer> predecessorArc, TDGraph graph) {
            this.source = source;
            this.arrival = Map.copyOf(arrival);
            this.predecessorArc = Map.copyOf(predecessorArc);
            this.graph = graph;
        }

        /**
         * Earliest known arrival at a node, or positive infinity if unreachable.
         */
        public double arrivalAt(int node) {
            return arrival.getOrDefault(node, Double.POSITIVE_INFINITY);
        }

        /**
         * True when the node was reached.
         */
        public boolean reached(int node) {
            return arrival.containsKey(node);
        }

        /**
         * Reconstructs the fastest path to a target.
         */
        public Path pathTo(int target) {
            if (target == source) {
                return Path.empty();
            }
            if (!arrival.containsKey(target)) {
                throw new IllegalArgumentException("target is unreachable: " + target);
            }
            List<Integer> arcs = new ArrayList<>();
            int current = target;
            while (current != source) {
                Integer arcId = predecessorArc.get(current);
                if (arcId == null) {
                    throw new IllegalStateException("missing predecessor for node " + current);
                }
                arcs.add(arcId);
                current = graph.edges().get(arcId).source();
            }
            Collections.reverse(arcs);
            return new Path(arcs);
        }
    }
}
