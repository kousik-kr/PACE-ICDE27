package edu.ipcmax.experiments.algorithms;

import java.util.LinkedHashMap;
import java.util.Map;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.EnvelopeExtractor;
import edu.ipcmax.core.pcmax.ExactPathProfileBuilder;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.experiments.framework.AlgorithmConfig;
import edu.ipcmax.experiments.framework.AlgorithmResult;
import edu.ipcmax.experiments.framework.ExactnessScope;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentInstrumentation;
import edu.ipcmax.experiments.framework.ExperimentStatus;

/** Independent exhaustive full-graph profile oracle for tiny graphs. */
public final class ExhaustiveProfileAlgorithm implements ExperimentAlgorithm {
    @Override
    public String id() {
        return "exh-profile";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long started = System.nanoTime();
        SimplePathSearch.SearchResult search = SimplePathSearch.exhaustive(
                graph, query.source(), query.destination(), query.maxTravelTime(),
                config.maxEnumeratedPaths());
        instrumentation.setTiming("connector_generation", System.nanoTime() - started);
        CandidateSet candidates = new CandidateSet();
        var replay = ExactPathProfileBuilder.context(
                graph, query.departureDomain(), query.maxTravelTime());
        long rejectedDomain = 0;
        for (SimplePathSearch.WeightedPath path : search.paths()) {
            var candidate = replay.replay(path.arcs(), query.source(), query.destination());
            if (candidate.isPresent()) {
                candidates.add(candidate.get());
            } else {
                rejectedDomain++;
            }
        }
        instrumentation.addCounter("simple_paths_enumerated", search.paths().size());
        instrumentation.addCounter("connectors_rejected_lower_bound", search.rejectedLowerBound());
        instrumentation.addCounter("connectors_rejected_empty_domain", rejectedDomain);
        instrumentation.addCounter("candidates_generated", candidates.size());
        long envelopeStarted = System.nanoTime();
        var profile = EnvelopeExtractor.extract(candidates, query.departureDomain());
        instrumentation.setTiming("envelope_extraction", System.nanoTime() - envelopeStarted);
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("paths_enumerated", search.paths().size());
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                profile, ExactnessScope.NOT_CERTIFIED, scalars, null, null);
    }
}
