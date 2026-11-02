package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Query-local, bounded, single-flight cache of immutable exact values.
 *
 * <p>Keys are complete value identities (never digests alone). Eviction is
 * least-recently-used under both an entry and a byte bound; it is
 * deterministic for a single-threaded access order. A value is cached only
 * after its computation completes, so a cancelled computation leaves nothing
 * behind and wakes every waiter with the same failure.</p>
 */
final class BoundedExactCache<K, V> {
    enum Outcome { HIT, SINGLE_FLIGHT_WAIT, COMPUTED }

    record Lookup<V>(V value, Outcome outcome) { }

    private record Entry<V>(V value, long bytes) { }

    private final int capacity;
    private final long maximumBytes;
    private final ToLongFunction<V> weigher;
    private final BiConsumer<K, V> evicted;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<K, CompletableFuture<V>> inFlight = new HashMap<>();
    private long bytes;
    private long peakBytes;
    private long evictions;
    private long oversize;

    BoundedExactCache(int capacity, long maximumBytes, ToLongFunction<V> weigher, BiConsumer<K, V> evicted) {
        if (capacity < 0 || maximumBytes < 0) throw new IllegalArgumentException("negative cache bound");
        this.capacity = capacity;
        this.maximumBytes = maximumBytes;
        this.weigher = Objects.requireNonNull(weigher, "weigher");
        this.evicted = evicted == null ? (key, value) -> { } : evicted;
    }

    synchronized V getIfPresent(K key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value();
    }

    /** Values currently cached for which {@code accept} holds, in LRU order. */
    synchronized List<Map.Entry<K, V>> matching(java.util.function.Predicate<K> accept) {
        List<Map.Entry<K, V>> result = new ArrayList<>();
        for (Map.Entry<K, Entry<V>> entry : entries.entrySet()) {
            if (accept.test(entry.getKey())) result.add(Map.entry(entry.getKey(), entry.getValue().value()));
        }
        return result;
    }

    Lookup<V> get(K key, Supplier<V> compute, BooleanSupplier cancellationRequested) {
        CompletableFuture<V> future;
        boolean producer;
        synchronized (this) {
            Entry<V> entry = entries.get(key);
            if (entry != null) return new Lookup<>(entry.value(), Outcome.HIT);
            future = inFlight.get(key);
            producer = future == null;
            if (producer) {
                future = new CompletableFuture<>();
                inFlight.put(key, future);
            }
        }
        if (!producer) {
            return new Lookup<>(ExactCacheSupport.await(future, cancellationRequested), Outcome.SINGLE_FLIGHT_WAIT);
        }
        try {
            V value = compute.get();
            long weight = weigher.applyAsLong(value);
            synchronized (this) {
                inFlight.remove(key);
                put(key, value, weight);
                future.complete(value);
            }
            return new Lookup<>(value, Outcome.COMPUTED);
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                inFlight.remove(key);
                future.completeExceptionally(failure);
            }
            throw failure;
        }
    }

    private void put(K key, V value, long weight) {
        if (capacity == 0 || weight > maximumBytes) {
            oversize++;
            return;
        }
        Entry<V> prior = entries.put(key, new Entry<>(value, weight));
        if (prior != null) bytes -= prior.bytes();
        bytes += weight;
        Iterator<Map.Entry<K, Entry<V>>> oldest = entries.entrySet().iterator();
        while ((entries.size() > capacity || bytes > maximumBytes) && oldest.hasNext()) {
            Map.Entry<K, Entry<V>> victim = oldest.next();
            if (victim.getKey().equals(key)) continue;
            bytes -= victim.getValue().bytes();
            oldest.remove();
            evictions++;
            evicted.accept(victim.getKey(), victim.getValue().value());
        }
        peakBytes = Math.max(peakBytes, bytes);
    }

    synchronized int size() { return entries.size(); }
    synchronized int inFlightCount() { return inFlight.size(); }
    synchronized long bytes() { return bytes; }
    synchronized long peakBytes() { return peakBytes; }
    synchronized long evictions() { return evictions; }
    synchronized long oversize() { return oversize; }
}
