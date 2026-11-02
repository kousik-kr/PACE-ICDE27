package edu.ipcmax.core.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/**
 * Immutable, arc-ID-addressable temporal summaries used by query preparation.
 */
public final class EdgeTemporalSummaryStore {
    private final List<EdgeTemporalSummary> summaries;
    private final Domain commonFunctionSupport;

    private EdgeTemporalSummaryStore(
            List<EdgeTemporalSummary> summaries,
            Domain commonFunctionSupport) {
        this.summaries = List.copyOf(summaries);
        this.commonFunctionSupport = Objects.requireNonNull(
                commonFunctionSupport, "commonFunctionSupport");
    }

    /**
     * Builds summaries in directed arc-ID order.
     *
     * <p>PACE lower-bound routing requires strictly positive edge weights. The
     * generated road datasets satisfy this invariant; malformed preparation
     * inputs are rejected here rather than reaching Dijkstra.</p>
     */
    public static EdgeTemporalSummaryStore build(TDGraph graph) {
        return build(graph, true);
    }

    /**
     * Compatibility construction for exhaustive tiny-graph validation where
     * zero-time fixture arcs are legal. Dataset/query preparation must use
     * {@link #build(TDGraph)}, which retains the strict positive invariant.
     */
    public static EdgeTemporalSummaryStore buildAllowingZero(TDGraph graph) {
        return build(graph, false);
    }

    private static EdgeTemporalSummaryStore build(
            TDGraph graph,
            boolean requirePositive) {
        Objects.requireNonNull(graph, "graph");
        List<EdgeTemporalSummary> summaries = new ArrayList<>(graph.edgeCount());
        Domain commonSupport = null;
        for (Edge edge : graph.edges()) {
            double lowerBound = Domain.canonicalTime(
                    edge.travelTimeFunction().minTravelTime());
            if (!Double.isFinite(lowerBound)
                    || lowerBound < 0
                    || (requirePositive && lowerBound == 0)) {
                throw new IllegalArgumentException(
                        "arc_id " + edge.arcId()
                                + " has non-positive lower-bound travel time: "
                                + lowerBound);
            }
            List<PositiveScoreInterval> positive = new ArrayList<>();
            int maximumScore = 0;
            for (PiecewiseConstFn.Interval interval : edge.scoreFunction().intervals()) {
                maximumScore = Math.max(maximumScore, interval.value());
                if (interval.value() > 0) {
                    positive.add(new PositiveScoreInterval(
                            interval.startMinute(),
                            interval.endMinute(),
                            interval.value()));
                }
            }
            summaries.add(new EdgeTemporalSummary(
                    edge.arcId(), lowerBound, maximumScore, positive));
            Domain edgeSupport = edge.travelTimeFunction().domain()
                    .intersection(edge.scoreFunction().domain());
            commonSupport = commonSupport == null
                    ? edgeSupport
                    : commonSupport.intersection(edgeSupport);
        }
        return new EdgeTemporalSummaryStore(
                summaries,
                commonSupport == null ? Domain.empty() : commonSupport);
    }

    /** Number of directed arc summaries. */
    public int size() {
        return summaries.size();
    }

    /** Summary for one stable directed arc ID. */
    public EdgeTemporalSummary summary(int arcId) {
        if (arcId < 0 || arcId >= summaries.size()) {
            throw new IllegalArgumentException("unknown arc_id: " + arcId);
        }
        return summaries.get(arcId);
    }

    /** All summaries in ascending directed arc-ID order. */
    public List<EdgeTemporalSummary> summaries() {
        return summaries;
    }

    /** Exact time domain covered by every edge travel-time and score function. */
    public Domain commonFunctionSupport() {
        return commonFunctionSupport;
    }

    /** Query-preparation summary for one directed edge. */
    public record EdgeTemporalSummary(
            int arcId,
            double lowerBoundTravelTime,
            int maximumScore,
            List<PositiveScoreInterval> positiveScoreIntervals) {
        public EdgeTemporalSummary {
            if (arcId < 0
                    || !Double.isFinite(lowerBoundTravelTime)
                    || lowerBoundTravelTime < 0
                    || maximumScore < 0) {
                throw new IllegalArgumentException("invalid edge temporal summary");
            }
            positiveScoreIntervals = List.copyOf(positiveScoreIntervals);
        }
    }

    /** Half-open positive-score interval and its constant score value. */
    public record PositiveScoreInterval(
            double startMinute,
            double endMinute,
            int score) {
        public PositiveScoreInterval {
            if (!Double.isFinite(startMinute)
                    || !Double.isFinite(endMinute)
                    || endMinute < startMinute
                    || score <= 0) {
                throw new IllegalArgumentException("invalid positive-score interval");
            }
            startMinute = Domain.canonicalTime(startMinute);
            endMinute = Domain.canonicalTime(endMinute);
        }

        /** True when this interval overlaps a requested closed time range. */
        public boolean overlaps(Domain requested) {
            Objects.requireNonNull(requested, "requested");
            Domain support = startMinute == endMinute
                    ? Domain.closed(startMinute, endMinute)
                    : Domain.halfOpen(startMinute, endMinute);
            return !support.intersection(requested).isEmpty();
        }
    }
}
