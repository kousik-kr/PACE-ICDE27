package edu.ipcmax.core.pcmax;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Immutable record of every cap reached during one query. */
public record PaceCapStatus(
        Set<PaceCapKind> triggered,
        String firstCanonicalWorkItem) {
    public PaceCapStatus {
        EnumSet<PaceCapKind> copy = triggered == null || triggered.isEmpty()
                ? EnumSet.noneOf(PaceCapKind.class)
                : EnumSet.copyOf(triggered);
        triggered = Collections.unmodifiableSet(copy);
        firstCanonicalWorkItem = firstCanonicalWorkItem == null
                ? "" : firstCanonicalWorkItem;
    }

    public static PaceCapStatus none() {
        return new PaceCapStatus(Set.of(), "");
    }

    public boolean any() {
        return !triggered.isEmpty();
    }

    public boolean reached(PaceCapKind kind) {
        return triggered.contains(kind);
    }
}
