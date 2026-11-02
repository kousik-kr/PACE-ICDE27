package edu.ipcmax.experiments.querygen;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Stable quartile labels for lower-bound source/destination distance. */
public enum DistanceBin {
    Q1,
    Q2,
    Q3,
    Q4;

    /** Parses Q1-Q4 case-insensitively; bare numbers 1-4 are also accepted. */
    @JsonCreator
    public static DistanceBin parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("distance bin is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() == 1 && Character.isDigit(normalized.charAt(0))) {
            normalized = "Q" + normalized;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown distance bin: " + value, failure);
        }
    }

    /** Stable JSON/YAML representation. */
    @JsonValue
    public String id() {
        return name();
    }

    String idToken() {
        return name().toLowerCase(Locale.ROOT);
    }
}
