package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceObjective;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;
import edu.ipcmax.baselines.exact.preference.ExactTravelBudgetDomain;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.QueryDeadline;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Exact-function iSCOPE adaptation with Opt1 or Opt1+approximate Opt2. */
public final class IScopePartialLabelAlgorithm implements ExperimentAlgorithm {
    private static final long DEFAULT_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long MAX_FINALIZATION_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String DEFAULT_OPT2_THRESHOLD_MINUTES = "5";

    private final String registryId;
    private final boolean approximateOpt2;
    private final LongSupplier clock;
    private TDGraph preparedGraph;
    private GraphGeometricTravelBounds.Lookup preparedGeometricBounds;

    public IScopePartialLabelAlgorithm(String registryId) {
        this(registryId, System::nanoTime);
    }

    IScopePartialLabelAlgorithm(String registryId, LongSupplier clock) {
        if (!List.of("iscope-opt1", "iscope-optimized").contains(registryId)) {
            throw new IllegalArgumentException("partial-label iSCOPE ID must be iscope-opt1 or iscope-optimized");
        }
        this.registryId = registryId;
        this.approximateOpt2 = registryId.equals("iscope-optimized");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return registryId;
    }

    @Override
    public synchronized void prepare(TDGraph graph, AlgorithmConfig config) {
        if (preparedGraph == graph && preparedGeometricBounds != null) return;
        preparedGeometricBounds = GraphGeometricTravelBounds.forGraph(graph);
        preparedGraph = graph;
    }

    @Override
    public AlgorithmResult run(TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        prepare(graph, config);
        GraphGeometricTravelBounds.Lookup geometricBounds = preparedGeometricBounds;
        instrumentation.setTiming("geometric_speed_bound_preprocessing",
                geometricBounds.preparationNanos());

        long started = clock.getAsLong();
        long limit = config.queryTimeLimitOr(DEFAULT_LIMIT_NANOS);
        long reserve = Math.min(MAX_FINALIZATION_RESERVE_NANOS, limit / 10);
        QueryDeadline deadline = QueryDeadline.start(clock, limit, reserve);
        ExactTemporalDomain rootDomain = ExactTemporalDomain.fromLegacy(query.departureDomain());
        ExactTime budget = ExactTime.fromCanonicalDouble(Domain.canonicalTime(query.maxTravelTime()));
        ExactTime mergeThreshold = approximateOpt2 ? opt2Threshold(config) : ExactTime.ZERO;
        long querySeed = querySeed(config.seed(), query);
        SplittableRandom random = new SplittableRandom(querySeed);
        MutableStats stats = new MutableStats();
        stats.queryStartNanos = started;
        stats.random = random;
        stats.opt2Threshold = mergeThreshold;
        stats.effectiveSeed = querySeed;
        stats.graphBoundMaximumSpeed = geometricBounds.prepared().maximumSpeed().toString();
        stats.graphBoundFallbackReason = geometricBounds.prepared().fallbackReason();
        stats.graphBoundCacheHit = geometricBounds.cacheHit();
        stats.graphBoundPreparationNanos = geometricBounds.preparationNanos();
        stats.graphBoundHeapUsedBeforeBytes = geometricBounds.heapUsedBeforeBytes();
        stats.graphBoundHeapUsedAfterBytes = geometricBounds.heapUsedAfterBytes();
        stats.graphBoundSampledPeakHeapUsedBytes = geometricBounds.sampledPeakHeapUsedBytes();
        stats.graphBoundEdgesInspected = geometricBounds.prepared().edgesInspected();
        stats.graphBoundTravelTimeBreakpointsInspected =
                geometricBounds.prepared().travelTimeBreakpointsInspected();
        stats.graphBoundPositiveCoordinateLengthEdges =
                geometricBounds.prepared().positiveCoordinateLengthEdges();
        stats.graphBoundZeroDurationEdges = geometricBounds.prepared().zeroDurationEdges();
        boolean searchExhausted = false;
        List<Path> emitted = new ArrayList<>();
        Map<Integer, ExactTime> queryBoundsByVertex = new java.util.HashMap<>();

        try {
            ExactArrivalProfile identity = ExactArrivalProfile.identity(rootDomain);
            ExactScoreProfile zeroScore = ExactScoreProfile.constant(rootDomain, BigInteger.ZERO);
            ExactPartialPathLabel root = new ExactPartialPathLabel(query.source(), List.of(),
                    Set.of(query.source()), rootDomain, identity, zeroScore, rootDomain, false);
            stats.partialLabelsCreated++;
            stats.sourceLabelInitializedBeforeForwardSearch = true;
            stats.observeLabel(root);
            long searchStarted = clock.getAsLong();
            stats.dfsStartElapsedNanos = Math.max(0, searchStarted - started);
            searchExhausted = search(graph, query, config, root, geometricBounds.prepared(),
                    queryBoundsByVertex, budget, deadline, stats, emitted);
            instrumentation.setTiming("algorithm_search", clock.getAsLong() - searchStarted);
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
        }
        instrumentation.setTiming("opt1_budget_domain_operations", stats.budgetDomainNanos);
        instrumentation.setTiming("opt1_geometric_bound_evaluation", stats.geometricBoundEvaluationNanos);

        long finalizationStarted = clock.getAsLong();
        String tentativeCompletion = searchExhausted ? "EXHAUSTED"
                : stats.pathCapReached ? "PATH_CAPPED_NOT_CERTIFIED" : "TIME_CAPPED_NOT_CERTIFIED";
        ExactPreferenceObjective.Evaluation exact;
        try {
            exact = ExactPreferenceObjective.evaluateIncrementally(
                    graph, query, emitted, clock.getAsLong() - started,
                    tentativeCompletion,
                    approximateOpt2 ? ExactnessScope.NOT_CERTIFIED.name()
                            : searchExhausted && !stats.pathCapReached && !stats.timeCapReached
                                    ? ExactnessScope.GLOBAL_CERTIFIED.name()
                                    : ExactnessScope.NOT_CERTIFIED.name(),
                    approximateOpt2 ? "ISCOPE_OPT2_RETAINED_EXACTLY_REPLAYED_ROUTES"
                            : "ISCOPE_OPT1_EXACTLY_FEASIBLE_EMITTED_SIMPLE_PATHS",
                    deadline::expired);
        } catch (CancellationException cancelled) {
            // The incremental evaluator is fail-closed, but retains only whole committed profiles.
            exact = ExactPreferenceObjective.evaluateIncrementally(
                    graph, query, List.of(), clock.getAsLong() - started,
                    "TIME_CAPPED_NOT_CERTIFIED", ExactnessScope.NOT_CERTIFIED.name(),
                    "ISCOPE_NO_COMPLETED_FINAL_REPLAY", () -> false);
            stats.timeCapReached = true;
        }
        if (!exact.evaluationComplete() || deadline.expired()) stats.timeCapReached = true;
        long finalizationNanos = clock.getAsLong() - finalizationStarted;
        long totalElapsedNanos = deadline.elapsedNanos();
        if (totalElapsedNanos > limit) stats.timeCapReached = true;
        instrumentation.setTiming("exact_candidate_replay_and_envelope", finalizationNanos);
        instrumentation.setTiming("exact_destination_replay",
                number(exact.objectiveResult().get("exact_destination_replay_nanos")));
        instrumentation.setTiming("exact_envelope_construction",
                number(exact.objectiveResult().get("exact_envelope_construction_nanos")));

        ExactnessScope exactness = !approximateOpt2 && searchExhausted
                && !stats.pathCapReached && !stats.timeCapReached && exact.evaluationComplete()
                        ? ExactnessScope.GLOBAL_CERTIFIED : ExactnessScope.NOT_CERTIFIED;
        ExperimentStatus status;
        String completion;
        if (stats.timeCapReached) {
            status = ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED;
            completion = "TIME_CAPPED_NOT_CERTIFIED";
        } else if (stats.pathCapReached) {
            status = ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
            completion = "PATH_CAPPED_NOT_CERTIFIED";
        } else if (!searchExhausted) {
            status = ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
            completion = "INCOMPLETE";
        } else if (approximateOpt2) {
            status = exact.profile().hasFeasibleCell()
                    ? ExperimentStatus.COMPLETED
                    : ExperimentStatus.UNRESOLVED_NO_GENERATED_CANDIDATE;
            completion = "OPT2_APPROXIMATE_SEARCH_COMPLETED_NOT_CERTIFIED";
        } else if (!exact.profile().hasFeasibleCell()) {
            status = ExperimentStatus.NO_FEASIBLE_PATH;
            completion = "NO_FEASIBLE_PATH";
        } else {
            status = ExperimentStatus.CERTIFIED_COMPLETE;
            completion = "CERTIFIED_COMPLETE";
        }

        Map<String, Object> objectiveResult = new LinkedHashMap<>(exact.objectiveResult());
        objectiveResult.put("completion_status", completion);
        objectiveResult.put("exactness_scope", exactness.name());
        objectiveResult.put("end_to_end_query_nanos", totalElapsedNanos);
        Map<String, Object> scalars = stats.toScalars();
        scalars.put("objective", ExactPreferenceObjective.OBJECTIVE);
        scalars.put("numerical_contract", ExactPreferenceObjective.CONTRACT);
        scalars.put("implementation_variant", approximateOpt2
                ? "CONTINUOUS_PARTIAL_FUNCTION_OPT1_OPT2_ADAPTATION-v1"
                : "CONTINUOUS_PARTIAL_FUNCTION_OPT1_EXACT-v1");
        scalars.put("paper_fidelity", approximateOpt2 ? "APPROXIMATE_ADAPTATION" : "OPT1_BOUND_ADAPTATION");
        scalars.put("paper_doi", "10.1007/978-981-95-7251-9_12");
        scalars.put("variant_id", registryId);
        scalars.put("historical_iscope_alias", false);
        scalars.put("search_pruning_policy", "FORWARD_EXACT_RATIONAL_PREFIX_BUDGET_PLUS_GEOMETRIC_OPT1-v2");
        scalars.put("lower_bound_policy", "GRAPH_DERIVED_NATIVE_COORDINATE_SPEED_BOUND-v1");
        scalars.put("euclidean_speed_bound_used", geometricBounds.prepared().enabled());
        scalars.put("coordinates_used", geometricBounds.prepared().enabled());
        scalars.put("lower_bound_invariant", "h(v,d)<=every_time_dependent_suffix_travel_duration_from_v_to_d");
        scalars.put("geometric_distance_policy", "FLOOR_SQRT_NATIVE_COORDINATE_EUCLIDEAN_LOWER_BOUND-v1");
        scalars.put("geometric_speed_bound_derived_from",
                "MAX_DIRECTED_EDGE_L1_COORDINATE_DISTANCE_OVER_MIN_EDGE_TRAVEL_TIME");
        scalars.put("geometric_speed_bound_native_units_per_minute", stats.graphBoundMaximumSpeed);
        scalars.put("geometric_speed_bound_fallback_reason", stats.graphBoundFallbackReason == null
                ? "none" : stats.graphBoundFallbackReason);
        scalars.put("geometric_speed_bound_cache_hit", stats.graphBoundCacheHit);
        scalars.put("geometric_speed_bound_preparation_nanos", stats.graphBoundPreparationNanos);
        scalars.put("geometric_speed_bound_heap_used_before_bytes", stats.graphBoundHeapUsedBeforeBytes);
        scalars.put("geometric_speed_bound_heap_used_after_bytes", stats.graphBoundHeapUsedAfterBytes);
        scalars.put("geometric_speed_bound_sampled_peak_heap_used_bytes",
                stats.graphBoundSampledPeakHeapUsedBytes);
        scalars.put("geometric_speed_bound_edges_inspected", stats.graphBoundEdgesInspected);
        scalars.put("geometric_speed_bound_travel_time_breakpoints_inspected",
                stats.graphBoundTravelTimeBreakpointsInspected);
        scalars.put("geometric_speed_bound_positive_coordinate_length_edges",
                stats.graphBoundPositiveCoordinateLengthEdges);
        scalars.put("geometric_speed_bound_zero_duration_edges", stats.graphBoundZeroDurationEdges);
        scalars.put("reverse_dijkstra_preparation_invocations", 0L);
        scalars.put("source_label_initialized_before_forward_search",
                stats.sourceLabelInitializedBeforeForwardSearch);
        scalars.put("dfs_start_elapsed_nanos", stats.dfsStartElapsedNanos);
        scalars.put("opt1_geometric_bound_evaluations", stats.geometricBoundEvaluations);
        scalars.put("opt1_geometric_bound_cache_misses", stats.geometricBoundCacheMisses);
        scalars.put("opt1_geometric_bound_evaluation_nanos", stats.geometricBoundEvaluationNanos);
        scalars.put("query_deadline_nanos", limit);
        scalars.put("query_time_cap_protocol_seconds", TimeUnit.NANOSECONDS.toSeconds(limit));
        scalars.put("finalization_reserve_nanos", reserve);
        scalars.put("measured_query_elapsed_ns", totalElapsedNanos);
        scalars.put("end_to_end_query_nanos", totalElapsedNanos);
        scalars.put("deadline_overrun_ns", Math.max(0L, totalElapsedNanos - limit));
        scalars.put("deadline_overrun", totalElapsedNanos > limit);
        scalars.put("deadline_cap_triggered", stats.timeCapReached);
        scalars.put("path_cap_triggered", stats.pathCapReached);
        scalars.put("algorithm_completion_status", completion);
        scalars.put("exact_profile_schema", "exact-preference-envelope-v1");
        scalars.put("exact_profile_checksum", objectiveResult.get("exact_profile_checksum"));
        scalars.put("exact_objective_evaluation_nanos", finalizationNanos);
        scalars.put("opt1_budget_domain_operation_nanos", stats.budgetDomainNanos);
        scalars.put("opt1_budget_domain_operation_count", stats.budgetDomainOperationCount);
        scalars.put("output_loopless", true);
        scalars.put("output_feasible", exact.profile().hasFeasibleCell());
        scalars.put("output_validation_contract", "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1");
        scalars.put("registry_variant_id", registryId);
        scalars.put("random_seed", Long.toUnsignedString(querySeed));
        if (approximateOpt2) {
            scalars.put("opt2_breakpoint_threshold_minutes", mergeThreshold.toString());
            scalars.put("opt2_score_selection", "PAPER_ROULETTE_PAIRWISE_LEFT_TO_RIGHT_ADAPTATION-v1");
            scalars.put("opt2_random_draw", "UNIFORM_INTEGER_0_TO_100_INCLUSIVE");
            scalars.put("opt2_arrival_approximation", "LINEAR_INTERPOLATION_BETWEEN_RETAINED_CLUSTER_BOUNDARIES");
            scalars.put("approximate_search_function_pieces_before", stats.approximatePiecesBefore);
            scalars.put("approximate_search_function_pieces_after", stats.approximatePiecesAfter);
            scalars.put("approximate_breakpoints_merged", stats.approximateBreakpointsMerged);
            scalars.put("approximate_roulette_draws", stats.approximateRouletteDraws);
            scalars.put("opt2_maximum_observed_arrival_error_minutes",
                    stats.maximumApproximateArrivalError.toString());
            scalars.put("exact_final_replay_restores_removed_candidates", false);
        }
        scalars.put("objective_result", objectiveResult);
        return new AlgorithmResult(status, null, exactness, scalars, null, null);
    }

    private boolean search(TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExactPartialPathLabel label, GraphGeometricTravelBounds.Prepared geometricBounds,
            Map<Integer, ExactTime> queryBoundsByVertex, ExactTime budget,
            QueryDeadline deadline, MutableStats stats, List<Path> emitted) {
        if (deadline.finalizationDue()) {
            stats.timeCapReached = true;
            return false;
        }
        stats.dfsStatesVisited++;
        stats.observeLabel(label);

        ExactTime remainingBound = queryBoundsByVertex.get(label.currentVertex());
        if (remainingBound == null) {
            stats.geometricBoundEvaluations++;
            stats.geometricBoundCacheMisses++;
            long boundStarted = clock.getAsLong();
            remainingBound = geometricBounds.remainingTimeLowerBound(
                    graph, label.currentVertex(), query.destination());
            stats.geometricBoundEvaluationNanos += Math.max(0, clock.getAsLong() - boundStarted);
            queryBoundsByVertex.put(label.currentVertex(), remainingBound);
        } else {
            stats.geometricBoundEvaluations++;
        }

        ExactTemporalDomain beforeBound = label.feasibleDepartureSubdomains();
        final ExactTravelBudgetDomain allowed;
        long budgetStarted = clock.getAsLong();
        try {
            allowed = ExactTravelBudgetDomain.compute(
                    label.exactPrefixArrival(), budget.subtract(remainingBound), deadline::finalizationDue);
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
            return false;
        } finally {
            stats.budgetDomainNanos += Math.max(0, clock.getAsLong() - budgetStarted);
            stats.budgetDomainOperationCount++;
        }

        budgetStarted = clock.getAsLong();
        ExactTemporalDomain feasible;
        try {
            feasible = beforeBound.intersection(allowed.domain(), deadline::finalizationDue);
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
            return false;
        } finally {
            stats.budgetDomainNanos += Math.max(0, clock.getAsLong() - budgetStarted);
            stats.budgetDomainOperationCount++;
        }

        budgetStarted = clock.getAsLong();
        try {
            stats.safelyPrunedCells += beforeBound.difference(
                    feasible, deadline::finalizationDue).intervals().size();
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
            return false;
        } finally {
            stats.budgetDomainNanos += Math.max(0, clock.getAsLong() - budgetStarted);
            stats.budgetDomainOperationCount++;
        }
        if (feasible.isEmpty()) {
            stats.searchStatesCompleted++;
            return true;
        }

        ExactArrivalProfile boundedArrival = label.exactPrefixArrival().restrict(
                feasible, deadline::finalizationDue);
        ExactScoreProfile boundedScore = label.exactPrefixScore().restrict(
                feasible, deadline::finalizationDue);
        ExactPartialPathLabel bounded = new ExactPartialPathLabel(label.currentVertex(),
                label.predecessorArcSequence(), label.visitedVertices(), feasible,
                boundedArrival, boundedScore, feasible, label.approximateFunctions());

        if (bounded.currentVertex() == query.destination()) {
            if (stats.firstDestinationCompletionElapsedNanos < 0) {
                stats.firstDestinationCompletionElapsedNanos = Math.max(0L,
                        clock.getAsLong() - stats.queryStartNanos);
            }
            if (stats.completedPathsEvaluated >= config.maxEnumeratedPaths()) {
                stats.pathCapReached = true;
                return false;
            }
            stats.completedPathsEvaluated++;
            Path path = new Path(bounded.predecessorArcSequence());
            if (!emitted.contains(path)) emitted.add(path);
            stats.searchStatesCompleted++;
            return true;
        }

        boolean directDestinationReachable = false;
        for (Edge edge : graph.outgoingEdges(bounded.currentVertex())) {
            if (deadline.finalizationDue()) {
                stats.timeCapReached = true;
                return false;
            }
            stats.edgesExpanded++;
            if (stats.firstEdgeExpansionElapsedNanos < 0) {
                stats.firstEdgeExpansionElapsedNanos = Math.max(0L,
                        clock.getAsLong() - stats.queryStartNanos);
            }
            if (edge.target() == query.destination()) {
                if (!directDestinationReachable) {
                    directDestinationReachable = true;
                    stats.partialLabelsWithDirectDestinationEdge++;
                }
                stats.edgesExpandedToDestination++;
            }
            if (bounded.visitedVertices().contains(edge.target())) continue;
            ExactPartialPathLabel next;
            try {
                next = extend(bounded, edge, deadline::finalizationDue, stats);
            } catch (CancellationException cancelled) {
                stats.timeCapReached = true;
                return false;
            }
            if (next == null) continue;
            if (!search(graph, query, config, next, geometricBounds, queryBoundsByVertex,
                    budget, deadline, stats, emitted)) {
                return false;
            }
        }
        stats.searchStatesCompleted++;
        return true;
    }

    private ExactPartialPathLabel extend(ExactPartialPathLabel label, Edge edge,
            java.util.function.BooleanSupplier stopRequested, MutableStats stats) {
        check(stopRequested);
        ExactScoreProfile edgeScore = ExactScoreProfile.edgeScore(edge, stopRequested);
        check(stopRequested);
        ExactTemporalDomain scorePreimage = label.exactPrefixArrival().preimage(
                edgeScore.domain(), stopRequested);
        check(stopRequested);
        ExactTemporalDomain scoreSupport = label.validSourceDepartureSupport().intersection(
                scorePreimage, stopRequested);
        if (scoreSupport.isEmpty()) {
            stats.temporalCellsSplit++;
            return null;
        }
        ExactArrivalProfile edgeArrival = ExactArrivalProfile.edgeArrival(edge, stopRequested);
        check(stopRequested);
        ExactTemporalDomain travelPreimage = label.exactPrefixArrival().preimage(
                edgeArrival.domain(), stopRequested);
        ExactTemporalDomain nextSupport = scoreSupport.intersection(travelPreimage, stopRequested);
        if (nextSupport.isEmpty()) {
            stats.temporalCellsSplit++;
            return null;
        }
        ExactArrivalProfile nextArrival;
        ExactScoreProfile nextScore;
        if (approximateOpt2) {
            long mergeStarted = System.nanoTime();
            ApproximatePartialFunctions.Result merged = ApproximatePartialFunctions.composeAndMerge(
                    label.exactPrefixArrival(), edgeArrival,
                    label.exactPrefixScore(), edgeScore,
                    nextSupport, opt2ThresholdFromStats(stats), stats.random, stopRequested);
            stats.opt2MergeNanos += System.nanoTime() - mergeStarted;
            stats.functionCompositionAndMergeNanos += System.nanoTime() - mergeStarted;
            stats.temporalFunctionCompositionNanos += merged.temporalCompositionNanos();
            stats.functionCompositionOperationCount += merged.temporalCompositionOperationCount();
            stats.approximateIncomingBreakpoints += merged.incomingBreakpoints();
            stats.approximateRetainedBreakpoints += merged.retainedBreakpoints();
            stats.approximateBreakpointsMerged += merged.mergedBreakpoints();
            stats.approximateRouletteDraws += merged.rouletteDraws();
            stats.approximateScoreReplacements += merged.scoreReplacements();
            stats.approximateScoreRetentions += merged.scoreRetentions();
            stats.peakOpt2BufferedMergeState = Math.max(
                    stats.peakOpt2BufferedMergeState, merged.peakBufferedMergeState());
            if (merged.maximumArrivalError().compareTo(stats.maximumApproximateArrivalError) > 0) {
                stats.maximumApproximateArrivalError = merged.maximumArrivalError();
            }
            stats.approximatePiecesBefore += merged.piecesBefore();
            stats.approximatePiecesAfter += merged.piecesAfter();
            nextArrival = merged.arrival();
            nextScore = merged.score();
        } else {
            ExactArrivalProfile scoreEntryMap = label.exactPrefixArrival()
                    .restrict(scoreSupport, stopRequested);
            check(stopRequested);
            ExactScoreProfile edgeScoreOnDeparture = edgeScore.pullback(scoreEntryMap, stopRequested);
            check(stopRequested);
            ExactScoreProfile accumulated = label.exactPrefixScore()
                    .restrict(scoreSupport, stopRequested).plus(edgeScoreOnDeparture, stopRequested);
            check(stopRequested);
            long compositionStarted = System.nanoTime();
            nextArrival = label.exactPrefixArrival()
                    .restrict(nextSupport, stopRequested).composeAfter(edgeArrival, stopRequested);
            stats.temporalFunctionCompositionNanos += System.nanoTime() - compositionStarted;
            stats.functionCompositionOperationCount++;
            nextScore = accumulated.restrict(nextSupport, stopRequested);
        }
        stats.partialFunctionOperations += 6;
        stats.temporalCellsSplit += nextArrival.segments().size() + nextScore.pieceCount();
        List<Integer> path = new ArrayList<>(label.predecessorArcSequence());
        path.add(edge.arcId());
        Set<Integer> visited = new HashSet<>(label.visitedVertices());
        visited.add(edge.target());
        stats.partialLabelsCreated++;
        ExactPartialPathLabel result = new ExactPartialPathLabel(edge.target(), path, visited,
                nextSupport, nextArrival, nextScore, nextSupport, approximateOpt2);
        stats.observeLabel(result);
        return result;
    }

    private ExactTime opt2Threshold(AlgorithmConfig config) {
        if (!approximateOpt2) return ExactTime.ZERO;
        String value = config.objectiveOptions().getOrDefault(
                "iscope_opt2_breakpoint_threshold_minutes", DEFAULT_OPT2_THRESHOLD_MINUTES);
        ExactTime parsed;
        try {
            parsed = ExactTime.parseDecimal(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid Opt2 breakpoint threshold: " + value, invalid);
        }
        if (parsed.signum() <= 0) throw new IllegalArgumentException("Opt2 threshold must be positive");
        return parsed;
    }

    private ExactTime opt2ThresholdFromStats(MutableStats stats) {
        return stats.opt2Threshold;
    }

    private static long querySeed(long configSeed, QuerySpec query) {
        String key = configSeed + "|" + query.source() + "|" + query.destination() + "|"
                + query.departureDomain() + "|" + Domain.canonicalTime(query.maxTravelTime());
        long value = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            value ^= key.charAt(i);
            value *= 0x100000001b3L;
        }
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static void check(java.util.function.BooleanSupplier stopRequested) {
        if (stopRequested.getAsBoolean()) throw new CancellationException("iSCOPE partial-label operation cancelled");
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static final class MutableStats {
        long queryStartNanos;
        long dfsStatesVisited;
        long partialLabelsCreated;
        long temporalCellsSplit;
        long safelyPrunedCells;
        long edgesExpanded;
        long completedPathsEvaluated;
        long partialFunctionOperations;
        long budgetDomainNanos;
        long budgetDomainOperationCount;
        long geometricBoundEvaluations;
        long geometricBoundCacheMisses;
        long geometricBoundEvaluationNanos;
        String graphBoundMaximumSpeed = "0";
        String graphBoundFallbackReason;
        boolean graphBoundCacheHit;
        long graphBoundPreparationNanos;
        long graphBoundHeapUsedBeforeBytes;
        long graphBoundHeapUsedAfterBytes;
        long graphBoundSampledPeakHeapUsedBytes;
        long graphBoundEdgesInspected;
        long graphBoundTravelTimeBreakpointsInspected;
        long graphBoundPositiveCoordinateLengthEdges;
        long graphBoundZeroDurationEdges;
        long dfsStartElapsedNanos;
        long firstEdgeExpansionElapsedNanos = -1;
        long firstDestinationCompletionElapsedNanos = -1;
        long maximumPathDepth;
        long maximumUniqueVerticesOnPath;
        long partialLabelsWithDirectDestinationEdge;
        long edgesExpandedToDestination;
        long temporalFunctionCompositionNanos;
        long functionCompositionAndMergeNanos;
        long functionCompositionOperationCount;
        boolean sourceLabelInitializedBeforeForwardSearch;
        long approximateBreakpointsMerged;
        long approximateIncomingBreakpoints;
        long approximateRetainedBreakpoints;
        long approximateRouletteDraws;
        long approximateScoreReplacements;
        long approximateScoreRetentions;
        long peakOpt2BufferedMergeState;
        long opt2MergeNanos;
        long approximatePiecesBefore;
        long approximatePiecesAfter;
        ExactTime maximumApproximateArrivalError = ExactTime.ZERO;
        long peakEstimatedLabelBytes;
        long peakHeapObservedBytes;
        long searchStatesCompleted;
        boolean timeCapReached;
        boolean pathCapReached;
        ExactTime opt2Threshold = ExactTime.ZERO;
        SplittableRandom random;
        long effectiveSeed;

        void observeLabel(ExactPartialPathLabel label) {
            maximumPathDepth = Math.max(maximumPathDepth, label.predecessorArcSequence().size());
            maximumUniqueVerticesOnPath = Math.max(maximumUniqueVerticesOnPath,
                    label.visitedVertices().size());
            long estimate = 64L + 4L * label.predecessorArcSequence().size()
                    + 8L * label.visitedVertices().size()
                    + 64L * label.validSourceDepartureSupport().intervals().size()
                    + 80L * label.exactPrefixArrival().segments().size()
                    + 64L * label.exactPrefixScore().pieceCount();
            peakEstimatedLabelBytes = Math.max(peakEstimatedLabelBytes, estimate);
            long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            peakHeapObservedBytes = Math.max(peakHeapObservedBytes, heap);
        }

        Map<String, Object> toScalars() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dfs_states_visited", dfsStatesVisited);
            result.put("dfs_time_to_first_edge_expansion_nanos", firstEdgeExpansionElapsedNanos);
            result.put("dfs_time_to_first_destination_nanos", firstDestinationCompletionElapsedNanos);
            result.put("maximum_path_depth", maximumPathDepth);
            result.put("maximum_unique_vertices_on_path", maximumUniqueVerticesOnPath);
            result.put("partial_labels_with_direct_destination_edge",
                    partialLabelsWithDirectDestinationEdge);
            result.put("expanded_edges_to_destination", edgesExpandedToDestination);
            result.put("partial_labels_created", partialLabelsCreated);
            result.put("partial_functions_constructed", partialLabelsCreated);
            result.put("temporal_cells_split", temporalCellsSplit);
            result.put("cells_safely_pruned", safelyPrunedCells);
            result.put("opt1_pruned_temporal_intervals", safelyPrunedCells);
            result.put("edges_expanded", edgesExpanded);
            result.put("completed_paths_evaluated", completedPathsEvaluated);
            result.put("search_states_completed", searchStatesCompleted);
            result.put("partial_function_operations", partialFunctionOperations);
            result.put("function_composition_operation_count", functionCompositionOperationCount);
            result.put("temporal_function_composition_nanos", temporalFunctionCompositionNanos);
            result.put("function_composition_and_opt2_merge_nanos", functionCompositionAndMergeNanos);
            result.put("opt1_geometric_bound_evaluations", geometricBoundEvaluations);
            result.put("opt1_geometric_bound_cache_misses", geometricBoundCacheMisses);
            result.put("opt1_geometric_bound_evaluation_nanos", geometricBoundEvaluationNanos);
            result.put("geometric_speed_bound_max_native_units_per_minute", graphBoundMaximumSpeed);
            result.put("geometric_speed_bound_fallback_reason", graphBoundFallbackReason == null
                    ? "none" : graphBoundFallbackReason);
            result.put("geometric_speed_bound_cache_hit", graphBoundCacheHit);
            result.put("geometric_speed_bound_preparation_nanos", graphBoundPreparationNanos);
            result.put("geometric_speed_bound_heap_used_before_bytes", graphBoundHeapUsedBeforeBytes);
            result.put("geometric_speed_bound_heap_used_after_bytes", graphBoundHeapUsedAfterBytes);
            result.put("geometric_speed_bound_sampled_peak_heap_used_bytes",
                    graphBoundSampledPeakHeapUsedBytes);
            result.put("geometric_speed_bound_edges_inspected", graphBoundEdgesInspected);
            result.put("geometric_speed_bound_travel_time_breakpoints_inspected",
                    graphBoundTravelTimeBreakpointsInspected);
            result.put("geometric_speed_bound_positive_coordinate_length_edges",
                    graphBoundPositiveCoordinateLengthEdges);
            result.put("geometric_speed_bound_zero_duration_edges", graphBoundZeroDurationEdges);
            result.put("reverse_dijkstra_preparation_invocations", 0);
            result.put("source_label_initialized_before_forward_search",
                    sourceLabelInitializedBeforeForwardSearch);
            result.put("dfs_start_elapsed_nanos", dfsStartElapsedNanos);
            result.put("approximate_breakpoints_merged", approximateBreakpointsMerged);
            result.put("opt2_incoming_breakpoints", approximateIncomingBreakpoints);
            result.put("opt2_retained_breakpoints", approximateRetainedBreakpoints);
            result.put("approximate_roulette_draws", approximateRouletteDraws);
            result.put("opt2_score_replacements", approximateScoreReplacements);
            result.put("opt2_score_retentions", approximateScoreRetentions);
            result.put("opt2_peak_buffered_merge_state", peakOpt2BufferedMergeState);
            result.put("opt2_streaming_merge_nanos", opt2MergeNanos);
            result.put("opt2_effective_seed", Long.toUnsignedString(effectiveSeed));
            result.put("opt2_maximum_observed_arrival_error_minutes",
                    maximumApproximateArrivalError.toString());
            result.put("approximate_search_function_pieces_before", approximatePiecesBefore);
            result.put("approximate_search_function_pieces_after", approximatePiecesAfter);
            result.put("peak_estimated_label_payload_bytes", peakEstimatedLabelBytes);
            result.put("peak_heap_observed_bytes", peakHeapObservedBytes);
            result.put("time_cap_triggered", timeCapReached);
            result.put("path_cap_triggered", pathCapReached);
            return result;
        }
    }
}
