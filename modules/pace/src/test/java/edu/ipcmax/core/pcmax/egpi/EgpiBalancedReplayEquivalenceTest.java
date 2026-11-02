package edu.ipcmax.core.pcmax.egpi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.ExactKernel;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceTransferProfile;
import edu.ipcmax.baselines.exact.preference.ExactReplayEngine;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.pcmax.PaceExecutionMetrics;
import edu.ipcmax.core.validate.Path;

/**
 * Balanced replay must return exactly the piecewise functions of the edge-by-edge replay: the same
 * arrival pieces, score pieces, owned endpoints, feasible domain and requested domain, with no fallback.
 */
class EgpiBalancedReplayEquivalenceTest {
    @AfterEach
    void restoreDefaults() {
        ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 8, 16);
        ExactKernel.configure(ExactKernel.Mode.R21);
    }

    private static ExactTemporalDomain closed(double start, double end) {
        return ExactTemporalDomain.of(ExactTemporalDomain.Interval.closed(
                ExactTime.parseDecimal(Double.toString(start)), ExactTime.parseDecimal(Double.toString(end))));
    }

    private static ExactTemporalDomain randomDeparture(Random random, double earliest, double latest) {
        double start = earliest + random.nextInt((int) ((latest - earliest) * 10)) / 10.0;
        switch (random.nextInt(4)) {
            case 0:
                return closed(start, start + random.nextInt(300) / 10.0);
            case 1:
                return ExactTemporalDomain.of(new ExactTemporalDomain.Interval(
                        ExactTime.parseDecimal(Double.toString(start)),
                        ExactTime.parseDecimal(Double.toString(start + 0.5 + random.nextInt(120) / 10.0)), false, true));
            case 2:
                return ExactTemporalDomain.of(
                        new ExactTemporalDomain.Interval(ExactTime.parseDecimal(Double.toString(start)),
                                ExactTime.parseDecimal(Double.toString(start + 2.5)), true, false),
                        ExactTemporalDomain.Interval.singleton(ExactTime.parseDecimal(Double.toString(start + 3.5))),
                        new ExactTemporalDomain.Interval(ExactTime.parseDecimal(Double.toString(start + 5)),
                                ExactTime.parseDecimal(Double.toString(start + 9.25)), false, true));
            default:
                return ExactTemporalDomain.of(ExactTemporalDomain.Interval.singleton(ExactTime.parseDecimal(Double.toString(start))));
        }
    }

    private static void assertSameTransfer(ExactPreferenceTransferProfile expected, ExactPreferenceTransferProfile actual,
            String context) {
        assertEquals(expected.requestedDomain(), actual.requestedDomain(), context + " requested domain");
        assertEquals(expected.arrival().segments(), actual.arrival().segments(), context + " arrival pieces");
        assertEquals(expected.score().segments(), actual.score().segments(), context + " score pieces");
        assertEquals(expected.path(), actual.path(), context);
    }

    private static ExactPreferenceTransferProfile transfer(TDGraph graph, int arcs, ExactTemporalDomain entry) {
        return ExactPreferenceReplay.replayTransferExact(graph, 1, arcs + 1,
                new Path(java.util.stream.IntStream.range(0, arcs).boxed().toList()), entry, null);
    }

    private static long fallbacks(PaceExecutionMetrics metrics) {
        return metrics.snapshot().counters().getOrDefault("exact_balanced_replay_fallbacks", 0L);
    }

    @Test
    void balancedTransfersEqualEdgeByEdgeTransfersOnLongSlopedChains() {
        Random random = new Random(20270425);
        PaceExecutionMetrics metrics = PaceExecutionMetrics.live(snapshot -> { });
        int compared = 0, nonEmpty = 0;
        try (var scope = ExactArithmeticDiagnostics.install(metrics)) {
            for (int c = 0; c < 70; c++) {
                int arcs = 17 + random.nextInt(60);
                TDGraph graph = EgpiFixtures.chain(arcs, 1000 + c);
                ExactTemporalDomain entry = randomDeparture(random, 0, 900);
                int leaf = new int[] {1, 2, 3, 5, 8, 13}[random.nextInt(6)];
                ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, leaf, 2);
                ExactPreferenceTransferProfile expected = transfer(graph, arcs, entry);
                ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, leaf, 2);
                ExactPreferenceTransferProfile actual = transfer(graph, arcs, entry);
                assertSameTransfer(expected, actual, "case " + c + " arcs " + arcs + " leaf " + leaf + " entry " + entry);
                compared++;
                if (!expected.arrival().isEmpty()) nonEmpty++;
            }
        }
        assertEquals(0, fallbacks(metrics), "no band may miss an entry time");
        assertTrue(compared == 70 && nonEmpty > 50, "non-empty comparisons: " + nonEmpty);
    }

    @Test
    void balancedReplayKeepsSupportTruncatedAtTheEndOfTheDay() {
        Random random = new Random(404);
        PaceExecutionMetrics metrics = PaceExecutionMetrics.live(snapshot -> { });
        int truncated = 0, empty = 0;
        try (var scope = ExactArithmeticDiagnostics.install(metrics)) {
            for (int c = 0; c < 60; c++) {
                int arcs = 12 + random.nextInt(30);
                TDGraph graph = EgpiFixtures.chain(arcs, 7000 + c);
                // windows whose images run through, and past, the end of every edge's domain at 1440
                ExactTemporalDomain entry = random.nextBoolean()
                        ? closed(1440 - arcs * (1.5 + random.nextInt(40) / 10.0), 1440 - arcs * 0.5)
                        : randomDeparture(random, 1440 - arcs * 6.0, 1440 - arcs * 0.5);
                int leaf = new int[] {1, 2, 4, 7}[random.nextInt(4)];
                ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, leaf, 2);
                ExactPreferenceTransferProfile expected = transfer(graph, arcs, entry);
                ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, leaf, 2);
                ExactPreferenceTransferProfile actual = transfer(graph, arcs, entry);
                assertSameTransfer(expected, actual, "case " + c + " arcs " + arcs + " leaf " + leaf);
                if (expected.arrival().isEmpty()) empty++;
                else if (!expected.arrival().domain().equals(expected.requestedDomain())) truncated++;
            }
        }
        assertEquals(0, fallbacks(metrics));
        assertTrue(truncated > 5 && empty > 3, "partial and empty supports must both occur: " + truncated + " partial, " + empty + " empty");
    }

    @Test
    void sourceRootReplayWithABudgetIsIdenticalIncludingTheFeasibleDomain() {
        Random random = new Random(55);
        for (int c = 0; c < 40; c++) {
            int arcs = 16 + random.nextInt(40);
            TDGraph graph = EgpiFixtures.chain(arcs, 3000 + c);
            ExactTemporalDomain entry = randomDeparture(random, 0, 700);
            ExactTime budget = ExactTime.of(arcs * 2L + random.nextInt(arcs * 4));
            Path path = new Path(java.util.stream.IntStream.range(0, arcs).boxed().toList());
            ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 8, 2);
            Optional<ExactPreferencePathProfile> expected = ExactPreferenceReplay.replayExact(graph, 1, arcs + 1, path, entry, budget);
            ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, 1 + random.nextInt(9), 2);
            Optional<ExactPreferencePathProfile> actual = ExactPreferenceReplay.replayExact(graph, 1, arcs + 1, path, entry, budget);
            assertEquals(expected.isPresent(), actual.isPresent(), "case " + c);
            if (expected.isEmpty()) continue;
            assertEquals(expected.get().arrival().segments(), actual.get().arrival().segments(), "case " + c);
            assertEquals(expected.get().score().segments(), actual.get().score().segments(), "case " + c);
            assertEquals(expected.get().feasibleDomain(), actual.get().feasibleDomain(), "case " + c);
        }
    }

    @Test
    void shortRandomGraphPathsAgreeWithEveryLeafSize() {
        Random random = new Random(909);
        int compared = 0;
        for (int c = 0; c < 80; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 9, 14);
            List<Integer> path = EgpiFixtures.randomSimplePath(random, graph, 1, 8);
            if (path.size() < 2) continue;
            int target = graph.edges().get(path.getLast()).target();
            ExactTemporalDomain entry = randomDeparture(random, 0, 200);
            ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 1, 2);
            ExactPreferenceTransferProfile expected = ExactPreferenceReplay.replayTransferExact(graph, 1, target, new Path(path), entry, null);
            for (int leaf : new int[] {1, 2, 3, 4}) {
                ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, leaf, 2);
                assertSameTransfer(expected, ExactPreferenceReplay.replayTransferExact(graph, 1, target, new Path(path), entry, null),
                        "case " + c + " leaf " + leaf);
            }
            compared++;
        }
        assertTrue(compared > 50);
    }

    @Test
    void theLehmerKernelAndBalancedReplayTogetherChangeNothing() {
        Random random = new Random(61);
        for (int c = 0; c < 25; c++) {
            int arcs = 20 + random.nextInt(50);
            TDGraph graph = EgpiFixtures.chain(arcs, 5000 + c);
            ExactTemporalDomain entry = randomDeparture(random, 0, 800);
            ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 8, 16);
            ExactKernel.configure(ExactKernel.Mode.R21);
            ExactPreferenceTransferProfile expected = transfer(graph, arcs, entry);
            ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, 4, 16);
            ExactKernel.configure(ExactKernel.Mode.R22);
            assertSameTransfer(expected, transfer(graph, arcs, entry), "case " + c);
        }
    }

    @Test
    void aCancelledBalancedReplayThrowsAndLeavesNoResult() {
        TDGraph graph = EgpiFixtures.chain(60, 31);
        ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, 4, 2);
        Path path = new Path(java.util.stream.IntStream.range(0, 60).boxed().toList());
        int cancelled = 0;
        for (int limit = 0; limit < 4000; limit += 97) {
            AtomicInteger checks = new AtomicInteger();
            final int stopAt = limit;
            try {
                ExactPreferenceReplay.replayTransferExact(graph, 1, 61, path, closed(100, 105), () -> checks.incrementAndGet() > stopAt);
            } catch (CancellationException expected) {
                cancelled++;
            }
        }
        assertTrue(cancelled > 10, "cancellation must interrupt the replay: " + cancelled);
        // after cancellations the same replay still produces the reference result
        ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 4, 2);
        ExactPreferenceTransferProfile expected = ExactPreferenceReplay.replayTransferExact(graph, 1, 61, path, closed(100, 105), null);
        ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, 4, 2);
        assertSameTransfer(expected, ExactPreferenceReplay.replayTransferExact(graph, 1, 61, path, closed(100, 105), null), "after");
    }

    @Test
    void emptyAndOutOfSupportDeparturesAreEmptyInBothEngines() {
        TDGraph graph = EgpiFixtures.chain(30, 11);
        for (ExactTemporalDomain entry : List.of(closed(2000, 2010), closed(1439, 1439), ExactTemporalDomain.empty())) {
            ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 4, 2);
            ExactPreferenceTransferProfile expected = transfer(graph, 30, entry);
            ExactReplayEngine.configure(ExactReplayEngine.Mode.BALANCED_V1, 4, 2);
            assertSameTransfer(expected, transfer(graph, 30, entry), entry.toString());
        }
    }

    @Test
    void gridRoundingContainsEveryValueAndIsExactOnTheGrid() {
        Random random = new Random(3);
        for (int i = 0; i < 2000; i++) {
            ExactTime value = ExactTime.of(java.math.BigInteger.valueOf(random.nextLong() >> random.nextInt(40)),
                    java.math.BigInteger.valueOf(1 + random.nextInt(1_000_000)));
            ExactTime floor = edu.ipcmax.baselines.exact.preference.ExactBalancedReplayProbe.floor(value), ceil = edu.ipcmax.baselines.exact.preference.ExactBalancedReplayProbe.ceil(value);
            assertTrue(floor.compareTo(value) <= 0 && value.compareTo(ceil) <= 0, value.toString());
            assertTrue(ceil.subtract(floor).compareTo(ExactTime.of(java.math.BigInteger.TWO, java.math.BigInteger.ONE.shiftLeft(20))) <= 0);
            assertEquals(floor, edu.ipcmax.baselines.exact.preference.ExactBalancedReplayProbe.floor(floor));
            assertEquals(ceil, edu.ipcmax.baselines.exact.preference.ExactBalancedReplayProbe.ceil(ceil));
        }
    }
}
