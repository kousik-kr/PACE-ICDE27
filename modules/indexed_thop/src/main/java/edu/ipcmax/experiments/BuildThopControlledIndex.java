package edu.ipcmax.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.baselines.BoundaryTHopIndex;
import edu.ipcmax.baselines.GraphPartitionMetadata;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphDataset;
import edu.ipcmax.core.loader.GeneratedGraphLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.HexFormat;

/** Builds and reloads only the controlled THop index; it submits no query. */
public final class BuildThopControlledIndex {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Boundary phase-1 searches are independent per source; {@code -Dthop.phase1Parallelism=N} runs N at once (default 1 keeps the historical serial behaviour). */
    private static final int PHASE1_PARALLELISM = Integer.getInteger("thop.phase1Parallelism", 1);

    private BuildThopControlledIndex() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("dataset partitions index-dir output-json");
        Path datasetDir = Path.of(args[0]);
        Path partitionFile = Path.of(args[1]);
        Path indexDir = Path.of(args[2]);
        Path reportFile = Path.of(args[3]);
        long totalStarted = System.nanoTime();
        Files.createDirectories(indexDir);
        Files.createDirectories(reportFile.getParent());

        GeneratedGraphDataset loaded = new GeneratedGraphLoader().load(datasetDir);
        TDGraph graph = loaded.graph();
        JsonNode partitionRoot = JSON.readTree(partitionFile.toFile());
        Map<Integer, Integer> assignment = new TreeMap<>();
        partitionRoot.path("assignments").fields().forEachRemaining(entry ->
                assignment.put(Integer.parseInt(entry.getKey()), entry.getValue().intValue()));
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignment);

        Domain indexDomain = Domain.closed(0.0, 10080.0);
        Path boundaryFile = indexDir.resolve("boundary.bthp");
        Path innerFile = indexDir.resolve("inner.pthp");
        if (Files.exists(innerFile)) throw new IllegalStateException("refusing to overwrite an existing controlled inner index");

        long loadStarted = System.nanoTime();
        String graphChecksum = PaceBench.graphSemanticChecksum(graph);
        long graphLoadAndIdentityNanos = System.nanoTime() - loadStarted;
        long boundaryBuildNanos;
        long boundaryLoadNanos = 0;
        boolean boundaryReused = Files.exists(boundaryFile);
        BoundaryTHopIndex boundary;
        if (boundaryReused) {
            long boundaryLoadStarted = System.nanoTime();
            boundary = BoundaryTHopIndex.load(graph, boundaryFile);
            boundaryLoadNanos = System.nanoTime() - boundaryLoadStarted;
            if (!boundary.partitionMetadata().partitionByVertex().equals(metadata.partitionByVertex())
                    || !boundary.indexDomain().equals(indexDomain)) {
                throw new IllegalStateException("existing controlled boundary index does not match graph/partition/domain");
            }
            boundaryBuildNanos = boundary.constructionStatistics().constructionWallNanos();
        } else {
            long boundaryStarted = System.nanoTime();
            boundary = BoundaryTHopIndex.build(
                    graph, metadata, indexDomain, boundaryFile, metadata.boundaryVertexIds(), PHASE1_PARALLELISM);
            boundaryBuildNanos = System.nanoTime() - boundaryStarted;
        }
        long innerStarted = System.nanoTime();
        PartitionedTHopIndex built = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        long innerBuildNanos = System.nanoTime() - innerStarted;
        long reloadStarted = System.nanoTime();
        PartitionedTHopIndex reloaded = PartitionedTHopIndex.load(graph, boundaryFile, innerFile);
        for (int partitionId : metadata.verticesByPartition().keySet()) {
            reloaded.innerIndexForPartition(partitionId);
        }
        long reloadNanos = System.nanoTime() - reloadStarted;

        PartitionedTHopIndex.ConstructionStatistics stats = reloaded.constructionStatistics();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema", "thop-controlled-index-build-v1");
        output.put("query_count", 0);
        output.put("dataset_id", "NY-Exact-radius");
        output.put("graph_semantic_checksum", graphChecksum);
        output.put("vertices", graph.nodeCount());
        output.put("directed_arcs", graph.edgeCount());
        output.put("index_domain", Map.of("start", "0", "end", "10080", "closed", true));
        output.put("index_type", "Partitioned-THop exact rational BTHP-v2/PTHP-v4");
        output.put("partition_algorithm", partitionRoot.path("partition_algorithm").asText());
        output.put("partition_file_sha256", sha256(partitionFile));
        output.put("partition_count", metadata.partitionCount());
        output.put("partition_sizes", metadata.verticesByPartition().entrySet().stream().collect(
                java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), e -> e.getValue().size(),
                        (a, b) -> a, TreeMap::new)));
        output.put("boundary_vertices", metadata.boundaryVertexCount());
        output.put("cross_partition_directed_arcs", metadata.crossPartitionArcCount());
        output.put("phase1_parallelism", PHASE1_PARALLELISM);
        output.put("boundary_build_nanos", boundaryBuildNanos);
        output.put("boundary_reused_from_validated_prior_attempt", boundaryReused);
        output.put("boundary_index_load_for_resume_nanos", boundaryLoadNanos);
        output.put("inner_build_nanos", innerBuildNanos);
        output.put("load_and_identity_nanos", graphLoadAndIdentityNanos);
        output.put("save_reload_nanos", reloadNanos);
        output.put("index_bytes", Files.size(boundaryFile) + Files.size(innerFile));
        output.put("boundary_index_bytes", Files.size(boundaryFile));
        output.put("inner_index_bytes", Files.size(innerFile));
        Path checkpointDirectory = innerFile.resolveSibling(innerFile.getFileName() + ".checkpoints");
        output.put("partition_checkpoint_directory", checkpointDirectory.toString());
        try (var checkpoints = Files.list(checkpointDirectory)) {
            var checkpointFiles = checkpoints.sorted().map(path -> {
                try {
                    return Map.of("path", path.getFileName().toString(),
                            "bytes", Files.size(path), "sha256", sha256(path));
                } catch (Exception failure) {
                    throw new java.io.UncheckedIOException(new java.io.IOException(failure));
                }
            }).toList();
            output.put("partition_checkpoint_files", checkpointFiles);
            output.put("partition_checkpoint_file_count", checkpointFiles.size());
            output.put("partition_checkpoint_total_bytes", checkpointFiles.stream()
                    .mapToLong(item -> ((Number) item.get("bytes")).longValue()).sum());
        }
        output.put("boundary_index_sha256", sha256(boundaryFile));
        output.put("inner_index_sha256", sha256(innerFile));
        output.put("boundary_profile_search_calls", boundary.constructionStatistics().boundaryProfileSearchCalls());
        output.put("inner_search_calls", stats.searchCalls());
        output.put("resumed_partition_checkpoint_count", stats.resumedCheckpointCount());
        output.put("inner_profiles_generated", stats.profilesGenerated());
        output.put("inner_labels_retained", stats.labelsRetained());
        output.put("inner_labels_pruned", stats.labelsPruned());
        output.put("inner_search_expansions_skipped", stats.searchExpansionsSkipped());
        output.put("sampled_peak_heap_bytes", Math.max(
                boundary.constructionStatistics().sampledPeakUsedHeapBytes(), stats.sampledPeakUsedHeapBytes()));
        output.put("load_validation", "PartitionedTHopIndex.load validated graph, partition, index and referenced-boundary identities");
        output.put("all_partition_blocks_eagerly_loaded_and_checksum_verified",
                reloaded.loadedPartitionCount() == metadata.partitionCount());
        output.put("built_reloaded_index_metadata_equal",
                built.partitionMetadata().partitionByVertex().equals(reloaded.partitionMetadata().partitionByVertex())
                        && built.indexDomain().equals(reloaded.indexDomain()));
        output.put("fastest_profile_queries_run", false);
        output.put("complete_build_and_reload_elapsed_nanos", System.nanoTime() - totalStarted);
        JSON.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), output);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
