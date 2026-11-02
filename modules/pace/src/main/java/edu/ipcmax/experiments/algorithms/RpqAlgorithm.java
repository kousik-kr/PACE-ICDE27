package edu.ipcmax.experiments.algorithms;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.IPCMaxOptions;
import edu.ipcmax.core.pcmax.PointPCMaxRunner;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** Repeated exact point queries with documented left-closed sampled cells. */
public final class RpqAlgorithm implements ExperimentAlgorithm {
    @Override
    public String id() {
        return "rpq";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        IPCMaxOptions options = new IPCMaxOptions(0, 0, Math.max(1, graph.nodeCount() - 1),
                true, false, 1, false, 1, 1, config.seed());
        PointPCMaxRunner solver = new PointPCMaxRunner(graph, options);
        CandidateSet reconstructed = new CandidateSet();
        var replay = ExactPathProfileBuilder.context(
                graph, query.departureDomain(), query.maxTravelTime());
        Set<String> distinct = new HashSet<>();
        long pointNanos = 0;
        int pointQueries = 0;
        for (int departure = query.departureStart(); departure <= query.departureEnd();
                departure += config.rpqStepMinutes()) {
            long started = System.nanoTime();
            var result = solver.run(query.source(), query.destination(), departure, query.maxTravelTime());
            pointNanos += System.nanoTime() - started;
            pointQueries++;
            if (!result.found()) {
                continue;
            }
            distinct.add(result.path().arcIds().toString());
            int next = Math.min(query.departureEnd(), departure + config.rpqStepMinutes());
            Domain cell = departure == query.departureEnd()
                    ? Domain.closed(departure, departure)
                    : Domain.halfOpen(departure, next);
            replay.replay(result.path().arcIds(), query.source(), query.destination())
                    .ifPresent(candidate -> {
                        Domain retained = candidate.domain().intersection(cell);
                        if (!retained.isEmpty()) {
                            reconstructed.add(candidate.restrict(retained));
                        }
                    });
        }
        if ((query.departureEnd() - query.departureStart()) % config.rpqStepMinutes() != 0) {
            int departure = query.departureEnd();
            long started = System.nanoTime();
            var result = solver.run(query.source(), query.destination(), departure, query.maxTravelTime());
            pointNanos += System.nanoTime() - started;
            pointQueries++;
            if (result.found()) {
                distinct.add(result.path().arcIds().toString());
                replay.replay(result.path().arcIds(), query.source(), query.destination())
                        .ifPresent(candidate -> {
                            Domain endpoint = candidate.domain().intersection(Domain.closed(departure, departure));
                            if (!endpoint.isEmpty()) {
                                reconstructed.add(candidate.restrict(endpoint));
                            }
                        });
            }
        }
        instrumentation.addCounter("point_queries", pointQueries);
        instrumentation.setTiming("recursive_generation", pointNanos);
        long reconstructionStarted = System.nanoTime();
        var profile = EnvelopeExtractor.extract(reconstructed, query.departureDomain());
        instrumentation.setTiming("breakpoint_construction", System.nanoTime() - reconstructionStarted);
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("point_query_total_ns", pointNanos);
        scalars.put("point_query_average_ns", pointQueries == 0 ? null : pointNanos / pointQueries);
        scalars.put("distinct_sampled_paths", distinct.size());
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                profile, ExactnessScope.NOT_CERTIFIED, scalars, null, null);
    }
}
