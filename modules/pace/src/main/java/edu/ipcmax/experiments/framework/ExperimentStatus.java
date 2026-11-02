package edu.ipcmax.experiments.framework;

/** Stable per-query experiment statuses. */
public enum ExperimentStatus {
    COMPLETED,
    CERTIFIED_COMPLETE,
    UNRESOLVED_NO_GENERATED_CANDIDATE,
    NO_FEASIBLE_PATH,
    TIME_CAPPED_NOT_CERTIFIED,
    PATH_CAPPED_NOT_CERTIFIED,
    TIMEOUT,
    OUT_OF_MEMORY,
    LIMIT_EXCEEDED,
    FUNCTION_HORIZON_EXCEEDED,
    INVALID_QUERY,
    INVALID_CONFIGURATION,
    ERROR;

    /**
     * Whether the algorithm ended by exhausting its defined work for this
     * query. A capped anytime result is terminal, but the algorithm did not
     * complete its search.
     */
    public boolean completedExecution() {
        return this == COMPLETED
                || this == CERTIFIED_COMPLETE
                || this == UNRESOLVED_NO_GENERATED_CANDIDATE
                || this == NO_FEASIBLE_PATH;
    }

    /** A result row with any status represents a terminal query outcome. */
    public boolean terminal() {
        return true;
    }
}
