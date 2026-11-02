package edu.ipcmax.baselines;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Optional low-overhead construction diagnostics, enabled only by R24 builds. */
final class ThopBuildProgress {
    static final boolean ENABLED = Boolean.getBoolean("ipcmax.thop.progress");
    private static final long INTERVAL_NANOS = Math.max(1L,
            Long.getLong("ipcmax.thop.progress.interval.seconds", 10L)) * 1_000_000_000L;
    private static final com.sun.management.OperatingSystemMXBean OS_BEAN =
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);

    private ThopBuildProgress() { }

    static long intervalNanos() { return INTERVAL_NANOS; }

    static void log(String event) {
        if (!ENABLED) return;
        long cpu = processCpuNanos();
        long heap = usedHeapBytes();
        long rss = peakRssBytes();
        System.err.println("THOP_PROGRESS " + event
                + " process_cpu_ns=" + cpu
                + " heap_used_bytes=" + heap
                + " rss_hwm_bytes=" + rss);
        if (ThopBuildControl.listening()) {
            ThopBuildControl.emit("progress_line", event, java.util.Map.of(
                    "process_cpu_ns", cpu, "heap_used_bytes", heap, "rss_hwm_bytes", rss));
        }
    }

    static long processCpuNanos() {
        return OS_BEAN == null ? -1L : OS_BEAN.getProcessCpuTime();
    }

    static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long peakRssBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    String[] values = line.trim().split("\\s+");
                    return Long.parseLong(values[1]) * 1024L;
                }
            }
        } catch (Exception ignored) {
            // JVM heap data remains available when procfs is not mounted.
        }
        return -1L;
    }
}
