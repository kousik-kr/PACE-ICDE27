package edu.ipcmax.core.pcmax;

import java.util.BitSet;
import java.util.List;

/** Exact selected-pivot coverage derived from a stable directed-arc path. */
final class PivotCoverage {
    private PivotCoverage() {
    }

    static BitSet extend(
            BitSet initial,
            PivotIndex pivots,
            List<Integer> stableArcIds) {
        BitSet covered = (BitSet) java.util.Objects
                .requireNonNull(initial, "initial").clone();
        java.util.Objects.requireNonNull(
                pivots, "pivots");
        for (int arcId : stableArcIds) {
            int rank = pivots.selectedRank(arcId);
            if (rank >= 0) {
                covered.set(rank);
            }
        }
        return covered;
    }
}
