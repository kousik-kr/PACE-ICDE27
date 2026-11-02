package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;

import java.util.function.BooleanSupplier;

/**
 * An exact preference envelope maintained over an append-only set of validated entries. Every
 * implementation has, after any sequence of adds, the cells of
 * {@link ExactPreferenceEnvelope#overRetained} over the entries added so far, whatever the order they
 * were added in. An add is atomic: if it is cancelled, the previous state is unchanged.
 */
public interface ExactEnvelopeBuilder {
    IncrementalExactPreferenceEnvelope.Update add(ExactPreferencePathProfile entry, BooleanSupplier cancellationRequested);

    /** Immutable, validated envelope over every entry added so far. */
    ExactPreferenceEnvelope snapshot();

    /**
     * The same winner function with adjacent cells of one winner merged ({@link ExactPreferenceEnvelope#canonicalized}),
     * built without materializing the refined cells where the implementation can avoid it.
     */
    default ExactPreferenceEnvelope canonicalSnapshot() { return snapshot().canonicalized(); }

    ExactTemporalDomain requestedDomain();
    long version();
    int entryCount();
    long comparisons();
    /** Number of point and open cells currently in the envelope. */
    int cellCount();
    int cutCount();
}
