package edu.ipcmax.core.pcmax;

/**
 * Machine-readable status for PACE query execution.
 */
public enum PaceStatus {
    /** Query execution completed normally. */
    SUCCESS,

    /** A configured exactness or frontier safety guard was exceeded. */
    LIMIT_EXCEEDED,

    /** The requested departure-plus-budget horizon is outside graph function coverage. */
    FUNCTION_HORIZON_EXCEEDED
}
