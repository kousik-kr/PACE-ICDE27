package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exact edge-arrival and path-arrival profile composition for baseline code. */
public final class TemporalArrivalProfiles {
    private TemporalArrivalProfiles() {
    }

    /**
     * Converts an edge travel-time function to its arrival-time profile
     * {@code A(t)=t+travelTime(t)} without losing any temporal breakpoints.
     */
    public static TimeProfile edgeArrivalProfile(Edge edge) {
        if (edge == null) {
            throw new IllegalArgumentException("edge is required");
        }
        List<TimeProfile.Breakpoint> points = new ArrayList<>();
        for (var breakpoint : edge.travelTimeFunction().breakpoints()) {
            points.add(new TimeProfile.Breakpoint(
                    breakpoint.minute(),
                    edge.travelTimeFunction().arrivalTimeAtClosure(breakpoint.minute())));
        }
        return TimeProfile.piecewise(
                edge.travelTimeFunction().domain(),
                points,
                "edge-arrival:" + edge.arcId());
    }

    /**
     * Composes a complete edge-ID path into an exact absolute-arrival profile.
     * The returned domain is the subset of requested departures for which all
     * edge functions are defined at their actual entry times.
     */
    public static Optional<TimeProfile> pathArrivalProfile(
            TDGraph graph,
            int source,
            int destination,
            Domain departureDomain,
            Path path) {
        if (graph == null || departureDomain == null || departureDomain.isEmpty() || path == null) {
            throw new IllegalArgumentException("graph, nonempty departure domain, and path are required");
        }
        graph.node(source);
        graph.node(destination);
        if (path.arcIds().isEmpty() && source != destination) {
            throw new IllegalArgumentException("empty path does not reach destination");
        }

        TimeProfile current = TimeProfile.identity(departureDomain);
        int currentNode = source;
        for (int arcId : path.arcIds()) {
            if (arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("unknown arc id " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != currentNode) {
                throw new IllegalArgumentException("path is disconnected at arc " + arcId);
            }
            current = current.composeOrNull(
                    edgeArrivalProfile(edge),
                    "path-arrival:" + path.arcIds(),
                    () -> false);
            if (current == null) {
                return Optional.empty();
            }
            currentNode = edge.target();
        }
        if (currentNode != destination) {
            throw new IllegalArgumentException("path does not end at destination");
        }
        return Optional.of(current);
    }
}
