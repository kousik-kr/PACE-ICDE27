package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.core.validate.ValidationResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exhaustive loopless-path oracle for tiny graphs and tests.
 */
public final class TinyGraphBruteForceOracle {
    private final TDGraph graph;
    private final int maxPathLength;

    /**
     * Creates an exhaustive oracle over all loopless paths.
     */
    public TinyGraphBruteForceOracle(TDGraph graph) {
        this(graph, Math.max(0, graph.nodeCount() - 1));
    }

    /**
     * Creates a bounded oracle with an explicit path-length cap.
     */
    public TinyGraphBruteForceOracle(TDGraph graph, int maxPathLength) {
        if (maxPathLength < 0) {
            throw new IllegalArgumentException("max path length cannot be negative");
        }
        this.graph = graph;
        this.maxPathLength = maxPathLength;
    }

    /**
     * Solves the query exactly by enumerating loopless paths and discrete departures.
     */
    public IPCMaxResult solve(QuerySpec query) {
        List<Path> paths = enumeratePaths(query.source(), query.destination());
        ExactPathValidator validator = new ExactPathValidator(graph);
        IPCMaxResult best = IPCMaxResult.notFound("no feasible path");

        for (int departure : query.departureDomain()) {
            if (!query.isOnGrid(departure)) {
                continue;
            }
            for (Path path : paths) {
                ValidationResult validation = validator.validate(
                        query.source(), query.destination(), departure, query.maxTravelTime(), path);
                if (!validation.valid()) {
                    continue;
                }
                IPCMaxResult candidate = new IPCMaxResult(
                        true,
                        departure,
                        validation.arrivalTime(),
                        validation.travelTime(),
                        validation.score(),
                        path,
                        "");
                if (ResultComparator.INSTANCE.compare(candidate, best) < 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private List<Path> enumeratePaths(int source, int destination) {
        List<Path> paths = new ArrayList<>();
        dfs(source, destination, new HashSet<>(), new ArrayList<>(), paths);
        return paths;
    }

    private void dfs(int node, int destination, Set<Integer> visited, List<Integer> arcs, List<Path> paths) {
        if (arcs.size() > maxPathLength) {
            return;
        }
        if (node == destination) {
            paths.add(new Path(arcs));
            return;
        }
        visited.add(node);
        for (Edge edge : graph.outgoingEdges(node)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            arcs.add(edge.arcId());
            dfs(edge.target(), destination, visited, arcs, paths);
            arcs.remove(arcs.size() - 1);
        }
        visited.remove(node);
    }
}
