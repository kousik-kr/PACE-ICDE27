package edu.ipcmax.core.pcmax;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;

/**
 * Query-local cumulative phase timings and work counters.
 *
 * <p>The core publishes immutable snapshots without knowing how they are
 * persisted. Phase and counter updates are published at most once per second;
 * terminal and explicit checkpoints publish immediately so an isolated worker
 * leaves useful evidence even when its parent must terminate it.</p>
 */
public final class PaceExecutionMetrics implements AutoCloseable {
    public static final String HORIZON_VALIDATION = "horizon_validation";
    public static final String CORRIDOR_CONSTRUCTION = "corridor_construction";
    public static final String FORWARD_BACKWARD_LABELING =
            "forward_backward_labeling";
    public static final String TEMPORAL_LABEL_BUILD =
            "temporal_label_build";
    public static final String TOP_L_SELECTION =
            "top_l_anchor_selection";
    public static final String PIVOT_EXPLORATION =
            "pivot_order_exploration";
    public static final String CANDIDATE_ASSEMBLY =
            "candidate_assembly";
    public static final String PROFILE_MERGE =
            "profile_merge";
    public static final String FEASIBLE_ENTRY_BANDS = "feasible_entry_band_computation";
    public static final String SCORE_SUPPORT_LOOKUP = "score_support_lookup";
    public static final String PIVOT_RANKING = "pivot_ranking_diversification";
    public static final String CONNECTOR_GENERATION = "connector_generation";
    public static final String FINAL_REDUCTION = "final_connector_reduction";
    public static final String PATH_REPLAY = "canonical_path_replay_stitching";
    public static final String BREAKPOINT_PROCESSING = "breakpoint_processing";
    public static final String EQUALITY_ROOTS = "equality_root_computation";
    public static final String DOMINANCE = "safe_dominance";
    public static final String FRONTIER_RETENTION = "bounded_retention";
    public static final String FRAGMENT_MERGE = "fragment_restriction_merge";
    public static final String STATISTICS = "statistics";
    public static final String ENVELOPE_EXTRACTION = "envelope_extraction";

    private static final long PERIODIC_PUBLISH_NANOS = 1_000_000_000L;
    private static final PaceExecutionMetrics NONE =
            new PaceExecutionMetrics(null, false);

    private final long startedNanos;
    private final Consumer<Snapshot> listener;
    private final boolean enabled;
    private final ConcurrentHashMap<String, PhaseClock> timings =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> counters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong>
            observedCounters = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentPhase =
            new AtomicReference<>("");
    private final AtomicReference<String> lastCompletedPhase =
            new AtomicReference<>("");
    private final AtomicLong phaseSequence = new AtomicLong();
    private final ConcurrentSkipListMap<Long, PhaseActivity> activePhases =
            new ConcurrentSkipListMap<>();
    private final AtomicLong lastPublishedNanos = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService heartbeat;

    private PaceExecutionMetrics(
            Consumer<Snapshot> listener,
            boolean enabled) {
        this.listener = listener;
        this.enabled = enabled;
        this.startedNanos = System.nanoTime();
        this.lastPublishedNanos.set(startedNanos);
        if (enabled) {
            heartbeat = Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "pace-progress-heartbeat");
                        thread.setDaemon(true);
                        return thread;
                    });
            heartbeat.scheduleAtFixedRate(
                    () -> publish(true),
                    1,
                    1,
                    TimeUnit.SECONDS);
        } else {
            heartbeat = null;
        }
    }

    /** Returns a zero-overhead disabled metrics object. */
    public static PaceExecutionMetrics none() {
        return NONE;
    }

    /** Creates live cumulative metrics backed by a snapshot listener. */
    public static PaceExecutionMetrics live(
            Consumer<Snapshot> listener) {
        return new PaceExecutionMetrics(
                Objects.requireNonNull(listener, "listener"), true);
    }

    /** True when this instance publishes live diagnostics. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Starts one timed phase and exposes the active marker at the next bounded
     * progress publication.
     *
     * <p>Repeated and concurrent entries are accumulated as wall-clock union
     * time, so 24 workers cannot inflate one phase above query elapsed time.</p>
     */
    public Timer phase(String name) {
        Objects.requireNonNull(name, "name");
        if (!enabled) {
            return Timer.NONE;
        }
        long now = System.nanoTime();
        long sequence = phaseSequence.incrementAndGet();
        activePhases.put(sequence, new PhaseActivity(name, now));
        currentPhase.set(name);
        timings.computeIfAbsent(
                name, ignored -> new PhaseClock())
                .enter(now);
        publish(true);
        return new Timer(this, name, sequence);
    }

    /** Adds deterministic work to one typed counter. */
    public void addCounter(String name, long amount) {
        Objects.requireNonNull(name, "name");
        if (!enabled || amount == 0) {
            return;
        }
        addCounterQuiet(name, amount);
        publish(false);
    }

    /**
     * Adds a high-frequency diagnostic counter without forcing a publish
     * check. The one-second heartbeat still persists the cumulative value.
     */
    public void addCounterQuiet(String name, long amount) {
        Objects.requireNonNull(name, "name");
        if (!enabled || amount == 0) {
            return;
        }
        counters.computeIfAbsent(
                name, ignored -> new LongAdder()).add(amount);
    }

    /** Increments one typed counter. */
    public void increment(String name) {
        addCounter(name, 1);
    }

    /** Records a monotone cumulative counter observed in another component. */
    public void observeCounter(String name, long value) {
        Objects.requireNonNull(name, "name");
        if (!enabled || value < 0) {
            return;
        }
        observedCounters.computeIfAbsent(
                name, ignored -> new AtomicLong())
                .accumulateAndGet(value, Math::max);
        publish(false);
    }

    /** Publishes the current state immediately. */
    public void checkpoint(String phase) {
        Objects.requireNonNull(phase, "phase");
        if (!enabled) {
            return;
        }
        currentPhase.set(phase);
        publish(true);
    }

    /** Returns the current immutable cumulative state. */
    public Snapshot snapshot() {
        if (!enabled) {
            return Snapshot.empty();
        }
        return new Snapshot(
                currentPhase.get(),
                System.nanoTime() - startedNanos,
                timingSums(),
                counterSums(),
                lastCompletedPhase.get(),
                currentPhaseElapsed(System.nanoTime()),
                currentPhaseStartedElapsed(System.nanoTime()));
    }

    /**
     * Flushes the final cumulative state and stops the periodic progress
     * heartbeat.
     */
    @Override
    public void close() {
        if (!enabled || !closed.compareAndSet(false, true)) {
            return;
        }
        publish(true);
        heartbeat.shutdownNow();
    }

    private void finishPhase(String name, long sequence) {
        long now = System.nanoTime();
        PhaseClock clock = timings.get(name);
        if (clock != null) {
            clock.exit(now);
        }
        activePhases.remove(sequence);
        lastCompletedPhase.set(name);
        addCounterQuiet("phase_completed_" + name, 1);
        Map.Entry<Long, PhaseActivity> active = activePhases.lastEntry();
        currentPhase.set(active == null ? "" : active.getValue().name());
        publish(true);
    }

    private void publish(boolean force) {
        if (!enabled) {
            return;
        }
        long usedHeap = Runtime.getRuntime().totalMemory()
                - Runtime.getRuntime().freeMemory();
        observedCounters.computeIfAbsent(
                "memory_peak_used_heap_bytes",
                ignored -> new AtomicLong())
                .accumulateAndGet(usedHeap, Math::max);
        long now = System.nanoTime();
        if (!force) {
            long previous = lastPublishedNanos.get();
            if (now - previous < PERIODIC_PUBLISH_NANOS
                    || !lastPublishedNanos.compareAndSet(previous, now)) {
                return;
            }
        } else {
            lastPublishedNanos.set(now);
        }
        listener.accept(new Snapshot(
                currentPhase.get(),
                now - startedNanos,
                timingSums(),
                counterSums(),
                lastCompletedPhase.get(),
                currentPhaseElapsed(now),
                currentPhaseStartedElapsed(now)));
    }

    private long currentPhaseElapsed(long now) {
        Map.Entry<Long, PhaseActivity> active = activePhases.lastEntry();
        return active == null ? 0 : Math.max(0, now - active.getValue().startedNanos());
    }

    private long currentPhaseStartedElapsed(long now) {
        Map.Entry<Long, PhaseActivity> active = activePhases.lastEntry();
        return active == null ? 0 : Math.max(0, active.getValue().startedNanos() - startedNanos);
    }

    private static Map<String, Long> sums(
            ConcurrentHashMap<String, LongAdder> values) {
        TreeMap<String, Long> result = new TreeMap<>();
        values.forEach((name, value) -> result.put(name, value.sum()));
        return Map.copyOf(result);
    }

    private Map<String, Long> counterSums() {
        TreeMap<String, Long> result =
                new TreeMap<>(sums(counters));
        observedCounters.forEach((name, value) ->
                result.merge(
                        name, value.get(), Math::max));
        return Map.copyOf(result);
    }

    private Map<String, Long> timingSums() {
        long now = System.nanoTime();
        TreeMap<String, Long> result =
                new TreeMap<>();
        timings.forEach((name, clock) ->
                result.put(name, clock.elapsed(now)));
        return Map.copyOf(result);
    }

    private static final class PhaseClock {
        private long accumulatedNanos;
        private long activeSinceNanos;
        private int activeEntries;

        synchronized void enter(long now) {
            if (activeEntries++ == 0) {
                activeSinceNanos = now;
            }
        }

        synchronized void exit(long now) {
            if (activeEntries < 1) {
                throw new IllegalStateException(
                        "phase timer exited without an entry");
            }
            if (--activeEntries == 0) {
                accumulatedNanos +=
                        now - activeSinceNanos;
            }
        }

        synchronized long elapsed(long now) {
            return accumulatedNanos
                    + (activeEntries == 0
                            ? 0
                            : now - activeSinceNanos);
        }
    }

    private record PhaseActivity(String name, long startedNanos) { }

    /** Immutable progress state suitable for atomic persistence. */
    public record Snapshot(
            String currentPhase,
            long elapsedNanos,
            Map<String, Long> timings,
            Map<String, Long> counters,
            String lastCompletedPhase,
            long currentPhaseElapsedNanos,
            long currentPhaseStartedElapsedNanos) {
        public Snapshot(
                String currentPhase,
                long elapsedNanos,
                Map<String, Long> timings,
                Map<String, Long> counters) {
            this(currentPhase, elapsedNanos, timings, counters, "", 0, 0);
        }

        public Snapshot {
            currentPhase = currentPhase == null ? "" : currentPhase;
            lastCompletedPhase = lastCompletedPhase == null ? "" : lastCompletedPhase;
            if (elapsedNanos < 0) {
                throw new IllegalArgumentException(
                        "elapsed time cannot be negative");
            }
            if (currentPhaseElapsedNanos < 0 || currentPhaseStartedElapsedNanos < 0) {
                throw new IllegalArgumentException("phase elapsed values cannot be negative");
            }
            timings = Map.copyOf(timings);
            counters = Map.copyOf(counters);
        }

        static Snapshot empty() {
            return new Snapshot("", 0, Map.of(), Map.of(), "", 0, 0);
        }
    }

    /** One additive phase timer. */
    public static final class Timer implements AutoCloseable {
        private static final Timer NONE = new Timer();
        private final PaceExecutionMetrics owner;
        private final String name;
        private final long sequence;
        private boolean closed;

        private Timer() {
            owner = null;
            name = "";
            sequence = -1;
            closed = true;
        }

        private Timer(
                PaceExecutionMetrics owner,
                String name,
                long sequence) {
            this.owner = owner;
            this.name = name;
            this.sequence = sequence;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                owner.finishPhase(name, sequence);
            }
        }
    }
}
