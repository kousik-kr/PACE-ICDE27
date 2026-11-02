package edu.ipcmax.baselines;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Route witnesses and preference scoring of THop's fastest profile, checked against independent oracles on small graphs. */
class ThopRouteWitnessesTest {
    private static final Domain HORIZON = Domain.closed(0, 60);
    private static final Domain QUERY = Domain.closed(0, 20);

    @Test
    void everyPieceOfEveryFastestProfileHasAnExactWitnessRouteAgreeingWithTheIndependentOracle() {
        TDGraph graph = switchingGraph(2);
        THopIndex index = THopIndex.buildPruned(graph, HORIZON);
        int checkedPairs = 0;
        for (int source : graph.nodeIds()) {
            Map<Integer, ExactTemporalProfile> oracle = SimplePathProfileOracle.allDestinations(graph, source, HORIZON);
            for (int destination : graph.nodeIds()) {
                if (source == destination || !oracle.containsKey(destination)) continue;
                ExactArrivalProfile travel = index.query(source, destination, QUERY).exactTravelTimeProfile();
                if (travel.isEmpty()) continue;
                ExactArrivalProfile arrival = travel.asArrivalProfile();
                // the independent simple-path oracle equals THop's profile; the witnesses below are checked against THop's profile exactly, hence against the oracle
                assertTrue(oracle.get(destination).restrict(QUERY).asTravelTime().sameFunction(index.query(source, destination, QUERY).travelTimeProfile()),
                        "independent oracle must equal the THop profile for " + source + "->" + destination);
                ThopRouteWitnesses.Result result = ThopRouteWitnesses.reconstruct(graph, source, destination, arrival);
                assertTrue(result.fullyVerified(), "unverified pieces " + result.unverified() + " " + result.disagreements() + " for " + source + "->" + destination);
                // the witness intervals tile exactly the profile's domain
                ExactTemporalDomain covered = ExactTemporalDomain.empty();
                for (ThopRouteWitnesses.Witness witness : result.witnesses()) covered = covered.union(ExactTemporalDomain.of(witness.interval()));
                assertEquals(arrival.domain(), covered, "witnesses must cover the profile domain exactly");
                for (ThopRouteWitnesses.Witness witness : result.witnesses()) {
                    assertValidSimpleWalk(graph, witness.arcIds(), source, destination);
                    for (ExactTime t : samples(witness.interval())) {
                        ExactTime viaRoute = walkArrival(graph, witness.arcIds(), t);
                        assertEquals(arrival.valueAtExact(t), viaRoute, "route arrival must equal THop's arrival at " + t);
                    }
                }
                checkedPairs++;
            }
        }
        assertEquals(5, checkedPairs, "the fixture graph has exactly five reachable ordered pairs: 1->2, 1->3, 1->4, 2->4, 3->4");
    }

    @Test
    void routeChangesAreWitnessedByDifferentRoutesAndEqualCostTiesAreDeterministic() {
        TDGraph switching = switchingGraph(0);
        THopIndex index = THopIndex.buildPruned(switching, HORIZON);
        ExactArrivalProfile arrival = index.query(1, 4, QUERY).exactTravelTimeProfile().asArrivalProfile();
        ThopRouteWitnesses.Result first = ThopRouteWitnesses.reconstruct(switching, 1, 4, arrival);
        ThopRouteWitnesses.Result second = ThopRouteWitnesses.reconstruct(switching, 1, 4, arrival);
        assertTrue(first.fullyVerified());
        assertEquals(first.witnesses(), second.witnesses(), "reconstruction must be deterministic");
        assertTrue(new HashSet<>(first.witnesses().stream().map(ThopRouteWitnesses.Witness::arcIds).toList()).size() >= 2,
                "the fastest route changes inside the window, so more than one witness route is expected");

        // two routes with identical, constant cost: the smaller incoming arc id wins, every time
        TDGraph ties = graph(List.of(1, 2, 3, 4),
                edge(0, 1, 2, constant(0, 60, 1), 0), edge(1, 2, 4, constant(0, 60, 1), 0),
                edge(2, 1, 3, constant(0, 60, 1), 0), edge(3, 3, 4, constant(0, 60, 1), 0));
        THopIndex tieIndex = THopIndex.buildPruned(ties, HORIZON);
        ThopRouteWitnesses.Result tie = ThopRouteWitnesses.reconstruct(ties, 1, 4, tieIndex.query(1, 4, QUERY).exactTravelTimeProfile().asArrivalProfile());
        assertTrue(tie.fullyVerified());
        for (ThopRouteWitnesses.Witness witness : tie.witnesses()) assertEquals(List.of(0, 1), witness.arcIds(), "tie rule: smaller incoming arc id");
    }

    @Test
    void aDisagreeingProfileIsReportedNeverSilentlyAccepted() {
        TDGraph graph = switchingGraph(0);
        THopIndex index = THopIndex.buildPruned(graph, HORIZON);
        ExactArrivalProfile correct = index.query(1, 4, QUERY).exactTravelTimeProfile().asArrivalProfile();
        // a profile that claims arrival one minute earlier than any route can achieve
        List<edu.ipcmax.baselines.exact.ExactAffineSegment> forged = correct.segments().stream()
                .map(s -> new edu.ipcmax.baselines.exact.ExactAffineSegment(s.slope(), s.intercept().subtract(ExactTime.ONE), s.interval())).toList();
        ThopRouteWitnesses.Result result = ThopRouteWitnesses.reconstruct(graph, 1, 4, ExactArrivalProfile.ofSegments(forged));
        assertFalse(result.fullyVerified());
        assertFalse(result.disagreements().isEmpty(), "the oracle must contradict the forged profile");
        assertTrue(result.witnesses().isEmpty(), "no witness may be produced for a profile no route realises");
    }

    @Test
    void preferenceKeepsFeasibilityApartFromScoreAndAssignsZeroOnlyToInfeasibleDepartures() {
        TDGraph graph = switchingGraph(3); // every arc scores 3 at every time, so a route's preference is 3 x its number of arcs when it is feasible
        THopIndex index = THopIndex.buildPruned(graph, HORIZON);
        ExactArrivalProfile travel = index.query(1, 4, QUERY).exactTravelTimeProfile();
        ThopRouteWitnesses.Result witnesses = ThopRouteWitnesses.reconstruct(graph, 1, 4, travel.asArrivalProfile());
        assertTrue(witnesses.fullyVerified());
        ExactTime budget = ExactTime.of(6); // the fastest travel time is above 6 early in the window and below it later: both kinds of departure occur
        ThopPreferenceScoring.Evaluation evaluation = ThopPreferenceScoring.evaluate(graph, 1, 4, witnesses.witnesses(), budget);
        assertFalse(evaluation.feasibleDomain().isEmpty());
        assertFalse(evaluation.infeasibleDomain().isEmpty());
        int feasibleChecked = 0;
        int infeasibleChecked = 0;
        for (ThopPreferenceScoring.Cell cell : evaluation.cells()) {
            for (ExactTime t : samples(cell.interval())) {
                ExactTime fastestTravel = travel.valueAtExact(t);
                boolean mustBeFeasible = fastestTravel.compareTo(budget) <= 0;
                assertEquals(mustBeFeasible, cell.feasible(), "feasibility at " + t + " (travel " + fastestTravel + ")");
                if (cell.feasible()) {
                    int arcs = witnesses.witnesses().get(cell.witnessIndex()).arcIds().size();
                    assertEquals(BigInteger.valueOf(3L * arcs), cell.score(), "preference of the fastest route at " + t);
                    feasibleChecked++;
                } else {
                    assertEquals(BigInteger.ZERO, cell.score());
                    infeasibleChecked++;
                }
            }
        }
        assertTrue(feasibleChecked > 0 && infeasibleChecked > 0);

        // genuinely zero preference stays distinguishable from infeasibility
        TDGraph zeroScore = switchingGraph(0);
        THopIndex zeroIndex = THopIndex.buildPruned(zeroScore, HORIZON);
        ThopRouteWitnesses.Result zeroWitnesses = ThopRouteWitnesses.reconstruct(zeroScore, 1, 4, zeroIndex.query(1, 4, QUERY).exactTravelTimeProfile().asArrivalProfile());
        ThopPreferenceScoring.Evaluation zero = ThopPreferenceScoring.evaluate(zeroScore, 1, 4, zeroWitnesses.witnesses(), budget);
        assertTrue(zero.cells().stream().anyMatch(c -> c.feasible() && c.score().signum() == 0), "feasible cells with a genuinely zero preference exist");
        assertTrue(zero.cells().stream().anyMatch(c -> !c.feasible() && c.score().signum() == 0), "infeasible cells with zero are distinct from them");
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private static void assertValidSimpleWalk(TDGraph graph, List<Integer> arcIds, int source, int destination) {
        assertFalse(arcIds.isEmpty());
        int at = source;
        HashSet<Integer> visited = new HashSet<>(List.of(source));
        for (int arcId : arcIds) {
            Edge edge = graph.edges().stream().filter(e -> e.arcId() == arcId).findFirst().orElseThrow();
            assertEquals(at, edge.source(), "route must be contiguous at arc " + arcId);
            at = edge.target();
            assertTrue(visited.add(at), "route must be vertex-simple");
        }
        assertEquals(destination, at);
    }

    /** Independent arrival: walk the arcs one by one with the exact edge arrival functions. */
    private static ExactTime walkArrival(TDGraph graph, List<Integer> arcIds, ExactTime departure) {
        ExactTime now = departure;
        for (int arcId : arcIds) {
            Edge edge = graph.edges().stream().filter(e -> e.arcId() == arcId).findFirst().orElseThrow();
            now = ExactArrivalProfile.edgeArrival(edge).valueAtExact(now);
        }
        return now;
    }

    private static List<ExactTime> samples(ExactTemporalDomain.Interval interval) {
        List<ExactTime> points = new ArrayList<>();
        ExactTime start = interval.start();
        ExactTime end = interval.end();
        if (start.equals(end)) return List.of(start);
        ExactTime length = end.subtract(start);
        for (int k = 1; k <= 3; k++) points.add(start.add(length.multiply(ExactTime.of(k)).divide(ExactTime.of(4))));
        if (interval.startInclusive()) points.add(start);
        if (interval.endInclusive()) points.add(end);
        return points;
    }

    private static TDGraph switchingGraph(int score) {
        PiecewiseLinearFn falling = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 8), new PiecewiseLinearFn.Breakpoint(10, 0), new PiecewiseLinearFn.Breakpoint(60, 0)));
        return graph(List.of(1, 2, 3, 4, 5),
                edge(0, 1, 2, constant(0, 60, 4), score), edge(1, 2, 4, constant(0, 60, 4), score), edge(2, 1, 3, falling, score),
                edge(3, 3, 4, constant(0, 60, 2), score), edge(4, 1, 4, constant(0, 60, 7), score));
    }

    private static PiecewiseLinearFn constant(double start, double end, double travelTime) {
        return new PiecewiseLinearFn(List.of(new PiecewiseLinearFn.Breakpoint(start, travelTime), new PiecewiseLinearFn.Breakpoint(end, travelTime)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, int score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel, PiecewiseConstFn.constant(travel.domain(), score));
    }

    private static TDGraph graph(List<Integer> nodeIds, Edge... edges) {
        List<Node> nodes = nodeIds.stream().map(id -> new Node(id, id, id)).toList();
        return new TDGraph(nodes, List.of(edges));
    }
}
