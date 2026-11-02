package edu.ipcmax.core.pcmax;

import java.util.List;

import edu.ipcmax.core.profile.CandidateProfile;

/** Completion-bearing deterministic connector stream result. */
public record ConnectorResult(
        List<CandidateProfile> connectors,
        long expansions,
        long invalidConnectors,
        boolean connectorCapReached) {
    public ConnectorResult {
        connectors = List.copyOf(connectors);
        if (expansions < 0 || invalidConnectors < 0) {
            throw new IllegalArgumentException(
                    "connector counters cannot be negative");
        }
    }
}
