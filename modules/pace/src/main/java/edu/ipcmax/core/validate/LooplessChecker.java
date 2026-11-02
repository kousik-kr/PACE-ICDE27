package edu.ipcmax.core.validate;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact looplessness checker for materialized paths.
 */
public final class LooplessChecker {
    private LooplessChecker() {
    }

    /**
     * Returns true if the path has no repeated vertices and has connected arcs.
     */
    public static boolean isLoopless(TDGraph graph, Path path) {
        if (path.arcIds().isEmpty()) {
            return true;
        }
        Set<Integer> seen = new HashSet<>();
        Integer current = null;
        for (int arcId : path.arcIds()) {
            if (arcId < 0 || arcId >= graph.edges().size()) {
                return false;
            }
            Edge edge = graph.edges().get(arcId);
            if (current == null) {
                current = edge.source();
                if (!seen.add(current)) {
                    return false;
                }
            } else if (edge.source() != current) {
                return false;
            }
            current = edge.target();
            if (!seen.add(current)) {
                return false;
            }
        }
        return true;
    }
}
