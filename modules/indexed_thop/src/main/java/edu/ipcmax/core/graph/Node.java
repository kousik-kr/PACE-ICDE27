package edu.ipcmax.core.graph;

/**
 * Road-network node with optional coordinate metadata.
 */
public record Node(int id, long x, long y, boolean coordinatesAvailable) {
    /**
     * Creates a node with coordinates in the graph's native coordinate units.
     */
    public Node(int id, long x, long y) {
        this(id, x, y, true);
    }

    /**
     * Creates a validated node.
     */
    public Node {
        if (id <= 0) {
            throw new IllegalArgumentException("node id must be positive");
        }
    }

    /** Creates a node whose coordinates cannot be used for geometric bounds. */
    public static Node withoutCoordinates(int id) {
        return new Node(id, 0, 0, false);
    }
}
