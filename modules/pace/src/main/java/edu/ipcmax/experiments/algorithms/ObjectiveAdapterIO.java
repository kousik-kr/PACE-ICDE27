package edu.ipcmax.experiments.algorithms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.experiments.framework.AlgorithmConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Strict objective-specific input helpers; these never derive weights from scores. */
final class ObjectiveAdapterIO {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    record InputSnapshot(JsonNode root, String sha256) { }

    private ObjectiveAdapterIO() { }

    static String required(AlgorithmConfig config, String key) {
        String value = config.objectiveOptions().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing explicit objective option: " + key);
        }
        return value;
    }

    static InputSnapshot readVersionOne(Path path, AlgorithmConfig config, String key) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String checksum;
        try {
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String expected = config.objectiveOptions().get(key + "_sha256");
        if (expected != null && !expected.equals(checksum)) {
            throw new IOException("objective input changed after configuration was parsed: " + key);
        }
        JsonNode root = JSON.readTree(bytes);
        if (!root.isObject() || !root.path("schema_version").isInt()
                || root.path("schema_version").intValue() != 1) {
            throw new IOException("unsupported objective input schema: " + path);
        }
        return new InputSnapshot(root, checksum);
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

}
