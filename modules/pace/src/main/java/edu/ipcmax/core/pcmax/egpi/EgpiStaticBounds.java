package edu.ipcmax.core.pcmax.egpi;

import java.util.Objects;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.QueryCorridor;
import edu.ipcmax.core.pcmax.QueryLowerBounds;

/**
 * Admissible static lower bounds on connector cost inside the safe corridor.
 *
 * <p>For corridor distances {@code d_s} and {@code d_d}, the triangle
 * inequality gives {@code d(a,u) >= d_s(u)-d_s(a)} and
 * {@code d(a,u) >= d_d(a)-d_d(u)}. With
 * {@code rho = min_e tau_lb(e)/|e|} over corridor arcs of positive planar
 * length, every corridor path also costs at least {@code rho*|a-u|}. A
 * restricted connector can only be longer, so each bound remains admissible
 * under any exclusion set. These bounds are used only to reject proposals
 * whose optimistic cost already exceeds the budget.</p>
 */
final class EgpiStaticBounds {
    /** Conservative slack absorbing double rounding in sums of canonical weights. */
    static final double REJECTION_TOLERANCE = 1e-6;

    private final TDGraph graph;
    private final EgpiPathWitnessStore labels;
    private final double rho;

    EgpiStaticBounds(TDGraph graph, QueryCorridor corridor, QueryLowerBounds weights,
            EgpiPathWitnessStore labels) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.labels = Objects.requireNonNull(labels, "labels");
        double ratio = Double.POSITIVE_INFINITY;
        boolean coordinates = true;
        for (int arc : corridor.directedArcIds()) {
            Edge edge = graph.edges().get(arc);
            Node from = graph.node(edge.source());
            Node to = graph.node(edge.target());
            if (!from.coordinatesAvailable() || !to.coordinatesAvailable()) {
                coordinates = false;
                break;
            }
            double length = planar(from, to);
            if (length > 0) ratio = Math.min(ratio, weights.edgeWeight(arc) / length);
        }
        this.rho = coordinates && Double.isFinite(ratio) && ratio > 0
                // Shrink by one ulp-scale factor so rounding can only weaken the bound.
                ? ratio * (1 - 1e-12) : 0;
    }

    double geometricRatio() { return rho; }

    /** Admissible lower bound on any corridor path from {@code from} to {@code to}. */
    double connector(int from, int to) {
        if (from == to) return 0;
        double bound = 0;
        double sFrom = labels.sourceDistance(from), sTo = labels.sourceDistance(to);
        if (Double.isFinite(sFrom) && Double.isFinite(sTo)) bound = Math.max(bound, sTo - sFrom);
        double dFrom = labels.destinationDistance(from), dTo = labels.destinationDistance(to);
        if (Double.isFinite(dFrom) && Double.isFinite(dTo)) bound = Math.max(bound, dFrom - dTo);
        if (rho > 0) bound = Math.max(bound, rho * planar(graph.node(from), graph.node(to)));
        return Math.max(0, bound);
    }

    private static double planar(Node a, Node b) {
        double dx = (double) a.x() - b.x();
        double dy = (double) a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
