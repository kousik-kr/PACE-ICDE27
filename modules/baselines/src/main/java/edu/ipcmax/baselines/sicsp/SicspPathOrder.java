package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

/** Stable numeric lexicographic order for complete directed arc-ID paths. */
final class SicspPathOrder {
    private SicspPathOrder() {
    }

    static int compare(Path left, Path right) {
        int common = Math.min(left.arcIds().size(), right.arcIds().size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.arcIds().get(i), right.arcIds().get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.arcIds().size(), right.arcIds().size());
    }
}
