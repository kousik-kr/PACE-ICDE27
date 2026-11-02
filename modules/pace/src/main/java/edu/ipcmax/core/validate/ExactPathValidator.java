package edu.ipcmax.core.validate;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/**
 * Replays paths against original edge functions with no waiting at intermediate vertices.
 */
public final class ExactPathValidator {
    private final TDGraph graph;

    /**
     * Creates a validator for a graph.
     */
    public ExactPathValidator(TDGraph graph) {
        this.graph = graph;
    }

    /**
     * Validates and replays a path.
     */
    public ValidationResult validate(int source, int destination, int departureTime, double maxTravelTime, Path path) {
        if (!LooplessChecker.isLoopless(graph, path)) {
            return ValidationResult.invalid("path is not loopless or connected", departureTime, path);
        }
        if (source == destination && path.arcIds().isEmpty()) {
            return ValidationResult.valid(departureTime, departureTime, 0, path);
        }
        if (path.arcIds().isEmpty()) {
            return ValidationResult.invalid("empty path does not reach destination", departureTime, path);
        }

        double currentTime = departureTime;
        int currentNode = source;
        int score = 0;
        for (int arcId : path.arcIds()) {
            if (arcId < 0 || arcId >= graph.edges().size()) {
                return ValidationResult.invalid("unknown arc id " + arcId, departureTime, path);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                return ValidationResult.invalid("path is disconnected at arc " + arcId, departureTime, path);
            }
            try {
                score += edge.scoreFunction().valueAt(currentTime);
                currentTime = edge.travelTimeFunction().arrivalTimeAt(currentTime);
            } catch (IllegalArgumentException ex) {
                return ValidationResult.invalid("edge function domain error at arc " + arcId + ": " + ex.getMessage(),
                        departureTime, path);
            }
            currentNode = edge.target();
        }

        if (currentNode != destination) {
            return ValidationResult.invalid("path does not end at destination", departureTime, path);
        }
        if (currentTime - departureTime > maxTravelTime) {
            return ValidationResult.invalid("path exceeds travel-time budget", departureTime, path);
        }
        return ValidationResult.valid(departureTime, currentTime, score, path);
    }
}
