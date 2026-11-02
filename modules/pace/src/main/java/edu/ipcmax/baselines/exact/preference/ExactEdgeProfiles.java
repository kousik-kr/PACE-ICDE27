package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.core.graph.Edge;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Exact arrival and score profiles of single edges, built once and shared. Both are pure functions
 * of the edge and immutable, so a shared instance cannot differ from a fresh one; the cache only
 * removes the repeated construction of identical rational pieces when routes share edges. It is
 * bounded: when full it is cleared, which only costs recomputation. An entry is used only for the
 * very edge object it was built from.
 */
final class ExactEdgeProfiles {
    private static final int CAPACITY = 16_384;
    private static final ConcurrentHashMap<Integer, Profiles> CACHE = new ConcurrentHashMap<>();

    record Profiles(Edge edge, ExactArrivalProfile arrival, ExactScoreProfile score) { }

    private ExactEdgeProfiles() { }

    static Profiles of(Edge edge, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(edge, "edge");
        Profiles cached = CACHE.get(edge.arcId());
        if (cached != null && cached.edge() == edge) return cached;
        Profiles built = new Profiles(edge, ExactArrivalProfile.edgeArrival(edge, cancellationRequested),
                ExactScoreProfile.edgeScore(edge, cancellationRequested));
        if (CACHE.size() >= CAPACITY) CACHE.clear();
        CACHE.put(edge.arcId(), built);
        return built;
    }

    static void clear() { CACHE.clear(); }
}
