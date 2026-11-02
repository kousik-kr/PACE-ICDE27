package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.LooplessChecker;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.math.BigInteger;
import java.util.Set;

/** Minimal objective adaptation of the native sequential SICSP candidate core. */
public final class SicspPreferenceEngine {
    private record Replay(ExactTime arrival, Optional<BigInteger> score) {
    }

    private record ReplayKey(Path path, double departure) {
    }

    private final TDGraph graph;

    public SicspPreferenceEngine(TDGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Runs SICSP's unchanged LST/EAT event generator and builds a PACE score envelope. */
    public SicspPrefResult query(SicspPrefQuery query) {
        Objects.requireNonNull(query, "query");
        graph.node(query.source());
        graph.node(query.destination());
        long queryStarted = System.nanoTime();
        long sampledHeap = usedHeap();

        Map<Integer, SicspEdgeWeightFn> zeroWeights = new LinkedHashMap<>();
        for (Edge edge : graph.edges()) {
            zeroWeights.put(edge.arcId(),
                    SicspEdgeWeightFn.constant(edge.travelTimeFunction().domain(), 0.0));
        }
        SicspNativeGraph timingGraph = new SicspNativeGraph(graph, zeroWeights);
        SicspNativeEngine sharedCore = new SicspNativeEngine(
                timingGraph,
                arcId -> graph.edges().get(arcId).scoreFunction().breakpoints());
        SicspCandidateGenerationResult generation = sharedCore.generateCandidates(
                new SicspNativeQuery(
                        query.source(), query.destination(), query.departureDomain(), 0.0));
        sampledHeap = Math.max(sampledHeap, generation.peakSampledHeapBytes());
        long adaptationStarted = System.nanoTime();

        List<SicspBreakpointCandidate> pointEvents = new ArrayList<>();
        pointEvents.addAll(generation.arrivalPoints());
        pointEvents.addAll(generation.weightPoints());
        List<SicspBreakpointCandidate> outsideEvents = new ArrayList<>();
        generation.arrivalDiagnostics().stream()
                .filter(SicspPreferenceEngine::isConsistentOutsideEvent)
                .forEach(outsideEvents::add);
        generation.weightDiagnostics().stream()
                .filter(SicspPreferenceEngine::isConsistentOutsideEvent)
                .forEach(outsideEvents::add);

        LinkedHashSet<Path> rawPaths = new LinkedHashSet<>();
        LinkedHashSet<Path> simplePaths = new LinkedHashSet<>();
        List<SicspBreakpointCandidate> rejectedNonSimple = new ArrayList<>();
        for (SicspBreakpointCandidate candidate : pointEvents) {
            collectPath(candidate, rawPaths, simplePaths, rejectedNonSimple);
        }
        for (SicspBreakpointCandidate candidate : outsideEvents) {
            collectPath(candidate, rawPaths, simplePaths, rejectedNonSimple);
        }
        if (query.source() == query.destination()) {
            rawPaths.add(Path.empty());
            simplePaths.add(Path.empty());
        }

        List<SicspBreakpointCandidate> rawO = generation.arrivalPoints().stream()
                .filter(this::isSimple).toList();
        List<SicspBreakpointCandidate> rawW = generation.weightPoints().stream()
                .filter(this::isSimple).toList();
        Map<ReplayKey, Optional<Replay>> replayCache = new HashMap<>();
        List<SicspBreakpointCandidate> retainedO = retainArrivalPoints(
                rawO, query, replayCache);
        List<SicspBreakpointCandidate> retainedW = retainWeightPoints(
                rawW, query, replayCache);
        List<SicspBreakpointCandidate> dominatedO = subtractByIdentity(rawO, retainedO);
        List<SicspBreakpointCandidate> dominatedW = subtractByIdentity(rawW, retainedW);
        sampledHeap = Math.max(sampledHeap, usedHeap());

        LinkedHashSet<Path> retainedPaths = new LinkedHashSet<>();
        retainedO.forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        retainedW.forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        outsideEvents.stream()
                .filter(this::isSimple)
                .forEach(candidate -> candidate.path().ifPresent(retainedPaths::add));
        if (query.source() == query.destination()) {
            retainedPaths.add(Path.empty());
        }
        List<Path> outsidePaths = outsideEvents.stream()
                .filter(this::isSimple)
                .map(candidate -> candidate.path().orElseThrow())
                .distinct()
                .sorted(SicspPathOrder::compare)
                .toList();
        SicspPrefCandidateGenerationResult preferenceGeneration = new SicspPrefCandidateGenerationResult(
                adaptCandidates(generation.arrivalPoints(), query, replayCache),
                adaptCandidates(generation.arrivalDiagnostics(), query, replayCache),
                adaptCandidates(generation.weightPoints(), query, replayCache),
                adaptCandidates(generation.weightDiagnostics(), query, replayCache),
                generation.edgesConsidered(),
                generation.timeBreakpointsExamined(),
                generation.weightBreakpointsExamined(),
                generation.reverseDijkstraCalls(),
                generation.forwardDijkstraCalls(),
                generation.duplicateCandidates(),
                generation.candidateGenerationNanos(),
                generation.peakSampledHeapBytes());
        long candidateAdaptationNanos = System.nanoTime() - adaptationStarted;
        sampledHeap = Math.max(sampledHeap, usedHeap());

        long profileReplayStarted = System.nanoTime();
        ExactPathProfileBuilder.HorizonReplayContext replay =
                ExactPathProfileBuilder.budgetContext(
                        graph, query.departureDomain(), query.travelTimeBudget(), () -> false);
        Map<Path, CandidateProfile> feasibleProfiles = new LinkedHashMap<>();
        CandidateSet candidates = new CandidateSet();
        for (Path path : retainedPaths) {
            Optional<CandidateProfile> candidate = replay.replay(
                    path.arcIds(), query.source(), query.destination());
            if (candidate.isPresent()) {
                feasibleProfiles.put(path, candidate.orElseThrow());
                candidates.add(candidate.orElseThrow());
            }
            sampledHeap = Math.max(sampledHeap, usedHeap());
        }
        Map<Path, ExactPreferencePathProfile> exactProfiles = new LinkedHashMap<>();
        ExactTemporalDomain exactQueryDomain = ExactTemporalDomain.fromLegacy(query.departureDomain());
        for (Path path : retainedPaths) {
            ExactPreferenceReplay.replay(graph, query.source(), query.destination(), path,
                    query.departureDomain(), query.travelTimeBudget())
                    .ifPresent(profile -> exactProfiles.put(path, profile));
            sampledHeap = Math.max(sampledHeap, usedHeap());
        }
        List<Path> budgetFeasiblePaths = exactProfiles.values().stream()
                .filter(profile -> !profile.feasibleDomain().isEmpty())
                .map(ExactPreferencePathProfile::path)
                .sorted(SicspPathOrder::compare).toList();
        long profileReplayNanos = System.nanoTime() - profileReplayStarted;

        long envelopeStarted = System.nanoTime();
        EnvelopeProfile envelope = EnvelopeExtractor.extract(candidates, query.departureDomain());
        ExactPreferenceEnvelope exactEnvelope = ExactPreferenceEnvelope.overRetained(exactQueryDomain, exactProfiles);
        long envelopeNanos = System.nanoTime() - envelopeStarted;
        sampledHeap = Math.max(sampledHeap, usedHeap());

        ProfileMeasures measures = measure(exactEnvelope);
        long queryNanos = System.nanoTime() - queryStarted;
        SicspPrefStatistics statistics = new SicspPrefStatistics(
                generation.edgesConsidered(),
                generation.candidateGenerationNanos(),
                candidateAdaptationNanos,
                generation.forwardDijkstraCalls(),
                generation.reverseDijkstraCalls(),
                generation.timeBreakpointsExamined(),
                generation.weightBreakpointsExamined(),
                generation.duplicateCandidates(),
                generation.arrivalPoints().size(),
                generation.weightPoints().size(),
                dominatedO.size() + dominatedW.size(),
                rejectedNonSimple.size(),
                rejectedNonSimple.stream().map(candidate -> candidate.path().orElseThrow())
                        .distinct().count(),
                rawPaths.size(),
                simplePaths.size(),
                outsidePaths.size(),
                retainedPaths.size(),
                budgetFeasiblePaths.size(),
                retainedPaths.size() - budgetFeasiblePaths.size(),
                profileReplayNanos,
                envelopeNanos,
                queryNanos,
                exactEnvelope.cells().size(),
                measures.feasibleCells(),
                sampledHeap,
                measures.feasibleCoverage(),
                measures.integratedScore(),
                measures.minimumTravelTime(),
                measures.averageTravelTime(),
                measures.maximumTravelTime());

        return new SicspPrefResult(
                envelope,
                preferenceGeneration,
                preferenceGeneration.arrivalPoints(),
                adaptCandidates(retainedO, query, replayCache),
                adaptCandidates(dominatedO, query, replayCache),
                preferenceGeneration.preferencePoints(),
                adaptCandidates(retainedW, query, replayCache),
                adaptCandidates(dominatedW, query, replayCache),
                adaptCandidates(rejectedNonSimple, query, replayCache),
                orderedPaths(rawPaths),
                orderedPaths(simplePaths),
                outsidePaths,
                orderedPaths(retainedPaths),
                feasibleProfiles,
                exactEnvelope,
                exactProfiles,
                budgetFeasiblePaths,
                statistics);
    }

    private static boolean isConsistentOutsideEvent(SicspBreakpointCandidate candidate) {
        return candidate.status() == SicspBreakpointCandidate.Status.SOURCE_DEPARTURE_OUTSIDE_QUERY
                && candidate.isProfileRouteCandidate();
    }

    private void collectPath(
            SicspBreakpointCandidate candidate,
            Set<Path> rawPaths,
            Set<Path> simplePaths,
            List<SicspBreakpointCandidate> rejected) {
        if (candidate.path().isEmpty()) {
            return;
        }
        Path path = candidate.path().orElseThrow();
        rawPaths.add(path);
        if (!LooplessChecker.isLoopless(graph, path)) {
            rejected.add(candidate);
        } else {
            simplePaths.add(path);
        }
    }

    private boolean isSimple(SicspBreakpointCandidate candidate) {
        return candidate.path().isPresent()
                && LooplessChecker.isLoopless(graph, candidate.path().orElseThrow());
    }

    private List<SicspBreakpointCandidate> retainArrivalPoints(
            List<SicspBreakpointCandidate> candidates,
            SicspPrefQuery query,
            Map<ReplayKey, Optional<Replay>> replayCache) {
        Map<Path, List<SicspBreakpointCandidate>> byPath = SicspCandidateGroups.byPath(candidates);
        List<SicspBreakpointCandidate> retained = new ArrayList<>();
        for (SicspBreakpointCandidate candidate : candidates) {
            Path path = candidate.path().orElseThrow();
            double departure = candidate.sourceDeparture().orElseThrow();
            Optional<Replay> candidateReplay = replayAt(path, departure, query, replayCache);
            boolean dominated = false;
            for (Path otherPath : byPath.keySet()) {
                if (otherPath.equals(path)) {
                    continue;
                }
                Optional<Replay> otherReplay = replayAt(otherPath, departure, query, replayCache);
                if (candidateReplay.isPresent() && otherReplay.isPresent()
                        && otherReplay.orElseThrow().score().isPresent()
                        && candidateReplay.orElseThrow().score().isPresent()
                        && SicspPreferenceDominance.strictlyDominates(
                                otherReplay.orElseThrow().arrival(),
                                otherReplay.orElseThrow().score().orElseThrow(),
                                candidateReplay.orElseThrow().arrival(),
                                candidateReplay.orElseThrow().score().orElseThrow())) {
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

    private List<SicspBreakpointCandidate> retainWeightPoints(
            List<SicspBreakpointCandidate> candidates,
            SicspPrefQuery query,
            Map<ReplayKey, Optional<Replay>> replayCache) {
        List<SicspBreakpointCandidate> retained = new ArrayList<>();
        for (SicspBreakpointCandidate candidate : candidates) {
            double departure = candidate.sourceDeparture().orElseThrow();
            Path candidatePath = candidate.path().orElseThrow();
            Optional<Replay> candidateReplay = replayAt(candidatePath, departure, query, replayCache);
            boolean dominated = false;
            for (SicspBreakpointCandidate other : candidates) {
                if (other == candidate
                        || other.sourceDeparture().orElseThrow() != departure) {
                    continue;
                }
                Optional<Replay> otherReplay = replayAt(
                        other.path().orElseThrow(), departure, query, replayCache);
                if (candidateReplay.isPresent() && otherReplay.isPresent()
                        && candidateReplay.orElseThrow().score().isPresent()
                        && otherReplay.orElseThrow().score().isPresent()) {
                    Replay left = otherReplay.orElseThrow();
                    Replay right = candidateReplay.orElseThrow();
                    if (SicspPreferenceDominance.skylineDominates(
                            left.arrival(), left.score().orElseThrow(),
                            right.arrival(), right.score().orElseThrow())) {
                        dominated = true;
                        break;
                    }
                }
            }
            if (!dominated) {
                retained.add(candidate);
            }
        }
        retained.sort(candidateOrder());
        return List.copyOf(retained);
    }

    private Optional<Replay> replayAt(
            Path path,
            double departure,
            SicspPrefQuery query,
            Map<ReplayKey, Optional<Replay>> cache) {
        ReplayKey key = new ReplayKey(path, Domain.canonicalTime(departure));
        return cache.computeIfAbsent(key, ignored -> replayPreferenceAt(
                graph, query.source(), query.destination(), key.departure(), path));
    }

    private static Optional<Replay> replayPreferenceAt(
            TDGraph graph,
            int source,
            int destination,
            double departure,
            Path path) {
        Optional<ExactPreferenceReplay.PointReplay> exact = ExactPreferenceReplay.replayAt(
                graph, source, destination, path, departure);
        return exact.map(point -> new Replay(point.arrival(), Optional.of(point.score())));
    }

    private List<SicspPrefBreakpointCandidate> adaptCandidates(
            List<SicspBreakpointCandidate> candidates,
            SicspPrefQuery query,
            Map<ReplayKey, Optional<Replay>> replayCache) {
        List<SicspPrefBreakpointCandidate> adapted = new ArrayList<>(candidates.size());
        for (SicspBreakpointCandidate candidate : candidates) {
            OptionalDouble departure = candidate.sourceDeparture();
            OptionalDouble arrival = candidate.replayedDestinationArrival();
            OptionalDouble travelTime = OptionalDouble.empty();
            OptionalInt score = OptionalInt.empty();
            if (departure.isPresent() && candidate.path().isPresent()
                    && LooplessChecker.isLoopless(graph, candidate.path().orElseThrow())) {
                Optional<Replay> replay = replayAt(
                        candidate.path().orElseThrow(), departure.getAsDouble(), query, replayCache);
                if (replay.isPresent()) {
                    ExactTime exactDeparture = ExactTime.fromCanonicalDouble(
                            Domain.canonicalTime(departure.getAsDouble()));
                    arrival = OptionalDouble.of(replay.orElseThrow().arrival().toDisplayDouble());
                    travelTime = OptionalDouble.of(replay.orElseThrow().arrival()
                            .subtract(exactDeparture).toDisplayDouble());
                    BigInteger exactScore = replay.orElseThrow().score().orElseThrow();
                    if (exactScore.bitLength() < Integer.SIZE) score = OptionalInt.of(exactScore.intValue());
                }
            }
            String rejectionReason = candidate.path().isPresent()
                    ? paceRejectionReason(candidate.path().orElseThrow()) : "";
            boolean paceSimple = candidate.path().isPresent() && rejectionReason.isEmpty();
            adapted.add(new SicspPrefBreakpointCandidate(
                    candidate.type(),
                    candidate.eventArcId(),
                    candidate.eventTime(),
                    departure,
                    candidate.actualPrefixArrival(),
                    candidate.intendedDestinationArrival(),
                    arrival,
                    travelTime,
                    score,
                    candidate.path(),
                    paceSimple,
                    rejectionReason,
                    candidate.status(),
                    candidate.detail()));
        }
        return List.copyOf(adapted);
    }

    private String paceRejectionReason(Path path) {
        Set<Integer> seen = new LinkedHashSet<>();
        Integer current = null;
        for (int arcId : path.arcIds()) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                return "UNKNOWN_ARC_ID:" + arcId;
            }
            Edge edge = graph.edges().get(arcId);
            if (current == null) {
                current = edge.source();
                seen.add(current);
            } else if (edge.source() != current) {
                return "DISCONNECTED_AT_ARC_ID:" + arcId;
            }
            current = edge.target();
            if (!seen.add(current)) {
                return "REPEATED_VERTEX:" + current;
            }
        }
        return "";
    }

    private static List<SicspBreakpointCandidate> subtractByIdentity(
            List<SicspBreakpointCandidate> all,
            List<SicspBreakpointCandidate> retained) {
        Set<SicspBreakpointCandidate> kept = Set.copyOf(retained);
        return all.stream().filter(candidate -> !kept.contains(candidate)).toList();
    }

    private static Comparator<SicspBreakpointCandidate> candidateOrder() {
        return Comparator.comparingDouble((SicspBreakpointCandidate candidate) ->
                        candidate.sourceDeparture().orElseThrow())
                .thenComparing(candidate -> candidate.path().orElseThrow(), SicspPathOrder::compare)
                .thenComparingDouble(SicspBreakpointCandidate::eventTime);
    }

    private static List<Path> orderedPaths(Set<Path> paths) {
        return paths.stream().sorted(SicspPathOrder::compare).toList();
    }

    private record ProfileMeasures(
            long feasibleCells,
            double feasibleCoverage,
            double integratedScore,
            double minimumTravelTime,
            double averageTravelTime,
            double maximumTravelTime) {
    }

    private static ProfileMeasures measure(ExactPreferenceEnvelope profile) {
        ExactTime total = ExactTime.ZERO;
        ExactTime feasible = ExactTime.ZERO;
        ExactTime scoreIntegral = ExactTime.ZERO;
        ExactTime travelIntegral = ExactTime.ZERO;
        ExactTime travelMeasure = ExactTime.ZERO;
        ExactTime minTravel = null;
        ExactTime maxTravel = null;
        long feasibleCells = 0;
        for (ExactTemporalDomain.Interval component : profile.domain().intervals()) {
            total = total.add(component.end().subtract(component.start()));
        }
        for (var cell : profile.cells()) {
            if (!cell.found()) continue;
            feasibleCells++;
            ExactTime length = cell.interval().end().subtract(cell.interval().start());
            feasible = feasible.add(length);
            scoreIntegral = scoreIntegral.add(length.multiply(new ExactTime(cell.score(), BigInteger.ONE)));
            ExactTime leftTravel = cell.arrivalSlope().subtract(ExactTime.ONE)
                    .multiply(cell.interval().start()).add(cell.arrivalIntercept());
            ExactTime rightTravel = cell.arrivalSlope().subtract(ExactTime.ONE)
                    .multiply(cell.interval().end()).add(cell.arrivalIntercept());
            minTravel = minTravel == null || leftTravel.compareTo(minTravel) < 0 ? leftTravel : minTravel;
            minTravel = rightTravel.compareTo(minTravel) < 0 ? rightTravel : minTravel;
            maxTravel = maxTravel == null || leftTravel.compareTo(maxTravel) > 0 ? leftTravel : maxTravel;
            maxTravel = rightTravel.compareTo(maxTravel) > 0 ? rightTravel : maxTravel;
            if (length.signum() > 0) {
                ExactTime slope = cell.arrivalSlope().subtract(ExactTime.ONE);
                ExactTime integral = slope.multiply(cell.interval().end().multiply(cell.interval().end())
                                .subtract(cell.interval().start().multiply(cell.interval().start())))
                        .divide(ExactTime.of(2))
                        .add(cell.arrivalIntercept().multiply(length));
                travelIntegral = travelIntegral.add(integral);
                travelMeasure = travelMeasure.add(length);
            }
        }
        double coverage = total.signum() > 0 ? feasible.divide(total).toDouble()
                : profile.cells().stream().anyMatch(cell -> cell.found()) ? 1.0 : 0.0;
        double average = travelMeasure.signum() > 0 ? travelIntegral.divide(travelMeasure).toDouble()
                : minTravel == null ? 0.0 : minTravel.toDouble();
        return new ProfileMeasures(feasibleCells, coverage, scoreIntegral.toDouble(),
                minTravel == null ? 0.0 : minTravel.toDouble(), average,
                maxTravel == null ? 0.0 : maxTravel.toDouble());
    }

    @SuppressWarnings("unused")
    private static ProfileMeasures measure(EnvelopeProfile profile) {
        double totalLength = 0;
        double feasibleLength = 0;
        double scoreIntegral = 0;
        double travelIntegral = 0;
        double travelMeasure = 0;
        double minTravel = Double.POSITIVE_INFINITY;
        double maxTravel = Double.NEGATIVE_INFINITY;
        long feasibleCells = 0;
        for (var segment : profile.segments()) {
            if (!segment.found()) {
                totalLength += segment.interval().end() - segment.interval().start();
                continue;
            }
            feasibleCells++;
            double segmentLength = segment.interval().end() - segment.interval().start();
            totalLength += segmentLength;
            feasibleLength += segmentLength;
            CandidateProfile candidate = segment.candidate();
            for (var scorePiece : candidate.scoreProfile().intervals()) {
                double left = Math.max(segment.interval().start(), scorePiece.startMinute());
                double right = Math.min(segment.interval().end(), scorePiece.endMinute());
                if (right > left) {
                    scoreIntegral += (right - left) * scorePiece.value();
                }
            }

            List<Double> cuts = new ArrayList<>();
            cuts.add(segment.interval().start());
            cuts.add(segment.interval().end());
            candidate.arrivalProfile().breakpoints().stream()
                    .map(point -> point.minute())
                    .filter(time -> time > segment.interval().start()
                            && time < segment.interval().end())
                    .forEach(cuts::add);
            cuts = cuts.stream().distinct().sorted().toList();
            if (segmentLength == 0) {
                double value = candidate.travelTimeAt(segment.interval().start());
                minTravel = Math.min(minTravel, value);
                maxTravel = Math.max(maxTravel, value);
            }
            for (int i = 1; i < cuts.size(); i++) {
                double left = cuts.get(i - 1);
                double right = cuts.get(i);
                double midpoint = left + (right - left) / 2.0;
                double midTravel = candidate.travelTimeAt(midpoint);
                double leftTravel = candidate.arrivalProfile().valueAtClosure(left) - left;
                double rightTravel = candidate.arrivalProfile().valueAtClosure(right) - right;
                minTravel = Math.min(minTravel, Math.min(leftTravel, Math.min(midTravel, rightTravel)));
                maxTravel = Math.max(maxTravel, Math.max(leftTravel, Math.max(midTravel, rightTravel)));
                travelIntegral += midTravel * (right - left);
                travelMeasure += right - left;
            }
        }
        double coverage = totalLength > 0
                ? feasibleLength / totalLength
                : profile.segments().stream().anyMatch(segment -> segment.found()) ? 1.0 : 0.0;
        double average = travelMeasure > 0 ? travelIntegral / travelMeasure
                : minTravel == Double.POSITIVE_INFINITY ? 0.0 : minTravel;
        return new ProfileMeasures(
                feasibleCells,
                coverage,
                scoreIntegral,
                minTravel == Double.POSITIVE_INFINITY ? 0.0 : minTravel,
                average,
                maxTravel == Double.NEGATIVE_INFINITY ? 0.0 : maxTravel);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
