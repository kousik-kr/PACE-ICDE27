package edu.ipcmax.baselines.exact;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import edu.ipcmax.core.pcmax.PaceExecutionMetrics;

/** Low-overhead query-scoped counters for exact rational work. */
public final class ExactArithmeticDiagnostics {
    private static final AtomicReference<PaceExecutionMetrics> ACTIVE =
            new AtomicReference<>();

    private ExactArithmeticDiagnostics() { }

    /** Installs the one active query's metrics across all its worker threads. */
    public static Scope install(PaceExecutionMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        PaceExecutionMetrics previous = ACTIVE.getAndSet(metrics);
        return new Scope(metrics, previous);
    }

    public static void increment(String name) {
        add(name, 1);
    }

    public static void add(String name, long amount) {
        PaceExecutionMetrics metrics = ACTIVE.get();
        if (metrics != null) {
            metrics.addCounterQuiet(name, amount);
        }
    }

    public static PaceExecutionMetrics.Timer phase(String name) {
        PaceExecutionMetrics metrics = ACTIVE.get();
        return metrics == null
                ? PaceExecutionMetrics.none().phase(name)
                : metrics.phase(name);
    }

    public static final class Scope implements AutoCloseable {
        private final PaceExecutionMetrics installed;
        private final PaceExecutionMetrics previous;
        private boolean closed;

        private Scope(
                PaceExecutionMetrics installed,
                PaceExecutionMetrics previous) {
            this.installed = installed;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                ACTIVE.compareAndSet(installed, null);
            } else {
                ACTIVE.compareAndSet(installed, previous);
            }
        }
    }
}
