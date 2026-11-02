package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePrefixProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceTransferProfile;
import edu.ipcmax.core.validate.Path;

/**
 * Exact source-root evaluation of a complete EGPI route on one departure chunk.
 *
 * <p>The route is replayed element by element (connector, pivot, connector,
 * ...). Each element's transfer is fetched lazily from
 * {@link LazyConnectorProfileCache} on the exact image of the current prefix
 * over the chunk, then composed onto the prefix. A source-root prefix state
 * depends only on the departure chunk and the structural elements before it,
 * so prefix states are cached under exactly that key and reused by later
 * insertions that leave the prefix unchanged. Suffix elements are never
 * reused from another route's source-root state: after an insertion they are
 * recomposed from their entry-time transfers on the new entry domain.</p>
 */
final class EgpiRouteEvaluator {
    private record PrefixKey(ExactTemporalDomain departures, List<List<Integer>> elements) {
        PrefixKey {
            List<List<Integer>> copied = new ArrayList<>(elements.size());
            for (List<Integer> element : elements) copied.add(List.copyOf(element));
            elements = List.copyOf(copied);
        }
    }

    /** One completed (or empty) exact unit and the reuse it achieved. */
    record Evaluation(Optional<ExactPreferencePathProfile> profile, int reusedElements,
            int composedElements, long nanos) { }

    private final LazyConnectorProfileCache transfers;
    private final BoundedExactCache<PrefixKey, ExactPreferencePrefixProfile> prefixes;
    private final TreeMap<String, Long> counters = new TreeMap<>();

    EgpiRouteEvaluator(LazyConnectorProfileCache transfers, int prefixCapacity, long prefixBytes) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.prefixes = new BoundedExactCache<>(prefixCapacity, prefixBytes,
                ExactCacheSupport::payloadBytes, null);
        for (String name : List.of("exact_units_evaluated", "exact_units_empty", "prefix_state_cache_hits",
                "prefix_elements_reused", "prefix_elements_composed", "prefix_state_single_flight_waits",
                "exact_unit_ns", "route_full_prefix_reuses")) counters.put(name, 0L);
    }

    Evaluation evaluate(EgpiRoute route, ExactTemporalDomain chunk, ExactTime budget, BooleanSupplier cancel) {
        long started = System.nanoTime();
        List<List<Integer>> elements = elements(route);
        ExactPreferencePrefixProfile state = null;
        int reused = 0;
        for (int length = elements.size(); length >= 1; length--) {
            ExactCacheSupport.check(cancel);
            ExactPreferencePrefixProfile cached = prefixes.getIfPresent(
                    new PrefixKey(chunk, elements.subList(0, length)));
            if (cached != null) {
                state = cached;
                reused = length;
                break;
            }
        }
        if (state == null) state = ExactPreferencePrefixProfile.identity(chunk, cancel);
        int composed = 0;
        int waits = 0;
        for (int index = reused; index < elements.size(); index++) {
            ExactCacheSupport.check(cancel);
            final ExactPreferencePrefixProfile before = state;
            final int element = index;
            BoundedExactCache.Lookup<ExactPreferencePrefixProfile> lookup = prefixes.get(
                    new PrefixKey(chunk, elements.subList(0, index + 1)),
                    () -> before.then(transfer(route, element, before, cancel), cancel), cancel);
            state = lookup.value();
            if (lookup.outcome() == BoundedExactCache.Outcome.COMPUTED) composed++;
            else waits++;
        }
        ExactCacheSupport.check(cancel);
        Optional<ExactPreferencePathProfile> profile = state.complete(
                new Path(route.completeEdgeSequence()), budget, cancel);
        long nanos = System.nanoTime() - started;
        synchronized (this) {
            add("exact_units_evaluated", 1);
            if (profile.isEmpty() || profile.get().feasibleDomain().isEmpty()) add("exact_units_empty", 1);
            if (reused > 0) add("prefix_state_cache_hits", 1);
            if (reused == elements.size()) add("route_full_prefix_reuses", 1);
            add("prefix_elements_reused", reused);
            add("prefix_elements_composed", composed);
            add("prefix_state_single_flight_waits", waits);
            add("exact_unit_ns", nanos);
        }
        return new Evaluation(profile, reused, composed, nanos);
    }

    /** Lazily obtains the element's transfer on the exact entry domain of {@code prefix}. */
    private ExactPreferenceTransferProfile transfer(EgpiRoute route, int element,
            ExactPreferencePrefixProfile prefix, BooleanSupplier cancel) {
        ExactTemporalDomain entry = prefix.requiredEntryDomain(cancel);
        if (element % 2 == 0) {
            return transfers.getExactProfile(route.orderedConnectors().get(element / 2), entry, cancel);
        }
        return transfers.getExactEdgeProfile(route.orderedPivotEdges().get(element / 2), entry, cancel);
    }

    private static List<List<Integer>> elements(EgpiRoute route) {
        List<List<Integer>> result = new ArrayList<>();
        for (int i = 0; i < route.orderedConnectors().size(); i++) {
            result.add(route.orderedConnectors().get(i).directedEdgeIds());
            if (i < route.orderedPivotEdges().size()) result.add(List.of(route.orderedPivotEdges().get(i)));
        }
        return result;
    }

    private void add(String name, long amount) { counters.merge(name, amount, Long::sum); }

    Map<String, Long> stats() {
        TreeMap<String, Long> result;
        synchronized (this) { result = new TreeMap<>(counters); }
        result.put("prefix_state_cache_entries", (long) prefixes.size());
        result.put("prefix_state_cache_payload_bytes", prefixes.bytes());
        result.put("prefix_state_cache_peak_payload_bytes", prefixes.peakBytes());
        result.put("prefix_state_cache_evictions", prefixes.evictions());
        result.put("prefix_state_cache_oversize_not_cached", prefixes.oversize());
        return Map.copyOf(result);
    }
}
