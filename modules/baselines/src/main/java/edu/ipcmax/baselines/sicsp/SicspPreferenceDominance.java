package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.baselines.exact.ExactTime;

import java.math.BigInteger;

/** Preference-direction counterparts to native SICSP's arrival/weight tests. */
public final class SicspPreferenceDominance {
    private SicspPreferenceDominance() {
    }

    /** O-point rule: both better arrival and strictly larger preference are required. */
    public static boolean strictlyDominates(
            double otherArrival,
            int otherScore,
            double candidateArrival,
            int candidateScore) {
        return otherArrival < candidateArrival && otherScore > candidateScore;
    }

    /** W-point skyline: earlier arrival and larger score, with one strict improvement. */
    public static boolean skylineDominates(
            double otherArrival,
            int otherScore,
            double candidateArrival,
            int candidateScore) {
        return otherArrival <= candidateArrival
                && otherScore >= candidateScore
                && (otherArrival < candidateArrival || otherScore > candidateScore);
    }

    /** Exact rational counterpart used by the SICSP-Pref adapted event filter. */
    public static boolean strictlyDominates(
            ExactTime otherArrival, BigInteger otherScore,
            ExactTime candidateArrival, BigInteger candidateScore) {
        return otherArrival.compareTo(candidateArrival) < 0 && otherScore.compareTo(candidateScore) > 0;
    }

    /** Exact rational counterpart used by the SICSP-Pref adapted event filter. */
    public static boolean skylineDominates(
            ExactTime otherArrival, BigInteger otherScore,
            ExactTime candidateArrival, BigInteger candidateScore) {
        int arrival = otherArrival.compareTo(candidateArrival);
        int score = otherScore.compareTo(candidateScore);
        return arrival <= 0 && score >= 0 && (arrival < 0 || score > 0);
    }
}
