package edu.ipcmax.core.pcmax.egpi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePrefixProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceReplay;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceTransferProfile;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;
import edu.ipcmax.baselines.exact.preference.IncrementalExactPreferenceEnvelope;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

/**
 * Randomized evidence for the three exactness properties EGPI relies on:
 * grouped transfer composition is segment-identical to edge-by-edge replay,
 * restricting a covering transfer equals computing it fresh, and the
 * incremental envelope equals {@code overRetained} cell for cell.
 */
class EgpiExactCompositionPropertyTest {
    private static final int CASES = 150;

    private static ExactTemporalDomain closed(long start, long end) {
        return ExactTemporalDomain.of(ExactTemporalDomain.Interval.closed(ExactTime.of(start), ExactTime.of(end)));
    }

    private static ExactPreferenceTransferProfile transfer(TDGraph graph, List<Integer> arcs,
            ExactTemporalDomain entry) {
        int source = graph.edges().get(arcs.getFirst()).source();
        int target = graph.edges().get(arcs.getLast()).target();
        return ExactPreferenceReplay.replayTransferExact(graph, source, target, new Path(arcs), entry, null);
    }

    /** Splits a path into random consecutive nonempty groups. */
    private static List<List<Integer>> groups(Random random, List<Integer> path) {
        List<List<Integer>> result = new ArrayList<>();
        int start = 0;
        while (start < path.size()) {
            int end = start + 1 + random.nextInt(Math.min(4, path.size() - start));
            result.add(path.subList(start, end));
            start = end;
        }
        return result;
    }

    private static Optional<ExactPreferencePathProfile> grouped(TDGraph graph, List<List<Integer>> groups,
            List<Integer> path, ExactTemporalDomain departures, ExactTime budget) {
        ExactPreferencePrefixProfile state = ExactPreferencePrefixProfile.identity(departures, null);
        for (List<Integer> group : groups) {
            state = state.then(transfer(graph, group, state.requiredEntryDomain(null)), null);
        }
        return state.complete(new Path(path), budget, null);
    }

    private static void assertSameProfile(Optional<ExactPreferencePathProfile> expected,
            Optional<ExactPreferencePathProfile> actual, String context) {
        assertEquals(expected.isPresent(), actual.isPresent(), context);
        if (expected.isEmpty()) return;
        var e = expected.get();
        var a = actual.get();
        assertEquals(e.arrival().segments(), a.arrival().segments(), context + " arrival segments");
        assertEquals(e.score().segments(), a.score().segments(), context + " score segments");
        assertEquals(e.feasibleDomain(), a.feasibleDomain(), context + " feasible domain");
    }

    @Test
    void groupedTransferCompositionIsSegmentIdenticalToEdgeByEdgeReplay() {
        Random random = new Random(20270421);
        int compared = 0;
        for (int c = 0; c < CASES; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 7 + random.nextInt(6), 6 + random.nextInt(10));
            List<Integer> path = EgpiFixtures.randomSimplePath(random, graph, 1, 2 + random.nextInt(7));
            if (path.size() < 2) continue;
            int target = graph.edges().get(path.getLast()).target();
            long start = random.nextInt(60);
            ExactTemporalDomain departures = random.nextBoolean() ? closed(start, start + 20 + random.nextInt(40))
                    : ExactTemporalDomain.of(
                            new ExactTemporalDomain.Interval(ExactTime.of(start), ExactTime.of(start + 15), true, false),
                            ExactTemporalDomain.Interval.singleton(ExactTime.of(start + 17)),
                            new ExactTemporalDomain.Interval(ExactTime.of(start + 20), ExactTime.of(start + 31), false, true));
            ExactTime budget = ExactTime.of(5 + random.nextInt(60));
            var direct = ExactPreferenceReplay.replayExact(graph, 1, target, new Path(path), departures, budget);
            for (int g = 0; g < 3; g++) {
                assertSameProfile(direct, grouped(graph, groups(random, path), path, departures, budget),
                        "case " + c + " grouping " + g + " path " + path);
            }
            compared++;
        }
        assertTrue(compared > 100, "too few comparable random paths: " + compared);
    }

    @Test
    void restrictingACoveringTransferEqualsAFreshTransferOnTheSubdomain() {
        Random random = new Random(1934);
        int compared = 0;
        for (int c = 0; c < CASES; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 8);
            List<Integer> path = EgpiFixtures.randomSimplePath(random, graph, 1 + random.nextInt(3), 1 + random.nextInt(5));
            if (path.isEmpty()) continue;
            long start = random.nextInt(80);
            ExactTemporalDomain wide = closed(start, start + 60);
            ExactTime cut = ExactTime.of(start * 3 + 7 + random.nextInt(100)).divide(ExactTime.of(3));
            ExactTemporalDomain narrow = ExactTemporalDomain.of(
                    new ExactTemporalDomain.Interval(cut, ExactTime.of(start + 50), random.nextBoolean(), random.nextBoolean()),
                    ExactTemporalDomain.Interval.singleton(ExactTime.of(start + 55)));
            var covering = transfer(graph, path, wide);
            var fresh = transfer(graph, path, narrow);
            var restricted = covering.restrict(narrow, null);
            assertEquals(fresh.arrival().segments(), restricted.arrival().segments(), "case " + c);
            assertEquals(fresh.score().segments(), restricted.score().segments(), "case " + c);
            assertEquals(narrow, restricted.requestedDomain());
            compared++;
        }
        assertTrue(compared > 100);
    }

    @Test
    void incrementalEnvelopeEqualsOverRetainedAfterEveryAdd() {
        Random random = new Random(77);
        for (int c = 0; c < 60; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 8, 14);
            int target = 8;
            ExactTemporalDomain departures = closed(10, 50);
            ExactTime budget = ExactTime.of(20 + random.nextInt(60));
            IncrementalExactPreferenceEnvelope incremental = new IncrementalExactPreferenceEnvelope(departures);
            Map<Path, ExactPreferencePathProfile> registry = new LinkedHashMap<>();
            for (int attempt = 0; attempt < 40 && registry.size() < 6; attempt++) {
                List<Integer> path = EgpiFixtures.randomSimplePath(random, graph, 1, 7);
                if (path.isEmpty() || graph.edges().get(path.getLast()).target() != target) continue;
                Path key = new Path(path);
                if (registry.containsKey(key)) continue;
                var profile = ExactPreferenceReplay.replayExact(graph, 1, target, key, departures, budget);
                if (profile.isEmpty()) continue;
                registry.put(key, profile.get());
                incremental.add(profile.get(), null);
                assertEquals(ExactPreferenceProfileCodec.encode(ExactPreferenceEnvelope.overRetained(departures, registry)),
                        ExactPreferenceProfileCodec.encode(incremental.snapshot()), "case " + c + " entries " + registry.size());
            }
        }
    }

    @Test
    void chunkEntriesProduceTheEnvelopeOfTheMergedPerRouteProfiles() {
        Random random = new Random(4242);
        for (int c = 0; c < 40; c++) {
            TDGraph graph = EgpiFixtures.randomGraph(random, 7, 12);
            ExactTemporalDomain departures = closed(0, 48);
            List<ExactTemporalDomain> chunks = EgpiCandidateGenerator.chunk(departures, 1 + random.nextInt(6));
            ExactTime budget = ExactTime.of(25 + random.nextInt(40));
            IncrementalExactPreferenceEnvelope incremental = new IncrementalExactPreferenceEnvelope(departures);
            Map<Path, ExactPreferencePathProfile> merged = new LinkedHashMap<>();
            for (int attempt = 0; attempt < 30 && merged.size() < 4; attempt++) {
                List<Integer> path = EgpiFixtures.randomSimplePath(random, graph, 1, 6);
                if (path.isEmpty() || graph.edges().get(path.getLast()).target() != 7) continue;
                Path key = new Path(path);
                if (merged.containsKey(key)) continue;
                List<ExactPreferencePathProfile> parts = new ArrayList<>();
                for (ExactTemporalDomain chunk : chunks) {
                    var part = ExactPreferenceReplay.replayExact(graph, 1, 7, key, chunk, budget);
                    if (part.isEmpty()) continue;
                    parts.add(part.get());
                    incremental.add(part.get(), null);
                }
                if (parts.isEmpty()) continue;
                merged.put(key, union(key, parts, budget));
            }
            assertEquals(ExactPreferenceProfileCodec.encode(ExactPreferenceEnvelope.overRetained(departures, merged)),
                    ExactPreferenceProfileCodec.encode(incremental.snapshot()), "case " + c);
        }
    }

    @Test
    void chunksAreDisjointExactAndCoverTheDepartureDomain() {
        ExactTemporalDomain departures = closed(450, 570);
        List<ExactTemporalDomain> chunks = EgpiCandidateGenerator.chunk(departures, 8);
        assertEquals(8, chunks.size());
        ExactTemporalDomain union = ExactTemporalDomain.empty();
        for (int i = 0; i < chunks.size(); i++) {
            for (int j = 0; j < i; j++) assertTrue(chunks.get(i).intersection(chunks.get(j)).isEmpty());
            union = union.union(chunks.get(i));
        }
        assertEquals(departures, union);
        assertTrue(chunks.getFirst().contains(ExactTime.of(450)));
        assertFalse(chunks.getFirst().contains(ExactTime.of(465)));
        assertTrue(chunks.get(1).contains(ExactTime.of(465)));
        assertTrue(chunks.getLast().contains(ExactTime.of(570)));
        assertEquals(List.of(ExactTemporalDomain.Interval.singleton(ExactTime.of(5))),
                EgpiCandidateGenerator.chunk(closed(5, 5), 8).getFirst().intervals());
    }

    static ExactPreferencePathProfile union(Path key, List<ExactPreferencePathProfile> parts, ExactTime budget) {
        List<edu.ipcmax.baselines.exact.ExactAffineSegment> arrival = new ArrayList<>();
        List<ExactScoreProfile.Segment> score = new ArrayList<>();
        List<ExactTemporalDomain.Interval> feasible = new ArrayList<>();
        for (var part : parts) {
            arrival.addAll(part.arrival().segments());
            score.addAll(part.score().segments());
            feasible.addAll(part.feasibleDomain().intervals());
        }
        return new ExactPreferencePathProfile(key, ExactArrivalProfile.ofSegments(arrival),
                ExactScoreProfile.ofSegments(score), ExactTemporalDomain.of(feasible), budget, 0, 0);
    }
}
