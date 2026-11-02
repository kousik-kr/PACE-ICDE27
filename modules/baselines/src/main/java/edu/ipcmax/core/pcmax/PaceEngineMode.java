package edu.ipcmax.core.pcmax;

/** Selects the production layered engine or the diagnostic legacy recursion. */
public enum PaceEngineMode {
    /** Final forward-layered bounded/exhaustive engine. */
    SCALABLE,
    /** Previous left/right recursive implementation, retained for diagnostics. */
    LEGACY
}
