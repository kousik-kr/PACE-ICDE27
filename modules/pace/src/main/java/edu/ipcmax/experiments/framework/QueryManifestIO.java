package edu.ipcmax.experiments.framework;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Strict JSONL manifest reader and validator. */
public final class QueryManifestIO {
    private static final Set<String> VERSION_1_FIELDS = Set.of(
            "schema_version", "query_id", "dataset_id", "source", "destination",
            "interval_start", "interval_end", "window_length", "budget", "budget_slack",
            "budget_policy", "distance_bin", "lower_bound_distance", "query_seed", "metadata");
    private static final Set<String> VERSION_1_NULLABLE = Set.of(
            "budget_slack", "distance_bin", "lower_bound_distance");
    private static final Set<String> VERSION_3_FIELDS = VERSION_1_FIELDS;
    private static final Set<String> VERSION_4_FIELDS = Set.of(
            "schema_version", "query_id", "dataset_id", "source", "destination",
            "interval_start_ticks", "interval_end_ticks", "window_length_ticks",
            "interval_time_unit", "endpoint_inclusion", "budget", "budget_slack",
            "budget_policy", "distance_bin", "lower_bound_distance", "query_seed", "metadata");
    private static final Set<String> VERSION_2_FIELDS = Set.of(
            "schema_version", "query_id", "query_family_id", "pair_family_id", "dataset_id",
            "dataset_path", "graph_checksum", "source", "destination", "distance_bin",
            "temporal_regime", "interval_start", "interval_end", "window_length", "budget",
            "budget_slack", "budget_policy", "lower_bound_distance", "lower_bound_edge_count",
            "corridor_anchor_count", "fastest_travel_time_min", "fastest_travel_time_max",
            "expected_full_interval_feasible", "expected_mixed_feasibility", "query_seed",
            "generator_version", "generator_config_hash", "metadata");
    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ObjectMapper CANONICAL_JSON = JSON.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private QueryManifestIO() {
    }

    public static List<QueryManifestEntry> read(Path path) throws IOException {
        List<QueryManifestEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                QueryManifestEntry entry;
                try {
                    JsonNode node = JSON.readTree(line);
                    validateShape(node);
                    entry = JSON.treeToValue(node, QueryManifestEntry.class);
                    entry.validate();
                } catch (IOException | RuntimeException ex) {
                    throw new IOException(path + ":" + lineNumber + ": " + ex.getMessage(), ex);
                }
                if (!ids.add(entry.queryId())) {
                    throw new IOException(path + ":" + lineNumber + ": duplicate query_id " + entry.queryId());
                }
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("query manifest is empty: " + path);
        }
        return List.copyOf(entries);
    }

    /** Validates and atomically writes canonical UTF-8 JSON Lines with LF delimiters. */
    public static void write(Path path, List<QueryManifestEntry> entries) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("query manifest path is required");
        }
        validateEntries(path, entries);
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent() == null ? Path.of(".").toAbsolutePath() : absolute.getParent();
        Files.createDirectories(parent);
        String prefix = "." + absolute.getFileName() + ".";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                for (QueryManifestEntry entry : entries) {
                    writer.write(CANONICAL_JSON.writeValueAsString(entry));
                    writer.write('\n');
                }
            }
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateEntries(Path path, List<QueryManifestEntry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            throw new IOException("query manifest is empty: " + path);
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            QueryManifestEntry entry = entries.get(index);
            if (entry == null) {
                throw new IOException(path + ": entry " + (index + 1) + " is null");
            }
            try {
                entry.validate();
            } catch (RuntimeException failure) {
                throw new IOException(path + ": entry " + (index + 1) + ": "
                        + failure.getMessage(), failure);
            }
            if (!ids.add(entry.queryId())) {
                throw new IOException(path + ": duplicate query_id " + entry.queryId());
            }
        }
    }

    private static void validateShape(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("query manifest row must be a JSON object");
        }
        JsonNode schema = node.get("schema_version");
        if (schema == null || !schema.isIntegralNumber() || !schema.canConvertToInt()) {
            throw new IllegalArgumentException("schema_version must be an integer");
        }
        int version = schema.intValue();
        Set<String> required = switch (version) {
            case 1 -> VERSION_1_FIELDS;
            case 2 -> VERSION_2_FIELDS;
            case 3 -> VERSION_3_FIELDS;
            case 4 -> VERSION_4_FIELDS;
            default -> throw new IllegalArgumentException("unsupported query schema_version: " + version);
        };
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("query manifest row is missing fields: " + missing);
        }
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(required);
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("query manifest row has unexpected fields: " + unexpected);
        }
        Set<String> nullable =
                version == 1 || version == 3 || version == 4
                        ? VERSION_1_NULLABLE : Set.of();
        for (String field : required) {
            if (node.get(field).isNull() && !nullable.contains(field)) {
                throw new IllegalArgumentException(field + " cannot be null in schema version " + version);
            }
        }
    }

    public static ObjectMapper mapper() {
        return JSON;
    }
}
