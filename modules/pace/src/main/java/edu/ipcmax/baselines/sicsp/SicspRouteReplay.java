package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Independent, no-wait path replay for native SICSP candidate validation. */
public final class SicspRouteReplay {
    private SicspRouteReplay() {
    }

    public record Step(int arcId, int sourceNode, int targetNode, double entryTime, double exitTime, double edgeWeight) {
    }

    public record Result(
            int source,
            int destination,
            double departureTime,
            double arrivalTime,
            double accumulatedWeight,
            List<Step> steps) {
        public Result {
            steps = List.copyOf(steps);
        }
    }

    public static Result replay(
            SicspNativeGraph graph,
            int source,
            int destination,
            double departureTime,
            Path path) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(path, "path");
        if (!Double.isFinite(departureTime)) {
            throw new IllegalArgumentException("departure time must be finite");
        }
        graph.temporalGraph().node(source);
        graph.temporalGraph().node(destination);
        double time = Domain.canonicalTime(departureTime);
        int node = source;
        double weight = 0.0;
        List<Step> steps = new ArrayList<>();
        for (int arcId : path.arcIds()) {
            if (arcId < 0 || arcId >= graph.temporalGraph().edgeCount()) {
                throw new IllegalArgumentException("unknown arc_id " + arcId);
            }
            Edge edge = graph.temporalGraph().edges().get(arcId);
            if (edge.source() != node) {
                throw new IllegalArgumentException("path is disconnected at arc_id " + arcId);
            }
            if (!edge.travelTimeFunction().domain().contains(time)) {
                throw new IllegalArgumentException(
                        "travel-time function excludes actual entry time " + time + " at arc_id " + arcId);
            }
            double edgeWeight = graph.weightFunction(arcId).valueAt(time);
            double exit = edge.travelTimeFunction().arrivalTimeAt(time);
            steps.add(new Step(arcId, edge.source(), edge.target(), time, exit, edgeWeight));
            weight += edgeWeight;
            time = exit;
            node = edge.target();
        }
        if (node != destination) {
            throw new IllegalArgumentException("path does not end at destination");
        }
        return new Result(source, destination, Domain.canonicalTime(departureTime),
                Domain.canonicalTime(time), Domain.canonicalTime(weight), steps);
    }
}
