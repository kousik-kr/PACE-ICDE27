package edu.ipcmax.core.pcmax;

/** Completion state of candidate generation. */
public enum PaceCompletion {
    COMPLETE,
    UNRESOLVED_NO_GENERATED_CANDIDATE,
    NO_FEASIBLE_PATH,
    RESOURCE_TRUNCATED,
    ABORTED
}
