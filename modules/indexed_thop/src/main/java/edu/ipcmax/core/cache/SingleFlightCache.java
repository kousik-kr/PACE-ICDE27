package edu.ipcmax.core.cache;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Generic query-local single-flight cache with explicit wait accounting. */
public final class SingleFlightCache<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> values =
            new ConcurrentHashMap<>();
    private final AtomicLong lookups = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong peakSize = new AtomicLong();
    private final ConcurrentLinkedQueue<Entry<K, V>>
            insertionOrder = new ConcurrentLinkedQueue<>();
    private final int maximumEntries;

    /** Creates an unbounded compatibility cache. */
    public SingleFlightCache() {
        this(Integer.MAX_VALUE);
    }

    /** Creates a cache with a hard completed-entry bound. */
    public SingleFlightCache(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException(
                    "maximum cache entries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public V getOrCompute(K key, Supplier<V> supplier) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        lookups.incrementAndGet();
        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> existing = values.putIfAbsent(key, promise);
        if (existing != null) {
            hits.incrementAndGet();
            if (!existing.isDone()) {
                waits.incrementAndGet();
            }
            return existing.join();
        }
        misses.incrementAndGet();
        insertionOrder.add(new Entry<>(key, promise));
        peakSize.accumulateAndGet(values.size(), Math::max);
        try {
            V value = Objects.requireNonNull(
                    supplier.get(), "single-flight value");
            promise.complete(value);
            trim();
            return value;
        } catch (RuntimeException | Error failure) {
            promise.completeExceptionally(failure);
            values.remove(key, promise);
            throw failure;
        }
    }

    public long lookups() {
        return lookups.get();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long waits() {
        return waits.get();
    }

    public int size() {
        return values.size();
    }

    public long evictions() {
        return evictions.get();
    }

    public long peakSize() {
        return peakSize.get();
    }

    public void clear() {
        values.clear();
        insertionOrder.clear();
        lookups.set(0);
        hits.set(0);
        misses.set(0);
        waits.set(0);
        evictions.set(0);
        peakSize.set(0);
    }

    private void trim() {
        while (values.size() > maximumEntries) {
            Entry<K, V> eldest = insertionOrder.poll();
            if (eldest == null) {
                return;
            }
            if (!eldest.promise().isDone()) {
                insertionOrder.add(eldest);
                return;
            }
            if (values.remove(
                    eldest.key(), eldest.promise())) {
                evictions.incrementAndGet();
            }
        }
    }

    private record Entry<K, V>(
            K key,
            CompletableFuture<V> promise) {
    }
}
