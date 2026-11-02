package edu.ipcmax.experiments.algorithms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.DenseDijkstraLowerBoundOracle;
import edu.ipcmax.core.index.ExactDijkstraLowerBoundOracle;
import edu.ipcmax.core.index.LowerBoundOracle;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;
import edu.ipcmax.core.pcmax.FastestEnvelopeExtractor;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.LooplessChecker;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.QueryDeadline;

/** Continuous functional-A* implementation of Time-Interval All Fastest Paths. */
public final class AllFpAlgorithm implements ExperimentAlgorithm {
    static final long DEFAULT_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long MAX_FINALIZATION_RESERVE_NANOS =
            TimeUnit.MILLISECONDS.toNanos(500);

    private static final Comparator<FunctionalLabel> LABEL_ORDER = Comparator
            .comparingDouble(FunctionalLabel::priority)
            .thenComparingInt(label -> label.trace().depth())
            .thenComparingLong(label -> label.trace().stableHash())
            .thenComparingInt(FunctionalLabel::node)
            .thenComparingLong(FunctionalLabel::ordinal);

    private final LongSupplier clock;
    private volatile TDGraph preparedGraph;
    private volatile LowerBoundOracle preparedLower;
    private volatile String preparedLowerName;
    private volatile double preparedSupportEnd = Double.NaN;
    private volatile ExecutorService preparedExecutor;
    private volatile int preparedThreads = 1;

    public AllFpAlgorithm() {
        this(System::nanoTime);
    }

    /** Injectable monotonic clock constructor for deterministic deadline tests. */
    public AllFpAlgorithm(LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("monotonic clock is required");
        }
        this.clock = clock;
    }

    @Override
    public String id() {
        return "allfp";
    }

    @Override
    public synchronized void prepare(
            TDGraph graph,
            AlgorithmConfig config) {
        int threads = Math.max(1, config.threads());
        if (preparedGraph == graph && preparedLower != null
                && Double.isFinite(preparedSupportEnd)
                && preparedThreads == threads) {
            return;
        }
        if (preparedExecutor != null) {
            preparedExecutor.shutdownNow();
            preparedExecutor = null;
        }
        OracleSelection selection = lowerBoundOracle(graph);
        preparedLower = selection.oracle();
        preparedLowerName = selection.name();
        preparedSupportEnd = commonSupportEnd(graph, null, () -> false);
        preparedThreads = threads;
        if (threads > 1) {
            preparedExecutor = workerPool(threads);
        }
        preparedGraph = graph;
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph,
            QuerySpec query,
            AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long limit = config.queryTimeLimitOr(DEFAULT_LIMIT_NANOS);
        long reserve = Math.min(MAX_FINALIZATION_RESERVE_NANOS, limit / 10);
        QueryDeadline deadline = QueryDeadline.start(clock, limit, reserve);
        MutableStats stats = new MutableStats();
        stats.requestedWorkers = Math.max(1, config.threads());
        EnvelopeProfile profile = FastestEnvelopeExtractor.extract(
                new CandidateSet(), query.departureDomain());
        boolean certified = false;
        Map<List<Integer>, FunctionalLabel> selectedTerminalLabels =
                new TreeMap<>(PathPointer.STABLE_PATH_ORDER);
        Map<Integer, TimeProfile> edgeArrivalCache =
                new ConcurrentHashMap<>();
        ExecutorSelection executors = executors(graph, config);
        String lowerBoundName = "unprepared";

        try {
            long lowerStarted = clock.getAsLong();
            LowerBoundOracle.Labels toTarget;
            try {
                OracleSelection selection = preparedGraph == graph
                        && preparedLower != null
                                ? new OracleSelection(
                                        preparedLower,
                                        preparedLowerName)
                                : lowerBoundOracle(graph);
                lowerBoundName = selection.name();
                toTarget = selection.oracle().distancesTo(
                        query.destination(), deadline::finalizationDue);
            } finally {
                instrumentation.setTiming(
                        "lower_bound_preprocessing",
                        nonnegative(clock.getAsLong() - lowerStarted));
            }

            if (!toTarget.reached(query.source())) {
                certified = true;
            } else {
                double supportEnd = preparedGraph == graph
                        && Double.isFinite(preparedSupportEnd)
                                ? preparedSupportEnd
                                : commonSupportEnd(
                                        graph,
                                        query,
                                        deadline::finalizationDue);
                if (supportEnd < query.departureEndExact().doubleValue()) {
                    throw new IllegalArgumentException(
                            "allFP query lies outside common temporal support");
                }

                PriorityQueue<FunctionalLabel> queue =
                        new PriorityQueue<>(LABEL_ORDER);
                Map<Integer, List<FunctionalLabel>> labelsByNode =
                        new HashMap<>();
                FunctionalLabel root = new FunctionalLabel(
                        query.source(),
                        null,
                        -1,
                        PathTrace.root(query.source()),
                        TimeProfile.identity(query.departureDomain()),
                        toTarget.distance(query.source()),
                        stats.nextLabelOrdinal++);
                queue.add(root);
                labelsByNode.computeIfAbsent(
                        root.node(), ignored -> new ArrayList<>()).add(root);
                stats.priorityQueuePushes++;
                stats.labelsGenerated++;
                TimeProfile lowerBorder = null;
                boolean lowerBorderCoversQuery = false;

                while (!queue.isEmpty()) {
                    if (deadline.finalizationDue()) {
                        stats.timeCapReached = true;
                        break;
                    }
                    FunctionalLabel next = queue.peek();
                    stats.priorityQueuePeeks++;
                    if (lowerBorder != null && lowerBorderCoversQuery
                            && Domain.canonicalTime(next.priority())
                                    > Domain.canonicalTime(
                                            lowerBorder.maximumTravelTime(
                                                    query.departureDomain()))) {
                        stats.lowerBorderStopTriggered = true;
                        certified = true;
                        break;
                    }

                    FunctionalLabel label = queue.remove();
                    stats.priorityQueuePops++;
                    stats.expandedFunctionalLabels++;
                    if (label.node() == query.destination()) {
                        stats.terminalCandidates++;
                        CandidateProfile candidate = unscoredCandidate(label);
                        long envelopeStarted = clock.getAsLong();
                        try {
                            CandidateSet reduced = selectedCandidates(
                                    profile, candidate);
                            profile = FastestEnvelopeExtractor.extract(
                                    reduced,
                                    query.departureDomain(),
                                    deadline::expired);
                        } catch (CancellationException cancelled) {
                            stats.terminalEnvelopeUpdatesDiscarded++;
                            stats.timeCapReached = true;
                            break;
                        } finally {
                            long envelopeNanos = nonnegative(
                                    clock.getAsLong() - envelopeStarted);
                            instrumentation.addTiming(
                                    "envelope_extraction", envelopeNanos);
                            instrumentation.addTiming(
                                    "envelope", envelopeNanos);
                        }
                        stats.fullyProfiledTerminalPaths++;
                        stats.terminalArrivalProfilesReused++;
                        stats.profileBreakpoints +=
                                candidate.arrivalProfile().breakpoints().size();
                        lowerBorder = lowerBorder == null
                                ? candidate.arrivalProfile()
                                : lowerBorder.pointwiseMinimum(
                                        candidate.arrivalProfile(),
                                        "allfp:lower-border:"
                                                + stats.terminalCandidates);
                        lowerBorderCoversQuery = lowerBorder.domain()
                                .intersection(query.departureDomain())
                                .equals(query.departureDomain());
                        selectedTerminalLabels.put(
                                candidate.stablePathId(), label);
                        retainSelectedTerminals(
                                selectedTerminalLabels, profile);
                        continue;
                    }

                    List<EdgeWork> work = new ArrayList<>();
                    List<Edge> outgoing = new ArrayList<>(
                            graph.outgoingEdges(label.node()));
                    outgoing.sort(Comparator.comparingInt(Edge::arcId));
                    for (Edge edge : outgoing) {
                        if (deadline.finalizationDue()) {
                            stats.timeCapReached = true;
                            break;
                        }
                        if (++stats.expansions > config.maxExpansions()) {
                            stats.pathCapReached = true;
                            break;
                        }
                        if (label.trace().containsVertex(edge.target())) {
                            stats.loopPrunes++;
                            continue;
                        }
                        stats.lowerBoundEvaluations++;
                        double remaining = toTarget.distance(edge.target());
                        if (!Double.isFinite(remaining)) {
                            stats.lowerBoundPrunes++;
                            continue;
                        }
                        work.add(new EdgeWork(
                                edge,
                                remaining,
                                label.trace().extend(edge)));
                    }
                    if (stats.timeCapReached || stats.pathCapReached) {
                        break;
                    }

                    long compositionStarted = clock.getAsLong();
                    List<Extension> extensions;
                    try {
                        extensions = extensions(
                                label,
                                work,
                                edgeArrivalCache,
                                deadline,
                                executors.executor(),
                                executors.parallel(),
                                stats);
                    } finally {
                        instrumentation.addTiming(
                                "functional_composition",
                                nonnegative(
                                        clock.getAsLong()
                                                - compositionStarted));
                    }
                    for (Extension extension : extensions) {
                        stats.functionalCompositionWorkerNanos +=
                                extension.compositionNanos();
                        if (extension.arrival() == null) {
                            stats.horizonPrunes++;
                            continue;
                        }
                        stats.functionCompositions++;
                        stats.generatedPathFunctions++;
                        FunctionalLabel generated = new FunctionalLabel(
                                extension.work().edge().target(),
                                label,
                                extension.work().edge().arcId(),
                                extension.work().trace(),
                                extension.arrival(),
                                Domain.canonicalTime(
                                        extension.arrival()
                                                .minimumTravelTime(
                                                        extension.arrival()
                                                                .domain())
                                                + extension.work()
                                                        .remaining()),
                                stats.nextLabelOrdinal++);
                        List<FunctionalLabel> sameNode = labelsByNode
                                .computeIfAbsent(
                                        generated.node(),
                                        ignored -> new ArrayList<>());
                        if (safelyDominated(generated, sameNode, stats)) {
                            continue;
                        }
                        sameNode.add(generated);
                        queue.add(generated);
                        stats.priorityQueuePushes++;
                        if (++stats.labelsGenerated > config.maxLabels()) {
                            stats.pathCapReached = true;
                            break;
                        }
                    }
                    if (stats.timeCapReached || stats.pathCapReached) {
                        break;
                    }
                }
                if (queue.isEmpty() && !stats.timeCapReached
                        && !stats.pathCapReached) {
                    certified = true;
                }
            }
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
        } finally {
            if (executors.local() && executors.executor() != null) {
                executors.executor().shutdownNow();
            }
        }

        long canonicalEnvelopeStarted = clock.getAsLong();
        try {
            profile = FastestEnvelopeExtractor.extract(
                    selectedCandidates(profile),
                    query.departureDomain(),
                    deadline::expired);
        } catch (CancellationException cancelled) {
            stats.timeCapReached = true;
        } finally {
            long canonicalEnvelopeNanos = nonnegative(
                    clock.getAsLong() - canonicalEnvelopeStarted);
            instrumentation.addTiming(
                    "envelope_extraction", canonicalEnvelopeNanos);
            instrumentation.setTiming(
                    "envelope_canonicalization", canonicalEnvelopeNanos);
        }

        long scoringStarted = clock.getAsLong();
        ScoringOutcome scoring = scoreSelectedPaths(
                graph,
                profile,
                selectedTerminalLabels,
                deadline::expired,
                stats);
        profile = scoring.profile();
        long scoringNanos = nonnegative(clock.getAsLong() - scoringStarted);
        instrumentation.setTiming("posthoc_scoring", scoringNanos);
        instrumentation.setTiming("profiling", scoringNanos);
        if (!scoring.complete()) {
            stats.timeCapReached = true;
        }

        stats.outputSubintervals = profile.segments().size();
        stats.distinctFastestPaths = profile.segments().stream()
                .filter(EnvelopeSegment::found)
                .map(segment -> segment.path().arcIds())
                .distinct()
                .count();
        stats.fullIntervalCoverage = fullCoverage(profile);
        stats.postHocBudgetFeasibleCoverage = budgetCoverage(
                profile, query.maxTravelTime());
        stats.outputLoopless = profile.segments().stream()
                .filter(EnvelopeSegment::found)
                .allMatch(segment -> LooplessChecker.isLoopless(
                        graph, segment.path()));
        boolean anyFeasible = profile.segments().stream()
                .anyMatch(EnvelopeSegment::found);
        long elapsed = deadline.elapsedNanos();
        instrumentation.setTiming("algorithm_total", elapsed);
        Map<String, Long> recordedTimings = instrumentation.timings();
        long explicitlyTimed = List.of(
                "lower_bound_preprocessing",
                "functional_composition",
                "envelope_extraction",
                "posthoc_scoring").stream()
                .mapToLong(name -> recordedTimings.getOrDefault(name, 0L))
                .sum();
        instrumentation.setTiming(
                "functional_search_control",
                Math.max(0L, elapsed - explicitlyTimed));
        publish(stats, instrumentation);

        ExperimentStatus status;
        ExactnessScope exactness;
        String completion;
        List<String> capTriggered = new ArrayList<>();
        if (stats.timeCapReached
                || (!certified && deadline.finalizationDue())) {
            status = ExperimentStatus.TIME_CAPPED_NOT_CERTIFIED;
            exactness = ExactnessScope.NOT_CERTIFIED;
            completion = "TIME_CAPPED_NOT_CERTIFIED";
            capTriggered.add("QUERY_DEADLINE");
        } else if (stats.pathCapReached) {
            status = ExperimentStatus.PATH_CAPPED_NOT_CERTIFIED;
            exactness = ExactnessScope.NOT_CERTIFIED;
            completion = "PATH_CAPPED_NOT_CERTIFIED";
            capTriggered.add("PATH_LIMIT");
        } else if (!anyFeasible) {
            status = ExperimentStatus.NO_FEASIBLE_PATH;
            exactness = ExactnessScope.GLOBAL_CERTIFIED;
            completion = "NO_FEASIBLE_PATH";
        } else if (certified) {
            status = ExperimentStatus.CERTIFIED_COMPLETE;
            exactness = ExactnessScope.GLOBAL_CERTIFIED;
            completion = "CERTIFIED_COMPLETE";
        } else {
            status = ExperimentStatus.ERROR;
            exactness = ExactnessScope.NOT_CERTIFIED;
            completion = "ERROR";
        }

        Map<String, Object> scalars = stats.scalars();
        scalars.put("algorithm_completion_status", completion);
        scalars.put(
                "generation_completion",
                exactness == ExactnessScope.GLOBAL_CERTIFIED
                        ? anyFeasible
                                ? "COMPLETE" : "NO_FEASIBLE_PATH"
                        : status == ExperimentStatus.ERROR
                                ? "ABORTED" : "RESOURCE_TRUNCATED");
        scalars.put("cap_triggered", List.copyOf(capTriggered));
        scalars.put("query_deadline_nanos", limit);
        scalars.put("finalization_reserve_nanos", reserve);
        scalars.put(
                "query_independent_lower_bound_weights_prepared",
                preparedGraph == graph && preparedLower != null);
        scalars.put(
                "query_independent_common_support_prepared",
                preparedGraph == graph
                        && Double.isFinite(preparedSupportEnd));
        scalars.put("lower_bound_oracle", lowerBoundName);
        scalars.put("preference_score_used_for_search", false);
        scalars.put("pcmax_budget_used_for_search", false);
        scalars.put("score_profiles_constructed_during_search", 0L);
        scalars.put("posthoc_score_complete", scoring.complete());
        scalars.put("allfp_search_executed", 1L);
        scalars.put("allfp_budget_variant_reuse_hit", 0L);
        scalars.put("allfp_runtime_reused_from_source", false);
        scalars.put("output_feasible", anyFeasible);
        scalars.put(
                "output_validation_contract",
                "CANONICAL_EXACT_PROFILE_AND_VERTEX_SIMPLE-v1");
        return new AlgorithmResult(
                status,
                profile,
                exactness,
                scalars,
                status == ExperimentStatus.ERROR
                        ? "AllFpIncomplete" : null,
                status == ExperimentStatus.ERROR
                        ? "functional search ended without a proof or explicit cap"
                        : null);
    }

    /**
     * Projects one budget-independent allFP execution to another PC-Max budget.
     * Search status, profile, timings, and exactness remain those of the measured
     * source trial; only the explicitly post-hoc feasibility statistic changes.
     */
    public static AlgorithmResult withPostHocBudget(
            AlgorithmResult source,
            double budget,
            String sourceQueryId,
            double sourceBudget) {
        if (source == null || source.profile() == null) {
            return source;
        }
        Map<String, Object> scalars = new LinkedHashMap<>(source.scalars());
        scalars.put(
                "posthoc_budget_feasible_coverage_fraction",
                budgetCoverage(source.profile(), budget));
        scalars.put("allfp_search_executed", 0L);
        scalars.put("allfp_budget_variant_reuse_hit", 1L);
        scalars.put("allfp_runtime_reused_from_source", true);
        scalars.put("allfp_search_source_query_id", sourceQueryId);
        scalars.put("allfp_search_source_budget", sourceBudget);
        scalars.put("allfp_projected_budget", budget);
        return new AlgorithmResult(
                source.status(),
                source.profile(),
                source.exactnessScope(),
                scalars,
                source.errorType(),
                source.errorMessage());
    }

    private ExecutorSelection executors(
            TDGraph graph,
            AlgorithmConfig config) {
        int threads = Math.max(1, config.threads());
        if (threads == 1) {
            return new ExecutorSelection(null, false, false);
        }
        if (preparedGraph == graph && preparedThreads == threads
                && preparedExecutor != null) {
            return new ExecutorSelection(
                    preparedExecutor, true, false);
        }
        return new ExecutorSelection(workerPool(threads), true, true);
    }

    private static ExecutorService workerPool(int threads) {
        AtomicInteger identifiers = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "allfp-functional-worker-"
                            + identifiers.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private List<Extension> extensions(
            FunctionalLabel label,
            List<EdgeWork> work,
            Map<Integer, TimeProfile> edgeArrivalCache,
            QueryDeadline deadline,
            ExecutorService executor,
            boolean parallel,
            MutableStats stats) {
        if (work.isEmpty()) {
            return List.of();
        }
        if (!parallel || work.size() == 1) {
            List<Extension> result = new ArrayList<>(work.size());
            for (EdgeWork edgeWork : work) {
                result.add(extension(
                        label,
                        edgeWork,
                        edgeArrivalCache,
                        deadline::finalizationDue));
            }
            stats.observedWorkers = Math.max(stats.observedWorkers, 1);
            return result;
        }

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Future<Extension>> futures = new ArrayList<>(work.size());
        stats.parallelFunctionalTasks += work.size();
        for (EdgeWork edgeWork : work) {
            futures.add(executor.submit(() -> {
                int workers = active.incrementAndGet();
                maximum.accumulateAndGet(workers, Math::max);
                try {
                    return extension(
                            label,
                            edgeWork,
                            edgeArrivalCache,
                            deadline::finalizationDue);
                } finally {
                    active.decrementAndGet();
                }
            }));
        }
        List<Extension> result = new ArrayList<>(work.size());
        try {
            // Deterministic reduction: futures are consumed in stable arc-id
            // order, independent of worker completion order.
            for (Future<Extension> future : futures) {
                result.add(future.get());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException(
                    "parallel allFP expansion was interrupted");
        } catch (ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof CancellationException cancelled) {
                throw cancelled;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "parallel allFP expansion failed", cause);
        } finally {
            if (result.size() != futures.size()) {
                futures.forEach(future -> future.cancel(true));
            }
            stats.observedWorkers = Math.max(
                    stats.observedWorkers, maximum.get());
        }
        return result;
    }

    private Extension extension(
            FunctionalLabel label,
            EdgeWork work,
            Map<Integer, TimeProfile> edgeArrivalCache,
            BooleanSupplier cancelled) {
        long started = clock.getAsLong();
        TimeProfile edgeArrival = edgeArrivalCache.computeIfAbsent(
                work.edge().arcId(),
                ignored -> edgeArrival(work.edge()));
        TimeProfile arrival = label.arrival().composeOrNull(
                edgeArrival,
                work.trace().fingerprint(),
                cancelled);
        return new Extension(
                work,
                arrival,
                nonnegative(clock.getAsLong() - started));
    }

    private static TimeProfile edgeArrival(Edge edge) {
        PiecewiseLinearFn travel = edge.travelTimeFunction();
        List<TimeProfile.Breakpoint> points = travel.breakpoints().stream()
                .map(point -> new TimeProfile.Breakpoint(
                        point.minute(),
                        point.minute() + point.value()))
                .toList();
        return TimeProfile.piecewise(
                travel.domain(),
                points,
                "allfp:arc=" + edge.arcId());
    }

    private static boolean safelyDominated(
            FunctionalLabel candidate,
            List<FunctionalLabel> existing,
            MutableStats stats) {
        for (FunctionalLabel label : existing) {
            stats.dominanceComparisons++;
            Domain candidateDomain = candidate.arrival().domain();
            if (!candidateDomain.difference(
                    label.arrival().domain()).isEmpty()) {
                continue;
            }
            if (!label.trace().verticesSubsetOf(candidate.trace())) {
                continue;
            }
            if (label.arrival().noLaterThan(
                    candidate.arrival(), candidateDomain)) {
                stats.dominatedLabels++;
                return true;
            }
        }
        return false;
    }

    private static CandidateProfile unscoredCandidate(
            FunctionalLabel label) {
        Domain domain = label.arrival().domain();
        return new CandidateProfile(
                domain,
                label.arrival(),
                ScoreProfile.constant(domain, 0),
                PathPointer.of(label.trace().arcIds()),
                0,
                -1,
                false);
    }

    private static ScoringOutcome scoreSelectedPaths(
            TDGraph graph,
            EnvelopeProfile profile,
            Map<List<Integer>, FunctionalLabel> terminalLabels,
            BooleanSupplier cancelled,
            MutableStats stats) {
        Map<List<Integer>, CandidateProfile> selected = new TreeMap<>(
                PathPointer.STABLE_PATH_ORDER);
        profile.segments().stream()
                .filter(EnvelopeSegment::found)
                .map(EnvelopeSegment::candidate)
                .forEach(candidate -> selected.putIfAbsent(
                        candidate.stablePathId(), candidate));
        Map<List<Integer>, CandidateProfile> scored = new TreeMap<>(
                PathPointer.STABLE_PATH_ORDER);
        boolean complete = true;
        for (Map.Entry<List<Integer>, CandidateProfile> entry
                : selected.entrySet()) {
            if (cancelled.getAsBoolean()
                    || Thread.currentThread().isInterrupted()) {
                complete = false;
                break;
            }
            FunctionalLabel terminal = terminalLabels.get(entry.getKey());
            if (terminal == null) {
                throw new IllegalStateException(
                        "missing terminal label for selected allFP path "
                                + entry.getKey());
            }
            try {
                scored.put(
                        entry.getKey(),
                        scoreCandidate(
                                graph,
                                terminal,
                                entry.getValue(),
                                cancelled));
                stats.posthocScoredPaths++;
            } catch (CancellationException deadline) {
                complete = false;
                break;
            }
        }
        List<EnvelopeSegment> segments = new ArrayList<>();
        for (EnvelopeSegment segment : profile.segments()) {
            if (!segment.found()) {
                segments.add(segment);
                continue;
            }
            segments.add(new EnvelopeSegment(
                    segment.interval(),
                    scored.getOrDefault(
                            segment.candidate().stablePathId(),
                            segment.candidate())));
        }
        return new ScoringOutcome(
                new EnvelopeProfile(profile.domain(), segments),
                complete && scored.size() == selected.size());
    }

    private static CandidateProfile scoreCandidate(
            TDGraph graph,
            FunctionalLabel terminal,
            CandidateProfile candidate,
            BooleanSupplier cancelled) {
        List<FunctionalLabel> chain = new ArrayList<>();
        for (FunctionalLabel cursor = terminal;
                cursor.parent() != null;
                cursor = cursor.parent()) {
            chain.add(cursor);
        }
        java.util.Collections.reverse(chain);
        Domain domain = candidate.domain();
        ScoreProfile score = ScoreProfile.constant(domain, 0);
        for (FunctionalLabel child : chain) {
            if (cancelled.getAsBoolean()
                    || Thread.currentThread().isInterrupted()) {
                throw new CancellationException(
                        "allFP post-hoc scoring reached its query deadline");
            }
            FunctionalLabel parent = child.parent();
            TimeProfile entry = parent.arrival().domain().equals(domain)
                    ? parent.arrival()
                    : parent.arrival().restrict(domain);
            Edge edge = graph.edges().get(child.incomingArc());
            ScoreProfile edgeScore = ScoreProfile.compose(
                    entry,
                    edge.scoreFunction(),
                    domain,
                    "allfp:posthoc-score:arc=" + edge.arcId());
            score = score.add(
                    edgeScore,
                    domain,
                    "allfp:posthoc-total:depth="
                            + child.trace().depth());
        }
        return new CandidateProfile(
                candidate.domain(),
                candidate.arrivalProfile(),
                score,
                candidate.pathPointer(),
                candidate.recursionDepth(),
                candidate.pivotId(),
                candidate.compressed(),
                candidate.usedPivotArcIds());
    }

    private static void retainSelectedTerminals(
            Map<List<Integer>, FunctionalLabel> labels,
            EnvelopeProfile profile) {
        Set<List<Integer>> selected = profile.segments().stream()
                .filter(EnvelopeSegment::found)
                .map(segment -> segment.candidate().stablePathId())
                .collect(java.util.stream.Collectors.toSet());
        labels.keySet().retainAll(selected);
    }

    private static OracleSelection lowerBoundOracle(TDGraph graph) {
        try {
            return new OracleSelection(
                    new DenseDijkstraLowerBoundOracle(graph),
                    "DenseDijkstraLowerBoundOracle");
        } catch (IllegalArgumentException unsuitable) {
            if (unsuitable.getMessage() == null
                    || !unsuitable.getMessage().startsWith(
                            "node identifiers are too sparse")) {
                throw unsuitable;
            }
            return new OracleSelection(
                    new ExactDijkstraLowerBoundOracle(graph),
                    "ExactDijkstraLowerBoundOracle");
        }
    }

    private static double commonSupportEnd(
            TDGraph graph,
            QuerySpec query,
            BooleanSupplier cancelled) {
        double supportEnd = Double.POSITIVE_INFINITY;
        int visited = 0;
        for (Edge edge : graph.edges()) {
            if ((visited++ & 1023) == 0
                    && (cancelled.getAsBoolean()
                        || Thread.currentThread().isInterrupted())) {
                throw new CancellationException(
                        "allFP support validation reached its query deadline");
            }
            double edgeEnd = edge.travelTimeFunction().domain()
                    .intervals().stream()
                    .mapToDouble(Domain.Interval::end)
                    .max().orElse(Double.NEGATIVE_INFINITY);
            supportEnd = Math.min(supportEnd, edgeEnd);
        }
        if (!Double.isFinite(supportEnd)
                || (query != null
                    && supportEnd < query.departureEndExact().doubleValue())) {
            throw new IllegalArgumentException(
                    "allFP query lies outside common temporal support");
        }
        return supportEnd;
    }

    private static boolean fullCoverage(EnvelopeProfile profile) {
        double total = profile.domain().intervals().stream()
                .mapToDouble(interval -> interval.end() - interval.start())
                .sum();
        double covered = profile.segments().stream()
                .filter(EnvelopeSegment::found)
                .mapToDouble(segment -> segment.interval().end()
                        - segment.interval().start())
                .sum();
        if (total == 0) {
            return profile.segments().stream()
                    .anyMatch(EnvelopeSegment::found);
        }
        return Domain.sameTime(total, covered);
    }

    private static double budgetCoverage(
            EnvelopeProfile profile,
            double budget) {
        double total = profile.domain().intervals().stream()
                .mapToDouble(interval -> interval.end() - interval.start())
                .sum();
        double feasible = 0.0;
        for (EnvelopeSegment segment : profile.segments()) {
            if (!segment.found()) {
                continue;
            }
            Domain cell = Domain.of(segment.interval())
                    .intersection(segment.candidate().domain());
            Domain withinBudget = segment.candidate().arrivalProfile()
                    .domainWhereTravelTimeAtMost(cell, budget);
            feasible += withinBudget.intervals().stream()
                    .mapToDouble(interval -> interval.end()
                            - interval.start())
                    .sum();
        }
        if (total == 0.0) {
            return profile.segments().stream()
                    .anyMatch(EnvelopeSegment::found) ? 1.0 : 0.0;
        }
        return feasible / total;
    }

    private static CandidateSet selectedCandidates(
            EnvelopeProfile committed,
            CandidateProfile additional) {
        Map<List<Integer>, CandidateProfile> selected = new TreeMap<>(
                PathPointer.STABLE_PATH_ORDER);
        committed.segments().stream()
                .filter(EnvelopeSegment::found)
                .map(EnvelopeSegment::candidate)
                .forEach(candidate -> selected.putIfAbsent(
                        candidate.stablePathId(), candidate));
        selected.put(additional.stablePathId(), additional);
        CandidateSet result = new CandidateSet();
        result.addAllCandidates(List.copyOf(selected.values()));
        return result;
    }

    private static CandidateSet selectedCandidates(
            EnvelopeProfile committed) {
        Map<List<Integer>, CandidateProfile> selected = new TreeMap<>(
                PathPointer.STABLE_PATH_ORDER);
        committed.segments().stream()
                .filter(EnvelopeSegment::found)
                .map(EnvelopeSegment::candidate)
                .forEach(candidate -> selected.putIfAbsent(
                        candidate.stablePathId(), candidate));
        CandidateSet result = new CandidateSet();
        result.addAllCandidates(List.copyOf(selected.values()));
        return result;
    }

    private static void publish(
            MutableStats stats,
            ExperimentInstrumentation instrumentation) {
        stats.scalars().forEach((name, value) -> {
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long) {
                instrumentation.addCounter(
                        name, ((Number) value).longValue());
            }
        });
    }

    private static long nonnegative(long value) {
        return Math.max(0L, value);
    }

    private record FunctionalLabel(
            int node,
            FunctionalLabel parent,
            int incomingArc,
            PathTrace trace,
            TimeProfile arrival,
            double priority,
            long ordinal) {
    }

    private static final class PathTrace {
        private final PathTrace parent;
        private final int vertex;
        private final int incomingArc;
        private final int depth;
        private final long hash;
        private volatile List<Integer> arcIds;

        private PathTrace(
                PathTrace parent,
                int vertex,
                int incomingArc,
                int depth,
                long hash) {
            this.parent = parent;
            this.vertex = vertex;
            this.incomingArc = incomingArc;
            this.depth = depth;
            this.hash = hash;
        }

        static PathTrace root(int source) {
            return new PathTrace(
                    null,
                    source,
                    -1,
                    0,
                    0x9E3779B97F4A7C15L ^ source);
        }

        PathTrace extend(Edge edge) {
            long nextHash = Long.rotateLeft(hash, 11)
                    ^ (0x9E3779B97F4A7C15L
                        * (Integer.toUnsignedLong(edge.arcId()) + 1));
            return new PathTrace(
                    this,
                    edge.target(),
                    edge.arcId(),
                    depth + 1,
                    nextHash);
        }

        int depth() {
            return depth;
        }

        long stableHash() {
            return hash;
        }

        boolean containsVertex(int candidate) {
            for (PathTrace cursor = this;
                    cursor != null;
                    cursor = cursor.parent) {
                if (cursor.vertex == candidate) {
                    return true;
                }
            }
            return false;
        }

        boolean verticesSubsetOf(PathTrace other) {
            for (PathTrace cursor = this;
                    cursor != null;
                    cursor = cursor.parent) {
                if (!other.containsVertex(cursor.vertex)) {
                    return false;
                }
            }
            return true;
        }

        List<Integer> arcIds() {
            List<Integer> existing = arcIds;
            if (existing != null) {
                return existing;
            }
            int[] values = new int[depth];
            PathTrace cursor = this;
            for (int index = depth - 1; index >= 0; index--) {
                values[index] = cursor.incomingArc;
                cursor = cursor.parent;
            }
            List<Integer> materialized = new ArrayList<>(depth);
            for (int value : values) {
                materialized.add(value);
            }
            existing = List.copyOf(materialized);
            arcIds = existing;
            return existing;
        }

        String fingerprint() {
            return "allfp:path=" + Long.toUnsignedString(hash)
                    + ":depth=" + depth;
        }
    }

    private record EdgeWork(
            Edge edge,
            double remaining,
            PathTrace trace) {
    }

    private record Extension(
            EdgeWork work,
            TimeProfile arrival,
            long compositionNanos) {
    }

    private record OracleSelection(
            LowerBoundOracle oracle,
            String name) {
    }

    private record ExecutorSelection(
            ExecutorService executor,
            boolean parallel,
            boolean local) {
    }

    private record ScoringOutcome(
            EnvelopeProfile profile,
            boolean complete) {
    }

    private static final class MutableStats {
        private long expandedFunctionalLabels;
        private long labelsGenerated;
        private long generatedPathFunctions;
        private long functionCompositions;
        private long priorityQueuePushes;
        private long priorityQueuePops;
        private long priorityQueuePeeks;
        private long lowerBoundEvaluations;
        private long lowerBoundPrunes;
        private long loopPrunes;
        private long horizonPrunes;
        private long expansions;
        private long terminalCandidates;
        private long fullyProfiledTerminalPaths;
        private long rejectedTerminalPaths;
        private long partialProfilesDiscarded;
        private long terminalEnvelopeUpdatesDiscarded;
        private long dominanceComparisons;
        private long dominatedLabels;
        private long profileBreakpoints;
        private long outputSubintervals;
        private long distinctFastestPaths;
        private long terminalArrivalProfilesReused;
        private long posthocScoredPaths;
        private long parallelFunctionalTasks;
        private long functionalCompositionWorkerNanos;
        private long requestedWorkers;
        private long observedWorkers;
        private long nextLabelOrdinal;
        private boolean lowerBorderStopTriggered;
        private boolean timeCapReached;
        private boolean pathCapReached;
        private boolean fullIntervalCoverage;
        private boolean outputLoopless;
        private double postHocBudgetFeasibleCoverage;

        private Map<String, Object> scalars() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("expanded_functional_labels", expandedFunctionalLabels);
            result.put("functional_labels_generated", labelsGenerated);
            result.put("generated_path_functions", generatedPathFunctions);
            result.put("function_compositions", functionCompositions);
            result.put("priority_queue_pushes", priorityQueuePushes);
            result.put("priority_queue_pops", priorityQueuePops);
            result.put("priority_queue_peeks", priorityQueuePeeks);
            result.put("lower_bound_evaluations", lowerBoundEvaluations);
            result.put("lower_bound_prunes", lowerBoundPrunes);
            result.put("loop_prunes", loopPrunes);
            result.put("horizon_prunes", horizonPrunes);
            result.put("functional_expansions", expansions);
            result.put("terminal_candidates", terminalCandidates);
            result.put("fully_profiled_terminal_paths",
                    fullyProfiledTerminalPaths);
            result.put("rejected_terminal_paths", rejectedTerminalPaths);
            result.put("partial_profiles_discarded",
                    partialProfilesDiscarded);
            result.put("completed_terminal_envelope_updates_discarded",
                    terminalEnvelopeUpdatesDiscarded);
            result.put("dominance_comparisons", dominanceComparisons);
            result.put("dominated_labels", dominatedLabels);
            result.put("profile_breakpoints", profileBreakpoints);
            result.put("output_subintervals", outputSubintervals);
            result.put("distinct_fastest_paths", distinctFastestPaths);
            result.put("terminal_arrival_profiles_reused",
                    terminalArrivalProfilesReused);
            result.put("posthoc_scored_paths", posthocScoredPaths);
            result.put("parallel_functional_tasks", parallelFunctionalTasks);
            result.put("functional_composition_worker_nanos",
                    functionalCompositionWorkerNanos);
            result.put("requested_workers", requestedWorkers);
            result.put("observed_workers", observedWorkers);
            result.put("lower_border_stop_triggered",
                    lowerBorderStopTriggered);
            result.put("deadline_cap_triggered", timeCapReached);
            result.put("path_cap_triggered", pathCapReached);
            result.put("full_interval_coverage", fullIntervalCoverage);
            result.put("output_loopless", outputLoopless);
            result.put("posthoc_budget_feasible_coverage_fraction",
                    postHocBudgetFeasibleCoverage);
            return result;
        }
    }
}
