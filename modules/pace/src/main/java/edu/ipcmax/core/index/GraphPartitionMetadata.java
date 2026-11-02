package edu.ipcmax.core.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/**
 * Deterministic graph cells used by score-support indexing.
 *
 * <p>Cells are consecutive ranges in the graph's stable ascending vertex-ID
 * order. Directed edges belong to the cell containing their source. Boundary
 * vertices are endpoints in a cell that are incident to a cross-cell arc.</p>
 */
public final class GraphPartitionMetadata {
    public static final int DEFAULT_TARGET_VERTICES_PER_CELL = 4096;

    private final List<Cell> cells;
    private final Map<Integer, Integer> cellByVertex;

    private GraphPartitionMetadata(List<Cell> cells, Map<Integer, Integer> cellByVertex) {
        this.cells = List.copyOf(cells);
        this.cellByVertex = Map.copyOf(cellByVertex);
    }

    /** Builds stable cells with at most {@code targetVerticesPerCell} vertices. */
    public static GraphPartitionMetadata partition(
            TDGraph graph,
            int targetVerticesPerCell) {
        Objects.requireNonNull(graph, "graph");
        if (targetVerticesPerCell <= 0) {
            throw new IllegalArgumentException("target vertices per cell must be positive");
        }
        List<Integer> vertices = graph.nodeIds();
        Map<Integer, Integer> cellByVertex = new HashMap<>(vertices.size());
        int cellCount = (vertices.size() + targetVerticesPerCell - 1)
                / targetVerticesPerCell;
        List<List<Integer>> cellVertices = new ArrayList<>(cellCount);
        List<List<Integer>> cellEdges = new ArrayList<>(cellCount);
        List<TreeSet<Integer>> boundary = new ArrayList<>(cellCount);
        for (int index = 0; index < cellCount; index++) {
            cellVertices.add(new ArrayList<>());
            cellEdges.add(new ArrayList<>());
            boundary.add(new TreeSet<>());
        }
        for (int rank = 0; rank < vertices.size(); rank++) {
            int cellIndex = rank / targetVerticesPerCell;
            int vertex = vertices.get(rank);
            cellVertices.get(cellIndex).add(vertex);
            cellByVertex.put(vertex, cellIndex);
        }
        for (Edge edge : graph.edges()) {
            int sourceCell = requiredCell(cellByVertex, edge.source());
            int targetCell = requiredCell(cellByVertex, edge.target());
            cellEdges.get(sourceCell).add(edge.arcId());
            if (sourceCell != targetCell) {
                boundary.get(sourceCell).add(edge.source());
                boundary.get(targetCell).add(edge.target());
            }
        }
        List<Cell> cells = new ArrayList<>(cellCount);
        for (int index = 0; index < cellCount; index++) {
            cells.add(new Cell(
                    cellId(index),
                    index,
                    cellVertices.get(index),
                    cellEdges.get(index),
                    new ArrayList<>(boundary.get(index))));
        }
        return new GraphPartitionMetadata(cells, cellByVertex);
    }

    /** Default deterministic partition. */
    public static GraphPartitionMetadata partition(TDGraph graph) {
        return partition(graph, DEFAULT_TARGET_VERTICES_PER_CELL);
    }

    public List<Cell> cells() {
        return cells;
    }

    public int cellIndexForVertex(int vertexId) {
        return requiredCell(cellByVertex, vertexId);
    }

    public Cell cellForVertex(int vertexId) {
        return cells.get(cellIndexForVertex(vertexId));
    }

    public Cell cell(String stableCellId) {
        Objects.requireNonNull(stableCellId, "stableCellId");
        for (Cell cell : cells) {
            if (cell.cellId().equals(stableCellId)) {
                return cell;
            }
        }
        throw new IllegalArgumentException("unknown stable cell ID: " + stableCellId);
    }

    private static int requiredCell(Map<Integer, Integer> cells, int vertex) {
        Integer result = cells.get(vertex);
        if (result == null) {
            throw new IllegalArgumentException("unknown vertex: " + vertex);
        }
        return result;
    }

    private static String cellId(int index) {
        return String.format(java.util.Locale.ROOT, "CELL-%08d", index);
    }

    /** Immutable stable cell metadata. */
    public record Cell(
            String cellId,
            int cellIndex,
            List<Integer> vertexIds,
            List<Integer> directedArcIds,
            List<Integer> boundaryVertexIds) {
        public Cell {
            if (cellId == null || cellId.isBlank() || cellIndex < 0) {
                throw new IllegalArgumentException("invalid graph cell identity");
            }
            vertexIds = List.copyOf(vertexIds);
            directedArcIds = List.copyOf(directedArcIds);
            boundaryVertexIds = List.copyOf(boundaryVertexIds);
        }
    }
}
