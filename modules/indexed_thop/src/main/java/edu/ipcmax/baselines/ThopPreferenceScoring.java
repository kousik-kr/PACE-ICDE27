package edu.ipcmax.baselines;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Preference of THop's fastest routes under the actual query constraints: {@code S_THop(t) = Pref(P_fastest(t))} when the fastest route
 * satisfies the travel-time budget at departure {@code t}, otherwise {@code 0}.
 *
 * <p>The evaluator is the exact route replay used by the PACE-B / baseline evaluation ({@link ExactPreferenceReplay}). Feasibility is kept
 * <em>separate</em> from the score: a feasible cell whose preference is genuinely zero is distinguishable from an infeasible cell that is
 * assigned zero. Nothing here is optimised or enumerated; the route is whatever THop's fastest profile implies.</p>
 */
public final class ThopPreferenceScoring {
    private ThopPreferenceScoring() { }

    /** One departure interval of one witness route: its feasibility and (for feasible cells) its exact preference. */
    public record Cell(ExactTemporalDomain.Interval interval, int witnessIndex, boolean feasible, BigInteger score) { }

    public record Evaluation(List<Cell> cells, ExactTemporalDomain feasibleDomain, ExactTemporalDomain infeasibleDomain, int replays, long replayNanos) { }

    public static Evaluation evaluate(TDGraph graph, int source, int destination, List<ThopRouteWitnesses.Witness> witnesses, ExactTime budget) {
        List<Cell> cells = new ArrayList<>();
        ExactTemporalDomain feasibleAll = ExactTemporalDomain.empty();
        ExactTemporalDomain infeasibleAll = ExactTemporalDomain.empty();
        long started = System.nanoTime();
        int replays = 0;
        for (int index = 0; index < witnesses.size(); index++) {
            ThopRouteWitnesses.Witness witness = witnesses.get(index);
            ExactTemporalDomain wanted = ExactTemporalDomain.of(witness.interval());
            Optional<ExactPreferencePathProfile> replay = ExactPreferenceReplay.replayExact(graph, source, destination, new Path(witness.arcIds()), wanted, budget);
            replays++;
            ExactTemporalDomain feasible = replay.map(ExactPreferencePathProfile::feasibleDomain).orElse(ExactTemporalDomain.empty());
            if (replay.isPresent()) {
                for (ExactScoreProfile.Segment segment : replay.get().score().segments()) {
                    for (ExactTemporalDomain.Interval part : ExactTemporalDomain.of(segment.interval()).intersection(feasible).intervals()) {
                        cells.add(new Cell(part, index, true, segment.value()));
                    }
                }
            }
            for (ExactTemporalDomain.Interval part : wanted.difference(feasible).intervals()) {
                cells.add(new Cell(part, index, false, BigInteger.ZERO));
            }
            feasibleAll = feasibleAll.union(feasible);
            infeasibleAll = infeasibleAll.union(wanted.difference(feasible));
        }
        cells.sort(java.util.Comparator.comparing((Cell c) -> c.interval().start()).thenComparing(c -> !c.interval().startInclusive()));
        return new Evaluation(cells, feasibleAll, infeasibleAll, replays, System.nanoTime() - started);
    }
}
