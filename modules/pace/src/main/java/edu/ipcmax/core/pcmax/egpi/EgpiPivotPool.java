package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.pcmax.SafeQueryRegion;

/**
 * Lazily qualified preference-bearing pivot arcs of the safe query region.
 *
 * <p>Every score-bearing corridor arc is ordered once by a cheap static key:
 * its largest positive score inside the plausible entry window
 * {@code [r0+d_s(u), r1+B-tau_lb(e)-d_d(v)]}, then its lower-bound detour
 * through the arc, then arc ID. The exact feasible entry band of an arc is
 * computed only when the search first reaches its rank. An arc whose positive
 * score never meets that band cannot add preference on any budget-feasible
 * route and is skipped; so is an arc with no positive score anywhere in the
 * plausible window, which contains every feasible entry time. These are the
 * only arcs ever discarded, each by a certificate, never by the heuristic key. There is no
 * fixed top-L set: callers walk ranks as far as their work budget allows.</p>
 */
final class EgpiPivotPool {
    /** A qualified pivot and the features used by opportunity estimates. */
    record Pivot(int rank, int arcId, int tail, int head, double weight, int windowMaxScore,
            Domain positiveBand, double positiveMeasure, double detour,
            List<EdgeTemporalSummaryStore.PositiveScoreInterval> positiveIntervals) {
        /** Largest score this arc can contribute when entered within {@code [from, to]}. */
        int maxScoreBetween(double from, double to) {
            int best = 0;
            for (var interval : positiveIntervals) {
                if (interval.endMinute() < from || interval.startMinute() > to) continue;
                if (positiveBand.intersection(Domain.closed(Math.max(from, interval.startMinute()),
                        Math.min(to, interval.endMinute()))).isEmpty()) continue;
                best = Math.max(best, interval.score());
            }
            return best;
        }
    }

    private record Candidate(int arcId, int windowMaxScore, double detour) { }

    private final SafeQueryRegion region;
    private final EgpiPathWitnessStore labels;
    private final List<Candidate> ordered;
    private final List<Pivot> qualified = new ArrayList<>();
    private int cursor;
    private final TreeMap<String, Long> counters = new TreeMap<>();
    private int maximumScore;

    EgpiPivotPool(SafeQueryRegion region, EgpiPathWitnessStore labels) {
        this.region = Objects.requireNonNull(region, "region");
        this.labels = Objects.requireNonNull(labels, "labels");
        double r0 = region.query().departureStartExact().doubleValue();
        double r1 = region.query().departureEndExact().doubleValue();
        double budget = region.query().maxTravelTime();
        double shortest = labels.sourceDistance(region.query().destination());
        List<Candidate> candidates = new ArrayList<>();
        var summaries = region.indexes().edgeTemporalSummaries();
        for (int arcId : region.scoreBearingCorridorArcs()) {
            Edge edge = region.graph().edges().get(arcId);
            double prefix = labels.sourceDistance(edge.source());
            double suffix = labels.destinationDistance(edge.target());
            if (!Double.isFinite(prefix) || !Double.isFinite(suffix)) continue;
            double weight = region.lowerBounds().edgeWeight(arcId);
            // Every feasible entry time lies in this window; widening only keeps more arcs.
            double windowStart = r0 + prefix - EgpiStaticBounds.REJECTION_TOLERANCE;
            double windowEnd = r1 + budget - weight - suffix + EgpiStaticBounds.REJECTION_TOLERANCE;
            int windowMax = 0;
            for (var interval : summaries.summary(arcId).positiveScoreIntervals()) {
                if (interval.endMinute() < windowStart || interval.startMinute() > windowEnd) continue;
                windowMax = Math.max(windowMax, interval.score());
            }
            if (windowMax == 0) continue;
            double detour = Double.isFinite(shortest) ? Math.max(0, prefix + weight + suffix - shortest) : 0;
            candidates.add(new Candidate(arcId, windowMax, detour));
        }
        candidates.sort(Comparator.comparingInt(Candidate::windowMaxScore).reversed()
                .thenComparingDouble(Candidate::detour)
                .thenComparingInt(Candidate::arcId));
        this.ordered = List.copyOf(candidates);
        counters.put("pivot_pool_score_bearing_candidates", (long) ordered.size());
        counters.put("pivot_pool_bands_computed", 0L);
        counters.put("pivot_pool_band_certified_rejections", 0L);
        for (Candidate candidate : ordered) maximumScore = Math.max(maximumScore, candidate.windowMaxScore());
    }

    /** Upper bound on any single pivot score in the plausible windows. */
    int maximumScore() { return maximumScore; }

    int candidateCount() { return ordered.size(); }

    /** The qualified pivot at {@code rank}, qualifying lazily; {@code null} once the pool is exhausted. */
    Pivot pivot(int rank) {
        while (qualified.size() <= rank && cursor < ordered.size()) {
            Candidate candidate = ordered.get(cursor++);
            Domain band = region.feasibleEntryDomain(candidate.arcId());
            counters.merge("pivot_pool_bands_computed", 1L, Long::sum);
            Edge edge = region.graph().edges().get(candidate.arcId());
            Domain positive = edge.scoreFunction().positiveDomain().intersection(band);
            if (positive.isEmpty()) {
                counters.merge("pivot_pool_band_certified_rejections", 1L, Long::sum);
                continue;
            }
            double measure = 0;
            for (Domain.Interval interval : positive.intervals()) measure += interval.end() - interval.start();
            qualified.add(new Pivot(qualified.size(), candidate.arcId(), edge.source(), edge.target(),
                    region.lowerBounds().edgeWeight(candidate.arcId()), candidate.windowMaxScore(),
                    positive, measure, candidate.detour(),
                    region.indexes().edgeTemporalSummaries().summary(candidate.arcId()).positiveScoreIntervals()));
        }
        return rank < qualified.size() ? qualified.get(rank) : null;
    }

    Map<String, Long> stats() {
        TreeMap<String, Long> result = new TreeMap<>(counters);
        result.put("pivot_pool_qualified", (long) qualified.size());
        result.put("pivot_pool_examined", (long) cursor);
        return Map.copyOf(result);
    }
}
