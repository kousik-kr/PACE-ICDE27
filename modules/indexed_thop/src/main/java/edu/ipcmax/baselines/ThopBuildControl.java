package edu.ipcmax.baselines;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional, cooperative control of a long index construction for diagnostic runs (enabled by {@code -Dthop.control=true}).
 *
 * <p>It changes no value the construction computes. It provides (a) a stop request that the search and build loops poll and answer by throwing
 * {@link BuildStopped}, (b) a persistent boundary-phase shard directory so that finished source searches survive a stop or a crash and are reused by the
 * next attempt, (c) a seeded processing order for the independent source searches, (d) a resizable cap on concurrently running source searches, and
 * (e) live progress of every running search and a stream of construction events for an external recorder.</p>
 */
public final class ThopBuildControl {
    public static final boolean ENABLED = Boolean.getBoolean("thop.control");

    private static volatile String stopReason;
    private static volatile Path persistentPhase1Directory;
    private static volatile boolean shuffleSources;
    private static volatile long shuffleSeed;
    private static volatile Listener listener;
    private static final Object SLOT_LOCK = new Object();
    private static int allowedSearches = Integer.MAX_VALUE;
    private static int runningSearches;
    private static final Set<SearchProbe> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<SearchProbe> CURRENT = new ThreadLocal<>();

    private ThopBuildControl() { }

    /** Receives construction events (stage, name, fields). Called from worker threads; implementations must be thread-safe and fast. */
    public interface Listener {
        void event(String stage, String name, Map<String, Object> fields);
    }

    /** Thrown by {@link #checkStop} after {@link #requestStop}. */
    public static final class BuildStopped extends RuntimeException {
        private final String reason;
        private final String where;

        BuildStopped(String reason, String where) {
            super("THop build stopped (" + reason + ") at " + where);
            this.reason = reason;
            this.where = where;
        }

        public String reason() { return reason; }

        public String where() { return where; }
    }

    /** Live counters of one running profile search; written by the search thread, read by a sampler. */
    public static final class SearchProbe {
        public final int source;
        public final long startedNanos = System.nanoTime();
        public final String threadName = Thread.currentThread().getName();
        public volatile long expanded;
        public volatile long generated;
        public volatile int queued;
        public volatile int labelled;
        public volatile long piecesHeld;
        public volatile long maxProfilePieces;
        public volatile int maxRationalBits;
        public volatile String phase = "setup";
        public volatile long searchStartedNanos;

        SearchProbe(int source) {
            this.source = source;
        }
    }

    public static void install(Listener newListener) {
        listener = newListener;
    }

    public static void requestStop(String reason) {
        if (stopReason == null) stopReason = reason;
    }

    public static boolean stopRequested() {
        return stopReason != null;
    }

    public static String stopReason() {
        return stopReason;
    }

    public static void clearStop() {
        stopReason = null;
    }

    public static void checkStop(String where) {
        String reason = stopReason;
        if (reason != null) throw new BuildStopped(reason, where);
    }

    public static void emit(String stage, String name, Map<String, Object> fields) {
        Listener current = listener;
        if (current != null) current.event(stage, name, fields);
    }

    public static boolean listening() {
        return listener != null;
    }

    // ---- persistent boundary-phase shards and processing order

    public static void persistPhase1In(Path directory) {
        persistentPhase1Directory = directory;
    }

    public static Path persistentPhase1Directory() {
        return persistentPhase1Directory;
    }

    /** Processes the independent boundary sources in a seeded pseudo-random order (the result does not depend on the order). */
    public static void shuffleSourcesWithSeed(long seed) {
        shuffleSeed = seed;
        shuffleSources = true;
    }

    static List<Integer> orderSources(List<Integer> sources) {
        if (!shuffleSources) return sources;
        List<Integer> ordered = new ArrayList<>(sources);
        Collections.shuffle(ordered, new Random(shuffleSeed));
        return ordered;
    }

    // ---- concurrency cap on source searches

    /** Sets the number of source searches allowed to run at once (default unlimited). Only affects searches that have not yet acquired a slot. */
    public static void limitConcurrentSearches(int permits) {
        synchronized (SLOT_LOCK) {
            allowedSearches = Math.max(1, permits);
            SLOT_LOCK.notifyAll();
        }
    }

    static void acquireSearchSlot() throws InterruptedException {
        synchronized (SLOT_LOCK) {
            while (runningSearches >= allowedSearches) {
                checkStop("phase1:waiting_for_search_slot");
                SLOT_LOCK.wait(500);
            }
            runningSearches++;
        }
    }

    static void releaseSearchSlot() {
        synchronized (SLOT_LOCK) {
            runningSearches--;
            SLOT_LOCK.notifyAll();
        }
    }

    // ---- live search progress

    static SearchProbe beginSearch(int source) {
        if (!ENABLED) return null;
        SearchProbe probe = new SearchProbe(source);
        CURRENT.set(probe);
        ACTIVE.add(probe);
        return probe;
    }

    static SearchProbe currentProbe() {
        return ENABLED ? CURRENT.get() : null;
    }

    static void endSearch(SearchProbe probe) {
        if (probe == null) return;
        ACTIVE.remove(probe);
        CURRENT.remove();
    }

    public static List<SearchProbe> activeSearches() {
        return new ArrayList<>(ACTIVE);
    }
}
