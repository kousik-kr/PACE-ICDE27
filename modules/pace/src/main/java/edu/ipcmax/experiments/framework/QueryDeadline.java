package edu.ipcmax.experiments.framework;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic per-query deadline with a reserved finalization interval. */
public final class QueryDeadline {
    private final LongSupplier clock;
    private final long startedNanos;
    private final long limitNanos;
    private final long finalizationReserveNanos;

    /** Starts a deadline using {@link System#nanoTime()}. */
    public static QueryDeadline start(
            long limitNanos,
            long finalizationReserveNanos) {
        return start(System::nanoTime, limitNanos, finalizationReserveNanos);
    }

    /** Starts an injectable deadline for deterministic tests. */
    public static QueryDeadline start(
            LongSupplier clock,
            long limitNanos,
            long finalizationReserveNanos) {
        return new QueryDeadline(clock, limitNanos, finalizationReserveNanos);
    }

    private QueryDeadline(
            LongSupplier clock,
            long limitNanos,
            long finalizationReserveNanos) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (limitNanos <= 0) {
            throw new IllegalArgumentException("query time limit must be positive");
        }
        if (finalizationReserveNanos < 0
                || finalizationReserveNanos >= limitNanos) {
            throw new IllegalArgumentException(
                    "finalization reserve must be nonnegative and less than the query limit");
        }
        this.limitNanos = limitNanos;
        this.finalizationReserveNanos = finalizationReserveNanos;
        this.startedNanos = clock.getAsLong();
    }

    /** Elapsed monotonic time, safe across {@code nanoTime} wraparound. */
    public long elapsedNanos() {
        return Math.max(0L, clock.getAsLong() - startedNanos);
    }

    /** True at the hard algorithmic query deadline. */
    public boolean expired() {
        return Thread.currentThread().isInterrupted()
                || elapsedNanos() >= limitNanos;
    }

    /** True when exploration must yield to deterministic finalization. */
    public boolean finalizationDue() {
        return Thread.currentThread().isInterrupted()
                || elapsedNanos() >= limitNanos - finalizationReserveNanos;
    }

    public long limitNanos() {
        return limitNanos;
    }

    public long finalizationReserveNanos() {
        return finalizationReserveNanos;
    }
}
