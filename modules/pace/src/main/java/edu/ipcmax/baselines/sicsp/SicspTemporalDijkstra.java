package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/** FIFO label-setting searches used by native SICSP's LST and EAT steps. */
final class SicspTemporalDijkstra {
    record EventPath(double time, Path path) {
    }

    record SearchOutcome(Optional<EventPath> result, long edgesConsidered) {
    }

    private record Label(int node, double time, List<Integer> arcs) {
    }

    private static final Comparator<Label> FORWARD_ORDER = Comparator
            .comparingDouble(Label::time)
            .thenComparingInt(label -> label.arcs().size())
            .thenComparing(Label::arcs, SicspTemporalDijkstra::compareArcIds)
            .thenComparingInt(Label::node);
    private static final Comparator<Label> BACKWARD_ORDER = Comparator
            .comparingDouble(Label::time).reversed()
            .thenComparingInt(label -> label.arcs().size())
            .thenComparing(Label::arcs, SicspTemporalDijkstra::compareArcIds)
            .thenComparingInt(Label::node);

    private final TDGraph graph;

    SicspTemporalDijkstra(TDGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    SearchOutcome earliestArrival(int source, int destination, double departure) {
        Label initial = new Label(source, Domain.canonicalTime(departure), List.of());
        Map<Integer, Label> best = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(FORWARD_ORDER);
        long edgesConsidered = 0;
        best.put(source, initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            Label label = queue.poll();
            if (!label.equals(best.get(label.node()))) {
                continue;
            }
            for (Edge edge : graph.outgoingEdges(label.node())) {
                edgesConsidered++;
                if (!edge.travelTimeFunction().domain().contains(label.time())) {
                    continue;
                }
                double arrival = edge.travelTimeFunction().arrivalTimeAt(label.time());
                List<Integer> arcs = append(label.arcs(), edge.arcId());
                Label candidate = new Label(edge.target(), arrival, arcs);
                Label previous = best.get(edge.target());
                if (previous == null || FORWARD_ORDER.compare(candidate, previous) < 0) {
                    best.put(edge.target(), candidate);
                    queue.add(candidate);
                }
            }
        }
        Label result = best.get(destination);
        return new SearchOutcome(result == null ? Optional.empty()
                : Optional.of(new EventPath(result.time(), new Path(result.arcs()))), edgesConsidered);
    }

    SearchOutcome latestDeparture(int source, int destination, double deadline) {
        Label initial = new Label(destination, Domain.canonicalTime(deadline), List.of());
        Map<Integer, Label> best = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(BACKWARD_ORDER);
        long edgesConsidered = 0;
        best.put(destination, initial);
        queue.add(initial);
        while (!queue.isEmpty()) {
            Label label = queue.poll();
            if (!label.equals(best.get(label.node()))) {
                continue;
            }
            for (Edge edge : graph.incomingEdges(label.node())) {
                edgesConsidered++;
                double departure = edge.travelTimeFunction().latestDepartureForArrival(label.time());
                if (departure == Double.NEGATIVE_INFINITY
                        || !edge.travelTimeFunction().domain().contains(departure)
                        || edge.travelTimeFunction().arrivalTimeAt(departure) > label.time()) {
                    continue;
                }
                List<Integer> arcs = prepend(edge.arcId(), label.arcs());
                Label candidate = new Label(edge.source(), departure, arcs);
                Label previous = best.get(edge.source());
                if (previous == null || BACKWARD_ORDER.compare(candidate, previous) < 0) {
                    best.put(edge.source(), candidate);
                    queue.add(candidate);
                }
            }
        }
        Label result = best.get(source);
        return new SearchOutcome(result == null ? Optional.empty()
                : Optional.of(new EventPath(result.time(), new Path(result.arcs()))), edgesConsidered);
    }

    private static List<Integer> append(List<Integer> arcs, int arcId) {
        List<Integer> result = new ArrayList<>(arcs.size() + 1);
        result.addAll(arcs);
        result.add(arcId);
        return List.copyOf(result);
    }

    private static List<Integer> prepend(int arcId, List<Integer> arcs) {
        List<Integer> result = new ArrayList<>(arcs.size() + 1);
        result.add(arcId);
        result.addAll(arcs);
        return List.copyOf(result);
    }

    private static int compareArcIds(List<Integer> left, List<Integer> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
