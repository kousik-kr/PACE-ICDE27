package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.GraphPartitionMetadata;

/**
 * Safe lower-bound query corridor over stable directed arc IDs.
 */
public final class QueryCorridor {
    private final int source;
    private final int destination;
    private final double budget;
    private final List<Integer> vertexIds;
    private final List<Integer> directedArcIds;
    private final List<String> activeCellIds;
    private final BitSet arcMembership;
    private final Map<Integer, List<Edge>> outgoing;
    private final Map<Integer, List<Edge>> incoming;
    private final String checksum;

    private QueryCorridor(
            int source,
            int destination,
            double budget,
            List<Integer> vertexIds,
            List<Integer> directedArcIds,
            List<String> activeCellIds,
            BitSet arcMembership,
            Map<Integer, List<Edge>> outgoing,
            Map<Integer, List<Edge>> incoming,
            String checksum) {
        this.source = source;
        this.destination = destination;
        this.budget = budget;
        this.vertexIds = List.copyOf(vertexIds);
        this.directedArcIds = List.copyOf(directedArcIds);
        this.activeCellIds = List.copyOf(activeCellIds);
        this.arcMembership = (BitSet) arcMembership.clone();
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.checksum = checksum;
    }

    /**
     * Builds a corridor using budget-truncated forward and reverse searches.
     *
     * <p>An arc {@code e=(x,y)} is retained exactly when
     * {@code d(s,x)+tau_lb(e)+d(y,d)<=B}. Assembly visits only outgoing arcs
     * of forward-active vertices.</p>
     */
    public static QueryCorridor build(
            TDGraph graph,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            int source,
            int destination,
            double budget) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(lowerBounds, "lowerBounds");
        Objects.requireNonNull(partition, "partition");
        graph.node(source);
        graph.node(destination);
        if (!Double.isFinite(budget) || budget < 0) {
            throw new IllegalArgumentException(
                    "corridor budget must be finite and nonnegative");
        }
        double canonicalBudget = Domain.canonicalTime(budget);
        QueryLowerBounds.Distances fromSource =
                lowerBounds.truncatedDistancesFrom(source, canonicalBudget);
        QueryLowerBounds.Distances toDestination =
                lowerBounds.truncatedDistancesTo(destination, canonicalBudget);
        return build(
                graph,
                lowerBounds,
                partition,
                source,
                destination,
                canonicalBudget,
                fromSource,
                toDestination);
    }

    /**
     * Builds a corridor from query-scoped labels that were already computed
     * for the same source, destination, and budget.
     */
    static QueryCorridor build(
            TDGraph graph,
            QueryLowerBounds lowerBounds,
            GraphPartitionMetadata partition,
            int source,
            int destination,
            double budget,
            QueryLowerBounds.Distances fromSource,
            QueryLowerBounds.Distances toDestination) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(lowerBounds, "lowerBounds");
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(fromSource, "fromSource");
        Objects.requireNonNull(toDestination, "toDestination");
        graph.node(source);
        graph.node(destination);
        if (!Double.isFinite(budget) || budget < 0) {
            throw new IllegalArgumentException(
                    "corridor budget must be finite and nonnegative");
        }
        double canonicalBudget = Domain.canonicalTime(budget);
        BitSet membership = new BitSet(graph.edgeCount());
        TreeSet<Integer> vertices = new TreeSet<>();
        List<Integer> arcs = new ArrayList<>();
        Map<Integer, List<Edge>> outgoing = new HashMap<>();
        Map<Integer, List<Edge>> incoming = new HashMap<>();
        fromSource.forEachReached(vertex -> {
            double prefix = fromSource.distance(vertex);
            for (Edge edge : graph.outgoingEdges(vertex)) {
                double suffix = toDestination.distance(edge.target());
                if (!Double.isFinite(suffix)) {
                    continue;
                }
                double bound = Domain.canonicalTime(
                        prefix
                                + lowerBounds.edgeWeight(edge.arcId())
                                + suffix);
                if (bound > canonicalBudget) {
                    continue;
                }
                membership.set(edge.arcId());
                arcs.add(edge.arcId());
                vertices.add(edge.source());
                vertices.add(edge.target());
                outgoing.computeIfAbsent(
                        edge.source(), ignored -> new ArrayList<>()).add(edge);
                incoming.computeIfAbsent(
                        edge.target(), ignored -> new ArrayList<>()).add(edge);
            }
        });
        if (source == destination || !arcs.isEmpty()) {
            vertices.add(source);
            vertices.add(destination);
        }
        arcs.sort(Integer::compare);
        Map<Integer, List<Edge>> frozenOutgoing = new HashMap<>();
        outgoing.forEach((vertex, edges) -> {
            edges.sort(java.util.Comparator.comparingInt(Edge::arcId));
            frozenOutgoing.put(vertex, List.copyOf(edges));
        });
        Map<Integer, List<Edge>> frozenIncoming = new HashMap<>();
        incoming.forEach((vertex, edges) -> {
            edges.sort(java.util.Comparator.comparingInt(Edge::arcId));
            frozenIncoming.put(vertex, List.copyOf(edges));
        });
        TreeSet<String> cells = new TreeSet<>();
        for (int vertex : vertices) {
            cells.add(partition.cellForVertex(vertex).cellId());
        }
        String checksum = checksum(
                source,
                destination,
                canonicalBudget,
                arcs,
                cells);
        return new QueryCorridor(
                source,
                destination,
                canonicalBudget,
                new ArrayList<>(vertices),
                arcs,
                new ArrayList<>(cells),
                membership,
                Collections.unmodifiableMap(frozenOutgoing),
                Collections.unmodifiableMap(frozenIncoming),
                checksum);
    }

    /**
     * Builds the explicit no-corridor ablation over every graph arc.
     *
     * <p>This deliberately scans the graph and is never the default path.</p>
     */
    public static QueryCorridor unpruned(
            TDGraph graph,
            GraphPartitionMetadata partition,
            int source,
            int destination,
            double budget) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(partition, "partition");
        graph.node(source);
        graph.node(destination);
        if (!Double.isFinite(budget) || budget < 0) {
            throw new IllegalArgumentException(
                    "corridor budget must be finite and nonnegative");
        }
        double canonicalBudget = Domain.canonicalTime(budget);
        BitSet membership = new BitSet(graph.edgeCount());
        List<Integer> arcs = new ArrayList<>(graph.edgeCount());
        Map<Integer, List<Edge>> outgoing = new HashMap<>();
        Map<Integer, List<Edge>> incoming = new HashMap<>();
        for (Edge edge : graph.edges()) {
            membership.set(edge.arcId());
            arcs.add(edge.arcId());
            outgoing.computeIfAbsent(
                    edge.source(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(
                    edge.target(), ignored -> new ArrayList<>()).add(edge);
        }
        Map<Integer, List<Edge>> frozenOutgoing = new HashMap<>();
        outgoing.forEach((vertex, edges) ->
                frozenOutgoing.put(vertex, List.copyOf(edges)));
        Map<Integer, List<Edge>> frozenIncoming = new HashMap<>();
        incoming.forEach((vertex, edges) ->
                frozenIncoming.put(vertex, List.copyOf(edges)));
        TreeSet<String> cells = new TreeSet<>();
        for (int vertex : graph.nodeIds()) {
            cells.add(partition.cellForVertex(vertex).cellId());
        }
        String checksum = checksum(
                source,
                destination,
                canonicalBudget,
                arcs,
                cells);
        return new QueryCorridor(
                source,
                destination,
                canonicalBudget,
                graph.nodeIds(),
                arcs,
                new ArrayList<>(cells),
                membership,
                Collections.unmodifiableMap(frozenOutgoing),
                Collections.unmodifiableMap(frozenIncoming),
                checksum);
    }

    public int source() {
        return source;
    }

    public int destination() {
        return destination;
    }

    public double budget() {
        return budget;
    }

    public List<Integer> vertexIds() {
        return vertexIds;
    }

    public List<Integer> directedArcIds() {
        return directedArcIds;
    }

    public List<String> activeCellIds() {
        return activeCellIds;
    }

    public boolean containsArc(int arcId) {
        return arcId >= 0 && arcMembership.get(arcId);
    }

    public List<Edge> outgoingEdges(int vertex) {
        return outgoing.getOrDefault(vertex, List.of());
    }

    public List<Edge> incomingEdges(int vertex) {
        return incoming.getOrDefault(vertex, List.of());
    }

    public String checksum() {
        return checksum;
    }

    private static String checksum(
            int source,
            int destination,
            double budget,
            List<Integer> arcs,
            Set<String> cells) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
        update(digest, "PACE-QUERY-CORRIDOR-v1");
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(source).array());
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(destination).array());
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(Double.doubleToLongBits(budget)).array());
        for (int arcId : arcs) {
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(arcId).array());
        }
        for (String cell : cells) {
            update(digest, cell);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }
}
