package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic helper for parallel independent tasks.
 *
 * <p>The executor has a fixed worker count and a bounded queue. Saturation
 * executes work in the submitting thread, which preserves progress without
 * creating an unbounded backlog. Results are always reduced in submission
 * order. A failed or interrupted task cancels every sibling.</p>
 */
public final class IPCMaxParallelExecutor implements AutoCloseable {
    private static final AtomicLong POOL_IDS = new AtomicLong();
    private final ThreadPoolExecutor pool;

    /**
     * Creates an executor with the requested thread count.
     */
    public IPCMaxParallelExecutor(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("thread count must be positive");
        }
        long poolId = POOL_IDS.incrementAndGet();
        int queueCapacity = Math.max(threadCount * 4, 1);
        this.pool = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "pace-worker-" + poolId + "-"
                                    + WORKER_IDS.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static final AtomicLong WORKER_IDS = new AtomicLong();

    /**
     * Executes tasks and returns results in the same order as input tasks.
     */
    public <T> List<T> invokeAllDeterministic(List<Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(
                        Objects.requireNonNull(task, "task")));
            }
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (InterruptedException interrupted) {
            cancelAll(futures);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "parallel task execution interrupted", interrupted);
        } catch (ExecutionException | CancellationException failure) {
            cancelAll(futures);
            throw new IllegalStateException(
                    "parallel task execution failed",
                    failure instanceof ExecutionException
                            && failure.getCause() != null
                            ? failure.getCause()
                            : failure);
        }
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "PACE worker pool did not terminate");
                }
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while closing PACE worker pool",
                    interrupted);
        }
    }

    /**
     * Submits tasks in order and returns their futures in the same order, so a
     * caller can consume results deterministically while later tasks still run.
     */
    public <T> List<Future<T>> submitAll(List<Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) futures.add(pool.submit(Objects.requireNonNull(task, "task")));
        return futures;
    }

    /**
     * Interrupts every worker and waits at most {@code grace} for the pool to
     * terminate. Unlike {@link #close()}, this never blocks longer than the
     * grace; the result reports whether any worker outlived it.
     */
    public boolean closeWithin(java.time.Duration grace) {
        pool.shutdownNow();
        try {
            return pool.awaitTermination(grace.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return pool.isTerminated();
        }
    }

    boolean isTerminated() {
        return pool.isTerminated();
    }

    private static void cancelAll(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }
}
