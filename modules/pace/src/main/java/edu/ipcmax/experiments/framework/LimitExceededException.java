package edu.ipcmax.experiments.framework;

import java.io.Serial;

/** Signals that an exact algorithm stopped before producing a complete answer. */
public final class LimitExceededException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public LimitExceededException(String message) {
        super(message);
    }
}
