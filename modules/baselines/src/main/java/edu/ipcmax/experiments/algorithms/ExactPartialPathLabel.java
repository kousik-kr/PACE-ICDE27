package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;

import java.util.List;
import java.util.Set;

/** Whole-function label for one directed partial simple path. */
record ExactPartialPathLabel(
        int currentVertex,
        List<Integer> predecessorArcSequence,
        Set<Integer> visitedVertices,
        ExactTemporalDomain validSourceDepartureSupport,
        ExactArrivalProfile exactPrefixArrival,
        ExactScoreProfile exactPrefixScore,
        ExactTemporalDomain feasibleDepartureSubdomains,
        boolean approximateFunctions) {
    ExactPartialPathLabel {
        predecessorArcSequence = List.copyOf(predecessorArcSequence);
        visitedVertices = Set.copyOf(visitedVertices);
    }
}
