package edu.ipcmax.core.pcmax.egpi;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.core.validate.Path;

/**
 * The validated registry as an immutable view whose per-route profile (the union of the route's committed
 * chunk entries) is built the first time it is asked for. Building one sorts and validates every piece of
 * the route, which for long routes with 8,800-bit coefficients costs more than assembling the answer, so
 * the answer only reads the route set and a profile is merged only for a caller that wants its pieces. The
 * merged profile is the same value whether it is built early or late.
 */
final class LazyRegistry extends AbstractMap<Path, ExactPreferencePathProfile> {
    private final Map<Path, List<ExactPreferencePathProfile>> parts;
    private final BiFunction<Path, List<ExactPreferencePathProfile>, ExactPreferencePathProfile> merger;
    private final ConcurrentHashMap<Path, ExactPreferencePathProfile> merged = new ConcurrentHashMap<>();

    LazyRegistry(Map<Path, List<ExactPreferencePathProfile>> parts,
            BiFunction<Path, List<ExactPreferencePathProfile>, ExactPreferencePathProfile> merger) {
        this.parts = parts;
        this.merger = merger;
    }

    /** The routes and their committed chunk entries, in commit order; no merging. */
    Map<Path, List<ExactPreferencePathProfile>> parts() { return parts; }

    @Override public int size() { return parts.size(); }
    @Override public boolean containsKey(Object key) { return parts.containsKey(key); }

    @Override public ExactPreferencePathProfile get(Object key) {
        List<ExactPreferencePathProfile> chunks = parts.get(key);
        if (chunks == null) return null;
        return merged.computeIfAbsent((Path) key, route -> merger.apply(route, chunks));
    }

    @Override public Set<Entry<Path, ExactPreferencePathProfile>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return parts.size(); }

            @Override public Iterator<Entry<Path, ExactPreferencePathProfile>> iterator() {
                Iterator<Path> routes = parts.keySet().iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return routes.hasNext(); }

                    @Override public Entry<Path, ExactPreferencePathProfile> next() {
                        if (!routes.hasNext()) throw new NoSuchElementException();
                        Path route = routes.next();
                        return new Entry<>() {
                            @Override public Path getKey() { return route; }
                            @Override public ExactPreferencePathProfile getValue() { return get(route); }
                            @Override public ExactPreferencePathProfile setValue(ExactPreferencePathProfile value) {
                                throw new UnsupportedOperationException("the registry is immutable");
                            }
                        };
                    }
                };
            }
        };
    }
}
