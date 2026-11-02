package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Exact source-root state of a route prefix: arrival {@code A(t)} at the
 * prefix end and accumulated edge-entry preference {@code S(t)} on the
 * departures for which every prefix edge is supported.
 *
 * <p>{@link #then} appends a whole connector or pivot transfer with the same
 * preimage, restriction, pullback and composition steps that
 * {@link ExactPreferenceReplay} performs per edge. The transfer must have been
 * evaluated on every entry time the prefix can produce; this is checked
 * exactly, so a cached transfer can never silently omit an entry time.</p>
 */
public final class ExactPreferencePrefixProfile {
    private final ExactTemporalDomain departureDomain;
    private final ExactArrivalProfile arrival;
    /** {@code null} before the first transfer, mirroring edge-by-edge replay. */
    private final ExactScoreProfile score;
    private final long scoreEventPreimages;
    private final int arcCount;

    private ExactPreferencePrefixProfile(ExactTemporalDomain departureDomain, ExactArrivalProfile arrival,
            ExactScoreProfile score, long scoreEventPreimages, int arcCount) {
        this.departureDomain = departureDomain;
        this.arrival = arrival;
        this.score = score;
        this.scoreEventPreimages = scoreEventPreimages;
        this.arcCount = arcCount;
    }

    /** The empty route at the source: identity arrival on the requested departures. */
    public static ExactPreferencePrefixProfile identity(
            ExactTemporalDomain departures, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(departures, "departures");
        return new ExactPreferencePrefixProfile(departures,
                ExactArrivalProfile.identity(departures, cancellationRequested), null, 0, 0);
    }

    /**
     * A transfer viewed as a prefix state: its entry domain plays the departure domain, so
     * {@link #then} composes a following transfer onto it. The result is again a transfer
     * ({@link #toTransfer}), which is how balanced replay composes segments pairwise.
     */
    public static ExactPreferencePrefixProfile ofTransfer(ExactPreferenceTransferProfile transfer) {
        Objects.requireNonNull(transfer, "transfer");
        return new ExactPreferencePrefixProfile(transfer.requestedDomain(), transfer.arrival(), transfer.score(),
                transfer.scoreEventPreimages(), transfer.path().arcIds().size());
    }

    /** This state as the transfer of {@code path} on the departure domain it was built from. */
    public ExactPreferenceTransferProfile toTransfer(Path path) {
        Objects.requireNonNull(path, "path");
        if (path.arcIds().size() != arcCount) throw new IllegalArgumentException("path does not match composed prefix");
        if (arrival.isEmpty()) {
            return new ExactPreferenceTransferProfile(path, departureDomain, arrival, ExactScoreProfile.empty(), 0);
        }
        return new ExactPreferenceTransferProfile(path, departureDomain, arrival,
                score == null ? ExactScoreProfile.constant(arrival.domain(), BigInteger.ZERO, null) : score,
                scoreEventPreimages);
    }

    public ExactTemporalDomain departureDomain() { return departureDomain; }
    public ExactArrivalProfile arrival() { return arrival; }
    /** Accumulated preference; empty before the first transfer. */
    public Optional<ExactScoreProfile> accumulatedScore() { return Optional.ofNullable(score); }
    public boolean isEmpty() { return arrival.isEmpty(); }
    public int arcCount() { return arcCount; }
    public long scoreEventPreimages() { return scoreEventPreimages; }

    /** Exact set of entry times at the prefix end, including singleton and open endpoints. */
    public ExactTemporalDomain requiredEntryDomain(BooleanSupplier cancellationRequested) {
        return arrival.imageDomain(cancellationRequested);
    }

    /** Appends a transfer evaluated on a superset of this prefix's exact entry domain. */
    public ExactPreferencePrefixProfile then(
            ExactPreferenceTransferProfile transfer, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(transfer, "transfer");
        checkCancelled(cancellationRequested);
        int arcs = arcCount + transfer.path().arcIds().size();
        if (arrival.isEmpty()) return emptyAfter(arcs);
        ExactTemporalDomain required = requiredEntryDomain(cancellationRequested);
        if (!required.difference(transfer.requestedDomain(), cancellationRequested).isEmpty()) {
            throw new IllegalArgumentException("transfer was not evaluated on every prefix entry time");
        }
        if (transfer.path().arcIds().isEmpty()) return this;
        ExactArithmeticDiagnostics.increment("exact_prefix_transfer_compositions");
        ExactTemporalDomain support = arrival.domain().intersection(
                arrival.preimage(transfer.arrival().domain(), cancellationRequested), cancellationRequested);
        if (support.isEmpty()) return emptyAfter(arcs);
        ExactArrivalProfile entryMap = arrival.restrict(support, cancellationRequested);
        ExactScoreProfile pulled = transfer.score().pullback(entryMap, cancellationRequested);
        ExactScoreProfile accumulated = score == null ? pulled
                : score.restrict(support, cancellationRequested).plus(pulled, cancellationRequested);
        ExactArrivalProfile next = entryMap.composeAfter(transfer.arrival(), cancellationRequested);
        checkCancelled(cancellationRequested);
        if (next.isEmpty()) return emptyAfter(arcs);
        return new ExactPreferencePrefixProfile(departureDomain, next,
                accumulated.restrict(next.domain(), cancellationRequested),
                scoreEventPreimages + transfer.scoreEventPreimages(), arcs);
    }

    /**
     * Applies the route travel-time budget. Returns empty exactly when the
     * composed route is unsupported on every requested departure, matching
     * {@link ExactPreferenceReplay#replayExact}.
     */
    public Optional<ExactPreferencePathProfile> complete(
            Path path, ExactTime budget, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(budget, "budget");
        if (budget.signum() < 0) throw new IllegalArgumentException("invalid exact budget");
        if (path.arcIds().size() != arcCount) {
            throw new IllegalArgumentException("completed path does not match composed prefix");
        }
        if (arrival.isEmpty()) return Optional.empty();
        ExactScoreProfile finalScore = score == null
                ? ExactScoreProfile.constant(arrival.domain(), BigInteger.ZERO, cancellationRequested)
                : score.restrict(arrival.domain(), cancellationRequested);
        if (!arrival.domain().equals(finalScore.domain())) {
            throw new IllegalStateException("exact prefix score support differs from arrival support");
        }
        ExactTravelBudgetDomain budgetDomain = ExactTravelBudgetDomain.compute(
                arrival, budget, cancellationRequested);
        ExactTemporalDomain feasible = arrival.domain().intersection(
                budgetDomain.domain(), cancellationRequested);
        checkCancelled(cancellationRequested);
        return Optional.of(new ExactPreferencePathProfile(path, arrival, finalScore, feasible,
                budget, scoreEventPreimages, budgetDomain.rootOperations()));
    }

    private ExactPreferencePrefixProfile emptyAfter(int arcs) {
        return new ExactPreferencePrefixProfile(departureDomain, ExactArrivalProfile.empty(),
                ExactScoreProfile.empty(), scoreEventPreimages, arcs);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("exact prefix composition cancelled");
        }
    }
}
