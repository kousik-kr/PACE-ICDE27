package edu.ipcmax.core.pcmax;

import java.util.List;
import java.util.Objects;

import edu.ipcmax.core.profile.CandidateSet;

/** Completion-bearing output of the feature-flagged candidate engine. */
public record PaceGenerationResult(
        CandidateSet frontier,
        PaceCompletion completion,
        PaceExactnessScope exactnessScope,
        PaceCapStatus capStatus,
        PaceGenerationStats stats,
        String corridorChecksum,
        List<Integer> selectedPivotArcIds,
        String outputChecksum) {
    public PaceGenerationResult {
        Objects.requireNonNull(frontier, "frontier");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(exactnessScope, "exactnessScope");
        Objects.requireNonNull(capStatus, "capStatus");
        Objects.requireNonNull(stats, "stats");
        corridorChecksum = corridorChecksum == null ? "" : corridorChecksum;
        selectedPivotArcIds = List.copyOf(selectedPivotArcIds);
        outputChecksum = outputChecksum == null ? "" : outputChecksum;
        if (exactnessScope == PaceExactnessScope.GLOBAL_CERTIFIED
                && ((completion != PaceCompletion.COMPLETE
                     && completion != PaceCompletion.NO_FEASIBLE_PATH)
                    || capStatus.any())) {
            throw new IllegalArgumentException(
                    "global exactness requires complete uncapped generation");
        }
        if (completion == PaceCompletion.NO_FEASIBLE_PATH
                && exactnessScope != PaceExactnessScope.GLOBAL_CERTIFIED) {
            throw new IllegalArgumentException(
                    "NO_FEASIBLE_PATH requires independent global certification");
        }
        if ((completion == PaceCompletion.NO_FEASIBLE_PATH
                || completion == PaceCompletion.UNRESOLVED_NO_GENERATED_CANDIDATE)
                && !frontier.isEmpty()) {
            throw new IllegalArgumentException("empty-frontier status requires an empty frontier");
        }
        if (completion == PaceCompletion.COMPLETE && frontier.isEmpty()) {
            throw new IllegalArgumentException("complete generation requires a non-empty frontier");
        }
    }
}
