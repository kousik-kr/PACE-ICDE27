package edu.ipcmax.baselines.exact.preference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.framework.ProfileSupport;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Shared exact objective evaluation and serialization for preference algorithms. */
public final class ExactPreferenceObjective {
    public static final String OBJECTIVE = "PREFERENCE_MAX_PROFILE";
    public static final String CONTRACT = "EXACT_RATIONAL_PREFERENCE_PROFILE-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExactPreferenceObjective() { }

    /**
     * Replays every route emitted by a search and builds the authoritative exact envelope.
     * Route IDs are directed arc-ID sequences and are retained in the typed profile.
     */
    public static Evaluation evaluate(
            TDGraph graph,
            QuerySpec query,
            Collection<Path> emittedRoutes,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(emittedRoutes, "emittedRoutes");
        ExactTemporalDomain domain = ExactTemporalDomain.fromLegacy(query.departureDomain());
        ExactTime budget = ExactTime.fromCanonicalDouble(
                Domain.canonicalTime(query.maxTravelTime()));
        Map<Path, ExactPreferencePathProfile> profiles = new LinkedHashMap<>();
        emittedRoutes.stream().distinct()
                .sorted(Comparator.comparing(path -> path.arcIds().toString()))
                .forEach(path -> ExactPreferenceReplay.replayExact(
                        graph, query.source(), query.destination(), path, domain, budget)
                        .ifPresent(profile -> profiles.put(path, profile)));
        ExactPreferenceEnvelope envelope = ExactPreferenceEnvelope.overRetained(domain, profiles);
        String exactJson = ExactPreferenceProfileCodec.encode(envelope);
        Map<String, Object> result = summarize(envelope, domain, endToEndNanos,
                completionStatus, exactnessScope, registryScope, emittedRoutes, retained(profiles), exactJson);
        return new Evaluation(envelope, Map.copyOf(profiles), exactJson, result, true);
    }

    /**
     * Cancellable route-by-route replay. Each accepted exact route is committed only
     * after its exact envelope update finishes, so a deadline returns the last whole
     * envelope over fully replayed routes.
     */
    public static Evaluation evaluateIncrementally(
            TDGraph graph,
            QuerySpec query,
            Collection<Path> emittedRoutes,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(emittedRoutes, "emittedRoutes");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        ExactTemporalDomain domain;
        ExactTime budget;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_budget_domain_preparation")) {
            domain = ExactTemporalDomain.fromLegacy(query.departureDomain());
            budget = ExactTime.fromCanonicalDouble(
                    Domain.canonicalTime(query.maxTravelTime()));
        }
        List<Path> orderedRoutes;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_candidate_deduplication")) {
            ExactArithmeticDiagnostics.add(
                    "exact_complete_candidates_emitted", emittedRoutes.size());
            orderedRoutes = emittedRoutes.stream().distinct()
                    .sorted(Comparator.comparing(path -> path.arcIds().toString())).toList();
            ExactArithmeticDiagnostics.add(
                    "exact_complete_candidates_after_deduplication",
                    orderedRoutes.size());
            recordRepeatedPathPrefixes(orderedRoutes);
        }
        Map<Path, ExactPreferencePathProfile> committedProfiles = new LinkedHashMap<>();
        ExactPreferenceEnvelope committedEnvelope = ExactPreferenceEnvelope.overRetained(domain, Map.of());
        boolean complete = true;
        long exactReplayNanos = 0;
        long exactEnvelopeNanos = 0;
        for (Path path : orderedRoutes) {
            if (cancellationRequested.getAsBoolean()) {
                complete = false;
                break;
            }
            final java.util.Optional<ExactPreferencePathProfile> replayed;
            long replayStarted = System.nanoTime();
            try {
                ExactArithmeticDiagnostics.increment(
                        "exact_candidate_replays_started");
                try (var ignored = ExactArithmeticDiagnostics.phase(
                        "exact_candidate_replay")) {
                    replayed = ExactPreferenceReplay.replayExact(
                            graph, query.source(), query.destination(), path, domain, budget,
                            cancellationRequested);
                }
            } catch (CancellationException cancelled) {
                exactReplayNanos += System.nanoTime() - replayStarted;
                complete = false;
                break;
            }
            exactReplayNanos += System.nanoTime() - replayStarted;
            ExactArithmeticDiagnostics.increment(
                    "exact_candidate_replays_finished");
            if (replayed.isEmpty()) continue;
            Map<Path, ExactPreferencePathProfile> staged = new LinkedHashMap<>(committedProfiles);
            staged.put(path, replayed.orElseThrow());
            if (!replayed.orElseThrow().feasibleDomain().isEmpty()) {
                long envelopeStarted = System.nanoTime();
                try {
                    ExactPreferenceEnvelope next;
                    try (var ignored = ExactArithmeticDiagnostics.phase(
                            "exact_envelope_update")) {
                        next = ExactPreferenceEnvelope.overRetained(
                                domain, staged, cancellationRequested);
                    }
                    committedProfiles = staged;
                    committedEnvelope = next;
                } catch (CancellationException cancelled) {
                    exactEnvelopeNanos += System.nanoTime() - envelopeStarted;
                    complete = false;
                    break;
                }
                exactEnvelopeNanos += System.nanoTime() - envelopeStarted;
            } else {
                committedProfiles = staged;
            }
        }
        String exactJson;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_final_profile_serialization")) {
            exactJson = ExactPreferenceProfileCodec.encode(committedEnvelope);
        }
        Map<String, Object> result;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_objective_summary")) {
            result = summarize(committedEnvelope, domain, endToEndNanos,
                    completionStatus, exactnessScope, registryScope, orderedRoutes,
                    retained(committedProfiles), exactJson);
        }
        result.put("exact_destination_replay_nanos", exactReplayNanos);
        result.put("exact_envelope_construction_nanos", exactEnvelopeNanos);
        return new Evaluation(committedEnvelope, committedProfiles, exactJson, result, complete);
    }

    /**
     * Serializes an already committed exact registry/envelope without replaying routes.
     * The caller owns validation and atomic publication of the pair; this does not
     * promote bounded candidate generation to a global certificate.
     */
    public static Evaluation fromValidated(
            ExactPreferenceEnvelope envelope,
            Map<Path, ExactPreferencePathProfile> profiles,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope,
            boolean evaluationComplete) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(profiles, "profiles");
        for (var entry : profiles.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().path())) {
                throw new IllegalArgumentException("registry key does not match validated route");
            }
        }
        for (var cell : envelope.cells()) {
            if (cell.found() && !profiles.containsKey(cell.path())) {
                throw new IllegalArgumentException("envelope winner is absent from validated registry");
            }
        }
        long encodeStarted = System.nanoTime();
        String encoded = ExactPreferenceProfileCodec.encode(envelope);
        ExactArithmeticDiagnostics.add("exact_objective_encode_ns", System.nanoTime() - encodeStarted);
        long summaryStarted = System.nanoTime();
        Map<String, Object> summary = summarize(envelope, envelope.domain(), endToEndNanos,
                completionStatus, exactnessScope, registryScope,
                profiles.keySet(), retained(profiles), encoded);
        ExactArithmeticDiagnostics.add("exact_objective_summarize_ns", System.nanoTime() - summaryStarted);
        return new Evaluation(envelope, profiles, encoded, summary, evaluationComplete);
    }

    /**
     * Serializes an already committed exact envelope for a registry given only by its routes, every one of which
     * is feasible somewhere. No route profile is read, so no per-route profile has to be built; the result has
     * an empty {@link Evaluation#routeProfiles()}. The caller owns validation and atomic publication.
     */
    public static Evaluation fromValidatedRoutes(
            ExactPreferenceEnvelope envelope,
            Collection<Path> feasibleRoutes,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope,
            boolean evaluationComplete) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(feasibleRoutes, "feasibleRoutes");
        java.util.Set<Path> known = new java.util.HashSet<>(feasibleRoutes);
        for (var cell : envelope.cells()) {
            if (cell.found() && !known.contains(cell.path())) {
                throw new IllegalArgumentException("envelope winner is absent from validated registry");
            }
        }
        long encodeStarted = System.nanoTime();
        String encoded = ExactPreferenceProfileCodec.encode(envelope);
        ExactArithmeticDiagnostics.add("exact_objective_encode_ns", System.nanoTime() - encodeStarted);
        long summaryStarted = System.nanoTime();
        Map<String, Object> summary = summarize(envelope, envelope.domain(), endToEndNanos,
                completionStatus, exactnessScope, registryScope, feasibleRoutes, feasibleRoutes, encoded);
        ExactArithmeticDiagnostics.add("exact_objective_summarize_ns", System.nanoTime() - summaryStarted);
        return new Evaluation(envelope, Map.of(), encoded, summary, evaluationComplete);
    }

    private static List<Path> retained(Map<Path, ExactPreferencePathProfile> profiles) {
        return profiles.values().stream().filter(profile -> !profile.feasibleDomain().isEmpty())
                .map(ExactPreferencePathProfile::path).toList();
    }

    private static void recordRepeatedPathPrefixes(List<Path> routes) {
        Map<List<Integer>, Integer> occurrences = new java.util.HashMap<>();
        long total = 0;
        for (Path route : routes) {
            List<Integer> arcIds = route.arcIds();
            for (int length = 1; length <= arcIds.size(); length++) {
                occurrences.merge(List.copyOf(arcIds.subList(0, length)), 1, Integer::sum);
                total++;
            }
        }
        long unique = occurrences.size();
        long repeated = total - unique;
        ExactArithmeticDiagnostics.add("exact_path_prefix_replay_occurrences", total);
        ExactArithmeticDiagnostics.add("exact_unique_path_prefixes", unique);
        ExactArithmeticDiagnostics.add("exact_repeated_path_prefix_recomputations", repeated);
    }

    public record Evaluation(
            ExactPreferenceEnvelope profile,
            Map<Path, ExactPreferencePathProfile> routeProfiles,
            String serializedProfile,
            Map<String, Object> objectiveResult,
            boolean evaluationComplete) {
        public Evaluation {
            Objects.requireNonNull(profile, "profile");
            routeProfiles = Map.copyOf(routeProfiles);
            Objects.requireNonNull(serializedProfile, "serializedProfile");
            objectiveResult = Collections.unmodifiableMap(new LinkedHashMap<>(objectiveResult));
        }
    }

    private static Map<String, Object> summarize(
            ExactPreferenceEnvelope envelope,
            ExactTemporalDomain domain,
            long endToEndNanos,
            String completionStatus,
            String exactnessScope,
            String registryScope,
            Collection<Path> emittedRoutes,
            Collection<Path> retainedRoutes,
            String exactJson) {
        long sumsStarted = System.nanoTime();
        ExactTime totalMeasure = measure(domain);
        ExactTime feasibleMeasure = ExactTime.ZERO;
        ExactTime integratedPreference = ExactTime.ZERO;
        // Exact sums over runs, not cells: the measure of a run of contiguous found cells is one difference of
        // its end points, and the integral of a run of equal score is that score times one difference. The
        // values are those of the cell-by-cell sums; far fewer big rational operations produce them.
        ExactTime coverStart = null, coverEnd = null, scoreStart = null, scoreEnd = null;
        BigInteger runScore = null;
        for (ExactPreferenceEnvelope.Cell cell : envelope.cells()) {
            ExactTemporalDomain.Interval interval = cell.interval();
            if (interval.end().equals(interval.start())) continue; // a point has no length and joins its neighbours
            if (!cell.found()) {
                if (coverStart != null) feasibleMeasure = feasibleMeasure.add(coverEnd.subtract(coverStart));
                if (scoreStart != null) {
                    integratedPreference = integratedPreference.add(
                            scoreEnd.subtract(scoreStart).multiply(ExactTime.of(runScore, BigInteger.ONE)));
                }
                coverStart = coverEnd = scoreStart = scoreEnd = null;
                runScore = null;
                continue;
            }
            if (coverStart != null && coverEnd.equals(interval.start())) {
                coverEnd = interval.end();
            } else {
                if (coverStart != null) feasibleMeasure = feasibleMeasure.add(coverEnd.subtract(coverStart));
                coverStart = interval.start();
                coverEnd = interval.end();
            }
            if (scoreStart != null && scoreEnd.equals(interval.start()) && runScore.equals(cell.score())) {
                scoreEnd = interval.end();
            } else {
                if (scoreStart != null) {
                    integratedPreference = integratedPreference.add(
                            scoreEnd.subtract(scoreStart).multiply(ExactTime.of(runScore, BigInteger.ONE)));
                }
                scoreStart = interval.start();
                scoreEnd = interval.end();
                runScore = cell.score();
            }
        }
        if (coverStart != null) feasibleMeasure = feasibleMeasure.add(coverEnd.subtract(coverStart));
        if (scoreStart != null) {
            integratedPreference = integratedPreference.add(
                    scoreEnd.subtract(scoreStart).multiply(ExactTime.of(runScore, BigInteger.ONE)));
        }
        ExactTime coverage = totalMeasure.signum() == 0
                ? (allSingletonsSelected(domain, envelope) ? ExactTime.ONE : ExactTime.ZERO)
                : feasibleMeasure.divide(totalMeasure);
        ExactTime average = feasibleMeasure.signum() == 0
                ? ExactTime.ZERO : integratedPreference.divide(feasibleMeasure);
        ExactArithmeticDiagnostics.add("exact_objective_exact_sums_ns", System.nanoTime() - sumsStarted);

        long switches = 0;
        List<Integer> previous = null;
        boolean hadPrevious = false;
        for (ExactPreferenceEnvelope.Cell cell : envelope.cells()) {
            List<Integer> current = cell.found() ? cell.path().arcIds() : null;
            if (hadPrevious && !Objects.equals(previous, current)) switches++;
            previous = current;
            hadPrevious = true;
        }
        List<List<Integer>> selectedRoutes = envelope.cells().stream()
                .filter(ExactPreferenceEnvelope.Cell::found)
                .map(cell -> cell.path().arcIds()).distinct().toList();
        List<List<Integer>> registry = emittedRoutes.stream().distinct()
                .sorted(Comparator.comparing(path -> path.arcIds().toString()))
                .map(Path::arcIds).toList();
        List<List<Integer>> retainedRegistry = retainedRoutes.stream()
                .map(Path::arcIds).distinct().toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objective", OBJECTIVE);
        result.put("numerical_contract", CONTRACT);
        result.put("completion_status", completionStatus);
        result.put("exactness_scope", exactnessScope);
        result.put("route_registry_scope", registryScope);
        result.put("exact_profile_schema", ExactPreferenceProfileCodec.SCHEMA);
        result.put("exact_profile_checksum", ProfileSupport.sha256(exactJson));
        long parseStarted = System.nanoTime();
        result.put("profile", parseProfile(exactJson));
        ExactArithmeticDiagnostics.add("exact_objective_parse_ns", System.nanoTime() - parseStarted);
        result.put("generated_route_registry", registry);
        result.put("emitted_route_registry", registry);
        result.put("retained_route_registry", retainedRegistry);
        result.put("selected_route_registry", selectedRoutes);
        result.put("selected_route_identity", selectedRoutes.size() == 1 ? selectedRoutes.getFirst() : null);
        result.put("feasible_departure_coverage_exact", coverage.toString());
        result.put("feasible_departure_measure_exact", feasibleMeasure.toString());
        result.put("integrated_preference_exact", integratedPreference.toString());
        result.put("average_selected_preference_exact", feasibleMeasure.signum() == 0 ? null : average.toString());
        result.put("feasible_coverage_fraction", decimal(coverage));
        result.put("integrated_preference", decimal(integratedPreference));
        result.put("average_selected_preference", feasibleMeasure.signum() == 0 ? null : decimal(average));
        result.put("average_selected_score", feasibleMeasure.signum() == 0 ? null : decimal(average));
        result.put("final_profile_intervals", envelope.cells().size());
        result.put("elementary_envelope_cells", envelope.cells().size());
        result.put("distinct_selected_paths", selectedRoutes.size());
        result.put("path_changes", switches);
        result.put("path_switch_count", switches);
        result.put("profile_cell_count", envelope.cells().size());
        result.put("end_to_end_query_nanos", Math.max(0L, endToEndNanos));
        result.put("feasible", envelope.hasFeasibleCell());
        result.put("profile_checksum", ProfileSupport.sha256(exactJson));
        return result;
    }

    private static boolean allSingletonsSelected(
            ExactTemporalDomain domain, ExactPreferenceEnvelope envelope) {
        if (domain.isEmpty()) return false;
        return domain.intervals().stream().allMatch(interval ->
                interval.start().equals(interval.end())
                        && envelope.cellAt(interval.start()).map(ExactPreferenceEnvelope.Cell::found)
                                .orElse(false));
    }

    private static ExactTime measure(ExactTemporalDomain domain) {
        ExactTime result = ExactTime.ZERO;
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            result = result.add(interval.end().subtract(interval.start()));
        }
        return result;
    }

    private static Double decimal(ExactTime value) {
        return new BigDecimal(value.numerator()).divide(
                new BigDecimal(value.denominator()), MathContext.DECIMAL128).doubleValue();
    }

    private static JsonNode parseProfile(String exactJson) {
        try {
            return JSON.readTree(exactJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException malformed) {
            throw new IllegalStateException("exact preference codec emitted invalid JSON", malformed);
        }
    }
}
