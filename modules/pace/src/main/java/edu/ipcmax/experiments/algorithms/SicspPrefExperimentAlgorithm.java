package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.sicsp.SicspPrefQuery;
import edu.ipcmax.baselines.sicsp.SicspPrefResult;
import edu.ipcmax.baselines.sicsp.SicspPreferenceEngine;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Research runner for Yuan et al.'s SICSP core with PACE preference semantics. */
public final class SicspPrefExperimentAlgorithm implements ExperimentAlgorithm {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return "sicsp-pref";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long endToEndStarted = System.nanoTime();
        SicspPrefResult result = new SicspPreferenceEngine(graph).query(new SicspPrefQuery(
                query.source(), query.destination(), query.departureDomain(), query.maxTravelTime()));
        var stats = result.statistics();
        instrumentation.setTiming("query_execution", stats.queryExecutionNanos());
        instrumentation.setTiming("candidate_generation",
                result.candidateGeneration().candidateGenerationNanos());
        instrumentation.setTiming("candidate_adaptation", stats.candidateAdaptationNanos());
        instrumentation.setTiming("profile_replay", stats.profileReplayNanos());
        instrumentation.setTiming("envelope_construction", stats.envelopeConstructionNanos());
        instrumentation.addCounter("edges_considered", stats.edgesConsidered());
        instrumentation.addCounter("forward_search_calls", stats.forwardSearchCalls());
        instrumentation.addCounter("reverse_search_calls", stats.reverseSearchCalls());
        instrumentation.addCounter("o_temporal_events", stats.oTemporalEvents());
        instrumentation.addCounter("w_temporal_events", stats.wTemporalEvents());
        instrumentation.addCounter("duplicate_candidate_events", stats.duplicateCandidateEvents());
        instrumentation.addCounter("raw_o_points", stats.rawOPoints());
        instrumentation.addCounter("raw_w_points", stats.rawWPoints());
        instrumentation.addCounter("candidates_removed_by_dominance", stats.candidatesRemovedByDominance());
        instrumentation.addCounter("non_simple_event_candidates_rejected",
                stats.nonSimpleEventCandidatesRejected());
        instrumentation.addCounter("distinct_non_simple_routes_rejected",
                stats.distinctNonSimpleRoutesRejected());
        instrumentation.addCounter("raw_candidate_routes", stats.rawCandidateRoutes());
        instrumentation.addCounter("simple_candidate_routes", stats.simpleCandidateRoutes());
        instrumentation.addCounter("out_of_query_candidate_routes", stats.outOfQueryCandidateRoutes());
        instrumentation.addCounter("retained_distinct_candidates", stats.retainedDistinctCandidates());
        instrumentation.addCounter("candidates_with_nonempty_feasible_domains",
                stats.candidatesWithNonemptyFeasibleDomains());
        instrumentation.addCounter("candidates_with_empty_feasible_domains",
                stats.candidatesWithEmptyFeasibleDomains());
        instrumentation.addCounter("profile_cells", stats.profileCellCount());
        instrumentation.addCounter("feasible_profile_cells", stats.feasibleProfileCellCount());
        instrumentation.addCounter("sampled_heap_bytes", stats.sampledHeapBytes());

        long serializationStarted = System.nanoTime();
        String exactJson = ExactPreferenceProfileCodec.encode(result.exactEnvelope());
        long serializationNanos = System.nanoTime() - serializationStarted;
        long serializedBytes = exactJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        instrumentation.setTiming("exact_profile_serialization", serializationNanos);
        instrumentation.addCounter("exact_profile_serialized_bytes", serializedBytes);
        long exactPieceCount = result.exactCandidateProfiles().values().stream()
                .mapToLong(profile -> profile.arrival().pieceCount() + profile.score().pieceCount()).sum()
                + result.exactEnvelope().cells().size();
        long scoreEventPreimages = result.exactCandidateProfiles().values().stream()
                .mapToLong(profile -> profile.scoreEventPreimages()).sum();
        long budgetRoots = result.exactCandidateProfiles().values().stream()
                .mapToLong(profile -> profile.budgetRootOperations()).sum();
        int maxRationalBits = result.exactCandidateProfiles().values().stream()
                .mapToInt(profile -> Math.max(profile.arrival().maximumRationalComponentBitLength(),
                        profile.score().maximumRationalComponentBitLength()))
                .max().orElse(0);
        maxRationalBits = Math.max(maxRationalBits, result.exactEnvelope().maximumRationalComponentBitLength());

        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("objective", "PREFERENCE_MAX_PROFILE");
        scalars.put("numerical_contract", "EXACT_RATIONAL_PREFERENCE_PROFILE-v1");
        scalars.put("temporal_correctness_verified", true);
        scalars.put("temporal_correctness_verified_for", "RETAINED_REGISTRY_REPLAY_AND_ENVELOPE");
        scalars.put("numerical_repair_status", "REPAIRED_EXACT_PROFILE");
        scalars.put("candidate_scope", "NATIVE_SICSP_RETAINED_SIMPLE_ROUTES");
        scalars.put("candidate_generation_numerical_contract", "NATIVE_SICSP_CANONICAL_12DP-v1");
        scalars.put("candidate_generation_complete", false);
        scalars.put("reported_feasibility_model", "EXACT_RATIONAL_TRAVEL_TIME_SUBLEVEL");
        scalars.put("oracle_numerical_contract", "EXACT_RATIONAL_INDEPENDENT_TEST_ORACLE");
        scalars.put("oracle_is_independent_temporal_validation", false);
        scalars.put("independent_validation_evidence", "EXACT_PREFERENCE_PROFILE_TESTS");
        scalars.put("oracle_quality_gap_status", "not_computed_search_scope_is_heuristic");
        scalars.put("oracle_quality_gap_interpretation", "GLOBAL_SIMPLE_PATH_GAP_IS_SEARCH_SCOPE_ONLY");
        scalars.put("optimality_claim", "EXACT_OVER_RETAINED_BUDGET_FEASIBLE_ROUTE_REGISTRY");
        scalars.put("exact_profile_schema", ExactPreferenceProfileCodec.SCHEMA);
        scalars.put("exact_profile_checksum", edu.ipcmax.experiments.framework.ProfileSupport.sha256(exactJson));
        scalars.put("exact_profile_serialized_bytes", serializedBytes);
        scalars.put("exact_profile_piece_count", exactPieceCount);
        scalars.put("exact_score_event_preimages", scoreEventPreimages);
        scalars.put("exact_budget_root_operations", budgetRoots);
        scalars.put("exact_envelope_comparisons", result.exactEnvelope().envelopeComparisons());
        scalars.put("exact_rational_max_component_bit_length", maxRationalBits);
        scalars.put("query_execution_nanos", stats.queryExecutionNanos());
        scalars.put("candidate_generation_nanos", stats.candidateGenerationNanos());
        scalars.put("candidate_adaptation_nanos", stats.candidateAdaptationNanos());
        scalars.put("forward_search_calls", stats.forwardSearchCalls());
        scalars.put("reverse_search_calls", stats.reverseSearchCalls());
        scalars.put("o_temporal_events", stats.oTemporalEvents());
        scalars.put("w_temporal_events", stats.wTemporalEvents());
        scalars.put("duplicate_candidate_events", stats.duplicateCandidateEvents());
        scalars.put("raw_o_points", stats.rawOPoints());
        scalars.put("raw_w_points", stats.rawWPoints());
        scalars.put("raw_candidate_routes", stats.rawCandidateRoutes());
        scalars.put("simple_candidate_routes", stats.simpleCandidateRoutes());
        scalars.put("out_of_query_candidate_routes", stats.outOfQueryCandidateRoutes());
        scalars.put("candidates_removed_by_dominance", stats.candidatesRemovedByDominance());
        scalars.put("non_simple_event_candidates_rejected", stats.nonSimpleEventCandidatesRejected());
        scalars.put("distinct_non_simple_routes_rejected", stats.distinctNonSimpleRoutesRejected());
        Map<String, Long> rejectionReasons = new LinkedHashMap<>();
        result.rejectedNonSimpleEvents().forEach(candidate -> {
            String reason = candidate.paceRejectionReason();
            String key = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
            rejectionReasons.merge(key, 1L, Long::sum);
        });
        scalars.put("non_simple_rejection_reasons", Map.copyOf(rejectionReasons));
        scalars.put("retained_distinct_candidates", stats.retainedDistinctCandidates());
        scalars.put("candidates_with_nonempty_feasible_domains",
                stats.candidatesWithNonemptyFeasibleDomains());
        scalars.put("candidates_with_empty_feasible_domains",
                stats.candidatesWithEmptyFeasibleDomains());
        scalars.put("profile_replay_nanos", stats.profileReplayNanos());
        scalars.put("envelope_construction_nanos", stats.envelopeConstructionNanos());
        scalars.put("profile_cell_count", stats.profileCellCount());
        scalars.put("feasible_profile_cell_count", stats.feasibleProfileCellCount());
        scalars.put("feasible_coverage", stats.feasibleCoverage());
        scalars.put("integrated_preference_score", stats.integratedPreferenceScore());
        scalars.put("minimum_selected_travel_time", stats.minimumSelectedTravelTime());
        scalars.put("average_selected_travel_time", stats.averageSelectedTravelTime());
        scalars.put("maximum_selected_travel_time", stats.maximumSelectedTravelTime());
        scalars.put("sampled_heap_bytes", stats.sampledHeapBytes());

        scalars.put("oracle_validation_nanos", 0L);
        scalars.put("exact_candidate_registry", result.budgetFeasibleRetainedPaths().stream()
                .map(path -> path.arcIds()).toList());
        Map<String, Object> objectiveResult = new LinkedHashMap<>();
        objectiveResult.put("objective", "PREFERENCE_MAX_PROFILE");
        objectiveResult.put("numerical_contract", "EXACT_RATIONAL_PREFERENCE_PROFILE-v1");
        objectiveResult.put("feasible", result.exactEnvelope().hasFeasibleCell());
        double totalMeasure = query.departureDomain().intervals().stream()
                .mapToDouble(interval -> interval.end() - interval.start()).sum();
        double feasibleMeasure = totalMeasure * stats.feasibleCoverage();
        objectiveResult.put("feasible_interval_measure", feasibleMeasure);
        objectiveResult.put("infeasible_interval_measure", Math.max(0.0, totalMeasure - feasibleMeasure));
        objectiveResult.put("feasible_coverage_fraction", stats.feasibleCoverage());
        objectiveResult.put("elementary_envelope_cells", result.exactEnvelope().cells().size());
        objectiveResult.put("final_profile_intervals", result.exactEnvelope().cells().size());
        long distinctPaths = result.exactEnvelope().cells().stream().filter(cell -> cell.found())
                .map(cell -> cell.path().arcIds()).distinct().count();
        objectiveResult.put("distinct_selected_paths", distinctPaths);
        long pathChanges = 0;
        String previous = null;
        for (var cell : result.exactEnvelope().cells()) {
            String assignment = cell.found() ? cell.path().arcIds().toString() : "NO_PATH";
            if (previous != null && !previous.equals(assignment)) pathChanges++;
            previous = assignment;
        }
        objectiveResult.put("path_changes", pathChanges);
        objectiveResult.put("average_interval_length", result.exactEnvelope().cells().isEmpty()
                ? null : totalMeasure / result.exactEnvelope().cells().size());
        double minimumPositiveLength = result.exactEnvelope().cells().stream()
                .mapToDouble(cell -> cell.interval().end().subtract(cell.interval().start()).toDouble())
                .filter(length -> length > 0).min().orElse(0.0);
        objectiveResult.put("minimum_interval_length", minimumPositiveLength);
        double averageScore = feasibleMeasure > 0 ? stats.integratedPreferenceScore() / feasibleMeasure : 0.0;
        objectiveResult.put("average_selected_score", feasibleMeasure > 0 ? averageScore : null);
        objectiveResult.put("best_selected_score", result.exactEnvelope().cells().stream()
                .filter(cell -> cell.found()).map(cell -> cell.score()).max(java.math.BigInteger::compareTo)
                .orElse(null));
        objectiveResult.put("average_selected_travel_time", result.exactEnvelope().hasFeasibleCell()
                ? stats.averageSelectedTravelTime() : null);
        objectiveResult.put("selected_departure_time", null);
        objectiveResult.put("selected_path_id", null);
        objectiveResult.put("selected_score", null);
        objectiveResult.put("selected_travel_time", null);
        try {
            objectiveResult.put("profile", JSON.readTree(exactJson));
        } catch (java.io.IOException malformed) {
            throw new IllegalStateException("exact profile codec emitted invalid JSON", malformed);
        }
        scalars.put("objective_result", objectiveResult);

        boolean feasible = result.exactEnvelope().hasFeasibleCell();
        ExactnessScope scope = feasible ? ExactnessScope.RETAINED_FRONTIER : ExactnessScope.NOT_CERTIFIED;
        long sharedObjectiveStarted = System.nanoTime();
        ExactPreferenceObjective.Evaluation sharedObjective = ExactPreferenceObjective.evaluate(
                graph, query, result.budgetFeasibleRetainedPaths(),
                stats.queryExecutionNanos(),
                feasible ? "COMPLETED" : "UNRESOLVED_NO_GENERATED_CANDIDATE",
                scope.name(), "SICSP_PREF_DOMINANCE_RETAINED_REGISTRY");
        long sharedObjectiveNanos = System.nanoTime() - sharedObjectiveStarted;
        instrumentation.setTiming("shared_exact_objective_evaluation", sharedObjectiveNanos);
        objectiveResult.putAll(sharedObjective.objectiveResult());
        objectiveResult.put("generated_route_registry", result.simpleCandidatePaths().stream()
                .map(path -> path.arcIds()).toList());
        objectiveResult.put("algorithm_retained_route_registry", result.retainedCandidatePaths().stream()
                .map(path -> path.arcIds()).toList());
        objectiveResult.put("end_to_end_query_nanos", System.nanoTime() - endToEndStarted);
        scalars.put("objective_result", objectiveResult);
        scalars.put("shared_exact_objective_evaluation_nanos", sharedObjectiveNanos);
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED
                        : ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE,
                null,
                scope,
                scalars,
                null,
                null);
    }
}
