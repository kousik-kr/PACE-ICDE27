package edu.ipcmax.core.pcmax;

import java.util.concurrent.CancellationException;

/** Cooperative, low-frequency cancellation check for long query loops. */
final class PaceCancellation {
    private static final ThreadLocal<int[]> POLLS =
            ThreadLocal.withInitial(() -> new int[1]);

    private PaceCancellation() {
    }

    static void checkpoint() {
        int[] polls = POLLS.get();
        if ((++polls[0] & 1023) == 0
                && Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "PACE query work interrupted");
        }
    }
}
