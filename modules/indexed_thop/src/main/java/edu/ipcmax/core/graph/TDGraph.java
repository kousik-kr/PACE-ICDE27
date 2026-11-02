package edu.ipcmax.core.graph;

import edu.ipcmax.core.function.TimeRange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Directed time-dependent graph with incoming and outgoing adjacency indexes.
 */
public final class TDGraph {
    private final Map<Integer, Node> nodes;
    private final List<Integer> nodeIds;
    private final List<Edge> edges;
    private final Map<Integer, List<Edge>> outgoing;
    private final Map<Integer, List<Edge>> incoming;

    /**
     * Builds immutable adjacency indexes from nodes and edges.
     */
    public TDGraph(Collection<Node> nodes, Collection<Edge> edges) {
        Map<Integer, Node> nodeMap = new HashMap<>();
        for (Node node : nodes) {
            if (nodeMap.put(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate node id: " + node.id());
            }
        }

        List<Edge> edgeList = new ArrayList<>(edges);
        edgeList.sort(Comparator.comparingInt(Edge::arcId));
        for (int i = 0; i < edgeList.size(); i++) {
            Edge edge = edgeList.get(i);
            if (edge.arcId() != i) {
                throw new IllegalArgumentException("arc ids must be consecutive from 0");
            }
            if (!nodeMap.containsKey(edge.source())) {
                throw new IllegalArgumentException("edge source node not found: " + edge.source());
            }
            if (!nodeMap.containsKey(edge.target())) {
                throw new IllegalArgumentException("edge target node not found: " + edge.target());
            }
        }

        Map<Integer, List<Edge>> out = new HashMap<>();
        Map<Integer, List<Edge>> in = new HashMap<>();
        for (Edge edge : edgeList) {
            out.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            in.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }

        this.nodes = Map.copyOf(nodeMap);
        this.nodeIds = nodeMap.keySet().stream().sorted().toList();
        this.edges = List.copyOf(edgeList);
        this.outgoing = freezeAdjacency(out);
        this.incoming = freezeAdjacency(in);
    }

    private static Map<Integer, List<Edge>> freezeAdjacency(Map<Integer, List<Edge>> mutable) {
        Map<Integer, List<Edge>> frozen = new HashMap<>();
        for (Map.Entry<Integer, List<Edge>> entry : mutable.entrySet()) {
            entry.getValue().sort(Comparator.comparingInt(Edge::arcId));
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
    }

    /**
     * Number of nodes.
     */
    public int nodeCount() {
        return nodes.size();
    }

    /**
     * Number of directed arcs.
     */
    public int edgeCount() {
        return edges.size();
    }

    /**
     * All node IDs in stable ascending order.
     */
    public List<Integer> nodeIds() {
        return nodeIds;
    }

    /**
     * Returns all edges sorted by arc id.
     */
    public List<Edge> edges() {
        return edges;
    }

    /**
     * Returns a node by id.
     */
    public Node node(int nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("unknown node id: " + nodeId);
        }
        return node;
    }

    /**
     * Outgoing directed edges sorted by arc id.
     */
    public List<Edge> outgoingEdges(int nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    /**
     * Incoming directed edges sorted by arc id.
     */
    public List<Edge> incomingEdges(int nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    /**
     * Edges whose score can be positive in the given departure-time range.
     */
    public List<Edge> edgesWithPositiveScore(TimeRange range) {
        List<Edge> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge.scoreFunction().hasPositiveValueIn(range)) {
                result.add(edge);
            }
        }
        return result;
    }
}
