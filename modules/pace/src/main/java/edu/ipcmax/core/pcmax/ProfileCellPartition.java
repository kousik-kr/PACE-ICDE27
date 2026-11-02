package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Exact temporal-cell construction shared by frontier compression and envelope extraction.
 */
final class ProfileCellPartition {
    private ProfileCellPartition() {
    }

    static List<Domain.Interval> cells(
            Domain domain,
            List<CandidateProfile> candidates,
            boolean includeTravelEqualityRoots) {
        return cells(
                domain,
                candidates,
                includeTravelEqualityRoots,
                PaceExecutionMetrics.none());
    }

    static List<Domain.Interval> cells(
            Domain domain,
            List<CandidateProfile> candidates,
            boolean includeTravelEqualityRoots,
            PaceExecutionMetrics metrics) {
        return cells(
                domain,
                candidates,
                includeTravelEqualityRoots,
                metrics,
                () -> false);
    }

    static List<Domain.Interval> cells(
            Domain domain,
            List<CandidateProfile> candidates,
            boolean includeTravelEqualityRoots,
            PaceExecutionMetrics metrics,
            BooleanSupplier cancelled) {
        if (cancelled == null) {
            throw new IllegalArgumentException("cancellation predicate is required");
        }
        List<Double> cuts;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.BREAKPOINT_PROCESSING)) {
            cuts = new ArrayList<>(domain.breakpoints());
            for (CandidateProfile candidate : candidates) {
                requireActive(cancelled);
                cuts.addAll(candidate.domain().breakpoints());
                for (TimeProfile.Breakpoint breakpoint :
                        candidate.arrivalProfile().breakpoints()) {
                    cuts.add(breakpoint.minute());
                }
                cuts.addAll(candidate.scoreProfile().breakpoints());
            }
            metrics.addCounter("breakpoints_processed", cuts.size());
        }
        if (includeTravelEqualityRoots) {
            try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                    PaceExecutionMetrics.EQUALITY_ROOTS)) {
                for (int i = 0; i < candidates.size(); i++) {
                    for (int j = i + 1; j < candidates.size(); j++) {
                        requireActive(cancelled);
                        metrics.increment("candidate_pair_root_checks");
                        List<Double> roots = travelEqualityBreakpoints(
                                candidates.get(i),
                                candidates.get(j),
                                domain,
                                cancelled);
                        metrics.addCounter(
                                "equality_roots_created", roots.size());
                        cuts.addAll(roots);
                    }
                }
            }
        }
        List<Domain.Interval> result;
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.BREAKPOINT_PROCESSING)) {
            result = partition(domain, cuts);
        }
        requireActive(cancelled);
        metrics.addCounter("temporal_cells_created", result.size());
        return result;
    }

    static List<Domain.Interval> partition(Domain domain, List<Double> cutPoints) {
        return domain.splitAt(uniqueSorted(cutPoints)).intervals();
    }

    static List<Double> travelEqualityBreakpoints(
            CandidateProfile left,
            CandidateProfile right,
            Domain containingDomain) {
        return travelEqualityBreakpoints(
                left, right, containingDomain, () -> false);
    }

    static List<Double> travelEqualityBreakpoints(
            CandidateProfile left,
            CandidateProfile right,
            Domain containingDomain,
            BooleanSupplier cancelled) {
        Domain overlap = containingDomain.intersection(left.domain()).intersection(right.domain());
        if (overlap.isEmpty()) {
            return List.of();
        }
        requireExact(left, overlap);
        requireExact(right, overlap);

        List<Double> baseCuts = new ArrayList<>(overlap.breakpoints());
        for (TimeProfile.Breakpoint breakpoint : left.arrivalProfile().breakpoints()) {
            baseCuts.add(breakpoint.minute());
        }
        for (TimeProfile.Breakpoint breakpoint : right.arrivalProfile().breakpoints()) {
            baseCuts.add(breakpoint.minute());
        }
        baseCuts.addAll(left.scoreProfile().breakpoints());
        baseCuts.addAll(right.scoreProfile().breakpoints());

        List<Double> roots = new ArrayList<>();
        for (Domain.Interval cell : partition(overlap, baseCuts)) {
            requireActive(cancelled);
            if (cell.end() <= cell.start()) {
                continue;
            }
            if (left.scoreProfile().valueAtClosure(
                            cell.start())
                    != right.scoreProfile().valueAtClosure(
                            cell.start())) {
                continue;
            }
            double startDifference = left.arrivalProfile().valueAtClosure(cell.start())
                    - right.arrivalProfile().valueAtClosure(cell.start());
            double endDifference = left.arrivalProfile().valueAtClosure(cell.end())
                    - right.arrivalProfile().valueAtClosure(cell.end());
            if (Domain.sameTime(startDifference, 0)) {
                roots.add(cell.start());
            }
            if (Domain.sameTime(endDifference, 0)) {
                roots.add(cell.end());
            }
            if (startDifference * endDifference < 0.0) {
                double root = cell.start()
                        + (-startDifference * (cell.end() - cell.start())
                        / (endDifference - startDifference));
                roots.add(root);
            }
        }
        return uniqueSorted(roots);
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "temporal envelope construction reached its query deadline");
        }
    }

    static boolean covers(Domain domain, Domain.Interval cell) {
        Domain requested = Domain.of(cell);
        return domain.intersection(requested).equals(requested);
    }

    static double midpoint(Domain.Interval interval) {
        long start = Domain.canonicalTick(
                interval.start());
        long end = Domain.canonicalTick(
                interval.end());
        return Domain.timeFromTick(
                start + ((end - start) / 2));
    }

    static double measure(Domain domain) {
        double result = 0.0;
        for (Domain.Interval interval : domain.intervals()) {
            result += Math.max(0.0, interval.end() - interval.start());
        }
        return result;
    }

    static List<Double> uniqueSorted(List<Double> points) {
        TreeSet<Long> ticks = new TreeSet<>();
        for (double point : points) {
            if (Double.isFinite(point)) {
                ticks.add(Domain.canonicalTick(point));
            }
        }
        return ticks.stream().map(Domain::timeFromTick).toList();
    }

    private static void requireExact(CandidateProfile candidate, Domain domain) {
        boolean positiveMeasure = measure(domain) > 0;
        if (positiveMeasure && !candidate.arrivalProfile().isPiecewise()) {
            throw new IllegalArgumentException("exact cell partition requires piecewise arrival metadata");
        }
        if (positiveMeasure && !candidate.scoreProfile().isPiecewise()) {
            throw new IllegalArgumentException("exact cell partition requires piecewise score metadata");
        }
    }
}
