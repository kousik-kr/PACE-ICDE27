package edu.ipcmax.core.validate;

import java.util.List;

/**
 * Materialized path represented by ordered arc ids.
 */
public record Path(List<Integer> arcIds) {
    /**
     * Creates an immutable path.
     */
    public Path {
        arcIds = List.copyOf(arcIds);
        for (int arcId : arcIds) {
            if (arcId < 0) {
                throw new IllegalArgumentException("arc id cannot be negative");
            }
        }
    }

    /**
     * Empty path.
     */
    public static Path empty() {
        return new Path(List.of());
    }
}
