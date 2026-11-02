package edu.ipcmax.core.pcmax.egpi;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One monotonic query deadline shared by coordinator and scoring workers. */
public final class EgpiSearchBudget implements BooleanSupplier {
    private final long started = System.nanoTime();
    /** Absolute {@link System#nanoTime()} of the budget's start; the origin of the search clock. */
    public long originNanoTime() { return started; }
    private final long durationNanos;
    private final long reserveNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public EgpiSearchBudget(long deadlineMillis) {
        this(deadlineMillis, 0);
    }

    /**
     * @param reserveMillis time kept at the end of the query for assembling and returning the answer: the
     *                      search itself stops that long before the deadline
     */
    public EgpiSearchBudget(long deadlineMillis, long reserveMillis) {
        if (deadlineMillis < 1 || deadlineMillis > 86_400_000) {
            throw new IllegalArgumentException("invalid EGPI deadline");
        }
        if (reserveMillis < 0 || reserveMillis >= deadlineMillis) {
            throw new IllegalArgumentException("invalid EGPI result reserve");
        }
        durationNanos = (deadlineMillis - reserveMillis) * 1_000_000L;
        reserveNanos = reserveMillis * 1_000_000L;
    }

    /** Nanoseconds before the query deadline at which the search stops. */
    public long reserveNanos() { return reserveNanos; }
    public long elapsedNanos() { return System.nanoTime() - started; }
    public long remainingNanos() { return Math.max(0, durationNanos - elapsedNanos()); }
    public boolean deadlineReached() { return elapsedNanos() >= durationNanos; }
    @Override public boolean getAsBoolean() {
        return cancelled.get() || deadlineReached() || Thread.currentThread().isInterrupted();
    }
    public void check() {
        if (getAsBoolean()) throw new CancellationException("EGPI query cancelled or deadline reached");
    }
    public void cancel() { cancelled.set(true); }
}
