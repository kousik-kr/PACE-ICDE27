package edu.ipcmax.core.pcmax.egpi;

import static edu.ipcmax.core.pcmax.egpi.EgpiSearchFixtures.query;
import static edu.ipcmax.core.pcmax.egpi.EgpiSearchFixtures.small;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.baselines.exact.preference.GainSum;
import edu.ipcmax.baselines.exact.preference.IncrementalExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ScoreStepEnvelope;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.pcmax.QuerySpec;
import edu.ipcmax.core.validate.Path;

/**
 * Completion-aware commits: the committed collection must not depend on the order units finish in,
 * the search decisions must equal the ordered policy's, and finished units must not wait for slow ones.
 */
class EgpiCompletionAwareCommitTest {
    private static final QuerySpec TWO_PIVOT = query(1, 9, 0, 20, 30);
    private static final EgpiEngineOptions ORDERED = EgpiEngineOptions.referenceProfile();
    private static final EgpiEngineOptions COMPLETION = new EgpiEngineOptions(
            EgpiEngineOptions.CommitPolicy.COMPLETION_AWARE_V1, EgpiEngineOptions.EnvelopeEngine.REFINED_STAGED_COPY_V1);

    private static ExactTemporalDomain closed(long start, long end) {
        return ExactTemporalDomain.of(ExactTemporalDomain.Interval.closed(ExactTime.of(start), ExactTime.of(end)));
    }

    /** Validated chunk entries of several random routes over {@code departures}, as the search would commit them. */
    private static List<ExactPreferencePathProfile> randomEntries(Random random, TDGraph graph, int target,
            ExactTemporalDomain departures, int chunks, int routes) {
        List<ExactTemporalDomain> parts = EgpiCandidateGenerator.chunk(departures, chunks);
        ExactTime budget = ExactTime.of(25 + random.nextInt(50));
        List<ExactPreferencePathProfile> entries = new ArrayList<>();
        java.util.Set<Path> seen = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 60 && seen.size() < routes; attempt++) {
            List<Integer> path = EgpiFixtures.randomSimplePath(random, graph, 1, 7);
            if (path.isEmpty() || graph.edges().get(path.getLast()).target() != target) continue;
            Path key = new Path(path);
            if (!seen.add(key)) continue;
            for (ExactTemporalDomain part : parts) {
                ExactPreferenceReplay.replayExact(graph, 1, target, key, part, budget).ifPresent(entries::add);
            }
        }
        return entries;
    }

    private static String encoded(IncrementalExactPreferenceEnvelope envelope) {
        return ExactPreferenceProfileCodec.encode(envelope.snapshot());
    }

    // ------------------------------------------------------------- order independence of the committed collection

    @Test
    void theCommittedEnvelopeDoesNotDependOnTheOrderEntriesAreAdded() {
        Random random = new Random(20270422);
        int compared = 0;
        for (int c = 0; c < 60; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 12);
            ExactTemporalDomain departures = random.nextBoolean() ? closed(0, 48)
                    : ExactTemporalDomain.of(
                            new ExactTemporalDomain.Interval(ExactTime.of(0), ExactTime.of(20), true, false),
                            ExactTemporalDomain.Interval.singleton(ExactTime.of(22)),
                            new ExactTemporalDomain.Interval(ExactTime.of(25), ExactTime.of(40), false, true));
            List<ExactPreferencePathProfile> entries = randomEntries(random, graph, 8, departures, 1 + random.nextInt(5), 4);
            if (entries.size() < 3) continue;
            IncrementalExactPreferenceEnvelope base = new IncrementalExactPreferenceEnvelope(departures);
            entries.forEach(entry -> base.add(entry, null));
            String expected = encoded(base);
            for (int shuffle = 0; shuffle < 6; shuffle++) {
                List<ExactPreferencePathProfile> order = new ArrayList<>(entries);
                Collections.shuffle(order, random);
                IncrementalExactPreferenceEnvelope other = new IncrementalExactPreferenceEnvelope(departures);
                order.forEach(entry -> other.add(entry, null));
                assertEquals(expected, encoded(other), "case " + c + " shuffle " + shuffle);
            }
            compared++;
        }
        assertTrue(compared > 40, "too few comparable cases: " + compared);
    }

    @Test
    void duplicateEntriesAndEntriesWithEqualScoresLeaveTheEnvelopeUnchanged() {
        Random random = new Random(33);
        for (int c = 0; c < 40; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 7, 10);
            ExactTemporalDomain departures = closed(5, 45);
            List<ExactPreferencePathProfile> entries = randomEntries(random, graph, 7, departures, 2, 3);
            if (entries.isEmpty()) continue;
            IncrementalExactPreferenceEnvelope envelope = new IncrementalExactPreferenceEnvelope(departures);
            ScoreStepEnvelope credit = new ScoreStepEnvelope();
            for (ExactPreferencePathProfile entry : entries) {
                envelope.add(entry, null);
                credit.add(entry);
            }
            String before = encoded(envelope);
            for (ExactPreferencePathProfile entry : entries) {
                IncrementalExactPreferenceEnvelope.Update update = envelope.add(entry, null);
                assertFalse(update.improved(), "a duplicate improves nothing");
                assertFalse(credit.add(entry).improved());
            }
            assertEquals(before, encoded(envelope), "case " + c);
        }
    }

    @Test
    void stepCreditReproducesTheGainsOfTheFullEnvelopeInAnyAddOrder() {
        Random random = new Random(512);
        int compared = 0;
        for (int c = 0; c < 80; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 12);
            ExactTemporalDomain departures = random.nextBoolean() ? closed(0, 60)
                    : ExactTemporalDomain.of(
                            new ExactTemporalDomain.Interval(ExactTime.of(3), ExactTime.of(30), false, true),
                            new ExactTemporalDomain.Interval(ExactTime.of(35), ExactTime.of(35), true, true),
                            new ExactTemporalDomain.Interval(ExactTime.of(40), ExactTime.of(55), true, false));
            List<ExactPreferencePathProfile> entries = randomEntries(random, graph, 8, departures, 1 + random.nextInt(6), 5);
            if (entries.size() < 3) continue;
            Collections.shuffle(entries, random);
            IncrementalExactPreferenceEnvelope envelope = new IncrementalExactPreferenceEnvelope(departures);
            ScoreStepEnvelope credit = new ScoreStepEnvelope();
            ExactTime coverage = ExactTime.ZERO, preference = ExactTime.ZERO;
            GainSum credited = new GainSum();
            for (int i = 0; i < entries.size(); i++) {
                IncrementalExactPreferenceEnvelope.Update update = envelope.add(entries.get(i), null);
                ScoreStepEnvelope.Gain gain = credit.add(entries.get(i));
                // The display doubles the search decides with are those of the exact values, and so are the sums.
                assertEquals(update.coverageGain().toDisplayDouble(), gain.coverageDisplay(), "coverage display, case " + c + " add " + i);
                assertEquals(update.integratedPreferenceGain().toDisplayDouble(), gain.preferenceDisplay(),
                        "preference display, case " + c + " add " + i);
                assertEquals(update.improved(), gain.improved(), "case " + c + " add " + i);
                coverage = coverage.add(update.coverageGain());
                preference = preference.add(update.integratedPreferenceGain());
                credited.add(gain);
                assertEquals(coverage.signum() > 0 || preference.signum() > 0, credited.improved());
                assertEquals(coverage.toDisplayDouble(), credited.coverageDisplay(), "summed coverage display, case " + c);
                assertEquals(preference.toDisplayDouble(), credited.preferenceDisplay(), "summed preference display, case " + c);
                assertEquals(update.coverageGain(), gain.coverage(), "coverage, case " + c + " add " + i);
                assertEquals(update.integratedPreferenceGain(), gain.preference(), "preference, case " + c + " add " + i);
            }
            assertEquals(coverage, credited.coverage());
            assertEquals(preference, credited.preference());
            assertEquals(coverage, credit.coveredMeasure());
            assertEquals(preference, credit.integratedPreference());
            // The totals also equal the measure and integral of the final envelope's cells.
            ExactTime cellMeasure = ExactTime.ZERO, cellPreference = ExactTime.ZERO;
            for (ExactPreferenceEnvelope.Cell cell : envelope.snapshot().cells()) {
                if (!cell.found()) continue;
                ExactTime length = cell.interval().end().subtract(cell.interval().start());
                cellMeasure = cellMeasure.add(length);
                cellPreference = cellPreference.add(length.multiply(ExactTime.of(cell.score(), java.math.BigInteger.ONE)));
            }
            assertEquals(cellMeasure, credit.coveredMeasure());
            assertEquals(cellPreference, credit.integratedPreference());
            compared++;
        }
        assertTrue(compared > 50, "too few comparable cases: " + compared);
    }

    // ------------------------------------------------------------- the search under controlled schedules

    private static void assertSameSearch(EgpiResult expected, EgpiResult actual, String context) {
        // the canonical engine publishes the canonical form of the refined envelope: same route, score and arrival
        // at every departure; refined engines publish identical refined cells
        assertEquals(ExactPreferenceProfileCodec.encode(expected.envelope().canonicalized()),
                ExactPreferenceProfileCodec.encode(actual.envelope().canonicalized()), context + " envelope");
        assertEquals(expected.registry().keySet(), actual.registry().keySet(), context + " registry routes");
        for (Path route : expected.registry().keySet()) {
            assertEquals(expected.registry().get(route).arrival().segments(),
                    actual.registry().get(route).arrival().segments(), context + " arrival of " + route);
            assertEquals(expected.registry().get(route).score().segments(),
                    actual.registry().get(route).score().segments(), context + " score of " + route);
        }
        assertEquals(expected.routeTrace(), actual.routeTrace(), context + " route trace (credit, roles, validated domains)");
        assertEquals(expected.stopReason(), actual.stopReason(), context);
        assertEquals(expected.capsTriggered(), actual.capsTriggered(), context);
        for (String key : List.of("egpi_routes_constructed", "egpi_routes_admitted_for_exact", "egpi_units_queued",
                "egpi_proposals_popped", "egpi_proposals_generated", "egpi_proposals_rejected",
                "egpi_envelope_improving_commits", "egpi_envelope_commits", "egpi_frontier_admissions_exploitation",
                "egpi_frontier_admissions_exploration", "egpi_frontier_evictions", "egpi_exact_batches")) {
            assertEquals(expected.counters().get(key), actual.counters().get(key), context + " " + key);
        }
        assertEquals(expected.timeline().commits().size(), actual.timeline().commits().size(), context);
    }

    @Test
    void completionOrderCommitsReproduceTheOrderedSearchUnderRandomSchedules() {
        Random random = new Random(808);
        int compared = 0;
        for (int c = 0; c < 14; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 12);
            QuerySpec q = query(1, 8, 10, random.nextInt(4) == 0 ? 10 : 50, 40 + random.nextInt(30));
            EgpiOptions options = small(1 + random.nextInt(5));
            EgpiResult ordered = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                    options, ORDERED, 1, false, "fixture").generate(q);
            long seed = random.nextLong();
            for (int run = 0; run < 3; run++) {
                Random delays = new Random(seed + run);
                EgpiResult completion = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                        options, COMPLETION, 4, false, "fixture")
                        .withUnitHook((epoch, batchIndex, route, chunk) -> {
                            try {
                                Thread.sleep(delays.nextInt(7) == 0 ? 0 : (long) ((batchIndex * 31L + route * 17L + chunk * 13L + epoch * 7L) % 11));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }).generate(q);
                assertSameSearch(ordered, completion, "case " + c + " schedule " + run);
            }
            compared++;
        }
        assertEquals(14, compared);
    }

    @Test
    void everyCombinationOfCommitPolicyAndEnvelopeEngineProducesTheSameSearch() {
        Random random = new Random(2468);
        List<EgpiEngineOptions> variants = new ArrayList<>();
        for (var commit : EgpiEngineOptions.CommitPolicy.values()) {
            for (var envelope : EgpiEngineOptions.EnvelopeEngine.values()) {
                variants.add(new EgpiEngineOptions(commit, envelope));
            }
        }
        assertEquals(6, variants.size());
        for (int c = 0; c < 10; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 12);
            QuerySpec q = query(1, 8, 10, random.nextInt(5) == 0 ? 10 : 50, 40 + random.nextInt(30));
            EgpiOptions options = small(1 + random.nextInt(5));
            EgpiResult reference = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                    options, ORDERED, 1, false, "fixture").generate(q);
            for (EgpiEngineOptions variant : variants) {
                EgpiResult other = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                        options, variant, 1 + random.nextInt(4), false, "fixture").generate(q);
                assertSameSearch(reference, other, "case " + c + " " + variant);
                if (variant.envelopeEngine() != EgpiEngineOptions.EnvelopeEngine.CANONICAL_LAZY_V3) {
                    assertEquals(ExactPreferenceProfileCodec.encode(reference.envelope()),
                            ExactPreferenceProfileCodec.encode(other.envelope()),
                            "refined engines publish identical refined cells, case " + c + " " + variant);
                }
            }
        }
    }

    @Test
    void completionOrderCommitsReproduceTheOrderedSearchOnTheTwoPivotGraph() {
        EgpiResult ordered = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                small(3), ORDERED, 1, false, "fixture").generate(TWO_PIVOT);
        for (int workers : new int[] {2, 4, 8}) {
            EgpiResult completion = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                    QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                    small(3), COMPLETION, workers, false, "fixture").generate(TWO_PIVOT);
            assertSameSearch(ordered, completion, workers + " workers");
        }
    }

    @Test
    void finishedUnitsAreCommittedWithoutWaitingForASlowerEarlierUnit() throws Exception {
        for (EgpiEngineOptions engine : List.of(COMPLETION, ORDERED)) {
            boolean completionAware = engine == COMPLETION;
            CountDownLatch releaseSlowUnit = new CountDownLatch(1);
            List<EgpiAnytime.Commit> seen = new CopyOnWriteArrayList<>();
            AtomicInteger commitsBeforeRelease = new AtomicInteger();
            AtomicBoolean released = new AtomicBoolean();
            EgpiCandidateGenerator generator = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                    QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                    small(3), engine, 4, false, "fixture")
                    .observe(commit -> {
                        seen.add(commit);
                        if (!released.get() && commit.profile() != null) commitsBeforeRelease.incrementAndGet();
                        if (commitsBeforeRelease.get() >= 2) {
                            released.set(true);
                            releaseSlowUnit.countDown();
                        }
                    })
                    .withUnitHook((epoch, batchIndex, route, chunk) -> {
                        if (epoch != 1 || batchIndex != 0) return;
                        try {
                            // Under the ordered policy nothing can commit while this unit runs: release by timeout.
                            releaseSlowUnit.await(completionAware ? 20 : 400, TimeUnit.MILLISECONDS == null ? null : TimeUnit.MILLISECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            EgpiResult result = generator.generate(TWO_PIVOT);
            assertFalse(result.timeline().commits().isEmpty());
            long released20 = result.unitEvents().stream().filter(e -> e.epoch() == 1 && e.batchIndex() == 0)
                    .mapToLong(EgpiAnytime.UnitEvent::finishNs).findFirst().orElseThrow();
            long firstCommit = result.timeline().commits().stream().filter(c -> c.epoch() == 1)
                    .mapToLong(EgpiAnytime.Commit::commitNs).min().orElseThrow();
            if (completionAware) {
                // Units 1 and 2 finish at once and are committed while unit 0 still waits for its timeout.
                assertTrue(firstCommit < released20, "completion-aware: a later unit committed before the slow unit finished");
                assertTrue(result.timeline().commits().stream().filter(c -> c.epoch() == 1 && c.commitNs() < released20).count() >= 2);
            } else {
                assertTrue(firstCommit >= released20, "ordered: nothing commits before the first unit of the batch finished");
            }
        }
    }

    @Test
    void anUnfinishedUnitIsNeverPublishedAndTheCommittedStateStaysValid() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        List<EgpiAnytime.Commit> seen = new CopyOnWriteArrayList<>();
        EgpiOptions options = small(3).withQueryDeadlineMs(1200);
        EgpiResult result = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                options, COMPLETION, 4, false, "fixture")
                .observe(seen::add)
                .withUnitHook((epoch, batchIndex, route, chunk) -> {
                    if (epoch != 1 || batchIndex != 0) return;
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException stop) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                }).generate(TWO_PIVOT);
        assertTrue(result.deadlineReached());
        assertEquals("DEADLINE", result.stopReason());
        assertTrue(interrupted.get(), "the blocked worker is cancelled at the deadline");
        EgpiAnytime.UnitEvent slow = result.unitEvents().stream().filter(e -> e.epoch() == 1 && e.batchIndex() == 0)
                .findFirst().orElseThrow();
        assertEquals(EgpiAnytime.CANCELLED_UNVALIDATED, slow.outcome());
        // The unit's chunk of that route is neither validated nor published: no commit of route 0 on chunk 0 exists,
        // and the route is not claimed to be validated on the full departure domain.
        assertTrue(result.timeline().commits().stream().noneMatch(c -> c.routeId() == 0 && c.chunk() == 0));
        Map<String, Object> routeZero = result.routeTrace().get(0);
        assertEquals(2, routeZero.get("units_committed"));
        assertEquals(false, routeZero.get("full_domain_validated"));
        assertEquals(1, routeZero.get("units_cancelled") instanceof Integer count ? 1 : 0);
        for (var route : result.registry().values()) {
            if (route.path().arcIds().equals(List.of(0))) {
                assertFalse(route.feasibleDomain().contains(ExactTime.of(1)), "the unfinished chunk is not in the registry");
            }
        }
        assertEquals(3, result.timeline().commits().stream().filter(c -> c.epoch() == 1 && c.profile() != null).count());
        int observed = seen.size();
        Thread.sleep(150);
        assertEquals(observed, seen.size(), "no worker publishes after the search returned");
        assertEquals(result.timeline().commits().size(), seen.size());
    }

    @Test
    void theResultReserveStopsTheSearchEarlierAndTheLazyRegistryMergesOnDemandOnly() throws Exception {
        EgpiEngineOptions reserved = new EgpiEngineOptions(EgpiEngineOptions.CommitPolicy.COMPLETION_AWARE_V1,
                EgpiEngineOptions.EnvelopeEngine.REFINED_JOURNALED_V2, EgpiEngineOptions.ResultForm.CANONICAL_CELLS_V1, 700);
        EgpiOptions options = small(3).withQueryDeadlineMs(1000);
        long started = System.nanoTime();
        EgpiResult result = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                options, reserved, 4, false, "fixture")
                .withUnitHook((epoch, batchIndex, route, chunk) -> {
                    if (epoch != 1 || batchIndex != 0) return;
                    try {
                        Thread.sleep(60_000);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                    }
                }).generate(TWO_PIVOT);
        double seconds = (System.nanoTime() - started) / 1e9;
        assertTrue(result.deadlineReached());
        assertTrue(seconds < 0.95, "the search stops 700 ms before the 1 s deadline, returned after " + seconds + " s");
        assertTrue(result.counters().get("egpi_units_published_without_credit_at_stop") >= 2);
        // routes and chunk entries are readable without merging; a profile is merged only when asked for
        assertFalse(result.registry().isEmpty());
        for (Path route : result.registry().keySet()) {
            assertTrue(result.registry().containsKey(route));
            assertEquals(route, result.registry().get(route).path());
        }
        assertThrows(UnsupportedOperationException.class, () -> result.registry().clear());
        // the canonical result form is the canonical form of the refined envelope of the same search
        EgpiResult refined = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                small(3), COMPLETION, 4, false, "fixture").generate(TWO_PIVOT);
        EgpiResult canonical = new EgpiCandidateGenerator(EgpiSearchFixtures.twoPivot(),
                QueryPreparationIndexes.buildAllowingZero(EgpiSearchFixtures.twoPivot()),
                small(3), new EgpiEngineOptions(EgpiEngineOptions.CommitPolicy.COMPLETION_AWARE_V1,
                        EgpiEngineOptions.EnvelopeEngine.REFINED_JOURNALED_V2, EgpiEngineOptions.ResultForm.CANONICAL_CELLS_V1),
                4, false, "fixture").generate(TWO_PIVOT);
        assertEquals(ExactPreferenceProfileCodec.encode(refined.envelope().canonicalized()),
                ExactPreferenceProfileCodec.encode(canonical.envelope()));
        assertTrue(canonical.envelope().cells().size() < refined.envelope().cells().size());
    }

    @Test
    void singletonDepartureQueriesAgreeAcrossPolicies() {
        Random random = new Random(91);
        for (int c = 0; c < 8; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 7, 10);
            QuerySpec q = query(1, 7, 20, 20, 30 + random.nextInt(30));
            EgpiOptions options = small(1 + random.nextInt(3));
            EgpiResult ordered = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                    options, ORDERED, 1, false, "fixture").generate(q);
            EgpiResult completion = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                    options, COMPLETION, 4, false, "fixture").generate(q);
            assertSameSearch(ordered, completion, "singleton case " + c);
        }
    }

    // ------------------------------------------------------------- atomic commits

    @Test
    void aCommitCancelledDuringEnvelopeConstructionLeavesNoTrace() {
        Random random = new Random(404);
        int interruptedCommits = 0;
        for (int c = 0; c < 25; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 7, 10);
            ExactTemporalDomain departures = closed(0, 40);
            List<ExactPreferencePathProfile> entries = randomEntries(random, graph, 7, departures, 3, 4);
            if (entries.size() < 3) continue;
            IncrementalExactPreferenceEnvelope envelope = new IncrementalExactPreferenceEnvelope(departures);
            IncrementalExactPreferenceEnvelope reference = new IncrementalExactPreferenceEnvelope(departures);
            for (ExactPreferencePathProfile entry : entries) {
                String before = encoded(envelope);
                long versionBefore = envelope.version();
                for (int limit = 0; limit < 400; limit += 1 + random.nextInt(25)) {
                    AtomicInteger checks = new AtomicInteger();
                    final int stopAt = limit;
                    try {
                        envelope.add(entry, () -> checks.incrementAndGet() > stopAt);
                        break;
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        interruptedCommits++;
                        assertEquals(before, encoded(envelope), "cancelled add must not change the envelope");
                        assertEquals(versionBefore, envelope.version());
                        assertFalse(envelope.snapshot().cells().isEmpty());
                    }
                }
                if (encoded(envelope).equals(before)) envelope.add(entry, null);
                reference.add(entry, null);
                assertEquals(encoded(reference), encoded(envelope), "case " + c);
            }
        }
        assertTrue(interruptedCommits > 20, "the test must actually interrupt commits: " + interruptedCommits);
    }

    @Test
    void registryAndCoverageAreMonotoneInEveryCompletionSchedule() {
        Random random = new Random(1717);
        for (int c = 0; c < 6; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 12);
            QuerySpec q = query(1, 8, 10, 50, 45 + random.nextInt(25));
            Random delays = new Random(random.nextLong());
            EgpiResult result = new EgpiCandidateGenerator(graph, QueryPreparationIndexes.buildAllowingZero(graph),
                    small(4), COMPLETION, 4, false, "fixture")
                    .withUnitHook((epoch, batchIndex, route, chunk) -> {
                        try {
                            Thread.sleep(delays.nextInt(9));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }).generate(q);
            EgpiAnytime.Snapshot previous = null;
            for (int k = 0; k <= result.timeline().commits().size(); k++) {
                EgpiAnytime.Snapshot current = result.timeline().snapshotAfter(k);
                if (previous != null) {
                    assertTrue(previous.covered().difference(current.covered()).isEmpty());
                    assertTrue(previous.integratedPreference().compareTo(current.integratedPreference()) <= 0);
                    assertTrue(current.registeredRoutes().containsAll(previous.registeredRoutes()));
                }
                previous = current;
            }
            assertEquals(ExactPreferenceProfileCodec.encode(result.envelope().canonicalized()),
                    ExactPreferenceProfileCodec.encode(previous.envelope()));
        }
    }
}
