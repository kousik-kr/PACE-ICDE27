package edu.ipcmax.experiments.algorithms;

import java.util.LinkedHashMap;
import java.util.Map;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** Reduced-output interval-best baseline plus its evaluation-only path profile. */
public final class IntervalBestAlgorithm implements ExperimentAlgorithm {
    @Override
    public String id() {
        return "interval-best";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        AlgorithmResult exhaustive = new ExhaustiveProfileAlgorithm().run(
                graph, query, config, instrumentation);
        var selected = exhaustive.profile().bestResult(
                new ExactPathValidator(graph), query.source(), query.destination(),
                query.maxTravelTime(), query::isOnGrid);
        CandidateSet evaluation = new CandidateSet();
        Map<String, Object> scalars = new LinkedHashMap<>();
        if (selected.found()) {
            ExactPathProfileBuilder.replay(graph, selected.path().arcIds(), query.source(),
                    query.destination(), query.departureDomain(), query.maxTravelTime())
                    .ifPresent(evaluation::add);
            scalars.put("selected_departure_time", selected.departureTime());
            scalars.put("selected_path_id", selected.path().arcIds());
            scalars.put("selected_score", selected.score());
            scalars.put("selected_travel_time", selected.travelTime());
        }
        var evaluationProfile = EnvelopeExtractor.extract(evaluation, query.departureDomain());
        return new AlgorithmResult(
                selected.found() ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                evaluationProfile, ExactnessScope.NOT_CERTIFIED, scalars, null, null);
    }
}
