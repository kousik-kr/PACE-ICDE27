package edu.ipcmax.core.pcmax;

import java.util.Comparator;

/**
 * Deterministic result tie-breaking: max score, min travel time, earliest departure, lexicographic arc ids.
 */
public final class ResultComparator implements Comparator<IPCMaxResult> {
    /**
     * Shared comparator instance.
     */
    public static final ResultComparator INSTANCE = new ResultComparator();

    private ResultComparator() {
    }

    @Override
    public int compare(IPCMaxResult left, IPCMaxResult right) {
        if (!left.found() && !right.found()) {
            return 0;
        }
        if (!left.found()) {
            return 1;
        }
        if (!right.found()) {
            return -1;
        }
        int scoreCompare = Integer.compare(right.score(), left.score());
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        int travelCompare = Double.compare(left.travelTime(), right.travelTime());
        if (travelCompare != 0) {
            return travelCompare;
        }
        int departureCompare = Integer.compare(left.departureTime(), right.departureTime());
        if (departureCompare != 0) {
            return departureCompare;
        }
        int size = Math.min(left.path().arcIds().size(), right.path().arcIds().size());
        for (int i = 0; i < size; i++) {
            int arcCompare = Integer.compare(left.path().arcIds().get(i), right.path().arcIds().get(i));
            if (arcCompare != 0) {
                return arcCompare;
            }
        }
        return Integer.compare(left.path().arcIds().size(), right.path().arcIds().size());
    }
}
