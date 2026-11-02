package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;

import java.math.BigInteger;
import java.util.Map;
import java.util.WeakHashMap;

/** Graph-level, query-independent speed bound for geometric iSCOPE Opt1. */
final class GraphGeometricTravelBounds {
    private static final Map<TDGraph, Lookup> CACHE = new WeakHashMap<>();

    record Lookup(
            Prepared prepared,
            boolean cacheHit,
            long preparationNanos,
            long heapUsedBeforeBytes,
            long heapUsedAfterBytes,
            long sampledPeakHeapUsedBytes) { }

    record Prepared(
            ExactTime maximumSpeed,
            String fallbackReason,
            long edgesInspected,
            long travelTimeBreakpointsInspected,
            long positiveCoordinateLengthEdges,
            long zeroDurationEdges) {
        boolean enabled() {
            return fallbackReason == null && maximumSpeed.signum() > 0;
        }

        /**
         * A downward-rounded Euclidean distance divided by an exact upward
         * speed bound. BigInteger.sqrt() floors the distance, so this value
         * cannot exceed the real geometric lower bound.
         */
        ExactTime remainingTimeLowerBound(TDGraph graph, int vertex, int target) {
            if (!enabled()) return ExactTime.ZERO;
            try {
                Node from = graph.node(vertex);
                Node to = graph.node(target);
                if (!from.coordinatesAvailable() || !to.coordinatesAvailable()) {
                    return ExactTime.ZERO;
                }
                BigInteger dx = BigInteger.valueOf(from.x()).subtract(BigInteger.valueOf(to.x()));
                BigInteger dy = BigInteger.valueOf(from.y()).subtract(BigInteger.valueOf(to.y()));
                BigInteger floorDistance = dx.multiply(dx).add(dy.multiply(dy)).sqrt();
                return ExactTime.of(floorDistance, BigInteger.ONE).divide(maximumSpeed);
            } catch (IllegalArgumentException | ArithmeticException invalidCoordinate) {
                // A missing coordinate or unrepresentable query endpoint must
                // weaken the bound to zero, never remove a feasible departure.
                return ExactTime.ZERO;
            }
        }
    }

    private GraphGeometricTravelBounds() { }

    /** Computes the constant once for an immutable graph/temporal identity. */
    static Lookup forGraph(TDGraph graph) {
        synchronized (CACHE) {
            long started = System.nanoTime();
            long heapBefore = usedHeapBytes();
            Lookup cached = CACHE.get(graph);
            if (cached != null) {
                return new Lookup(cached.prepared(), true, cached.preparationNanos(),
                        cached.heapUsedBeforeBytes(), cached.heapUsedAfterBytes(),
                        cached.sampledPeakHeapUsedBytes());
            }
            Preparation preparation = compute(graph, heapBefore);
            Prepared prepared = preparation.prepared();
            long elapsed = System.nanoTime() - started;
            long heapAfter = usedHeapBytes();
            Lookup lookup = new Lookup(prepared, false, elapsed, heapBefore, heapAfter,
                    Math.max(preparation.sampledPeakHeapUsedBytes(), heapAfter));
            CACHE.put(graph, lookup);
            return lookup;
        }
    }

    private record Preparation(Prepared prepared, long sampledPeakHeapUsedBytes) { }

    private static Preparation compute(TDGraph graph, long heapBefore) {
        long peakHeap = heapBefore;
        for (int nodeId : graph.nodeIds()) {
            Node node;
            try {
                node = graph.node(nodeId);
            } catch (IllegalArgumentException missingNode) {
                return fallback("missing_node_coordinates", 0, 0, 0, 0, peakHeap);
            }
            if (!node.coordinatesAvailable()) {
                return fallback("missing_node_coordinates", 0, 0, 0, 0, peakHeap);
            }
        }

        ExactTime maximumSpeed = ExactTime.ZERO;
        long edgesInspected = 0;
        long breakpointsInspected = 0;
        long positiveLengthEdges = 0;
        long zeroDurationEdges = 0;
        try {
            for (Edge edge : graph.edges()) {
                edgesInspected++;
                Node from = graph.node(edge.source());
                Node to = graph.node(edge.target());
                if (!from.coordinatesAvailable() || !to.coordinatesAvailable()) {
                    return fallback("missing_edge_endpoint_coordinates", edgesInspected,
                            breakpointsInspected, positiveLengthEdges, zeroDurationEdges, peakHeap);
                }

                BigInteger dx = BigInteger.valueOf(from.x()).subtract(BigInteger.valueOf(to.x())).abs();
                BigInteger dy = BigInteger.valueOf(from.y()).subtract(BigInteger.valueOf(to.y())).abs();
                BigInteger coordinateDistanceUpper = dx.add(dy); // L1 >= Euclidean.
                if (coordinateDistanceUpper.signum() > 0) positiveLengthEdges++;

                Double minimumTravel = null;
                for (var breakpoint : edge.travelTimeFunction().breakpoints()) {
                    breakpointsInspected++;
                    double value = breakpoint.value();
                    if (!Double.isFinite(value) || value < 0) {
                        return fallback("invalid_travel_time", edgesInspected,
                                breakpointsInspected, positiveLengthEdges, zeroDurationEdges, peakHeap);
                    }
                    if (minimumTravel == null || value < minimumTravel) minimumTravel = value;
                }
                if (minimumTravel == null) {
                    return fallback("missing_travel_time_support", edgesInspected,
                            breakpointsInspected, positiveLengthEdges, zeroDurationEdges, peakHeap);
                }

                ExactTime minimumDuration = ExactTime.fromCanonicalDouble(minimumTravel);
                if (minimumDuration.signum() == 0) {
                    zeroDurationEdges++;
                    if (coordinateDistanceUpper.signum() > 0) {
                        return fallback("positive_coordinate_length_zero_duration_edge", edgesInspected,
                                breakpointsInspected, positiveLengthEdges, zeroDurationEdges, peakHeap);
                    }
                } else if (coordinateDistanceUpper.signum() > 0) {
                    ExactTime edgeSpeedUpper = ExactTime.of(coordinateDistanceUpper, BigInteger.ONE)
                            .divide(minimumDuration);
                    if (edgeSpeedUpper.compareTo(maximumSpeed) > 0) maximumSpeed = edgeSpeedUpper;
                }

                if ((edgesInspected & 0xffffL) == 0) peakHeap = Math.max(peakHeap, usedHeapBytes());
            }
        } catch (ArithmeticException | IllegalArgumentException invalidGraphMetric) {
            return fallback("invalid_coordinate_or_travel_time", edgesInspected,
                    breakpointsInspected, positiveLengthEdges, zeroDurationEdges, peakHeap);
        }

        if (maximumSpeed.signum() <= 0) {
            return fallback("no_finite_positive_speed_bound", edgesInspected,
                    breakpointsInspected, positiveLengthEdges, zeroDurationEdges, peakHeap);
        }
        return new Preparation(new Prepared(maximumSpeed, null, edgesInspected,
                breakpointsInspected, positiveLengthEdges, zeroDurationEdges), peakHeap);
    }

    private static Preparation fallback(String reason, long edges, long breakpoints,
            long positiveLengthEdges, long zeroDurationEdges, long peakHeap) {
        return new Preparation(new Prepared(ExactTime.ZERO, reason, edges, breakpoints,
                positiveLengthEdges, zeroDurationEdges), peakHeap);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
