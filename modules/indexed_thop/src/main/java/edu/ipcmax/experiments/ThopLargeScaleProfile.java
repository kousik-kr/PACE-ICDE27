package edu.ipcmax.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.ipcmax.baselines.BoundaryTHopIndex;
import edu.ipcmax.baselines.GraphPartitionMetadata;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.baselines.ThopBuildControl;
import edu.ipcmax.baselines.ThopFingerprintEquivalence;
import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ThopProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;
import edu.ipcmax.experiments.querygen.ManifestChecksum;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * One-network THop index-construction diagnostic. It loads the full original network, partitions it, and runs the repaired THop boundary/inner index
 * construction under component instrumentation ({@link ThopProfile}) and explicit safeguards, writing durable stage records, event and progress streams
 * as it goes. It never reports an incomplete index as complete and runs a query only against a complete, reloaded, integrity-checked index.
 *
 * <p>Argument: path of a JSON configuration (see {@link Config}). Output: {@code out_dir/driver_result.json} (always written, also on a guard stop).</p>
 */
public final class ThopLargeScaleProfile {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT = new ObjectMapper();
    private static final long NANOS = 1_000_000_000L;

    record Config(String dataset, Path datasetDir, Path outDir, Path workDir, int partitionDepth, double loadBudgetS, double partitionBudgetS,
                  double indexBudgetS, double firstSourceBudgetS, double projectionCeilingH, double minObservationS, int maxPhase1Parallelism,
                  double heapGuardFraction, long diskQuotaBytes, long minFreeDiskBytes, long cgroupLimitBytes, long seed, double samplerIntervalS,
                  JsonNode query, boolean skipInner, boolean runQuery, boolean verifyFingerprint, int initialConcurrentSearches) {
        static Config read(Path file) throws IOException {
            JsonNode n = JSON.readTree(file.toFile());
            return new Config(n.get("dataset").asText(), Path.of(n.get("dataset_dir").asText()), Path.of(n.get("out_dir").asText()),
                    Path.of(n.get("work_dir").asText()), n.get("partition_depth").asInt(), n.get("load_budget_s").asDouble(),
                    n.get("partition_budget_s").asDouble(), n.get("index_budget_s").asDouble(), n.get("first_source_budget_s").asDouble(),
                    n.get("projection_ceiling_h").asDouble(), n.get("min_observation_s").asDouble(), n.get("max_phase1_parallelism").asInt(),
                    n.get("heap_guard_fraction").asDouble(), n.get("disk_quota_bytes").asLong(), n.get("min_free_disk_bytes").asLong(),
                    n.get("cgroup_limit_bytes").asLong(), n.get("seed").asLong(), n.get("sampler_interval_s").asDouble(),
                    n.get("query"), n.path("skip_inner").asBoolean(false), n.path("run_query").asBoolean(true), n.path("verify_fingerprint").asBoolean(false), n.path("initial_concurrent_searches").asInt(1));
        }
    }

    // ---- shared mutable state read by the sampler/watchdog
    private static Config config;
    private static final long JVM_STARTED = System.nanoTime();
    private static volatile String stage = "INIT";
    private static volatile long stageStarted = JVM_STARTED;
    private static volatile long indexStarted = 0;
    private static volatile int boundaryTotal = 0;
    private static volatile int allowedParallelism = 1;
    private static volatile long baselineHeap = 0;
    private static volatile String guardTripped = null;
    private static final Map<Integer, Map<String, Object>> completedSources = new ConcurrentHashMap<>();
    private static final AtomicLong resumedSources = new AtomicLong();
    private static final Map<String, Object> pruningState = new ConcurrentHashMap<>();
    private static final Map<String, Object> innerState = new ConcurrentHashMap<>();
    private static BufferedWriter eventLog;
    private static BufferedWriter progressLog;
    private static final Object LOG_LOCK = new Object();
    private static final Map<String, Object> result = new LinkedHashMap<>();
    private static final com.sun.management.OperatingSystemMXBean OS = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);

    private ThopLargeScaleProfile() { }

    public static void main(String[] args) throws Exception {
        config = Config.read(Path.of(args[0]));
        Files.createDirectories(config.outDir());
        Files.createDirectories(config.workDir());
        eventLog = Files.newBufferedWriter(config.outDir().resolve("stage_events.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        progressLog = Files.newBufferedWriter(config.outDir().resolve("progress.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        result.put("schema", "thop-large-scale-driver-result-v1");
        result.put("dataset", config.dataset());
        result.put("started_utc", Instant.now().toString());
        result.put("status", "RUNNING");
        writeJson("effective_resources.json", effectiveResources());
        ThopBuildControl.install(ThopLargeScaleProfile::onEvent);
        Thread sampler = new Thread(ThopLargeScaleProfile::samplerLoop, "thop-sampler");
        sampler.setDaemon(true);
        sampler.start();
        event("driver", "start", Map.of("config", config.toString(), "profile_counters_enabled", ThopProfile.ENABLED,
                "timer_pair_overhead_ns", ThopProfile.ENABLED ? ThopProfile.timerOverheadNanos() : -1.0));
        int exit = 0;
        try {
            run();
        } catch (ThopBuildControl.BuildStopped stopped) {
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("reason", stopped.reason());
            stop.put("where", stopped.where());
            stop.put("stage", stage);
            result.put("status", "STOPPED_BY_SAFEGUARD");
            result.put("stop", stop);
            event("driver", "stopped", stop);
            exit = 10;
        } catch (Throwable failure) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("type", failure.getClass().getName());
            error.put("message", String.valueOf(failure.getMessage()));
            error.put("stage", stage);
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            error.put("stack", trace.toString().lines().limit(25).toList());
            result.put("status", failure instanceof OutOfMemoryError ? "FAILED_OUT_OF_MEMORY" : "FAILED_EXCEPTION");
            result.put("error", error);
            event("driver", "failed", error);
            exit = 12;
        }
        finish(exit);
    }

    private static void finish(int exit) throws IOException {
        result.put("final_stage", stage);
        result.put("ended_utc", Instant.now().toString());
        result.put("elapsed_s", (System.nanoTime() - JVM_STARTED) / 1e9);
        result.put("final_snapshot", snapshot(true));
        result.put("completed_sources", completedSources.size());
        result.put("resumed_sources", resumedSources.get());
        writeJson("driver_result.json", result);
        synchronized (LOG_LOCK) {
            eventLog.flush();
            progressLog.flush();
        }
        System.exit(exit);
    }

    // ------------------------------------------------------------------ stages

    private static void run() throws Exception {
        // ---- Stage A: graph loading
        enter("A_GRAPH_LOADING");
        Map<String, Object> a = new LinkedHashMap<>();
        long rssBefore = vmRssBytes();
        long hwmBefore = vmHwmBytes();
        Map<String, Long> ioBefore = procIo();
        long cpuBefore = OS.getProcessCpuTime();
        long allocBefore = threadAllocated();
        Map<String, Long> loadTimings = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        GeneratedGraphDataset loaded = new GeneratedGraphLoader().load(config.datasetDir(), loadTimings::put);
        TDGraph graph = loaded.graph();
        long loadNanos = System.nanoTime() - t0;
        a.put("graph_loading_nanos", loadNanos);
        a.put("load_component_timing_ns", loadTimings);
        a.put("vertices", graph.nodeCount());
        a.put("directed_edges", graph.edgeCount());
        long travelFunctions = 0, travelBreakpoints = 0, scoreFunctions = 0, scorePieces = 0;
        for (Edge edge : graph.edges()) {
            travelFunctions++;
            travelBreakpoints += edge.travelTimeFunction().breakpoints().size();
            scoreFunctions++;
            scorePieces += edge.scoreFunction().intervals().size();
        }
        a.put("temporal_edge_function_count", travelFunctions);
        a.put("travel_time_breakpoints_total", travelBreakpoints);
        a.put("travel_time_breakpoints_per_function", travelFunctions == 0 ? 0 : travelBreakpoints / (double) travelFunctions);
        a.put("score_function_count", scoreFunctions);
        a.put("score_pieces_total", scorePieces);
        a.put("manifest_node_count_checked_by_loader", loaded.manifest() == null ? null : loaded.manifest().numNodes());
        a.put("manifest_arc_count_checked_by_loader", loaded.manifest() == null ? null : loaded.manifest().numArcs());
        long idStarted = System.nanoTime();
        a.put("dataset_payload_checksum", ManifestChecksum.datasetChecksum(config.datasetDir()));
        a.put("dataset_structure_checksum", ManifestChecksum.graphChecksum(config.datasetDir()));
        a.put("temporal_attribute_checksum", ManifestChecksum.temporalAttributeChecksum(config.datasetDir()));
        a.put("identity_checksum_nanos", System.nanoTime() - idStarted);
        idStarted = System.nanoTime();
        a.put("runtime_graph_semantic_checksum", PaceBench.graphSemanticChecksum(graph));
        a.put("semantic_checksum_nanos", System.nanoTime() - idStarted);
        a.put("rss_before_bytes", rssBefore);
        a.put("rss_after_load_bytes", vmRssBytes());
        a.put("rss_hwm_before_bytes", hwmBefore);
        a.put("rss_hwm_after_load_bytes", vmHwmBytes());
        a.put("heap_used_after_load_before_gc_bytes", usedHeap());
        a.put("cpu_time_ns", OS.getProcessCpuTime() - cpuBefore);
        a.put("allocated_bytes_loading_thread", threadAllocated() - allocBefore);
        Map<String, Long> ioAfter = procIo();
        a.put("io_bytes_read", ioAfter.get("read_bytes") - ioBefore.get("read_bytes"));
        a.put("io_chars_read", ioAfter.get("rchar") - ioBefore.get("rchar"));
        long gcStarted = System.nanoTime();
        System.gc();
        baselineHeap = usedHeap();
        a.put("live_heap_after_full_gc_bytes", baselineHeap);
        a.put("full_gc_nanos", System.nanoTime() - gcStarted);
        a.put("rss_after_gc_bytes", vmRssBytes());
        a.put("stage_wall_nanos", System.nanoTime() - stageStarted);
        a.put("status", "COMPLETE");
        writeJson("stage_A_graph_loading.json", a);
        result.put("stage_A", "COMPLETE");

        // ---- Stage B: partitioning
        enter("B_PARTITIONING");
        Map<String, Object> b = new LinkedHashMap<>();
        long bHeapBefore = usedHeap();
        long bCpuBefore = OS.getProcessCpuTime();
        long b0 = System.nanoTime();
        Map<Integer, Integer> assignment = ThopSpatialPartitioner.bisect(graph, config.partitionDepth());
        long assignNanos = System.nanoTime() - b0;
        b0 = System.nanoTime();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignment);
        long metadataNanos = System.nanoTime() - b0;
        b.put("algorithm", "Deterministic recursive spatial median bisection (x/y alternate, ties by other coordinate then vertex id), depth=" + config.partitionDepth());
        b.put("depth", config.partitionDepth());
        b.put("assignment_nanos", assignNanos);
        b.put("metadata_nanos", metadataNanos);
        b.put("partition_construction_nanos", assignNanos + metadataNanos);
        b.put("partition_count", metadata.partitionCount());
        b.put("boundary_vertices", metadata.boundaryVertexCount());
        b.put("cross_partition_directed_arcs", metadata.crossPartitionArcCount());
        b.put("internal_directed_arcs", metadata.internalDirectedArcIds().size());
        List<Integer> vertexCounts = new ArrayList<>();
        List<Integer> arcCounts = new ArrayList<>();
        Map<Integer, Integer> boundaryPerPartition = new HashMap<>();
        for (int boundaryVertex : metadata.boundaryVertexIds()) boundaryPerPartition.merge(metadata.partitionByVertex().get(boundaryVertex), 1, Integer::sum);
        List<Integer> boundaryCounts = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : metadata.verticesByPartition().entrySet()) {
            vertexCounts.add(e.getValue().size());
            arcCounts.add(metadata.internalArcIdsByPartition().get(e.getKey()).size());
            boundaryCounts.add(boundaryPerPartition.getOrDefault(e.getKey(), 0));
        }
        b.put("vertices_per_partition", stats(vertexCounts));
        b.put("internal_arcs_per_partition", stats(arcCounts));
        b.put("boundary_vertices_per_partition", stats(boundaryCounts));
        b.put("boundary_fraction_of_vertices", metadata.boundaryVertexCount() / (double) graph.nodeCount());
        b.put("cpu_time_ns", OS.getProcessCpuTime() - bCpuBefore);
        b.put("heap_used_before_bytes", bHeapBefore);
        b.put("heap_used_after_before_gc_bytes", usedHeap());
        assignment = null;
        System.gc();
        b.put("live_heap_after_full_gc_bytes", usedHeap());
        b.put("memory_consumed_by_partitioning_bytes_live", usedHeap() - baselineHeap);
        b.put("rss_bytes", vmRssBytes());
        b.put("stage_wall_nanos", System.nanoTime() - stageStarted);
        b.put("status", "COMPLETE");
        writeJson("stage_B_partitioning.json", b);
        result.put("stage_B", "COMPLETE");
        baselineHeap = usedHeap();
        boundaryTotal = metadata.boundaryVertexCount();
        if (boundaryTotal == 0) throw new IllegalStateException("partitioning produced no boundary vertices");

        // ---- optional verification of the graph fingerprint before the build computes it (independent encoder + closed-form size) and the first source in build order
        if (config.verifyFingerprint()) {
            enter("B2_INDEPENDENT_FINGERPRINT_VERIFICATION");
            Map<String, Object> fp = new LinkedHashMap<>();
            ThopFingerprintEquivalence.Independent independent = ThopFingerprintEquivalence.independentFingerprint(graph);
            fp.put("independent_encoder_digest_sha256", independent.hex());
            fp.put("independent_encoder_bytes_hashed", independent.bytes());
            fp.put("independent_encoder_nanos", independent.nanos());
            fp.put("closed_form_serialization_bytes", ThopFingerprintEquivalence.analyticByteCount(graph));
            fp.put("independent_bytes_equal_closed_form", independent.bytes() == ThopFingerprintEquivalence.analyticByteCount(graph));
            fp.put("java_array_limit_bytes", Integer.MAX_VALUE - 8L);
            fp.put("serialization_exceeds_one_java_array", independent.bytes() > Integer.MAX_VALUE - 8L);
            fp.put("note", "the build's streamed fingerprint digest is recorded by the 'graph_fingerprint' event; the two must be equal");
            writeJson("stage_B2_independent_fingerprint.json", fp);
            event("driver", "independent_fingerprint", fp);
            List<Integer> orderPreview = new ArrayList<>(metadata.boundaryVertexIds());
            java.util.Collections.shuffle(orderPreview, new java.util.Random(config.seed()));
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("first_source_in_build_order", orderPreview.get(0));
            first.put("rule", "Collections.shuffle(sorted boundary vertex ids, new Random(seed)).get(0); the build processes sources in this order (ThopBuildControl.orderSources), one at a time when phase-1 parallelism is 1");
            first.put("seed", config.seed());
            writeJson("first_source_in_build_order.json", first);
            event("driver", "first_source_in_build_order", first);
        }

        // ---- Stage C: boundary index (phase 1 profile searches, pruning, serialization)
        enter("C1_BOUNDARY_PHASE1_SOURCE_SEARCHES");
        indexStarted = System.nanoTime();
        Path boundaryFile = config.workDir().resolve("boundary.bthp");
        Path innerFile = config.workDir().resolve("inner.pthp");
        Domain domain = Domain.closed(0.0, 10080.0);
        BoundaryTHopIndex boundary;
        if (Files.exists(boundaryFile)) {
            event("C", "boundary_index_present_validating", Map.of("bytes", Files.size(boundaryFile)));
            boundary = BoundaryTHopIndex.load(graph, boundaryFile);
        } else {
            ThopBuildControl.persistPhase1In(config.workDir().resolve("phase1_shards"));
            ThopBuildControl.shuffleSourcesWithSeed(config.seed());
            ThopBuildControl.limitConcurrentSearches(config.initialConcurrentSearches());
            allowedParallelism = config.initialConcurrentSearches();
            boundary = BoundaryTHopIndex.build(graph, metadata, domain, boundaryFile, metadata.boundaryVertexIds(), config.maxPhase1Parallelism());
        }
        result.put("stage_C_boundary", "COMPLETE");
        BoundaryTHopIndex.ConstructionStatistics bs = boundary.constructionStatistics();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("status", "BOUNDARY_INDEX_COMPLETE");
        c.put("statistics", COMPACT.convertValue(bs, Map.class));
        c.put("boundary_index_bytes", Files.size(boundaryFile));
        c.put("boundary_index_sha256", sha256(boundaryFile));
        c.put("elapsed_nanos", System.nanoTime() - indexStarted);
        writeJson("stage_C_boundary_complete.json", c);

        if (config.skipInner()) {
            result.put("status", "BOUNDARY_COMPLETE_INNER_SKIPPED");
            return;
        }
        // ---- Stage C2: inner partition indexes (atomic per-partition checkpoints)
        enter("C3_INNER_PARTITION_INDEXES");
        PartitionedTHopIndex built = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        result.put("stage_C_inner", "COMPLETE");
        // ---- Stage F: serialization, reload and integrity validation
        enter("F_SERIALIZATION_RELOAD_VALIDATION");
        Map<String, Object> f = new LinkedHashMap<>();
        PartitionedTHopIndex.ConstructionStatistics builtStats = built.constructionStatistics();
        f.put("build_statistics", COMPACT.convertValue(builtStats, Map.class));
        f.put("total_build_wall_nanos", System.nanoTime() - indexStarted);
        f.put("final_index_bytes", Files.size(boundaryFile) + Files.size(innerFile));
        f.put("boundary_index_bytes", Files.size(boundaryFile));
        f.put("inner_index_bytes", Files.size(innerFile));
        long s0 = System.nanoTime();
        f.put("boundary_index_sha256", sha256(boundaryFile));
        f.put("inner_index_sha256", sha256(innerFile));
        f.put("checksum_nanos", System.nanoTime() - s0);
        s0 = System.nanoTime();
        PartitionedTHopIndex reloaded = PartitionedTHopIndex.load(graph, boundaryFile, innerFile);
        long labels = 0, segments = 0;
        for (int partitionId : metadata.verticesByPartition().keySet()) {
            var inner = reloaded.innerIndexForPartition(partitionId);
            labels += inner.statistics().labelCount();
            segments += inner.statistics().temporalFunctionPieces();
        }
        f.put("reload_all_partitions_nanos", System.nanoTime() - s0);
        f.put("reloaded_inner_labels", labels);
        f.put("reloaded_inner_profile_segments", segments);
        f.put("reloaded_partition_count", reloaded.loadedPartitionCount());
        f.put("all_partition_blocks_loaded_and_checksum_verified", reloaded.loadedPartitionCount() == metadata.partitionCount());
        f.put("built_and_reloaded_partition_metadata_equal", built.partitionMetadata().partitionByVertex().equals(reloaded.partitionMetadata().partitionByVertex())
                && built.indexDomain().equals(reloaded.indexDomain()));
        f.put("boundary_labels_profiles_retained", bs.profilesRetained());
        f.put("boundary_label_profile_segments_retained", bs.retainedFunctionPieces());
        boolean valid = Boolean.TRUE.equals(f.get("all_partition_blocks_loaded_and_checksum_verified"))
                && Boolean.TRUE.equals(f.get("built_and_reloaded_partition_metadata_equal"));
        f.put("integrity_validated", valid);
        f.put("status", valid ? "COMPLETE_VALIDATED" : "INTEGRITY_CHECK_FAILED");
        writeJson("stage_F_serialization.json", f);
        result.put("stage_F", valid ? "COMPLETE_VALIDATED" : "INTEGRITY_CHECK_FAILED");
        if (!valid) {
            result.put("status", "INDEX_INTEGRITY_FAILED");
            return;
        }
        result.put("status", "INDEX_COMPLETE_VALIDATED");
        if (config.runQuery() && config.query() != null && !config.query().isNull()) {
            enter("Q_PILOT_QUERY");
            runPilotQuery(graph, reloaded);
        }
    }

    private static void runPilotQuery(TDGraph graph, PartitionedTHopIndex index) throws IOException {
        JsonNode q = config.query();
        String queryId = q.get("query_id").asText();
        int source = q.get("source").asInt();
        int destination = q.get("destination").asInt();
        double start = q.get("interval_start").asDouble();
        double end = q.get("interval_end").asDouble();
        Map<String, Object> out = new LinkedHashMap<>();
        long q0 = System.nanoTime();
        try {
            var queryResult = index.query(source, destination, Domain.closed(start, end));
            long queryNanos = System.nanoTime() - q0;
            ExactArrivalProfile travel = queryResult.exactTravelTimeProfile();
            if (travel == null || travel.isEmpty()) {
                out.put("thop_status", "THOP_NO_PROFILE_UNREACHABLE_OR_UNSUPPORTED");
                out.put("query_nanos", queryNanos);
            } else {
                ObjectNode rendered = ThopWitnessCli.render(graph, queryId, source, destination, ThopWitnessCli.parse(q.get("budget").asText()), travel, true, 0L, "large-scale-pilot");
                rendered.put("schema", "thop-large-scale-pilot-query-v1");
                ObjectNode live = rendered.putObject("thop_query");
                live.put("query_ns", queryNanos);
                live.put("profile_compositions", queryResult.queryStatistics().profileCompositions());
                ArrayNode pieces = live.putArray("travel_time_pieces");
                for (ExactAffineSegment piece : travel.segments()) {
                    ObjectNode node = pieces.addObject();
                    node.set("interval", ThopWitnessCli.interval(piece.interval()));
                    node.put("slope", ThopWitnessCli.rational(piece.slope()));
                    node.put("intercept", ThopWitnessCli.rational(piece.intercept()));
                }
                Files.writeString(config.outDir().resolve("pilot_query_result.json"), JSON.writeValueAsString(rendered) + "\n");
                out.put("thop_status", "THOP_QUERY_EXECUTED");
                out.put("query_nanos", queryNanos);
                out.put("profile_pieces", travel.segments().size());
                out.put("fully_verified", rendered.get("fully_verified").asBoolean());
                out.put("timing_ns", COMPACT.convertValue(rendered.get("timing_ns"), Map.class));
            }
        } catch (RuntimeException failed) {
            out.put("thop_status", "THOP_QUERY_FAILED");
            out.put("detail", failed.getClass().getSimpleName() + ": " + failed.getMessage());
            out.put("query_nanos", System.nanoTime() - q0);
        }
        writeJson("pilot_query_status.json", out);
        result.put("pilot_query", out.get("thop_status"));
    }

    // ------------------------------------------------------------------ events, sampler and safeguards

    private static long parseLong(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) return 0;
        int i = at + key.length();
        int j = i;
        while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
        return j == i ? 0 : Long.parseLong(line.substring(i, j));
    }

    private static void enter(String name) {
        stage = name;
        stageStarted = System.nanoTime();
        event("driver", "stage_enter", Map.of("stage", name));
    }

    private static void onEvent(String eventStage, String name, Map<String, Object> fields) {
        if (eventStage.equals("phase1")) {
            int source = ((Number) fields.get("source")).intValue();
            completedSources.put(source, fields);
            if (name.equals("source_resumed")) resumedSources.incrementAndGet();
            else if (completedSources.size() - resumedSources.get() == 1) adaptParallelism();
        } else if (eventStage.equals("boundary_pruning")) {
            if (!stage.equals("C2_BOUNDARY_PRUNING")) {
                stage = "C2_BOUNDARY_PRUNING";
                stageStarted = System.nanoTime();
            }
            pruningState.putAll(fields);
        } else if (eventStage.equals("progress_line")) {
            innerState.put("last_line", name);
            if (name.startsWith("stage=partitioned_build_start")) {
                innerState.put("partitions_total", parseLong(name, "partitions="));
            } else if (name.startsWith("stage=partition_start")) {
                innerState.put("partition_started_nanos", System.nanoTime());
                innerState.put("current_partition", parseLong(name, "partition="));
            } else if (name.startsWith("stage=partition_complete")) {
                innerState.put("partitions_completed", parseLong(name, "completed_partitions="));
                long wall = parseLong(name, "wall_ns=");
                innerState.merge("partition_wall_sum_ns", wall, (x, y) -> (Long) x + (Long) y);
                innerState.put("partition_started_nanos", 0L);
            } else if (name.startsWith("stage=partition_checkpoint_loaded")) {
                innerState.merge("partitions_resumed", 1L, (x, y) -> (Long) x + (Long) y);
            }
        }
        event(eventStage, name, fields);
    }

    /** After the first fresh source search: allow as many concurrent searches as the measured per-search heap permits (the cap is a safeguard, not a tuning). */
    private static void adaptParallelism() {
        long maxPeak = 0;
        for (Map<String, Object> s : completedSources.values()) maxPeak = Math.max(maxPeak, ((Number) s.get("sampled_peak_heap_bytes")).longValue());
        long perSearch = Math.max(1, maxPeak - baselineHeap);
        long budget = (long) (Runtime.getRuntime().maxMemory() * 0.80) - baselineHeap;
        int byMemory = (int) Math.max(1, Math.min(config.maxPhase1Parallelism(), budget / (perSearch * 3 / 2)));
        allowedParallelism = byMemory;
        ThopBuildControl.limitConcurrentSearches(byMemory);
        event("driver", "parallelism_set", Map.of("allowed_concurrent_searches", byMemory, "measured_peak_heap_per_search_bytes", perSearch,
                "baseline_live_heap_bytes", baselineHeap, "rule", "floor(0.80*maxHeap - baseline) / (1.5 * peak per search), capped at max_phase1_parallelism"));
    }

    private static void event(String eventStage, String name, Map<String, Object> fields) {
        try {
            ObjectNode line = COMPACT.createObjectNode();
            line.put("utc", Instant.now().toString());
            line.put("t_s", (System.nanoTime() - JVM_STARTED) / 1e9);
            line.put("stage", stage);
            line.put("source", eventStage);
            line.put("event", name);
            line.set("fields", COMPACT.valueToTree(fields));
            synchronized (LOG_LOCK) {
                eventLog.write(COMPACT.writeValueAsString(line));
                eventLog.newLine();
                eventLog.flush();
            }
        } catch (IOException ignored) {
            // the durable stream is best effort; the stage JSON files remain authoritative
        }
    }

    private static void samplerLoop() {
        long lastProfile = 0;
        long lastDisk = 0;
        long diskBytes = 0;
        int heapStrikes = 0;
        long lastStopCheck = 0;
        while (true) {
            try {
                Thread.sleep((long) (config.samplerIntervalS() * 1000));
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            try {
                if (now - lastDisk > 30 * NANOS) {
                    diskBytes = directoryBytes(config.workDir());
                    lastDisk = now;
                }
                boolean withProfile = now - lastProfile > 60 * NANOS;
                if (withProfile) lastProfile = now;
                Map<String, Object> snap = snapshot(withProfile);
                snap.put("workdir_bytes", diskBytes);
                synchronized (LOG_LOCK) {
                    progressLog.write(COMPACT.writeValueAsString(snap));
                    progressLog.newLine();
                    progressLog.flush();
                }
                Files.writeString(config.outDir().resolve("current_state.json.tmp"), JSON.writeValueAsString(snap));
                Files.move(config.outDir().resolve("current_state.json.tmp"), config.outDir().resolve("current_state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                // ---- safeguards
                double heapFraction = usedHeap() / (double) Runtime.getRuntime().maxMemory();
                heapStrikes = heapFraction > config.heapGuardFraction() ? heapStrikes + 1 : 0;
                if (heapStrikes >= 3) trip("HEAP_GUARD", "used heap " + usedHeap() + " > " + config.heapGuardFraction() + " of max for 3 consecutive samples");
                if (vmRssBytes() > 0.95 * config.cgroupLimitBytes()) trip("RSS_GUARD", "resident set " + vmRssBytes() + " above 95% of the cgroup limit");
                if (config.workDir().toFile().getUsableSpace() < config.minFreeDiskBytes()) trip("DISK_FREE_GUARD", "free disk below " + config.minFreeDiskBytes());
                if (diskBytes > config.diskQuotaBytes()) trip("DISK_QUOTA_GUARD", "work directory " + diskBytes + " > quota " + config.diskQuotaBytes());
                double stageElapsed = (now - stageStarted) / 1e9;
                if (stage.equals("A_GRAPH_LOADING") && stageElapsed > config.loadBudgetS()) trip("LOAD_BUDGET_EXHAUSTED", "graph loading exceeded " + config.loadBudgetS() + " s");
                if (stage.equals("B_PARTITIONING") && stageElapsed > config.partitionBudgetS()) trip("PARTITION_BUDGET_EXHAUSTED", "partitioning exceeded " + config.partitionBudgetS() + " s");
                if (indexStarted > 0 && stage.startsWith("C") && now - lastStopCheck > 5 * NANOS) {
                    lastStopCheck = now;
                    evaluateIndexProjection(now);
                }
            } catch (Throwable ignored) {
                // never let a recorder failure end the run
            }
        }
    }

    /** The only places that stop construction: budgets and projections computed from the sources and hubs actually observed. */
    private static void evaluateIndexProjection(long now) {
        double indexElapsed = (now - indexStarted) / 1e9;
        double ceilingS = config.projectionCeilingH() * 3600;
        if (indexElapsed > config.indexBudgetS()) {
            stopConstruction("INDEX_CONSTRUCTION_BUDGET_EXHAUSTED", "index construction exceeded the diagnostic budget of " + config.indexBudgetS() + " s");
            return;
        }
        if (indexElapsed < config.minObservationS()) return;
        if (stage.equals("C1_BOUNDARY_PHASE1_SOURCE_SEARCHES")) {
            int done = completedSources.size();
            int fresh = (int) (done - resumedSources.get());
            double meanSearchS = 0;
            int counted = 0;
            for (Map<String, Object> s : completedSources.values()) {
                if (Boolean.TRUE.equals(s.get("resumed"))) continue;
                meanSearchS += ((Number) s.get("search_nanos")).doubleValue() / 1e9;
                counted++;
            }
            int parallel = Math.max(1, allowedParallelism);
            if (counted == 0) {
                // no source has finished: the longest running one gives a lower bound for every source at the most optimistic parallelism
                double longest = 0;
                // the observation clock starts at the first label-correcting expansion: the search's creation also covers the common-support pass over every arc (about 40 minutes on USA)
                for (ThopBuildControl.SearchProbe probe : ThopBuildControl.activeSearches()) {
                    if ("label_correcting".equals(probe.phase)) longest = Math.max(longest, (now - probe.searchStartedNanos) / 1e9);
                }
                double lowerBoundTotal = boundaryTotal * longest / Math.max(1, config.maxPhase1Parallelism());
                if (lowerBoundTotal > ceilingS) {
                    stopConstruction("FIRST_SOURCE_LOWER_BOUND_EXCEEDS_CEILING", "the first boundary-source search has run " + longest + " s without finishing; "
                            + boundaryTotal + " sources at even " + config.maxPhase1Parallelism() + "-way parallelism need at least " + lowerBoundTotal / 3600 + " h > ceiling "
                            + config.projectionCeilingH() + " h");
                } else if (longest > config.firstSourceBudgetS()) {
                    stopConstruction("FIRST_SOURCE_BUDGET_EXHAUSTED", "the first boundary-source search exceeded " + config.firstSourceBudgetS() + " s");
                }
            } else {
                meanSearchS /= counted;
                int remaining = boundaryTotal - done;
                double remainingS = remaining * meanSearchS / parallel;
                if (indexElapsed + remainingS > ceilingS) {
                    stopConstruction("PHASE1_PROJECTION_EXCEEDS_CEILING", "phase 1 needs about " + (indexElapsed + remainingS) / 3600 + " h (mean source search " + meanSearchS
                            + " s, " + remaining + " sources left, " + parallel + " concurrent) > ceiling " + config.projectionCeilingH() + " h");
                }
            }
        } else if (stage.equals("C3_INNER_PARTITION_INDEXES") && innerState.containsKey("partitions_total")) {
            long total = (Long) innerState.get("partitions_total");
            long completed = innerState.containsKey("partitions_completed") ? (Long) innerState.get("partitions_completed") : 0L;
            long running = innerState.containsKey("partition_started_nanos") ? (Long) innerState.get("partition_started_nanos") : 0L;
            double runningS = running == 0 ? 0 : (now - running) / 1e9;
            double sumS = innerState.containsKey("partition_wall_sum_ns") ? (Long) innerState.get("partition_wall_sum_ns") / 1e9 : 0;
            long freshDone = completed - (innerState.containsKey("partitions_resumed") ? (Long) innerState.get("partitions_resumed") : 0L);
            double perPartition = freshDone > 0 ? sumS / freshDone : runningS;
            double projected = indexElapsed + (total - completed) * Math.max(perPartition, runningS);
            if ((freshDone > 0 || runningS > 60) && projected > ceilingS) {
                stopConstruction("INNER_PROJECTION_EXCEEDS_CEILING", "inner partition indexes: " + completed + " of " + total + " done, " + perPartition
                        + " s per partition (current partition running " + runningS + " s) -> about " + projected / 3600 + " h > ceiling " + config.projectionCeilingH() + " h");
            }
        } else if (stage.equals("C2_BOUNDARY_PRUNING") && pruningState.containsKey("processed_hubs")) {
            double processed = ((Number) pruningState.get("processed_hubs")).doubleValue();
            double elapsed = ((Number) pruningState.get("elapsed_nanos")).doubleValue() / 1e9;
            if (processed >= 3 && elapsed >= 120) {
                double projected = elapsed * Math.pow(boundaryTotal / processed, 2);
                if (projected > ceilingS) {
                    stopConstruction("PRUNING_PROJECTION_EXCEEDS_CEILING", "label pruning handled " + processed + " of " + boundaryTotal + " hubs in " + elapsed
                            + " s; hub cost grows with the hubs already processed, so at least " + projected / 3600 + " h (quadratic lower bound) > ceiling");
                }
            }
        }
    }

    private static void stopConstruction(String reason, String detail) {
        if (ThopBuildControl.stopRequested()) return;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reason", reason);
        fields.put("detail", detail);
        fields.put("stage", stage);
        fields.put("completed_sources", completedSources.size());
        fields.put("active_searches", describeProbes());
        event("driver", "stop_requested", fields);
        result.put("stop_request", fields);
        ThopBuildControl.requestStop(reason);
    }

    private static void trip(String reason, String detail) {
        if (guardTripped != null) return;
        guardTripped = reason;
        if (stage.startsWith("C")) {
            stopConstruction(reason, detail);
            // the cooperative stop normally ends the build within seconds; a build that does not react is halted after a grace period
            new Thread(() -> {
                try {
                    Thread.sleep(180_000);
                } catch (InterruptedException e) {
                    return;
                }
                haltWithRecord(reason, detail);
            }, "thop-halt-grace").start();
        } else {
            haltWithRecord(reason, detail);
        }
    }

    private static void haltWithRecord(String reason, String detail) {
        try {
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("reason", reason);
            stop.put("detail", detail);
            stop.put("stage", stage);
            result.put("status", "STOPPED_BY_SAFEGUARD_HALT");
            result.put("stop", stop);
            event("driver", "halt", stop);
            result.put("final_stage", stage);
            result.put("ended_utc", Instant.now().toString());
            result.put("elapsed_s", (System.nanoTime() - JVM_STARTED) / 1e9);
            result.put("final_snapshot", snapshot(true));
            result.put("completed_sources", completedSources.size());
            result.put("resumed_sources", resumedSources.get());
            writeJson("driver_result.json", result);
            synchronized (LOG_LOCK) {
                eventLog.flush();
                progressLog.flush();
            }
        } catch (Throwable ignored) {
            // halting is the priority
        }
        Runtime.getRuntime().halt(11);
    }

    private static List<Map<String, Object>> describeProbes() {
        List<Map<String, Object>> probes = new ArrayList<>();
        long now = System.nanoTime();
        for (ThopBuildControl.SearchProbe p : ThopBuildControl.activeSearches()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", p.source);
            m.put("thread", p.threadName);
            m.put("elapsed_s", (now - p.startedNanos) / 1e9);
            m.put("phase", p.phase);
            boolean searching = "label_correcting".equals(p.phase);
            m.put("setup_s", searching ? (p.searchStartedNanos - p.startedNanos) / 1e9 : (now - p.startedNanos) / 1e9);
            m.put("search_elapsed_s", searching ? (now - p.searchStartedNanos) / 1e9 : 0.0);
            m.put("expanded", p.expanded);
            m.put("generated", p.generated);
            m.put("queued", p.queued);
            m.put("vertices_labelled", p.labelled);
            m.put("profile_pieces_held", p.piecesHeld);
            m.put("max_profile_pieces", p.maxProfilePieces);
            m.put("max_rational_component_bits", p.maxRationalBits);
            probes.add(m);
        }
        return probes;
    }

    private static Map<String, Object> snapshot(boolean withProfile) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("utc", Instant.now().toString());
        s.put("t_s", (System.nanoTime() - JVM_STARTED) / 1e9);
        s.put("stage", stage);
        s.put("stage_elapsed_s", (System.nanoTime() - stageStarted) / 1e9);
        s.put("index_elapsed_s", indexStarted == 0 ? 0 : (System.nanoTime() - indexStarted) / 1e9);
        s.put("heap_used_bytes", usedHeap());
        s.put("heap_max_bytes", Runtime.getRuntime().maxMemory());
        s.put("rss_bytes", vmRssBytes());
        s.put("rss_hwm_bytes", vmHwmBytes());
        s.put("process_cpu_s", OS.getProcessCpuTime() / 1e9);
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        s.put("gc_count", gcCount);
        s.put("gc_ms", gcMs);
        s.put("io", procIo());
        s.put("boundary_vertices_total", boundaryTotal);
        s.put("sources_completed", completedSources.size());
        s.put("sources_resumed", resumedSources.get());
        s.put("allowed_concurrent_searches", allowedParallelism);
        s.put("active_searches", describeProbes());
        if (!pruningState.isEmpty()) s.put("pruning", new LinkedHashMap<>(pruningState));
        if (!innerState.isEmpty()) s.put("inner", new LinkedHashMap<>(innerState));
        if (withProfile) s.put("component_profile", ThopProfile.snapshot());
        s.put("stop_requested", ThopBuildControl.stopReason());
        return s;
    }

    private static Map<String, Object> effectiveResources() throws IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available_processors", Runtime.getRuntime().availableProcessors());
        r.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        r.put("jvm_input_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        r.put("java_version", System.getProperty("java.version"));
        r.put("pid", ProcessHandle.current().pid());
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("Cpus_allowed_list:")) r.put("cpus_allowed_list", line.substring(18).trim());
        }
        String cgroupPath = "";
        for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
            if (line.startsWith("0::")) cgroupPath = line.substring(3).trim();
        }
        r.put("cgroup_path", cgroupPath);
        for (String name : List.of("memory.max", "memory.swap.max", "memory.current", "memory.events", "cpu.max", "cpuset.cpus.effective")) {
            Path file = Path.of("/sys/fs/cgroup" + cgroupPath + "/" + name);
            if (Files.isReadable(file)) r.put("cgroup_" + name.replace('.', '_'), Files.readString(file).trim());
        }
        r.put("free_disk_bytes_workdir", config.workDir().toFile().getUsableSpace());
        r.put("config_cgroup_limit_bytes", config.cgroupLimitBytes());
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> stats(List<Integer> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (values.isEmpty()) return m;
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(null);
        long sum = 0;
        for (int v : sorted) sum += v;
        m.put("min", sorted.get(0));
        m.put("median", sorted.get(sorted.size() / 2));
        m.put("mean", sum / (double) sorted.size());
        m.put("p90", sorted.get((int) Math.min(sorted.size() - 1, Math.floor(sorted.size() * 0.9))));
        m.put("max", sorted.get(sorted.size() - 1));
        m.put("sum", sum);
        return m;
    }

    private static void writeJson(String name, Object value) throws IOException {
        Path target = config.outDir().resolve(name);
        Path tmp = config.outDir().resolve(name + ".tmp");
        Files.writeString(tmp, JSON.writeValueAsString(value) + "\n");
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long threadAllocated() {
        try {
            com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (Throwable unsupported) {
            return -1;
        }
    }

    private static long vmRssBytes() {
        return procStatusKb("VmRSS:") * 1024;
    }

    private static long vmHwmBytes() {
        return procStatusKb("VmHWM:") * 1024;
    }

    private static long procStatusKb(String key) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith(key)) return Long.parseLong(line.substring(key.length()).trim().split("\\s+")[0]);
            }
        } catch (Exception ignored) {
            // procfs unavailable
        }
        return -1;
    }

    private static Map<String, Long> procIo() {
        Map<String, Long> io = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/io"))) {
                String[] kv = line.split(":\\s*");
                if (kv.length == 2) io.put(kv[0], Long.parseLong(kv[1].trim()));
            }
        } catch (Exception ignored) {
            // procfs unavailable
        }
        for (String key : List.of("rchar", "wchar", "read_bytes", "write_bytes")) io.putIfAbsent(key, 0L);
        return io;
    }

    private static long directoryBytes(Path directory) {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
