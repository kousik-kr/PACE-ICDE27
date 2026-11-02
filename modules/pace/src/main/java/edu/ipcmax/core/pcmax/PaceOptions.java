package edu.ipcmax.core.pcmax;

import java.util.Objects;

/**
 * Complete, immutable configuration for one PACE candidate engine.
 *
 * <p>The connector output limit ({@code K_c}) and temporal-frontier limit
 * ({@code K_f}) are deliberately separate. The three safety/work caps also
 * have distinct units: connector states ({@code M_c}), exact breakpoints per
 * profile ({@code M_b}), and typed units of actual query work
 * ({@code M_q}).</p>
 */
public record PaceOptions(
        PaceExecutionPolicy policy,
        PaceEngineMode engineMode,
        int theta,
        int pivotLimitL,
        int connectorLimitKc,
        int frontierLimitKf,
        long connectorExpansionCapMc,
        int breakpointCapMb,
        long queryWorkCapMq,
        int threadCount,
        boolean memoizationEnabled,
        PaceFeatures features,
        int maxFrontierFragments) {
    /** Sentinel for disabled integer-valued limits. */
    public static final int UNBOUNDED = Integer.MAX_VALUE;
    /** Sentinel for disabled work limits. */
    public static final long UNBOUNDED_WORK = Long.MAX_VALUE;

    /** Validates and normalizes execution-policy invariants. */
    public PaceOptions {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(engineMode, "engineMode");
        Objects.requireNonNull(features, "features");
        if (theta < 0) {
            throw new IllegalArgumentException("theta cannot be negative");
        }
        if (threadCount < 1) {
            throw new IllegalArgumentException(
                    "thread count must be at least 1");
        }
        if (maxFrontierFragments < 1) {
            throw new IllegalArgumentException(
                    "maximum frontier fragments must be at least 1");
        }
        if (policy == PaceExecutionPolicy.PACE_X) {
            // SafeScoreUpperBound uses nearest-rounded doubles. It is not an
            // admissible exact score bound under the canonical rational contract.
            features = features.withoutScoreUpperBound();
            pivotLimitL = UNBOUNDED;
            connectorLimitKc = UNBOUNDED;
            frontierLimitKf = UNBOUNDED;
            connectorExpansionCapMc = UNBOUNDED_WORK;
            breakpointCapMb = UNBOUNDED;
            queryWorkCapMq = UNBOUNDED_WORK;
        } else {
            if (pivotLimitL < 0) {
                throw new IllegalArgumentException(
                        "PACE-B pivot limit L cannot be negative");
            }
            if (connectorLimitKc < 1) {
                throw new IllegalArgumentException(
                        "PACE-B connector limit K_c must be at least 1");
            }
            if (frontierLimitKf < 1) {
                throw new IllegalArgumentException(
                        "PACE-B frontier limit K_f must be at least 1");
            }
            if (connectorExpansionCapMc < 1) {
                throw new IllegalArgumentException(
                        "PACE-B connector cap M_c must be at least 1");
            }
            if (breakpointCapMb < 1) {
                throw new IllegalArgumentException(
                        "PACE-B breakpoint cap M_b must be at least 1");
            }
            if (queryWorkCapMq < 1) {
                throw new IllegalArgumentException(
                        "PACE-B query-work cap M_q must be at least 1");
            }
        }
    }

    /**
     * Source-compatible constructor for the previous shared-K option shape.
     *
     * <p>New production configuration must use the complete constructor. This
     * compatibility boundary maps the historical K to both K_c and K_f and
     * supplies conservative default safety caps.</p>
     */
    public PaceOptions(
            PaceExecutionPolicy policy,
            int theta,
            int anchorLimit,
            int frontierLimit,
            int threadCount,
            boolean memoizationEnabled,
            PaceFeatures features,
            int maxFrontierFragments) {
        this(
                policy,
                PaceEngineMode.SCALABLE,
                theta,
                anchorLimit,
                frontierLimit,
                frontierLimit,
                5_000_000L,
                1_000_000,
                250_000_000L,
                threadCount,
                memoizationEnabled,
                features,
                maxFrontierFragments);
    }

    /** Source-compatible constructor for finalized default features. */
    public PaceOptions(
            PaceExecutionPolicy policy,
            int theta,
            int anchorLimit,
            int frontierLimit,
            int threadCount,
            boolean memoizationEnabled) {
        this(
                policy,
                theta,
                anchorLimit,
                frontierLimit,
                threadCount,
                memoizationEnabled,
                PaceFeatures.defaults(),
                Integer.MAX_VALUE);
    }

    /** Exhaustive tiny-instance validation configuration. */
    public static PaceOptions exhaustive(int theta) {
        return new PaceOptions(
                PaceExecutionPolicy.PACE_X,
                PaceEngineMode.SCALABLE,
                theta,
                UNBOUNDED,
                UNBOUNDED,
                UNBOUNDED,
                UNBOUNDED_WORK,
                UNBOUNDED,
                UNBOUNDED_WORK,
                1,
                true,
                PaceFeatures.defaults(),
                Integer.MAX_VALUE);
    }

    /** Deterministically bounded compatibility factory with a shared K. */
    public static PaceOptions bounded(
            int theta,
            int pivotLimitL,
            int sharedK) {
        return new PaceOptions(
                PaceExecutionPolicy.PACE_B,
                theta,
                pivotLimitL,
                sharedK,
                1,
                true);
    }

    /** Complete deterministic bounded production configuration. */
    public static PaceOptions bounded(
            int theta,
            int pivotLimitL,
            int connectorLimitKc,
            long connectorExpansionCapMc,
            int frontierLimitKf,
            int breakpointCapMb,
            long queryWorkCapMq,
            int threadCount) {
        return new PaceOptions(
                PaceExecutionPolicy.PACE_B,
                PaceEngineMode.SCALABLE,
                theta,
                pivotLimitL,
                connectorLimitKc,
                frontierLimitKf,
                connectorExpansionCapMc,
                breakpointCapMb,
                queryWorkCapMq,
                threadCount,
                true,
                PaceFeatures.defaults(),
                Integer.MAX_VALUE);
    }

    /** Returns an otherwise identical option set using the requested engine. */
    public PaceOptions withEngineMode(PaceEngineMode mode) {
        return new PaceOptions(
                policy,
                mode,
                theta,
                pivotLimitL,
                connectorLimitKc,
                frontierLimitKf,
                connectorExpansionCapMc,
                breakpointCapMb,
                queryWorkCapMq,
                threadCount,
                memoizationEnabled,
                features,
                maxFrontierFragments);
    }

    /** Historical alias for L. */
    public int anchorLimit() {
        return pivotLimitL;
    }

    /** Historical alias for K_f. */
    public int frontierLimit() {
        return frontierLimitKf;
    }

    public int effectiveAnchorLimit() {
        return policy == PaceExecutionPolicy.PACE_X
                ? UNBOUNDED : pivotLimitL;
    }

    public int effectiveConnectorLimit() {
        return policy == PaceExecutionPolicy.PACE_X
                ? UNBOUNDED : connectorLimitKc;
    }

    public int effectiveFrontierLimit() {
        return policy == PaceExecutionPolicy.PACE_X
                ? UNBOUNDED : frontierLimitKf;
    }

    /** True when finalized PACE-B selects one static lower-bound witness. */
    public boolean singleLabelFastestConnectorEnabled() {
        return policy == PaceExecutionPolicy.PACE_B
                && engineMode == PaceEngineMode.SCALABLE
                && !features.connectorPortfolioEnabled();
    }

    /** Stable connector-policy identity serialized with experiment records. */
    public String connectorPolicyId() {
        if (policy == PaceExecutionPolicy.PACE_X) {
            return "PACE_X_EXHAUSTIVE_CONNECTORS_V1";
        }
        if (engineMode == PaceEngineMode.LEGACY) {
            return "PACE_B_LEGACY_RECURSIVE_CONNECTOR_ALTERNATIVES_DIAGNOSTIC_V1";
        }
        return features.connectorPortfolioEnabled()
                ? "PACE_B_LEGACY_CONNECTOR_PORTFOLIO_DIAGNOSTIC_V1"
                : "PACE_B_SINGLE_STATIC_LABEL_FASTEST_CONNECTOR_V1";
    }

    /**
     * Historical source-compatibility accessor. The finalized connector is
     * selected from static lower-bound labels, never at a temporal midpoint.
     */
    @Deprecated
    public boolean singleMidpointFastestConnectorEnabled() {
        return false;
    }
}
