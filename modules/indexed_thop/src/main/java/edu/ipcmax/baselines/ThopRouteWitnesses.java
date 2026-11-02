package edu.ipcmax.baselines;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Fastest-route witnesses for a THop fastest-arrival profile.
 *
 * <p>THop's native output is a piecewise-affine fastest-arrival profile; it carries no route. For every affine piece of that profile
 * this class recovers original-graph edge sequences whose <em>exact</em> arrival function over (a sub-interval of) the piece is
 * identical to the piece, so that the route is a witness of what THop reported and not an approximation of it:</p>
 * <ol>
 *   <li>an exact fixed-departure time-dependent Dijkstra at an interior sample departure proposes a route (deterministic tie rule:
 *       among equal arrival times the smaller incoming arc id wins);</li>
 *   <li>the route's exact arrival profile is formed by exact composition of the edge arrival functions;</li>
 *   <li>the route becomes the witness of every sub-interval on which that profile equals the THop piece (same slope and intercept);</li>
 *   <li>sub-intervals not covered are re-sampled; a sample whose Dijkstra arrival differs from the THop value is reported as a
 *       <em>disagreement</em> (the profile and the oracle contradict each other), and any remaining part is reported as unverified.</li>
 * </ol>
 * THop's objective is not changed: nothing here optimises preference, enumerates pivots or alters the profile.
 */
public final class ThopRouteWitnesses {
    private ThopRouteWitnesses() { }

    /** A route that exactly reproduces THop's arrival function {@code slope*t+intercept} on {@code interval}. */
    public record Witness(ExactTemporalDomain.Interval interval, List<Integer> arcIds, ExactTime arrivalSlope, ExactTime arrivalIntercept) {
        public Witness {
            arcIds = List.copyOf(arcIds);
        }
    }

    /** THop's reported value and the exact oracle's value at one sampled departure. */
    public record Disagreement(ExactTime departure, ExactTime thopArrival, ExactTime oracleArrival) { }

    public record Stats(int profilePieces, int distinctRoutes, long routeSearches, long routeProfileCompositions, long searchNanos, long verifyNanos) { }

    public record Result(List<Witness> witnesses, ExactTemporalDomain unverified, List<Disagreement> disagreements, Stats stats) {
        public boolean fullyVerified() {
            return unverified.isEmpty() && disagreements.isEmpty();
        }
    }

    private static final int MAX_ROUTES_PER_PIECE = 64;

    /**
     * Independent exact oracle for one fixed departure: time-dependent Dijkstra on the original graph in exact rational arithmetic, with no THop index involved. It is reused
     * across calls (the arc arrival functions it has converted stay cached, up to a bound) and is not thread-safe.
     */
    public static final class Oracle {
        private static final int MAX_CACHED_ARCS = 2_000_000;
        private final Context context;

        private Oracle(TDGraph graph) {
            this.context = new Context(graph);
        }

        /** Exact earliest arrival at {@code destination} when leaving {@code source} at {@code departure}; {@code null} when the destination cannot be reached. */
        public ExactTime fastestArrival(int source, int destination, ExactTime departure) {
            if (context.edgeProfileCount() > MAX_CACHED_ARCS) context.clearEdgeProfiles();
            List<Integer> route = context.fastestRoute(source, destination, departure);
            if (route == null) return null;
            ExactTime time = departure;
            for (int arcId : route) time = context.edgeProfile(context.arcOf(arcId)).valueAtExact(time);
            return time;
        }
    }

    public static Oracle oracle(TDGraph graph) {
        return new Oracle(graph);
    }

    public static Result reconstruct(TDGraph graph, int source, int destination, ExactArrivalProfile thopArrival) {
        Context context = new Context(graph);
        List<Witness> witnesses = new ArrayList<>();
        List<Disagreement> disagreements = new ArrayList<>();
        ExactTemporalDomain unverified = ExactTemporalDomain.empty();
        int pieces = 0;
        for (ExactAffineSegment piece : thopArrival.segments()) {
            pieces++;
            ExactTemporalDomain remaining = ExactTemporalDomain.of(piece.interval());
            for (int attempt = 0; attempt < MAX_ROUTES_PER_PIECE && !remaining.isEmpty(); attempt++) {
                ExactTemporalDomain.Interval target = remaining.intervals().get(0);
                // the first try samples the middle of the uncovered part; if the sample lands where ownership is ambiguous, the next try uses a quarter point
                ExactTime sample = attempt % 2 == 0 ? midpoint(target, 1, 2) : midpoint(target, 1, 4);
                long searchStarted = System.nanoTime();
                List<Integer> route = context.fastestRoute(source, destination, sample);
                context.searchNanos += System.nanoTime() - searchStarted;
                context.searches++;
                ExactTime thopValue = piece.valueAtClosure(sample);
                if (route == null) {
                    unverified = unverified.union(ExactTemporalDomain.of(target));
                    remaining = remaining.difference(ExactTemporalDomain.of(target));
                    continue;
                }
                long verifyStarted = System.nanoTime();
                ExactArrivalProfile routeProfile = context.routeProfile(route, thopArrival.domain());
                ExactTime oracleValue = routeProfile.valueAtExact(sample);
                context.verifyNanos += System.nanoTime() - verifyStarted;
                if (!oracleValue.equals(thopValue)) {
                    disagreements.add(new Disagreement(sample, thopValue, oracleValue));
                    unverified = unverified.union(ExactTemporalDomain.of(target));
                    remaining = remaining.difference(ExactTemporalDomain.of(target));
                    continue;
                }
                ExactTemporalDomain covered = ExactTemporalDomain.empty();
                if (target.isSingleton() && piece.interval().isSingleton()) {
                    // a single departure: only the arrival VALUE is meaningful (the slope of a degenerate piece is representation noise); it was just verified exactly above
                    witnesses.add(new Witness(piece.interval(), route, piece.slope(), piece.intercept()));
                    remaining = remaining.difference(ExactTemporalDomain.of(piece.interval()));
                    continue;
                }
                for (ExactAffineSegment owned : routeProfile.segments()) {
                    if (!owned.slope().equals(piece.slope()) || !owned.intercept().equals(piece.intercept())) continue;
                    ExactTemporalDomain.Interval common = owned.interval().intersection(piece.interval());
                    if (common == null) continue;
                    witnesses.add(new Witness(common, route, piece.slope(), piece.intercept()));
                    covered = covered.union(ExactTemporalDomain.of(common));
                }
                // an isolated leftover point (typically a junction owned by the other neighbour in the route's own canonical form) is covered when the route's exact value equals THop's there
                for (ExactTemporalDomain.Interval leftover : remaining.difference(covered).intervals()) {
                    if (!leftover.isSingleton()) continue;
                    ExactTime at = leftover.start();
                    try {
                        if (routeProfile.valueAtExact(at).equals(piece.valueAtClosure(at))) {
                            witnesses.add(new Witness(leftover, route, piece.slope(), piece.intercept()));
                            covered = covered.union(ExactTemporalDomain.of(leftover));
                        }
                    } catch (RuntimeException outsideSupport) {
                        // the route is not defined there: the point stays uncovered
                    }
                }
                ExactTemporalDomain next = remaining.difference(covered);
                if (next.equals(remaining)) { // the sample was not owned by a matching piece of this route: force progress by retrying from another sample
                    continue;
                }
                remaining = next;
            }
            if (!remaining.isEmpty()) unverified = unverified.union(remaining);
        }
        witnesses.sort(Comparator.comparing((Witness w) -> w.interval().start()).thenComparing(w -> !w.interval().startInclusive()));
        return new Result(Collections.unmodifiableList(witnesses), unverified, disagreements,
                new Stats(pieces, context.routeCache.size(), context.searches, context.compositions, context.searchNanos, context.verifyNanos));
    }

    private static ExactTime midpoint(ExactTemporalDomain.Interval interval, long numerator, long denominator) {
        if (interval.start().equals(interval.end())) return interval.start();
        ExactTime length = interval.end().subtract(interval.start());
        return interval.start().add(length.multiply(ExactTime.of(numerator)).divide(ExactTime.of(denominator)));
    }

    /** Edge arrival functions, exact route profiles and the exact fixed-departure search, cached per graph. */
    static final class Context {
        private final TDGraph graph;
        private final Map<Integer, ExactArrivalProfile> edgeProfiles = new HashMap<>();
        final Map<List<Integer>, ExactArrivalProfile> routeCache = new HashMap<>();
        long searches, compositions, searchNanos, verifyNanos;

        Context(TDGraph graph) {
            this.graph = graph;
        }

        int edgeProfileCount() {
            return edgeProfiles.size();
        }

        void clearEdgeProfiles() {
            edgeProfiles.clear();
        }

        Edge arcOf(int arcId) {
            return arc(arcId);
        }

        ExactArrivalProfile edgeProfile(Edge edge) {
            return edgeProfiles.computeIfAbsent(edge.arcId(), id -> ExactArrivalProfile.edgeArrival(edge));
        }

        /** Exact arrival at the end of the route as a function of the departure time, over the supported part of {@code domain}. */
        ExactArrivalProfile routeProfile(List<Integer> route, ExactTemporalDomain domain) {
            return routeCache.computeIfAbsent(route, key -> {
                ExactArrivalProfile profile = ExactArrivalProfile.identity(domain);
                for (int arcId : key) {
                    profile = profile.composeAfter(edgeProfile(arc(arcId)));
                    compositions++;
                }
                return profile;
            });
        }

        /** Arc ids are the consecutive indices 0..m-1 of the graph's edge list (a TDGraph invariant), so no per-graph lookup table is needed. */
        private Edge arc(int arcId) {
            List<Edge> edges = graph.edges();
            if (arcId < 0 || arcId >= edges.size()) throw new IllegalArgumentException("unknown arc " + arcId);
            return edges.get(arcId);
        }

        private record Label(int node, ExactTime arrival) { }

        /** Exact fixed-departure fastest route; ties on the arrival time are broken by the smaller incoming arc id. {@code null} when unreachable. */
        List<Integer> fastestRoute(int source, int destination, ExactTime departure) {
            Map<Integer, ExactTime> best = new HashMap<>();
            Map<Integer, Integer> viaArc = new HashMap<>();
            Map<Integer, Integer> viaNode = new HashMap<>();
            Map<Integer, Boolean> settled = new HashMap<>();
            PriorityQueue<Label> queue = new PriorityQueue<>(Comparator.comparing(Label::arrival).thenComparingInt(Label::node));
            best.put(source, departure);
            queue.add(new Label(source, departure));
            while (!queue.isEmpty()) {
                Label label = queue.poll();
                if (settled.containsKey(label.node()) || !label.arrival().equals(best.get(label.node()))) continue;
                settled.put(label.node(), true);
                if (label.node() == destination) break;
                for (Edge edge : graph.outgoingEdges(label.node())) {
                    if (settled.containsKey(edge.target())) continue;
                    ExactArrivalProfile profile = edgeProfile(edge);
                    ExactTime arrival;
                    try {
                        arrival = profile.valueAtExact(label.arrival());
                    } catch (RuntimeException outsideSupport) {
                        continue; // the edge function is not defined at this entry time
                    }
                    if (arrival == null) continue;
                    ExactTime known = best.get(edge.target());
                    boolean better = known == null || arrival.compareTo(known) < 0
                            || (arrival.compareTo(known) == 0 && edge.arcId() < viaArc.get(edge.target()));
                    if (better) {
                        best.put(edge.target(), arrival);
                        viaArc.put(edge.target(), edge.arcId());
                        viaNode.put(edge.target(), label.node());
                        queue.add(new Label(edge.target(), arrival));
                    }
                }
            }
            if (!settled.containsKey(destination)) return null;
            List<Integer> route = new ArrayList<>();
            for (int node = destination; node != source; node = viaNode.get(node)) route.add(viaArc.get(node));
            Collections.reverse(route);
            return route;
        }
    }
}
