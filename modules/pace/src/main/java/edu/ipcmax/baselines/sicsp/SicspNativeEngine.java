package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;

/** Native sequential SICSP (original ICSP_Arr plus a documented ICSP_Wgh inference). */
public final class SicspNativeEngine {
    private record CandidateKey(
            SicspBreakpointCandidate.Type type,
            int arcId,
            double eventTime,
            double departure,
            Path path) {
    }

    private static final class Counters {
        long edgesConsidered;
        long timeBreakpointsExamined;
        long weightBreakpointsExamined;
        long reverseDijkstraCalls;
        long forwardDijkstraCalls;
        long oPointsGenerated;
        long wPointsGenerated;
        long candidateRoutesGenerated;
        long duplicateCandidates;
        long dominanceComparisons;
        long dominatedPointsRemoved;
        long temporalFunctionPieces;
        long feasibleDepartureRegions;
        long candidateGenerationNanos;
        long dominanceNanos;
        long profileConstructionNanos;
        long selectionNanos;
        long queryStartNanos;
        long peakSampledHeapBytes;

        void sampleHeap() {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            peakSampledHeapBytes = Math.max(peakSampledHeapBytes, used);
        }
    }

    private final SicspNativeGraph nativeGraph;
    private final TDGraph graph;
    private final SicspTemporalDijkstra dijkstra;
    private final IntFunction<List<Double>> weightBreakpointProvider;

    public SicspNativeEngine(SicspNativeGraph nativeGraph) {
        this(nativeGraph, arcId -> nativeGraph.weightFunction(arcId).breakpoints());
    }

    /**
     * Builds the unchanged SICSP candidate generator with a caller-supplied
     * secondary-attribute event source.  The native constructor continues to
     * enumerate the native edge-weight breakpoints.
     */
    SicspNativeEngine(
            SicspNativeGraph nativeGraph,
            IntFunction<List<Double>> weightBreakpointProvider) {
        this.nativeGraph = Objects.requireNonNull(nativeGraph, "nativeGraph");
        this.weightBreakpointProvider = Objects.requireNonNull(
                weightBreakpointProvider, "weightBreakpointProvider");
        this.graph = nativeGraph.temporalGraph();
        validateFifoClosedGraph(graph);
        this.dijkstra = new SicspTemporalDijkstra(graph);
    }

    /** Builds interval-wide candidate profiles, then applies native ArrTime. */
    public SicspNativeResult query(SicspNativeQuery query) {
        Objects.requireNonNull(query, "query");
        graph.node(query.source());
        graph.node(query.destination());
        Counters counters = new Counters();
        counters.queryStartNanos = System.nanoTime();
        counters.sampleHeap();

        SicspCandidateGenerationResult generation = generateCandidates(query);
        counters.edgesConsidered = generation.edgesConsidered();
        counters.timeBreakpointsExamined = generation.timeBreakpointsExamined();
        counters.weightBreakpointsExamined = generation.weightBreakpointsExamined();
        counters.reverseDijkstraCalls = generation.reverseDijkstraCalls();
        counters.forwardDijkstraCalls = generation.forwardDijkstraCalls();
        counters.duplicateCandidates = generation.duplicateCandidates();
        counters.candidateGenerationNanos = generation.candidateGenerationNanos();
        counters.peakSampledHeapBytes = generation.peakSampledHeapBytes();
        List<SicspBreakpointCandidate> generatedO = generation.arrivalPoints();
        List<SicspBreakpointCandidate> diagnosticO = generation.arrivalDiagnostics();
        List<SicspBreakpointCandidate> generatedW = generation.weightPoints();
        List<SicspBreakpointCandidate> diagnosticW = generation.weightDiagnostics();
        counters.sampleHeap();

        long phaseStart;

        phaseStart = System.nanoTime();
        List<SicspBreakpointCandidate> retainedO = filterArrivalCandidates(
                generatedO, query.source(), query.destination(), counters);
        List<SicspBreakpointCandidate> dominatedO = subtractByIdentity(generatedO, retainedO);
        List<SicspBreakpointCandidate> retainedW = filterWeightCandidates(generatedW, counters);
        List<SicspBreakpointCandidate> dominatedW = subtractByIdentity(generatedW, retainedW);
        counters.oPointsGenerated = generatedO.size();
        counters.wPointsGenerated = generatedW.size();
        counters.dominatedPointsRemoved = dominatedO.size() + dominatedW.size();
        counters.dominanceNanos = System.nanoTime() - phaseStart;
        counters.sampleHeap();

        phaseStart = System.nanoTime();
        LinkedHashSet<Path> allCandidatePaths = new LinkedHashSet<>();
        for (SicspBreakpointCandidate candidate : generatedO) {
            if (candidate.isProfileRouteCandidate()) {
                candidate.path().ifPresent(allCandidatePaths::add);
            }
        }
        for (SicspBreakpointCandidate candidate : generatedW) {
            if (candidate.isProfileRouteCandidate()) {
                candidate.path().ifPresent(allCandidatePaths::add);
            }
        }
        for (SicspBreakpointCandidate candidate : diagnosticO) {
            if (candidate.isProfileRouteCandidate()) {
                candidate.path().ifPresent(allCandidatePaths::add);
            }
        }
        for (SicspBreakpointCandidate candidate : diagnosticW) {
            if (candidate.isProfileRouteCandidate()) {
                candidate.path().ifPresent(allCandidatePaths::add);
            }
        }
        if (query.source() == query.destination()) {
            allCandidatePaths.add(Path.empty());
        }
        counters.candidateRoutesGenerated = allCandidatePaths.size();

        Map<Path, SicspRouteProfile> allProfilesByPath = new LinkedHashMap<>();
        for (Path path : allCandidatePaths) {
            SicspRouteProfile profile = SicspRouteProfile.build(
                    nativeGraph, query.source(), query.destination(), query.departureDomain(), path);
            allProfilesByPath.put(path, profile);
            counters.temporalFunctionPieces += profile.arrivalFunction().pieceCount()
                    + profile.weightFunction().pieceCount();
            counters.sampleHeap();
        }

        LinkedHashSet<Path> retainedPaths = new LinkedHashSet<>();
        retainedO.forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        retainedW.forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        diagnosticO.stream().filter(candidate -> candidate.status()
                        == SicspBreakpointCandidate.Status.SOURCE_DEPARTURE_OUTSIDE_QUERY
                        && candidate.isProfileRouteCandidate())
                .forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        diagnosticW.stream().filter(candidate -> candidate.status()
                        == SicspBreakpointCandidate.Status.SOURCE_DEPARTURE_OUTSIDE_QUERY
                        && candidate.isProfileRouteCandidate())
                .forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        if (query.source() == query.destination()) {
            retainedPaths.add(Path.empty());
        }
        List<SicspRouteProfile> allProfiles = List.copyOf(allProfilesByPath.values());
        List<SicspRouteProfile> retainedProfiles = retainedPaths.stream()
                .map(allProfilesByPath::get)
                .filter(Objects::nonNull)
                .toList();
        List<SicspWeightCandidates.WPointPiece> wPointPieces =
                SicspWeightCandidates.constructWeightPieces(retainedW);
        SicspArrivalFunction arrivalFunction = SicspArrivalFunction.construct(retainedProfiles, retainedO);
        counters.temporalFunctionPieces += wPointPieces.size()
                + arrivalFunction.oPointPieces().size()
                + arrivalFunction.iPoints().size();
        List<SicspProfileDiscrepancy> discrepancies = new ArrayList<>(compareProfilesToCandidates(
                allProfilesByPath, generatedO, generatedW));
        discrepancies.addAll(compareOPointInterpolants(arrivalFunction, retainedProfiles));
        counters.profileConstructionNanos = System.nanoTime() - phaseStart;
        counters.sampleHeap();

        phaseStart = System.nanoTime();
        SicspArrivalFunction.SelectionEvaluation evaluation = arrivalFunction.selectBestFeasible(query);
        counters.feasibleDepartureRegions = evaluation.feasibleDepartureRegions();
        counters.selectionNanos = System.nanoTime() - phaseStart;
        counters.sampleHeap();

        Optional<SicspNativeResult.Selection> selection = evaluation.selection().map(value ->
                new SicspNativeResult.Selection(
                        value.sourceDeparture(), value.destinationArrival(),
                        value.accumulatedWeight(), value.path()));
        long totalNanos = System.nanoTime() - counters.queryStartNanos;
        SicspNativeStatistics statistics = new SicspNativeStatistics(
                counters.edgesConsidered,
                counters.timeBreakpointsExamined,
                counters.weightBreakpointsExamined,
                counters.reverseDijkstraCalls,
                counters.forwardDijkstraCalls,
                counters.oPointsGenerated,
                counters.wPointsGenerated,
                counters.candidateRoutesGenerated,
                counters.duplicateCandidates,
                counters.dominanceComparisons,
                counters.dominatedPointsRemoved,
                counters.temporalFunctionPieces,
                counters.feasibleDepartureRegions,
                counters.candidateGenerationNanos,
                counters.dominanceNanos,
                counters.profileConstructionNanos,
                counters.selectionNanos,
                totalNanos,
                counters.peakSampledHeapBytes);
        return new SicspNativeResult(
                selection,
                new SicspArrivalCandidates(generatedO, retainedO, dominatedO, diagnosticO),
                new SicspWeightCandidates(generatedW, retainedW, dominatedW, diagnosticW, wPointPieces),
                arrivalFunction,
                allProfiles,
                retainedProfiles,
                discrepancies,
                statistics);
    }

    /**
     * Runs only the paper's per-breakpoint LST/EAT candidate generation.
     * Native SICSP and SICSP-Pref call this same implementation; only the
     * secondary-attribute event provider differs for the preference adapter.
     */
    public SicspCandidateGenerationResult generateCandidates(SicspNativeQuery query) {
        Objects.requireNonNull(query, "query");
        graph.node(query.source());
        graph.node(query.destination());
        Counters counters = new Counters();
        long started = System.nanoTime();
        counters.sampleHeap();
        List<SicspBreakpointCandidate> generatedO = new ArrayList<>();
        List<SicspBreakpointCandidate> diagnosticO = new ArrayList<>();
        List<SicspBreakpointCandidate> generatedW = new ArrayList<>();
        List<SicspBreakpointCandidate> diagnosticW = new ArrayList<>();
        Set<CandidateKey> seenO = new HashSet<>();
        Set<CandidateKey> seenW = new HashSet<>();

        for (Edge edge : graph.edges()) {
            counters.edgesConsidered++;
            TreeSet<Double> timeEvents = new TreeSet<>();
            edge.travelTimeFunction().breakpoints().forEach(point -> timeEvents.add(point.minute()));
            for (double eventTime : timeEvents) {
                counters.timeBreakpointsExamined++;
                SicspBreakpointCandidate attempt = generateBreakpointCandidate(
                        query, edge, eventTime,
                        SicspBreakpointCandidate.Type.ARRIVAL_BREAKPOINT, counters);
                addUniqueCandidate(attempt, generatedO, diagnosticO, seenO, counters);
            }

            TreeSet<Double> weightEvents = new TreeSet<>(weightBreakpointProvider.apply(edge.arcId()));
            for (double eventTime : weightEvents) {
                counters.weightBreakpointsExamined++;
                SicspBreakpointCandidate attempt = generateBreakpointCandidate(
                        query, edge, eventTime,
                        SicspBreakpointCandidate.Type.WEIGHT_BREAKPOINT, counters);
                addUniqueCandidate(attempt, generatedW, diagnosticW, seenW, counters);
            }
            counters.sampleHeap();
        }
        counters.candidateGenerationNanos = System.nanoTime() - started;
        counters.sampleHeap();
        return new SicspCandidateGenerationResult(
                generatedO,
                diagnosticO,
                generatedW,
                diagnosticW,
                counters.edgesConsidered,
                counters.timeBreakpointsExamined,
                counters.weightBreakpointsExamined,
                counters.reverseDijkstraCalls,
                counters.forwardDijkstraCalls,
                counters.duplicateCandidates,
                counters.candidateGenerationNanos,
                counters.peakSampledHeapBytes);
    }

    private SicspBreakpointCandidate generateBreakpointCandidate(
            SicspNativeQuery query,
            Edge eventEdge,
            double eventTime,
            SicspBreakpointCandidate.Type type,
            Counters counters) {
        if (!eventEdge.travelTimeFunction().domain().contains(eventTime)) {
            return candidate(type, eventEdge.arcId(), eventTime,
                    none(), none(), none(), none(), none(), Optional.empty(),
                    SicspBreakpointCandidate.Status.EVENT_OUTSIDE_EDGE_DOMAIN,
                    "event breakpoint is outside the travel-time function domain");
        }

        counters.reverseDijkstraCalls++;
        SicspTemporalDijkstra.SearchOutcome prefixSearch =
                dijkstra.latestDeparture(query.source(), eventEdge.source(), eventTime);
        counters.edgesConsidered += prefixSearch.edgesConsidered();
        if (prefixSearch.result().isEmpty()) {
            return candidate(type, eventEdge.arcId(), eventTime,
                    none(), none(), none(), none(), none(), Optional.empty(),
                    SicspBreakpointCandidate.Status.NO_PREFIX,
                    "reverse time-dependent Dijkstra found no source prefix");
        }
        SicspTemporalDijkstra.EventPath prefix = prefixSearch.result().orElseThrow();
        double edgeArrival = eventEdge.travelTimeFunction().arrivalTimeAt(eventTime);
        counters.forwardDijkstraCalls++;
        SicspTemporalDijkstra.SearchOutcome suffixSearch =
                dijkstra.earliestArrival(eventEdge.target(), query.destination(), edgeArrival);
        counters.edgesConsidered += suffixSearch.edgesConsidered();
        if (suffixSearch.result().isEmpty()) {
            return candidate(type, eventEdge.arcId(), eventTime,
                    OptionalDouble.of(prefix.time()), none(), none(), none(), none(), Optional.empty(),
                    SicspBreakpointCandidate.Status.NO_SUFFIX,
                    "forward time-dependent Dijkstra found no destination suffix");
        }
        SicspTemporalDijkstra.EventPath suffix = suffixSearch.result().orElseThrow();
        Path path = concatenate(prefix.path(), eventEdge.arcId(), suffix.path());

        OptionalDouble prefixArrival = none();
        OptionalDouble replayedArrival = none();
        OptionalDouble accumulatedWeight = none();
        List<String> issues = new ArrayList<>();
        if (!query.departureDomain().contains(prefix.time())) {
            issues.add("latest source departure lies outside requested interval");
        }
        try {
            SicspRouteReplay.Result prefixReplay = SicspRouteReplay.replay(
                    nativeGraph, query.source(), eventEdge.source(), prefix.time(), prefix.path());
            prefixArrival = OptionalDouble.of(prefixReplay.arrivalTime());
            if (prefixArrival.getAsDouble() != Domain.canonicalTime(eventTime)) {
                issues.add("prefix reaches edge source at " + prefixArrival.getAsDouble()
                        + " instead of event time " + eventTime);
            }
        } catch (IllegalArgumentException invalidPrefix) {
            issues.add("prefix replay failed: " + invalidPrefix.getMessage());
        }
        try {
            SicspRouteReplay.Result replay = SicspRouteReplay.replay(
                    nativeGraph, query.source(), query.destination(), prefix.time(), path);
            replayedArrival = OptionalDouble.of(replay.arrivalTime());
            accumulatedWeight = OptionalDouble.of(replay.accumulatedWeight());
            if (replay.arrivalTime() != Domain.canonicalTime(suffix.time())) {
                issues.add("replayed arrival differs from forward Dijkstra EAT " + suffix.time());
            }
        } catch (IllegalArgumentException invalidPath) {
            issues.add("complete path replay failed: " + invalidPath.getMessage());
        }
        SicspBreakpointCandidate.Status status = issues.isEmpty()
                ? SicspBreakpointCandidate.Status.RETAINED
                : classifyIssue(issues);
        String detail = String.join("; ", issues);
        if (containsRepeatedNode(query.source(), path)) {
            detail = detail.isEmpty() ? "concatenated witness repeats a vertex"
                    : detail + "; concatenated witness repeats a vertex (kept; no simple-path filter applied)";
        }
        return candidate(type, eventEdge.arcId(), eventTime,
                OptionalDouble.of(prefix.time()), prefixArrival,
                OptionalDouble.of(suffix.time()), replayedArrival,
                accumulatedWeight, Optional.of(path), status, detail);
    }

    private static SicspBreakpointCandidate.Status classifyIssue(List<String> issues) {
        if (issues.stream().anyMatch(issue -> issue.startsWith("prefix reaches"))) {
            return SicspBreakpointCandidate.Status.PREFIX_EVENT_MISMATCH;
        }
        if (issues.stream().anyMatch(issue -> issue.startsWith("prefix replay")
                || issue.startsWith("complete path replay"))) {
            return SicspBreakpointCandidate.Status.PATH_REPLAY_FAILED;
        }
        if (issues.stream().anyMatch(issue -> issue.startsWith("replayed arrival"))) {
            return SicspBreakpointCandidate.Status.FORWARD_ARRIVAL_MISMATCH;
        }
        if (issues.stream().anyMatch(issue -> issue.startsWith("latest source departure"))) {
            return SicspBreakpointCandidate.Status.SOURCE_DEPARTURE_OUTSIDE_QUERY;
        }
        return SicspBreakpointCandidate.Status.PATH_REPLAY_FAILED;
    }

    private static SicspBreakpointCandidate candidate(
            SicspBreakpointCandidate.Type type,
            int arcId,
            double eventTime,
            OptionalDouble departure,
            OptionalDouble prefixArrival,
            OptionalDouble intendedArrival,
            OptionalDouble replayedArrival,
            OptionalDouble weight,
            Optional<Path> path,
            SicspBreakpointCandidate.Status status,
            String detail) {
        return new SicspBreakpointCandidate(type, arcId, eventTime, departure,
                prefixArrival, intendedArrival, replayedArrival, weight, path, status, detail);
    }

    private static OptionalDouble none() {
        return OptionalDouble.empty();
    }

    private static Path concatenate(Path prefix, int eventArcId, Path suffix) {
        List<Integer> arcs = new ArrayList<>(prefix.arcIds().size() + suffix.arcIds().size() + 1);
        arcs.addAll(prefix.arcIds());
        arcs.add(eventArcId);
        arcs.addAll(suffix.arcIds());
        return new Path(arcs);
    }

    private static void addUniqueCandidate(
            SicspBreakpointCandidate attempt,
            List<SicspBreakpointCandidate> generated,
            List<SicspBreakpointCandidate> diagnostics,
            Set<CandidateKey> seen,
            Counters counters) {
        if (attempt.status() != SicspBreakpointCandidate.Status.RETAINED) {
            diagnostics.add(attempt);
            return;
        }
        CandidateKey key = new CandidateKey(
                attempt.type(), attempt.eventArcId(), attempt.eventTime(),
                attempt.sourceDeparture().orElseThrow(), attempt.path().orElseThrow());
        if (seen.add(key)) {
            generated.add(attempt);
        } else {
            counters.duplicateCandidates++;
            diagnostics.add(candidate(attempt.type(), attempt.eventArcId(), attempt.eventTime(),
                    attempt.sourceDeparture(), attempt.actualPrefixArrival(),
                    attempt.intendedDestinationArrival(), attempt.replayedDestinationArrival(),
                    attempt.accumulatedWeight(), attempt.path(),
                    SicspBreakpointCandidate.Status.DUPLICATE_CANDIDATE,
                    "duplicate event candidate"));
        }
    }

    private List<SicspBreakpointCandidate> filterArrivalCandidates(
            List<SicspBreakpointCandidate> candidates,
            int source,
            int destination,
            Counters counters) {
        Map<Path, List<SicspBreakpointCandidate>> groups = SicspCandidateGroups.byPath(candidates);
        List<SicspBreakpointCandidate> retained = new ArrayList<>();
        for (SicspBreakpointCandidate candidate : candidates) {
            boolean dominated = false;
            double departure = candidate.sourceDeparture().orElseThrow();
            double arrival = candidate.replayedDestinationArrival().orElseThrow();
            double weight = candidate.accumulatedWeight().orElseThrow();
            for (Map.Entry<Path, List<SicspBreakpointCandidate>> group : groups.entrySet()) {
                if (group.getKey().equals(candidate.path().orElseThrow())) {
                    continue;
                }
                List<SicspBreakpointCandidate> nearby = new ArrayList<>(group.getValue());
                nearby.sort(Comparator.comparingDouble(point -> Math.abs(
                        point.sourceDeparture().orElseThrow() - departure)));
                if (nearby.isEmpty()) {
                    continue;
                }
                counters.dominanceComparisons++;
                try {
                    SicspRouteReplay.Result other = SicspRouteReplay.replay(
                            nativeGraph, source, destination,
                            departure, group.getKey());
                    if (SicspDominance.strictlyDominates(
                            other.arrivalTime(), other.accumulatedWeight(), arrival, weight)) {
                        dominated = true;
                        break;
                    }
                } catch (IllegalArgumentException unavailableAtDeparture) {
                    // A path with no valid no-wait replay at this departure cannot dominate the point.
                }
            }
            if (!dominated) {
                retained.add(candidate);
            }
        }
        retained.sort(candidateOrder());
        return List.copyOf(retained);
    }

    private List<SicspBreakpointCandidate> filterWeightCandidates(
            List<SicspBreakpointCandidate> candidates,
            Counters counters) {
        List<SicspBreakpointCandidate> retained = new ArrayList<>();
        for (SicspBreakpointCandidate candidate : candidates) {
            double departure = candidate.sourceDeparture().orElseThrow();
            double arrival = candidate.replayedDestinationArrival().orElseThrow();
            double weight = candidate.accumulatedWeight().orElseThrow();
            boolean dominated = false;
            for (SicspBreakpointCandidate other : candidates) {
                if (other == candidate
                        || other.sourceDeparture().orElseThrow() != departure) {
                    continue;
                }
                counters.dominanceComparisons++;
                if (SicspDominance.skylineDominates(
                        other.replayedDestinationArrival().orElseThrow(),
                        other.accumulatedWeight().orElseThrow(), arrival, weight)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                retained.add(candidate);
            }
        }
        retained.sort(candidateOrder());
        return List.copyOf(retained);
    }

    private static Comparator<SicspBreakpointCandidate> candidateOrder() {
        return Comparator.comparingDouble((SicspBreakpointCandidate candidate) ->
                        candidate.sourceDeparture().orElseThrow())
                .thenComparing(candidate -> candidate.path().orElseThrow(), SicspPathOrder::compare)
                .thenComparingDouble(candidate -> candidate.eventTime());
    }

    private static List<SicspBreakpointCandidate> subtractByIdentity(
            List<SicspBreakpointCandidate> all,
            List<SicspBreakpointCandidate> retained) {
        Set<SicspBreakpointCandidate> retainedSet = new HashSet<>(retained);
        return all.stream().filter(candidate -> !retainedSet.contains(candidate)).toList();
    }

    private static List<SicspProfileDiscrepancy> compareProfilesToCandidates(
            Map<Path, SicspRouteProfile> profiles,
            List<SicspBreakpointCandidate> arrivalPoints,
            List<SicspBreakpointCandidate> weightPoints) {
        List<SicspProfileDiscrepancy> result = new ArrayList<>();
        List<SicspBreakpointCandidate> candidates = new ArrayList<>(arrivalPoints);
        candidates.addAll(weightPoints);
        for (SicspBreakpointCandidate candidate : candidates) {
            Path path = candidate.path().orElseThrow();
            SicspRouteProfile profile = profiles.get(path);
            double departure = candidate.sourceDeparture().orElseThrow();
            double replayedArrival = candidate.replayedDestinationArrival().orElseThrow();
            double replayedWeight = candidate.accumulatedWeight().orElseThrow();
            try {
                double profileArrival = profile.arrivalFunction().valueAt(departure);
                double profileWeight = profile.weightFunction().valueAt(departure);
                if (profileArrival != replayedArrival || profileWeight != replayedWeight) {
                    result.add(new SicspProfileDiscrepancy(path, departure, replayedArrival,
                            profileArrival, replayedWeight, profileWeight,
                            "exact profile composition differs from independent event replay"));
                }
            } catch (IllegalArgumentException unsupported) {
                result.add(new SicspProfileDiscrepancy(path, departure, replayedArrival,
                        Double.NaN, replayedWeight, Double.NaN,
                        "candidate departure is outside constructed route-profile support: "
                                + unsupported.getMessage()));
            }
        }
        return List.copyOf(result);
    }

    private static List<SicspProfileDiscrepancy> compareOPointInterpolants(
            SicspArrivalFunction arrivalFunction,
            List<SicspRouteProfile> retainedProfiles) {
        Map<Path, SicspRouteProfile> profiles = new HashMap<>();
        retainedProfiles.forEach(profile -> profiles.put(profile.path(), profile));
        List<SicspProfileDiscrepancy> result = new ArrayList<>();
        for (SicspArrivalFunction.OPointPathFunction oPointFunction
                : arrivalFunction.oPointPathFunctions()) {
            SicspRouteProfile route = profiles.get(oPointFunction.path());
            if (route == null) {
                continue;
            }
            Domain common = oPointFunction.function().domain()
                    .intersection(route.arrivalFunction().domain());
            TreeSet<Double> cuts = new TreeSet<>(common.breakpoints());
            for (var breakpoint : oPointFunction.function().breakpoints()) {
                if (common.contains(breakpoint.minute())) {
                    cuts.add(breakpoint.minute());
                }
            }
            for (var cell : route.arrivalFunction().cells()) {
                for (var breakpoint : cell.breakpoints()) {
                    if (common.contains(breakpoint.minute())) {
                        cuts.add(breakpoint.minute());
                    }
                }
            }
            List<Double> ordered = List.copyOf(cuts);
            for (double point : ordered) {
                addOPointDiscrepancy(result, oPointFunction, route, point);
            }
            for (int i = 1; i < ordered.size(); i++) {
                double start = ordered.get(i - 1);
                double end = ordered.get(i);
                if (end > start) {
                    double midpoint = Domain.canonicalTime(start + (end - start) / 2.0);
                    addOPointDiscrepancy(result, oPointFunction, route, midpoint);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void addOPointDiscrepancy(
            List<SicspProfileDiscrepancy> output,
            SicspArrivalFunction.OPointPathFunction oPointFunction,
            SicspRouteProfile route,
            double departure) {
        if (!oPointFunction.function().domain().contains(departure)
                || !route.arrivalFunction().domain().contains(departure)) {
            return;
        }
        double interpolated = oPointFunction.function().valueAt(departure);
        double exact = route.arrivalFunction().valueAt(departure);
        if (interpolated != exact) {
            double weight = route.weightFunction().valueAt(departure);
            output.add(new SicspProfileDiscrepancy(
                    oPointFunction.path(), departure, exact, interpolated, weight, weight,
                    "paper O-point linear interpolation differs from exact path-arrival composition"));
        }
    }

    private boolean containsRepeatedNode(int source, Path path) {
        Set<Integer> visited = new HashSet<>();
        int current = source;
        visited.add(current);
        for (int arcId : path.arcIds()) {
            Edge edge = graph.edges().get(arcId);
            current = edge.target();
            if (!visited.add(current)) {
                return true;
            }
        }
        return false;
    }

    private static void validateFifoClosedGraph(TDGraph graph) {
        for (Edge edge : graph.edges()) {
            var function = edge.travelTimeFunction();
            function.requireFifo("arc_id " + edge.arcId());
            List<Domain.Interval> intervals = function.domain().intervals();
            for (Domain.Interval interval : intervals) {
                if (!interval.startInclusive() || !interval.endInclusive()) {
                    throw new IllegalArgumentException(
                            "native SICSP reverse LST search requires closed edge domains; arc_id "
                                    + edge.arcId());
                }
            }
            for (int i = 1; i < intervals.size(); i++) {
                double priorLimit = function.arrivalTimeAtClosure(intervals.get(i - 1).end());
                double nextLimit = function.arrivalTimeAtClosure(intervals.get(i).start());
                if (priorLimit > nextLimit) {
                    throw new IllegalArgumentException(
                            "arc_id " + edge.arcId() + ": arrival function is non-FIFO across a domain gap");
                }
            }
        }
    }
}
