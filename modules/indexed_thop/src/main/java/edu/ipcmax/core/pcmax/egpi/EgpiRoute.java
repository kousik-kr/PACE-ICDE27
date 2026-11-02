package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/** Complete source-to-destination route represented by alternating connectors and pivot arcs. */
public final class EgpiRoute {
    private final int source;
    private final int destination;
    private final List<PathWitness> connectors;
    private final List<Integer> pivots;
    private final List<Integer> edges;
    private final List<Integer> vertices;
    private final Set<Integer> visited;
    private final double staticCost;
    private final String digest;

    private EgpiRoute(TDGraph graph, int source, int destination,
            List<PathWitness> connectors, List<Integer> pivots) {
        graph.node(source);
        graph.node(destination);
        this.source = source;
        this.destination = destination;
        this.connectors = List.copyOf(connectors);
        this.pivots = List.copyOf(pivots);
        if (connectors.size() != pivots.size() + 1) {
            throw new IllegalArgumentException("route requires one connector in each anchor gap");
        }
        String graphIdentity = connectors.getFirst().graphIdentity();
        List<Integer> path = new ArrayList<>();
        List<Integer> vertexList = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        vertexList.add(source);
        seen.add(source);
        int current = source;
        double cost = 0;
        for (int i = 0; i < connectors.size(); i++) {
            PathWitness connector = Objects.requireNonNull(connectors.get(i), "connector");
            if (connector.source() != current || !graphIdentity.equals(connector.graphIdentity())) {
                throw new IllegalArgumentException("connector endpoint or graph identity mismatch");
            }
            for (int j = 0; j < connector.directedEdgeIds().size(); j++) {
                int arc = connector.directedEdgeIds().get(j);
                current = append(graph, arc, current, path, vertexList, seen);
                if (connector.vertexIds().get(j + 1) != current) {
                    throw new IllegalArgumentException("connector vertex identity mismatch");
                }
            }
            if (current != connector.destination()) {
                throw new IllegalArgumentException("connector does not reach its destination");
            }
            cost += connector.staticLowerBoundCost();
            if (i < pivots.size()) {
                int pivot = pivots.get(i);
                current = append(graph, pivot, current, path, vertexList, seen);
                // Only the structural bound is used here; exact feasibility belongs to replay.
                cost += graph.edges().get(pivot).travelTimeFunction().minTravelTime();
            }
        }
        if (current != destination) throw new IllegalArgumentException("incomplete route");
        this.edges = List.copyOf(path);
        this.vertices = List.copyOf(vertexList);
        this.visited = Set.copyOf(seen);
        this.staticCost = cost;
        this.digest = PathWitness.digest(graphIdentity, source, destination, edges);
    }

    private static int append(TDGraph graph, int arc, int current,
            List<Integer> edges, List<Integer> vertices, Set<Integer> visited) {
        if (arc < 0 || arc >= graph.edgeCount()) throw new IllegalArgumentException("missing route arc");
        Edge edge = graph.edges().get(arc);
        if (edge.source() != current) throw new IllegalArgumentException("disconnected or reversed route arc");
        if (!visited.add(edge.target())) throw new IllegalArgumentException("route repeats a vertex");
        edges.add(arc);
        vertices.add(edge.target());
        return edge.target();
    }

    public static EgpiRoute of(TDGraph graph, int source, int destination,
            List<PathWitness> connectors, List<Integer> pivots) {
        return new EgpiRoute(graph, source, destination, connectors, pivots);
    }

    /** Replace one connector while preserving every unaffected connector object. */
    public EgpiRoute insert(TDGraph graph, int gap, int pivotArc,
            PathWitness left, PathWitness right) {
        if (gap < 0 || gap >= connectors.size()) throw new IllegalArgumentException("unknown insertion gap");
        PathWitness old = connectors.get(gap);
        if (left.source() != old.source() || right.destination() != old.destination()) {
            throw new IllegalArgumentException("insertion endpoints do not match gap");
        }
        List<PathWitness> nextConnectors = new ArrayList<>(connectors);
        nextConnectors.set(gap, left);
        nextConnectors.add(gap + 1, right);
        List<Integer> nextPivots = new ArrayList<>(pivots);
        nextPivots.add(gap, pivotArc);
        return of(graph, source, destination, nextConnectors, nextPivots);
    }

    public int source() { return source; }
    public int destination() { return destination; }
    public List<PathWitness> orderedConnectors() { return connectors; }
    public List<Integer> orderedPivotEdges() { return pivots; }
    public List<Integer> completeEdgeSequence() { return edges; }
    public List<Integer> vertexIds() { return vertices; }
    public Set<Integer> visitedVertices() { return visited; }
    public double staticLowerBoundCost() { return staticCost; }
    public String canonicalRouteDigest() { return digest; }
}
