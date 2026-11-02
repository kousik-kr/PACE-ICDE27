package edu.ipcmax.experiments.querygen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Stable checksums and dataset-specific seed derivation for generated graphs. */
public final class ManifestChecksum {
    private static final String CHECKSUM_DOMAIN = "PACE-GRAPH-CHECKSUM-v1";
    private static final String DATASET_CHECKSUM_DOMAIN =
            "PACE-DATASET-STRUCTURE-CHECKSUM-v1";
    private static final String TEMPORAL_CHECKSUM_DOMAIN =
            "PACE-TEMPORAL-ATTRIBUTE-CHECKSUM-v1";
    private static final String SEED_DOMAIN = "PACE-DATASET-SEED-v1";
    private static final int BUFFER_SIZE = 64 * 1024;

    /** Required graph files in their canonical checksum order. */
    public static final List<String> REQUIRED_GRAPH_FILES = List.of(
            "edges_static.csv.gz",
            "nodes.csv.gz",
            "manifest.json",
            "score_functions.jsonl.gz",
            "travel_time_functions.jsonl.gz");
    public static final List<String> STRUCTURAL_GRAPH_FILES = List.of(
            "edges_static.csv.gz",
            "nodes.csv.gz");
    public static final List<String> TEMPORAL_ATTRIBUTE_FILES = List.of(
            "score_functions.jsonl.gz",
            "travel_time_functions.jsonl.gz");

    private ManifestChecksum() {
    }

    /**
     * Hashes the raw bytes of every required file using domain-, name-, and length-framed SHA-256.
     * Text is UTF-8, text lengths are 32-bit big-endian, and file lengths are 64-bit big-endian.
     */
    public static String graphChecksum(Path datasetDirectory) throws IOException {
        return framedChecksum(
                datasetDirectory, CHECKSUM_DOMAIN, REQUIRED_GRAPH_FILES);
    }

    /** Hash of stable graph structure, excluding temporal attributes and manifest metadata. */
    public static String datasetChecksum(Path datasetDirectory) throws IOException {
        return framedChecksum(
                datasetDirectory,
                DATASET_CHECKSUM_DOMAIN,
                STRUCTURAL_GRAPH_FILES);
    }

    /** Hash of travel-time and score-function payload bytes. */
    public static String temporalAttributeChecksum(Path datasetDirectory)
            throws IOException {
        return framedChecksum(
                datasetDirectory,
                TEMPORAL_CHECKSUM_DOMAIN,
                TEMPORAL_ATTRIBUTE_FILES);
    }

    private static String framedChecksum(
            Path datasetDirectory,
            String domain,
            List<String> filenames) throws IOException {
        Objects.requireNonNull(datasetDirectory, "datasetDirectory");
        if (!Files.isDirectory(datasetDirectory)) {
            throw new IOException("graph dataset directory does not exist: " + datasetDirectory);
        }

        MessageDigest digest = sha256();
        updateFramedText(digest, domain);
        byte[] buffer = new byte[BUFFER_SIZE];
        for (String filename : filenames) {
            Path file = datasetDirectory.resolve(filename);
            if (!Files.isRegularFile(file)) {
                throw new IOException("required graph file does not exist or is not a regular file: " + file);
            }
            updateFramedText(digest, filename);
            long expectedSize = Files.size(file);
            updateLong(digest, expectedSize);
            long actualSize = 0;
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    actualSize += read;
                }
            }
            if (actualSize != expectedSize) {
                throw new IOException("graph file changed while computing checksum: " + file);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Alias for callers that treat the checksum utility as a computation. */
    public static String compute(Path datasetDirectory) throws IOException {
        return graphChecksum(datasetDirectory);
    }

    /**
     * Derives a signed 64-bit seed from the global seed, normalized dataset ID, and checksum bytes.
     * The result is the first eight SHA-256 bytes interpreted as a big-endian signed long.
     */
    public static long deriveDatasetSeed(long globalSeed, String datasetId, String graphChecksum) {
        String normalizedDataset = normalizeDatasetId(datasetId);
        byte[] checksumBytes = parseChecksum(graphChecksum);
        MessageDigest digest = sha256();
        updateFramedText(digest, SEED_DOMAIN);
        updateLong(digest, globalSeed);
        updateFramedText(digest, normalizedDataset);
        digest.update(checksumBytes);
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    static String normalizeDatasetId(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("dataset id is required");
        }
        String normalized = datasetId.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]*")) {
            throw new IllegalArgumentException("dataset id must be a single safe path component: " + datasetId);
        }
        return normalized;
    }

    private static byte[] parseChecksum(String checksum) {
        if (checksum == null || !checksum.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("graph checksum must contain exactly 64 hexadecimal characters");
        }
        return HexFormat.of().parseHex(checksum);
    }

    private static void updateFramedText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }
}
