package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Disk-backed two-phase Boundary-THop construction and boundary query API
 * (Li, Wang and Zhou, ICDE 2019, Sections V-A, V-B and V-D).
 *
 * <p>Phase 1 runs exact all-destination functional searches from each boundary
 * vertex on the original complete graph and writes boundary-destination
 * profiles to per-source disk shards. Phase 2 reads those shards in the
 * recorded boundary-hub order and keeps a label only when earlier boundary
 * labels do not dominate the complete profile support. No edge is removed
 * from a phase-1 search and no departure times are sampled.</p>
 */
public final class BoundaryTHopIndex {
    private static final int MAGIC = 0x42544850; // BTHP
    private static final int FORMAT_VERSION = 2;
    private static final String CONSTRUCTION_ID = "Li-Wang-Zhou-ICDE2019-Section-V-B";
    private static final String SEARCH_ID =
            "FifoFastestPathProfileSearch:original-complete-directed-graph:exact";
    private static final String PRUNING_ID =
            "previous-boundary-hubs:weak-pointwise-dominance:complete-support";
    private static final int DEFAULT_PHASE1_PARALLELISM = Math.max(
            1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int MAX_SERIALIZED_COUNT = 100_000_000;

    private final TDGraph graph;
    private final GraphPartitionMetadata partitionMetadata;
    private final Domain indexDomain;
    private final List<Integer> hubOrder;
    private final THopIndex labels;
    private final ConstructionStatistics constructionStatistics;
    private final DeterministicCounters deterministicCounters;

    private BoundaryTHopIndex(
            TDGraph graph,
            GraphPartitionMetadata partitionMetadata,
            Domain indexDomain,
            List<Integer> hubOrder,
            THopIndex labels,
            ConstructionStatistics constructionStatistics,
            DeterministicCounters deterministicCounters) {
        this.graph = graph;
        this.partitionMetadata = partitionMetadata;
        this.indexDomain = indexDomain;
        this.hubOrder = List.copyOf(hubOrder);
        this.labels = labels;
        this.constructionStatistics = constructionStatistics;
        this.deterministicCounters = deterministicCounters;
    }

    /** Builds and writes a Boundary-THop index with sorted boundary hub order. */
    public static BoundaryTHopIndex build(
            TDGraph graph,
            GraphPartitionMetadata partitionMetadata,
            Domain indexDomain,
            Path indexFile) throws IOException {
        Objects.requireNonNull(partitionMetadata, "partitionMetadata");
        return build(graph, partitionMetadata, indexDomain, indexFile,
                partitionMetadata.boundaryVertexIds(), DEFAULT_PHASE1_PARALLELISM);
    }

    /** Builds and writes a Boundary-THop index using an explicit pruning order. */
    public static BoundaryTHopIndex build(
            TDGraph graph,
            GraphPartitionMetadata partitionMetadata,
            Domain indexDomain,
            Path indexFile,
            List<Integer> suppliedHubOrder) throws IOException {
        return build(graph, partitionMetadata, indexDomain, indexFile,
                suppliedHubOrder, DEFAULT_PHASE1_PARALLELISM);
    }

    /**
     * Builds the index. Phase-1 searches may run concurrently; each completed
     * source result is immediately written to its own temporary disk shard.
     */
    public static BoundaryTHopIndex build(
            TDGraph graph,
            GraphPartitionMetadata partitionMetadata,
            Domain indexDomain,
            Path indexFile,
            List<Integer> suppliedHubOrder,
            int phase1Parallelism) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(partitionMetadata, "partitionMetadata");
        Objects.requireNonNull(indexFile, "indexFile");
        partitionMetadata.validateAgainst(graph);
        validateIndexDomain(graph, indexDomain);
        List<Integer> order = validateBoundaryHubOrder(partitionMetadata, suppliedHubOrder);
        if (phase1Parallelism <= 0) {
            throw new IllegalArgumentException("phase1Parallelism must be positive");
        }

        long constructionStarted = System.nanoTime();
        Path absoluteIndexFile = indexFile.toAbsolutePath();
        Path parent = absoluteIndexFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path phase1Directory = Files.createTempDirectory(parent, absoluteIndexFile.getFileName() + ".phase1-");
        try {
            byte[] graphFingerprint = graphFingerprint(graph);
            long sampledPeak = usedHeapBytes();
            long phase1Profiles = 0;
            long generatedPieces = 0;
            long forwardExpansions = 0;
            long forwardStatesGenerated = 0;
            long compositions = 0;
            long envelopes = 0;
            long phase1Bytes = 0;
            long phase1DiskWriteNanos = 0;
            long candidateGenerationNanos = 0;

            long phase1WallStarted = System.nanoTime();
            List<SourceSearchResult> phase1Results = runPhase1(
                    graph, partitionMetadata, indexDomain, phase1Directory, phase1Parallelism);
            long phase1WallNanos = System.nanoTime() - phase1WallStarted;
            for (SourceSearchResult result : phase1Results) {
                phase1Profiles += result.profileCount();
                generatedPieces += result.functionPieces();
                forwardExpansions += result.searchExpansions();
                forwardStatesGenerated += result.searchStatesGenerated();
                compositions += result.compositions();
                envelopes += result.envelopeOperations();
                phase1Bytes += result.bytesWritten();
                phase1DiskWriteNanos += result.diskWriteNanos();
                candidateGenerationNanos += result.searchNanos();
                sampledPeak = Math.max(sampledPeak, result.sampledPeakUsedHeapBytes());
            }

            Map<Integer, Map<Integer, ExactArrivalProfile>> out = emptyBoundaryLabels(graph);
            Map<Integer, Map<Integer, ExactArrivalProfile>> in = emptyBoundaryLabels(graph);
            long retainedProfiles = 0;
            long prunedProfiles = 0;
            long phase1DiskReadNanos = 0;
            long pruningNanos = 0;
            Set<Integer> processed = new LinkedHashSet<>();
            for (int hub : order) {
                ExactArrivalProfile identity = ExactArrivalProfile.identity(indexDomain);
                out.get(hub).put(hub, identity);
                in.get(hub).put(hub, identity);

                long readStarted = System.nanoTime();
                Map<Integer, ExactArrivalProfile> row;
                try (Phase1ShardReader rowReader = new Phase1ShardReader(phase1Directory, hub)) {
                    row = rowReader.readAll();
                }
                phase1DiskReadNanos += System.nanoTime() - readStarted;
                for (int destination : partitionMetadata.boundaryVertexIds()) {
                    if (destination == hub) {
                        continue;
                    }
                    ExactArrivalProfile candidate = row.get(destination);
                    if (candidate == null) {
                        continue;
                    }
                    long pruneStarted = System.nanoTime();
                    ExactArrivalProfile previous = labelEnvelope(
                            out, in, hub, destination, processed);
                    if (previous.dominates(candidate)) {
                        prunedProfiles++;
                    } else {
                        in.get(destination).put(hub, candidate);
                        retainedProfiles++;
                    }
                    pruningNanos += System.nanoTime() - pruneStarted;
                }

                for (int source : partitionMetadata.boundaryVertexIds()) {
                    if (source == hub) {
                        continue;
                    }
                    ExactArrivalProfile candidate;
                    readStarted = System.nanoTime();
                    try (Phase1ShardReader sourceReader = new Phase1ShardReader(phase1Directory, source)) {
                        candidate = sourceReader.read(hub);
                    }
                    phase1DiskReadNanos += System.nanoTime() - readStarted;
                    if (candidate == null) {
                        continue;
                    }
                    long pruneStarted = System.nanoTime();
                    ExactArrivalProfile previous = labelEnvelope(
                            out, in, source, hub, processed);
                    if (previous.dominates(candidate)) {
                        prunedProfiles++;
                    } else {
                        out.get(source).put(hub, candidate);
                        retainedProfiles++;
                    }
                    pruningNanos += System.nanoTime() - pruneStarted;
                }
                processed.add(hub);
                sampledPeak = Math.max(sampledPeak, usedHeapBytes());
            }

            long retainedPieces = countPieces(out) + countPieces(in);
            THopIndex queryLabels = THopIndex.fromExistingLabels(
                    graph, indexDomain, out, in, order);
            long diskWritingNanos = phase1DiskWriteNanos;
            DeterministicCounters counters = new DeterministicCounters(
                    phase1Parallelism,
                    phase1Profiles,
                    retainedProfiles,
                    prunedProfiles,
                    generatedPieces,
                    retainedPieces,
                    forwardExpansions,
                    forwardStatesGenerated,
                    compositions,
                    envelopes,
                    phase1Bytes);
            long writeStarted = System.nanoTime();
            byte[] encodedIndex = encodeIndex(
                    graph,
                    partitionMetadata,
                    indexDomain,
                    graphFingerprint,
                    order,
                    counters,
                    out,
                    in);
            writeAtomically(absoluteIndexFile, encodedIndex);
            diskWritingNanos += System.nanoTime() - writeStarted;
            long actualIndexBytes = Files.size(absoluteIndexFile);

            ConstructionStatistics statistics = new ConstructionStatistics(
                    partitionMetadata.partitionCount(),
                    partitionMetadata.boundaryVertexCount(),
                    partitionMetadata.crossPartitionArcCount(),
                    phase1Results.size(),
                    phase1Profiles,
                    retainedProfiles,
                    prunedProfiles,
                    generatedPieces,
                    retainedPieces,
                    forwardExpansions,
                    0,
                    forwardStatesGenerated,
                    0,
                    compositions,
                    envelopes,
                    candidateGenerationNanos,
                    phase1WallNanos,
                    pruningNanos,
                    phase1DiskReadNanos,
                    diskWritingNanos,
                    System.nanoTime() - constructionStarted,
                    0,
                    phase1Bytes,
                    actualIndexBytes,
                    sampledPeak,
                    phase1Parallelism);
            return new BoundaryTHopIndex(
                    graph, partitionMetadata, indexDomain, order, queryLabels, statistics, counters);
        } finally {
            deleteRecursively(phase1Directory);
        }
    }

    private static List<SourceSearchResult> runPhase1(
            TDGraph graph,
            GraphPartitionMetadata partitionMetadata,
            Domain indexDomain,
            Path phase1Directory,
            int parallelism) throws IOException {
        List<Integer> sources = partitionMetadata.boundaryVertexIds();
        if (sources.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(parallelism, sources.size()));
        try {
            List<Future<SourceSearchResult>> futures = new ArrayList<>();
            for (int source : sources) {
                Callable<SourceSearchResult> task = () -> {
                    long started = System.nanoTime();
                    FifoFastestPathProfileSearch.SearchResult search =
                            new FifoFastestPathProfileSearch(graph).search(source, indexDomain);
                    long searchNanos = System.nanoTime() - started;
                    TreeMap<Integer, ExactArrivalProfile> boundaryProfiles = new TreeMap<>();
                    for (int destination : partitionMetadata.boundaryVertexIds()) {
                        search.exactProfileTo(destination).ifPresent(profile -> boundaryProfiles.put(destination, profile));
                    }
                    long pieces = boundaryProfiles.values().stream()
                            .mapToLong(ExactArrivalProfile::pieceCount).sum();
                    long writeStarted = System.nanoTime();
                    long bytes = writePhase1Shard(phase1Directory, source, boundaryProfiles);
                    long writeNanos = System.nanoTime() - writeStarted;
                    FifoFastestPathProfileSearch.SearchStatistics searchStats = search.statistics();
                    return new SourceSearchResult(
                            source,
                            boundaryProfiles.size(),
                            pieces,
                            searchStats.searchStatesExpanded(),
                            searchStats.searchStatesGenerated(),
                            searchStats.temporalCompositions(),
                            searchStats.envelopeOperations(),
                            searchStats.sampledPeakUsedHeapBytes(),
                            searchNanos,
                            writeNanos,
                            bytes);
                };
                futures.add(executor.submit(task));
            }
            List<SourceSearchResult> result = new ArrayList<>();
            for (Future<SourceSearchResult> future : futures) {
                try {
                    result.add(future.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("phase-1 boundary profile generation was interrupted", interrupted);
                } catch (ExecutionException execution) {
                    Throwable cause = execution.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IOException("phase-1 boundary profile generation failed", cause);
                }
            }
            result.sort(Comparator.comparingInt(SourceSearchResult::source));
            return List.copyOf(result);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Loads a finalized index. Missing, corrupt or graph-mismatched files fail explicitly. */
    public static BoundaryTHopIndex load(TDGraph graph, Path indexFile) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(indexFile, "indexFile");
        long started = System.nanoTime();
        byte[] allBytes = Files.readAllBytes(indexFile);
        byte[] payload = verifyAndExtractPayload(allBytes);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String constructionId = input.readUTF();
            String searchId = input.readUTF();
            String pruningId = input.readUTF();
            if (!CONSTRUCTION_ID.equals(constructionId)
                    || !SEARCH_ID.equals(searchId)
                    || !PRUNING_ID.equals(pruningId)) {
                throw new IOException("unsupported Boundary-THop construction metadata");
            }
            byte[] storedFingerprint = input.readNBytes(32);
            if (storedFingerprint.length != 32
                    || !Arrays.equals(storedFingerprint, graphFingerprint(graph))) {
                throw new IOException("Boundary-THop index does not match the supplied graph");
            }
            Domain indexDomain = readDomain(input);
            validateIndexDomain(graph, indexDomain);
            byte[] encodedPartitionMetadata = readBytes(input);
            Map<Integer, Integer> assignments = readAssignments(encodedPartitionMetadata);
            GraphPartitionMetadata metadata;
            try {
                metadata = GraphPartitionMetadata.fromAssignments(graph, assignments);
            } catch (RuntimeException invalid) {
                throw new IOException("invalid partition metadata in Boundary-THop index", invalid);
            }
            if (!Arrays.equals(encodedPartitionMetadata, encodePartitionMetadata(metadata))) {
                throw new IOException("serialized partition metadata failed integrity validation");
            }
            int orderCount = readCount(input, "boundary hub order");
            List<Integer> order = new ArrayList<>(orderCount);
            for (int index = 0; index < orderCount; index++) {
                order.add(input.readInt());
            }
            try {
                validateBoundaryHubOrder(metadata, order);
            } catch (RuntimeException invalid) {
                throw new IOException("invalid boundary hub order in index", invalid);
            }
            DeterministicCounters counters = readCounters(input);
            validateCounters(counters, metadata, order);
            Map<Integer, Map<Integer, ExactArrivalProfile>> out = readLabels(input, graph, metadata, indexDomain);
            Map<Integer, Map<Integer, ExactArrivalProfile>> in = readLabels(input, graph, metadata, indexDomain);
            if (input.available() != 0) {
                throw new IOException("trailing bytes in Boundary-THop index payload");
            }
            validateLabelMaps(out, in, metadata, indexDomain);
            validateCountersAgainstLabels(counters, out, in, metadata);
            THopIndex queryLabels = THopIndex.fromExistingLabels(graph, indexDomain, out, in, order);
            long loadingNanos = System.nanoTime() - started;
            long retainedPieces = countPieces(out) + countPieces(in);
            ConstructionStatistics statistics = new ConstructionStatistics(
                    metadata.partitionCount(),
                    metadata.boundaryVertexCount(),
                    metadata.crossPartitionArcCount(),
                    metadata.boundaryVertexCount(),
                    counters.profilesGenerated(),
                    counters.profilesRetained(),
                    counters.profilesPruned(),
                    counters.generatedFunctionPieces(),
                    retainedPieces,
                    counters.forwardSearchExpansions(),
                    0,
                    counters.forwardSearchStatesGenerated(),
                    0,
                    counters.temporalCompositions(),
                    counters.envelopeOperations(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    loadingNanos,
                    counters.phase1BytesWritten(),
                    Files.size(indexFile),
                    usedHeapBytes(),
                    counters.phase1Parallelism());
            return new BoundaryTHopIndex(
                    graph, metadata, indexDomain, order, queryLabels, statistics, counters);
        } catch (EOFException malformed) {
            throw new IOException("truncated Boundary-THop index payload", malformed);
        }
    }

    /** Verifies the stored payload checksum and format envelope. */
    public static boolean verifyChecksum(Path indexFile) throws IOException {
        byte[] bytes = Files.readAllBytes(indexFile);
        try {
            verifyAndExtractPayload(bytes);
            return true;
        } catch (IOException invalid) {
            return false;
        }
    }

    public TDGraph graph() {
        return graph;
    }

    public GraphPartitionMetadata partitionMetadata() {
        return partitionMetadata;
    }

    public Domain indexDomain() {
        return indexDomain;
    }

    /** Deterministic order used for the phase-2 boundary hubs. */
    public List<Integer> hubOrder() {
        return hubOrder;
    }

    /** Detailed construction, disk, loading and memory counters. */
    public ConstructionStatistics constructionStatistics() {
        return constructionStatistics;
    }

    /** Writes this finalized boundary index using deterministic versioned serialization. */
    public long write(Path indexFile) throws IOException {
        Objects.requireNonNull(indexFile, "indexFile");
        Path absoluteIndexFile = indexFile.toAbsolutePath();
        if (absoluteIndexFile.getParent() != null) {
            Files.createDirectories(absoluteIndexFile.getParent());
        }
        Map<Integer, Map<Integer, ExactArrivalProfile>> out = emptyBoundaryLabels(graph);
        Map<Integer, Map<Integer, ExactArrivalProfile>> in = emptyBoundaryLabels(graph);
        for (int boundary : partitionMetadata.boundaryVertexIds()) {
            out.get(boundary).putAll(labels.exactOutgoingLabels(boundary));
            in.get(boundary).putAll(labels.exactIncomingLabels(boundary));
        }
        byte[] encoded = encodeIndex(
                graph,
                partitionMetadata,
                indexDomain,
                graphFingerprint(graph),
                hubOrder,
                deterministicCounters,
                out,
                in);
        writeAtomically(absoluteIndexFile, encoded);
        return Files.size(absoluteIndexFile);
    }

    /** Label map for paths from this boundary vertex to each hub. */
    public Map<Integer, ExactTemporalProfile> outgoingLabels(int boundaryVertex) {
        requireBoundary(boundaryVertex);
        return labels.outgoingLabels(boundaryVertex);
    }

    /** Label map for paths from each hub to this boundary vertex. */
    public Map<Integer, ExactTemporalProfile> incomingLabels(int boundaryVertex) {
        requireBoundary(boundaryVertex);
        return labels.incomingLabels(boundaryVertex);
    }

    public Map<Integer, ExactArrivalProfile> exactOutgoingLabels(int boundaryVertex) {
        requireBoundary(boundaryVertex);
        return labels.exactOutgoingLabels(boundaryVertex);
    }

    public Map<Integer, ExactArrivalProfile> exactIncomingLabels(int boundaryVertex) {
        requireBoundary(boundaryVertex);
        return labels.exactIncomingLabels(boundaryVertex);
    }

    /** Direct boundary-only THop query; result values are travel times. */
    public THopIndex.QueryResult query(int sourceBoundary, int destinationBoundary, Domain queryInterval) {
        requireBoundary(sourceBoundary);
        requireBoundary(destinationBoundary);
        return labels.query(sourceBoundary, destinationBoundary, queryInterval);
    }

    ExactTemporalProfile queryArrivalProfile(
            int sourceBoundary, int destinationBoundary, Domain queryInterval) {
        requireBoundary(sourceBoundary);
        requireBoundary(destinationBoundary);
        return labels.queryArrivalProfile(sourceBoundary, destinationBoundary, queryInterval);
    }

    ExactArrivalProfile queryExactArrivalProfile(
            int sourceBoundary, int destinationBoundary, Domain queryInterval) {
        requireBoundary(sourceBoundary);
        requireBoundary(destinationBoundary);
        return labels.queryExactArrivalProfile(sourceBoundary, destinationBoundary, queryInterval);
    }

    int commonHubCount(int sourceBoundary, int destinationBoundary) {
        requireBoundary(sourceBoundary);
        requireBoundary(destinationBoundary);
        return labels.commonHubCount(sourceBoundary, destinationBoundary);
    }

    private void requireBoundary(int vertex) {
        if (!partitionMetadata.boundaryVertexIds().contains(vertex)) {
            throw new IllegalArgumentException("query endpoint is not a boundary vertex: " + vertex);
        }
    }

    private static Map<Integer, Map<Integer, ExactArrivalProfile>> emptyBoundaryLabels(TDGraph graph) {
        Map<Integer, Map<Integer, ExactArrivalProfile>> labels = new TreeMap<>();
        for (int vertex : graph.nodeIds()) {
            labels.put(vertex, new TreeMap<>());
        }
        return labels;
    }

    private static ExactArrivalProfile labelEnvelope(
            Map<Integer, Map<Integer, ExactArrivalProfile>> out,
            Map<Integer, Map<Integer, ExactArrivalProfile>> in,
            int source,
            int destination,
            Set<Integer> eligibleHubs) {
        ExactArrivalProfile result = ExactArrivalProfile.empty();
        for (int hub : eligibleHubs) {
            ExactArrivalProfile sourceToHub = out.get(source).get(hub);
            ExactArrivalProfile hubToDestination = in.get(destination).get(hub);
            if (sourceToHub == null || hubToDestination == null) {
                continue;
            }
            result = result.pointwiseMinimum(sourceToHub.composeAfter(hubToDestination));
        }
        return result;
    }

    private static List<Integer> validateBoundaryHubOrder(
            GraphPartitionMetadata metadata, List<Integer> suppliedOrder) {
        if (suppliedOrder == null
                || suppliedOrder.size() != metadata.boundaryVertexCount()
                || new HashSet<>(suppliedOrder).size() != metadata.boundaryVertexCount()
                || !new HashSet<>(suppliedOrder).equals(Set.copyOf(metadata.boundaryVertexIds()))) {
            throw new IllegalArgumentException("hub order must be a permutation of all boundary vertices");
        }
        return List.copyOf(suppliedOrder);
    }

    private static void validateIndexDomain(TDGraph graph, Domain indexDomain) {
        if (indexDomain == null || indexDomain.isEmpty()) {
            throw new IllegalArgumentException("a nonempty index domain is required");
        }
        Domain commonEdgeDomain = null;
        for (Edge edge : graph.edges()) {
            Domain edgeDomain = edge.travelTimeFunction().domain();
            if (commonEdgeDomain == null) {
                commonEdgeDomain = edgeDomain;
            } else if (!commonEdgeDomain.equals(edgeDomain)) {
                throw new IllegalArgumentException(
                        "Boundary-THop requires a common edge-function support domain");
            }
            if (!edgeDomain.difference(indexDomain).isEmpty()) {
                throw new IllegalArgumentException(
                        "index domain must cover every edge function domain; arc_id=" + edge.arcId());
            }
        }
        // The search constructor validates FIFO and FIFO continuity across support gaps.
        if (!graph.edges().isEmpty()) {
            new FifoFastestPathProfileSearch(graph);
        }
    }

    private static long countPieces(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        return labels.values().stream()
                .flatMap(map -> map.values().stream())
                .mapToLong(ExactArrivalProfile::pieceCount)
                .sum();
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public record ConstructionStatistics(
            int partitionCount,
            int boundaryVertexCount,
            int crossPartitionArcCount,
            long boundaryProfileSearchCalls,
            long profilesGenerated,
            long profilesRetained,
            long profilesPruned,
            long generatedFunctionPieces,
            long retainedFunctionPieces,
            long forwardSearchExpansions,
            long backwardSearchExpansions,
            long forwardSearchStatesGenerated,
            long backwardSearchStatesGenerated,
            long temporalCompositions,
            long envelopeOperations,
            long candidateGenerationNanos,
            long phase1WallNanos,
            long pruningNanos,
            long phase1DiskReadingNanos,
            long diskWritingNanos,
            long constructionWallNanos,
            long indexLoadingNanos,
            long phase1BytesWritten,
            long indexBytesOnDisk,
            long sampledPeakUsedHeapBytes,
            int phase1Parallelism) {
    }

    private record SourceSearchResult(
            int source,
            long profileCount,
            long functionPieces,
            long searchExpansions,
            long searchStatesGenerated,
            long compositions,
            long envelopeOperations,
            long sampledPeakUsedHeapBytes,
            long searchNanos,
            long diskWriteNanos,
            long bytesWritten) {
    }

    private record DeterministicCounters(
            int phase1Parallelism,
            long profilesGenerated,
            long profilesRetained,
            long profilesPruned,
            long generatedFunctionPieces,
            long retainedFunctionPieces,
            long forwardSearchExpansions,
            long forwardSearchStatesGenerated,
            long temporalCompositions,
            long envelopeOperations,
            long phase1BytesWritten) {
    }

    private record ShardRecord(long offset, int length) {
    }

    private static final class Phase1ShardReader implements AutoCloseable {
        private final RandomAccessFile data;
        private final Map<Integer, ShardRecord> records;

        private Phase1ShardReader(Path directory, int source) throws IOException {
            Path dataPath = phase1DataPath(directory, source);
            Path indexPath = phase1IndexPath(directory, source);
            if (!Files.isRegularFile(dataPath) || !Files.isRegularFile(indexPath)) {
                throw new IOException("missing phase-1 profile shard for boundary source " + source);
            }
            this.data = new RandomAccessFile(dataPath.toFile(), "r");
            this.records = new HashMap<>();
            try (DataInputStream input = new DataInputStream(Files.newInputStream(indexPath))) {
                int storedSource = input.readInt();
                if (storedSource != source) {
                    throw new IOException("phase-1 shard source mismatch: " + source);
                }
                int count = readCount(input, "phase-1 profile count");
                for (int index = 0; index < count; index++) {
                    int destination = input.readInt();
                    long offset = input.readLong();
                    int length = input.readInt();
                    if (offset < 0 || length < 0 || records.put(destination, new ShardRecord(offset, length)) != null) {
                        throw new IOException("invalid phase-1 profile directory for source " + source);
                    }
                }
                if (input.available() != 0) {
                    throw new IOException("trailing phase-1 directory data for source " + source);
                }
            } catch (Throwable failure) {
                data.close();
                throw failure;
            }
        }

        private ExactArrivalProfile read(int destination) throws IOException {
            ShardRecord record = records.get(destination);
            if (record == null) {
                return null;
            }
            data.seek(record.offset());
            int storedDestination = data.readInt();
            int storedLength = data.readInt();
            if (storedDestination != destination || storedLength != record.length()) {
                throw new IOException("phase-1 profile record failed its directory check");
            }
            byte[] bytes = new byte[record.length()];
            data.readFully(bytes);
            return decodeProfile(bytes);
        }

        private Map<Integer, ExactArrivalProfile> readAll() throws IOException {
            TreeMap<Integer, ExactArrivalProfile> result = new TreeMap<>();
            for (int destination : new TreeSet<>(records.keySet())) {
                result.put(destination, read(destination));
            }
            return result;
        }

        @Override
        public void close() throws IOException {
            data.close();
        }
    }

    private static long writePhase1Shard(
            Path directory, int source, Map<Integer, ExactArrivalProfile> profiles) throws IOException {
        Path dataPath = phase1DataPath(directory, source);
        Path indexPath = phase1IndexPath(directory, source);
        TreeMap<Integer, ShardRecord> entries = new TreeMap<>();
        try (RandomAccessFile data = new RandomAccessFile(dataPath.toFile(), "rw")) {
            data.setLength(0);
            for (Map.Entry<Integer, ExactArrivalProfile> entry : new TreeMap<>(profiles).entrySet()) {
                byte[] profileBytes = encodeProfile(entry.getValue());
                long offset = data.getFilePointer();
                data.writeInt(entry.getKey());
                data.writeInt(profileBytes.length);
                data.write(profileBytes);
                entries.put(entry.getKey(), new ShardRecord(offset, profileBytes.length));
            }
        }
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(indexPath))) {
            output.writeInt(source);
            output.writeInt(entries.size());
            for (Map.Entry<Integer, ShardRecord> entry : entries.entrySet()) {
                output.writeInt(entry.getKey());
                output.writeLong(entry.getValue().offset());
                output.writeInt(entry.getValue().length());
            }
        }
        return Files.size(dataPath) + Files.size(indexPath);
    }

    private static Path phase1DataPath(Path directory, int source) {
        return directory.resolve("source-" + source + ".profiles");
    }

    private static Path phase1IndexPath(Path directory, int source) {
        return directory.resolve("source-" + source + ".directory");
    }

    private static byte[] encodeIndex(
            TDGraph graph,
            GraphPartitionMetadata metadata,
            Domain indexDomain,
            byte[] graphFingerprint,
            List<Integer> hubOrder,
            DeterministicCounters counters,
            Map<Integer, Map<Integer, ExactArrivalProfile>> out,
            Map<Integer, Map<Integer, ExactArrivalProfile>> in) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeUTF(CONSTRUCTION_ID);
            output.writeUTF(SEARCH_ID);
            output.writeUTF(PRUNING_ID);
            output.write(graphFingerprint);
            writeDomain(output, indexDomain);
            writeBytes(output, encodePartitionMetadata(metadata));
            output.writeInt(hubOrder.size());
            for (int hub : hubOrder) {
                output.writeInt(hub);
            }
            writeCounters(output, counters);
            writeLabels(output, metadata.boundaryVertexIds(), out);
            writeLabels(output, metadata.boundaryVertexIds(), in);
        }
        byte[] payload = payloadBytes.toByteArray();
        byte[] checksum = sha256(payload);
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(fileBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(payload.length);
            output.write(payload);
            output.write(checksum);
        }
        return fileBytes.toByteArray();
    }

    private static void writeCounters(DataOutputStream output, DeterministicCounters counters)
            throws IOException {
        output.writeInt(counters.phase1Parallelism());
        output.writeLong(counters.profilesGenerated());
        output.writeLong(counters.profilesRetained());
        output.writeLong(counters.profilesPruned());
        output.writeLong(counters.generatedFunctionPieces());
        output.writeLong(counters.retainedFunctionPieces());
        output.writeLong(counters.forwardSearchExpansions());
        output.writeLong(counters.forwardSearchStatesGenerated());
        output.writeLong(counters.temporalCompositions());
        output.writeLong(counters.envelopeOperations());
        output.writeLong(counters.phase1BytesWritten());
    }

    private static DeterministicCounters readCounters(DataInputStream input) throws IOException {
        return new DeterministicCounters(
                input.readInt(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong());
    }

    private static void validateCounters(
            DeterministicCounters counters, GraphPartitionMetadata metadata, List<Integer> hubOrder)
            throws IOException {
        if (counters.phase1Parallelism() <= 0
                || counters.profilesGenerated() < metadata.boundaryVertexCount()
                || counters.profilesRetained() < 0
                || counters.profilesPruned() < 0
                || counters.generatedFunctionPieces() < 0
                || counters.retainedFunctionPieces() < 0
                || counters.forwardSearchExpansions() < 0
                || counters.forwardSearchStatesGenerated() < 0
                || counters.temporalCompositions() < 0
                || counters.envelopeOperations() < 0
                || counters.phase1BytesWritten() < 0
                || hubOrder.size() != metadata.boundaryVertexCount()) {
            throw new IOException("invalid deterministic construction counters in Boundary-THop index");
        }
    }

    private static void validateCountersAgainstLabels(
            DeterministicCounters counters,
            Map<Integer, Map<Integer, ExactArrivalProfile>> out,
            Map<Integer, Map<Integer, ExactArrivalProfile>> in,
            GraphPartitionMetadata metadata) throws IOException {
        long directedLabels = out.values().stream().mapToLong(Map::size).sum()
                + in.values().stream().mapToLong(Map::size).sum();
        long nonSelfLabels = directedLabels - 2L * metadata.boundaryVertexCount();
        long decisionsExpected = 2L * (counters.profilesGenerated() - metadata.boundaryVertexCount());
        long pieces = countPieces(out) + countPieces(in);
        if (nonSelfLabels != counters.profilesRetained()
                || counters.profilesRetained() + counters.profilesPruned() != decisionsExpected
                || pieces != counters.retainedFunctionPieces()) {
            throw new IOException("Boundary-THop label maps do not match their construction counters");
        }
    }

    private static void writeLabels(
            DataOutputStream output,
            List<Integer> boundaryVertices,
            Map<Integer, Map<Integer, ExactArrivalProfile>> labels) throws IOException {
        output.writeInt(boundaryVertices.size());
        for (int vertex : boundaryVertices) {
            output.writeInt(vertex);
            Map<Integer, ExactArrivalProfile> byHub = new TreeMap<>(labels.get(vertex));
            output.writeInt(byHub.size());
            for (Map.Entry<Integer, ExactArrivalProfile> entry : byHub.entrySet()) {
                output.writeInt(entry.getKey());
                writeProfile(output, entry.getValue());
            }
        }
    }

    private static Map<Integer, Map<Integer, ExactArrivalProfile>> readLabels(
            DataInputStream input,
            TDGraph graph,
            GraphPartitionMetadata metadata,
            Domain indexDomain) throws IOException {
        Map<Integer, Map<Integer, ExactArrivalProfile>> labels = emptyBoundaryLabels(graph);
        int vertexCount = readCount(input, "label vertex count");
        if (vertexCount != metadata.boundaryVertexCount()) {
            throw new IOException("label map does not cover the boundary vertex set");
        }
        List<Integer> expectedVertices = metadata.boundaryVertexIds();
        for (int vertexIndex = 0; vertexIndex < vertexCount; vertexIndex++) {
            int vertex = input.readInt();
            if (vertex != expectedVertices.get(vertexIndex)) {
                throw new IOException("boundary label vertices are not serialized deterministically");
            }
            int labelCount = readCount(input, "vertex label count");
            Map<Integer, ExactArrivalProfile> byHub = labels.get(vertex);
            for (int labelIndex = 0; labelIndex < labelCount; labelIndex++) {
                int hub = input.readInt();
                ExactArrivalProfile profile = readProfile(input);
                if (byHub.put(hub, profile) != null) {
                    throw new IOException("duplicate hub label for boundary vertex " + vertex);
                }
                if (!profile.domain().difference(ExactTemporalDomain.fromLegacy(indexDomain)).isEmpty()) {
                    throw new IOException("label profile support lies outside the index domain");
                }
            }
        }
        return labels;
    }

    private static void validateLabelMaps(
            Map<Integer, Map<Integer, ExactArrivalProfile>> out,
            Map<Integer, Map<Integer, ExactArrivalProfile>> in,
            GraphPartitionMetadata metadata,
            Domain indexDomain) throws IOException {
        Set<Integer> boundary = Set.copyOf(metadata.boundaryVertexIds());
        for (int vertex : metadata.boundaryVertexIds()) {
            for (Map.Entry<Integer, ExactArrivalProfile> entry : out.get(vertex).entrySet()) {
                if (!boundary.contains(entry.getKey()) || entry.getValue().isEmpty()
                        || !entry.getValue().domain().difference(ExactTemporalDomain.fromLegacy(indexDomain)).isEmpty()) {
                    throw new IOException("invalid outgoing boundary label at vertex " + vertex);
                }
            }
            for (Map.Entry<Integer, ExactArrivalProfile> entry : in.get(vertex).entrySet()) {
                if (!boundary.contains(entry.getKey()) || entry.getValue().isEmpty()
                        || !entry.getValue().domain().difference(ExactTemporalDomain.fromLegacy(indexDomain)).isEmpty()) {
                    throw new IOException("invalid incoming boundary label at vertex " + vertex);
                }
            }
            ExactArrivalProfile selfOut = out.get(vertex).get(vertex);
            ExactArrivalProfile selfIn = in.get(vertex).get(vertex);
            if (selfOut == null || selfIn == null
                    || !selfOut.domain().equals(ExactTemporalDomain.fromLegacy(indexDomain))
                    || !selfIn.domain().equals(ExactTemporalDomain.fromLegacy(indexDomain))
                    || !selfOut.sameFunction(ExactArrivalProfile.identity(indexDomain))
                    || !selfIn.sameFunction(ExactArrivalProfile.identity(indexDomain))) {
                throw new IOException("boundary self labels are missing or invalid at vertex " + vertex);
            }
        }
        for (int vertex : out.keySet()) {
            if (!boundary.contains(vertex) && (!out.get(vertex).isEmpty() || !in.get(vertex).isEmpty())) {
                throw new IOException("non-boundary vertex has a stored Boundary-THop label");
            }
        }
    }

    private static byte[] encodePartitionMetadata(GraphPartitionMetadata metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            Map<Integer, Integer> assignments = new TreeMap<>(metadata.partitionByVertex());
            output.writeInt(assignments.size());
            for (Map.Entry<Integer, Integer> assignment : assignments.entrySet()) {
                output.writeInt(assignment.getKey());
                output.writeInt(assignment.getValue());
            }
            writeIntListMap(output, metadata.verticesByPartition());
            writeIntListMap(output, metadata.internalArcIdsByPartition());
            writeIntList(output, metadata.internalDirectedArcIds());
            writeIntList(output, metadata.crossPartitionDirectedArcIds());
            writeIntList(output, metadata.boundaryVertexIds());
            writeIntListMap(output, metadata.incomingBoundaryArcIds());
            writeIntListMap(output, metadata.outgoingBoundaryArcIds());
            writeIntListMap(output, metadata.partitionAdjacency());
            output.writeInt(metadata.crossArcsByPartitionPair().size());
            for (Map.Entry<GraphPartitionMetadata.PartitionPair, List<Integer>> entry
                    : metadata.crossArcsByPartitionPair().entrySet()) {
                output.writeInt(entry.getKey().sourcePartition());
                output.writeInt(entry.getKey().targetPartition());
                writeIntList(output, entry.getValue());
            }
        }
        return bytes.toByteArray();
    }

    private static Map<Integer, Integer> readAssignments(byte[] metadataBytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(metadataBytes))) {
            int count = readCount(input, "partition assignment count");
            TreeMap<Integer, Integer> assignments = new TreeMap<>();
            for (int index = 0; index < count; index++) {
                int vertex = input.readInt();
                int partition = input.readInt();
                if (partition < 0 || assignments.put(vertex, partition) != null) {
                    throw new IOException("invalid partition assignment data");
                }
            }
            return assignments;
        }
    }

    private static void writeIntListMap(DataOutputStream output, Map<Integer, List<Integer>> values)
            throws IOException {
        TreeMap<Integer, List<Integer>> ordered = new TreeMap<>(values);
        output.writeInt(ordered.size());
        for (Map.Entry<Integer, List<Integer>> entry : ordered.entrySet()) {
            output.writeInt(entry.getKey());
            writeIntList(output, entry.getValue());
        }
    }

    private static void writeIntList(DataOutputStream output, List<Integer> values) throws IOException {
        output.writeInt(values.size());
        for (int value : values) {
            output.writeInt(value);
        }
    }

    private static void writeProfile(DataOutputStream output, ExactArrivalProfile profile, boolean allowEmpty)
            throws IOException {
        if (!allowEmpty && profile.isEmpty()) {
            throw new IOException("empty temporal functions cannot be serialized as labels");
        }
        profile.writeTo(output);
    }

    private static void writeProfile(DataOutputStream output, ExactArrivalProfile profile) throws IOException {
        writeProfile(output, profile, false);
    }

    private static ExactArrivalProfile readProfile(DataInputStream input) throws IOException {
        ExactArrivalProfile profile = readProfileAllowEmpty(input);
        if (profile.isEmpty()) {
            throw new IOException("empty temporal profile found in boundary labels");
        }
        return profile;
    }

    private static byte[] encodeProfile(ExactArrivalProfile profile) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeProfile(output, profile, true);
        }
        return bytes.toByteArray();
    }

    private static ExactArrivalProfile decodeProfile(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            ExactArrivalProfile result = readProfileAllowEmpty(input);
            if (input.available() != 0) {
                throw new IOException("trailing bytes in phase-1 profile");
            }
            return result;
        }
    }

    private static ExactArrivalProfile readProfileAllowEmpty(DataInputStream input) throws IOException {
        return ExactArrivalProfile.readFrom(input);
    }

    private static void writeDomain(DataOutputStream output, Domain domain) throws IOException {
        output.writeInt(domain.intervals().size());
        for (Domain.Interval interval : domain.intervals()) {
            output.writeDouble(interval.start());
            output.writeDouble(interval.end());
            output.writeBoolean(interval.startInclusive());
            output.writeBoolean(interval.endInclusive());
        }
    }

    private static Domain readDomain(DataInputStream input) throws IOException {
        int intervalCount = readCount(input, "temporal support interval count");
        Domain.Interval[] intervals = new Domain.Interval[intervalCount];
        try {
            for (int index = 0; index < intervalCount; index++) {
                intervals[index] = new Domain.Interval(
                        input.readDouble(), input.readDouble(), input.readBoolean(), input.readBoolean());
            }
            return Domain.of(intervals);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid temporal support domain", invalid);
        }
    }

    private static byte[] graphFingerprint(TDGraph graph) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            List<Integer> nodeIds = graph.nodeIds();
            output.writeInt(nodeIds.size());
            for (int nodeId : nodeIds) {
                output.writeInt(nodeId);
            }
            output.writeInt(graph.edgeCount());
            for (Edge edge : graph.edges()) {
                output.writeInt(edge.arcId());
                output.writeInt(edge.source());
                output.writeInt(edge.target());
                writeDomain(output, edge.travelTimeFunction().domain());
                output.writeInt(edge.travelTimeFunction().breakpoints().size());
                for (PiecewiseLinearFn.Breakpoint point : edge.travelTimeFunction().breakpoints()) {
                    output.writeDouble(point.minute());
                    output.writeDouble(point.value());
                }
            }
        }
        return sha256(bytes.toByteArray());
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = readCount(input, "serialized metadata byte count");
        byte[] result = input.readNBytes(length);
        if (result.length != length) {
            throw new EOFException("truncated serialized metadata");
        }
        return result;
    }

    private static int readCount(DataInputStream input, String description) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_SERIALIZED_COUNT) {
            throw new IOException("invalid " + description + ": " + count);
        }
        return count;
    }

    private static byte[] verifyAndExtractPayload(byte[] fileBytes) throws IOException {
        if (fileBytes.length < 4 + 4 + 8 + 32) {
            throw new IOException("Boundary-THop index is truncated");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(fileBytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("file is not a Boundary-THop index");
            }
            int version = input.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported Boundary-THop index version: " + version);
            }
            long payloadLength = input.readLong();
            if (payloadLength < 0 || payloadLength != fileBytes.length - 4L - 4L - 8L - 32L) {
                throw new IOException("Boundary-THop index length does not match its header");
            }
            byte[] payload = input.readNBytes((int) payloadLength);
            byte[] storedChecksum = input.readNBytes(32);
            if (payload.length != payloadLength || storedChecksum.length != 32
                    || !MessageDigest.isEqual(storedChecksum, sha256(payload))) {
                throw new IOException("Boundary-THop index checksum verification failed");
            }
            return payload;
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
