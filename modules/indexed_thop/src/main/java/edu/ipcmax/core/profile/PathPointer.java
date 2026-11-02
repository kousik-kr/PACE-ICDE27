package edu.ipcmax.core.profile;

import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lazy path pointer used by candidate profiles.
 */
public interface PathPointer {
    /**
     * Numeric lexicographic ordering used for stable path identifiers.
     */
    Comparator<List<Integer>> STABLE_PATH_ORDER = (left, right) -> {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    };

    /**
     * Materializes ordered arc ids.
     */
    List<Integer> arcIds();

    /**
     * Stable path identifier. Parallel arcs remain distinct because the identifier is the
     * complete ordered sequence of stable arc ids.
     */
    default List<Integer> stablePathId() {
        return List.copyOf(arcIds());
    }

    /**
     * Number of arcs in this path.
     */
    default int edgeCount() {
        return arcIds().size();
    }

    /**
     * Materializes a {@link Path}.
     */
    default Path toPath() {
        return new Path(arcIds());
    }

    /**
     * Empty path pointer.
     */
    static PathPointer empty() {
        return List::of;
    }

    /**
     * Single arc pointer.
     */
    static PathPointer arc(int arcId) {
        return () -> List.of(arcId);
    }

    /**
     * Pointer backed by an immutable arc-id sequence.
     */
    static PathPointer of(List<Integer> arcIds) {
        List<Integer> copy = List.copyOf(arcIds);
        return () -> copy;
    }

    /**
     * Concatenates path pointers without exposing mutable state.
     */
    static PathPointer concat(PathPointer... pointers) {
        if (pointers == null || pointers.length == 0) {
            return empty();
        }
        PathPointer result = Objects.requireNonNull(
                pointers[0], "path pointer");
        for (int index = 1; index < pointers.length; index++) {
            result = new CompositePathPointer(
                    result,
                    Objects.requireNonNull(
                            pointers[index], "path pointer"));
        }
        return result;
    }

    /**
     * Persistent predecessor pair. The complete arc sequence is materialized
     * lazily and cached only when a stable ID or final replay needs it.
     */
    final class CompositePathPointer implements PathPointer {
        private final PathPointer prefix;
        private final PathPointer suffix;
        private final int edgeCount;
        private volatile List<Integer> materialized;

        CompositePathPointer(
                PathPointer prefix,
                PathPointer suffix) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.edgeCount = Math.addExact(
                    prefix.edgeCount(), suffix.edgeCount());
        }

        @Override
        public List<Integer> arcIds() {
            List<Integer> value = materialized;
            if (value == null) {
                List<Integer> combined = new ArrayList<>(edgeCount);
                combined.addAll(prefix.arcIds());
                combined.addAll(suffix.arcIds());
                value = List.copyOf(combined);
                materialized = value;
            }
            return value;
        }

        @Override
        public int edgeCount() {
            return edgeCount;
        }
    }
}
