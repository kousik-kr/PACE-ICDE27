package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;

/** Query-horizon lower travel-time weights and admissible static distances. */
public final class QueryLowerBounds {
    private static final int MAX_DENSE_ID_FACTOR = 2;

    private final TDGraph graph;
    private final double[] weights;
    private final EdgeTemporalSummaryStore summaries;
    private final int maximumNodeId;
    private final boolean denseNodeIds;
    private final Map<Integer, Distances> forward =
            new ConcurrentHashMap<>();
    private final Map<Integer, Distances> reverse =
            new ConcurrentHashMap<>();
    private final Map<RadiusKey, Distances> truncatedForward =
            new ConcurrentHashMap<>();
    private final Map<RadiusKey, Distances> truncatedReverse =
            new ConcurrentHashMap<>();

    /** Builds {@code inf T_q tau_e} weights for every edge. */
    public QueryLowerBounds(TDGraph graph, Domain queryHorizon) {
        this.graph = java.util.Objects.requireNonNull(
                graph, "graph");
        this.maximumNodeId = maximumNodeId(graph);
        this.denseNodeIds = useDenseLabels(
                graph, maximumNodeId);
        this.weights = new double[graph.edgeCount()];
        this.summaries = null;
        for (Edge edge : graph.edges()) {
            double weight = PaceProfiles.minimumTravelTime(
                    edge, queryHorizon);
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IllegalArgumentException(
                        "edge lower travel time must be finite and "
                                + "nonnegative: arc " + edge.arcId());
            }
            weights[edge.arcId()] = weight;
        }
    }

    /** Reuses prepared all-support edge minima. */
    public QueryLowerBounds(
            TDGraph graph,
            EdgeTemporalSummaryStore summaries) {
        this.graph = java.util.Objects.requireNonNull(
                graph, "graph");
        this.maximumNodeId = maximumNodeId(graph);
        this.denseNodeIds = useDenseLabels(
                graph, maximumNodeId);
        this.summaries = java.util.Objects.requireNonNull(
                summaries, "summaries");
        this.weights = null;
        if (summaries.size() != graph.edgeCount()) {
            throw new IllegalArgumentException(
                    "edge summary count does not match graph");
        }
    }

    public double edgeWeight(int arcId) {
        return summaries == null
                ? weights[arcId]
                : summaries.summary(arcId)
                        .lowerBoundTravelTime();
    }

    public double distance(int source, int destination) {
        return distancesFrom(source).distance(destination);
    }

    public Distances distancesFrom(int source) {
        return forward.computeIfAbsent(
                source,
                node -> dijkstra(node, true, Set.of()));
    }

    public Distances distancesTo(int destination) {
        return reverse.computeIfAbsent(
                destination,
                node -> dijkstra(node, false, Set.of()));
    }

    public Distances distancesTo(
            int destination,
            Set<Integer> excludedArcIds) {
        return excludedArcIds.isEmpty()
                ? distancesTo(destination)
                : dijkstra(
                        destination, false, excludedArcIds);
    }

    public Distances truncatedDistancesFrom(
            int source,
            double maximumDistance) {
        RadiusKey key = RadiusKey.of(
                source, maximumDistance);
        return truncatedForward.computeIfAbsent(
                key,
                ignored -> dijkstra(
                        source,
                        true,
                        Set.of(),
                        key.maximumDistance()));
    }

    public Distances truncatedDistancesTo(
            int destination,
            double maximumDistance) {
        RadiusKey key = RadiusKey.of(
                destination, maximumDistance);
        return truncatedReverse.computeIfAbsent(
                key,
                ignored -> dijkstra(
                        destination,
                        false,
                        Set.of(),
                        key.maximumDistance()));
    }

    public double distanceWithin(
            int source,
            int destination,
            double maximumDistance) {
        return truncatedDistancesFrom(
                source, maximumDistance)
                .distance(destination);
    }

    private Distances dijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds) {
        return dijkstra(
                start,
                outgoing,
                excludedArcIds,
                Double.POSITIVE_INFINITY);
    }

    private Distances dijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds,
            double maximumDistance) {
        if (Double.isNaN(maximumDistance)
                || maximumDistance < 0) {
            throw new IllegalArgumentException(
                    "maximum lower-bound distance must be "
                            + "nonnegative");
        }
        double maximum = Double.isFinite(maximumDistance)
                ? Domain.canonicalTime(maximumDistance)
                : Double.POSITIVE_INFINITY;
        return denseNodeIds
                ? denseDijkstra(
                        start,
                        outgoing,
                        excludedArcIds,
                        maximum)
                : sparseDijkstra(
                        start,
                        outgoing,
                        excludedArcIds,
                        maximum);
    }

    private Distances sparseDijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds,
            double maximum) {
        Map<Integer, Double> distance = new HashMap<>();
        Map<Integer, Integer> edgeCounts = new HashMap<>();
        Map<Integer, Integer> witnessArcs = new HashMap<>();
        PriorityQueue<Label> queue =
                new PriorityQueue<>();
        distance.put(start, 0.0);
        edgeCounts.put(start, 0);
        queue.add(new Label(start, 0.0, 0));
        while (!queue.isEmpty()) {
            Label current = queue.poll();
            if (current.distance() != distance.getOrDefault(
                    current.node(), Double.POSITIVE_INFINITY)
                    || current.edgeCount() != edgeCounts.getOrDefault(
                            current.node(), Integer.MAX_VALUE)) {
                continue;
            }
            if (current.distance() > maximum) {
                break;
            }
            Iterable<Edge> adjacent = outgoing
                    ? graph.outgoingEdges(current.node())
                    : graph.incomingEdges(current.node());
            for (Edge edge : adjacent) {
                if (excludedArcIds.contains(edge.arcId())) {
                    continue;
                }
                int next = outgoing
                        ? edge.target() : edge.source();
                double candidate = Domain.canonicalTime(
                        current.distance()
                                + edgeWeight(edge.arcId()));
                int candidateEdges = Math.addExact(
                        current.edgeCount(), 1);
                double knownDistance = distance.getOrDefault(
                        next, Double.POSITIVE_INFINITY);
                int knownEdges = edgeCounts.getOrDefault(
                        next, Integer.MAX_VALUE);
                boolean improves = candidate < knownDistance
                        || (candidate == knownDistance
                            && candidateEdges < knownEdges);
                boolean witnessImproves = candidate == knownDistance
                        && candidateEdges == knownEdges
                        && edge.arcId() < witnessArcs.getOrDefault(
                                next, Integer.MAX_VALUE);
                if (candidate <= maximum && improves) {
                    distance.put(next, candidate);
                    edgeCounts.put(next, candidateEdges);
                    witnessArcs.put(next, edge.arcId());
                    queue.add(new Label(
                            next, candidate, candidateEdges));
                } else if (candidate <= maximum
                        && witnessImproves) {
                    witnessArcs.put(next, edge.arcId());
                }
            }
        }
        return new Distances(
                start, outgoing, graph,
                distance, edgeCounts, witnessArcs);
    }

    private Distances denseDijkstra(
            int start,
            boolean outgoing,
            Set<Integer> excludedArcIds,
            double maximum) {
        graph.node(start);
        double[] distance =
                new double[maximumNodeId + 1];
        int[] edgeCounts = new int[maximumNodeId + 1];
        int[] witnessArcs = new int[maximumNodeId + 1];
        Arrays.fill(
                distance, Double.POSITIVE_INFINITY);
        Arrays.fill(edgeCounts, Integer.MAX_VALUE);
        Arrays.fill(witnessArcs, -1);
        DenseNodeHeap queue =
                new DenseNodeHeap(distance, edgeCounts);
        IntAccumulator reached = new IntAccumulator();
        distance[start] = 0;
        edgeCounts[start] = 0;
        reached.add(start);
        queue.addOrDecrease(start);
        while (!queue.isEmpty()) {
            int current = queue.removeMinimum();
            double currentDistance = distance[current];
            if (currentDistance > maximum) {
                break;
            }
            Iterable<Edge> adjacent = outgoing
                    ? graph.outgoingEdges(current)
                    : graph.incomingEdges(current);
            for (Edge edge : adjacent) {
                if (excludedArcIds.contains(edge.arcId())) {
                    continue;
                }
                int next = outgoing
                        ? edge.target() : edge.source();
                double candidate = Domain.canonicalTime(
                        currentDistance
                                + edgeWeight(edge.arcId()));
                int candidateEdges = Math.addExact(
                        edgeCounts[current], 1);
                boolean improves = candidate < distance[next]
                        || (candidate == distance[next]
                            && candidateEdges < edgeCounts[next]);
                boolean witnessImproves = candidate == distance[next]
                        && candidateEdges == edgeCounts[next]
                        && (witnessArcs[next] < 0
                            || edge.arcId() < witnessArcs[next]);
                if (candidate > maximum
                        || (!improves && !witnessImproves)) {
                    continue;
                }
                if (improves && !Double.isFinite(distance[next])) {
                    reached.add(next);
                }
                if (improves) {
                    distance[next] = candidate;
                    edgeCounts[next] = candidateEdges;
                    witnessArcs[next] = edge.arcId();
                    queue.addOrDecrease(next);
                } else {
                    witnessArcs[next] = edge.arcId();
                }
            }
        }
        int[] reachedNodes = reached.toArray();
        Arrays.sort(reachedNodes);
        return new Distances(
                start, outgoing, graph,
                distance, edgeCounts, witnessArcs,
                reachedNodes);
    }

    private static int maximumNodeId(TDGraph graph) {
        List<Integer> ids = graph.nodeIds();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "lower-bound graph cannot be empty");
        }
        return ids.get(ids.size() - 1);
    }

    private static boolean useDenseLabels(
            TDGraph graph,
            int maximumNodeId) {
        return maximumNodeId <= Math.max(
                1L,
                (long) graph.nodeCount()
                        * MAX_DENSE_ID_FACTOR);
    }

    private record Label(
            int node,
            double distance,
            int edgeCount)
            implements Comparable<Label> {
        @Override
        public int compareTo(Label other) {
            int byDistance = Double.compare(
                    distance, other.distance);
            if (byDistance != 0) {
                return byDistance;
            }
            int byEdges = Integer.compare(
                    edgeCount, other.edgeCount);
            return byEdges != 0
                    ? byEdges
                    : Integer.compare(node, other.node);
        }
    }

    private record RadiusKey(
            int node,
            double maximumDistance) {
        static RadiusKey of(
                int node,
                double maximumDistance) {
            if (!Double.isFinite(maximumDistance)
                    || maximumDistance < 0) {
                throw new IllegalArgumentException(
                        "truncated distance radius must be "
                                + "finite and nonnegative");
            }
            return new RadiusKey(
                    node,
                    Domain.canonicalTime(maximumDistance));
        }
    }

    /** Immutable distance lookup. */
    public static final class Distances {
        private final int start;
        private final boolean outgoing;
        private final TDGraph graph;
        private final Map<Integer, Double> values;
        private final Map<Integer, Integer> sparseEdgeCounts;
        private final Map<Integer, Integer> sparseWitnessArcs;
        private final double[] denseValues;
        private final int[] denseEdgeCounts;
        private final int[] denseWitnessArcs;
        private final int[] denseReachedNodes;

        private Distances(
                int start,
                boolean outgoing,
                TDGraph graph,
                Map<Integer, Double> values,
                Map<Integer, Integer> edgeCounts,
                Map<Integer, Integer> witnessArcs) {
            this.start = start;
            this.outgoing = outgoing;
            this.graph = graph;
            this.values = Map.copyOf(values);
            this.sparseEdgeCounts = Map.copyOf(edgeCounts);
            this.sparseWitnessArcs = Map.copyOf(witnessArcs);
            this.denseValues = null;
            this.denseEdgeCounts = null;
            this.denseWitnessArcs = null;
            this.denseReachedNodes = null;
        }

        private Distances(
                int start,
                boolean outgoing,
                TDGraph graph,
                double[] denseValues,
                int[] denseEdgeCounts,
                int[] denseWitnessArcs,
                int[] denseReachedNodes) {
            this.start = start;
            this.outgoing = outgoing;
            this.graph = graph;
            this.values = null;
            this.sparseEdgeCounts = null;
            this.sparseWitnessArcs = null;
            this.denseValues = denseValues;
            this.denseEdgeCounts = denseEdgeCounts;
            this.denseWitnessArcs = denseWitnessArcs;
            this.denseReachedNodes = denseReachedNodes;
        }

        /** Root vertex of this lower-bound labeling. */
        public int start() {
            return start;
        }

        /** True for source-to-vertex labels; false for vertex-to-target labels. */
        public boolean outgoing() {
            return outgoing;
        }

        public double distance(int node) {
            if (denseValues != null) {
                return node >= 0
                        && node < denseValues.length
                        ? denseValues[node]
                        : Double.POSITIVE_INFINITY;
            }
            return values.getOrDefault(
                    node, Double.POSITIVE_INFINITY);
        }

        public boolean reached(int node) {
            return Double.isFinite(distance(node));
        }

        /** Number of arcs in the stable witness, or -1 when unreachable. */
        public int edgeCount(int node) {
            if (!reached(node)) {
                return -1;
            }
            if (denseEdgeCounts != null) {
                return denseEdgeCounts[node];
            }
            return sparseEdgeCounts.getOrDefault(node, -1);
        }

        /**
         * Returns the deterministic minimum-distance witness. Forward labels
         * return {@code start -> node}; reverse labels return
         * {@code node -> start}.
         */
        public List<Integer> witnessArcIds(int node) {
            if (!reached(node)) {
                throw new IllegalArgumentException(
                        "node is unreachable in lower-bound graph: " + node);
            }
            if (node == start) {
                return List.of();
            }
            int expectedEdges = edgeCount(node);
            List<Integer> arcs = new ArrayList<>(expectedEdges);
            int current = node;
            while (current != start) {
                int arcId = witnessArc(current);
                if (arcId < 0) {
                    throw new IllegalStateException(
                            "missing lower-bound witness for node " + current);
                }
                arcs.add(arcId);
                Edge edge = graph.edges().get(arcId);
                current = outgoing ? edge.source() : edge.target();
                if (arcs.size() > expectedEdges) {
                    throw new IllegalStateException(
                            "cyclic lower-bound witness for node " + node);
                }
            }
            if (arcs.size() != expectedEdges) {
                throw new IllegalStateException(
                        "lower-bound witness edge-count mismatch for node "
                                + node);
            }
            if (outgoing) {
                Collections.reverse(arcs);
            }
            return List.copyOf(arcs);
        }

        private int witnessArc(int node) {
            if (denseWitnessArcs != null) {
                return node >= 0 && node < denseWitnessArcs.length
                        ? denseWitnessArcs[node] : -1;
            }
            return sparseWitnessArcs.getOrDefault(node, -1);
        }

        public List<Integer> reachedNodes() {
            if (denseReachedNodes != null) {
                return Arrays.stream(denseReachedNodes)
                        .boxed().toList();
            }
            return values.keySet().stream()
                    .sorted().toList();
        }

        /** Visits stable ascending active vertex IDs without boxing. */
        public void forEachReached(IntConsumer consumer) {
            java.util.Objects.requireNonNull(
                    consumer, "consumer");
            if (denseReachedNodes != null) {
                for (int node : denseReachedNodes) {
                    consumer.accept(node);
                }
            } else {
                values.keySet().stream()
                        .mapToInt(Integer::intValue)
                        .sorted()
                        .forEach(consumer);
            }
        }

        public int size() {
            return denseReachedNodes != null
                    ? denseReachedNodes.length
                    : values.size();
        }
    }

    /** Allocation-stable heap ordered by distance, hops, then node ID. */
    private static final class DenseNodeHeap {
        private int[] nodes;
        private final int[] positions;
        private final double[] distances;
        private final int[] edgeCounts;
        private int size;

        DenseNodeHeap(
                double[] distances,
                int[] edgeCounts) {
            this.distances = distances;
            this.edgeCounts = edgeCounts;
            positions = new int[distances.length];
            Arrays.fill(positions, -1);
            nodes = new int[Math.min(
                    distances.length, 1 << 20)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void addOrDecrease(int node) {
            int position = positions[node];
            if (position == -2) {
                throw new IllegalStateException(
                        "nonnegative Dijkstra improved settled "
                                + "node " + node);
            }
            if (position < 0) {
                ensureCapacity();
                position = size++;
                nodes[position] = node;
                positions[node] = position;
            }
            siftUp(position);
        }

        int removeMinimum() {
            int minimum = nodes[0];
            positions[minimum] = -2;
            int last = --size;
            if (last == 0) {
                return minimum;
            }
            int node = nodes[last];
            int index = 0;
            while (true) {
                int left = (index << 1) + 1;
                if (left >= size) {
                    break;
                }
                int right = left + 1;
                int smaller = right < size
                        && compare(
                                nodes[right],
                                nodes[left]) < 0
                                ? right : left;
                if (compare(
                        node, nodes[smaller]) <= 0) {
                    break;
                }
                move(smaller, index);
                index = smaller;
            }
            nodes[index] = node;
            positions[node] = index;
            return minimum;
        }

        private void siftUp(int position) {
            int node = nodes[position];
            int index = position;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (compare(
                        node, nodes[parent]) >= 0) {
                    break;
                }
                move(parent, index);
                index = parent;
            }
            nodes[index] = node;
            positions[node] = index;
        }

        private int compare(int left, int right) {
            int byDistance = Double.compare(
                    distances[left],
                    distances[right]);
            if (byDistance != 0) {
                return byDistance;
            }
            int byEdges = Integer.compare(
                    edgeCounts[left], edgeCounts[right]);
            return byEdges != 0
                    ? byEdges
                    : Integer.compare(left, right);
        }

        private void move(int source, int destination) {
            nodes[destination] = nodes[source];
            positions[nodes[destination]] = destination;
        }

        private void ensureCapacity() {
            if (size < nodes.length) {
                return;
            }
            int expanded = (int) Math.min(
                    positions.length,
                    (long) nodes.length
                            + Math.max(
                                    1, nodes.length >>> 1));
            if (expanded <= nodes.length) {
                throw new IllegalStateException(
                        "dense Dijkstra heap exceeds vertex count");
            }
            nodes = Arrays.copyOf(nodes, expanded);
        }
    }

    private static final class IntAccumulator {
        private int[] values = new int[1_024];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(
                        values,
                        values.length
                                + (values.length >>> 1));
            }
            values[size++] = value;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
