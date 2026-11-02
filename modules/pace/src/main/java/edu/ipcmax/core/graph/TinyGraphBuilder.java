package edu.ipcmax.core.graph;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;

import java.util.ArrayList;
import java.util.List;

/**
 * Test helper for building tiny directed time-dependent graphs inline.
 */
public final class TinyGraphBuilder {
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    /**
     * Adds a node.
     */
    public TinyGraphBuilder node(int id) {
        nodes.add(new Node(id, id, id));
        return this;
    }

    /** Adds a node with explicit deterministic coordinate metadata. */
    public TinyGraphBuilder node(int id, long x, long y) {
        nodes.add(new Node(id, x, y));
        return this;
    }

    /**
     * Adds an edge with constant travel time and full-day zero score.
     */
    public TinyGraphBuilder edge(int source, int target, double travelTime) {
        return edge(source, target, travelTime, PiecewiseConstFn.zeroFullDay());
    }

    /**
     * Adds an edge with constant travel time and supplied score function.
     */
    public TinyGraphBuilder edge(int source, int target, double travelTime, PiecewiseConstFn scoreFunction) {
        int arcId = edges.size();
        PiecewiseLinearFn travel = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, travelTime),
                new PiecewiseLinearFn.Breakpoint(1440, travelTime)));
        edges.add(new Edge(arcId, source, target, Math.round(travelTime), travelTime, travel, scoreFunction));
        return this;
    }

    /**
     * Builds the graph.
     */
    public TDGraph build() {
        return new TDGraph(nodes, edges);
    }
}
