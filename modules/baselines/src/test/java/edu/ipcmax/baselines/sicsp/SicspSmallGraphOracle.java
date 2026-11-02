package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Independent vertex-simple path oracle for tiny SICSP graphs. */
final class SicspSmallGraphOracle {
    record Replay(double arrival, double weight) {
    }

    record Best(double departure, double arrival, double weight, Path path) {
    }

    record Report(Best globalBest, Best retainedPathBest, List<Path> feasibleSimplePaths,
                  List<Path> missedFeasiblePaths, int candidateValueDiscrepancies,
                  int retainedProfileDiscrepancies, double nativeArrivalError,
                  boolean nativeMatchesRetainedPathOracle, boolean selectedRouteFeasible,
                  List<String> discrepancyDetails) {
    }

    private SicspSmallGraphOracle() {
    }

    static List<Path> enumerateSimplePaths(TDGraph graph, int source, int destination) {
        List<Path> result = new ArrayList<>();
        if (source == destination) {
            result.add(Path.empty());
            return List.copyOf(result);
        }
        Set<Integer> visited = new HashSet<>();
        visited.add(source);
        enumerateFrom(graph, source, destination, visited, new ArrayList<>(), result);
        return List.copyOf(result);
    }

    private static void enumerateFrom(TDGraph graph, int node, int destination,
                                      Set<Integer> visited, List<Integer> arcs, List<Path> result) {
        if (node == destination) {
            result.add(new Path(arcs));
            return;
        }
        for (Edge edge : graph.outgoingEdges(node)) {
            if (visited.contains(edge.target())) {
                continue;
            }
            visited.add(edge.target());
            arcs.add(edge.arcId());
            enumerateFrom(graph, edge.target(), destination, visited, arcs, result);
            arcs.remove(arcs.size() - 1);
            visited.remove(edge.target());
        }
    }

    /** Replays edge entry times and weights without using SICSP production helpers. */
    static Replay replay(SicspNativeGraph graph, int source, int destination,
                         double departure, Path path) {
        TDGraph temporal = graph.temporalGraph();
        double now = Domain.canonicalTime(departure);
        double weight = 0.0;
        int node = source;
        for (int arcId : path.arcIds()) {
            Edge edge = temporal.edges().get(arcId);
            if (edge.source() != node || !edge.travelTimeFunction().domain().contains(now)) {
                throw new IllegalArgumentException("oracle path is disconnected or temporally undefined");
            }
            weight += graph.weightFunction(arcId).valueAt(now);
            now = edge.travelTimeFunction().arrivalTimeAt(now);
            node = edge.target();
        }
        if (node != destination) {
            throw new IllegalArgumentException("oracle path does not reach destination");
        }
        return new Replay(Domain.canonicalTime(now), Domain.canonicalTime(weight));
    }

    static Report compare(SicspNativeGraph graph, SicspNativeQuery query, SicspNativeResult nativeResult) {
        List<Path> simplePaths = enumerateSimplePaths(
                graph.temporalGraph(), query.source(), query.destination());
        Best global = bestOverPaths(graph, query, simplePaths).best();

        Set<Path> retainedPaths = new LinkedHashSet<>();
        nativeResult.retainedRouteProfiles().forEach(profile -> retainedPaths.add(profile.path()));
        Best retained = bestOverPaths(graph, query, List.copyOf(retainedPaths)).best();
        List<Path> feasibleFromSimple = simplePaths.stream()
                .filter(path -> hasFeasibleDeparture(graph, query, path))
                .toList();
        List<Path> missed = new ArrayList<>();
        for (Path path : feasibleFromSimple) {
            if (!retainedPaths.contains(path)) {
                missed.add(path);
            }
        }

        int candidateErrors = 0;
        List<SicspBreakpointCandidate> candidates = new ArrayList<>(
                nativeResult.arrivalCandidates().generatedPoints());
        candidates.addAll(nativeResult.weightCandidates().generatedPoints());
        for (SicspBreakpointCandidate candidate : candidates) {
            Replay replay = replay(graph, query.source(), query.destination(),
                    candidate.sourceDeparture().orElseThrow(), candidate.path().orElseThrow());
            if (replay.arrival() != candidate.replayedDestinationArrival().orElseThrow()
                    || replay.weight() != candidate.accumulatedWeight().orElseThrow()) {
                candidateErrors++;
            }
        }

        int profileErrors = 0;
        List<String> discrepancyDetails = new ArrayList<>();
        for (SicspRouteProfile profile : nativeResult.retainedRouteProfiles()) {
            for (double departure : criticalDepartures(graph, query, profile.path())) {
                try {
                    Replay replay = replay(graph, query.source(), query.destination(), departure, profile.path());
                    if (replay.arrival() != profile.arrivalFunction().valueAt(departure)
                            || replay.weight() != profile.weightFunction().valueAt(departure)) {
                        profileErrors++;
                        discrepancyDetails.add("path=" + profile.path().arcIds() + " departure=" + departure
                                + " replay=" + replay + " profileArrival="
                                + profile.arrivalFunction().valueAt(departure) + " profileWeight="
                                + profile.weightFunction().valueAt(departure) + " arrivalBreakpoints="
                                + profile.arrivalFunction().cells().stream()
                                        .map(cell -> cell.breakpoints().toString()).toList());
                    }
                } catch (IllegalArgumentException unsupported) {
                    if (profile.arrivalFunction().domain().contains(departure)) {
                        profileErrors++;
                        discrepancyDetails.add("path=" + profile.path().arcIds() + " departure=" + departure
                                + " unsupported=" + unsupported.getMessage());
                    }
                }
            }
        }
        double nativeError;
        boolean matchesRetained;
        boolean selectedFeasible;
        if (nativeResult.selection().isEmpty()) {
            nativeError = global == null ? 0.0 : Double.POSITIVE_INFINITY;
            matchesRetained = retained == null;
            selectedFeasible = true;
        } else {
            var selected = nativeResult.selection().orElseThrow();
            nativeError = global == null
                    ? Double.POSITIVE_INFINITY
                    : Domain.canonicalTime(selected.destinationArrival() - global.arrival());
            selectedFeasible = selected.accumulatedWeight() <= query.weightBound();
            matchesRetained = retained != null
                    && selected.destinationArrival() == retained.arrival()
                    && selected.accumulatedWeight() == retained.weight()
                    && selected.sourceDeparture() == retained.departure()
                    && selected.path().equals(retained.path());
        }
        return new Report(global, retained, feasibleFromSimple, List.copyOf(missed),
                candidateErrors, profileErrors, nativeError, matchesRetained, selectedFeasible,
                List.copyOf(discrepancyDetails));
    }

    private record Search(Best best, boolean feasible) {
    }

    private static Search bestOverPaths(SicspNativeGraph graph, SicspNativeQuery query, List<Path> paths) {
        Best best = null;
        boolean anyFeasible = false;
        for (Path path : paths) {
            TreeSet<Double> cuts = criticalDepartures(graph, query, path);
            List<Double> ordered = List.copyOf(cuts);
            for (double departure : ordered) {
                Best candidate = evaluate(graph, query, path, departure);
                if (candidate != null) {
                    anyFeasible = true;
                    best = better(candidate, best);
                }
            }
            for (int i = 1; i < ordered.size(); i++) {
                double left = ordered.get(i - 1);
                double right = ordered.get(i);
                if (!(right > left)) {
                    continue;
                }
                double sample = left + (right - left) / 2.0;
                Best intervalSample = evaluate(graph, query, path, sample);
                if (intervalSample == null) {
                    continue;
                }
                anyFeasible = true;
                Best earliest = evaluate(graph, query, path, left);
                if (earliest == null) {
                    double nextTick = Domain.timeFromTick(Domain.canonicalTick(left) + 1);
                    if (nextTick < right) {
                        earliest = evaluate(graph, query, path, nextTick);
                    }
                }
                best = better(earliest == null ? intervalSample : earliest, best);
            }
        }
        return new Search(best, anyFeasible);
    }

    private static Best evaluate(SicspNativeGraph graph, SicspNativeQuery query, Path path, double departure) {
        if (!query.departureDomain().contains(departure)) {
            return null;
        }
        try {
            Replay replay = replay(graph, query.source(), query.destination(), departure, path);
            if (replay.weight() > query.weightBound()) {
                return null;
            }
            return new Best(Domain.canonicalTime(departure), replay.arrival(), replay.weight(), path);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static boolean hasFeasibleDeparture(SicspNativeGraph graph, SicspNativeQuery query, Path path) {
        return bestOverPaths(graph, query, List.of(path)).feasible();
    }

    /** All source-departure roots where a path edge changes travel or weight pieces. */
    static TreeSet<Double> criticalDepartures(SicspNativeGraph graph, SicspNativeQuery query, Path path) {
        TreeSet<Double> cuts = new TreeSet<>(query.departureDomain().breakpoints());
        int prefixLength = 0;
        for (int arcId : path.arcIds()) {
            Edge edge = graph.temporalGraph().edges().get(arcId);
            TreeSet<Double> events = new TreeSet<>();
            edge.travelTimeFunction().breakpoints().forEach(point -> events.add(point.minute()));
            events.addAll(graph.weightFunction(arcId).breakpoints());
            List<Double> before = List.copyOf(cuts);
            for (int i = 1; i < before.size(); i++) {
                double start = before.get(i - 1);
                double end = before.get(i);
                if (!(end > start)) {
                    continue;
                }
                double entryStart;
                double entryEnd;
                try {
                    entryStart = arrivalAfterPrefix(graph, query.source(), query.departureDomain(),
                            path, prefixLength, start);
                    entryEnd = arrivalAfterPrefix(graph, query.source(), query.departureDomain(),
                            path, prefixLength, end);
                } catch (IllegalArgumentException unsupported) {
                    continue;
                }
                for (double event : events) {
                    if (event <= Math.min(entryStart, entryEnd) || event >= Math.max(entryStart, entryEnd)) {
                        continue;
                    }
                    double alpha = (event - entryStart) / (entryEnd - entryStart);
                    double root = Domain.canonicalTime(start + alpha * (end - start));
                    if (root > start && root < end) {
                        cuts.add(root);
                    }
                }
            }
            prefixLength++;
        }
        return cuts;
    }

    private static double arrivalAfterPrefix(SicspNativeGraph graph, int source, Domain departures,
                                             Path path, int prefixLength, double departure) {
        if (!departures.contains(departure)) {
            throw new IllegalArgumentException("departure outside domain");
        }
        double now = departure;
        int node = source;
        for (int i = 0; i < prefixLength; i++) {
            Edge edge = graph.temporalGraph().edges().get(path.arcIds().get(i));
            if (edge.source() != node || !edge.travelTimeFunction().domain().contains(now)) {
                throw new IllegalArgumentException("undefined prefix");
            }
            now = edge.travelTimeFunction().arrivalTimeAt(now);
            node = edge.target();
        }
        return now;
    }

    private static Best better(Best candidate, Best current) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        int comparison = Double.compare(candidate.arrival(), current.arrival());
        if (comparison == 0) {
            comparison = Double.compare(candidate.weight(), current.weight());
        }
        if (comparison == 0) {
            comparison = Double.compare(candidate.departure(), current.departure());
        }
        if (comparison == 0) {
            comparison = compareArcIds(candidate.path(), current.path());
        }
        return comparison < 0 ? candidate : current;
    }

    private static int compareArcIds(Path left, Path right) {
        int common = Math.min(left.arcIds().size(), right.arcIds().size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.arcIds().get(i), right.arcIds().get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.arcIds().size(), right.arcIds().size());
    }
}
