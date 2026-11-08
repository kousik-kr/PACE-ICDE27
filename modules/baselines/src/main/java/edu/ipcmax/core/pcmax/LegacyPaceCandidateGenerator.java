package edu.ipcmax.core.pcmax;

import java.util.Objects;

/** Versioned facade preserving the existing PACE-X and legacy PACE-B bounded engines. */
public final class LegacyPaceCandidateGenerator
        implements VersionedCandidateGenerator<PaceGenerationResult> {
    private final PACE pace;
    private final PaceAlgorithmVersion version;

    public LegacyPaceCandidateGenerator(PACE pace, PaceAlgorithmVersion version) {
        this.pace = Objects.requireNonNull(pace, "pace");
        this.version = Objects.requireNonNull(version, "version");
        if (version == PaceAlgorithmVersion.PACE_B_EGPI_V1) {
            throw new IllegalArgumentException("EGPI requires its own candidate generator");
        }
    }

    @Override public PaceGenerationResult generate(QuerySpec query) { return pace.generate(query); }
    @Override public String algorithmVersion() { return version.name(); }

    /** Preserves the original frontier extraction, cap handling, and timing path. */
    public EnvelopeProfile run(QuerySpec query) { return pace.run(query); }
}
