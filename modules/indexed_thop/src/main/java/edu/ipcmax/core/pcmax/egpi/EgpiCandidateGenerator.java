package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;
import edu.ipcmax.baselines.exact.preference.CanonicalExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.EnvelopeGain;
import edu.ipcmax.baselines.exact.preference.GainSum;
import edu.ipcmax.baselines.exact.preference.ExactEnvelopeBuilder;
import edu.ipcmax.baselines.exact.preference.IncrementalExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.JournaledExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ScoreStepEnvelope;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.pcmax.IPCMaxParallelExecutor;
import edu.ipcmax.core.pcmax.PaceAlgorithmVersion;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.pcmax.SafeQueryRegion;
import edu.ipcmax.core.pcmax.VersionedCandidateGenerator;
import edu.ipcmax.core.validate.Path;

/**
 * PACE-B-EGPI-V1: envelope-guided pivot insertion over complete routes.
 *
 * <p>The search grows complete source-to-destination routes. Starting from the
 * canonical static witness route, a proposal jointly chooses a frontier route,
 * one of its anchor gaps {@code a->b}, and a preference-bearing pivot arc
 * {@code u->v}; it replaces the gap connector by {@code a->u}, the pivot and
 * {@code v->b}, built by the structural witness store under the exact
 * exclusions that keep the route vertex-simple. Proposals are prioritized by
 * an inexpensive estimate of the preference the pivot could add where the
 * current exact envelope is weak; the estimate is refreshed lazily when the
 * envelope version changes and is never used as a proof of anything.</p>
 *
 * <p>Selected complete routes are evaluated exactly, one departure chunk per
 * unit, by composing lazily computed entry-time transfers. A unit is
 * committed only after it completes: the validated registry is append-only
 * and the envelope over it never loses coverage or preference. The search
 * stops at the first exhausted work budget or at the monotonic deadline and
 * returns the last committed state. It is not exhaustive and never certifies
 * global optimality.</p>
 */
public final class EgpiCandidateGenerator implements VersionedCandidateGenerator<EgpiResult> {
    public static final String REGISTRY_SCOPE = "PACE_B_EGPI_V1_APPEND_ONLY_VALIDATED_REGISTRY";
    public static final String CONNECTOR_WITNESS_POLICY = PathWitness.POLICY;
    public static final String LAZY_PROFILE_CACHE_POLICY =
            "QUERY_SCOPED_EXACT_ENTRY_DOMAIN_LAZY_TRANSFER_V1+PREFIX_STATE_REUSE_V1";
    private static final double BASELINE_UNIT_BONUS = 2;
    private static final double CONTINUATION_DECAY = 0.9;

    /** Receives every commit, in publication order, on the coordinator thread. */
    public interface CommitObserver {
        void onCommit(EgpiAnytime.Commit commit);
    }

    /** Test seam: runs on the worker thread immediately before a unit is evaluated. */
    interface UnitHook {
        void beforeEvaluate(int epoch, int batchIndex, int routeId, int chunk);
    }

    private final TDGraph graph;
    private final QueryPreparationIndexes indexes;
    private final EgpiOptions options;
    private final EgpiEngineOptions engine;
    private volatile CommitObserver observer;
    private volatile UnitHook unitHook;
    private final int threads;
    private final boolean recordHistory;
    private final String temporalIdentity;

    public EgpiCandidateGenerator(TDGraph graph, QueryPreparationIndexes indexes,
            EgpiOptions options, int threads) {
        this(graph, indexes, options, threads, false, "QUERY_GRAPH_TEMPORAL_FUNCTIONS");
    }

    public EgpiCandidateGenerator(TDGraph graph, QueryPreparationIndexes indexes,
            EgpiOptions options, EgpiEngineOptions engine, int threads) {
        this(graph, indexes, options, engine, threads, false, "QUERY_GRAPH_TEMPORAL_FUNCTIONS");
    }

    /**
     * @param recordHistory keep an envelope snapshot after every commit (tests and audits)
     * @param temporalIdentity identity of the temporal edge data in exact profile cache keys
     */
    public EgpiCandidateGenerator(TDGraph graph, QueryPreparationIndexes indexes,
            EgpiOptions options, int threads, boolean recordHistory, String temporalIdentity) {
        this(graph, indexes, options, EgpiEngineOptions.defaults(), threads, recordHistory, temporalIdentity);
    }

    public EgpiCandidateGenerator(TDGraph graph, QueryPreparationIndexes indexes,
            EgpiOptions options, EgpiEngineOptions engine, int threads, boolean recordHistory,
            String temporalIdentity) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.options = Objects.requireNonNull(options, "options");
        this.engine = Objects.requireNonNull(engine, "engine");
        if (threads < 1 || threads > 24) throw new IllegalArgumentException("EGPI supports 1 to 24 workers");
        this.threads = threads;
        this.recordHistory = recordHistory;
        this.temporalIdentity = Objects.requireNonNull(temporalIdentity, "temporalIdentity");
    }

    @Override public String algorithmVersion() { return PaceAlgorithmVersion.PACE_B_EGPI_V1.name(); }

    public EgpiOptions options() { return options; }

    /** Streams each committed unit to {@code observer}; commits are immutable. */
    public EgpiCandidateGenerator observe(CommitObserver observer) {
        this.observer = observer;
        return this;
    }

    EgpiCandidateGenerator withUnitHook(UnitHook hook) {
        this.unitHook = hook;
        return this;
    }

    public EgpiEngineOptions engine() { return engine; }

    @Override
    public EgpiResult generate(QuerySpec query) {
        Objects.requireNonNull(query, "query");
        EgpiSearchBudget budget = new EgpiSearchBudget(options.queryDeadlineMs(), engine.resultReserveMs());
        Search search = new Search(query, budget);
        try {
            return search.run();
        } finally {
            search.close();
        }
    }

    enum Role { NONE, EXPLOITATION, EXPLORATION }

    /** Mutable search bookkeeping for one structural route; owned by the coordinator. */
    private static final class RouteRecord {
        final int id;
        final int parentId;
        final EgpiRoute route;
        final Path path;
        final Set<Integer> arcs;
        final double[] gapPrefixCost;
        final double[] estimatedGain;
        final double estimatedOpportunity;
        Role role = Role.NONE;
        final java.util.EnumSet<Role> rolesHeld = java.util.EnumSet.noneOf(Role.class);
        boolean admittedForExact;
        int nextPivotRank;
        boolean expansionExhausted;
        boolean continuationQueued;
        int unitsQueued;
        int unitsCommitted;
        int unitsEmpty;
        int unitsCancelled;
        /** Gains credited by the ordered commits (R21), summed exactly as they arrive. */
        ExactTime coverageGain = ExactTime.ZERO;
        ExactTime preferenceGain = ExactTime.ZERO;
        /** Gains credited at epoch boundaries (R22): summed from approximations, exactly only on demand. */
        final GainSum credited = new GainSum();
        ExactTemporalDomain validatedDomain = ExactTemporalDomain.empty();
        double frontierScore;

        RouteRecord(int id, int parentId, EgpiRoute route, double[] gapPrefixCost,
                double[] estimatedGain, double estimatedOpportunity) {
            this.id = id;
            this.parentId = parentId;
            this.route = route;
            this.path = new Path(route.completeEdgeSequence());
            this.arcs = Set.copyOf(route.completeEdgeSequence());
            this.gapPrefixCost = gapPrefixCost;
            this.estimatedGain = estimatedGain;
            this.estimatedOpportunity = estimatedOpportunity;
        }

        int pivots() { return route.orderedPivotEdges().size(); }
        boolean improved() { return coverageGain.signum() > 0 || preferenceGain.signum() > 0 || credited.improved(); }

        /** The display double of the route's total coverage gain. */
        double coverageGainDisplay() { return credited.isEmpty() ? coverageGain.toDisplayDouble() : credited.coverageDisplay(); }

        /** The display double of the route's total integrated preference gain. */
        double preferenceGainDisplay() { return credited.isEmpty() ? preferenceGain.toDisplayDouble() : credited.preferenceDisplay(); }

        ExactTime coverageGainExact() { return credited.isEmpty() ? coverageGain : coverageGain.add(credited.coverage()); }

        ExactTime preferenceGainExact() { return credited.isEmpty() ? preferenceGain : preferenceGain.add(credited.preference()); }
    }

    /** An insertion proposal, or a continuation that expands the next pivot block of a route. */
    private record Proposal(long sequence, int routeId, int gap, int pivotRank, int pivotArc,
            double opportunity, double slack, double[] gain, long envelopeVersion) {
        boolean continuation() { return pivotRank < 0; }
        String tieKey() { return "r" + routeId + ":g" + gap + ":e" + pivotArc; }
    }

    private static final Comparator<Proposal> PROPOSAL_ORDER = Comparator
            .comparingDouble(Proposal::opportunity).reversed()
            .thenComparingInt(Proposal::routeId)
            .thenComparingInt(proposal -> proposal.continuation() ? 1 : 0)
            .thenComparingInt(Proposal::gap)
            .thenComparingInt(Proposal::pivotArc)
            .thenComparingLong(Proposal::sequence);

    /** A constructed complete route waiting for exact-evaluation admission. */
    private record Candidate(int routeId, double priority, long envelopeVersion) { }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingDouble(Candidate::priority).reversed()
            .thenComparingInt(Candidate::routeId);

    private record Unit(int routeId, int chunk, double priority, long envelopeVersion) { }

    private static final Comparator<Unit> UNIT_ORDER = Comparator
            .comparingDouble(Unit::priority).reversed()
            .thenComparingInt(Unit::routeId)
            .thenComparingInt(Unit::chunk);

    /** A finished (or failed) exact unit with the worker-side search-clock stamps of its evaluation. */
    private record UnitOutcome(EgpiRouteEvaluator.Evaluation evaluation, RuntimeException failure,
            long startNs, long finishNs, String worker) {
        UnitOutcome finishedAt(long finishNs) {
            return new UnitOutcome(evaluation, failure, startNs, finishNs, worker);
        }
    }

    /** Coordinator-side bookkeeping of one batch of exact units. */
    private static final class EpochTally {
        final int epoch;
        final int units;
        final long submitNs;
        long waitNs;
        long commitNs;
        long creditNs;
        long workerBusyNs;
        long lastFinishNs;
        long lastCommitEndNs;
        int processed;
        int committed;
        int cancelled;
        int validatedUncommitted;

        EpochTally(int epoch, int units, long submitNs) {
            this.epoch = epoch;
            this.units = units;
            this.submitNs = submitNs;
        }
    }

    private final class Search {
        final QuerySpec query;
        final EgpiSearchBudget budget;
        final ExactTemporalDomain departures;
        final ExactTime exactBudget;
        final long budgetTicks;
        final List<ExactTemporalDomain> chunks;
        final double[] chunkStart;
        final double[] chunkEnd;
        final double[] chunkWeight;
        final TreeMap<String, Long> counters = new TreeMap<>();
        final List<RouteRecord> routes = new ArrayList<>();
        final Map<List<Integer>, Integer> routeByEdges = new HashMap<>();
        /** Every committed exact chunk part, per route, in commit order. */
        final LinkedHashMap<Path, List<ExactPreferencePathProfile>> registry = new LinkedHashMap<>();
        /** Routes with at least one committed budget-feasible departure. */
        final Set<Path> registeredRoutes = new HashSet<>();
        final PriorityQueue<Proposal> proposals = new PriorityQueue<>(PROPOSAL_ORDER);
        final PriorityQueue<Candidate> candidates = new PriorityQueue<>(CANDIDATE_ORDER);
        final PriorityQueue<Unit> units = new PriorityQueue<>(UNIT_ORDER);
        final List<Integer> exploitation = new ArrayList<>();
        final List<Integer> exploration = new ArrayList<>();
        final List<ExactPreferenceEnvelope> history = new ArrayList<>();
        final List<Long> completedUnitNanos = new ArrayList<>();
        final List<EgpiAnytime.Commit> commitLog = new ArrayList<>();
        final List<EgpiAnytime.UnitEvent> unitEvents = new ArrayList<>();
        final List<EgpiAnytime.EpochEvent> epochEvents = new ArrayList<>();
        int epochCount;
        /** Improvement credit in batch order, independent of the order units were published in. */
        final ScoreStepEnvelope decisionCredit = new ScoreStepEnvelope();
        final List<Map<String, Object>> trace = new ArrayList<>();
        final Set<String> capsTriggered = new java.util.TreeSet<>();
        final AtomicInteger activeWorkers = new AtomicInteger();
        final AtomicInteger peakActiveWorkers = new AtomicInteger();
        final AtomicLong workerBusyNanos = new AtomicLong();
        final AtomicLong workerTasks = new AtomicLong();
        IPCMaxParallelExecutor executor;
        SafeQueryRegion region;
        EgpiPathWitnessStore store;
        EgpiStaticBounds bounds;
        EgpiPivotPool pivots;
        LazyConnectorProfileCache transfers;
        EgpiRouteEvaluator evaluator;
        ExactEnvelopeBuilder envelope;
        double[] coveredMeasure;
        double[] integratedPreference;
        double[] deficit;
        long deficitVersion = -1;
        double scoreNorm = 1;
        long proposalSequence;
        int proposalPops;
        int routesAdmitted;
        String stopReason;
        boolean deadline;

        Search(QuerySpec query, EgpiSearchBudget budget) {
            this.query = query;
            this.budget = budget;
            this.departures = ExactTemporalDomain.fromLegacy(query.departureDomain());
            this.exactBudget = ExactTime.fromCanonicalDouble(Domain.canonicalTime(query.maxTravelTime()));
            this.budgetTicks = Domain.canonicalTick(query.maxTravelTime());
            this.chunks = chunk(departures, options.departureChunks());
            this.chunkStart = new double[chunks.size()];
            this.chunkEnd = new double[chunks.size()];
            this.chunkWeight = new double[chunks.size()];
            double total = 0;
            for (int c = 0; c < chunks.size(); c++) {
                var interval = chunks.get(c).intervals().getFirst();
                chunkStart[c] = interval.start().toDisplayDouble();
                chunkEnd[c] = chunks.get(c).intervals().getLast().end().toDisplayDouble();
                chunkWeight[c] = Math.max(0, chunkEnd[c] - chunkStart[c]);
                total += chunkWeight[c];
            }
            for (int c = 0; c < chunks.size(); c++) {
                chunkWeight[c] = total > 0 ? chunkWeight[c] / total : 1.0 / chunks.size();
            }
            this.coveredMeasure = new double[chunks.size()];
            this.integratedPreference = new double[chunks.size()];
            this.deficit = new double[chunks.size()];
            java.util.Arrays.fill(deficit, 1);
        }

        EgpiResult run() {
            long started = System.nanoTime();
            try {
                prepare();
                if (stopReason == null) search();
            } catch (CancellationException cancelled) {
                deadline = budget.deadlineReached();
                stopReason = deadline ? "DEADLINE" : "CANCELLED";
            }
            if (stopReason == null) stopReason = "PROPOSALS_EXHAUSTED";
            long searchNanos = System.nanoTime() - started;
            // Stop workers first, so the result records whether every worker terminated.
            close();
            // Assembling the result reads only committed state. An interrupt that ended
            // the search must not abort it; the flag is restored for the caller.
            boolean interrupted = Thread.interrupted();
            try {
                return result(searchNanos);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        ExactEnvelopeBuilder newEnvelope() {
            return switch (engine.envelopeEngine()) {
                case CANONICAL_LAZY_V3 -> new CanonicalExactPreferenceEnvelope(departures);
                case REFINED_JOURNALED_V2 -> new JournaledExactPreferenceEnvelope(departures);
                case REFINED_STAGED_COPY_V1 -> new IncrementalExactPreferenceEnvelope(departures);
            };
        }

        void prepare() {
            long started = System.nanoTime();
            region = SafeQueryRegion.prepare(graph, indexes, query);
            add("egpi_region_preparation_ns", System.nanoTime() - started);
            add("egpi_corridor_arcs", region.corridor().directedArcIds().size());
            add("egpi_corridor_vertices", region.corridor().vertexIds().size());
            budget.check();
            store = new EgpiPathWitnessStore(graph, region.corridor(), region.lowerBounds(),
                    query.source(), query.destination(), options.structuralCacheCapacity(),
                    options.maxConnectorSearches(), budget);
            bounds = new EgpiStaticBounds(graph, region.corridor(), region.lowerBounds(), store);
            started = System.nanoTime();
            pivots = new EgpiPivotPool(region, store);
            add("egpi_pivot_pool_ns", System.nanoTime() - started);
            transfers = new LazyConnectorProfileCache(graph, temporalIdentity,
                    options.temporalProfileCacheCapacity(), options.temporalCacheMaxBytes(), budget);
            evaluator = new EgpiRouteEvaluator(transfers, options.prefixProfileCacheCapacity(),
                    options.prefixCacheMaxBytes());
            envelope = newEnvelope();
            scoreNorm = Math.max(1, pivots.maximumScore());
            if (threads > 1) executor = new IPCMaxParallelExecutor(threads);
            budget.check();
            Optional<PathWitness> baseline = store.forward(query.destination());
            if (baseline.isEmpty()) {
                // The corridor keeps every arc of every route whose static lower bound fits B.
                stopReason = "NO_STRUCTURAL_ROUTE_WITHIN_STATIC_BOUND";
                add("egpi_static_bound_certifies_no_route", 1);
                return;
            }
            EgpiRoute root = EgpiRoute.of(graph, query.source(), query.destination(),
                    List.of(baseline.get()), List.of());
            RouteRecord record = register(root, -1, new double[chunks.size()], 0, "BASELINE_STATIC_WITNESS");
            admitForExact(record);
            admitToFrontier(record, Role.EXPLORATION, Double.POSITIVE_INFINITY);
        }

        void search() {
            while (true) {
                if (budget.deadlineReached()) {
                    deadline = true;
                    stopReason = "DEADLINE";
                    return;
                }
                budget.check();
                boolean worked = false;
                if (structuralWorkAllowed()) worked |= structuralRound();
                if (stopReason != null) return;
                worked |= admitCandidates();
                if (!units.isEmpty()) worked |= exactRound();
                if (stopReason != null) return;
                if (!worked) {
                    stopReason = naturalStop();
                    return;
                }
            }
        }

        boolean structuralWorkAllowed() {
            if (proposals.isEmpty()) return false;
            if (proposalPops >= options.maxProposalPops()) {
                capsTriggered.add("MAX_PROPOSAL_POPS");
                return false;
            }
            if (!admissionOpen()) return false;
            return candidates.size() < options.proposalBatchSize()
                    && units.size() < 2L * options.exactUnitBatchSize();
        }

        /**
         * Admits the best constructed routes for exact evaluation only when the
         * unit queue runs low, so admission uses the current envelope rather
         * than the envelope at construction time.
         */
        boolean admitCandidates() {
            boolean admitted = false;
            refreshDeficits();
            while (units.size() < options.exactUnitBatchSize() && !candidates.isEmpty() && admissionOpen()) {
                Candidate candidate = candidates.poll();
                RouteRecord route = routes.get(candidate.routeId());
                if (candidate.envelopeVersion() != envelope.version()) {
                    double priority = 0;
                    for (int c = 0; c < chunks.size(); c++) {
                        priority += chunkWeight[c] * deficit[c] * route.estimatedGain[c];
                    }
                    Candidate refreshed = new Candidate(route.id, priority, envelope.version());
                    Candidate next = candidates.peek();
                    if (next != null && CANDIDATE_ORDER.compare(next, refreshed) < 0) {
                        candidates.add(refreshed);
                        continue;
                    }
                    candidate = refreshed;
                }
                if (candidate.priority() <= 0) {
                    increment("egpi_candidates_not_admitted_without_estimated_opportunity");
                    continue;
                }
                admitForExact(route);
                admitted = true;
            }
            return admitted;
        }

        boolean admissionOpen() {
            if (routesAdmitted >= options.maxExactRouteEvaluations()) {
                capsTriggered.add("MAX_EXACT_ROUTE_EVALUATIONS");
                return false;
            }
            if (registeredRoutes.size() >= options.maxRegistryRoutes()) {
                capsTriggered.add("MAX_REGISTRY_ROUTES");
                return false;
            }
            return true;
        }

        String naturalStop() {
            for (String cap : List.of("MAX_PROPOSAL_POPS", "MAX_EXACT_ROUTE_EVALUATIONS",
                    "MAX_REGISTRY_ROUTES", "MAX_CONNECTOR_SEARCHES")) {
                if (capsTriggered.contains(cap)) return cap;
            }
            return "PROPOSALS_EXHAUSTED";
        }

        // ------------------------------------------------------------------ structural phase

        boolean structuralRound() {
            int popsBefore = proposalPops;
            List<Proposal> batch = new ArrayList<>();
            refreshDeficits();
            while (batch.size() < options.proposalBatchSize() && !proposals.isEmpty()
                    && proposalPops < options.maxProposalPops()) {
                budget.check();
                Proposal proposal = proposals.poll();
                RouteRecord parent = routes.get(proposal.routeId());
                if (parent.role == Role.NONE) {
                    increment("egpi_proposals_discarded_parent_left_frontier");
                    if (proposal.continuation()) parent.continuationQueued = false;
                    continue;
                }
                if (!proposal.continuation() && proposal.envelopeVersion() != envelope.version()) {
                    Proposal refreshed = rescore(proposal);
                    increment("egpi_proposal_priority_refreshes");
                    Proposal next = proposals.peek();
                    if (next != null && PROPOSAL_ORDER.compare(next, refreshed) < 0) {
                        proposals.add(refreshed);
                        continue;
                    }
                    proposal = refreshed;
                }
                proposalPops++;
                increment("egpi_proposals_popped");
                if (proposal.continuation()) {
                    parent.continuationQueued = false;
                    increment("egpi_continuations_popped");
                    expand(parent, proposal.opportunity());
                    continue;
                }
                batch.add(proposal);
            }
            if (proposalPops >= options.maxProposalPops()) capsTriggered.add("MAX_PROPOSAL_POPS");
            if (!batch.isEmpty()) construct(batch);
            return proposalPops > popsBefore;
        }

        /** Builds connectors for a batch: all left connectors, then all right connectors. */
        void construct(List<Proposal> batch) {
            long started = System.nanoTime();
            List<EgpiPathWitnessStore.Request> left = new ArrayList<>(batch.size());
            for (Proposal proposal : batch) {
                RouteRecord parent = routes.get(proposal.routeId());
                PathWitness gap = parent.route.orderedConnectors().get(proposal.gap());
                var pivot = graph.edges().get(proposal.pivotArc());
                Set<Integer> forbidden = keptVertices(parent, proposal.gap());
                forbidden.remove(gap.source());
                forbidden.add(pivot.target());
                left.add(new EgpiPathWitnessStore.Request(gap.source(), pivot.source(), forbidden, Set.of(), ""));
            }
            List<EgpiPathWitnessStore.Resolution> lefts = store.resolve(left, executor);
            List<EgpiPathWitnessStore.Request> right = new ArrayList<>(batch.size());
            List<Integer> rightIndex = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                var resolution = lefts.get(i);
                if (resolution.budgetRejected()) {
                    reject("connector_search_budget");
                    capsTriggered.add("MAX_CONNECTOR_SEARCHES");
                    continue;
                }
                if (resolution.witness().isEmpty()) {
                    reject("left_connector_unreachable");
                    continue;
                }
                Proposal proposal = batch.get(i);
                RouteRecord parent = routes.get(proposal.routeId());
                PathWitness gap = parent.route.orderedConnectors().get(proposal.gap());
                var pivot = graph.edges().get(proposal.pivotArc());
                Set<Integer> forbidden = keptVertices(parent, proposal.gap());
                forbidden.remove(gap.destination());
                forbidden.addAll(resolution.witness().get().vertexIds());
                right.add(new EgpiPathWitnessStore.Request(pivot.target(), gap.destination(), forbidden, Set.of(), ""));
                rightIndex.add(i);
            }
            List<EgpiPathWitnessStore.Resolution> rights = store.resolve(right, executor);
            add("egpi_connector_construction_ns", System.nanoTime() - started);
            for (int r = 0; r < rights.size(); r++) {
                budget.check();
                int i = rightIndex.get(r);
                var resolution = rights.get(r);
                if (resolution.budgetRejected()) {
                    reject("connector_search_budget");
                    capsTriggered.add("MAX_CONNECTOR_SEARCHES");
                    continue;
                }
                if (resolution.witness().isEmpty()) {
                    reject("right_connector_unreachable");
                    continue;
                }
                assemble(batch.get(i), lefts.get(i).witness().get(), resolution.witness().get());
            }
        }

        void assemble(Proposal proposal, PathWitness left, PathWitness right) {
            RouteRecord parent = routes.get(proposal.routeId());
            EgpiRoute candidate;
            try {
                candidate = parent.route.insert(graph, proposal.gap(), proposal.pivotArc(), left, right);
            } catch (IllegalArgumentException invalid) {
                reject("assembly_not_vertex_simple");
                return;
            }
            long ticks = 0;
            for (int arc : candidate.completeEdgeSequence()) {
                ticks += Domain.canonicalTick(region.lowerBounds().edgeWeight(arc));
            }
            if (ticks > budgetTicks) {
                reject("exact_static_cost_exceeds_budget");
                return;
            }
            if (routeByEdges.containsKey(candidate.completeEdgeSequence())) {
                reject("duplicate_complete_route");
                return;
            }
            increment("egpi_routes_generated");
            RouteRecord record = register(candidate, parent.id, proposal.gain(), proposal.opportunity(),
                    proposal.tieKey());
            candidates.add(new Candidate(record.id, proposal.opportunity(), envelope.version()));
            if (record.pivots() < options.maxPivotsPerConstructedRoute()) {
                admitToFrontier(record, Role.EXPLORATION, explorationScore(record));
            }
        }

        Set<Integer> keptVertices(RouteRecord route, int gap) {
            PathWitness connector = route.route.orderedConnectors().get(gap);
            Set<Integer> kept = new HashSet<>(route.route.visitedVertices());
            List<Integer> vertices = connector.vertexIds();
            for (int i = 1; i + 1 < vertices.size(); i++) kept.remove(vertices.get(i));
            return kept;
        }

        /** Pushes proposals for the route's next block of qualified pivots over all gaps. */
        void expand(RouteRecord route, double priorPriority) {
            if (route.pivots() >= options.maxPivotsPerConstructedRoute()) {
                route.expansionExhausted = true;
                return;
            }
            double best = 0;
            int generated = 0;
            int end = route.nextPivotRank + options.proposalLookahead();
            for (int rank = route.nextPivotRank; rank < end; rank++) {
                budget.check();
                EgpiPivotPool.Pivot pivot = pivots.pivot(rank);
                if (pivot == null) {
                    route.expansionExhausted = true;
                    break;
                }
                for (int gap = 0; gap < route.route.orderedConnectors().size(); gap++) {
                    Proposal proposal = propose(route, gap, pivot);
                    if (proposal == null) continue;
                    proposals.add(proposal);
                    generated++;
                    best = Math.max(best, proposal.opportunity());
                }
            }
            route.nextPivotRank = end;
            add("egpi_proposals_generated", generated);
            if (!route.expansionExhausted) {
                // A barren block lowers the route's priority; the root's unbounded
                // initial priority must not keep it ahead of real proposals.
                double next = generated > 0 ? best * CONTINUATION_DECAY
                        : priorPriority >= Double.MAX_VALUE / 4 ? 0 : priorPriority * CONTINUATION_DECAY / 2;
                proposals.add(continuation(route, next));
            }
        }

        Proposal continuation(RouteRecord route, double priority) {
            route.continuationQueued = true;
            return new Proposal(proposalSequence++, route.id, 0, -1, -1, priority, 0, null, envelope.version());
        }

        /** Structural compatibility, certified static screen, and the opportunity estimate. */
        Proposal propose(RouteRecord route, int gap, EgpiPivotPool.Pivot pivot) {
            if (route.arcs.contains(pivot.arcId())) {
                reject("pivot_already_on_route");
                return null;
            }
            PathWitness connector = route.route.orderedConnectors().get(gap);
            int a = connector.source(), b = connector.destination();
            int u = pivot.tail(), v = pivot.head();
            Set<Integer> interior = new HashSet<>(connector.vertexIds().subList(1,
                    Math.max(1, connector.vertexIds().size() - 1)));
            boolean tailRepeats = u != a && route.route.visitedVertices().contains(u) && !interior.contains(u);
            boolean headRepeats = v != b && route.route.visitedVertices().contains(v) && !interior.contains(v);
            if (tailRepeats || headRepeats || u == b || v == a) {
                reject("pivot_repeats_kept_route_vertex");
                return null;
            }
            double leftBound = bounds.connector(a, u);
            double rightBound = bounds.connector(v, b);
            double lowerBound = route.route.staticLowerBoundCost() - connector.staticLowerBoundCost()
                    + leftBound + pivot.weight() + rightBound;
            double slack = query.maxTravelTime() - lowerBound;
            if (slack < -EgpiStaticBounds.REJECTION_TOLERANCE) {
                reject("static_lower_bound_exceeds_budget");
                return null;
            }
            double offset = route.gapPrefixCost[gap] + leftBound;
            double[] gain = new double[chunks.size()];
            boolean any = false;
            for (int c = 0; c < chunks.size(); c++) {
                gain[c] = pivot.maxScoreBetween(chunkStart[c] + offset - EgpiStaticBounds.REJECTION_TOLERANCE,
                        chunkEnd[c] + offset + Math.max(0, slack) + EgpiStaticBounds.REJECTION_TOLERANCE);
                any |= gain[c] > 0;
            }
            if (!any) {
                reject("no_pivot_score_in_reachable_entry_window");
                return null;
            }
            double detour = Math.max(0, leftBound + pivot.weight() + rightBound - connector.staticLowerBoundCost());
            double structural = 1 / (1 + detour / Math.max(query.maxTravelTime(), 1e-9));
            double slackFactor = 0.25 + 0.75 * clamp(slack / Math.max(query.maxTravelTime(), 1e-9));
            double opportunity = opportunity(gain, slackFactor * structural);
            return new Proposal(proposalSequence++, route.id, gap, pivot.rank(), pivot.arcId(),
                    opportunity, slack, scale(gain, slackFactor * structural), envelope.version());
        }

        Proposal rescore(Proposal proposal) {
            refreshDeficits();
            double opportunity = 0;
            for (int c = 0; c < chunks.size(); c++) opportunity += chunkWeight[c] * deficit[c] * proposal.gain()[c];
            return new Proposal(proposal.sequence(), proposal.routeId(), proposal.gap(), proposal.pivotRank(),
                    proposal.pivotArc(), opportunity, proposal.slack(), proposal.gain(), envelope.version());
        }

        double opportunity(double[] gain, double factor) {
            refreshDeficits();
            double result = 0;
            for (int c = 0; c < chunks.size(); c++) result += chunkWeight[c] * deficit[c] * gain[c] * factor;
            return result;
        }

        double explorationScore(RouteRecord route) {
            refreshDeficits();
            double slack = clamp((query.maxTravelTime() - route.route.staticLowerBoundCost())
                    / Math.max(query.maxTravelTime(), 1e-9));
            double weak = 0;
            for (int c = 0; c < chunks.size(); c++) weak += chunkWeight[c] * deficit[c];
            // Room for further insertions where the envelope is weak; the route's own estimate breaks ties.
            return slack * weak * scoreNorm + 1e-3 * route.estimatedOpportunity;
        }

        // ------------------------------------------------------------------ exact phase

        void admitForExact(RouteRecord route) {
            route.admittedForExact = true;
            routesAdmitted++;
            increment("egpi_routes_admitted_for_exact");
            refreshDeficits();
            for (int c = 0; c < chunks.size(); c++) {
                units.add(new Unit(route.id, c, unitPriority(route, c), envelope.version()));
                route.unitsQueued++;
            }
            add("egpi_units_queued", chunks.size());
        }

        double unitPriority(RouteRecord route, int chunk) {
            double priority = deficit[chunk] * (1 + route.estimatedGain[chunk] / scoreNorm);
            if (route.parentId < 0) priority += BASELINE_UNIT_BONUS;
            if (route.improved()) priority += 0.25;
            return priority;
        }

        boolean exactRound() {
            refreshDeficits();
            List<Unit> batch = new ArrayList<>();
            while (batch.size() < options.exactUnitBatchSize() && !units.isEmpty()) {
                Unit unit = units.poll();
                if (unit.envelopeVersion() != envelope.version()) {
                    Unit refreshed = new Unit(unit.routeId(), unit.chunk(),
                            unitPriority(routes.get(unit.routeId()), unit.chunk()), envelope.version());
                    Unit next = units.peek();
                    if (next != null && UNIT_ORDER.compare(next, refreshed) < 0) {
                        units.add(refreshed);
                        continue;
                    }
                    unit = refreshed;
                }
                batch.add(unit);
            }
            if (batch.isEmpty()) return false;
            if (!completedUnitNanos.isEmpty()) {
                List<Long> sorted = new ArrayList<>(completedUnitNanos);
                Collections.sort(sorted);
                long median = sorted.get(sorted.size() / 2);
                if (budget.remainingNanos() < median) {
                    // A typical unit would not finish before the deadline; keep the committed state.
                    stopReason = "REPLAY_BUDGET_RESERVE";
                    add("egpi_units_not_started_reserve", batch.size() + units.size());
                    return false;
                }
            }
            evaluateAndCommit(batch);
            return true;
        }

        /**
         * Evaluates a batch, in parallel when workers exist, and commits each
         * unit as soon as it and every earlier unit of the batch are done.
         * Commit order is the batch order for any worker count.
         */
        void evaluateAndCommit(List<Unit> batch) {
            EpochTally tally = new EpochTally(++epochCount, batch.size(), budget.elapsedNanos());
            try {
                if (engine.commitPolicy() == EgpiEngineOptions.CommitPolicy.COMPLETION_AWARE_V1
                        && executor != null && batch.size() > 1) {
                    evaluateAndCommitCompletion(batch, tally);
                } else {
                    evaluateAndCommitOrdered(batch, tally);
                }
            } finally {
                epochEvents.add(new EgpiAnytime.EpochEvent(tally.epoch, tally.units, tally.submitNs,
                        tally.lastFinishNs, tally.lastCommitEndNs, tally.waitNs, tally.commitNs, tally.creditNs,
                        tally.workerBusyNs, tally.committed, tally.cancelled, tally.validatedUncommitted, threads));
            }
        }

        List<Callable<UnitOutcome>> unitTasks(List<Unit> batch, java.util.concurrent.BlockingQueue<Integer> completed,
                int epoch) {
            List<Callable<UnitOutcome>> tasks = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                Unit unit = batch.get(index);
                final int batchIndex = index;
                RouteRecord route = routes.get(unit.routeId());
                ExactTemporalDomain chunk = chunks.get(unit.chunk());
                tasks.add(() -> {
                    try {
                        int active = activeWorkers.incrementAndGet();
                        peakActiveWorkers.accumulateAndGet(active, Math::max);
                        long startedNs = budget.elapsedNanos();
                        long started = System.nanoTime();
                        String worker = Thread.currentThread().getName();
                        UnitOutcome outcome;
                        try {
                            UnitHook hook = unitHook;
                            if (hook != null) hook.beforeEvaluate(epoch, batchIndex, unit.routeId(), unit.chunk());
                            outcome = new UnitOutcome(evaluator.evaluate(route.route, chunk, exactBudget, budget),
                                    null, startedNs, 0, worker);
                        } catch (RuntimeException failure) {
                            outcome = new UnitOutcome(null, failure, startedNs, 0, worker);
                        } finally {
                            workerBusyNanos.addAndGet(System.nanoTime() - started);
                            workerTasks.incrementAndGet();
                            activeWorkers.decrementAndGet();
                        }
                        return outcome.finishedAt(budget.elapsedNanos());
                    } finally {
                        // Signal even on an Error, so the coordinator never waits for a unit that cannot finish.
                        if (completed != null) completed.add(batchIndex);
                    }
                });
            }
            return tasks;
        }

        void evaluateAndCommitOrdered(List<Unit> batch, EpochTally tally) {
            List<Callable<UnitOutcome>> tasks = unitTasks(batch, null, tally.epoch);
            add("egpi_exact_batches", 1);
            boolean changed = false;
            if (executor == null || tasks.size() == 1) {
                for (int i = 0; i < tasks.size(); i++) {
                    UnitOutcome outcome;
                    try {
                        outcome = tasks.get(i).call();
                    } catch (Exception impossible) {
                        throw new IllegalStateException(impossible);
                    }
                    try {
                        changed |= commitOne(batch.get(i), outcome, tally, i);
                    } catch (CancellationException stopped) {
                        increment("egpi_units_validated_uncommitted_at_deadline");
                        tally.validatedUncommitted++;
                        unitEvents.add(unitEvent(batch.get(i), tally, i, outcome, 0, 0,
                                EgpiAnytime.VALIDATED_UNCOMMITTED_AT_STOP));
                        throw stopped;
                    }
                }
            } else {
                add("egpi_parallel_exact_units", tasks.size());
                List<java.util.concurrent.Future<UnitOutcome>> futures = executor.submitAll(tasks);
                int next = 0;
                try {
                    for (; next < futures.size(); next++) {
                        UnitOutcome outcome;
                        long waitStarted = budget.elapsedNanos();
                        try {
                            outcome = futures.get(next).get();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw cancellation(new IllegalStateException(interrupted));
                        } catch (java.util.concurrent.ExecutionException impossible) {
                            throw new IllegalStateException(impossible.getCause());
                        }
                        tally.waitNs += budget.elapsedNanos() - waitStarted;
                        changed |= commitOne(batch.get(next), outcome, tally, next);
                    }
                } catch (CancellationException stopped) {
                    countUncommitted(futures, next, batch, tally);
                    throw stopped;
                } finally {
                    for (var future : futures) future.cancel(true);
                }
            }
            if (changed) refreshDeficits();
        }

        /** How long the coordinator waits for a completion before it re-checks the deadline. */
        private static final long COMPLETION_POLL_MILLIS = 5;

        /**
         * A published unit awaiting epoch-boundary accounting: the profile that entered the
         * committed envelope, for the batch position it occupied.
         */
        record Published(Unit unit, ExactPreferencePathProfile profile) { }

        /**
         * Commits each unit as soon as it finishes and the coordinator is free, instead of waiting for
         * the units before it in the batch. Publication (the committed envelope and registry, which the
         * anytime answer is read from) follows completion order; the search decisions are not taken
         * from it. At the epoch boundary the published units are credited in batch order through
         * {@link ScoreStepEnvelope}, which reproduces exactly the per-unit gains the batch-order
         * commits of R21 report, so improvement credit, frontier promotion and every later proposal,
         * admission and priority are those of the ordered policy. A deadline stops publication at
         * once: finished-but-unpublished units are recorded as such and never added.
         */
        void evaluateAndCommitCompletion(List<Unit> batch, EpochTally tally) {
            int count = batch.size();
            java.util.concurrent.LinkedBlockingQueue<Integer> completed = new java.util.concurrent.LinkedBlockingQueue<>();
            List<Callable<UnitOutcome>> tasks = unitTasks(batch, completed, tally.epoch);
            add("egpi_exact_batches", 1);
            add("egpi_parallel_exact_units", count);
            List<java.util.concurrent.Future<UnitOutcome>> futures = executor.submitAll(tasks);
            Published[] published = new Published[count];
            boolean[] settled = new boolean[count];
            int remaining = count;
            try {
                while (remaining > 0) {
                    long waitStarted = budget.elapsedNanos();
                    Integer index;
                    try {
                        index = completed.poll(COMPLETION_POLL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw cancellation(new IllegalStateException(interrupted));
                    }
                    tally.waitNs += budget.elapsedNanos() - waitStarted;
                    if (index == null) {
                        budget.check();
                        continue;
                    }
                    UnitOutcome outcome;
                    try {
                        outcome = futures.get(index).get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw cancellation(new IllegalStateException(interrupted));
                    } catch (java.util.concurrent.ExecutionException impossible) {
                        throw new IllegalStateException(impossible.getCause());
                    }
                    publishUnit(batch.get(index), outcome, tally, index, published);
                    settled[index] = true;
                    remaining--;
                }
            } catch (CancellationException stopped) {
                // After a stop no search decision remains to be taken: skip the exact credit accounting, which
                // costs time proportional to the published units, and only classify what was left unpublished.
                for (Published unaccounted : published) if (unaccounted != null) increment("egpi_units_published_without_credit_at_stop");
                classifyUnsettled(futures, settled, batch, tally);
                throw stopped;
            } finally {
                for (var future : futures) future.cancel(true);
            }
            creditEpoch(published, tally);
        }

        /** Publishes one finished unit: the committed envelope and registry change; no search decision is taken. */
        void publishUnit(Unit unit, UnitOutcome outcome, EpochTally tally, int batchIndex, Published[] published) {
            RouteRecord route = routes.get(unit.routeId());
            tally.processed++;
            if (outcome.failure() != null) {
                if (!(outcome.failure() instanceof CancellationException)) throw outcome.failure();
                route.unitsCancelled++;
                increment("egpi_units_cancelled_unvalidated");
                tally.cancelled++;
                unitEvents.add(unitEvent(unit, tally, batchIndex, outcome, 0, 0, EgpiAnytime.CANCELLED_UNVALIDATED));
                return;
            }
            var evaluation = outcome.evaluation();
            completedUnitNanos.add(evaluation.nanos());
            add("egpi_exact_replay_ns", evaluation.nanos());
            tally.workerBusyNs += evaluation.nanos();
            tally.lastFinishNs = Math.max(tally.lastFinishNs, outcome.finishNs());
            Optional<ExactPreferencePathProfile> profile = evaluation.profile();
            long commitStarted = budget.elapsedNanos();
            if (profile.isEmpty()) {
                route.unitsCommitted++;
                route.unitsEmpty++;
                route.validatedDomain = route.validatedDomain.union(chunks.get(unit.chunk()));
                increment("egpi_units_committed_without_feasible_departure");
                finishCommit(unit, outcome, tally, batchIndex, null, false, false, EnvelopeGain.NONE,
                        commitStarted, 0, EgpiAnytime.COMMITTED_NO_FEASIBLE_DEPARTURE);
                return;
            }
            long started = System.nanoTime();
            IncrementalExactPreferenceEnvelope.Update update;
            try {
                update = envelope.add(profile.get(), budget);
            } catch (CancellationException cancelled) {
                add("egpi_envelope_update_ns", System.nanoTime() - started);
                throw cancelled;
            }
            long updateNanos = System.nanoTime() - started;
            add("egpi_envelope_update_ns", updateNanos);
            recordUpdateStats(update.stats());
            increment("egpi_envelope_commits");
            route.unitsCommitted++;
            route.validatedDomain = route.validatedDomain.union(chunks.get(unit.chunk()));
            registry.computeIfAbsent(route.path, ignored -> new ArrayList<>()).add(profile.get());
            boolean feasibleSupport = !profile.get().feasibleDomain().isEmpty();
            if (!feasibleSupport) {
                route.unitsEmpty++;
                increment("egpi_units_committed_without_feasible_departure");
            } else {
                registeredRoutes.add(route.path);
            }
            published[batchIndex] = new Published(unit, profile.get());
            if (recordHistory) history.add(envelope.snapshot());
            finishCommit(unit, outcome, tally, batchIndex, profile.get(), feasibleSupport, update.improved(),
                    update.gain(), commitStarted, updateNanos,
                    update.improved() ? EgpiAnytime.COMMITTED_IMPROVING : EgpiAnytime.COMMITTED_NONIMPROVING);
        }

        /**
         * Epoch-boundary accounting in batch order: credits each published unit's route with the exact
         * gain the ordered commits would have reported, and takes the promotions they would have taken.
         */
        void creditEpoch(Published[] published, EpochTally tally) {
            long creditStarted = System.nanoTime();
            try {
                creditPublished(published);
            } finally {
                long spent = System.nanoTime() - creditStarted;
                add("egpi_epoch_credit_ns", spent);
                tally.creditNs += spent;
            }
        }

        private void creditPublished(Published[] published) {
            boolean changed = false;
            for (Published entry : published) {
                if (entry == null) continue;
                if (budget.deadlineReached()) {
                    // The search is over: no later decision can use this credit, and its exact arithmetic must not
                    // run past the deadline.
                    increment("egpi_units_published_without_credit_at_stop");
                    continue;
                }
                Unit unit = entry.unit();
                RouteRecord route = routes.get(unit.routeId());
                ScoreStepEnvelope.Gain gain = decisionCredit.add(entry.profile());
                route.credited.add(gain);
                coveredMeasure[unit.chunk()] += gain.coverageDisplay();
                integratedPreference[unit.chunk()] += gain.preferenceDisplay();
                for (ExactScoreProfile.Segment segment : entry.profile().score().segments()) {
                    scoreNorm = Math.max(scoreNorm, segment.value().doubleValue() + pivots.maximumScore());
                }
                if (gain.improved()) {
                    increment("egpi_envelope_improving_commits");
                    promote(route);
                    changed = true;
                }
            }
            if (changed) refreshDeficits();
        }

        /** At a stop: unsettled units that finished are validated but unpublished; the others were cancelled. */
        void classifyUnsettled(List<java.util.concurrent.Future<UnitOutcome>> futures, boolean[] settled,
                List<Unit> batch, EpochTally tally) {
            for (int i = 0; i < futures.size(); i++) {
                if (settled[i]) continue;
                var future = futures.get(i);
                UnitOutcome outcome = null;
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        outcome = future.get();
                    } catch (Exception unavailable) {
                        outcome = null;
                    }
                }
                if (outcome != null && outcome.failure() == null) {
                    increment("egpi_units_validated_uncommitted_at_deadline");
                    tally.validatedUncommitted++;
                    tally.workerBusyNs += outcome.evaluation().nanos();
                    tally.lastFinishNs = Math.max(tally.lastFinishNs, outcome.finishNs());
                    unitEvents.add(unitEvent(batch.get(i), tally, i, outcome, 0, 0,
                            EgpiAnytime.VALIDATED_UNCOMMITTED_AT_STOP));
                } else {
                    increment("egpi_units_cancelled_unvalidated");
                    tally.cancelled++;
                    unitEvents.add(unitEvent(batch.get(i), tally, i, outcome, 0, 0, EgpiAnytime.CANCELLED_UNVALIDATED));
                }
            }
        }

        /**
         * At a stop, classifies the batch's uncommitted units exactly: finished
         * units are validated but unpublished, the rest were cancelled.
         */
        void countUncommitted(List<java.util.concurrent.Future<UnitOutcome>> futures, int from,
                List<Unit> batch, EpochTally tally) {
            for (int i = from; i < futures.size(); i++) {
                var future = futures.get(i);
                UnitOutcome outcome = null;
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        outcome = future.get();
                    } catch (Exception unavailable) {
                        outcome = null;
                    }
                }
                if (outcome != null && outcome.failure() == null) {
                    increment("egpi_units_validated_uncommitted_at_deadline");
                    tally.validatedUncommitted++;
                    tally.workerBusyNs += outcome.evaluation().nanos();
                    tally.lastFinishNs = Math.max(tally.lastFinishNs, outcome.finishNs());
                    unitEvents.add(unitEvent(batch.get(i), tally, i, outcome, 0, 0,
                            EgpiAnytime.VALIDATED_UNCOMMITTED_AT_STOP));
                } else {
                    increment("egpi_units_cancelled_unvalidated");
                    tally.cancelled++;
                    unitEvents.add(unitEvent(batch.get(i), tally, i, outcome, 0, 0,
                            EgpiAnytime.CANCELLED_UNVALIDATED));
                }
            }
        }

        EgpiAnytime.UnitEvent unitEvent(Unit unit, EpochTally tally, int batchIndex, UnitOutcome outcome,
                long commitStartNs, long commitEndNs, String label) {
            RouteRecord route = routes.get(unit.routeId());
            boolean timed = outcome != null && outcome.evaluation() != null;
            boolean profile = timed && outcome.evaluation().profile().isPresent();
            boolean feasible = profile && !outcome.evaluation().profile().get().feasibleDomain().isEmpty();
            String measure = null;
            if (feasible) {
                ExactTime total = ExactTime.ZERO;
                for (var interval : outcome.evaluation().profile().get().feasibleDomain().intervals()) {
                    total = total.add(interval.end().subtract(interval.start()));
                }
                measure = total.toString();
            }
            return new EgpiAnytime.UnitEvent(tally.epoch, batchIndex, unit.routeId(), route.pivots(), unit.chunk(),
                    tally.submitNs, outcome == null ? 0 : outcome.startNs(), outcome == null ? 0 : outcome.finishNs(),
                    timed ? outcome.evaluation().nanos() : 0, commitStartNs, commitEndNs, label,
                    timed ? outcome.evaluation().reusedElements() : 0,
                    timed ? outcome.evaluation().composedElements() : 0,
                    outcome == null ? null : outcome.worker(), profile, feasible, measure);
        }

        /** Commits one completed unit; returns whether the envelope improved. */
        boolean commitOne(Unit unit, UnitOutcome outcome, EpochTally tally, int batchIndex) {
            RouteRecord route = routes.get(unit.routeId());
            tally.processed++;
            if (outcome.failure() != null) {
                if (!(outcome.failure() instanceof CancellationException)) throw outcome.failure();
                route.unitsCancelled++;
                increment("egpi_units_cancelled_unvalidated");
                tally.cancelled++;
                unitEvents.add(unitEvent(unit, tally, batchIndex, outcome, 0, 0, EgpiAnytime.CANCELLED_UNVALIDATED));
                return false;
            }
            var evaluation = outcome.evaluation();
            completedUnitNanos.add(evaluation.nanos());
            add("egpi_exact_replay_ns", evaluation.nanos());
            tally.workerBusyNs += evaluation.nanos();
            tally.lastFinishNs = Math.max(tally.lastFinishNs, outcome.finishNs());
            Optional<ExactPreferencePathProfile> profile = evaluation.profile();
            long commitStarted = budget.elapsedNanos();
            if (profile.isEmpty()) {
                // The route is unsupported on every departure of this chunk.
                route.unitsCommitted++;
                route.unitsEmpty++;
                route.validatedDomain = route.validatedDomain.union(chunks.get(unit.chunk()));
                increment("egpi_units_committed_without_feasible_departure");
                finishCommit(unit, outcome, tally, batchIndex, null, false, false, EnvelopeGain.NONE,
                        commitStarted, 0, EgpiAnytime.COMMITTED_NO_FEASIBLE_DEPARTURE);
                return false;
            }
            long started = System.nanoTime();
            IncrementalExactPreferenceEnvelope.Update update;
            try {
                update = envelope.add(profile.get(), budget);
            } catch (CancellationException cancelled) {
                // The caller classifies this finished unit as validated but uncommitted.
                add("egpi_envelope_update_ns", System.nanoTime() - started);
                throw cancelled;
            }
            long updateNanos = System.nanoTime() - started;
            add("egpi_envelope_update_ns", updateNanos);
            recordUpdateStats(update.stats());
            increment("egpi_envelope_commits");
            route.unitsCommitted++;
            route.validatedDomain = route.validatedDomain.union(chunks.get(unit.chunk()));
            // Over-budget parts are kept too: the envelope takes cuts from every
            // segment of a route that is feasible somewhere.
            registry.computeIfAbsent(route.path, ignored -> new ArrayList<>()).add(profile.get());
            boolean feasibleSupport = !profile.get().feasibleDomain().isEmpty();
            if (!feasibleSupport) {
                route.unitsEmpty++;
                increment("egpi_units_committed_without_feasible_departure");
            } else {
                registeredRoutes.add(route.path);
            }
            route.coverageGain = route.coverageGain.add(update.coverageGain());
            route.preferenceGain = route.preferenceGain.add(update.integratedPreferenceGain());
            coveredMeasure[unit.chunk()] += update.coverageGain().toDisplayDouble();
            integratedPreference[unit.chunk()] += update.integratedPreferenceGain().toDisplayDouble();
            for (ExactScoreProfile.Segment segment : profile.get().score().segments()) {
                scoreNorm = Math.max(scoreNorm, segment.value().doubleValue() + pivots.maximumScore());
            }
            if (recordHistory) history.add(envelope.snapshot());
            finishCommit(unit, outcome, tally, batchIndex, profile.get(), feasibleSupport, update.improved(),
                    update.gain(), commitStarted, updateNanos,
                    update.improved() ? EgpiAnytime.COMMITTED_IMPROVING : EgpiAnytime.COMMITTED_NONIMPROVING);
            if (!update.improved()) return false;
            increment("egpi_envelope_improving_commits");
            promote(route);
            return true;
        }

        /** Records the commit in the append-only log and its unit lifecycle event. */
        void finishCommit(Unit unit, UnitOutcome outcome, EpochTally tally, int batchIndex,
                ExactPreferencePathProfile profile, boolean feasibleSupport, boolean improving,
                EnvelopeGain gain, long commitStarted, long updateNanos, String label) {
            long commitEnded = budget.elapsedNanos();
            tally.committed++;
            tally.commitNs += commitEnded - commitStarted;
            tally.lastCommitEndNs = commitEnded;
            EgpiAnytime.Commit commit = new EgpiAnytime.Commit(commitLog.size() + 1, tally.epoch, batchIndex,
                    unit.routeId(), unit.chunk(), commitEnded, profile, feasibleSupport, improving, gain,
                    envelope.cellCount(), registeredRoutes.size(), proposalPops,
                    store == null ? 0 : store.connectorSearches(),
                    routesAdmitted, tally.units - tally.processed, units.size(), activeWorkers.get(), updateNanos);
            commitLog.add(commit);
            CommitObserver watcher = observer;
            if (watcher != null) watcher.onCommit(commit);
            unitEvents.add(unitEvent(unit, tally, batchIndex, outcome, commitStarted, commitEnded, label));
        }

        void recordUpdateStats(IncrementalExactPreferenceEnvelope.Stats stats) {
            add("egpi_envelope_phase_cuts_ns", stats.cutsNs());
            add("egpi_envelope_phase_crossings_ns", stats.crossingNs());
            add("egpi_envelope_phase_stage_copy_ns", stats.stageNs());
            add("egpi_envelope_phase_cut_insertion_ns", stats.insertNs());
            add("egpi_envelope_phase_winner_scan_ns", stats.scanNs());
            add("egpi_envelope_pairs_with_common_support", stats.pairsWithCommonSupport());
            add("egpi_envelope_crossing_roots", stats.crossingRoots());
            add("egpi_envelope_fresh_cuts", stats.freshCuts());
            add("egpi_envelope_cuts_scanned", stats.cutsScanned());
            add("egpi_envelope_cuts_before_sum", stats.cutsBefore());
            add("egpi_envelope_cuts_after_sum", stats.cutsAfter());
            add("egpi_envelope_comparisons", stats.comparisons());
        }

        void refreshDeficits() {
            if (envelope == null || deficitVersion == envelope.version()) return;
            ExactPreferenceEnvelope snapshot = null;
            for (int c = 0; c < chunks.size(); c++) {
                double measure = Math.max(0, chunkEnd[c] - chunkStart[c]);
                double covered;
                double average;
                if (measure > 0) {
                    covered = clamp(coveredMeasure[c] / measure);
                    average = coveredMeasure[c] > 0 ? integratedPreference[c] / coveredMeasure[c] : 0;
                } else {
                    if (snapshot == null) snapshot = envelope.snapshot();
                    var cell = snapshot.cellAt(chunks.get(c).intervals().getFirst().start());
                    covered = cell.isPresent() && cell.get().found() ? 1 : 0;
                    average = covered > 0 ? cell.get().score().doubleValue() : 0;
                }
                deficit[c] = (1 - covered) + covered * (1 - clamp(average / scoreNorm));
            }
            deficitVersion = envelope.version();
        }

        // ------------------------------------------------------------------ frontier

        void admitToFrontier(RouteRecord route, Role role, double score) {
            List<Integer> members = role == Role.EXPLOITATION ? exploitation : exploration;
            int capacity = role == Role.EXPLOITATION
                    ? options.maxExpansionFrontierRoutes() - options.explorationFrontierQuota()
                    : options.explorationFrontierQuota();
            if (route.role == role) {
                route.frontierScore = score;
                return;
            }
            if (members.size() >= capacity) {
                int weakest = -1;
                for (int id : members) {
                    if (weakest < 0 || weaker(routes.get(id), routes.get(weakest))) weakest = id;
                }
                RouteRecord victim = routes.get(weakest);
                if (!(score > victim.frontierScore
                        || (score == victim.frontierScore && route.id < victim.id))) {
                    increment("egpi_frontier_admissions_rejected");
                    return;
                }
                members.remove(Integer.valueOf(weakest));
                victim.role = Role.NONE;
                increment("egpi_frontier_evictions");
            }
            if (route.role != Role.NONE) {
                (route.role == Role.EXPLOITATION ? exploitation : exploration).remove(Integer.valueOf(route.id));
            }
            route.role = role;
            route.rolesHeld.add(role);
            route.frontierScore = score;
            members.add(route.id);
            increment(role == Role.EXPLOITATION ? "egpi_frontier_admissions_exploitation"
                    : "egpi_frontier_admissions_exploration");
            counters.merge("egpi_frontier_peak_routes", (long) (exploitation.size() + exploration.size()), Math::max);
            if (!route.expansionExhausted && !route.continuationQueued
                    && route.pivots() < options.maxPivotsPerConstructedRoute()) {
                proposals.add(continuation(route, Double.isInfinite(score)
                        ? Double.MAX_VALUE : Math.max(route.estimatedOpportunity, 0)));
            }
        }

        boolean weaker(RouteRecord left, RouteRecord right) {
            if (left.frontierScore != right.frontierScore) return left.frontierScore < right.frontierScore;
            return left.id > right.id;
        }

        void promote(RouteRecord route) {
            double gain = route.coverageGainDisplay() * scoreNorm + route.preferenceGainDisplay();
            if (route.pivots() >= options.maxPivotsPerConstructedRoute()) return;
            admitToFrontier(route, Role.EXPLOITATION, gain);
        }

        // ------------------------------------------------------------------ bookkeeping

        RouteRecord register(EgpiRoute route, int parentId, double[] gain, double opportunity, String origin) {
            double[] prefix = new double[route.orderedConnectors().size()];
            double cost = 0;
            for (int gap = 0; gap < prefix.length; gap++) {
                prefix[gap] = cost;
                cost += route.orderedConnectors().get(gap).staticLowerBoundCost();
                if (gap < route.orderedPivotEdges().size()) {
                    cost += region.lowerBounds().edgeWeight(route.orderedPivotEdges().get(gap));
                }
            }
            RouteRecord record = new RouteRecord(routes.size(), parentId, route, prefix, gain, opportunity);
            routes.add(record);
            routeByEdges.put(route.completeEdgeSequence(), record.id);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("route_id", record.id);
            entry.put("parent_route_id", parentId);
            entry.put("origin", origin);
            entry.put("pivot_edges", route.orderedPivotEdges());
            entry.put("complete_edge_sequence", route.completeEdgeSequence());
            entry.put("edge_count", route.completeEdgeSequence().size());
            entry.put("static_lower_bound_cost", route.staticLowerBoundCost());
            entry.put("estimated_opportunity", opportunity);
            entry.put("canonical_route_digest", route.canonicalRouteDigest());
            trace.add(entry);
            increment("egpi_routes_constructed");
            return record;
        }

        void reject(String reason) {
            increment("egpi_rejections_" + reason);
            increment("egpi_proposals_rejected");
        }

        RuntimeException cancellation(RuntimeException cause) {
            CancellationException cancelled = new CancellationException("EGPI parallel work cancelled");
            cancelled.initCause(cause);
            return cancelled;
        }

        void add(String name, long amount) { counters.merge(name, amount, Long::sum); }
        void increment(String name) { add(name, 1); }

        EgpiResult result(long elapsedNanos) {
            ExactEnvelopeBuilder builder = envelope == null ? newEnvelope() : envelope;
            ExactPreferenceEnvelope committed = engine.resultForm() == EgpiEngineOptions.ResultForm.CANONICAL_CELLS_V1
                    ? builder.canonicalSnapshot() : builder.snapshot();
            LinkedHashMap<Path, List<ExactPreferencePathProfile>> registeredParts = new LinkedHashMap<>();
            for (var entry : registry.entrySet()) {
                if (registeredRoutes.contains(entry.getKey())) registeredParts.put(entry.getKey(), entry.getValue());
            }
            LazyRegistry merged = new LazyRegistry(registeredParts, this::merge);
            // Contiguous found cells are one covered interval: normalizing a few intervals instead of every
            // cell avoids thousands of big-rational comparisons after the deadline.
            List<ExactTemporalDomain.Interval> found = new ArrayList<>();
            ExactTemporalDomain.Interval run = null;
            for (var cell : committed.cells()) {
                if (!cell.found()) continue;
                ExactTemporalDomain.Interval interval = cell.interval();
                if (run != null && run.end().equals(interval.start()) && (run.endInclusive() || interval.startInclusive())) {
                    run = new ExactTemporalDomain.Interval(run.start(), interval.end(), run.startInclusive(),
                            interval.endInclusive());
                } else {
                    if (run != null) found.add(run);
                    run = interval;
                }
            }
            if (run != null) found.add(run);
            ExactTemporalDomain unresolved = departures.difference(ExactTemporalDomain.of(found));
            Map<String, Long> all = new TreeMap<>(counters);
            if (store != null) store.statistics().forEach((key, value) -> all.put("egpi_structural_" + key, value));
            if (transfers != null) transfers.stats().forEach((key, value) -> all.put("egpi_temporal_" + key, value));
            if (evaluator != null) evaluator.stats().forEach((key, value) -> all.put("egpi_evaluator_" + key, value));
            if (pivots != null) pivots.stats().forEach((key, value) -> all.put("egpi_" + key, value));
            all.put("egpi_configured_workers", (long) threads);
            all.put("egpi_peak_active_workers", (long) peakActiveWorkers.get());
            all.put("egpi_worker_tasks", workerTasks.get());
            all.put("egpi_worker_busy_ns", workerBusyNanos.get());
            all.put("egpi_proposal_heap_remaining", (long) proposals.size());
            all.put("egpi_units_remaining", (long) units.size());
            all.put("egpi_candidates_remaining_unadmitted", (long) candidates.size());
            all.put("egpi_frontier_final_exploitation", (long) exploitation.size());
            all.put("egpi_frontier_final_exploration", (long) exploration.size());
            all.put("egpi_registry_routes", (long) merged.size());
            all.put("egpi_registry_chunk_entries", (long) registry.entrySet().stream()
                    .filter(entry -> registeredRoutes.contains(entry.getKey()))
                    .mapToInt(entry -> entry.getValue().size()).sum());
            all.put("egpi_evaluated_routes_without_feasible_departure",
                    registry.keySet().stream().filter(path -> !registeredRoutes.contains(path)).count());
            all.put("egpi_envelope_version", envelope == null ? 0 : envelope.version());
            all.put("egpi_departure_chunks", (long) chunks.size());
            List<Map<String, Object>> traceEntries = new ArrayList<>();
            for (RouteRecord route : routes) {
                Map<String, Object> entry = new LinkedHashMap<>(trace.get(route.id));
                entry.put("admitted_for_exact", route.admittedForExact);
                entry.put("units_committed", route.unitsCommitted);
                entry.put("units_without_feasible_departure", route.unitsEmpty);
                entry.put("units_cancelled", route.unitsCancelled);
                entry.put("final_role", route.role.name());
                entry.put("frontier_roles_held", route.rolesHeld.stream().map(Enum::name).toList());
                entry.put("validated_departure_domain", route.validatedDomain.toString());
                entry.put("full_domain_validated", route.validatedDomain.equals(departures));
                traceEntries.add(entry);
            }
            // The exact credit totals of the routes are formed when the trace is read, not by the query.
            List<RouteRecord> traced = List.copyOf(routes);
            List<Map<String, Object>> routeTrace = new LazyRouteTrace(traceEntries, () -> {
                for (int i = 0; i < traced.size(); i++) {
                    Map<String, Object> entry = traceEntries.get(i);
                    entry.put("coverage_gain_exact", traced.get(i).coverageGainExact().toString());
                    entry.put("integrated_preference_gain_exact", traced.get(i).preferenceGainExact().toString());
                }
            });
            return new EgpiResult(committed, merged, all, stopReason, deadline, elapsedNanos,
                    envelope == null ? 0 : envelope.version(), history, unresolved,
                    List.copyOf(capsTriggered), routeTrace, options, engine,
                    new EgpiAnytime.Timeline(algorithmVersion(), departures, commitLog),
                    unitEvents, epochEvents, budget.originNanoTime());
        }

        ExactPreferencePathProfile merge(Path path, List<ExactPreferencePathProfile> parts) {
            if (parts.size() == 1) return parts.getFirst();
            List<edu.ipcmax.baselines.exact.ExactAffineSegment> arrival = new ArrayList<>();
            List<ExactScoreProfile.Segment> score = new ArrayList<>();
            List<ExactTemporalDomain.Interval> feasible = new ArrayList<>();
            long events = 0, roots = 0;
            for (ExactPreferencePathProfile part : parts) {
                arrival.addAll(part.arrival().segments());
                score.addAll(part.score().segments());
                feasible.addAll(part.feasibleDomain().intervals());
                events += part.scoreEventPreimages();
                roots += part.budgetRootOperations();
            }
            return new ExactPreferencePathProfile(path, ExactArrivalProfile.ofSegments(arrival),
                    ExactScoreProfile.ofSegments(score), ExactTemporalDomain.of(feasible), exactBudget, events, roots);
        }

        /** Idempotent: cancels outstanding work and waits a bounded time for every worker. */
        void close() {
            budget.cancel();
            if (executor != null) {
                long started = System.nanoTime();
                boolean terminated = executor.closeWithin(java.time.Duration.ofSeconds(1));
                counters.put("egpi_worker_pool_terminated", terminated ? 1L : 0L);
                counters.put("egpi_worker_pool_shutdown_ns", System.nanoTime() - started);
                executor = null;
            }
        }
    }

    /** Disjoint departure chunks with exact rational endpoints and owned boundaries. */
    static List<ExactTemporalDomain> chunk(ExactTemporalDomain departures, int count) {
        if (departures.isEmpty()) return List.of(departures);
        List<ExactTemporalDomain> result = new ArrayList<>();
        for (ExactTemporalDomain.Interval interval : departures.intervals()) {
            if (interval.isSingleton() || count == 1) {
                result.add(ExactTemporalDomain.of(interval));
                continue;
            }
            ExactTime width = interval.end().subtract(interval.start()).divide(ExactTime.of(count));
            for (int i = 0; i < count; i++) {
                ExactTime start = interval.start().add(width.multiply(ExactTime.of(i)));
                ExactTime end = i + 1 == count ? interval.end() : interval.start().add(width.multiply(ExactTime.of(i + 1)));
                boolean startIncluded = i == 0 ? interval.startInclusive() : true;
                boolean endIncluded = i + 1 == count && interval.endInclusive();
                result.add(ExactTemporalDomain.of(new ExactTemporalDomain.Interval(start, end, startIncluded, endIncluded)));
            }
        }
        return List.copyOf(result);
    }

    private static double clamp(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    private static double[] scale(double[] values, double factor) {
        double[] result = values.clone();
        for (int i = 0; i < result.length; i++) result[i] *= factor;
        return result;
    }
}
