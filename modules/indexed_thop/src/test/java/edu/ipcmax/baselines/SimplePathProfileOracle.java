package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent tiny-graph oracle: recursively enumerates vertex-simple routes. */
final class SimplePathProfileOracle {
    private SimplePathProfileOracle() {
    }

    static Map<Integer, ExactTemporalProfile> allDestinations(
            TDGraph graph, int source, Domain departureDomain) {
        graph.node(source);
        Map<Integer, ExactTemporalProfile> best = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerate(
                graph,
                source,
                ExactTemporalProfile.identity(departureDomain),
                visited,
                best);
        return Map.copyOf(best);
    }

    private static void enumerate(
            TDGraph graph,
            int current,
            ExactTemporalProfile route,
            Set<Integer> visited,
            Map<Integer, ExactTemporalProfile> best) {
        ExactTemporalProfile prior = best.get(current);
        best.put(current, prior == null ? route : prior.pointwiseMinimum(route));
        for (Edge edge : graph.outgoingEdges(current)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            ExactTemporalProfile extended = route.composeAfter(ExactTemporalProfile.edgeArrival(edge));
            if (extended.isEmpty()) {
                continue;
            }
            visited.add(edge.target());
            enumerate(graph, edge.target(), extended, visited, best);
            visited.remove(edge.target());
        }
    }
}
