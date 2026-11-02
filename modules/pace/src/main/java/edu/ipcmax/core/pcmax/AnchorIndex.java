package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/**
 * Immutable query-specific index of all single-edge anchors.
 */
public final class AnchorIndex {
    private final Domain queryHorizon;
    private final List<Anchor> anchors;
    private final Set<Integer> anchorArcIds;
    private final String version;

    private AnchorIndex(Domain queryHorizon, List<Anchor> anchors, String version) {
        this.queryHorizon = queryHorizon;
        this.anchors = List.copyOf(anchors);
        Set<Integer> ids = new HashSet<>();
        for (Anchor anchor : anchors) {
            ids.add(anchor.stableArcId());
        }
        this.anchorArcIds = Set.copyOf(ids);
        this.version = version;
    }

    /**
     * Validates graph function coverage and constructs all anchors whose {@code D_a+} is nonempty.
     */
    public static AnchorIndex create(TDGraph graph, Domain queryHorizon) {
        if (queryHorizon == null || queryHorizon.isEmpty()) {
            throw new IllegalArgumentException("query horizon cannot be null or empty");
        }
        if (queryHorizon.intervals().size() != 1) {
            throw new IllegalArgumentException("query horizon must be one normalized interval");
        }
        Domain.Interval horizon = queryHorizon.intervals().get(0);
        List<Anchor> anchors = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            requireCoverage(edge, horizon);
            Domain valid = PaceProfiles.validEntryDomain(edge, queryHorizon);
            Domain positive = edge.scoreFunction().positiveDomain().intersection(valid);
            if (!positive.isEmpty()) {
                anchors.add(new Anchor(edge, valid, positive, PaceProfiles.minimumTravelTime(edge, valid)));
            }
        }
        anchors.sort(Comparator.comparingInt(Anchor::stableArcId));
        // The cache is owned by one immutable graph instance. Within that graph, the query
        // horizon uniquely determines this single-edge anchor index.
        String version = "single-edge-anchor-index-v1:Tq=" + queryHorizon.intervals();
        return new AnchorIndex(queryHorizon, anchors, version);
    }

    /** Query horizon {@code T_q}. */
    public Domain queryHorizon() {
        return queryHorizon;
    }

    /** All anchors, sorted by stable arc id. */
    public List<Anchor> anchors() {
        return anchors;
    }

    /** True exactly for graph arcs removed from the anchor-free connector view. */
    public boolean isAnchorArc(int arcId) {
        return anchorArcIds.contains(arcId);
    }

    /** Stable anchor arc-id set used by the anchor-free graph view. */
    public Set<Integer> anchorArcIds() {
        return anchorArcIds;
    }

    /** Stable version fingerprint used by memoization. */
    public String version() {
        return version;
    }

    /**
     * Applies the conservative lower-bound relevance test and the policy's deterministic retention.
     */
    public List<RelevantAnchor> relevantAnchors(
            int source,
            int destination,
            Domain subproblemDomain,
            double budget,
            QueryLowerBounds lowerBounds,
            PaceOptions options) {
        if (subproblemDomain == null || subproblemDomain.isEmpty()) {
            return List.of();
        }
        double domainInfimum = subproblemDomain.intervals().get(0).start();
        double domainSupremum = subproblemDomain.intervals()
                .get(subproblemDomain.intervals().size() - 1).end();
        QueryLowerBounds.Distances fromSource = lowerBounds.distancesFrom(source);
        QueryLowerBounds.Distances toDestination = lowerBounds.distancesTo(destination);
        double direct = fromSource.distance(destination);
        List<RelevantAnchor> relevant = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (anchor.source() == anchor.target()) {
                continue;
            }
            double toAnchor = fromSource.distance(anchor.source());
            double fromAnchor = toDestination.distance(anchor.target());
            if (!Double.isFinite(toAnchor) || !Double.isFinite(fromAnchor)) {
                continue;
            }
            double routeLowerBound = Domain.canonicalTime(
                    toAnchor + anchor.lowerTravelTime() + fromAnchor);
            if (options.features().anchorLowerBoundFilterEnabled()
                    && routeLowerBound > Domain.canonicalTime(budget)) {
                continue;
            }
            double windowStart = Domain.canonicalTime(domainInfimum + toAnchor);
            double windowEnd = Domain.canonicalTime(
                    domainSupremum + budget - anchor.lowerTravelTime() - fromAnchor);
            if (options.features().anchorLowerBoundFilterEnabled() && windowStart > windowEnd) {
                continue;
            }
            Domain window = options.features().anchorLowerBoundFilterEnabled()
                    ? Domain.closed(windowStart, windowEnd)
                    : anchor.validDomain();
            Domain relevantValid = options.features().anchorLowerBoundFilterEnabled()
                    ? anchor.validDomain().intersection(window)
                    : anchor.validDomain();
            if (relevantValid.isEmpty()) {
                continue;
            }
            Domain relevantPositive = options.features().anchorLowerBoundFilterEnabled()
                    ? anchor.positiveDomain().intersection(window)
                    : anchor.positiveDomain();
            double scorePotential = scoreIntegral(anchor.edge(), relevantPositive);
            double coverage = measure(relevantPositive);
            double slack = Domain.canonicalTime(budget - routeLowerBound);
            double detour = Domain.canonicalTime(routeLowerBound - direct);
            relevant.add(new RelevantAnchor(
                    anchor,
                    relevantValid,
                    relevantPositive,
                    scorePotential,
                    coverage,
                    slack,
                    detour));
        }
        Comparator<RelevantAnchor> rank = Comparator
                .comparingDouble(RelevantAnchor::scorePotential).reversed()
                .thenComparing(Comparator.comparingDouble(RelevantAnchor::positiveCoverage).reversed())
                .thenComparing(Comparator.comparingDouble(RelevantAnchor::slack).reversed())
                .thenComparingDouble(RelevantAnchor::detour)
                .thenComparingInt(item -> item.anchor().stableArcId());
        relevant.sort(options.policy() == PaceExecutionPolicy.PACE_B
                ? rank
                : Comparator.comparingInt(item -> item.anchor().stableArcId()));
        if (options.policy() == PaceExecutionPolicy.PACE_B && relevant.size() > options.anchorLimit()) {
            return List.copyOf(relevant.subList(0, options.anchorLimit()));
        }
        return List.copyOf(relevant);
    }

    private static void requireCoverage(Edge edge, Domain.Interval horizon) {
        Domain requested = Domain.of(horizon);
        boolean travelCovered = requested.difference(edge.travelTimeFunction().domain()).isEmpty();
        boolean scoreCovered = requested.difference(edge.scoreFunction().domain()).isEmpty();
        if (!travelCovered || !scoreCovered) {
            throw new PaceException(
                    PaceStatus.FUNCTION_HORIZON_EXCEEDED,
                    "FUNCTION_HORIZON_EXCEEDED: arc " + edge.arcId()
                            + " does not cover query horizon " + horizon);
        }
    }

    private static double measure(Domain domain) {
        double measure = 0;
        for (Domain.Interval interval : domain.intervals()) {
            measure += Math.max(0, interval.end() - interval.start());
        }
        return measure;
    }

    private static double scoreIntegral(Edge edge, Domain domain) {
        if (domain.isEmpty()) {
            return 0;
        }
        double integral = 0;
        for (Domain.Interval part : domain.intervals()) {
            for (PiecewiseConstFn.Interval score : edge.scoreFunction().intervals()) {
                double start = Math.max(part.start(), score.startMinute());
                double end = Math.min(part.end(), score.endMinute());
                if (end > start) {
                    integral += (end - start) * score.value();
                }
            }
        }
        return integral;
    }
}
