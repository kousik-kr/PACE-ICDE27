package edu.ipcmax.experiments.framework;

import java.util.Map;
import java.util.Objects;

import edu.ipcmax.core.pcmax.EnvelopeProfile;

/** Common result returned by every experiment-facing algorithm. */
public record AlgorithmResult(
        ExperimentStatus status,
        EnvelopeProfile profile,
        ExactnessScope exactnessScope,
        Map<String, Object> scalars,
        String errorType,
        String errorMessage) {
    public AlgorithmResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(exactnessScope, "exactnessScope");
        if (exactnessScope == ExactnessScope.GLOBAL_CERTIFIED
                && status != ExperimentStatus.COMPLETED
                && status != ExperimentStatus.CERTIFIED_COMPLETE
                && status != ExperimentStatus.NO_FEASIBLE_PATH) {
            exactnessScope = ExactnessScope.NOT_CERTIFIED;
        }
        scalars = scalars == null ? Map.of() : Map.copyOf(scalars);
    }

    /**
     * Source-compatible constructor for callers using the former global-exactness boolean.
     * New code should pass an explicit {@link ExactnessScope}.
     */
    @Deprecated
    public AlgorithmResult(
            ExperimentStatus status,
            EnvelopeProfile profile,
            boolean completeProfile,
            Map<String, Object> scalars,
            String errorType,
            String errorMessage) {
        this(status, profile,
                completeProfile ? ExactnessScope.GLOBAL_CERTIFIED : ExactnessScope.NOT_CERTIFIED,
                scalars, errorType, errorMessage);
    }

    /** Backward-compatible view of whether the result carries a global certificate. */
    public boolean completeProfile() {
        return exactnessScope == ExactnessScope.GLOBAL_CERTIFIED;
    }

    public static AlgorithmResult profile(EnvelopeProfile profile, ExactnessScope exactnessScope) {
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible
                        ? ExperimentStatus.COMPLETED
                        : exactnessScope == ExactnessScope.GLOBAL_CERTIFIED
                                ? ExperimentStatus.NO_FEASIBLE_PATH
                                : ExperimentStatus
                                        .UNRESOLVED_NO_GENERATED_CANDIDATE,
                profile, exactnessScope, Map.of(), null, null);
    }

    /** Backward-compatible factory for the former global-exactness boolean. */
    @Deprecated
    public static AlgorithmResult profile(EnvelopeProfile profile, boolean exactClaim) {
        return profile(profile,
                exactClaim ? ExactnessScope.GLOBAL_CERTIFIED : ExactnessScope.NOT_CERTIFIED);
    }
}
