package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.baselines.ExactTemporalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Interval-wide arrival profiles retained by SICSP, with every function
 * associated with the path that supplies its matching weight profile.
 */
public final class SicspArrivalFunction {
    public record Selection(double sourceDeparture, double destinationArrival,
                            double accumulatedWeight, Path path) {
    }

    public record SelectionEvaluation(Optional<Selection> selection, long feasibleDepartureRegions) {
        public SelectionEvaluation {
            Objects.requireNonNull(selection, "selection");
        }
    }
    /** Paper-style linear segment between neighboring O-points on one path. */
    public record OPointPiece(Path path, double startDeparture, double endDeparture,
                              double startArrival, double endArrival) {
        public OPointPiece {
            Objects.requireNonNull(path, "path");
            if (!(endDeparture > startDeparture)) {
                throw new IllegalArgumentException("O-point piece must have positive departure width");
            }
        }
    }

    /** A crossing of two path-arrival pieces (an I-point in the paper). */
    public record IPoint(double departure, double arrival, Path leftPath, Path rightPath) {
        public IPoint {
            Objects.requireNonNull(leftPath, "leftPath");
            Objects.requireNonNull(rightPath, "rightPath");
        }
    }

    /** O-point interpolation function for a single path group. */
    public record OPointPathFunction(Path path, TimeProfile function) {
        public OPointPathFunction {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(function, "function");
        }
    }

    private final List<SicspRouteProfile> routeProfiles;
    private final List<OPointPiece> oPointPieces;
    private final List<OPointPathFunction> oPointPathFunctions;
    private final List<Path> pathsWithoutInterpolatedOFunction;
    private final List<IPoint> iPoints;
    private final ExactTemporalProfile oPointLowerEnvelope;
    private final ExactTemporalProfile lowerEnvelope;

    private SicspArrivalFunction(List<SicspRouteProfile> routeProfiles,
                                 List<OPointPiece> oPointPieces,
                                 List<OPointPathFunction> oPointPathFunctions,
                                 List<Path> pathsWithoutInterpolatedOFunction,
                                 List<IPoint> iPoints,
                                 ExactTemporalProfile oPointLowerEnvelope,
                                 ExactTemporalProfile lowerEnvelope) {
        this.routeProfiles = List.copyOf(routeProfiles);
        this.oPointPieces = List.copyOf(oPointPieces);
        this.oPointPathFunctions = List.copyOf(oPointPathFunctions);
        this.pathsWithoutInterpolatedOFunction = List.copyOf(pathsWithoutInterpolatedOFunction);
        this.iPoints = List.copyOf(iPoints);
        this.oPointLowerEnvelope = oPointLowerEnvelope;
        this.lowerEnvelope = lowerEnvelope;
    }

    public static SicspArrivalFunction construct(
            List<SicspRouteProfile> routeProfiles,
            List<SicspBreakpointCandidate> retainedOPoints) {
        Objects.requireNonNull(routeProfiles, "routeProfiles");
        Objects.requireNonNull(retainedOPoints, "retainedOPoints");
        List<OPointPiece> pieces = makeOPointPieces(retainedOPoints);
        List<IPoint> intersections = makeIPoints(pieces);
        List<Path> pathsWithoutFunction = new ArrayList<>();
        List<OPointPathFunction> pathFunctions = makeOPointPathFunctions(
                retainedOPoints, pathsWithoutFunction);
        ExactTemporalProfile oPointEnvelope = ExactTemporalProfile.empty();
        for (OPointPathFunction pathFunction : pathFunctions) {
            ExactTemporalProfile pathProfile = ExactTemporalProfile.from(pathFunction.function());
            oPointEnvelope = oPointEnvelope.isEmpty()
                    ? pathProfile
                    : oPointEnvelope.pointwiseMinimum(pathProfile);
        }
        ExactTemporalProfile envelope = ExactTemporalProfile.empty();
        for (SicspRouteProfile route : routeProfiles) {
            if (!route.arrivalFunction().isEmpty()) {
                envelope = envelope.isEmpty()
                        ? route.arrivalFunction()
                        : envelope.pointwiseMinimum(route.arrivalFunction());
            }
        }
        return new SicspArrivalFunction(routeProfiles, pieces, pathFunctions,
                pathsWithoutFunction, intersections, oPointEnvelope, envelope);
    }

    private static List<OPointPathFunction> makeOPointPathFunctions(
            List<SicspBreakpointCandidate> points,
            List<Path> missingFunctions) {
        var byPath = new java.util.LinkedHashMap<Path, List<SicspBreakpointCandidate>>();
        for (SicspBreakpointCandidate candidate : points) {
            if (candidate.type() == SicspBreakpointCandidate.Type.ARRIVAL_BREAKPOINT
                    && candidate.sourceDeparture().isPresent()
                    && candidate.replayedDestinationArrival().isPresent()
                    && candidate.path().isPresent()) {
                byPath.computeIfAbsent(candidate.path().orElseThrow(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        List<OPointPathFunction> result = new ArrayList<>();
        for (var entry : byPath.entrySet()) {
            List<SicspBreakpointCandidate> ordered = entry.getValue();
            ordered.sort(Comparator.comparingDouble(candidate -> candidate.sourceDeparture().orElseThrow()));
            List<SicspBreakpointCandidate> unique = new ArrayList<>();
            for (SicspBreakpointCandidate candidate : ordered) {
                if (unique.isEmpty()
                        || unique.get(unique.size() - 1).sourceDeparture().orElseThrow()
                        != candidate.sourceDeparture().orElseThrow()) {
                    unique.add(candidate);
                }
            }
            if (unique.size() < 2) {
                missingFunctions.add(entry.getKey());
                continue;
            }
            List<TimeProfile.Breakpoint> breakpoints = unique.stream()
                    .map(candidate -> new TimeProfile.Breakpoint(
                            candidate.sourceDeparture().orElseThrow(),
                            candidate.replayedDestinationArrival().orElseThrow()))
                    .toList();
            double start = breakpoints.get(0).minute();
            double end = breakpoints.get(breakpoints.size() - 1).minute();
            if (!(end > start)) {
                missingFunctions.add(entry.getKey());
                continue;
            }
            result.add(new OPointPathFunction(entry.getKey(), TimeProfile.piecewise(
                    Domain.closed(start, end), breakpoints,
                    "sicsp-o-points:" + entry.getKey().arcIds())));
        }
        return List.copyOf(result);
    }

    private static List<OPointPiece> makeOPointPieces(List<SicspBreakpointCandidate> points) {
        List<OPointPiece> result = new ArrayList<>();
        var byPath = new java.util.LinkedHashMap<Path, List<SicspBreakpointCandidate>>();
        for (SicspBreakpointCandidate candidate : points) {
            if (candidate.type() == SicspBreakpointCandidate.Type.ARRIVAL_BREAKPOINT
                    && candidate.sourceDeparture().isPresent()
                    && candidate.replayedDestinationArrival().isPresent()
                    && candidate.path().isPresent()) {
                byPath.computeIfAbsent(candidate.path().orElseThrow(), ignored -> new ArrayList<>()).add(candidate);
            }
        }
        for (var entry : byPath.entrySet()) {
            List<SicspBreakpointCandidate> ordered = entry.getValue();
            ordered.sort(Comparator.comparingDouble(candidate -> candidate.sourceDeparture().orElseThrow()));
            List<SicspBreakpointCandidate> unique = new ArrayList<>();
            for (SicspBreakpointCandidate candidate : ordered) {
                if (unique.isEmpty()
                        || unique.get(unique.size() - 1).sourceDeparture().orElseThrow()
                        != candidate.sourceDeparture().orElseThrow()) {
                    unique.add(candidate);
                }
            }
            for (int i = 1; i < unique.size(); i++) {
                SicspBreakpointCandidate left = unique.get(i - 1);
                SicspBreakpointCandidate right = unique.get(i);
                result.add(new OPointPiece(
                        entry.getKey(),
                        left.sourceDeparture().orElseThrow(),
                        right.sourceDeparture().orElseThrow(),
                        left.replayedDestinationArrival().orElseThrow(),
                        right.replayedDestinationArrival().orElseThrow()));
            }
        }
        return List.copyOf(result);
    }

    private static List<IPoint> makeIPoints(List<OPointPiece> pieces) {
        List<IPoint> result = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            OPointPiece left = pieces.get(i);
            for (int j = i + 1; j < pieces.size(); j++) {
                OPointPiece right = pieces.get(j);
                if (left.path().equals(right.path())) {
                    continue;
                }
                double overlapStart = Math.max(left.startDeparture(), right.startDeparture());
                double overlapEnd = Math.min(left.endDeparture(), right.endDeparture());
                if (overlapEnd < overlapStart) {
                    continue;
                }
                double leftStart = interpolate(left, overlapStart);
                double rightStart = interpolate(right, overlapStart);
                if (leftStart == rightStart) {
                    result.add(new IPoint(overlapStart, leftStart, left.path(), right.path()));
                }
                if (overlapEnd == overlapStart) {
                    continue;
                }
                double leftEnd = interpolate(left, overlapEnd);
                double rightEnd = interpolate(right, overlapEnd);
                double startDifference = leftStart - rightStart;
                double endDifference = leftEnd - rightEnd;
                if (startDifference == 0.0 || endDifference == 0.0) {
                    if (endDifference == 0.0) {
                        result.add(new IPoint(overlapEnd, leftEnd, left.path(), right.path()));
                    }
                    continue;
                }
                if ((startDifference < 0.0 && endDifference > 0.0)
                        || (startDifference > 0.0 && endDifference < 0.0)) {
                    double alpha = -startDifference / (endDifference - startDifference);
                    double departure = Domain.canonicalTime(overlapStart + alpha * (overlapEnd - overlapStart));
                    if (departure > overlapStart && departure < overlapEnd) {
                        result.add(new IPoint(departure, interpolate(left, departure), left.path(), right.path()));
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(IPoint::departure)
                .thenComparing(IPoint::leftPath, SicspPathOrder::compare)
                .thenComparing(IPoint::rightPath, SicspPathOrder::compare));
        List<IPoint> unique = new ArrayList<>();
        for (IPoint point : result) {
            if (unique.isEmpty()
                    || unique.get(unique.size() - 1).departure() != point.departure()
                    || !unique.get(unique.size() - 1).leftPath().equals(point.leftPath())
                    || !unique.get(unique.size() - 1).rightPath().equals(point.rightPath())) {
                unique.add(point);
            }
        }
        return List.copyOf(unique);
    }

    private static double interpolate(OPointPiece piece, double departure) {
        double ratio = (departure - piece.startDeparture()) / (piece.endDeparture() - piece.startDeparture());
        return Domain.canonicalTime(piece.startArrival() + ratio * (piece.endArrival() - piece.startArrival()));
    }

    /** ArrTime over the retained path-linked arrival and weight functions. */
    public SelectionEvaluation selectBestFeasible(SicspNativeQuery query) {
        Objects.requireNonNull(query, "query");
        Selection best = null;
        long regions = 0;
        for (SicspRouteProfile route : routeProfiles) {
            Domain feasible = route.weightFunction().feasibleDomain(query.weightBound())
                    .intersection(query.departureDomain())
                    .intersection(route.arrivalFunction().domain());
            regions += feasible.intervals().size();
            for (Domain.Interval interval : feasible.intervals()) {
                List<Double> candidates = candidateTimes(route, interval);
                for (double departure : candidates) {
                    if (!interval.contains(departure)) {
                        continue;
                    }
                    double arrival;
                    double weight;
                    try {
                        arrival = route.arrivalFunction().valueAt(departure);
                        weight = route.weightFunction().valueAt(departure);
                    } catch (IllegalArgumentException unsupportedPoint) {
                        continue;
                    }
                    if (weight > query.weightBound()) {
                        continue;
                    }
                    Selection candidate = new Selection(
                            departure, arrival, weight, route.path());
                    if (best == null || compareSelections(candidate, best) < 0) {
                        best = candidate;
                    }
                }
            }
        }
        return new SelectionEvaluation(Optional.ofNullable(best), regions);
    }

    private static List<Double> candidateTimes(SicspRouteProfile route, Domain.Interval interval) {
        java.util.TreeSet<Double> times = new java.util.TreeSet<>();
        long startTick = Domain.canonicalTick(interval.start());
        long endTick = Domain.canonicalTick(interval.end());
        long first = interval.startInclusive() ? startTick : startTick + 1;
        long last = interval.endInclusive() ? endTick : endTick - 1;
        if (first > last) {
            return List.of();
        }
        times.add(Domain.timeFromTick(first));
        times.add(Domain.timeFromTick(last));
        for (var cell : route.arrivalFunction().cells()) {
            for (var breakpoint : cell.breakpoints()) {
                if (breakpoint.minute() >= interval.start() && breakpoint.minute() <= interval.end()
                        && interval.contains(breakpoint.minute())) {
                    times.add(breakpoint.minute());
                }
            }
        }
        for (SicspWeightFunction.Piece piece : route.weightFunction().pieces()) {
            for (double point : List.of(piece.interval().start(), piece.interval().end())) {
                if (point >= interval.start() && point <= interval.end() && interval.contains(point)) {
                    times.add(point);
                }
            }
        }
        return List.copyOf(times);
    }

    private static int compareSelections(Selection left, Selection right) {
        int comparison = Double.compare(left.destinationArrival(), right.destinationArrival());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(left.accumulatedWeight(), right.accumulatedWeight());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(left.sourceDeparture(), right.sourceDeparture());
        if (comparison != 0) {
            return comparison;
        }
        int common = Math.min(left.path().arcIds().size(), right.path().arcIds().size());
        for (int i = 0; i < common; i++) {
            comparison = Integer.compare(left.path().arcIds().get(i), right.path().arcIds().get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.path().arcIds().size(), right.path().arcIds().size());
    }

    public List<SicspRouteProfile> routeProfiles() {
        return routeProfiles;
    }

    public List<OPointPiece> oPointPieces() {
        return oPointPieces;
    }

    public List<OPointPathFunction> oPointPathFunctions() {
        return oPointPathFunctions;
    }

    public List<Path> pathsWithoutInterpolatedOFunction() {
        return pathsWithoutInterpolatedOFunction;
    }

    public List<IPoint> iPoints() {
        return iPoints;
    }

    /** Paper Algorithm 1's O-point-interpolated arrival envelope (may be partial). */
    public ExactTemporalProfile oPointLowerEnvelope() {
        return oPointLowerEnvelope;
    }

    /** Lower envelope of exact, path-linked candidate arrival profiles, independent of Delta. */
    public ExactTemporalProfile lowerEnvelope() {
        return lowerEnvelope;
    }
}
