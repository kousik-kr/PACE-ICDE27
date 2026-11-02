package edu.ipcmax.core.index;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.validate.Path;

/**
 * Admissible directed lower-bound routing interface used by query preparation.
 *
 * <p>The repository currently provides an exact Dijkstra implementation.
 * Large production datasets still need a separately verified scalable routing
 * index; callers must not relabel the fallback as such.</p>
 */
public interface LowerBoundOracle {
    /** Admissible, strictly positive lower-bound weight for a directed arc. */
    double edgeWeight(int arcId);

    /** Exact/admissible labels from a source over outgoing directed arcs. */
    Labels distancesFrom(int source);

    /**
     * Cancellation-aware forward labels. Implementations with an interruptible
     * search should override this method; the compatibility default still
     * checks cancellation at both call boundaries.
     */
    default Labels distancesFrom(
            int source,
            BooleanSupplier cancelled) {
        requireActive(cancelled);
        Labels labels = distancesFrom(source);
        requireActive(cancelled);
        return labels;
    }

    /** Exact/admissible labels to a target over incoming directed arcs. */
    Labels distancesTo(int target);

    /** Cancellation-aware reverse labels to one target. */
    default Labels distancesTo(
            int target,
            BooleanSupplier cancelled) {
        requireActive(cancelled);
        Labels labels = distancesTo(target);
        requireActive(cancelled);
        return labels;
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled == null) {
            throw new IllegalArgumentException(
                    "cancellation predicate is required");
        }
        if (cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "lower-bound oracle reached its query deadline");
        }
    }

    /** Immutable distance labels and deterministic path witnesses. */
    interface Labels {
        double distance(int node);

        boolean reached(int node);

        int edgeCount(int node);

        /**
         * Directed source-to-node path for forward labels, or node-to-target
         * path for reverse labels.
         */
        Path witnessPath(int node);
    }
}
