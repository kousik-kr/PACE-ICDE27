package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;

/**
 * Query-local deterministic portfolio for one temporal S-to-v or v-to-D
 * label request.  All connector alternatives are retained in canonical path
 * order; no fastest-only projection is performed.
 */
public record TemporalLabelPortfolio(
        int source,
        int target,
        List<TemporalLabelAlternative> alternatives,
        long expansions,
        long invalidAlternatives,
        boolean capTruncated,
        String deterministicKey) {
    public TemporalLabelPortfolio {
        if (source < 0 || target < 0
                || expansions < 0 || invalidAlternatives < 0) {
            throw new IllegalArgumentException(
                    "invalid temporal label metadata");
        }
        alternatives = alternatives == null
                ? List.of()
                : List.copyOf(alternatives);
        Objects.requireNonNull(deterministicKey, "deterministicKey");
    }

    /** Converts the portfolio back to the legacy connector result facade. */
    public ConnectorResult connectorResult() {
        return new ConnectorResult(
                alternatives.stream()
                        .map(TemporalLabelAlternative::profile)
                        .toList(),
                expansions,
                invalidAlternatives,
                capTruncated);
    }

    public int alternativeCount() {
        return alternatives.size();
    }

    /** Builds an immutable portfolio while preserving every exact alternative. */
    static TemporalLabelPortfolio fromConnectorResult(
            TDGraph graph,
            int source,
            int target,
            double residualBudget,
            ConnectorResult result) {
        List<TemporalLabelAlternative> labels = new ArrayList<>();
        for (CandidateProfile profile : result.connectors()) {
            String key = profile.stablePathId()
                    + "|domain=" + profile.domain()
                    + "|arrival="
                    + profile.arrivalProfile().fingerprint()
                    + "|score="
                    + profile.scoreProfile().fingerprint();
            labels.add(new TemporalLabelAlternative(
                    profile,
                    profile.domain(),
                    residualBudget,
                    profile.vertexMembership(graph, source, target),
                    profile.edgeMembership(),
                    key,
                    result.connectorCapReached()));
        }
        /* ConnectorResult is already produced in the canonical deterministic
         * stream order. Preserve it: changing alternative order can change
         * the first admitted unit when a bounded query reaches M_q. */
        String portfolioKey = source + "->" + target
                + "|alternatives=" + labels.size()
                + "|cap=" + result.connectorCapReached();
        return new TemporalLabelPortfolio(
                source,
                target,
                labels,
                result.expansions(),
                result.invalidConnectors(),
                result.connectorCapReached(),
                portfolioKey);
    }
}
