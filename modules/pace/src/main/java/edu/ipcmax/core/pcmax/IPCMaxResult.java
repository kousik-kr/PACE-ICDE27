package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.validate.Path;

/**
 * Validated query result under deterministic tie-breaking.
 */
public record IPCMaxResult(
        boolean found,
        int departureTime,
        double arrivalTime,
        double travelTime,
        int score,
        Path path,
        String reason) {
    /**
     * No feasible result.
     */
    public static IPCMaxResult notFound(String reason) {
        return new IPCMaxResult(false, -1, Double.NaN, Double.NaN, 0, Path.empty(), reason);
    }
}
