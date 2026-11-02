package edu.ipcmax.experiments;

import java.util.List;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.graph.TinyGraphBuilder;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.function.PiecewiseLinearFn;

/** Small deterministic datasets bundled for infrastructure smoke tests. */
public final class ExperimentDatasets {
    private ExperimentDatasets() {
    }

    /** Four-node temporal graph with competing score/travel alternatives. */
    public static TDGraph demo() {
        PiecewiseConstFn scoreA = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 8),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        PiecewiseConstFn scoreB = new PiecewiseConstFn(List.of(
                new PiecewiseConstFn.Interval(0, 420, 0),
                new PiecewiseConstFn.Interval(420, 600, 5),
                new PiecewiseConstFn.Interval(600, 1440, 0)));
        return new TinyGraphBuilder()
                .node(1).node(2).node(3).node(4)
                .edge(1, 2, 10, scoreA)
                .edge(2, 4, 10, PiecewiseConstFn.zeroFullDay())
                .edge(1, 3, 5, scoreB)
                .edge(3, 4, 40, PiecewiseConstFn.zeroFullDay())
                .edge(1, 4, 25, PiecewiseConstFn.zeroFullDay())
                .build();
    }

    /** Yuan et al., Figure 1 travel functions; native weights are supplied separately. */
    public static TDGraph sicspFigureOne() {
        var first = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(40, 30),
                new PiecewiseLinearFn.Breakpoint(100, 40)));
        var second = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 40),
                new PiecewiseLinearFn.Breakpoint(50, 40),
                new PiecewiseLinearFn.Breakpoint(80, 10),
                new PiecewiseLinearFn.Breakpoint(100, 10)));
        return new TDGraph(List.of(new Node(1, 0, 0), new Node(2, 0, 0), new Node(3, 0, 0)),
                List.of(new Edge(0, 1, 2, 1, first.minTravelTime(), first,
                                PiecewiseConstFn.constant(first.domain(), 0)),
                        new Edge(1, 2, 3, 1, second.minTravelTime(), second,
                                PiecewiseConstFn.constant(second.domain(), 0))));
    }

    /** Dense acyclic graph used only to verify external timeout handling in infrastructure tests. */
    static TDGraph timeoutStress() {
        TinyGraphBuilder builder = new TinyGraphBuilder().node(1).node(4);
        for (int node = 5; node <= 23; node++) {
            builder.node(node);
        }
        for (int target = 5; target <= 23; target++) {
            builder.edge(1, target, 1);
        }
        for (int source = 5; source <= 23; source++) {
            for (int target = source + 1; target <= 23; target++) {
                builder.edge(source, target, 1);
            }
            builder.edge(source, 4, 1);
        }
        return builder.build();
    }
}
