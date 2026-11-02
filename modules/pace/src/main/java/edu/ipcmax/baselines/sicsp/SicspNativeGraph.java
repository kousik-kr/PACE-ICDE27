package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** The temporal PACE graph plus a separate native SICSP edge-weight model. */
public final class SicspNativeGraph {
    private final TDGraph temporalGraph;
    private final Map<Integer, SicspEdgeWeightFn> weightFunctions;

    public SicspNativeGraph(TDGraph temporalGraph, Map<Integer, SicspEdgeWeightFn> weightFunctions) {
        this.temporalGraph = Objects.requireNonNull(temporalGraph, "temporalGraph");
        Objects.requireNonNull(weightFunctions, "weightFunctions");
        Map<Integer, SicspEdgeWeightFn> copied = new HashMap<>();
        for (Edge edge : temporalGraph.edges()) {
            SicspEdgeWeightFn weight = weightFunctions.get(edge.arcId());
            if (weight == null) {
                throw new IllegalArgumentException("missing SICSP weight function for arc_id " + edge.arcId());
            }
            Domain undefinedWeightTimes = edge.travelTimeFunction().domain().difference(weight.domain());
            if (!undefinedWeightTimes.isEmpty()) {
                throw new IllegalArgumentException(
                        "weight function does not cover travel-time domain on arc_id " + edge.arcId());
            }
            copied.put(edge.arcId(), weight);
        }
        for (int arcId : weightFunctions.keySet()) {
            if (arcId < 0 || arcId >= temporalGraph.edgeCount()) {
                throw new IllegalArgumentException("weight function supplied for unknown arc_id " + arcId);
            }
        }
        this.weightFunctions = Map.copyOf(copied);
    }

    public TDGraph temporalGraph() {
        return temporalGraph;
    }

    public SicspEdgeWeightFn weightFunction(int arcId) {
        SicspEdgeWeightFn result = weightFunctions.get(arcId);
        if (result == null) {
            throw new IllegalArgumentException("unknown arc_id " + arcId);
        }
        return result;
    }

    public Map<Integer, SicspEdgeWeightFn> weightFunctions() {
        return weightFunctions;
    }
}
