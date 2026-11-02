package edu.ipcmax.core.graph;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;

/**
 * Directed time-dependent edge.
 */
public record Edge(
        int arcId,
        int source,
        int target,
        long distance,
        double baseTravelTime,
        PiecewiseLinearFn travelTimeFunction,
        PiecewiseConstFn scoreFunction) {
    /**
     * Creates a validated edge.
     */
    public Edge {
        if (arcId < 0) {
            throw new IllegalArgumentException("arc id must be non-negative");
        }
        if (source <= 0 || target <= 0) {
            throw new IllegalArgumentException("edge endpoints must be positive node ids");
        }
        if (distance < 0) {
            throw new IllegalArgumentException("edge distance cannot be negative");
        }
        if (baseTravelTime < 0) {
            throw new IllegalArgumentException("base travel time cannot be negative");
        }
        if (travelTimeFunction == null) {
            throw new IllegalArgumentException("travel-time function is required");
        }
        if (scoreFunction == null) {
            throw new IllegalArgumentException("score function is required");
        }
    }
}
