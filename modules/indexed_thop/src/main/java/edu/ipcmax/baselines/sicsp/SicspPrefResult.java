package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.validate.Path;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete SICSP-Pref registry, retained frontier, and preference envelope. */
public record SicspPrefResult(
        EnvelopeProfile envelope,
        SicspPrefCandidateGenerationResult candidateGeneration,
        List<SicspPrefBreakpointCandidate> rawOPoints,
        List<SicspPrefBreakpointCandidate> retainedOPoints,
        List<SicspPrefBreakpointCandidate> dominatedOPoints,
        List<SicspPrefBreakpointCandidate> rawWPoints,
        List<SicspPrefBreakpointCandidate> retainedWPoints,
        List<SicspPrefBreakpointCandidate> dominatedWPoints,
        List<SicspPrefBreakpointCandidate> rejectedNonSimpleEvents,
        List<Path> rawCandidatePaths,
        List<Path> simpleCandidatePaths,
        List<Path> outOfQueryCandidatePaths,
        List<Path> retainedCandidatePaths,
        Map<Path, CandidateProfile> feasibleCandidateProfiles,
        ExactPreferenceEnvelope exactEnvelope,
        Map<Path, ExactPreferencePathProfile> exactCandidateProfiles,
        List<Path> budgetFeasibleRetainedPaths,
        SicspPrefStatistics statistics) {
    public SicspPrefResult {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(candidateGeneration, "candidateGeneration");
        rawOPoints = List.copyOf(rawOPoints);
        retainedOPoints = List.copyOf(retainedOPoints);
        dominatedOPoints = List.copyOf(dominatedOPoints);
        rawWPoints = List.copyOf(rawWPoints);
        retainedWPoints = List.copyOf(retainedWPoints);
        dominatedWPoints = List.copyOf(dominatedWPoints);
        rejectedNonSimpleEvents = List.copyOf(rejectedNonSimpleEvents);
        rawCandidatePaths = List.copyOf(rawCandidatePaths);
        simpleCandidatePaths = List.copyOf(simpleCandidatePaths);
        outOfQueryCandidatePaths = List.copyOf(outOfQueryCandidatePaths);
        retainedCandidatePaths = List.copyOf(retainedCandidatePaths);
        feasibleCandidateProfiles = Map.copyOf(feasibleCandidateProfiles);
        Objects.requireNonNull(exactEnvelope, "exactEnvelope");
        exactCandidateProfiles = Map.copyOf(exactCandidateProfiles);
        budgetFeasibleRetainedPaths = List.copyOf(budgetFeasibleRetainedPaths);
        Objects.requireNonNull(statistics, "statistics");
    }
}
