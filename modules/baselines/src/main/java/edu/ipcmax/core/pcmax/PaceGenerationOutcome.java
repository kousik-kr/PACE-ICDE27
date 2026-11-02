package edu.ipcmax.core.pcmax;

import java.util.Objects;

/** Authoritative completion/scope mapping; neither status is derived from the other. */
record PaceGenerationOutcome(PaceCompletion completion, PaceExactnessScope exactness) {
    static PaceGenerationOutcome resolve(
            PaceExecutionPolicy policy, boolean hasCandidates, PaceCapStatus caps,
            boolean exhaustiveCoverage, boolean exactRetainedFrontier) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(caps, "caps");
        PaceExactnessScope retained = exactRetainedFrontier
                ? PaceExactnessScope.RETAINED_FRONTIER : PaceExactnessScope.NOT_CERTIFIED;
        if (caps.any()) {
            return policy == PaceExecutionPolicy.PACE_X
                    ? new PaceGenerationOutcome(PaceCompletion.ABORTED, PaceExactnessScope.NOT_CERTIFIED)
                    : new PaceGenerationOutcome(PaceCompletion.RESOURCE_TRUNCATED, retained);
        }
        boolean global = policy == PaceExecutionPolicy.PACE_X && exhaustiveCoverage;
        if (hasCandidates) {
            return new PaceGenerationOutcome(PaceCompletion.COMPLETE,
                    global ? PaceExactnessScope.GLOBAL_CERTIFIED : retained);
        }
        if (global) {
            return new PaceGenerationOutcome(PaceCompletion.NO_FEASIBLE_PATH,
                    PaceExactnessScope.GLOBAL_CERTIFIED);
        }
        return new PaceGenerationOutcome(PaceCompletion.UNRESOLVED_NO_GENERATED_CANDIDATE,
                policy == PaceExecutionPolicy.PACE_X ? PaceExactnessScope.NOT_CERTIFIED : retained);
    }
}
