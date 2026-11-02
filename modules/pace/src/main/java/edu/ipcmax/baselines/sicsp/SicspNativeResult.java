package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Native interval profiles and optional earliest feasible SICSP selection. */
public record SicspNativeResult(
        Optional<Selection> selection,
        SicspArrivalCandidates arrivalCandidates,
        SicspWeightCandidates weightCandidates,
        SicspArrivalFunction arrivalFunction,
        List<SicspRouteProfile> candidateRouteProfiles,
        List<SicspRouteProfile> retainedRouteProfiles,
        List<SicspProfileDiscrepancy> profileDiscrepancies,
        SicspNativeStatistics statistics) {
    public record Selection(double sourceDeparture, double destinationArrival,
                            double accumulatedWeight, Path path) {
        public Selection {
            Objects.requireNonNull(path, "path");
        }
    }

    public SicspNativeResult {
        selection = Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(arrivalCandidates, "arrivalCandidates");
        Objects.requireNonNull(weightCandidates, "weightCandidates");
        Objects.requireNonNull(arrivalFunction, "arrivalFunction");
        candidateRouteProfiles = List.copyOf(Objects.requireNonNull(candidateRouteProfiles, "candidateRouteProfiles"));
        retainedRouteProfiles = List.copyOf(Objects.requireNonNull(retainedRouteProfiles, "retainedRouteProfiles"));
        profileDiscrepancies = List.copyOf(Objects.requireNonNull(profileDiscrepancies, "profileDiscrepancies"));
        Objects.requireNonNull(statistics, "statistics");
    }
}
