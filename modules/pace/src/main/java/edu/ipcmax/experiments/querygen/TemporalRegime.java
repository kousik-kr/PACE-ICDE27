package edu.ipcmax.experiments.querygen;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Named departure-time regimes used by deterministic query generation. */
public enum TemporalRegime {
    MORNING_PEAK,
    DAY_OFFPEAK,
    EVENING_PEAK,
    LATE_OFFPEAK;

    /** Parses a case-insensitive name, accepting spaces and hyphens as separators. */
    @JsonCreator
    public static TemporalRegime parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("temporal regime is required");
        }
        try {
            return valueOf(normalize(value));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown temporal regime: " + value, failure);
        }
    }

    /** Stable JSON/YAML representation. */
    @JsonValue
    public String id() {
        return name();
    }

    String idToken() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String normalize(String value) {
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }
}
