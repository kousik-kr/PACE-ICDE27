package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.core.validate.Path;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A complete path's exact transfer functions parameterized by entry time.
 * No source-root travel budget is imposed on a connector. Requested coverage
 * includes unsupported times: an empty output there is a completed evaluation,
 * not permission to reuse the transfer on an unevaluated domain.
 */
public record ExactPreferenceTransferProfile(
        Path path,
        ExactTemporalDomain requestedDomain,
        ExactArrivalProfile arrival,
        ExactScoreProfile score,
        long scoreEventPreimages) {
    public ExactPreferenceTransferProfile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(requestedDomain, "requested domain");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(score, "score");
        if (!arrival.domain().equals(score.domain())) {
            throw new IllegalArgumentException("transfer arrival and preference supports differ");
        }
        if (!arrival.domain().difference(requestedDomain).isEmpty()) {
            throw new IllegalArgumentException("transfer support lies outside its evaluated domain");
        }
    }

    public ExactPreferenceTransferProfile restrict(
            ExactTemporalDomain requested, BooleanSupplier cancellationRequested) {
        if (!requested.difference(requestedDomain, cancellationRequested).isEmpty()) {
            throw new IllegalArgumentException("cannot restrict transfer to unevaluated times");
        }
        return new ExactPreferenceTransferProfile(path, requested,
                arrival.restrict(requested, cancellationRequested),
                score.restrict(requested, cancellationRequested), scoreEventPreimages);
    }
}
