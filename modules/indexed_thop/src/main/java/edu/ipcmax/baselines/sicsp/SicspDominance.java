package edu.ipcmax.baselines.sicsp;

/** Dominance predicates used by original SICSP candidate validation. */
public final class SicspDominance {
    private SicspDominance() {
    }

    /** Algorithm 1, line 16: both arrival and weight inequalities are strict. */
    public static boolean strictlyDominates(double arrivalA, double weightA, double arrivalB, double weightB) {
        return arrivalA < arrivalB && weightA < weightB;
    }

    /** Conventional skyline dominance used for the paper's prose-only W-point filter. */
    public static boolean skylineDominates(double arrivalA, double weightA, double arrivalB, double weightB) {
        return arrivalA <= arrivalB && weightA <= weightB
                && (arrivalA < arrivalB || weightA < weightB);
    }
}
