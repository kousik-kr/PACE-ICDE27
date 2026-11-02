package edu.ipcmax.experiments.algorithms;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.SafeProfileDominance;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;
import edu.ipcmax.experiments.framework.LimitExceededException;

/** Exact direct temporal profile-label expansion without anchor decomposition. */
public final class ProfileLabelingAlgorithm implements ExperimentAlgorithm {
    @Override
    public String id() {
        return "pl-exact";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        ArrayDeque<Label> queue = new ArrayDeque<>();
        queue.add(new Label(query.source(), List.of(), Set.of(query.source()), null));
        Map<Integer, List<Label>> frontiers = new HashMap<>();
        var replay = ExactPathProfileBuilder.context(
                graph, query.departureDomain(), query.maxTravelTime());
        CandidateSet destinations = new CandidateSet();
        long labels = 1;
        long expansions = 0;
        while (!queue.isEmpty()) {
            Label label = queue.removeFirst();
            if (label.node == query.destination()) {
                replay.replay(label.arcs, query.source(), query.destination()).ifPresent(destinations::add);
                continue;
            }
            for (Edge edge : graph.outgoingEdges(label.node)) {
                if (label.vertices.contains(edge.target())) {
                    continue;
                }
                if (++expansions > config.maxExpansions()) {
                    throw new LimitExceededException("maximum label expansions exceeded: "
                            + config.maxExpansions());
                }
                List<Integer> arcs = new ArrayList<>(label.arcs);
                arcs.add(edge.arcId());
                var replayed = replay.replay(arcs, query.source(), edge.target());
                if (replayed.isEmpty()) {
                    continue;
                }
                if (++labels > config.maxLabels()) {
                    throw new LimitExceededException("maximum labels exceeded: " + config.maxLabels());
                }
                Set<Integer> vertices = new HashSet<>(label.vertices);
                vertices.add(edge.target());
                Label next = new Label(edge.target(), List.copyOf(arcs), Set.copyOf(vertices), replayed.get());
                List<Label> frontier = frontiers.computeIfAbsent(edge.target(), ignored -> new ArrayList<>());
                long dominanceStarted = System.nanoTime();
                if (frontier.stream().anyMatch(existing -> safelyDominates(
                        graph, existing.profile, next.profile, query.source(), edge.target()))) {
                    instrumentation.increment("extension_dominated_fragments_removed");
                    instrumentation.addTiming("safe_dominance", System.nanoTime() - dominanceStarted);
                    continue;
                }
                List<Label> removed = frontier.stream().filter(existing -> safelyDominates(
                        graph, next.profile, existing.profile, query.source(), edge.target())).toList();
                frontier.removeAll(removed);
                queue.removeAll(removed);
                instrumentation.addCounter("extension_dominated_fragments_removed", removed.size());
                instrumentation.addTiming("safe_dominance", System.nanoTime() - dominanceStarted);
                frontier.add(next);
                queue.addLast(next);
            }
        }
        instrumentation.addCounter("labels_created", labels);
        instrumentation.addCounter("labels_expanded", expansions);
        instrumentation.addCounter("candidates_generated", destinations.size());
        var profile = EnvelopeExtractor.extract(destinations, query.departureDomain());
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                profile, ExactnessScope.NOT_CERTIFIED, Map.of(), null, null);
    }

    private static boolean safelyDominates(
            TDGraph graph, CandidateProfile left, CandidateProfile right, int source, int destination) {
        if (!left.domain().equals(right.domain())) {
            return false;
        }
        List<Double> cuts = new ArrayList<>(left.domain().breakpoints());
        left.arrivalProfile().breakpoints().forEach(point -> cuts.add(point.minute()));
        right.arrivalProfile().breakpoints().forEach(point -> cuts.add(point.minute()));
        cuts.addAll(left.scoreProfile().breakpoints());
        cuts.addAll(right.scoreProfile().breakpoints());
        for (var cell : left.domain().splitAt(cuts).intervals()) {
            if (!SafeProfileDominance.dominates(graph, left, right, cell, source, destination)) {
                return false;
            }
        }
        return true;
    }

    private record Label(
            int node, List<Integer> arcs, Set<Integer> vertices, CandidateProfile profile) {
    }
}
