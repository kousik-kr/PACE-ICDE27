package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;

/** Exact continuous minimum-travel-time envelope with stable path witnesses. */
public final class FastestEnvelopeExtractor {
    private FastestEnvelopeExtractor() {
    }

    /** Selects only by arrival/travel time; preference scores are ignored. */
    public static EnvelopeProfile extract(
            CandidateSet candidates,
            Domain rootDomain) {
        return extract(candidates, rootDomain, () -> false);
    }

    /** Cancellation-aware continuous fastest-envelope extraction. */
    public static EnvelopeProfile extract(
            CandidateSet candidates,
            Domain rootDomain,
            BooleanSupplier cancelled) {
        if (candidates == null || rootDomain == null || rootDomain.isEmpty()) {
            throw new IllegalArgumentException(
                    "candidates and a nonempty root domain are required");
        }
        List<CandidateProfile> values = candidates.candidates();
        List<Double> cuts = new ArrayList<>(rootDomain.breakpoints());
        for (CandidateProfile candidate : values) {
            requireActive(cancelled);
            cuts.addAll(candidate.domain().breakpoints());
            candidate.arrivalProfile().breakpoints().forEach(
                    point -> cuts.add(point.minute()));
        }
        addArrivalCrossings(values, rootDomain, cuts, cancelled);
        List<EnvelopeSegment> raw = new ArrayList<>();
        for (Domain.Interval cell : ProfileCellPartition.partition(rootDomain, cuts)) {
            requireActive(cancelled);
            assignCell(values, cell, raw);
        }
        requireActive(cancelled);
        return new EnvelopeProfile(rootDomain, mergeAdjacent(raw));
    }

    private static void addArrivalCrossings(
            List<CandidateProfile> candidates,
            Domain rootDomain,
            List<Double> cuts,
            BooleanSupplier cancelled) {
        List<Domain.Interval> baseCells = ProfileCellPartition.partition(
                rootDomain, cuts);
        for (int leftIndex = 0; leftIndex < candidates.size(); leftIndex++) {
            CandidateProfile left = candidates.get(leftIndex);
            for (int rightIndex = leftIndex + 1;
                    rightIndex < candidates.size(); rightIndex++) {
                requireActive(cancelled);
                CandidateProfile right = candidates.get(rightIndex);
                for (Domain.Interval cell : baseCells) {
                    requireActive(cancelled);
                    if (cell.end() <= cell.start()) {
                        continue;
                    }
                    double sample = ProfileCellPartition.midpoint(cell);
                    if (!left.domain().contains(sample)
                            || !right.domain().contains(sample)) {
                        continue;
                    }
                    double startDifference = Domain.canonicalTime(
                            left.arrivalProfile().valueAtClosure(cell.start())
                            - right.arrivalProfile().valueAtClosure(
                                    cell.start()));
                    double endDifference = Domain.canonicalTime(
                            left.arrivalProfile().valueAtClosure(cell.end())
                            - right.arrivalProfile().valueAtClosure(
                                    cell.end()));
                    if (startDifference * endDifference < 0.0) {
                        double root = cell.start()
                                - startDifference * (cell.end() - cell.start())
                                / (endDifference - startDifference);
                        cuts.add(Domain.canonicalTime(root));
                    }
                }
            }
        }
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled == null) {
            throw new IllegalArgumentException("cancellation predicate is required");
        }
        if (cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "fastest envelope construction reached its query deadline");
        }
    }

    private static void assignCell(
            List<CandidateProfile> candidates,
            Domain.Interval cell,
            List<EnvelopeSegment> output) {
        if (cell.start() == cell.end()) {
            output.add(new EnvelopeSegment(
                    cell, bestAt(candidates, cell.start(), false)));
            return;
        }
        double midpoint = ProfileCellPartition.midpoint(cell);
        CandidateProfile interior = bestAt(candidates, midpoint, false);
        CandidateProfile start = cell.startInclusive()
                ? bestAt(candidates, cell.start(), false)
                : interior;
        CandidateProfile end = cell.endInclusive()
                ? bestAt(candidates, cell.end(), false)
                : interior;
        boolean splitStart = cell.startInclusive()
                && !sameAssignment(start, interior);
        boolean splitEnd = cell.endInclusive()
                && !sameAssignment(end, interior);
        if (splitStart) {
            output.add(new EnvelopeSegment(
                    new Domain.Interval(cell.start(), cell.start()), start));
        }
        output.add(new EnvelopeSegment(
                new Domain.Interval(
                        cell.start(), cell.end(),
                        cell.startInclusive() && !splitStart,
                        cell.endInclusive() && !splitEnd),
                interior));
        if (splitEnd) {
            output.add(new EnvelopeSegment(
                    new Domain.Interval(cell.end(), cell.end()), end));
        }
    }

    private static CandidateProfile bestAt(
            List<CandidateProfile> candidates,
            double departure,
            boolean closure) {
        CandidateProfile best = null;
        for (CandidateProfile candidate : candidates) {
            if (!candidate.domain().contains(departure)) {
                continue;
            }
            if (best == null || compare(candidate, best, departure, closure) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static int compare(
            CandidateProfile left,
            CandidateProfile right,
            double departure,
            boolean closure) {
        double leftArrival = closure
                ? left.arrivalProfile().valueAtClosure(departure)
                : left.arrivalProfile().valueAt(departure);
        double rightArrival = closure
                ? right.arrivalProfile().valueAtClosure(departure)
                : right.arrivalProfile().valueAt(departure);
        int comparison = Double.compare(
                Domain.canonicalTime(leftArrival - rightArrival), 0.0);
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.edgeCount(), right.edgeCount());
        if (comparison != 0) {
            return comparison;
        }
        return PathPointer.STABLE_PATH_ORDER.compare(
                left.stablePathId(), right.stablePathId());
    }

    private static List<EnvelopeSegment> mergeAdjacent(
            List<EnvelopeSegment> source) {
        List<EnvelopeSegment> result = new ArrayList<>();
        for (EnvelopeSegment next : source) {
            if (result.isEmpty()) {
                result.add(next);
                continue;
            }
            EnvelopeSegment previous = result.get(result.size() - 1);
            if (!previous.sameAssignment(next)
                    || !Domain.sameTime(
                            previous.interval().end(), next.interval().start())
                    || !(previous.interval().endInclusive()
                            || next.interval().startInclusive())) {
                result.add(next);
                continue;
            }
            Domain.Interval merged = new Domain.Interval(
                    previous.interval().start(),
                    next.interval().end(),
                    previous.interval().startInclusive(),
                    next.interval().endInclusive());
            result.set(
                    result.size() - 1,
                    new EnvelopeSegment(
                            merged,
                            previous.found()
                                    ? previous.candidate()
                                    : null));
        }
        return List.copyOf(result);
    }

    private static boolean sameAssignment(
            CandidateProfile left,
            CandidateProfile right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.stablePathId().equals(right.stablePathId());
    }
}
