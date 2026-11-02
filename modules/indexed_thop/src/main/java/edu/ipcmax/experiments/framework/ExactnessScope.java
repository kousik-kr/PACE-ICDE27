package edu.ipcmax.experiments.framework;

/** Machine-readable scope of the correctness claim attached to an algorithm result. */
public enum ExactnessScope {
    /** The result is certified against the complete feasible path space. */
    GLOBAL_CERTIFIED,

    /** The result is exact only over the algorithm's retained candidate frontier. */
    RETAINED_FRONTIER,

    /** No exactness claim is made for this result. */
    NOT_CERTIFIED
}
