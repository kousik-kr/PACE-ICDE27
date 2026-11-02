package edu.ipcmax.core.pcmax.egpi;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.core.validate.Path;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Last committed EGPI answer. The registry contains only exactly validated
 * departure chunks; work in flight at the stop is never included.
 * {@code unresolvedDomain} is every requested departure without a validated
 * route: it is not a proof of infeasibility.
 */
public record EgpiResult(
        ExactPreferenceEnvelope envelope,
        Map<Path, ExactPreferencePathProfile> registry,
        Map<String, Long> counters,
        String stopReason,
        boolean deadlineReached,
        long elapsedNanos,
        long envelopeVersion,
        List<ExactPreferenceEnvelope> registryHistory,
        ExactTemporalDomain unresolvedDomain,
        List<String> capsTriggered,
        List<Map<String, Object>> routeTrace,
        EgpiOptions options,
        EgpiEngineOptions engine,
        EgpiAnytime.Timeline timeline,
        List<EgpiAnytime.UnitEvent> unitEvents,
        List<EgpiAnytime.EpochEvent> epochEvents,
        long originNanoTime) {
    public EgpiResult {
        registry = registry instanceof LazyRegistry ? registry : Collections.unmodifiableMap(new LinkedHashMap<>(registry));
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
        registryHistory = List.copyOf(registryHistory);
        capsTriggered = List.copyOf(capsTriggered);
        routeTrace = routeTrace instanceof LazyRouteTrace ? routeTrace : List.copyOf(routeTrace);
        unitEvents = List.copyOf(unitEvents);
        epochEvents = List.copyOf(epochEvents);
    }

    /** True when every requested departure has a validated, budget-feasible winner. */
    public boolean fullDomainCovered() {
        return unresolvedDomain.isEmpty() && envelope.hasFeasibleCell();
    }
}
