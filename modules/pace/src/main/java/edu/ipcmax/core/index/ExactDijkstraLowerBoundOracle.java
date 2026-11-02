package edu.ipcmax.core.index;

import java.util.Objects;

import edu.ipcmax.core.graph.LowerBoundGraph;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

/**
 * Exact Dijkstra fallback for fixtures and preparation runs that fit memory.
 *
 * <p>This is admissible and exact, but it is not a preprocessed continental
 * routing index. That scalability gap remains explicit.</p>
 */
public final class ExactDijkstraLowerBoundOracle implements LowerBoundOracle {
    private final LowerBoundGraph graph;

    public ExactDijkstraLowerBoundOracle(TDGraph graph) {
        this.graph = new LowerBoundGraph(Objects.requireNonNull(graph, "graph"));
        for (int arcId = 0; arcId < graph.edgeCount(); arcId++) {
            double weight = this.graph.weight(arcId);
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new IllegalArgumentException(
                        "arc_id " + arcId
                                + " has non-positive lower-bound travel time: "
                                + weight);
            }
        }
    }

    @Override
    public double edgeWeight(int arcId) {
        return graph.weight(arcId);
    }

    @Override
    public Labels distancesFrom(int source) {
        return new DijkstraLabels(graph.distancesFromSource(source), true);
    }

    @Override
    public Labels distancesTo(int target) {
        return new DijkstraLabels(graph.distancesToTarget(target), false);
    }

    private record DijkstraLabels(
            LowerBoundGraph.Distances delegate,
            boolean forward) implements Labels {
        private DijkstraLabels {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public double distance(int node) {
            return delegate.distance(node);
        }

        @Override
        public boolean reached(int node) {
            return delegate.reached(node);
        }

        @Override
        public int edgeCount(int node) {
            return delegate.edgeCount(node);
        }

        @Override
        public Path witnessPath(int node) {
            return forward ? delegate.pathTo(node) : delegate.pathFrom(node);
        }
    }
}
