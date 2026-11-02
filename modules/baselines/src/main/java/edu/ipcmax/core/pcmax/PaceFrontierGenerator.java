package edu.ipcmax.core.pcmax;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import edu.ipcmax.core.cache.CandidateCache;
import edu.ipcmax.core.cache.MemoKey;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * PACE GenerateFrontier recursion with explicit-anchor budget splitting.
 */
public final class PaceFrontierGenerator {
    private final TDGraph graph;
    private final PaceOptions options;
    private final CandidateCache memo;
    private volatile PaceGenerationStats lastStats = PaceGenerationStats.empty();

    /** Backward-compatible exhaustive generator; the legacy method supplies theta. */
    public PaceFrontierGenerator(TDGraph graph) {
        this(graph, PaceOptions.exhaustive(0));
    }

    /** Creates a generator with an explicit PACE policy. */
    public PaceFrontierGenerator(TDGraph graph, PaceOptions options) {
        if (graph == null || options == null) {
            throw new IllegalArgumentException("graph and PACE options are required");
        }
        this.graph = graph;
        this.options = options;
        this.memo = new CandidateCache();
    }

    /** Generates the root frontier for a validated logical PACE query. */
    public synchronized CandidateSet generateFrontier(QuerySpec query) {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        return execute(
                query.source(),
                query.destination(),
                query.departureDomain(),
                query.maxTravelTime(),
                options.theta(),
                options);
    }

    /**
     * Backward-compatible direct entry point. The supplied {@code ell} is an explicit-anchor
     * budget, not a recursion-tree depth.
     */
    public synchronized CandidateSet generateFrontier(
            int source,
            int destination,
            Domain domain,
            double budget,
            int ell) {
        PaceOptions runOptions = new PaceOptions(
                options.policy(),
                options.engineMode(),
                ell,
                options.pivotLimitL(),
                options.connectorLimitKc(),
                options.frontierLimitKf(),
                options.connectorExpansionCapMc(),
                options.breakpointCapMb(),
                options.queryWorkCapMq(),
                options.threadCount(),
                options.memoizationEnabled(),
                options.features(),
                options.maxFrontierFragments());
        return execute(source, destination, domain, budget, ell, runOptions);
    }

    /** Statistics from the most recently completed root generation. */
    public PaceGenerationStats stats() {
        return lastStats;
    }

    private CandidateSet execute(
            int source,
            int destination,
            Domain domain,
            double budget,
            int ell,
            PaceOptions runOptions) {
        lastStats = PaceGenerationStats.empty();
        if (domain == null || domain.isEmpty()) {
            return new CandidateSet();
        }
        if (ell < 0) {
            throw new IllegalArgumentException("remaining explicit-anchor budget cannot be negative");
        }
        if (budget < 0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("travel budget must be finite and nonnegative");
        }
        graph.node(source);
        graph.node(destination);
        double horizonStart = domain.intervals().get(0).start();
        double horizonEnd = domain.intervals().get(domain.intervals().size() - 1).end() + budget;
        if (!Double.isFinite(horizonEnd)) {
            throw new IllegalArgumentException("query horizon endpoint must be finite");
        }
        Domain queryHorizon = Domain.closed(horizonStart, horizonEnd);
        AnchorIndex anchorIndex = AnchorIndex.create(graph, queryHorizon);
        QueryLowerBounds lowerBounds = new QueryLowerBounds(graph, queryHorizon);
        ConnectorProfiles connectors = new ConnectorProfiles(graph, anchorIndex, lowerBounds, runOptions);
        RunCounters counters = new RunCounters();
        long hitsBefore = memo.hits();
        long missesBefore = memo.misses();
        RunContext context = new RunContext(
                runOptions,
                queryHorizon,
                anchorIndex,
                lowerBounds,
                connectors,
                counters,
                "graph-edges=" + graph.edgeCount() + ":nodes=" + graph.nodeCount(),
                ell);
        CandidateSet result = generate(context, source, destination, domain, budget, ell);
        lastStats = new PaceGenerationStats(
                counters.recursionCalls.get(),
                counters.anchorsConsidered.get(),
                counters.anchorsRetained.get(),
                counters.connectorCandidates.get(),
                counters.stitchedCandidates.get(),
                memo.hits() - hitsBefore,
                memo.misses() - missesBefore,
                counters.parallelTasksStarted.get());
        return result;
    }

    private CandidateSet generate(
            RunContext context,
            int source,
            int destination,
            Domain domain,
            double budget,
            int remainingAnchors) {
        context.counters().recursionCalls.incrementAndGet();
        if (domain.isEmpty()) {
            return new CandidateSet();
        }
        MemoKey key = new MemoKey(
                source,
                destination,
                domain,
                remainingAnchors,
                context.options().policy(),
                context.options().effectiveAnchorLimit(),
                context.options().effectiveFrontierLimit(),
                budget,
                context.queryHorizon(),
                context.graphVersion(),
                context.anchors().version());
        if (!context.options().memoizationEnabled()) {
            return computeFrontier(context, source, destination, domain, budget, remainingAnchors);
        }
        return memo.getOrCompute(
                key,
                () -> computeFrontier(context, source, destination, domain, budget, remainingAnchors));
    }

    private CandidateSet computeFrontier(
            RunContext context,
            int source,
            int destination,
            Domain domain,
            double budget,
            int remainingAnchors) {
        CandidateSet frontier = context.connectors().generate(source, destination, domain, budget);
        context.counters().connectorCandidates.addAndGet(frontier.size());
        if (remainingAnchors > 0) {
            context.counters().anchorsConsidered.addAndGet(context.anchors().anchors().size());
            var relevant = context.anchors().relevantAnchors(
                    source,
                    destination,
                    domain,
                    budget,
                    context.lowerBounds(),
                    context.options());
            context.counters().anchorsRetained.addAndGet(relevant.size());
            if (context.options().threadCount() > 1
                    && remainingAnchors == context.rootAnchorBudget()
                    && relevant.size() > 1) {
                try (IPCMaxParallelExecutor executor =
                             new IPCMaxParallelExecutor(context.options().threadCount())) {
                    List<Callable<CandidateSet>> tasks = relevant.stream()
                            .<Callable<CandidateSet>>map(relevantAnchor -> () -> {
                                context.counters().parallelTasksStarted.incrementAndGet();
                                return stitchForAnchor(
                                        context,
                                        relevantAnchor,
                                        source,
                                        destination,
                                        domain,
                                        budget,
                                        remainingAnchors);
                            })
                            .toList();
                    for (CandidateSet generated : executor.invokeAllDeterministic(tasks)) {
                        frontier.addAll(generated);
                    }
                }
            } else {
                for (RelevantAnchor relevantAnchor : relevant) {
                    frontier.addAll(stitchForAnchor(
                            context,
                            relevantAnchor,
                            source,
                            destination,
                            domain,
                            budget,
                            remainingAnchors));
                }
            }
        }
        CandidateSet canonical = canonicalizeGeneratedPaths(
                context,
                frontier,
                source,
                destination,
                domain,
                budget,
                remainingAnchors);
        if (canonical.size() > context.options().maxFrontierFragments()) {
            throw new PaceException(
                    PaceStatus.LIMIT_EXCEEDED,
                    "frontier guard exceeded: " + canonical.size() + " > "
                            + context.options().maxFrontierFragments());
        }
        return FrontierCompressor.compress(
                graph,
                canonical,
                domain,
                budget,
                context.options().effectiveFrontierLimit(),
                context.options().policy(),
                source,
                destination,
                context.options().features());
    }

    private CandidateSet stitchForAnchor(
            RunContext context,
            RelevantAnchor relevantAnchor,
            int source,
            int destination,
            Domain domain,
            double budget,
            int remainingAnchors) {
        CandidateSet stitched = new CandidateSet();
        Anchor anchor = relevantAnchor.anchor();
        for (int leftBudget = 0; leftBudget < remainingAnchors; leftBudget++) {
            int rightBudget = remainingAnchors - 1 - leftBudget;
            CandidateSet leftFrontier = generate(
                    context,
                    source,
                    anchor.source(),
                    domain,
                    budget,
                    leftBudget);
            for (CandidateProfile left : leftFrontier.candidates()) {
                Domain leftAnchorDomain = leftAnchorDomain(left, anchor, domain);
                if (leftAnchorDomain.isEmpty()) {
                    continue;
                }
                Domain rightDomain = imageDomainAfterAnchor(left, anchor, leftAnchorDomain);
                if (rightDomain.isEmpty()) {
                    continue;
                }
                CandidateSet rightFrontier = generate(
                        context,
                        anchor.target(),
                        destination,
                        rightDomain,
                        budget,
                        rightBudget);
                for (CandidateProfile right : rightFrontier.candidates()) {
                    TemporalStitch.stitch(graph, left, anchor, right, domain, budget)
                            .ifPresent(candidate -> {
                                if (candidate.explicitAnchorCount() > remainingAnchors) {
                                    throw new IllegalStateException(
                                            "generated candidate exceeds explicit-anchor budget");
                                }
                                stitched.add(candidate);
                                context.counters().stitchedCandidates.incrementAndGet();
                            });
                }
            }
        }
        return stitched;
    }

    private CandidateSet canonicalizeGeneratedPaths(
            RunContext context,
            CandidateSet generated,
            int source,
            int destination,
            Domain subproblemDomain,
            double budget,
            int remainingAnchors) {
        Map<List<Integer>, Domain> generatedDomains = new TreeMap<>(PathPointer.STABLE_PATH_ORDER);
        for (CandidateProfile candidate : generated.candidates()) {
            Domain retained = candidate.domain().intersection(subproblemDomain);
            if (retained.isEmpty()) {
                continue;
            }
            List<Integer> pathId = candidate.stablePathId();
            generatedDomains.merge(pathId, retained, Domain::union);
        }

        CandidateSet canonical = new CandidateSet();
        for (Map.Entry<List<Integer>, Domain> entry : generatedDomains.entrySet()) {
            CanonicalPathProfileBuilder.replay(
                    graph,
                    context.anchors(),
                    entry.getKey(),
                    source,
                    destination,
                    subproblemDomain,
                    budget,
                    -1,
                    false)
                    .ifPresent(candidate -> {
                        if (candidate.explicitAnchorCount() > remainingAnchors) {
                            throw new IllegalStateException(
                                    "canonical path exceeds explicit-anchor budget: "
                                            + candidate.stablePathId());
                        }
                        Domain retained = candidate.domain().intersection(entry.getValue());
                        if (!retained.isEmpty()) {
                            canonical.add(candidate.domain().equals(retained)
                                    ? candidate
                                    : candidate.restrict(retained));
                        }
                    });
        }
        return canonical;
    }

    private static Domain leftAnchorDomain(CandidateProfile left, Anchor anchor, Domain rootDomain) {
        Domain base = rootDomain.intersection(left.domain());
        if (base.isEmpty()) {
            return Domain.empty();
        }
        return left.arrivalProfile().preimage(anchor.validDomain(), base);
    }

    /** Computes {@code ImageDomain(A_a composed A_C_L, D_L^a)}. */
    static Domain imageDomainAfterAnchor(
            CandidateProfile left,
            Anchor anchor,
            Domain leftAnchorDomain) {
        return imageDomainAfterAnchor(left, anchor.edge(), anchor.validDomain(), leftAnchorDomain);
    }

    /** Backward-compatible image-domain helper. */
    static Domain imageDomainAfterAnchor(
            CandidateProfile left,
            Edge anchor,
            Domain leftAnchorDomain) {
        return imageDomainAfterAnchor(
                left,
                anchor,
                PaceProfiles.travelFunctionDomain(anchor),
                leftAnchorDomain);
    }

    private static Domain imageDomainAfterAnchor(
            CandidateProfile left,
            Edge anchor,
            Domain anchorValidDomain,
            Domain leftAnchorDomain) {
        if (leftAnchorDomain.isEmpty()) {
            return Domain.empty();
        }
        TimeProfile leftArrival = left.arrivalProfile().restrict(leftAnchorDomain);
        TimeProfile anchorArrival = PaceProfiles.edgeArrivalProfile(
                anchor,
                anchorValidDomain,
                "pace-anchor-image");
        TimeProfile afterAnchor = leftArrival.compose(
                anchorArrival,
                "pace-anchor-image:left=" + left.stablePathId() + ":a=" + anchor.arcId());
        return afterAnchor.imageDomain(leftAnchorDomain);
    }

    private record RunContext(
            PaceOptions options,
            Domain queryHorizon,
            AnchorIndex anchors,
            QueryLowerBounds lowerBounds,
            ConnectorProfiles connectors,
            RunCounters counters,
            String graphVersion,
            int rootAnchorBudget) {
    }

    private static final class RunCounters {
        private final AtomicLong recursionCalls = new AtomicLong();
        private final AtomicLong anchorsConsidered = new AtomicLong();
        private final AtomicLong anchorsRetained = new AtomicLong();
        private final AtomicLong connectorCandidates = new AtomicLong();
        private final AtomicLong stitchedCandidates = new AtomicLong();
        private final AtomicLong parallelTasksStarted = new AtomicLong();
    }
}
