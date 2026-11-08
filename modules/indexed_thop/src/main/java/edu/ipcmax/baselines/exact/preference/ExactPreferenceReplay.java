package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Builds exact no-wait rational arrival and preference profiles for one route. */
public final class ExactPreferenceReplay {
    private ExactPreferenceReplay() { }

    /** Public decimal bounds and budget are canonicalized once by their existing input boundary. */
    public static Optional<ExactPreferencePathProfile> replay(
            TDGraph graph, int source, int destination, Path path, Domain departureDomain, double budget) {
        if (!Double.isFinite(budget) || budget < 0) throw new IllegalArgumentException("invalid budget");
        return replayExact(graph, source, destination, path,
                ExactTemporalDomain.fromLegacy(departureDomain),
                ExactTime.fromCanonicalDouble(Domain.canonicalTime(budget)));
    }

    public static Optional<ExactPreferencePathProfile> replayExact(
            TDGraph graph, int source, int destination, Path path,
            ExactTemporalDomain departureDomain, ExactTime budget) {
        return replayExact(graph, source, destination, path, departureDomain, budget, () -> false);
    }

    /** Cancellable exact replay; checks between edge and profile operations. */
    public static Optional<ExactPreferencePathProfile> replayExact(
            TDGraph graph, int source, int destination, Path path,
            ExactTemporalDomain departureDomain, ExactTime budget,
            BooleanSupplier cancellationRequested) {
        if (budget.signum() < 0) throw new IllegalArgumentException("invalid exact budget");
        ExactPreferenceTransferProfile transfer = replayTransferExact(
                graph, source, destination, path, departureDomain, cancellationRequested);
        if (transfer.arrival().isEmpty()) return Optional.empty();
        ExactTravelBudgetDomain budgetDomain;
        ExactTemporalDomain feasible;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_budget_domain_restriction")) {
            budgetDomain = ExactTravelBudgetDomain.compute(
                    transfer.arrival(), budget, cancellationRequested);
            feasible = transfer.arrival().domain().intersection(
                    budgetDomain.domain(), cancellationRequested);
        }
        checkCancelled(cancellationRequested);
        return Optional.of(new ExactPreferencePathProfile(path, transfer.arrival(), transfer.score(), feasible,
                budget, transfer.scoreEventPreimages(), budgetDomain.rootOperations()));
    }

    /**
     * Replays a complete structural connector on its exact entry-time domain.
     * This uses the same temporal arithmetic as source-root route replay but
     * deliberately applies no route travel-time budget to the connector.
     */
    public static ExactPreferenceTransferProfile replayTransferExact(
            TDGraph graph, int source, int destination, Path path,
            ExactTemporalDomain entryDomain, BooleanSupplier cancellationRequested) {
        ExactArithmeticDiagnostics.increment("exact_replay_paths_started");
        ExactPreferenceTransferProfile result = replayTransferInternal(
                graph, source, destination, path, entryDomain, cancellationRequested);
        checkCancelled(cancellationRequested);
        ExactArithmeticDiagnostics.increment("exact_replay_paths_completed");
        return result;
    }

    private static ExactPreferenceTransferProfile replayTransferInternal(
            TDGraph graph, int source, int destination, Path path,
            ExactTemporalDomain departureDomain, BooleanSupplier cancellationRequested) {
        checkCancelled(cancellationRequested);
        graph.node(source);
        graph.node(destination);
        validatePath(graph, source, destination, path, cancellationRequested);
        if (ExactReplayEngine.mode() == ExactReplayEngine.Mode.BALANCED_V1
                && path.arcIds().size() >= ExactReplayEngine.minimumArcs()) {
            try {
                return ExactBalancedReplay.replay(graph, path, departureDomain, ExactReplayEngine.leafEdges(),
                        cancellationRequested);
            } catch (ExactBalancedReplay.BandNotCoveringException notCovered) {
                // A band missed an entry time (never expected): the reference replay decides the result.
                ExactArithmeticDiagnostics.increment("exact_balanced_replay_fallbacks");
            }
        }
        ExactPreferenceTransferProfile result = replayEdges(graph, source, path, departureDomain, false,
                cancellationRequested);
        int current = path.arcIds().isEmpty() ? source : graph.edges().get(path.arcIds().getLast()).target();
        if (current != destination) throw new IllegalArgumentException("path does not reach destination");
        return result;
    }

    /**
     * Edge-by-edge exact replay of an already validated path from {@code startVertex}: the sequential
     * replay. With {@code cachedEdgeProfiles} the per-edge exact pieces come from the shared edge
     * cache instead of being rebuilt, which cannot change them.
     */
    static ExactPreferenceTransferProfile replayEdges(
            TDGraph graph, int startVertex, Path path, ExactTemporalDomain departureDomain,
            boolean cachedEdgeProfiles, BooleanSupplier cancellationRequested) {
        ExactArrivalProfile prefix = ExactArrivalProfile.identity(departureDomain, cancellationRequested);
        ExactScoreProfile accumulated = null;
        long scoreEvents = 0;
        int current = startVertex;
        for (int arcId : path.arcIds()) {
            checkCancelled(cancellationRequested);
            ExactArithmeticDiagnostics.increment("exact_replay_edges_started");
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) throw new IllegalArgumentException("disconnected path at arc " + arcId);
            ExactEdgeProfiles.Profiles shared = cachedEdgeProfiles ? ExactEdgeProfiles.of(edge, cancellationRequested) : null;
            ExactScoreProfile edgeScore = shared != null ? shared.score() : ExactScoreProfile.edgeScore(edge, cancellationRequested);
            ExactTemporalDomain scorePreimage = prefix.preimage(edgeScore.domain(), cancellationRequested);
            ExactTemporalDomain scoreSupport = prefix.domain().intersection(scorePreimage, cancellationRequested);
            if (scoreSupport.isEmpty()) {
                return emptyTransfer(path, departureDomain);
            }
            ExactArrivalProfile scoreEntryMap = prefix.restrict(scoreSupport, cancellationRequested);
            ExactScoreProfile edgeScoreOnDeparture = edgeScore.pullback(scoreEntryMap, cancellationRequested);
            scoreEvents += Math.max(0, edgeScoreOnDeparture.pieceCount() - 1);
            if (accumulated == null) accumulated = edgeScoreOnDeparture;
            else accumulated = accumulated.restrict(scoreSupport, cancellationRequested)
                    .plus(edgeScoreOnDeparture, cancellationRequested);

            ExactArrivalProfile edgeArrival = shared != null ? shared.arrival() : ExactArrivalProfile.edgeArrival(edge, cancellationRequested);
            ExactTemporalDomain travelPreimage = prefix.preimage(edgeArrival.domain(), cancellationRequested);
            ExactTemporalDomain nextSupport = scoreSupport.intersection(travelPreimage, cancellationRequested);
            if (nextSupport.isEmpty()) {
                return emptyTransfer(path, departureDomain);
            }
            accumulated = accumulated.restrict(nextSupport, cancellationRequested);
            prefix = prefix.restrict(nextSupport, cancellationRequested)
                    .composeAfter(edgeArrival, cancellationRequested);
            checkCancelled(cancellationRequested);
            if (prefix.isEmpty()) {
                return emptyTransfer(path, departureDomain);
            }
            current = edge.target();
            ExactArithmeticDiagnostics.increment("exact_replay_edges_completed");
        }
        if (accumulated == null) accumulated = ExactScoreProfile.constant(prefix.domain(), java.math.BigInteger.ZERO, cancellationRequested);
        accumulated = accumulated.restrict(prefix.domain(), cancellationRequested);
        if (!prefix.domain().equals(accumulated.domain())) {
            throw new IllegalStateException("exact path score support differs from arrival support");
        }
        checkCancelled(cancellationRequested);
        return new ExactPreferenceTransferProfile(path, departureDomain, prefix, accumulated, scoreEvents);
    }

    private static ExactPreferenceTransferProfile emptyTransfer(Path path, ExactTemporalDomain requested) {
        return new ExactPreferenceTransferProfile(path, requested, ExactArrivalProfile.empty(),
                ExactScoreProfile.empty(), 0);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("exact preference replay cancelled");
        }
    }

    /** Replays one canonical decimal departure exactly, without imposing a budget. */
    public static Optional<PointReplay> replayAt(
            TDGraph graph, int source, int destination, Path path, double departure) {
        ExactTime exact = ExactTime.fromCanonicalDouble(Domain.canonicalTime(departure));
        var profile = replayExact(graph, source, destination, path,
                ExactTemporalDomain.of(ExactTemporalDomain.Interval.singleton(exact)), ExactTime.ZERO);
        if (profile.isEmpty() || !profile.orElseThrow().arrival().domain().contains(exact)) return Optional.empty();
        ExactPreferencePathProfile result = profile.orElseThrow();
        return Optional.of(new PointReplay(result.arrival().valueAtExact(exact), result.score().valueAt(exact)));
    }

    public record PointReplay(ExactTime arrival, java.math.BigInteger score) { }

    private static void validatePath(TDGraph graph, int source, int destination, Path path,
            BooleanSupplier cancellationRequested) {
        if (path.arcIds().isEmpty()) {
            if (source != destination) throw new IllegalArgumentException("empty route requires identical endpoints");
            return;
        }
        if (source == destination) throw new IllegalArgumentException("nonempty identity route is not loopless");
        Set<Integer> visited = new LinkedHashSet<>();
        visited.add(source);
        int current = source;
        for (int arcId : path.arcIds()) {
            checkCancelled(cancellationRequested);
            if (arcId < 0 || arcId >= graph.edgeCount()) throw new IllegalArgumentException("unknown arc id " + arcId);
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) throw new IllegalArgumentException("disconnected path at arc " + arcId);
            if (!visited.add(edge.target())) throw new IllegalArgumentException("path is not vertex-simple");
            current = edge.target();
        }
        if (current != destination) throw new IllegalArgumentException("path does not reach destination");
    }
}
