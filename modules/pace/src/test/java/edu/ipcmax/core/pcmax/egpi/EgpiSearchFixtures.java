package edu.ipcmax.core.pcmax.egpi;

import static edu.ipcmax.core.pcmax.egpi.EgpiFixtures.constant;
import static edu.ipcmax.core.pcmax.egpi.EgpiFixtures.edge;
import static edu.ipcmax.core.pcmax.egpi.EgpiFixtures.scores;
import static edu.ipcmax.core.pcmax.egpi.EgpiFixtures.zero;

import java.util.List;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.pcmax.QuerySpec;

/** Search-level fixtures whose intended routes are spelled out arc by arc. */
final class EgpiSearchFixtures {
    private EgpiSearchFixtures() { }

    /**
     * The static baseline is the direct arc 1->9 (weight 2) and scores 5. Pivot
     * 2->3 alone (route [1,2,3]) scores 3 and pivot 4->5 alone (route [4,5,6])
     * scores 4: neither improves the envelope. Only the two-pivot route
     * [1,2,7,5,6] = 1->2, 2->3, 3->4, 4->5, 5->9 scores 7.
     */
    static TDGraph twoPivot() {
        return EgpiFixtures.graph(List.of(
                edge(0, 1, 9, constant(2), scores(0, 1440, 5)),
                edge(1, 1, 2, constant(1), zero()),
                edge(2, 2, 3, constant(1), scores(0, 1440, 3)),
                edge(3, 3, 9, constant(1), zero()),
                edge(4, 1, 4, constant(1), zero()),
                edge(5, 4, 5, constant(1), scores(0, 1440, 4)),
                edge(6, 5, 9, constant(1), zero()),
                edge(7, 3, 4, constant(1), zero())));
    }

    static List<Integer> twoPivotWinner() { return List.of(1, 2, 7, 5, 6); }

    static QuerySpec query(int source, int destination, int start, int end, double budget) {
        return new QuerySpec(source, destination, start, end, budget, 1);
    }

    static EgpiResult run(TDGraph graph, QuerySpec query, EgpiOptions options, int threads, boolean history) {
        return new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                options, threads, history, "fixture").generate(query);
    }

    static EgpiResult run(TDGraph graph, QuerySpec query, EgpiOptions options, EgpiEngineOptions engine,
            int threads, boolean history) {
        return new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                options, engine, threads, history, "fixture").generate(query);
    }

    static EgpiOptions small(int chunks) {
        return new EgpiOptions(256, 128, 32, 8, 32, 3, 60_000, 512, 256, 3,
                chunks, 8, 4, 16, 64, 64L << 20, 64L << 20);
    }
}
