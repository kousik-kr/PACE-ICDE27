package edu.ipcmax.core.loader;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import edu.ipcmax.core.function.Domain;

/**
 * Generated-dataset manifest fields used by loading and query construction.
 */
public record ManifestSummary(
        int schemaVersion,
        int numNodes,
        int numArcs,
        long seed,
        int selectedScoreEdgeCount,
        boolean unlistedEdgesHaveScoreZero,
        Optional<TimeWindow> temporalSupport,
        Map<String, TimeWindow> rushWindows,
        Optional<String> conversionContractId,
        Optional<String> datasetChecksum,
        Optional<String> temporalAttributeChecksum) {
    public ManifestSummary {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("manifest schema version must be positive");
        }
        temporalSupport = temporalSupport == null ? Optional.empty() : temporalSupport;
        conversionContractId = normalizedOptional(
                conversionContractId, "conversion contract ID");
        datasetChecksum = normalizedChecksum(datasetChecksum, "dataset checksum");
        temporalAttributeChecksum = normalizedChecksum(
                temporalAttributeChecksum, "temporal-attribute checksum");
        TreeMap<String, TimeWindow> normalizedRushWindows = new TreeMap<>();
        if (rushWindows != null) {
            rushWindows.forEach((name, window) -> {
                if (name == null || name.isBlank() || window == null) {
                    throw new IllegalArgumentException("rush-window names and values are required");
                }
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if (normalizedRushWindows.put(normalized, window) != null) {
                    throw new IllegalArgumentException("duplicate rush-window name: " + normalized);
                }
            });
        }
        rushWindows = Collections.unmodifiableMap(normalizedRushWindows);
    }

    /** Compatibility constructor for callers that do not supply preparation checksums. */
    public ManifestSummary(
            int numNodes,
            int numArcs,
            long seed,
            int selectedScoreEdgeCount,
            boolean unlistedEdgesHaveScoreZero,
            Optional<TimeWindow> temporalSupport,
            Map<String, TimeWindow> rushWindows) {
        this(1, numNodes, numArcs, seed, selectedScoreEdgeCount,
                unlistedEdgesHaveScoreZero, temporalSupport, rushWindows,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Compatibility constructor for manifests without temporal metadata. */
    public ManifestSummary(
            int numNodes,
            int numArcs,
            long seed,
            int selectedScoreEdgeCount,
            boolean unlistedEdgesHaveScoreZero) {
        this(numNodes, numArcs, seed, selectedScoreEdgeCount,
                unlistedEdgesHaveScoreZero, Optional.empty(), Map.of());
    }

    private static Optional<String> normalizedOptional(
            Optional<String> value,
            String name) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String normalized = value.orElseThrow().trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return Optional.of(normalized);
    }

    private static Optional<String> normalizedChecksum(
            Optional<String> value,
            String name) {
        Optional<String> normalized = normalizedOptional(value, name);
        if (normalized.isPresent()
                && !normalized.orElseThrow().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must contain exactly 64 hexadecimal characters");
        }
        return normalized.map(text -> text.toLowerCase(Locale.ROOT));
    }

    /** Named rush period, matched case-insensitively. */
    public Optional<TimeWindow> rushWindow(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rushWindows.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /** Closed temporal interval in dataset minutes. */
    public record TimeWindow(double startMinute, double endMinute) {
        public TimeWindow {
            if (!Double.isFinite(startMinute) || !Double.isFinite(endMinute)) {
                throw new IllegalArgumentException("manifest time-window endpoints must be finite");
            }
            startMinute = Domain.canonicalTime(startMinute);
            endMinute = Domain.canonicalTime(endMinute);
            if (endMinute < startMinute) {
                throw new IllegalArgumentException("manifest time-window endpoints are reversed");
            }
        }
    }
}
