package edu.ipcmax.baselines.exact.preference;

import java.util.Locale;

/**
 * Selects how a complete path's exact transfer is built. Both engines produce the same exact
 * piecewise functions; they differ in the order of the composition and in how much work is shared.
 *
 * <p>{@code SEQUENTIAL_V1} is the reference edge-by-edge replay. {@code BALANCED_V1} composes
 * {@code leafEdges}-edge segments, each replayed on a conservative exact entry band, pairwise in a
 * balanced tree; see {@link ExactBalancedReplay}. The selection is process wide so that runs of one
 * executable can be compared; the default is the sequential engine.</p>
 */
public final class ExactReplayEngine {
    public enum Mode { SEQUENTIAL_V1, BALANCED_V1 }

    private static volatile Mode mode = parse(System.getProperty("ipcmax.exact.replay_engine", "sequential"));
    private static volatile int leafEdges = Integer.getInteger("ipcmax.exact.balanced_leaf_edges", 8);
    private static volatile int minimumArcs = Integer.getInteger("ipcmax.exact.balanced_minimum_arcs", 16);

    private ExactReplayEngine() { }

    public static Mode mode() { return mode; }
    public static int leafEdges() { return leafEdges; }
    public static int minimumArcs() { return minimumArcs; }

    public static void configure(Mode selected) { mode = selected; }

    public static void configure(Mode selected, int leaf, int minimum) {
        if (leaf < 1 || minimum < 2) throw new IllegalArgumentException("invalid balanced replay parameters");
        mode = selected;
        leafEdges = leaf;
        minimumArcs = minimum;
    }

    public static Mode parse(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "sequential", "sequential_v1" -> Mode.SEQUENTIAL_V1;
            case "balanced", "balanced_v1" -> Mode.BALANCED_V1;
            default -> throw new IllegalArgumentException("unknown exact replay engine: " + name);
        };
    }
}
