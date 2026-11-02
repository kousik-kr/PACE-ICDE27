package edu.ipcmax.core.pcmax.egpi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/** An immutable structural path, with no evaluated temporal profile. */
public record PathWitness(
        int source,
        int destination,
        List<Integer> directedEdgeIds,
        List<Integer> vertexIds,
        double staticLowerBoundCost,
        String graphIdentity,
        String restrictionIdentity,
        String canonicalPathDigest) {
    public static final String POLICY = "STATIC_LOWER_BOUND_FASTEST_WITNESS";

    public PathWitness {
        directedEdgeIds = List.copyOf(directedEdgeIds);
        vertexIds = List.copyOf(vertexIds);
        Objects.requireNonNull(graphIdentity, "graphIdentity");
        Objects.requireNonNull(restrictionIdentity, "restrictionIdentity");
        if (source <= 0 || destination <= 0 || !Double.isFinite(staticLowerBoundCost)
                || staticLowerBoundCost < 0 || vertexIds.size() != directedEdgeIds.size() + 1
                || vertexIds.getFirst() != source || vertexIds.getLast() != destination
                || new HashSet<>(vertexIds).size() != vertexIds.size()
                || directedEdgeIds.stream().anyMatch(id -> id < 0)) {
            throw new IllegalArgumentException("invalid structural path witness");
        }
        String expected = digest(graphIdentity, source, destination, directedEdgeIds);
        if (!expected.equals(canonicalPathDigest)) {
            throw new IllegalArgumentException("noncanonical path digest");
        }
    }

    public PathWitness(int source, int destination, List<Integer> edgeIds,
            List<Integer> vertices, double cost, String graphIdentity, String restrictionIdentity) {
        this(source, destination, edgeIds, vertices, cost, graphIdentity, restrictionIdentity,
                digest(graphIdentity, source, destination, edgeIds));
    }

    public String policy() { return POLICY; }

    /** Constructs and checks the directed structural walk against the immutable graph. */
    public static PathWitness of(TDGraph graph, int source, int destination,
            List<Integer> edgeIds, double cost, String graphIdentity, String restrictionIdentity) {
        graph.node(source);
        graph.node(destination);
        List<Integer> vertices = new ArrayList<>();
        vertices.add(source);
        int current = source;
        for (int arcId : edgeIds) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("unknown witness arc " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) {
                throw new IllegalArgumentException("disconnected or reversed witness arc " + arcId);
            }
            vertices.add(edge.target());
            current = edge.target();
        }
        if (current != destination) {
            throw new IllegalArgumentException("witness does not end at requested destination");
        }
        return new PathWitness(source, destination, edgeIds, vertices, cost,
                graphIdentity, restrictionIdentity);
    }

    public PathWitness withRestrictionIdentity(String restriction) {
        return new PathWitness(source, destination, directedEdgeIds, vertexIds,
                staticLowerBoundCost, graphIdentity, restriction, canonicalPathDigest);
    }

    static String digest(String graphIdentity, int source, int destination, List<Integer> arcs) {
        MessageDigest digest = newDigest();
        byte[] identity = graphIdentity.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(identity.length).array());
        digest.update(identity);
        digest.update(ByteBuffer.allocate(12).putInt(source).putInt(destination).putInt(arcs.size()).array());
        for (int arc : arcs) digest.update(ByteBuffer.allocate(4).putInt(arc).array());
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
