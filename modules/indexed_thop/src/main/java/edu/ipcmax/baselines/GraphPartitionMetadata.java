package edu.ipcmax.baselines;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic directed partition metadata for a {@link TDGraph}.
 *
 * <p>Assignments are supplied by the caller. This class does not implement or
 * claim to implement Natural Cut. A crossing arc remains directed exactly as
 * it appears in the input graph; its source and target are both boundary
 * vertices.</p>
 */
public final class GraphPartitionMetadata {
    private final Map<Integer, Integer> partitionByVertex;
    private final Map<Integer, List<Integer>> verticesByPartition;
    private final Map<Integer, List<Integer>> internalArcIdsByPartition;
    private final List<Integer> internalDirectedArcIds;
    private final List<Integer> crossPartitionDirectedArcIds;
    private final List<Integer> boundaryVertexIds;
    private final Map<Integer, List<Integer>> incomingBoundaryArcIds;
    private final Map<Integer, List<Integer>> outgoingBoundaryArcIds;
    private final Map<Integer, List<Integer>> partitionAdjacency;
    private final Map<PartitionPair, List<Integer>> crossArcsByPartitionPair;

    private GraphPartitionMetadata(
            Map<Integer, Integer> partitionByVertex,
            Map<Integer, List<Integer>> verticesByPartition,
            Map<Integer, List<Integer>> internalArcIdsByPartition,
            List<Integer> internalDirectedArcIds,
            List<Integer> crossPartitionDirectedArcIds,
            List<Integer> boundaryVertexIds,
            Map<Integer, List<Integer>> incomingBoundaryArcIds,
            Map<Integer, List<Integer>> outgoingBoundaryArcIds,
            Map<Integer, List<Integer>> partitionAdjacency,
            Map<PartitionPair, List<Integer>> crossArcsByPartitionPair) {
        this.partitionByVertex = Collections.unmodifiableMap(new LinkedHashMap<>(partitionByVertex));
        this.verticesByPartition = freezeListMap(verticesByPartition);
        this.internalArcIdsByPartition = freezeListMap(internalArcIdsByPartition);
        this.internalDirectedArcIds = List.copyOf(internalDirectedArcIds);
        this.crossPartitionDirectedArcIds = List.copyOf(crossPartitionDirectedArcIds);
        this.boundaryVertexIds = List.copyOf(boundaryVertexIds);
        this.incomingBoundaryArcIds = freezeListMap(incomingBoundaryArcIds);
        this.outgoingBoundaryArcIds = freezeListMap(outgoingBoundaryArcIds);
        this.partitionAdjacency = freezeListMap(partitionAdjacency);
        this.crossArcsByPartitionPair = Collections.unmodifiableMap(new LinkedHashMap<>(crossArcsByPartitionPair));
    }

    /** Builds metadata from an exact assignment for every graph vertex. */
    public static GraphPartitionMetadata fromAssignments(
            TDGraph graph, Map<Integer, Integer> suppliedAssignments) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(suppliedAssignments, "suppliedAssignments");
        if (!suppliedAssignments.keySet().equals(Set.copyOf(graph.nodeIds()))) {
            throw new IllegalArgumentException("partition assignment must contain every graph vertex exactly once");
        }

        TreeMap<Integer, Integer> assignment = new TreeMap<>();
        suppliedAssignments.forEach((vertex, partition) -> {
            graph.node(vertex);
            if (partition == null || partition < 0) {
                throw new IllegalArgumentException("partition IDs must be nonnegative");
            }
            assignment.put(vertex, partition);
        });

        TreeMap<Integer, List<Integer>> vertices = new TreeMap<>();
        assignment.forEach((vertex, partition) ->
                vertices.computeIfAbsent(partition, ignored -> new ArrayList<>()).add(vertex));
        TreeMap<Integer, List<Integer>> internalByPartition = new TreeMap<>();
        vertices.keySet().forEach(partition -> internalByPartition.put(partition, new ArrayList<>()));
        List<Integer> internal = new ArrayList<>();
        List<Integer> cross = new ArrayList<>();
        TreeSet<Integer> boundary = new TreeSet<>();
        TreeMap<Integer, List<Integer>> incomingBoundary = new TreeMap<>();
        TreeMap<Integer, List<Integer>> outgoingBoundary = new TreeMap<>();
        TreeMap<Integer, TreeSet<Integer>> adjacency = new TreeMap<>();
        TreeMap<PartitionPair, List<Integer>> crossByPair = new TreeMap<>();
        vertices.keySet().forEach(partition -> adjacency.put(partition, new TreeSet<>()));

        for (Edge edge : graph.edges()) {
            int sourcePartition = assignment.get(edge.source());
            int targetPartition = assignment.get(edge.target());
            if (sourcePartition == targetPartition) {
                internal.add(edge.arcId());
                internalByPartition.get(sourcePartition).add(edge.arcId());
            } else {
                cross.add(edge.arcId());
                boundary.add(edge.source());
                boundary.add(edge.target());
                outgoingBoundary.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.arcId());
                incomingBoundary.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge.arcId());
                adjacency.get(sourcePartition).add(targetPartition);
                crossByPair.computeIfAbsent(
                        new PartitionPair(sourcePartition, targetPartition), ignored -> new ArrayList<>())
                        .add(edge.arcId());
            }
        }

        boundary.forEach(vertex -> {
            incomingBoundary.computeIfAbsent(vertex, ignored -> new ArrayList<>());
            outgoingBoundary.computeIfAbsent(vertex, ignored -> new ArrayList<>());
        });

        TreeMap<Integer, List<Integer>> immutableAdjacency = new TreeMap<>();
        adjacency.forEach((partition, neighbors) ->
                immutableAdjacency.put(partition, List.copyOf(neighbors)));
        return new GraphPartitionMetadata(
                assignment,
                vertices,
                internalByPartition,
                internal,
                cross,
                new ArrayList<>(boundary),
                incomingBoundary,
                outgoingBoundary,
                immutableAdjacency,
                crossByPair);
    }

    /** Verifies that this assignment can describe exactly the supplied graph. */
    public void validateAgainst(TDGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (!partitionByVertex.keySet().equals(Set.copyOf(graph.nodeIds()))) {
            throw new IllegalArgumentException("partition metadata vertex set does not match graph");
        }
        GraphPartitionMetadata rebuilt = fromAssignments(graph, partitionByVertex);
        if (!equalsMetadata(rebuilt)) {
            throw new IllegalArgumentException("partition metadata does not match graph arcs");
        }
    }

    private boolean equalsMetadata(GraphPartitionMetadata other) {
        return partitionByVertex.equals(other.partitionByVertex)
                && verticesByPartition.equals(other.verticesByPartition)
                && internalArcIdsByPartition.equals(other.internalArcIdsByPartition)
                && internalDirectedArcIds.equals(other.internalDirectedArcIds)
                && crossPartitionDirectedArcIds.equals(other.crossPartitionDirectedArcIds)
                && boundaryVertexIds.equals(other.boundaryVertexIds)
                && incomingBoundaryArcIds.equals(other.incomingBoundaryArcIds)
                && outgoingBoundaryArcIds.equals(other.outgoingBoundaryArcIds)
                && partitionAdjacency.equals(other.partitionAdjacency)
                && crossArcsByPartitionPair.equals(other.crossArcsByPartitionPair);
    }

    private static Map<Integer, List<Integer>> freezeListMap(Map<Integer, List<Integer>> source) {
        LinkedHashMap<Integer, List<Integer>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    public int partitionCount() {
        return verticesByPartition.size();
    }

    public int boundaryVertexCount() {
        return boundaryVertexIds.size();
    }

    public int crossPartitionArcCount() {
        return crossPartitionDirectedArcIds.size();
    }

    public Map<Integer, Integer> partitionByVertex() {
        return partitionByVertex;
    }

    public Map<Integer, List<Integer>> verticesByPartition() {
        return verticesByPartition;
    }

    public Map<Integer, List<Integer>> internalArcIdsByPartition() {
        return internalArcIdsByPartition;
    }

    public List<Integer> internalDirectedArcIds() {
        return internalDirectedArcIds;
    }

    public List<Integer> crossPartitionDirectedArcIds() {
        return crossPartitionDirectedArcIds;
    }

    public List<Integer> boundaryVertexIds() {
        return boundaryVertexIds;
    }

    /** Cross-partition arcs entering this boundary vertex, by original arc ID. */
    public Map<Integer, List<Integer>> incomingBoundaryArcIds() {
        return incomingBoundaryArcIds;
    }

    /** Cross-partition arcs leaving this boundary vertex, by original arc ID. */
    public Map<Integer, List<Integer>> outgoingBoundaryArcIds() {
        return outgoingBoundaryArcIds;
    }

    /** Directed partition adjacency: p lists q when an original arc goes p to q. */
    public Map<Integer, List<Integer>> partitionAdjacency() {
        return partitionAdjacency;
    }

    public Map<PartitionPair, List<Integer>> crossArcsByPartitionPair() {
        return crossArcsByPartitionPair;
    }

    public record PartitionPair(int sourcePartition, int targetPartition)
            implements Comparable<PartitionPair> {
        public PartitionPair {
            if (sourcePartition < 0 || targetPartition < 0 || sourcePartition == targetPartition) {
                throw new IllegalArgumentException("partition pair must be directed between distinct partitions");
            }
        }

        @Override
        public int compareTo(PartitionPair other) {
            int sourceComparison = Integer.compare(sourcePartition, other.sourcePartition);
            return sourceComparison != 0
                    ? sourceComparison : Integer.compare(targetPartition, other.targetPartition);
        }
    }
}
