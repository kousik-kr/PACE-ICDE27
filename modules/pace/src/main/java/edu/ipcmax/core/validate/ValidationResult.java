package edu.ipcmax.core.validate;

/**
 * Exact replay result for a candidate path.
 */
public record ValidationResult(
        boolean valid,
        String reason,
        int departureTime,
        double arrivalTime,
        double travelTime,
        int score,
        Path path) {
    /**
     * Creates a valid result.
     */
    public static ValidationResult valid(int departureTime, double arrivalTime, int score, Path path) {
        return new ValidationResult(true, "", departureTime, arrivalTime, arrivalTime - departureTime, score, path);
    }

    /**
     * Creates an invalid result.
     */
    public static ValidationResult invalid(String reason, int departureTime, Path path) {
        return new ValidationResult(false, reason, departureTime, Double.NaN, Double.NaN, 0, path);
    }
}
