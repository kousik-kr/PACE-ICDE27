package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.baselines.ExactTemporalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.validate.Path;

import java.util.Objects;

/** Arrival and weight profiles tied to the same complete arc-ID route. */
public record SicspRouteProfile(
        Path path,
        ExactTemporalProfile arrivalFunction,
        SicspWeightFunction weightFunction) {
    public SicspRouteProfile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(arrivalFunction, "arrivalFunction");
        Objects.requireNonNull(weightFunction, "weightFunction");
        if (!arrivalFunction.domain().equals(weightFunction.domain())) {
            throw new IllegalArgumentException("arrival and weight profiles must have identical route support");
        }
    }

    /** Constructs exact no-wait path profiles over the requested departure domain. */
    public static SicspRouteProfile build(
            SicspNativeGraph graph,
            int source,
            int destination,
            Domain departureDomain,
            Path path) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(departureDomain, "departureDomain");
        Objects.requireNonNull(path, "path");
        if (departureDomain.isEmpty()) {
            throw new IllegalArgumentException("departure domain cannot be empty");
        }
        graph.temporalGraph().node(source);
        graph.temporalGraph().node(destination);
        if (path.arcIds().isEmpty() && source != destination) {
            throw new IllegalArgumentException("empty path does not reach destination");
        }

        ExactTemporalProfile arrival = ExactTemporalProfile.identity(departureDomain);
        SicspWeightFunction weight = SicspWeightFunction.constant(departureDomain, 0.0);
        int currentNode = source;
        for (int arcId : path.arcIds()) {
            if (arcId >= graph.temporalGraph().edgeCount()) {
                throw new IllegalArgumentException("unknown arc_id " + arcId);
            }
            Edge edge = graph.temporalGraph().edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("path is disconnected at arc_id " + arcId);
            }
            SicspWeightFunction edgeWeight = SicspWeightFunction.pullBack(
                    graph.weightFunction(arcId),
                    edge.travelTimeFunction().domain(),
                    arrival.cells());
            ExactTemporalProfile nextArrival = arrival.composeAfter(ExactTemporalProfile.edgeArrival(edge));
            weight = weight.plus(edgeWeight).restrict(nextArrival.domain());
            arrival = nextArrival;
            currentNode = edge.target();
        }
        if (currentNode != destination) {
            throw new IllegalArgumentException("path does not end at destination");
        }
        return new SicspRouteProfile(path, arrival, weight);
    }
}
