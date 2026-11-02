package edu.ipcmax.core.pcmax;

/** Feature switches used by controlled PACE-B ablations. */
public record PaceFeatures(
        boolean safeDominanceEnabled,
        boolean perCellRetentionEnabled,
        boolean representativeRetentionEnabled,
        boolean anchorLowerBoundFilterEnabled,
        boolean compressionEnabled,
        boolean adjacentMergeEnabled,
        boolean safeCorridorEnabled,
        boolean pivotDiversificationEnabled,
        boolean connectorPortfolioEnabled,
        boolean connectorCacheEnabled,
        boolean profileCacheEnabled,
        boolean scoreUpperBoundEnabled) {
    /** Compatibility constructor for the original frontier-only switches. */
    public PaceFeatures(
            boolean safeDominanceEnabled,
            boolean perCellRetentionEnabled,
            boolean representativeRetentionEnabled,
            boolean anchorLowerBoundFilterEnabled,
            boolean compressionEnabled,
            boolean adjacentMergeEnabled) {
        this(
                safeDominanceEnabled,
                perCellRetentionEnabled,
                representativeRetentionEnabled,
                anchorLowerBoundFilterEnabled,
                compressionEnabled,
                adjacentMergeEnabled,
                true,
                true,
                false,
                true,
                true,
                true);
    }

    /** Finalized PACE behavior. */
    public static PaceFeatures defaults() {
        return new PaceFeatures(
                true, true, true, true, true, true,
                true, true, false, true, true, true);
    }

    /** Returns the same features with the explicitly selected connector mode. */
    public PaceFeatures withConnectorPortfolioEnabled(boolean enabled) {
        return new PaceFeatures(
                safeDominanceEnabled,
                perCellRetentionEnabled,
                representativeRetentionEnabled,
                anchorLowerBoundFilterEnabled,
                compressionEnabled,
                adjacentMergeEnabled,
                safeCorridorEnabled,
                pivotDiversificationEnabled,
                enabled,
                connectorCacheEnabled,
                profileCacheEnabled,
                scoreUpperBoundEnabled);
    }

    /** Returns the same policy with the non-directed score-rate bound disabled. */
    public PaceFeatures withoutScoreUpperBound() {
        return new PaceFeatures(
                safeDominanceEnabled,
                perCellRetentionEnabled,
                representativeRetentionEnabled,
                anchorLowerBoundFilterEnabled,
                compressionEnabled,
                adjacentMergeEnabled,
                safeCorridorEnabled,
                pivotDiversificationEnabled,
                connectorPortfolioEnabled,
                connectorCacheEnabled,
                profileCacheEnabled,
                false);
    }
}
