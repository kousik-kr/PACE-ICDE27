package edu.ipcmax.core.pcmax;

import java.util.List;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.profile.TimeProfile;

/** Internal exact edge-profile helpers shared by PACE generation and stitching. */
final class PaceProfiles {
    private PaceProfiles() {
    }

    static Domain travelFunctionDomain(Edge edge) {
        return edge.travelTimeFunction().domain();
    }

    static Domain scoreFunctionDomain(Edge edge) {
        return edge.scoreFunction().domain();
    }

    static Domain validEntryDomain(Edge edge, Domain horizon) {
        return horizon.intersection(travelFunctionDomain(edge)).intersection(scoreFunctionDomain(edge));
    }

    static TimeProfile edgeArrivalProfile(Edge edge, Domain validDomain, String context) {
        Domain domain = validDomain.intersection(travelFunctionDomain(edge));
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("edge arrival profile has an empty domain: arc " + edge.arcId());
        }
        PiecewiseLinearFn restricted = edge.travelTimeFunction().restrict(domain);
        List<TimeProfile.Breakpoint> points = restricted.breakpoints().stream()
                .map(point -> new TimeProfile.Breakpoint(
                        point.minute(),
                        point.minute() + point.value()))
                .toList();
        return TimeProfile.piecewise(domain, points, context + ":arc=" + edge.arcId() + ":domain=" + domain.intervals());
    }

    static double minimumTravelTime(Edge edge, Domain domain) {
        Domain restricted = domain.intersection(travelFunctionDomain(edge));
        if (restricted.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        return edge.travelTimeFunction().restrict(restricted).minTravelTime();
    }
}
