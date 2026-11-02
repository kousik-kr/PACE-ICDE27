package edu.ipcmax.core.validate;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/**
 * Exact structural checks for loaded time-dependent graphs.
 */
public final class GraphValidator {
    private GraphValidator() {
    }

    /**
     * Validates graph invariants required by I-PC-Max.
     */
    public static void validate(TDGraph graph) {
        validate(graph, true);
    }

    /**
     * Validates graph invariants, optionally enforcing FIFO arrival functions.
     */
    public static void validate(TDGraph graph, boolean requireFifo) {
        for (int i = 0; i < graph.edges().size(); i++) {
            Edge edge = graph.edges().get(i);
            if (edge.arcId() != i) {
                throw new IllegalArgumentException("arc ids must be consecutive from 0");
            }
            graph.node(edge.source());
            graph.node(edge.target());
            if (requireFifo) {
                edge.travelTimeFunction().requireFifo("arc_id " + edge.arcId());
            }
        }
    }

    /**
     * Counts edges whose induced arrival function is not FIFO.
     */
    public static long countNonFifoEdges(TDGraph graph) {
        return graph.edges().stream()
                .filter(edge -> !edge.travelTimeFunction().isFifo())
                .count();
    }
}
