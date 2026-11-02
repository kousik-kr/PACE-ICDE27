package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.List;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore;
import edu.ipcmax.core.index.EdgeTemporalSummaryStore.EdgeTemporalSummary;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;

/**
 * Relaxed corridor-wide score-rate upper bound.
 *
 * <p>{@code R_q=max_e(maxScore(e)/minTravel(e))} and
 * {@code UB_C(t)=S_C(t)+(B-T_C(t))*R_q}. Pruning uses strict score
 * inferiority on every exact cell; equality is retained for travel/path tie
 * resolution.</p>
 */
public final class SafeScoreUpperBound {
    private final double corridorMaximumScoreRate;

    public SafeScoreUpperBound(
            QueryCorridor corridor,
            EdgeTemporalSummaryStore summaries) {
        double maximum = 0;
        for (int arcId : corridor.directedArcIds()) {
            EdgeTemporalSummary summary = summaries.summary(arcId);
            if (summary.lowerBoundTravelTime() == 0
                    && summary.maximumScore() > 0) {
                maximum = Double.POSITIVE_INFINITY;
                break;
            }
            maximum = Math.max(
                    maximum,
                    summary.maximumScore()
                            / summary.lowerBoundTravelTime());
        }
        corridorMaximumScoreRate = Double.isFinite(maximum)
                ? Domain.canonicalTime(maximum)
                : Double.POSITIVE_INFINITY;
    }

    public double corridorMaximumScoreRate() {
        return corridorMaximumScoreRate;
    }

    public double upperBound(
            CandidateProfile candidate,
            double departure,
            double budget) {
        if (!candidate.domain().contains(departure)) {
            throw new IllegalArgumentException(
                    "departure is outside candidate domain");
        }
        return upperBound(
                candidate.scoreProfile().valueAt(departure),
                candidate.travelTimeAt(departure),
                budget);
    }

    public double upperBound(
            int currentScore,
            double spentTravelTime,
            double budget) {
        double residual = Math.max(
                0,
                Domain.canonicalTime(budget - spentTravelTime));
        return currentScore
                + residual * corridorMaximumScoreRate;
    }

    /**
     * Returns true only when strict score inferiority is proved over the
     * candidate's complete exact domain.
     */
    public boolean cannotImprove(
            CandidateProfile candidate,
            CandidateSet completed,
            double budget) {
        if (completed.isEmpty()) {
            return false;
        }
        List<Double> cuts =
                new ArrayList<>(candidate.domain().breakpoints());
        candidate.arrivalProfile().breakpoints().forEach(
                value -> cuts.add(value.minute()));
        cuts.addAll(candidate.scoreProfile().breakpoints());
        for (CandidateProfile incumbent : completed.candidates()) {
            cuts.addAll(incumbent.domain().breakpoints());
            cuts.addAll(incumbent.scoreProfile().breakpoints());
        }
        for (Domain.Interval cell : ProfileCellPartition.partition(
                candidate.domain(), cuts)) {
            if (cell.start() == cell.end()) {
                if (!strictlyBelowAt(
                        candidate,
                        completed,
                        cell.start(),
                        budget)) {
                    return false;
                }
                continue;
            }
            double sample = ProfileCellPartition.midpoint(cell);
            int incumbent = incumbentScore(completed, sample);
            if (incumbent == Integer.MIN_VALUE) {
                return false;
            }
            int candidateScore =
                    candidate.scoreProfile().valueAt(sample);
            double leftTravel =
                    candidate.arrivalProfile()
                            .valueAtClosure(cell.start())
                            - cell.start();
            double rightTravel =
                    candidate.arrivalProfile()
                            .valueAtClosure(cell.end())
                            - cell.end();
            if (!(upperBound(candidateScore, leftTravel, budget)
                        < incumbent
                    && upperBound(candidateScore, rightTravel, budget)
                        < incumbent)) {
                return false;
            }
            if (cell.startInclusive()
                    && !strictlyBelowAt(
                            candidate,
                            completed,
                            cell.start(),
                            budget)) {
                return false;
            }
            if (cell.endInclusive()
                    && !strictlyBelowAt(
                            candidate,
                            completed,
                            cell.end(),
                            budget)) {
                return false;
            }
        }
        return true;
    }

    private boolean strictlyBelowAt(
            CandidateProfile candidate,
            CandidateSet completed,
            double departure,
            double budget) {
        int incumbent = incumbentScore(completed, departure);
        return incumbent != Integer.MIN_VALUE
                && upperBound(candidate, departure, budget)
                    < incumbent;
    }

    private static int incumbentScore(
            CandidateSet completed,
            double departure) {
        int maximum = Integer.MIN_VALUE;
        for (CandidateProfile candidate : completed.candidates()) {
            if (candidate.domain().contains(departure)) {
                maximum = Math.max(
                        maximum,
                        candidate.scoreProfile().valueAt(departure));
            }
        }
        return maximum;
    }
}
