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

/** Static lower-bound k-shortest full-graph path profile baseline. */
public final class KspProfileAlgorithm implements ExperimentAlgorithm {
    @Override
    public String id() {
        return "ksp-profile";
    }

    @Override
    public AlgorithmResult run(
            TDGraph graph, QuerySpec query, AlgorithmConfig config,
            ExperimentInstrumentation instrumentation) {
        long cap = Math.min(config.maxEnumeratedPaths(), Math.max(config.baselineK() * 100L, 100L));
        var search = SimplePathSearch.lowerBoundOrder(
                graph, query.source(), query.destination(), query.maxTravelTime(), cap);
        CandidateSet retained = new CandidateSet();
        var replay = ExactPathProfileBuilder.context(
                graph, query.departureDomain(), query.maxTravelTime());
        long rejectedDomain = 0;
        long enumerated = 0;
        for (SimplePathSearch.WeightedPath path : search.paths()) {
            enumerated++;
            var candidate = replay.replay(path.arcs(), query.source(), query.destination());
            if (candidate.isEmpty()) {
                rejectedDomain++;
                continue;
            }
            retained.add(candidate.get());
            if (retained.size() == config.baselineK()) {
                break;
            }
        }
        if (retained.size() < config.baselineK() && search.limitReached()) {
            throw new edu.ipcmax.experiments.framework.LimitExceededException(
                    "maximum KSP enumerated-path guard exceeded before retaining k paths: " + cap);
        }
        instrumentation.addCounter("simple_paths_enumerated", enumerated);
        instrumentation.addCounter("connectors_rejected_lower_bound", search.rejectedLowerBound());
        instrumentation.addCounter("connectors_rejected_empty_domain", rejectedDomain);
        instrumentation.addCounter("ksp_paths_retained", retained.size());
        var profile = EnvelopeExtractor.extract(retained, query.departureDomain());
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("paths_enumerated", enumerated);
        scalars.put("paths_rejected_empty_domain", rejectedDomain);
        scalars.put("paths_retained", retained.size());
        boolean feasible = profile.segments().stream().anyMatch(segment -> segment.found());
        return new AlgorithmResult(
                feasible ? ExperimentStatus.COMPLETED : ExperimentStatus.NO_FEASIBLE_PATH,
                profile, ExactnessScope.RETAINED_FRONTIER, scalars, null, null);
    }
}
