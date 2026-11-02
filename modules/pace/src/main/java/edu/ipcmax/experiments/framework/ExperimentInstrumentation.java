package edu.ipcmax.experiments.framework;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.ipcmax.core.pcmax.PaceExecutionMetrics;

/** Mutable per-query counters and phase timings owned by one worker. */
public final class ExperimentInstrumentation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long PROGRESS_PERSIST_PERIOD_NANOS = 1_000_000_000L;
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private final Path progressPath;
    private String currentPhase = "";
    private String lastCompletedPhase = "";
    private long elapsedNanos;
    private long activePhaseElapsedNanos;
    private long activePhaseStartedElapsedNanos;
    private long lastSnapshotMonotonicNanos;
    private long lastProgressPersistNanos;
    private boolean progressFrozen;

    public ExperimentInstrumentation() {
        this(null);
    }

    /** Creates instrumentation that atomically persists live progress. */
    public ExperimentInstrumentation(Path progressPath) {
        this.progressPath = progressPath;
    }

    public synchronized void addCounter(String name, long amount) {
        counters.merge(name, amount, Long::sum);
    }

    public void increment(String name) {
        addCounter(name, 1);
    }

    public synchronized void setTiming(String name, long nanos) {
        timings.put(name, nanos);
    }

    public synchronized void addTiming(String name, long nanos) {
        timings.merge(name, nanos, Long::sum);
    }

    public synchronized Map<String, Long> counters() {
        return Map.copyOf(counters);
    }

    public synchronized Map<String, Long> timings() {
        return Map.copyOf(timings);
    }

    /** Accepts and atomically persists one cumulative core snapshot. */
    public synchronized void accept(
            PaceExecutionMetrics.Snapshot snapshot) {
        if (progressFrozen) {
            return;
        }
        currentPhase = snapshot.currentPhase();
        lastCompletedPhase = snapshot.lastCompletedPhase();
        elapsedNanos = snapshot.elapsedNanos();
        activePhaseElapsedNanos = snapshot.currentPhaseElapsedNanos();
        activePhaseStartedElapsedNanos = snapshot.currentPhaseStartedElapsedNanos();
        lastSnapshotMonotonicNanos = System.nanoTime();
        snapshot.timings().forEach(timings::put);
        snapshot.counters().forEach(counters::put);
        if (progressPath != null) {
            long now = System.nanoTime();
            if (lastProgressPersistNanos == 0
                    || now - lastProgressPersistNanos >= PROGRESS_PERSIST_PERIOD_NANOS) {
                writeProgress();
                lastProgressPersistNanos = now;
            }
        }
    }

    /** Last phase entered by the live query worker. */
    public synchronized String currentPhase() {
        return currentPhase;
    }

    /** Cumulative query elapsed time in the last live snapshot. */
    public synchronized long elapsedNanos() {
        return elapsedNanos;
    }

    /** Freezes the latest query progress at the deadline boundary. */
    public synchronized void freezeProgressAtTerminal() {
        if (progressFrozen) {
            return;
        }
        long now = System.nanoTime();
        long sinceSnapshot = lastSnapshotMonotonicNanos == 0
                ? 0 : Math.max(0, now - lastSnapshotMonotonicNanos);
        elapsedNanos += sinceSnapshot;
        if (!currentPhase.isBlank()) {
            activePhaseElapsedNanos += sinceSnapshot;
        }
        lastSnapshotMonotonicNanos = now;
        progressFrozen = true;
        if (progressPath != null) {
            writeProgress();
            lastProgressPersistNanos = now;
        }
    }

    /** Immutable copy of the last active/finished component marker. */
    public synchronized Map<String, Object> progressState() {
        return Map.of(
                "last_entered_phase", currentPhase,
                "last_completed_phase", lastCompletedPhase,
                "active_phase_elapsed_nanos", activePhaseElapsedNanos,
                "active_phase_started_elapsed_nanos", activePhaseStartedElapsedNanos,
                "elapsed_nanos", elapsedNanos,
                "frozen_at_terminal", progressFrozen);
    }

    /** Recovers the most recent live-worker snapshot, if one exists. */
    public synchronized boolean recover(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            Map<String, Object> value = JSON.readValue(
                    Files.readString(path, StandardCharsets.UTF_8),
                    new TypeReference<>() {
                    });
            currentPhase = String.valueOf(
                    value.getOrDefault("current_phase", ""));
            lastCompletedPhase = String.valueOf(
                    value.getOrDefault("last_completed_phase", ""));
            elapsedNanos = number(
                    value.get("elapsed_nanos"));
            activePhaseElapsedNanos = number(
                    value.get("active_phase_elapsed_nanos"));
            activePhaseStartedElapsedNanos = number(
                    value.get("active_phase_started_elapsed_nanos"));
            progressFrozen = Boolean.TRUE.equals(value.get("frozen_at_terminal"));
            lastProgressPersistNanos = System.nanoTime();
            putNumbers(value.get("timing_ns"), timings);
            putNumbers(value.get("counters"), counters);
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private void writeProgress() {
        try {
            Path absolute = progressPath.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schema_version", 2);
            value.put("timestamp_utc", Instant.now().toString());
            value.put("current_phase", currentPhase);
            value.put("last_completed_phase", lastCompletedPhase);
            value.put("active_phase_elapsed_nanos", activePhaseElapsedNanos);
            value.put("active_phase_started_elapsed_nanos", activePhaseStartedElapsedNanos);
            value.put("elapsed_nanos", elapsedNanos);
            value.put("frozen_at_terminal", progressFrozen);
            value.put("timing_ns", new LinkedHashMap<>(timings));
            value.put("counters", new LinkedHashMap<>(counters));
            Path temporary = absolute.resolveSibling(
                    absolute.getFileName() + ".tmp-"
                            + ProcessHandle.current().pid() + "-"
                            + Thread.currentThread().threadId());
            Files.writeString(
                    temporary,
                    JSON.writeValueAsString(value),
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Query execution must not fail because diagnostic persistence failed.
        }
    }

    private static void putNumbers(
            Object source,
            Map<String, Long> destination) {
        if (!(source instanceof Map<?, ?> values)) {
            return;
        }
        values.forEach((name, value) -> {
            if (name != null && value instanceof Number number) {
                destination.put(name.toString(), number.longValue());
            }
        });
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }
}
