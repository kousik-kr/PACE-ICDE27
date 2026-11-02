package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTime;

/** Test access to the package-private rounding helpers of balanced replay. */
public final class ExactBalancedReplayProbe {
    private ExactBalancedReplayProbe() { }

    public static ExactTime floor(ExactTime value) { return ExactBalancedReplay.floorToGrid(value); }
    public static ExactTime ceil(ExactTime value) { return ExactBalancedReplay.ceilToGrid(value); }
}
