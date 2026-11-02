package edu.ipcmax.core.profile;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.ObjLongConsumer;

/**
 * Query-local observer for low-level temporal-profile work.
 *
 * <p>The observer is inherited by PACE worker threads and is removed when the
 * query finishes. Profile correctness never depends on an observer being
 * installed.</p>
 */
public final class TemporalProfileWork {
    private static final InheritableThreadLocal<ObjLongConsumer<String>>
            CURRENT = new InheritableThreadLocal<>();
    private static final ThreadLocal<int[]> CANCELLATION_POLLS =
            ThreadLocal.withInitial(() -> new int[1]);

    private TemporalProfileWork() {
    }

    /** Installs a query-local counter sink until the returned scope is closed. */
    public static Scope install(ObjLongConsumer<String> sink) {
        Objects.requireNonNull(sink, "sink");
        ObjLongConsumer<String> previous = CURRENT.get();
        CURRENT.set(sink);
        return new Scope(previous);
    }

    public static void increment(String name) {
        add(name, 1);
    }

    public static void add(String name, long amount) {
        int[] polls = CANCELLATION_POLLS.get();
        if ((++polls[0] & 1023) == 0
                && Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "temporal profile work interrupted");
        }
        ObjLongConsumer<String> sink = CURRENT.get();
        if (sink != null && amount != 0) {
            sink.accept(name, amount);
        }
    }

    /** Restores the observer that was active before this scope. */
    public static final class Scope implements AutoCloseable {
        private final ObjLongConsumer<String> previous;
        private boolean active = true;

        private Scope(ObjLongConsumer<String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!active) {
                return;
            }
            active = false;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
