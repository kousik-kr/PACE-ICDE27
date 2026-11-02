package edu.ipcmax.experiments.framework;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;

/** One common benchmark interface for all candidate methods. */
public interface ExperimentAlgorithm extends AutoCloseable {
    String id();

    /**
     * Builds immutable dataset-wide state outside measured query time.
     * Algorithms without preprocessing keep the default no-op.
     */
    default void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
    }

    /** Releases prepared state. Persistent on-disk indexes remain available for reuse. */
    @Override
    default void close() {
    }

    AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation);

    /**
     * Diagnostics produced after the query's clock has stopped, for example intermediate
     * answers rebuilt from a commit log. Runs on the harness thread once per measured
     * query, never inside the timed algorithm run; the returned values are added to the
     * run record. The default has none.
     */
    default java.util.Map<String, Object> afterMeasuredRun(
            QuerySpec query,
            AlgorithmConfig config,
            AlgorithmResult result,
            java.nio.file.Path artifactDirectory,
            String runId,
            java.util.List<Long> checkpointsMs) {
        return java.util.Map.of();
    }
}
