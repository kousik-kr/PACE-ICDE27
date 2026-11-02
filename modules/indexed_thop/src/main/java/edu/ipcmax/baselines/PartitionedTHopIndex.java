package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TemporalProfileWork;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Partitioned THop index for arbitrary-vertex fastest-path queries.
 *
 * <p>For each partition, construction searches a temporary directed graph
 * containing its original internal arcs and exact all-graph boundary-to-
 * boundary profiles as shortcut arcs. Thus leaving and re-entering a
 * partition is represented by a valid original-graph route, rather than by
 * an induced-subgraph approximation. Labels are admitted in deterministic
 * vertex order after exact functional searches; only a full-support label
 * envelope can remove a candidate.</p>
 *
 * <p>Queries within one partition use its inner labels. Cross-partition
 * queries compose source-inner, boundary, and destination-inner profiles for
 * every boundary pair. The ordinary {@link #query(int, int, Domain)} method
 * is exhaustive. {@link #queryWithSafePruning(int, int, Domain)} optionally
 * skips a complete combination only when the current result dominates that
 * combination over its entire support.</p>
 */
public final class PartitionedTHopIndex {
    private static final int MAGIC = 0x50544850; // PTHP
    private static final int FORMAT_VERSION = 4;
    private static final String CONSTRUCTION_ID = "Li-Wang-Zhou-ICDE2019-Section-V-C:incremental-pruning-v3-checkpoint-resume";
    private static final String QUERY_ID = "Section-V-E:exhaustive-boundary-combinations";
    private static final int CHECKPOINT_MAGIC = 0x50544843; // PTHC
    private static final int CHECKPOINT_FORMAT_VERSION = 1;
    private static final int MAX_COUNT = 100_000_000;
    private static final int DIGEST_LENGTH = 32;

    private final TDGraph graph;
    private final BoundaryTHopIndex boundaryIndex;
    private final GraphPartitionMetadata metadata;
    private final Domain indexDomain;
    private final Path indexFile;
    private final Path boundaryIndexFile;
    private final byte[] boundaryFileDigest;
    private final Map<Integer, THopIndex> loadedPartitions = new ConcurrentHashMap<>();
    private final Map<Integer, BlockPointer> partitionBlocks;
    private final Map<Integer, PartitionConstructionStatistics> partitionStatistics;
    private volatile ConstructionStatistics constructionStatistics;
    private final boolean builtInMemory;

    private PartitionedTHopIndex(
            TDGraph graph,
            BoundaryTHopIndex boundaryIndex,
            Path boundaryIndexFile,
            byte[] boundaryFileDigest,
            Path indexFile,
            Map<Integer, THopIndex> loadedPartitions,
            Map<Integer, BlockPointer> partitionBlocks,
            Map<Integer, PartitionConstructionStatistics> partitionStatistics,
            ConstructionStatistics constructionStatistics,
            boolean builtInMemory) {
        this.graph = graph;
        this.boundaryIndex = boundaryIndex;
        this.metadata = boundaryIndex.partitionMetadata();
        this.indexDomain = boundaryIndex.indexDomain();
        this.boundaryIndexFile = boundaryIndexFile.toAbsolutePath().normalize();
        this.boundaryFileDigest = boundaryFileDigest.clone();
        this.indexFile = indexFile.toAbsolutePath().normalize();
        this.loadedPartitions.putAll(loadedPartitions);
        this.partitionBlocks = Map.copyOf(partitionBlocks);
        this.partitionStatistics = Map.copyOf(partitionStatistics);
        this.constructionStatistics = constructionStatistics;
        this.builtInMemory = builtInMemory;
    }

    /** Builds all inner indexes from a previously persisted Boundary-THop index. */
    public static PartitionedTHopIndex build(
            TDGraph graph, Path boundaryIndexFile, Path indexFile) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(boundaryIndexFile, "boundaryIndexFile");
        Objects.requireNonNull(indexFile, "indexFile");
        if (!BoundaryTHopIndex.verifyChecksum(boundaryIndexFile)) {
            throw new IOException("Boundary-THop index checksum failed");
        }
        BoundaryTHopIndex boundary = BoundaryTHopIndex.load(graph, boundaryIndexFile);
        byte[] boundaryDigest = sha256(Files.readAllBytes(boundaryIndexFile));
        GraphPartitionMetadata metadata = boundary.partitionMetadata();
        Domain domain = boundary.indexDomain();

        long started = System.nanoTime();
        long sampledPeak = usedHeapBytes();
        TreeMap<Integer, THopIndex> builtPartitions = new TreeMap<>();
        TreeMap<Integer, PartitionConstructionStatistics> details = new TreeMap<>();
        Path checkpointDirectory = indexFile.toAbsolutePath().normalize()
                .resolveSibling(indexFile.getFileName() + ".checkpoints");
        Files.createDirectories(checkpointDirectory);
        int resumedCheckpointCount = 0;
        for (int partitionId : metadata.verticesByPartition().keySet()) {
            Path checkpoint = partitionCheckpointPath(checkpointDirectory, partitionId);
            if (!Files.exists(checkpoint)) continue;
            LoadedPartitionCheckpoint loaded = readPartitionCheckpoint(
                    graph, metadata, domain, boundaryDigest, partitionId, checkpoint);
            builtPartitions.put(partitionId, loaded.index());
            details.put(partitionId, loaded.statistics());
            resumedCheckpointCount++;
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=partition_checkpoint_loaded partition=" + partitionId
                        + " checkpoint=" + checkpoint
                        + " labels=" + loaded.index().statistics().labelCount()
                        + " construction_ns=" + loaded.statistics().constructionNanos());
            }
        }
        long shortcutArcs = 0;
        long searches = 0;
        long generated = 0;
        long retained = 0;
        long pruned = 0;
        long skippedExpansions = 0;
        long forwardExpansions = 0;
        long backwardExpansions = 0;
        long pieces = 0;
        long compositions = 0;
        long envelopes = 0;

        for (PartitionConstructionStatistics stats : details.values()) {
            shortcutArcs += stats.shortcutArcCount();
            searches += stats.searchCalls();
            generated += stats.profilesGenerated();
            retained += stats.labelsRetained();
            pruned += stats.labelsPruned();
            skippedExpansions += stats.searchExpansionsSkipped();
            forwardExpansions += stats.forwardSearchExpansions();
            backwardExpansions += stats.backwardSearchExpansions();
            pieces += stats.temporalPieces();
            compositions += stats.temporalCompositions();
            envelopes += stats.envelopeOperations();
            sampledPeak = Math.max(sampledPeak, stats.sampledPeakUsedHeapBytes());
        }

        if (ThopBuildProgress.ENABLED) {
            ThopBuildProgress.log("stage=partitioned_build_start partitions=" + metadata.partitionCount()
                    + " graph_vertices=" + graph.nodeCount()
                    + " graph_arcs=" + graph.edgeCount()
                    + " boundary_vertices=" + metadata.boundaryVertexCount()
                    + " domain=" + domain.intervals());
        }

        for (int partitionId : metadata.verticesByPartition().keySet()) {
            if (builtPartitions.containsKey(partitionId)) continue;
            if (ThopBuildControl.ENABLED) ThopBuildControl.checkStop("inner:partition=" + partitionId + ":completed=" + builtPartitions.size());
            long partitionStarted = System.nanoTime();
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=partition_start partition=" + partitionId
                        + " completed_partitions=" + builtPartitions.size()
                        + " vertices=" + metadata.verticesByPartition().get(partitionId).size()
                        + " internal_arcs=" + metadata.internalArcIdsByPartition().get(partitionId).size()
                        + " boundary_vertices=" + boundaryVerticesInPartition(metadata, partitionId).size());
            }
            AugmentedPartition augmented = buildAugmentedPartition(
                    graph, metadata, boundary, partitionId, domain);
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=partition_augmented partition=" + partitionId
                        + " augmented_vertices=" + augmented.graph().nodeCount()
                        + " augmented_arcs=" + augmented.graph().edgeCount()
                        + " shortcut_arcs=" + augmented.counts().shortcutArcs()
                        + " exact_shortcut_profiles=" + augmented.exactArcArrivals().size());
            }
            InnerIndexBuild innerBuild = buildInnerLabels(
                    graph, augmented.graph(), metadata.verticesByPartition().get(partitionId), domain,
                    augmented.exactArcArrivals());
            THopIndex inner = innerBuild.index();
            PartitionBuildCounts counts = augmented.counts();
            long partitionNanos = System.nanoTime() - partitionStarted;
            PartitionConstructionStatistics stats = new PartitionConstructionStatistics(
                    partitionId,
                    metadata.verticesByPartition().get(partitionId).size(),
                    metadata.internalArcIdsByPartition().get(partitionId).size(),
                    boundaryVerticesInPartition(metadata, partitionId).size(),
                    counts.shortcutArcs(),
                    innerBuild.searchCalls(),
                    innerBuild.profilesGenerated(),
                    innerBuild.labelsRetained(),
                    innerBuild.labelsPruned(),
                    innerBuild.searchExpansionsSkipped(),
                    innerBuild.forwardSearchExpansions(),
                    innerBuild.backwardSearchExpansions(),
                    innerBuild.temporalPieces(),
                    innerBuild.temporalCompositions(),
                    innerBuild.envelopeOperations(),
                    partitionNanos,
                    Math.max(usedHeapBytes(), inner.statistics().sampledPeakUsedHeapBytes()));
            Path checkpoint = partitionCheckpointPath(checkpointDirectory, partitionId);
            writePartitionCheckpoint(graph, metadata, domain, boundaryDigest,
                    partitionId, inner, stats, checkpoint);
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=partition_checkpoint_written partition=" + partitionId
                        + " bytes=" + Files.size(checkpoint)
                        + " checkpoint=" + checkpoint);
            }
            builtPartitions.put(partitionId, inner);
            details.put(partitionId, stats);
            if (ThopBuildProgress.ENABLED) {
                ThopBuildProgress.log("stage=partition_complete partition=" + partitionId
                        + " completed_partitions=" + builtPartitions.size()
                        + " inner_labels=" + inner.statistics().labelCount()
                        + " inner_profile_segments=" + inner.statistics().temporalFunctionPieces()
                        + " shortcut_arcs=" + counts.shortcutArcs()
                        + " searches=" + stats.searchCalls()
                        + " generated_profiles=" + stats.profilesGenerated()
                        + " wall_ns=" + partitionNanos);
            }
            shortcutArcs += counts.shortcutArcs();
            searches += stats.searchCalls();
            generated += stats.profilesGenerated();
            retained += stats.labelsRetained();
            pruned += stats.labelsPruned();
            skippedExpansions += stats.searchExpansionsSkipped();
            forwardExpansions += stats.forwardSearchExpansions();
            backwardExpansions += stats.backwardSearchExpansions();
            pieces += stats.temporalPieces();
            compositions += stats.temporalCompositions();
            envelopes += stats.envelopeOperations();
            sampledPeak = Math.max(sampledPeak, stats.sampledPeakUsedHeapBytes());
        }

        ConstructionStatistics initialStats = new ConstructionStatistics(
                metadata.partitionCount(),
                graph.nodeCount(),
                metadata.internalDirectedArcIds().size(),
                metadata.boundaryVertexCount(),
                shortcutArcs,
                searches,
                generated,
                retained,
                pruned,
                skippedExpansions,
                forwardExpansions,
                backwardExpansions,
                pieces,
                compositions,
                envelopes,
                System.nanoTime() - started,
                boundary.constructionStatistics().indexBytesOnDisk(),
                0,
                0,
                sampledPeak,
                resumedCheckpointCount,
                details);
        Path absoluteIndex = indexFile.toAbsolutePath().normalize();
        if (ThopBuildProgress.ENABLED) {
            ThopBuildProgress.log("stage=partitioned_write_start completed_partitions=" + builtPartitions.size()
                    + " labels=" + retained
                    + " generated_profiles=" + generated
                    + " shortcut_arcs=" + shortcutArcs);
        }
        TreeMap<Integer, BlockPointer> blockPointers = writeIndexFile(
                graph, metadata, domain, boundaryIndexFile, boundaryDigest,
                absoluteIndex, builtPartitions, initialStats);
        ConstructionStatistics finalStats = copyWithBytes(
                initialStats, Files.size(absoluteIndex), 0);
        if (ThopBuildProgress.ENABLED) {
            ThopBuildProgress.log("stage=partitioned_build_complete index_bytes=" + Files.size(absoluteIndex)
                    + " construction_wall_ns=" + finalStats.constructionNanos());
        }
        return new PartitionedTHopIndex(
                graph, boundary, boundaryIndexFile, boundaryDigest, absoluteIndex,
                builtPartitions, blockPointers, details, finalStats, true);
    }

    /** Loads metadata eagerly and decodes each partition's labels only on first use. */
    public static PartitionedTHopIndex load(
            TDGraph graph, Path boundaryIndexFile, Path indexFile) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(boundaryIndexFile, "boundaryIndexFile");
        Objects.requireNonNull(indexFile, "indexFile");
        long started = System.nanoTime();
        if (!BoundaryTHopIndex.verifyChecksum(boundaryIndexFile)) {
            throw new IOException("Boundary-THop index checksum failed");
        }
        BoundaryTHopIndex boundary = BoundaryTHopIndex.load(graph, boundaryIndexFile);
        byte[] boundaryDigest = sha256(Files.readAllBytes(boundaryIndexFile));
        byte[] fileBytes = Files.readAllBytes(indexFile);
        if (fileBytes.length < DIGEST_LENGTH) {
            throw new IOException("truncated Partitioned-THop index");
        }
        int payloadLength = fileBytes.length - DIGEST_LENGTH;
        byte[] payload = Arrays.copyOf(fileBytes, payloadLength);
        byte[] suppliedDigest = Arrays.copyOfRange(fileBytes, payloadLength, fileBytes.length);
        if (!Arrays.equals(sha256(payload), suppliedDigest)) {
            throw new IOException("Partitioned-THop index checksum failed");
        }

        TreeMap<Integer, BlockPointer> pointers = new TreeMap<>();
        TreeMap<Integer, PartitionConstructionStatistics> details = new TreeMap<>();
        ConstructionStatistics storedStats;
        Domain domain;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("unsupported Partitioned-THop index format");
            }
            if (!CONSTRUCTION_ID.equals(input.readUTF()) || !QUERY_ID.equals(input.readUTF())) {
                throw new IOException("unsupported Partitioned-THop construction metadata");
            }
            byte[] graphDigest = input.readNBytes(DIGEST_LENGTH);
            if (graphDigest.length != DIGEST_LENGTH
                    || !Arrays.equals(graphDigest, graphFingerprint(graph))) {
                throw new IOException("Partitioned-THop index does not match the supplied graph");
            }
            byte[] storedBoundaryDigest = input.readNBytes(DIGEST_LENGTH);
            if (storedBoundaryDigest.length != DIGEST_LENGTH
                    || !Arrays.equals(storedBoundaryDigest, boundaryDigest)) {
                throw new IOException("Partitioned-THop index references a different Boundary-THop file");
            }
            input.readUTF(); // Recorded source path; the supplied path is checked by content identity.
            domain = readDomain(input);
            if (!domain.equals(boundary.indexDomain())) {
                throw new IOException("Partitioned-THop temporal domain differs from its boundary index");
            }
            Map<Integer, Integer> assignments = readAssignments(input);
            GraphPartitionMetadata storedMetadata;
            try {
                storedMetadata = GraphPartitionMetadata.fromAssignments(graph, assignments);
                if (!storedMetadata.partitionByVertex().equals(boundary.partitionMetadata().partitionByVertex())) {
                    throw new IllegalArgumentException("partition assignments differ from boundary index");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("invalid partition metadata in Partitioned-THop index", invalid);
            }
            storedStats = readConstructionStatistics(input, Files.size(indexFile), started);
            int partitionCount = readCount(input, "partition block count");
            if (partitionCount != storedMetadata.partitionCount()) {
                throw new IOException("partition block count does not match metadata");
            }
            for (int i = 0; i < partitionCount; i++) {
                int partitionId = input.readInt();
                long blockLength = input.readLong();
                if (blockLength < 0 || blockLength > input.available() - DIGEST_LENGTH) {
                    throw new IOException("invalid partition label block length");
                }
                byte[] blockDigest = input.readNBytes(DIGEST_LENGTH);
                long blockOffset = payload.length - input.available();
                input.skipNBytes(blockLength);
                if (pointers.put(partitionId, new BlockPointer(blockOffset, blockLength, blockDigest)) != null) {
                    throw new IOException("duplicate partition label block");
                }
                PartitionConstructionStatistics detail = storedStats.partitions().get(partitionId);
                if (detail == null) {
                    throw new IOException("missing per-partition construction statistics");
                }
                details.put(partitionId, detail);
            }
            if (input.available() != 0
                    || !pointers.keySet().equals(storedMetadata.verticesByPartition().keySet())) {
                throw new IOException("partition label directory is incomplete or has trailing bytes");
            }
        } catch (EOFException truncated) {
            throw new IOException("truncated Partitioned-THop index payload", truncated);
        }

        ConstructionStatistics loadStats = new ConstructionStatistics(
                storedStats.partitionCount(),
                storedStats.graphVertexCount(),
                storedStats.internalArcCount(),
                storedStats.boundaryVertexCount(),
                storedStats.boundaryShortcutArcCount(),
                storedStats.searchCalls(),
                storedStats.profilesGenerated(),
                storedStats.labelsRetained(),
                storedStats.labelsPruned(),
                storedStats.searchExpansionsSkipped(),
                storedStats.forwardSearchExpansions(),
                storedStats.backwardSearchExpansions(),
                storedStats.temporalPieces(),
                storedStats.temporalCompositions(),
                storedStats.envelopeOperations(),
                storedStats.constructionNanos(),
                boundary.constructionStatistics().indexBytesOnDisk(),
                Files.size(indexFile),
                System.nanoTime() - started,
                storedStats.sampledPeakUsedHeapBytes(),
                storedStats.resumedCheckpointCount(),
                details);
        return new PartitionedTHopIndex(
                graph, boundary, boundaryIndexFile, boundaryDigest, indexFile,
                Map.of(), pointers, details, loadStats, false);
    }

    public TDGraph graph() {
        return graph;
    }

    public GraphPartitionMetadata partitionMetadata() {
        return metadata;
    }

    public Domain indexDomain() {
        return indexDomain;
    }

    public BoundaryTHopIndex boundaryIndex() {
        return boundaryIndex;
    }

    public ConstructionStatistics constructionStatistics() {
        return constructionStatistics;
    }

    public int loadedPartitionCount() {
        return loadedPartitions.size();
    }

    public Map<Integer, PartitionConstructionStatistics> partitionConstructionStatistics() {
        return partitionStatistics;
    }

    public THopIndex innerIndexForPartition(int partitionId) throws IOException {
        if (!metadata.verticesByPartition().containsKey(partitionId)) {
            throw new IllegalArgumentException("unknown partition ID: " + partitionId);
        }
        return requirePartition(partitionId).index();
    }

    /** Exact same-partition or exhaustive cross-partition query. */
    public QueryResult query(int source, int destination, Domain departureDomain) throws IOException {
        return queryInternal(source, destination, departureDomain, false);
    }

    /** Optional exact skipping: a full combination is skipped only if wholly dominated. */
    public QueryResult queryWithSafePruning(
            int source, int destination, Domain departureDomain) throws IOException {
        return queryInternal(source, destination, departureDomain, true);
    }

    /** Copies or deterministically writes the finalized index. */
    public long write(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (!Arrays.equals(boundaryFileDigest, sha256(Files.readAllBytes(boundaryIndexFile)))) {
            throw new IOException("referenced Boundary-THop file changed after construction");
        }
        if (!builtInMemory) {
            byte[] existing = Files.readAllBytes(indexFile);
            if (existing.length < DIGEST_LENGTH
                    || !Arrays.equals(sha256(Arrays.copyOf(existing, existing.length - DIGEST_LENGTH)),
                            Arrays.copyOfRange(existing, existing.length - DIGEST_LENGTH, existing.length))) {
                throw new IOException("source Partitioned-THop index checksum failed");
            }
            writeAtomically(target, existing);
            return Files.size(target);
        }
        TreeMap<Integer, THopIndex> indexes = new TreeMap<>();
        for (int partitionId : metadata.verticesByPartition().keySet()) {
            indexes.put(partitionId, requirePartition(partitionId).index());
        }
        writeIndexFile(graph, metadata, indexDomain, boundaryIndexFile, boundaryFileDigest,
                target.toAbsolutePath().normalize(), indexes, constructionStatistics);
        return Files.size(target);
    }

    private QueryResult queryInternal(
            int source, int destination, Domain departureDomain, boolean safePruning) throws IOException {
        graph.node(source);
        graph.node(destination);
        if (departureDomain == null || departureDomain.isEmpty()) {
            throw new IllegalArgumentException("a nonempty departure domain is required");
        }
        if (!departureDomain.difference(indexDomain).isEmpty()) {
            throw new IllegalArgumentException("query interval lies outside the index temporal domain");
        }
        long started = System.nanoTime();
        int sourcePartition = metadata.partitionByVertex().get(source);
        int destinationPartition = metadata.partitionByVertex().get(destination);
        if (source == destination) {
            ExactArrivalProfile identity = ExactArrivalProfile.identity(departureDomain).asTravelTime();
            return new QueryResult(
                    source,
                    destination,
                    departureDomain,
                    identity.roundedLegacyProjection(),
                    identity,
                    new QueryStatistics(
                            "same-partition", sourcePartition, destinationPartition,
                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                            System.nanoTime() - started, identity.pieceCount()));
        }
        if (sourcePartition == destinationPartition) {
            PartitionUse use = requirePartition(sourcePartition);
            THopIndex.QueryResult inner = use.index().query(source, destination, departureDomain);
            return new QueryResult(
                    source,
                    destination,
                    departureDomain,
                    inner.travelTimeProfile(),
                    inner.exactTravelTimeProfile(),
                    new QueryStatistics(
                            "same-partition",
                            sourcePartition,
                            destinationPartition,
                            inner.statistics().commonHubCount(),
                            0,
                            0,
                            0,
                            0,
                            inner.statistics().temporalCompositions(),
                            inner.statistics().envelopeOperations(),
                            0,
                            use.bytesRead(),
                            use.loadNanos(),
                            System.nanoTime() - started,
                            inner.statistics().resultFunctionPieces()));
        }

        List<Integer> sourceBoundaries = boundaryVerticesInPartition(metadata, sourcePartition);
        List<Integer> destinationBoundaries = boundaryVerticesInPartition(metadata, destinationPartition);
        PartitionUse sourceUse = requirePartition(sourcePartition);
        THopIndex sourceInner = sourceUse.index();
        PartitionUse destinationUse = requirePartition(destinationPartition);
        THopIndex destinationInner = destinationUse.index();
        Map<Integer, ExactArrivalProfile> sourcePrefixes = new TreeMap<>();
        Map<Integer, ExactArrivalProfile> destinationSuffixes = new TreeMap<>();
        long sourceWork = 0;
        long destinationWork = 0;
        long[] prefixWork = {0, 0};
        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install((name, amount) -> {
            if (name.equals("thop_temporal_compositions")) {
                prefixWork[0] += amount;
            } else if (name.equals("thop_envelope_operations")) {
                prefixWork[1] += amount;
            }
        })) {
            for (int boundary : sourceBoundaries) {
                sourcePrefixes.put(boundary,
                        sourceInner.queryExactArrivalProfile(source, boundary, departureDomain));
                sourceWork += sourceInner.commonHubCount(source, boundary);
            }
            for (int boundary : destinationBoundaries) {
                destinationSuffixes.put(boundary,
                        destinationInner.queryExactArrivalProfile(boundary, destination, indexDomain));
                destinationWork += destinationInner.commonHubCount(boundary, destination);
            }
        }

        ExactArrivalProfile result = ExactArrivalProfile.empty();
        long combinations = 0;
        long skipped = 0;
        long supportBoundaryRejections = 0;
        long boundaryWork = 0;
        long compositions = prefixWork[0];
        long envelopes = prefixWork[1];
        long boundaryLoadNanos = 0;
        long[] work = {0, 0};
        try (TemporalProfileWork.Scope ignored = TemporalProfileWork.install((name, amount) -> {
            if (name.equals("thop_temporal_compositions")) {
                work[0] += amount;
            } else if (name.equals("thop_envelope_operations")) {
                work[1] += amount;
            }
        })) {
            for (int sourceBoundary : sourceBoundaries) {
                ExactArrivalProfile prefix = sourcePrefixes.get(sourceBoundary);
                if (prefix.isEmpty()) {
                    continue;
                }
                for (int destinationBoundary : destinationBoundaries) {
                    combinations++;
                    ExactArrivalProfile middle;
                    if (sourceBoundary == destinationBoundary) {
                        middle = ExactArrivalProfile.identity(arrivalImageDomain(prefix));
                    } else {
                        int commonBoundaryHubs = boundaryIndex.commonHubCount(
                                sourceBoundary, destinationBoundary);
                        boundaryWork += commonBoundaryHubs;
                        if (commonBoundaryHubs == 0) {
                            skipped++;
                            continue;
                        }
                        middle = boundaryIndex.queryExactArrivalProfile(
                                sourceBoundary, destinationBoundary, indexDomain);
                    }
                    if (middle.isEmpty()) {
                        continue;
                    }
                    ExactArrivalProfile candidate;
                    ExactArrivalProfile suffix = destinationBoundary == destination
                            ? ExactArrivalProfile.identity(arrivalImageDomain(middle))
                            : destinationSuffixes.get(destinationBoundary);
                    if (suffix.isEmpty()) {
                        continue;
                    }
                    ExactArrivalProfile boundaryToDestination = middle.composeAfter(suffix);
                    candidate = prefix.composeAfter(boundaryToDestination).restrict(departureDomain);
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    if (safePruning && !result.isEmpty() && result.dominates(candidate)) {
                        skipped++;
                        continue;
                    }
                    result = result.pointwiseMinimum(candidate);
                }
            }
        }
        compositions += work[0];
        envelopes += work[1];
        if (supportBoundaryRejections > 0) {
            throw new UnsupportedTemporalCompositionException(
                    source, destination, supportBoundaryRejections);
        }
        ExactArrivalProfile travelTimes = result.asTravelTime();
        return new QueryResult(
                source,
                destination,
                departureDomain,
                travelTimes.roundedLegacyProjection(),
                travelTimes,
                new QueryStatistics(
                        "cross-partition",
                        sourcePartition,
                        destinationPartition,
                        sourceWork,
                        destinationWork,
                        boundaryWork,
                        combinations,
                        skipped,
                        compositions,
                        envelopes,
                        supportBoundaryRejections,
                        sourceUse.bytesRead() + destinationUse.bytesRead(),
                        boundaryLoadNanos + sourceUse.loadNanos() + destinationUse.loadNanos(),
                        System.nanoTime() - started,
                        travelTimes.pieceCount()));
    }

    private PartitionUse requirePartition(int partitionId) throws IOException {
        THopIndex cached = loadedPartitions.get(partitionId);
        if (cached != null) {
            return new PartitionUse(cached, 0, 0);
        }
        BlockPointer pointer = partitionBlocks.get(partitionId);
        if (pointer == null) {
            throw new IOException("missing inner index block for partition " + partitionId);
        }
        long started = System.nanoTime();
        byte[] block = new byte[Math.toIntExact(pointer.length())];
        try (RandomAccessFile input = new RandomAccessFile(indexFile.toFile(), "r")) {
            input.seek(pointer.offset());
            input.readFully(block);
        }
        if (!Arrays.equals(sha256(block), pointer.digest())) {
            throw new IOException("partition label block checksum failed: " + partitionId);
        }
        THopIndex decoded = decodePartition(graph, metadata, indexDomain, partitionId, block);
        THopIndex prior = loadedPartitions.putIfAbsent(partitionId, decoded);
        return new PartitionUse(
                prior == null ? decoded : prior,
                prior == null ? block.length : 0,
                System.nanoTime() - started);
    }

    private static InnerIndexBuild buildInnerLabels(
            TDGraph originalGraph,
            TDGraph augmentedGraph,
            List<Integer> vertices,
            Domain indexDomain,
            Map<Integer, ExactArrivalProfile> exactArcArrivals) {
        List<Integer> hubOrder = vertices.stream().sorted().toList();
        THopIndex inner = THopIndex.buildPruned(
                augmentedGraph, indexDomain, hubOrder, exactArcArrivals);
        THopIndex.PreprocessingStatistics stats = inner.preprocessingStatistics();
        long searchCalls = stats.forwardSearchCalls() + stats.backwardSearchCalls();
        return new InnerIndexBuild(
                THopIndex.fromExistingLabels(originalGraph, indexDomain,
                        inner.outgoingLabelsSnapshot(), inner.incomingLabelsSnapshot(), hubOrder),
                searchCalls,
                stats.profilesConstructed(),
                stats.labelsRetained(),
                stats.labelsPruned(),
                stats.searchExpansionsSkipped(),
                stats.forwardSearchExpansions(),
                stats.backwardSearchExpansions(),
                stats.temporalFunctionPieces(),
                stats.temporalCompositions(),
                stats.envelopeOperations(),
                stats.sampledPeakUsedHeapBytes());
    }

    private static AugmentedPartition buildAugmentedPartition(
            TDGraph graph,
            GraphPartitionMetadata metadata,
            BoundaryTHopIndex boundary,
            int partitionId,
            Domain domain) {
        List<Integer> vertices = metadata.verticesByPartition().get(partitionId);
        Set<Integer> memberSet = Set.copyOf(vertices);
        List<Node> nodes = vertices.stream().map(graph::node).toList();
        List<Edge> edges = new ArrayList<>();
        for (int arcId : metadata.internalArcIdsByPartition().get(partitionId)) {
            Edge original = graph.edges().get(arcId);
            edges.add(new Edge(
                    edges.size(), original.source(), original.target(), original.distance(),
                    original.baseTravelTime(), original.travelTimeFunction(), original.scoreFunction()));
        }
        List<Integer> localBoundaries = boundaryVerticesInPartition(metadata, partitionId);
        Map<Integer, ExactArrivalProfile> exactArcArrivals = new HashMap<>();
        long shortcutArcs = 0;
        long started = System.nanoTime();
        long lastProgress = started;
        int totalPairs = localBoundaries.size() * Math.max(0, localBoundaries.size() - 1);
        int completedPairs = 0;
        long maxPieces = 0;
        int maxRationalBits = 0;
        if (ThopBuildProgress.ENABLED) {
            ThopBuildProgress.log("stage=boundary_shortcuts_start partition=" + partitionId
                    + " boundary_vertices=" + localBoundaries.size()
                    + " candidate_pairs=" + totalPairs);
        }
        for (int source : localBoundaries) {
            for (int destination : localBoundaries) {
                if (source == destination) {
                    continue;
                }
                // Inner-index construction consumes exact profiles only. Avoid creating the
                // rounded legacy projection that the public boundary query also returns.
                if (ThopBuildControl.ENABLED) ThopBuildControl.checkStop("inner:boundary_shortcuts:partition=" + partitionId + ":pair=" + source + "->" + destination);
                ExactArrivalProfile travel = boundary.queryExactArrivalProfile(
                        source, destination, domain).asTravelTime();
                completedPairs++;
                if (travel.isEmpty()) continue;
                validateFifoProfile(travel, source, destination);
                maxPieces = Math.max(maxPieces, travel.pieceCount());
                maxRationalBits = Math.max(maxRationalBits,
                        travel.maximumRationalComponentBitLength());
                PiecewiseLinearFn placeholder = placeholderTravelFunction(domain);
                Edge shortcut = new Edge(
                        edges.size(), source, destination, 0, 0, placeholder,
                        PiecewiseConstFn.constant(placeholder.domain(), 0));
                edges.add(shortcut);
                // The graph arc only carries topology; the exact profile is the authoritative shortcut weight.
                exactArcArrivals.put(shortcut.arcId(), travel.asArrivalProfile());
                shortcutArcs++;
                long now = System.nanoTime();
                if (ThopBuildProgress.ENABLED && now - lastProgress >= ThopBuildProgress.intervalNanos()) {
                    ThopBuildProgress.log("stage=boundary_shortcuts_progress partition=" + partitionId
                            + " completed_pairs=" + completedPairs
                            + " candidate_pairs=" + totalPairs
                            + " current_pair=" + source + "->" + destination
                            + " shortcut_arcs=" + shortcutArcs
                            + " max_profile_segments=" + maxPieces
                            + " max_rational_component_bits=" + maxRationalBits
                            + " elapsed_wall_ns=" + (now - started));
                    lastProgress = now;
                }
            }
        }
        if (edges.stream().anyMatch(edge -> !memberSet.contains(edge.source())
                || !memberSet.contains(edge.target()))) {
            throw new IllegalStateException("augmented partition contains an external vertex");
        }
        if (ThopBuildProgress.ENABLED) {
            ThopBuildProgress.log("stage=boundary_shortcuts_complete partition=" + partitionId
                    + " completed_pairs=" + completedPairs
                    + " candidate_pairs=" + totalPairs
                    + " shortcut_arcs=" + shortcutArcs
                    + " max_profile_segments=" + maxPieces
                    + " max_rational_component_bits=" + maxRationalBits
                    + " elapsed_wall_ns=" + (System.nanoTime() - started));
        }
        return new AugmentedPartition(
                new TDGraph(nodes, edges), new PartitionBuildCounts(shortcutArcs),
                Map.copyOf(exactArcArrivals));
    }

    private static void validateFifoProfile(
            ExactArrivalProfile profile, int source, int destination) {
        ExactArrivalProfile arrivals = profile.asArrivalProfile();
        List<ExactAffineSegment> pieces = arrivals.segments();
        for (ExactAffineSegment piece : pieces) {
            if (piece.slope().signum() < 0) {
                throw new IllegalArgumentException(
                        "non-FIFO boundary shortcut " + source + "->" + destination);
            }
        }
        for (int index = 1; index < pieces.size(); index++) {
            ExactAffineSegment left = pieces.get(index - 1);
            ExactAffineSegment right = pieces.get(index);
            ExactTime leftArrival = left.valueAtClosure(left.interval().end());
            ExactTime rightArrival = right.valueAtClosure(right.interval().start());
            if (leftArrival.compareTo(rightArrival) > 0) {
                throw new IllegalArgumentException(
                        "non-FIFO boundary shortcut across temporal support at "
                                + source + "->" + destination);
            }
        }
    }

    private static List<Integer> boundaryVerticesInPartition(
            GraphPartitionMetadata metadata, int partitionId) {
        return metadata.verticesByPartition().get(partitionId).stream()
                .filter(vertex -> metadata.boundaryVertexIds().contains(vertex))
                .sorted()
                .toList();
    }

    private static ExactTemporalDomain arrivalImageDomain(ExactArrivalProfile arrivalProfile) {
        return arrivalProfile.imageDomain();
    }

    private static PiecewiseLinearFn placeholderTravelFunction(Domain domain) {
        double start = domain.intervals().getFirst().start();
        double end = domain.intervals().getLast().end();
        if (start == end) {
            start -= 1.0;
            end += 1.0;
        }
        PiecewiseLinearFn spanning = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(start, 0),
                new PiecewiseLinearFn.Breakpoint(end, 0)));
        return spanning.restrict(domain);
    }

    private static long countLabels(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        return labels.values().stream().mapToLong(Map::size).sum();
    }

    private static long countPieces(Map<Integer, Map<Integer, ExactArrivalProfile>> labels) {
        return labels.values().stream()
                .flatMap(row -> row.values().stream())
                .mapToLong(ExactArrivalProfile::pieceCount)
                .sum();
    }

    private static TreeMap<Integer, BlockPointer> writeIndexFile(
            TDGraph graph,
            GraphPartitionMetadata metadata,
            Domain domain,
            Path boundaryIndexFile,
            byte[] boundaryDigest,
            Path indexFile,
            Map<Integer, THopIndex> partitions,
            ConstructionStatistics stats) throws IOException {
        Path absolute = indexFile.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        TreeMap<Integer, BlockPointer> pointers = new TreeMap<>();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeUTF(CONSTRUCTION_ID);
            output.writeUTF(QUERY_ID);
            output.write(graphFingerprint(graph));
            output.write(boundaryDigest);
            output.writeUTF(boundaryIndexFile.toAbsolutePath().normalize().toString());
            writeDomain(output, domain);
            writeAssignments(output, metadata.partitionByVertex());
            writeConstructionStatistics(output, stats);
            output.writeInt(metadata.partitionCount());
            for (int partitionId : metadata.verticesByPartition().keySet()) {
                THopIndex partition = partitions.get(partitionId);
                if (partition == null) {
                    throw new IOException("missing inner index for partition " + partitionId);
                }
                byte[] block = encodePartition(
                        partitionId, metadata.verticesByPartition().get(partitionId), partition);
                output.writeInt(partitionId);
                output.writeLong(block.length);
                byte[] digest = sha256(block);
                output.write(digest);
                long offset = payloadBytes.size();
                output.write(block);
                pointers.put(partitionId, new BlockPointer(offset, block.length, digest));
            }
        }
        byte[] payload = payloadBytes.toByteArray();
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream(payload.length + DIGEST_LENGTH);
        fileBytes.write(payload);
        fileBytes.write(sha256(payload));
        writeAtomically(absolute, fileBytes.toByteArray());
        return pointers;
    }

    private static Path partitionCheckpointPath(Path directory, int partitionId) {
        return directory.resolve("partition-" + partitionId + ".pthc");
    }

    /** Persist a completed partition before starting the next exact profile search. */
    private static void writePartitionCheckpoint(
            TDGraph graph,
            GraphPartitionMetadata metadata,
            Domain domain,
            byte[] boundaryDigest,
            int partitionId,
            THopIndex index,
            PartitionConstructionStatistics statistics,
            Path target) throws IOException {
        byte[] block = encodePartition(
                partitionId, metadata.verticesByPartition().get(partitionId), index);
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(CHECKPOINT_MAGIC);
            output.writeInt(CHECKPOINT_FORMAT_VERSION);
            output.writeUTF(CONSTRUCTION_ID);
            output.writeUTF(QUERY_ID);
            output.write(graphFingerprint(graph));
            output.write(boundaryDigest);
            writeDomain(output, domain);
            writeAssignments(output, metadata.partitionByVertex());
            output.writeInt(partitionId);
            output.writeLong(block.length);
            output.write(sha256(block));
            output.write(block);
            writePartitionStatistics(output, statistics);
        }
        byte[] payload = payloadBytes.toByteArray();
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream(payload.length + DIGEST_LENGTH);
        fileBytes.write(payload);
        fileBytes.write(sha256(payload));
        writeAtomically(target.toAbsolutePath().normalize(), fileBytes.toByteArray());
    }

    private static LoadedPartitionCheckpoint readPartitionCheckpoint(
            TDGraph graph,
            GraphPartitionMetadata metadata,
            Domain domain,
            byte[] boundaryDigest,
            int expectedPartition,
            Path checkpoint) throws IOException {
        byte[] fileBytes = Files.readAllBytes(checkpoint);
        if (fileBytes.length < DIGEST_LENGTH) {
            throw new IOException("truncated THop partition checkpoint: " + checkpoint);
        }
        int payloadLength = fileBytes.length - DIGEST_LENGTH;
        byte[] payload = Arrays.copyOf(fileBytes, payloadLength);
        byte[] suppliedDigest = Arrays.copyOfRange(fileBytes, payloadLength, fileBytes.length);
        if (!Arrays.equals(sha256(payload), suppliedDigest)) {
            throw new IOException("THop partition checkpoint checksum failed: " + checkpoint);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != CHECKPOINT_MAGIC
                    || input.readInt() != CHECKPOINT_FORMAT_VERSION
                    || !CONSTRUCTION_ID.equals(input.readUTF())
                    || !QUERY_ID.equals(input.readUTF())) {
                throw new IOException("THop partition checkpoint version or construction mismatch: " + checkpoint);
            }
            byte[] graphDigest = new byte[DIGEST_LENGTH];
            input.readFully(graphDigest);
            byte[] storedBoundaryDigest = new byte[DIGEST_LENGTH];
            input.readFully(storedBoundaryDigest);
            if (!Arrays.equals(graphDigest, graphFingerprint(graph))
                    || !Arrays.equals(storedBoundaryDigest, boundaryDigest)) {
                throw new IOException("THop partition checkpoint graph or boundary identity mismatch: " + checkpoint);
            }
            if (!readDomain(input).equals(domain)
                    || !readAssignments(input).equals(metadata.partitionByVertex())) {
                throw new IOException("THop partition checkpoint domain or partition identity mismatch: " + checkpoint);
            }
            int partitionId = input.readInt();
            if (partitionId != expectedPartition) {
                throw new IOException("THop partition checkpoint ID mismatch: " + checkpoint);
            }
            long blockLength = input.readLong();
            long requiredAfterBlock = DIGEST_LENGTH + 120L;
            if (blockLength < 0 || blockLength > Integer.MAX_VALUE
                    || blockLength > input.available() - requiredAfterBlock) {
                throw new IOException("invalid THop partition checkpoint block length: " + checkpoint);
            }
            byte[] blockDigest = new byte[DIGEST_LENGTH];
            input.readFully(blockDigest);
            byte[] block = new byte[(int) blockLength];
            input.readFully(block);
            if (!Arrays.equals(sha256(block), blockDigest)) {
                throw new IOException("THop partition checkpoint label-block checksum failed: " + checkpoint);
            }
            PartitionConstructionStatistics statistics = readPartitionStatistics(input);
            if (input.available() != 0
                    || statistics.partitionId() != expectedPartition
                    || statistics.vertexCount() != metadata.verticesByPartition().get(expectedPartition).size()
                    || statistics.internalArcCount() != metadata.internalArcIdsByPartition().get(expectedPartition).size()
                    || statistics.boundaryVertexCount() != boundaryVerticesInPartition(metadata, expectedPartition).size()) {
                throw new IOException("THop partition checkpoint statistics mismatch: " + checkpoint);
            }
            THopIndex index = decodePartition(graph, metadata, domain, expectedPartition, block);
            return new LoadedPartitionCheckpoint(index, statistics);
        } catch (EOFException truncated) {
            throw new IOException("truncated THop partition checkpoint: " + checkpoint, truncated);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid THop partition checkpoint: " + checkpoint, invalid);
        }
    }

    private static void writePartitionStatistics(
            DataOutputStream output, PartitionConstructionStatistics stats) throws IOException {
        output.writeInt(stats.partitionId());
        output.writeInt(stats.vertexCount());
        output.writeInt(stats.internalArcCount());
        output.writeInt(stats.boundaryVertexCount());
        output.writeLong(stats.shortcutArcCount());
        output.writeLong(stats.searchCalls());
        output.writeLong(stats.profilesGenerated());
        output.writeLong(stats.labelsRetained());
        output.writeLong(stats.labelsPruned());
        output.writeLong(stats.searchExpansionsSkipped());
        output.writeLong(stats.forwardSearchExpansions());
        output.writeLong(stats.backwardSearchExpansions());
        output.writeLong(stats.temporalPieces());
        output.writeLong(stats.temporalCompositions());
        output.writeLong(stats.envelopeOperations());
        output.writeLong(stats.constructionNanos());
        output.writeLong(stats.sampledPeakUsedHeapBytes());
    }

    private static PartitionConstructionStatistics readPartitionStatistics(DataInputStream input)
            throws IOException {
        return new PartitionConstructionStatistics(
                input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong());
    }

    private static byte[] encodePartition(
            int partitionId, List<Integer> vertices, THopIndex index) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(partitionId);
            output.writeInt(vertices.size());
            for (int vertex : vertices) {
                output.writeInt(vertex);
            }
            output.writeInt(index.hubOrder().size());
            for (int hub : index.hubOrder()) {
                output.writeInt(hub);
            }
            writeLabelMaps(output, vertices, index, true);
            writeLabelMaps(output, vertices, index, false);
        }
        return bytes.toByteArray();
    }

    private static void writeLabelMaps(
            DataOutputStream output, List<Integer> vertices, THopIndex index, boolean outgoing)
            throws IOException {
        output.writeInt(vertices.size());
        for (int vertex : vertices) {
            output.writeInt(vertex);
            Map<Integer, ExactArrivalProfile> labels = outgoing
                    ? index.exactOutgoingLabels(vertex) : index.exactIncomingLabels(vertex);
            TreeMap<Integer, ExactArrivalProfile> ordered = new TreeMap<>(labels);
            output.writeInt(ordered.size());
            for (Map.Entry<Integer, ExactArrivalProfile> label : ordered.entrySet()) {
                output.writeInt(label.getKey());
                writeProfile(output, label.getValue());
            }
        }
    }

    private static THopIndex decodePartition(
            TDGraph graph, GraphPartitionMetadata metadata, Domain domain, int expectedPartition,
            byte[] block) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(block))) {
            int partitionId = input.readInt();
            if (partitionId != expectedPartition) {
                throw new IOException("partition block ID mismatch");
            }
            int vertexCount = readCount(input, "partition vertices");
            List<Integer> vertices = new ArrayList<>(vertexCount);
            for (int i = 0; i < vertexCount; i++) {
                vertices.add(input.readInt());
            }
            if (!vertices.equals(metadata.verticesByPartition().get(partitionId))) {
                throw new IOException("partition vertex list does not match graph metadata");
            }
            int hubCount = readCount(input, "partition hub order");
            List<Integer> hubOrder = new ArrayList<>(hubCount);
            for (int i = 0; i < hubCount; i++) {
                hubOrder.add(input.readInt());
            }
            if (hubCount != vertexCount || !new HashSet<>(hubOrder).equals(Set.copyOf(vertices))) {
                throw new IOException("invalid inner hub order");
            }
            Map<Integer, Map<Integer, ExactArrivalProfile>> out = readLabelMaps(input, vertices, domain);
            Map<Integer, Map<Integer, ExactArrivalProfile>> in = readLabelMaps(input, vertices, domain);
            if (input.available() != 0) {
                throw new IOException("trailing bytes in partition label block");
            }
            for (int source : vertices) {
                for (Map.Entry<Integer, ExactArrivalProfile> label : out.get(source).entrySet()) {
                    ExactArrivalProfile reverse = in.get(label.getKey()).get(source);
                    // Forward and backward pruned labels can retain different valid path
                    // profiles for the same ordered pair. Query exactness is validated against
                    // the independent full-graph oracle; these maps are not inverse copies.
                    if (reverse != null && !reverse.sameFunction(label.getValue())) {
                        // Keep both certified path alternatives for exact two-hop minimization.
                    }
                }
            }
            return THopIndex.fromExistingLabels(graph, domain, out, in, hubOrder);
        } catch (EOFException truncated) {
            throw new IOException("truncated inner partition label block", truncated);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid inner partition label block", invalid);
        }
    }

    private static Map<Integer, Map<Integer, ExactArrivalProfile>> readLabelMaps(
            DataInputStream input, List<Integer> vertices, Domain indexDomain) throws IOException {
        int vertexCount = readCount(input, "label map vertices");
        if (vertexCount != vertices.size()) {
            throw new IOException("inner label vertex count mismatch");
        }
        TreeMap<Integer, Map<Integer, ExactArrivalProfile>> result = new TreeMap<>();
        Set<Integer> validVertices = Set.copyOf(vertices);
        for (int i = 0; i < vertexCount; i++) {
            int vertex = input.readInt();
            if (!validVertices.contains(vertex) || result.containsKey(vertex)) {
                throw new IOException("invalid or duplicate inner label vertex");
            }
            int labelCount = readCount(input, "inner labels");
            TreeMap<Integer, ExactArrivalProfile> labels = new TreeMap<>();
            for (int j = 0; j < labelCount; j++) {
                int hub = input.readInt();
                ExactArrivalProfile profile = readProfile(input);
                if (!validVertices.contains(hub)
                        || !profile.domain().difference(ExactTemporalDomain.fromLegacy(indexDomain)).isEmpty()
                        || labels.put(hub, profile) != null) {
                    throw new IOException("invalid inner label hub or temporal support");
                }
            }
            result.put(vertex, labels);
        }
        if (!result.keySet().equals(validVertices)) {
            throw new IOException("incomplete inner label maps");
        }
        return result;
    }

    private static void writeProfile(DataOutputStream output, ExactArrivalProfile profile)
            throws IOException {
        profile.writeTo(output);
    }

    private static ExactArrivalProfile readProfile(DataInputStream input) throws IOException {
        ExactArrivalProfile profile = ExactArrivalProfile.readFrom(input);
        if (profile.isEmpty()) {
            throw new IOException("stored THop labels cannot be empty");
        }
        return profile;
    }

    private static void writeConstructionStatistics(
            DataOutputStream output, ConstructionStatistics stats) throws IOException {
        output.writeInt(stats.partitionCount());
        output.writeInt(stats.graphVertexCount());
        output.writeInt(stats.internalArcCount());
        output.writeInt(stats.boundaryVertexCount());
        output.writeLong(stats.boundaryShortcutArcCount());
        output.writeLong(stats.searchCalls());
        output.writeLong(stats.profilesGenerated());
        output.writeLong(stats.labelsRetained());
        output.writeLong(stats.labelsPruned());
        output.writeLong(stats.searchExpansionsSkipped());
        output.writeLong(stats.forwardSearchExpansions());
        output.writeLong(stats.backwardSearchExpansions());
        output.writeLong(stats.temporalPieces());
        output.writeLong(stats.temporalCompositions());
        output.writeLong(stats.envelopeOperations());
        output.writeLong(stats.constructionNanos());
        output.writeLong(stats.boundaryIndexBytesOnDisk());
        output.writeLong(stats.sampledPeakUsedHeapBytes());
        output.writeInt(stats.resumedCheckpointCount());
        output.writeInt(stats.partitions().size());
        for (PartitionConstructionStatistics detail : new TreeMap<>(stats.partitions()).values()) {
            output.writeInt(detail.partitionId());
            output.writeInt(detail.vertexCount());
            output.writeInt(detail.internalArcCount());
            output.writeInt(detail.boundaryVertexCount());
            output.writeLong(detail.shortcutArcCount());
            output.writeLong(detail.searchCalls());
            output.writeLong(detail.profilesGenerated());
            output.writeLong(detail.labelsRetained());
            output.writeLong(detail.labelsPruned());
            output.writeLong(detail.searchExpansionsSkipped());
            output.writeLong(detail.forwardSearchExpansions());
            output.writeLong(detail.backwardSearchExpansions());
            output.writeLong(detail.temporalPieces());
            output.writeLong(detail.temporalCompositions());
            output.writeLong(detail.envelopeOperations());
            output.writeLong(detail.constructionNanos());
            output.writeLong(detail.sampledPeakUsedHeapBytes());
        }
    }

    private static ConstructionStatistics readConstructionStatistics(
            DataInputStream input, long indexBytes, long loadStarted) throws IOException {
        int partitions = input.readInt();
        int vertices = input.readInt();
        int internalArcs = input.readInt();
        int boundaryVertices = input.readInt();
        long shortcutArcs = input.readLong();
        long searches = input.readLong();
        long generated = input.readLong();
        long retained = input.readLong();
        long pruned = input.readLong();
        long skippedExpansions = input.readLong();
        long forwardExpansions = input.readLong();
        long backwardExpansions = input.readLong();
        long pieces = input.readLong();
        long compositions = input.readLong();
        long envelopes = input.readLong();
        long constructionNanos = input.readLong();
        long boundaryBytes = input.readLong();
        long peak = input.readLong();
        int resumedCheckpointCount = input.readInt();
        if (resumedCheckpointCount < 0 || resumedCheckpointCount > partitions) {
            throw new IOException("invalid resumed partition checkpoint count");
        }
        int detailCount = readCount(input, "per-partition construction statistics");
        if (detailCount != partitions) {
            throw new IOException("per-partition construction statistics count mismatch");
        }
        TreeMap<Integer, PartitionConstructionStatistics> details = new TreeMap<>();
        for (int i = 0; i < detailCount; i++) {
            PartitionConstructionStatistics detail = new PartitionConstructionStatistics(
                    input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readLong(),
                    input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                    input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                    input.readLong(), input.readLong());
            if (details.put(detail.partitionId(), detail) != null) {
                throw new IOException("duplicate per-partition statistics");
            }
        }
        return new ConstructionStatistics(
                partitions, vertices, internalArcs, boundaryVertices, shortcutArcs, searches,
                generated, retained, pruned, skippedExpansions, forwardExpansions,
                backwardExpansions, pieces, compositions, envelopes, constructionNanos,
                boundaryBytes, indexBytes, System.nanoTime() - loadStarted, peak,
                resumedCheckpointCount, details);
    }

    private static void writeAssignments(DataOutputStream output, Map<Integer, Integer> assignments)
            throws IOException {
        TreeMap<Integer, Integer> ordered = new TreeMap<>(assignments);
        output.writeInt(ordered.size());
        for (Map.Entry<Integer, Integer> entry : ordered.entrySet()) {
            output.writeInt(entry.getKey());
            output.writeInt(entry.getValue());
        }
    }

    private static Map<Integer, Integer> readAssignments(DataInputStream input) throws IOException {
        int count = readCount(input, "partition assignments");
        TreeMap<Integer, Integer> result = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int vertex = input.readInt();
            int partition = input.readInt();
            if (partition < 0 || result.put(vertex, partition) != null) {
                throw new IOException("invalid or duplicate partition assignment");
            }
        }
        return result;
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
        int count = readCount(input, "domain intervals");
        List<Domain.Interval> intervals = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                intervals.add(new Domain.Interval(
                        input.readDouble(), input.readDouble(), input.readBoolean(), input.readBoolean()));
            }
            return Domain.of(intervals.toArray(Domain.Interval[]::new));
        } catch (RuntimeException invalid) {
            throw new IOException("invalid serialized temporal domain", invalid);
        }
    }

    private static int readCount(DataInputStream input, String description) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COUNT) {
            throw new IOException("invalid " + description + " count: " + count);
        }
        return count;
    }

    /**
     * SHA-256 over the canonical serialization of the graph (node ids and coordinates, arc identifiers, lengths, base travel times, travel-time domains and breakpoints),
     * streamed into the digest in the exact order, widths and encodings of the original implementation ({@link #graphFingerprintReference}) instead of being buffered in one
     * byte[] (which fails above 2^31-9 bytes, i.e. on graphs of roughly 10 million arcs or more).
     */
    private static byte[] graphFingerprint(TDGraph graph) throws IOException {
        return graphFingerprintStreamed(graph).digest();
    }

    static BoundaryTHopIndex.GraphFingerprint graphFingerprintStreamed(TDGraph graph) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
        BoundaryTHopIndex.CountingDigestStream sink = new BoundaryTHopIndex.CountingDigestStream(digest);
        try (DataOutputStream output = new DataOutputStream(new java.io.BufferedOutputStream(sink, 1 << 20))) {
            writeGraphSerialization(output, graph);
        }
        return new BoundaryTHopIndex.GraphFingerprint(digest.digest(), sink.count());
    }

    private static void writeGraphSerialization(DataOutputStream output, TDGraph graph) throws IOException {
        output.writeInt(graph.nodeCount());
        for (int nodeId : graph.nodeIds()) {
            Node node = graph.node(nodeId);
            output.writeInt(node.id());
            output.writeLong(node.x());
            output.writeLong(node.y());
        }
        output.writeInt(graph.edgeCount());
        for (Edge edge : graph.edges()) {
            output.writeInt(edge.arcId());
            output.writeInt(edge.source());
            output.writeInt(edge.target());
            output.writeLong(edge.distance());
            output.writeDouble(edge.baseTravelTime());
            writeDomain(output, edge.travelTimeFunction().domain());
            output.writeInt(edge.travelTimeFunction().breakpoints().size());
            for (PiecewiseLinearFn.Breakpoint point : edge.travelTimeFunction().breakpoints()) {
                output.writeDouble(point.minute());
                output.writeDouble(point.value());
            }
        }
    }

    /** The original implementation, retained only for differential tests; it buffers the whole serialization in one byte[]. */
    static byte[] graphFingerprintReference(TDGraph graph) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(graph.nodeCount());
            for (int nodeId : graph.nodeIds()) {
                Node node = graph.node(nodeId);
                output.writeInt(node.id());
                output.writeLong(node.x());
                output.writeLong(node.y());
            }
            output.writeInt(graph.edgeCount());
            for (Edge edge : graph.edges()) {
                output.writeInt(edge.arcId());
                output.writeInt(edge.source());
                output.writeInt(edge.target());
                output.writeLong(edge.distance());
                output.writeDouble(edge.baseTravelTime());
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

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Path temporary = Files.createTempFile(
                absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static ConstructionStatistics copyWithBytes(
            ConstructionStatistics stats, long innerBytes, long loadingNanos) {
        return new ConstructionStatistics(
                stats.partitionCount(), stats.graphVertexCount(), stats.internalArcCount(),
                stats.boundaryVertexCount(), stats.boundaryShortcutArcCount(), stats.searchCalls(),
                stats.profilesGenerated(), stats.labelsRetained(), stats.labelsPruned(),
                stats.searchExpansionsSkipped(), stats.forwardSearchExpansions(),
                stats.backwardSearchExpansions(),
                stats.temporalPieces(), stats.temporalCompositions(), stats.envelopeOperations(),
                stats.constructionNanos(), stats.boundaryIndexBytesOnDisk(), innerBytes, loadingNanos,
                stats.sampledPeakUsedHeapBytes(), stats.resumedCheckpointCount(), stats.partitions());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public record ConstructionStatistics(
            int partitionCount,
            int graphVertexCount,
            int internalArcCount,
            int boundaryVertexCount,
            long boundaryShortcutArcCount,
            long searchCalls,
            long profilesGenerated,
            long labelsRetained,
            long labelsPruned,
            long searchExpansionsSkipped,
            long forwardSearchExpansions,
            long backwardSearchExpansions,
            long temporalPieces,
            long temporalCompositions,
            long envelopeOperations,
            long constructionNanos,
            long boundaryIndexBytesOnDisk,
            long innerIndexBytesOnDisk,
            long indexLoadingNanos,
            long sampledPeakUsedHeapBytes,
            int resumedCheckpointCount,
            Map<Integer, PartitionConstructionStatistics> partitions) {
        public ConstructionStatistics {
            partitions = Map.copyOf(new TreeMap<>(partitions));
        }
    }

    public record PartitionConstructionStatistics(
            int partitionId,
            int vertexCount,
            int internalArcCount,
            int boundaryVertexCount,
            long shortcutArcCount,
            long searchCalls,
            long profilesGenerated,
            long labelsRetained,
            long labelsPruned,
            long searchExpansionsSkipped,
            long forwardSearchExpansions,
            long backwardSearchExpansions,
            long temporalPieces,
            long temporalCompositions,
            long envelopeOperations,
            long constructionNanos,
            long sampledPeakUsedHeapBytes) {
    }

    public record QueryStatistics(
            String queryType,
            int sourcePartitionId,
            int destinationPartitionId,
            long sourceInnerLabelWork,
            long destinationInnerLabelWork,
            long boundaryLabelWork,
            long boundaryCombinationsExamined,
            long boundaryCombinationsSkipped,
            long profileCompositions,
            long envelopeOperations,
            long supportBoundaryRejections,
            long diskBytesRead,
            long lazyLoadNanos,
            long totalQueryNanos,
            long resultProfilePieces) {
    }

    public record QueryResult(
            int source,
            int destination,
            Domain departureDomain,
            ExactTemporalProfile travelTimeProfile,
            ExactArrivalProfile exactTravelTimeProfile,
            QueryStatistics queryStatistics) {
        public double travelTimeAt(double departureTime) {
            return exactTravelTimeProfile.valueAt(departureTime).toDisplayDouble();
        }

        public ExactTime exactTravelTimeAt(double departureTime) {
            return exactTravelTimeProfile.valueAt(departureTime);
        }
    }

    /** Raised instead of returning an incomplete profile after a support failure. */
    public static final class UnsupportedTemporalCompositionException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final long rejectedCombinations;

        private UnsupportedTemporalCompositionException(
                int source, int destination, long rejectedCombinations) {
            super("partitioned query " + source + "->" + destination
                    + " rejected: " + rejectedCombinations
                    + " boundary combination(s) crossed an unrepresentable profile-support endpoint");
            this.rejectedCombinations = rejectedCombinations;
        }

        public long rejectedCombinations() {
            return rejectedCombinations;
        }
    }

    private record AugmentedPartition(
            TDGraph graph,
            PartitionBuildCounts counts,
            Map<Integer, ExactArrivalProfile> exactArcArrivals) {
    }

    private record PartitionBuildCounts(long shortcutArcs) {
    }

    private record InnerIndexBuild(
            THopIndex index,
            long searchCalls,
            long profilesGenerated,
            long labelsRetained,
            long labelsPruned,
            long searchExpansionsSkipped,
            long forwardSearchExpansions,
            long backwardSearchExpansions,
            long temporalPieces,
            long temporalCompositions,
            long envelopeOperations,
            long sampledPeakUsedHeapBytes) {
    }

    private record BlockPointer(long offset, long length, byte[] digest) {
        private BlockPointer {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    private record PartitionUse(THopIndex index, long bytesRead, long loadNanos) {
    }

    private record LoadedPartitionCheckpoint(
            THopIndex index, PartitionConstructionStatistics statistics) {
    }
}
