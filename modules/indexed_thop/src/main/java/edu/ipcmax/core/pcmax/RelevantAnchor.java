package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;

/**
 * A safely relevant anchor together with the exact quantities used by PACE-B ranking.
 */
public record RelevantAnchor(
        Anchor anchor,
        Domain relevantValidDomain,
        Domain relevantPositiveDomain,
        double scorePotential,
        double positiveCoverage,
        double slack,
        double detour) {
}
