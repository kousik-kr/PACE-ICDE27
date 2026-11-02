package edu.ipcmax.core.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

/**
 * Exact lower-bound Dijkstra for large DIMACS graphs with dense positive node
 * identifiers.
 *
 * <p>The oracle reuses the canonical {@link TDGraph}; it is not a second graph
 * representation or a preprocessed routing index.  Dense primitive label
 * arrays avoid the per-node hash-map/object overhead of the fixture fallback.
 * The selected witness contract is minimum distance, then minimum hop count,
 * then stable predecessor arc ID.</p>
 */
public final class DenseDijkstraLowerBoundOracle
        implements LowerBoundOracle {
    private static final int MAX_DENSE_ID_FACTOR = 2;

    private final TDGraph graph;
    private final double[] weightsByArc;
    private final int maximumNodeId;

    public DenseDijkstraLowerBoundOracle(TDGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        maximumNodeId = graph.nodeIds().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElseThrow(() -> new IllegalArgumentException(
                        "lower-bound graph must not be empty"));
        if (maximumNodeId > Math.max(
                1L,
                (long) graph.nodeCount() * MAX_DENSE_ID_FACTOR)) {
            throw new IllegalArgumentException(
                    "node identifiers are too sparse for dense lower-bound "
                            + "routing: max_id=" + maximumNodeId
                            + ", nodes=" + graph.nodeCount());
        }
        weightsByArc = new double[graph.edgeCount()];
        for (Edge edge : graph.edges()) {
            double weight =
                    edge.travelTimeFunction().minTravelTime();
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException(
                        "arc_id " + edge.arcId()
                                + " has non-positive lower-bound travel time: "
                                + weight);
            }
            weightsByArc[edge.arcId()] =
                    Domain.canonicalTime(weight);
        }
    }

    @Override
    public double edgeWeight(int arcId) {
        return weightsByArc[arcId];
    }

    @Override
    public Labels distancesFrom(int source) {
        return dijkstra(source, true, () -> false);
    }

    @Override
    public Labels distancesFrom(
            int source,
            BooleanSupplier cancelled) {
        return dijkstra(source, true, cancelled);
    }

    @Override
    public Labels distancesTo(int target) {
        return dijkstra(target, false, () -> false);
    }

    @Override
    public Labels distancesTo(
            int target,
            BooleanSupplier cancelled) {
        return dijkstra(target, false, cancelled);
    }

    private Labels dijkstra(
            int start,
            boolean forward,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        graph.node(start);
        double[] distances = new double[maximumNodeId + 1];
        int[] edgeCounts = new int[maximumNodeId + 1];
        int[] witnessArcs = new int[maximumNodeId + 1];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        Arrays.fill(edgeCounts, Integer.MAX_VALUE);
        Arrays.fill(witnessArcs, -1);
        distances[start] = 0.0;
        edgeCounts[start] = 0;

        IndexedNodeHeap queue = new IndexedNodeHeap(
                maximumNodeId + 1,
                distances,
                edgeCounts);
        queue.addOrDecrease(start);
        int settled = 0;
        while (!queue.isEmpty()) {
            if ((settled++ & 1023) == 0
                    && (cancelled.getAsBoolean()
                        || Thread.currentThread().isInterrupted())) {
                throw new CancellationException(
                        "dense lower-bound search reached its query deadline");
            }
            int labelNode = queue.removeMinimum();
            double labelDistance = distances[labelNode];
            int labelEdgeCount = edgeCounts[labelNode];
            List<Edge> adjacent = forward
                    ? graph.outgoingEdges(labelNode)
                    : graph.incomingEdges(labelNode);
            for (Edge edge : adjacent) {
                int next = forward ? edge.target() : edge.source();
                double candidate = Domain.canonicalTime(
                        labelDistance + weightsByArc[edge.arcId()]);
                int candidateEdges =
                        Math.addExact(labelEdgeCount, 1);
                boolean improves =
                        candidate < distances[next]
                                || (candidate == distances[next]
                                    && candidateEdges
                                        < edgeCounts[next]);
                boolean witnessImproves =
                        candidate == distances[next]
                                && candidateEdges == edgeCounts[next]
                                && (witnessArcs[next] < 0
                                    || edge.arcId()
                                        < witnessArcs[next]);
                if (improves) {
                    distances[next] = candidate;
                    edgeCounts[next] = candidateEdges;
                    witnessArcs[next] = edge.arcId();
                    queue.addOrDecrease(next);
                } else if (witnessImproves) {
                    witnessArcs[next] = edge.arcId();
                }
            }
        }
        return new DenseLabels(
                start,
                forward,
                distances,
                edgeCounts,
                witnessArcs,
                graph);
    }

    /**
     * Allocation-stable binary heap for continental Dijkstra runs. The
     * ordering exactly matches the former Label comparator:
     * distance, hop count, then node ID.
     */
    private static final class IndexedNodeHeap {
        private int[] nodes;
        private final int[] positions;
        private final double[] distances;
        private final int[] edgeCounts;
        private int size;

        IndexedNodeHeap(
                int vertexSlots,
                double[] distances,
                int[] edgeCounts) {
            if (vertexSlots < 1
                    || distances.length != vertexSlots
                    || edgeCounts.length != vertexSlots) {
                throw new IllegalArgumentException(
                        "invalid indexed label heap dimensions");
            }
            int capacity = Math.min(vertexSlots, 1 << 20);
            nodes = new int[capacity];
            positions = new int[vertexSlots];
            Arrays.fill(positions, -1);
            this.distances = distances;
            this.edgeCounts = edgeCounts;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void addOrDecrease(int node) {
            int position = positions[node];
            if (position == -2) {
                throw new IllegalStateException(
                        "positive-weight Dijkstra attempted to improve "
                                + "a finalized node: " + node);
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
            if (size == 0) {
                throw new IllegalStateException(
                        "cannot remove from an empty label heap");
            }
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
                        && compareNodes(
                                nodes[right],
                                nodes[left]) < 0
                            ? right : left;
                if (compareNodes(
                        node,
                        nodes[smaller]) <= 0) {
                    break;
                }
                move(smaller, index);
                index = smaller;
            }
            nodes[index] = node;
            positions[node] = index;
            return minimum;
        }

        private void ensureCapacity() {
            if (size < nodes.length) {
                return;
            }
            int expanded = (int) Math.min(
                    positions.length,
                    (long) nodes.length
                            + Math.max(1, nodes.length >>> 1));
            if (expanded <= nodes.length) {
                throw new IllegalStateException(
                        "indexed label heap exceeds vertex count");
            }
            nodes = Arrays.copyOf(nodes, expanded);
        }

        private void siftUp(int position) {
            int node = nodes[position];
            int index = position;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (compareNodes(
                        node,
                        nodes[parent]) >= 0) {
                    break;
                }
                move(parent, index);
                index = parent;
            }
            nodes[index] = node;
            positions[node] = index;
        }

        private void move(int source, int destination) {
            nodes[destination] = nodes[source];
            positions[nodes[destination]] = destination;
        }

        private int compareNodes(int leftNode, int rightNode) {
            return compare(
                    distances[leftNode],
                    edgeCounts[leftNode],
                    leftNode,
                    distances[rightNode],
                    edgeCounts[rightNode],
                    rightNode);
        }

        private static int compare(
                double leftDistance,
                int leftEdgeCount,
                int leftNode,
                double rightDistance,
                int rightEdgeCount,
                int rightNode) {
            int byDistance =
                    Double.compare(leftDistance, rightDistance);
            if (byDistance != 0) {
                return byDistance;
            }
            int byEdges = Integer.compare(
                    leftEdgeCount, rightEdgeCount);
            return byEdges != 0
                    ? byEdges : Integer.compare(leftNode, rightNode);
        }
    }

    private record DenseLabels(
            int start,
            boolean forward,
            double[] distances,
            int[] edgeCounts,
            int[] witnessArcs,
            TDGraph graph) implements Labels {
        @Override
        public double distance(int node) {
            return validNode(node)
                    ? distances[node] : Double.POSITIVE_INFINITY;
        }

        @Override
        public boolean reached(int node) {
            return validNode(node)
                    && Double.isFinite(distances[node]);
        }

        @Override
        public int edgeCount(int node) {
            return reached(node) ? edgeCounts[node] : -1;
        }

        @Override
        public Path witnessPath(int node) {
            if (!reached(node)) {
                throw new IllegalArgumentException(
                        "node is unreachable in lower-bound graph: "
                                + node);
            }
            if (node == start) {
                return Path.empty();
            }
            List<Integer> arcs =
                    new ArrayList<>(edgeCounts[node]);
            int current = node;
            while (current != start) {
                int arcId = witnessArcs[current];
                if (arcId < 0) {
                    throw new IllegalStateException(
                            "missing lower-bound witness for node "
                                    + current);
                }
                arcs.add(arcId);
                Edge edge = graph.edges().get(arcId);
                current = forward
                        ? edge.source() : edge.target();
            }
            if (forward) {
                Collections.reverse(arcs);
            }
            return new Path(arcs);
        }

        private boolean validNode(int node) {
            return node >= 0 && node < distances.length;
        }
    }
}
