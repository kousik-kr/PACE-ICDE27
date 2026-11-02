package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;

/** Deterministic time-dependent fixtures with sloped FIFO travel and stepped preference. */
final class EgpiFixtures {
    private EgpiFixtures() { }

    static TDGraph graph(List<Edge> edges) {
        TreeSet<Integer> ids = new TreeSet<>();
        edges.forEach(edge -> { ids.add(edge.source()); ids.add(edge.target()); });
        if (ids.isEmpty()) ids.add(1);
        return new TDGraph(ids.stream().map(id -> new Node(id, id * 10L, (id % 3) * 7L)).toList(), edges);
    }

    static TDGraph graphWithNodes(int nodes, List<Edge> edges) {
        List<Node> list = new ArrayList<>();
        for (int id = 1; id <= nodes; id++) list.add(new Node(id, id * 10L, (id % 3) * 7L));
        return new TDGraph(list, edges);
    }

    static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, PiecewiseConstFn score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel, score);
    }

    static PiecewiseLinearFn travel(double... values) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) points.add(new PiecewiseLinearFn.Breakpoint(values[i], values[i + 1]));
        return new PiecewiseLinearFn(points);
    }

    static PiecewiseLinearFn constant(double value) {
        return travel(0, value, 1440, value);
    }

    static PiecewiseConstFn scores(double... values) {
        List<PiecewiseConstFn.Interval> pieces = new ArrayList<>();
        for (int i = 0; i < values.length; i += 3) {
            pieces.add(new PiecewiseConstFn.Interval(values[i], values[i + 1], (int) values[i + 2]));
        }
        return new PiecewiseConstFn(pieces);
    }

    static PiecewiseConstFn zero() {
        return PiecewiseConstFn.zeroFullDay();
    }

    /** FIFO travel function over [0,1440] with a rush-hour ramp, 3-decimal values. */
    static PiecewiseLinearFn randomTravel(Random random) {
        double base = 1 + random.nextInt(9000) / 1000.0;
        int knots = 2 + random.nextInt(4);
        TreeSet<Integer> times = new TreeSet<>();
        while (times.size() < knots) times.add(5 + random.nextInt(190));
        List<Double> values = new ArrayList<>();
        values.add(0.0);
        values.add(base);
        double previousTime = 0;
        double previousValue = base;
        for (int time : times) {
            double span = time - previousTime;
            // |slope| < 1 keeps arrival nondecreasing (FIFO).
            double delta = (random.nextInt(1800) - 900) / 1000.0 * Math.min(span, 4);
            double value = Math.max(0.5, Math.round((previousValue + delta) * 1000) / 1000.0);
            values.add((double) time);
            values.add(value);
            previousTime = time;
            previousValue = value;
        }
        values.add(1440.0);
        values.add(previousValue);
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) array[i] = values.get(i);
        return travel(array);
    }

    static PiecewiseConstFn randomScore(Random random) {
        if (random.nextInt(3) == 0) return zero();
        List<PiecewiseConstFn.Interval> pieces = new ArrayList<>();
        int cursor = 0;
        while (cursor < 1440) {
            int end = cursor < 200 ? Math.min(200, cursor + 3 + random.nextInt(25)) : 1440;
            pieces.add(new PiecewiseConstFn.Interval(cursor, end, random.nextInt(4) == 0 ? 0 : random.nextInt(15)));
            cursor = end;
        }
        return new PiecewiseConstFn(pieces);
    }

    /** Random layered graph where every node has a path forward to {@code nodes}. */
    static TDGraph randomGraph(Random random, int nodes, int extraEdges) {
        List<Edge> edges = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int node = 1; node < nodes; node++) {
            int target = node + 1 + random.nextInt(Math.min(2, nodes - node));
            addEdge(random, edges, seen, node, target);
        }
        for (int i = 0; i < extraEdges; i++) {
            int a = 1 + random.nextInt(nodes), b = 1 + random.nextInt(nodes);
            if (a != b) addEdge(random, edges, seen, a, b);
        }
        return graphWithNodes(nodes, edges);
    }

    private static void addEdge(Random random, List<Edge> edges, Set<Long> seen, int a, int b) {
        if (!seen.add(((long) a << 32) | b)) return;
        edges.add(edge(edges.size(), a, b, randomTravel(random), randomScore(random)));
    }

    /** A random vertex-simple walk of up to {@code maxEdges} edges starting at {@code source}. */
    static List<Integer> randomSimplePath(Random random, TDGraph graph, int source, int maxEdges) {
        List<Integer> path = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        int current = source;
        for (int step = 0; step < maxEdges; step++) {
            List<Edge> options = new ArrayList<>();
            for (Edge edge : graph.outgoingEdges(current)) if (!visited.contains(edge.target())) options.add(edge);
            if (options.isEmpty()) break;
            Edge next = options.get(random.nextInt(options.size()));
            path.add(next.arcId());
            visited.add(next.target());
            current = next.target();
        }
        return path;
    }

    /** A long chain 1->2->...->n+1 of sloped, scored arcs: exact replay is deliberately costly. */
    static TDGraph chain(int arcs, long seed) {
        Random random = new Random(seed);
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < arcs; i++) {
            double[] knots = new double[16];
            double value = 1 + random.nextInt(3000) / 1000.0;
            for (int k = 0; k < 7; k++) {
                knots[2 * k] = k == 0 ? 0 : 3 + 7 * k + random.nextInt(5);
                knots[2 * k + 1] = Math.round((value + (random.nextInt(1400) - 700) / 1000.0) * 1000) / 1000.0;
                if (knots[2 * k + 1] < 0.5) knots[2 * k + 1] = 0.5 + k / 10.0;
            }
            knots[14] = 1440;
            knots[15] = knots[13];
            edges.add(edge(i, i + 1, i + 2, travel(knots), scores(0, 12, i % 7, 12, 27, (i * 3) % 11, 27, 1440, i % 5)));
        }
        return graph(edges);
    }
}
