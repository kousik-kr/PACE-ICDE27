package edu.ipcmax.core.cache;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import edu.ipcmax.core.profile.CandidateSet;

/**
 * Thread-safe candidate-set memoization without publishing partially constructed values.
 */
public final class CandidateCache {
    private final ConcurrentHashMap<MemoKey, CompletableFuture<CandidateSet>> values = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    /** Retrieves a completed cached candidate set. */
    public Optional<CandidateSet> get(MemoKey key) {
        CompletableFuture<CandidateSet> value = values.get(key);
        if (value == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(copy(value.join()));
    }

    /** Stores a defensive copy unless the key was already completed. */
    public void put(MemoKey key, CandidateSet value) {
        values.putIfAbsent(key, CompletableFuture.completedFuture(copy(value)));
    }

    /**
     * Atomically computes a key once. Concurrent callers wait for the same complete result.
     */
    public CandidateSet getOrCompute(MemoKey key, Supplier<CandidateSet> supplier) {
        CompletableFuture<CandidateSet> promise = new CompletableFuture<>();
        CompletableFuture<CandidateSet> existing = values.putIfAbsent(key, promise);
        if (existing != null) {
            hits.incrementAndGet();
            return copy(existing.join());
        }
        misses.incrementAndGet();
        try {
            CandidateSet computed = copy(supplier.get());
            promise.complete(computed);
            return copy(computed);
        } catch (RuntimeException | Error failure) {
            promise.completeExceptionally(failure);
            values.remove(key, promise);
            throw failure;
        }
    }

    /** Cache hits. */
    public long hits() {
        return hits.get();
    }

    /** Cache misses. */
    public long misses() {
        return misses.get();
    }

    /** Removes all values and resets counters. */
    public void clear() {
        values.clear();
        hits.set(0);
        misses.set(0);
    }

    private static CandidateSet copy(CandidateSet source) {
        CandidateSet copy = new CandidateSet();
        copy.addAll(source);
        return copy;
    }
}
