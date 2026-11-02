package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Exact retained-frontier PACE envelope extraction.
 */
public final class EnvelopeExtractor {
    private EnvelopeExtractor() {
    }

    /**
     * Extracts the exact envelope over the retained root frontier. Cells preserve
     * canonical endpoint ownership, and uncovered cells are emitted as NO_PATH.
     */
    public static EnvelopeProfile extract(CandidateSet frontier, Domain rootDomain) {
        return extract(
                frontier, rootDomain, PaceExecutionMetrics.none());
    }

    /** Extracts an envelope while recording temporal-cell work. */
    public static EnvelopeProfile extract(
            CandidateSet frontier,
            Domain rootDomain,
            PaceExecutionMetrics metrics) {
        return extract(frontier, rootDomain, metrics, () -> false);
    }

    /** Cancellation-aware exact extraction for query-deadline algorithms. */
    public static EnvelopeProfile extract(
            CandidateSet frontier,
            Domain rootDomain,
            PaceExecutionMetrics metrics,
            BooleanSupplier cancelled) {
        if (frontier == null || rootDomain == null || rootDomain.isEmpty()) {
            throw new IllegalArgumentException("frontier and non-empty root domain are required");
        }
        List<CandidateProfile> candidates = frontier.candidates();
        for (CandidateProfile candidate : candidates) {
            requireActive(cancelled);
            requireExactContinuousMetadata(candidate);
        }

        List<Domain.Interval> cells =
                frontier.temporalCells().isEmpty()
                        ? ProfileCellPartition.cells(
                                rootDomain,
                                candidates,
                                true,
                                metrics,
                                cancelled)
                        : frontier.temporalCells();
        List<EnvelopeSegment> raw = new ArrayList<>();
        for (Domain.Interval cell : cells) {
            requireActive(cancelled);
            raw.addAll(assignCellExactly(candidates, cell, cancelled));
        }
        requireActive(cancelled);
        return new EnvelopeProfile(rootDomain, mergeAdjacentAssignments(raw));
    }

    /**
     * Final PACE ranking at a departure time: score, travel time, edge count,
     * then numeric lexicographic stable path id.
     */
    public static int compareAt(CandidateProfile left, CandidateProfile right, double departure) {
        int comparison = Integer.compare(
                right.scoreProfile().valueAt(departure),
                left.scoreProfile().valueAt(departure));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Double.compare(left.travelTimeAt(departure), right.travelTimeAt(departure));
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(left.edgeCount(), right.edgeCount());
        if (comparison != 0) {
            return comparison;
        }
        return PathPointer.STABLE_PATH_ORDER.compare(left.stablePathId(), right.stablePathId());
    }

    private static CandidateProfile bestCandidateAt(
            List<CandidateProfile> candidates,
            Domain.Interval cell) {
        double departure = cell.start() == cell.end()
                ? cell.start()
                : ProfileCellPartition.midpoint(cell);
        CandidateProfile best = null;
        for (CandidateProfile candidate : candidates) {
            if (!candidate.domain().contains(departure)) {
                continue;
            }
            if (best == null || compareAt(candidate, best, departure) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static CandidateProfile bestCandidateAtPoint(
            List<CandidateProfile> candidates,
            double departure) {
        CandidateProfile best = null;
        for (CandidateProfile candidate : candidates) {
            if (!candidate.domain().contains(departure)) {
                continue;
            }
            if (best == null || compareAt(candidate, best, departure) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<EnvelopeSegment> assignCellExactly(
            List<CandidateProfile> candidates,
            Domain.Interval cell,
            BooleanSupplier cancelled) {
        requireActive(cancelled);
        if (cell.start() == cell.end()) {
            return List.of(new EnvelopeSegment(cell, bestCandidateAtPoint(candidates, cell.start())));
        }
        CandidateProfile interior = bestCandidateAt(candidates, cell);
        CandidateProfile start = cell.startInclusive()
                ? bestCandidateAtPoint(candidates, cell.start())
                : interior;
        CandidateProfile end = cell.endInclusive()
                ? bestCandidateAtPoint(candidates, cell.end())
                : interior;
        boolean startDiffers = cell.startInclusive() && !sameAssignment(start, interior);
        boolean endDiffers = cell.endInclusive() && !sameAssignment(end, interior);
        if (!startDiffers && !endDiffers) {
            return List.of(new EnvelopeSegment(cell, interior));
        }

        List<EnvelopeSegment> result = new ArrayList<>();
        if (startDiffers) {
            result.add(new EnvelopeSegment(
                    new Domain.Interval(cell.start(), cell.start()), start));
        }
        result.add(new EnvelopeSegment(
                new Domain.Interval(
                        cell.start(),
                        cell.end(),
                        cell.startInclusive() && !startDiffers,
                        cell.endInclusive() && !endDiffers),
                interior));
        if (endDiffers) {
            result.add(new EnvelopeSegment(
                    new Domain.Interval(cell.end(), cell.end()), end));
        }
        return result;
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled == null) {
            throw new IllegalArgumentException("cancellation predicate is required");
        }
        if (cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "score envelope construction reached its query deadline");
        }
    }

    private static List<EnvelopeSegment> mergeAdjacentAssignments(List<EnvelopeSegment> raw) {
        List<EnvelopeSegment> merged = new ArrayList<>();
        for (EnvelopeSegment next : raw) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            EnvelopeSegment previous = merged.get(merged.size() - 1);
            if (!previous.sameAssignment(next) || !touchWithoutGap(previous.interval(), next.interval())) {
                merged.add(next);
                continue;
            }
            Domain.Interval union = new Domain.Interval(
                    previous.interval().start(),
                    next.interval().end(),
                    previous.interval().startInclusive(),
                    next.interval().endInclusive());
            if (previous.noPath()) {
                merged.set(merged.size() - 1, new EnvelopeSegment(union, null));
                continue;
            }
            Domain unionDomain = Domain.of(union);
            if (previous.candidate().domain().intersection(unionDomain).equals(unionDomain)) {
                merged.set(merged.size() - 1, new EnvelopeSegment(union, previous.candidate()));
                continue;
            }
            if (next.candidate().domain().intersection(unionDomain).equals(unionDomain)) {
                merged.set(merged.size() - 1, new EnvelopeSegment(union, next.candidate()));
                continue;
            }
            CandidateProfile mergedCandidate = FrontierCompressor.mergeAdjacentCompatible(
                    previous.candidate(),
                    next.candidate());
            if (mergedCandidate == null) {
                merged.add(next);
                continue;
            }
            merged.set(merged.size() - 1, new EnvelopeSegment(union, mergedCandidate));
        }
        return List.copyOf(merged);
    }

    private static boolean touchWithoutGap(Domain.Interval left, Domain.Interval right) {
        return Domain.sameTime(left.end(), right.start())
                && (left.endInclusive() || right.startInclusive());
    }

    private static boolean sameAssignment(CandidateProfile left, CandidateProfile right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.stablePathId().equals(right.stablePathId());
    }

    private static void requireExactContinuousMetadata(CandidateProfile candidate) {
        if (ProfileCellPartition.measure(candidate.domain()) <= 0) {
            return;
        }
        if (!candidate.arrivalProfile().isPiecewise()) {
            throw new IllegalArgumentException(
                    "exact envelope extraction requires piecewise arrival metadata");
        }
        if (!candidate.scoreProfile().isPiecewise()) {
            throw new IllegalArgumentException(
                    "exact envelope extraction requires piecewise score metadata");
        }
    }
}
