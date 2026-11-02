package edu.ipcmax.core.pcmax;

/** Explicit PACE family identities; historical pace-b continues to mean legacy R13. */
public enum PaceAlgorithmVersion {
    PACE_X,
    PACE_B_LEGACY_R13,
    PACE_B_EGPI_V1;

    /**
     * The PACE-B engine that new experiment manifests should name explicitly.
     * The bare {@code pace-b} selector is not redirected: frozen manifests that
     * say {@code pace-b} keep running {@link #PACE_B_LEGACY_R13}.
     */
    public static final PaceAlgorithmVersion INTENDED_PACE_B = PACE_B_EGPI_V1;

    /** Canonical selector of this identity for new manifests. */
    public String selector() {
        return switch (this) {
            case PACE_X -> "pace-x";
            case PACE_B_LEGACY_R13 -> "pace-b-legacy-r13";
            case PACE_B_EGPI_V1 -> "pace-b-egpi-v1";
        };
    }

    public static PaceAlgorithmVersion fromSelector(String selector) {
        return switch (selector) {
            case "pace-x", "PACE_X" -> PACE_X;
            case "pace-b", "pace-b-legacy-r13", "PACE_B_LEGACY_R13" -> PACE_B_LEGACY_R13;
            case "pace-b-egpi-v1", "PACE_B_EGPI_V1" -> PACE_B_EGPI_V1;
            default -> throw new IllegalArgumentException("not a PACE algorithm selector: " + selector);
        };
    }

    public String candidateGenerationPolicy() {
        return switch (this) {
            case PACE_X -> "PACE_X_EXHAUSTIVE_FRONTIER";
            case PACE_B_LEGACY_R13 -> "LEGACY_R13_BOUNDED_LAYERED_FRONTIER";
            case PACE_B_EGPI_V1 -> "ENVELOPE_GUIDED_COMPLETE_ROUTE_PIVOT_INSERTION_V1";
        };
    }
}
