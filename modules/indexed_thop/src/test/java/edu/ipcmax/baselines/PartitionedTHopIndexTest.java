package edu.ipcmax.baselines;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionedTHopIndexTest {
    private static final Domain HORIZON = Domain.closed(0, 60);

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyTinyGraphPairMatchesBothThopReferencesAndTheIndependentOracle() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("partitioned.pthop");
        BoundaryTHopIndex boundaryIndex = BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        THopIndex unpruned = THopIndex.build(graph, HORIZON);
        THopIndex pruned = THopIndex.buildPruned(graph, HORIZON);

        assertTrue(index.constructionStatistics().boundaryShortcutArcCount() > 0);
        assertTrue(index.constructionStatistics().forwardSearchExpansions() > 0);
        assertTrue(index.constructionStatistics().backwardSearchExpansions() > 0);
        assertEquals(index.constructionStatistics().searchExpansionsSkipped(),
                index.partitionConstructionStatistics().values().stream()
                        .mapToLong(PartitionedTHopIndex.PartitionConstructionStatistics::searchExpansionsSkipped)
                        .sum());
        assertEquals(metadata.partitionCount(), index.partitionConstructionStatistics().size());
        for (Map.Entry<Integer, List<Integer>> partition : metadata.verticesByPartition().entrySet()) {
            THopIndex inner = index.innerIndexForPartition(partition.getKey());
            for (int source : partition.getValue()) {
                Map<Integer, RationalTemporalReference.Profile> oracle =
                        RationalTemporalReference.allDestinationProfiles(graph, source, HORIZON);
                for (int destination : partition.getValue()) {
                    RationalTemporalReference.Profile expected = oracle.getOrDefault(
                            destination, new RationalTemporalReference.Profile(List.of())).asTravelTime();
                    var actual = inner.query(source, destination, HORIZON).exactTravelTimeProfile();
                    assertTrue(expected.sameFunction(
                                    RationalTemporalReference.Profile.fromProductionForComparison(actual)),
                            "inner augmented mismatch " + partition.getKey() + ": "
                                    + source + "->" + destination + " expected=" + expected.segments()
                                    + " actual=" + actual.segments());
                }
            }
        }
        for (int source : graph.nodeIds()) {
            Map<Integer, RationalTemporalReference.Profile> oracle =
                    RationalTemporalReference.allDestinationProfiles(graph, source, HORIZON);
            Map<Integer, edu.ipcmax.baselines.exact.ExactArrivalProfile> direct =
                    new FifoFastestPathProfileSearch(graph).search(source, HORIZON).exactProfiles();
            for (int destination : graph.nodeIds()) {
                RationalTemporalReference.Profile expected = oracle.getOrDefault(
                        destination, new RationalTemporalReference.Profile(List.of())).asTravelTime();
                var partitioned = index.query(source, destination, HORIZON).exactTravelTimeProfile();
                var safelyPruned = index.queryWithSafePruning(source, destination, HORIZON)
                        .exactTravelTimeProfile();
                var fullUnpruned = unpruned.query(source, destination, HORIZON).exactTravelTimeProfile();
                var fullPruned = pruned.query(source, destination, HORIZON).exactTravelTimeProfile();
                var directProfile = direct.getOrDefault(destination,
                        edu.ipcmax.baselines.exact.ExactArrivalProfile.empty()).asTravelTime();
                assertTrue(expected.sameFunction(RationalTemporalReference.Profile
                                .fromProductionForComparison(partitioned)),
                        "partitioned mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(RationalTemporalReference.Profile
                                .fromProductionForComparison(safelyPruned)),
                        "safe-prune mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(RationalTemporalReference.Profile
                                .fromProductionForComparison(fullUnpruned)),
                        "unpruned mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(RationalTemporalReference.Profile
                                .fromProductionForComparison(fullPruned)),
                        "pruned mismatch " + source + "->" + destination);
                assertTrue(expected.sameFunction(RationalTemporalReference.Profile
                                .fromProductionForComparison(directProfile)),
                        "direct functional search mismatch " + source + "->" + destination);
                if (metadata.boundaryVertexIds().contains(source)
                        && metadata.boundaryVertexIds().contains(destination)) {
                    var boundary = boundaryIndex.query(source, destination, HORIZON).exactTravelTimeProfile();
                    assertTrue(expected.sameFunction(RationalTemporalReference.Profile
                                    .fromProductionForComparison(boundary)),
                            "Boundary-THop mismatch " + source + "->" + destination);
                }
            }
        }
    }

    @Test
    void fastestRouteCanLeaveAndReenterItsPartition() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("counterexample-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("counterexample-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        TDGraph inducedGraph = inducedPartitionGraph(graph, metadata, 10);
        ExactTemporalProfile induced = SimplePathProfileOracle.allDestinations(
                        inducedGraph, 3, HORIZON).get(2).asTravelTime();
        ExactTemporalProfile leavesAndReenters = index.query(3, 2, HORIZON).travelTimeProfile();
        assertEquals(6.0, induced.valueAt(20), "the fastest induced route stays inside partition 10");
        assertEquals(3.0, leavesAndReenters.valueAt(20),
                "the fastest route leaves and re-enters partition 10");
    }

    @Test
    void completedPartitionCheckpointsReassembleTheSameQueryableIndex() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("checkpoint-boundary.bthp");
        Path innerFile = temporaryDirectory.resolve("checkpoint-inner.pthp");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);

        PartitionedTHopIndex first = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        assertEquals(0, first.constructionStatistics().resumedCheckpointCount());
        Path checkpointDirectory = innerFile.resolveSibling(innerFile.getFileName() + ".checkpoints");
        try (var checkpoints = Files.list(checkpointDirectory)) {
            assertEquals(metadata.partitionCount(), checkpoints.count());
        }
        var expected = first.query(3, 2, HORIZON).exactTravelTimeProfile();

        Files.delete(innerFile);
        PartitionedTHopIndex resumed = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        assertEquals(metadata.partitionCount(), resumed.constructionStatistics().resumedCheckpointCount());
        assertTrue(expected.sameFunction(resumed.query(3, 2, HORIZON).exactTravelTimeProfile()));
        PartitionedTHopIndex loaded = PartitionedTHopIndex.load(graph, boundaryFile, innerFile);
        assertTrue(expected.sameFunction(loaded.query(3, 2, HORIZON).exactTravelTimeProfile()));
    }

    @Test
    void saveReloadIsDeterministicLazyAndValidatesChecksumsAndGraphIdentity() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("storage-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("storage-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        PartitionedTHopIndex loaded = PartitionedTHopIndex.load(graph, boundaryFile, innerFile);
        assertEquals(index.constructionStatistics().forwardSearchExpansions(),
                loaded.constructionStatistics().forwardSearchExpansions());
        assertEquals(index.constructionStatistics().backwardSearchExpansions(),
                loaded.constructionStatistics().backwardSearchExpansions());
        assertEquals(index.constructionStatistics().searchExpansionsSkipped(),
                loaded.constructionStatistics().searchExpansionsSkipped());
        Path copied = temporaryDirectory.resolve("storage-copy.pthop");
        assertEquals(Files.size(innerFile), loaded.write(copied));
        assertEquals(-1L, Files.mismatch(innerFile, copied));
        var exactRoot = edu.ipcmax.baselines.exact.ExactTime.of(
                java.math.BigInteger.valueOf(62), java.math.BigInteger.valueOf(3));
        var originalProfile = index.query(4, 1, HORIZON).exactTravelTimeProfile();
        var loadedProfile = loaded.query(4, 1, HORIZON).exactTravelTimeProfile();
        assertTrue(originalProfile.sameFunction(loadedProfile));
        // 62/3 is an exact rational event root of the construction but lies strictly inside the piece t/2-1 of the final function, so a canonical profile has no
        // piece boundary there; what must be preserved by construction and by save/reload is the exact value at the root (t/2 - 1 = 28/3)
        var expectedAtRoot = edu.ipcmax.baselines.exact.ExactTime.of(java.math.BigInteger.valueOf(28), java.math.BigInteger.valueOf(3));
        assertEquals(expectedAtRoot, originalProfile.valueAtExact(exactRoot), "the persisted partitioned result carries the exact rational value at the event root");
        assertEquals(expectedAtRoot, loadedProfile.valueAtExact(exactRoot), "reload preserves the exact rational value at the event root");
        assertEquals(originalProfile.segments(), loadedProfile.segments(), "reload preserves every exact piece");

        byte[] corrupted = Files.readAllBytes(innerFile);
        corrupted[corrupted.length / 2] ^= 1;
        Path corruptedFile = temporaryDirectory.resolve("storage-corrupted.pthop");
        Files.write(corruptedFile, corrupted);
        assertThrows(IOException.class,
                () -> PartitionedTHopIndex.load(graph, boundaryFile, corruptedFile));
        assertThrows(IOException.class,
                () -> PartitionedTHopIndex.load(withChangedTravelTime(graph), boundaryFile, innerFile));
    }

    @Test
    void onePartitionWithoutBoundariesAndAnIsolatedPartitionAreQueryable() throws Exception {
        TDGraph graph = graph(
                List.of(1, 2, 3, 4),
                edge(0, 1, 2, constant(2), 0),
                edge(1, 2, 3, constant(3), 0));
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(
                graph, Map.of(1, 7, 2, 7, 3, 7, 4, 9));
        Path boundaryFile = temporaryDirectory.resolve("no-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("no-boundary.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        ExactTemporalProfile route = index.query(1, 3, HORIZON).travelTimeProfile();
        assertEquals(5.0, route.valueAt(10));
        assertTrue(index.query(1, 4, HORIZON).travelTimeProfile().isEmpty());
        assertEquals("same-partition", index.query(4, 4, HORIZON).queryStatistics().queryType());
    }

    @Test
    void crossPartitionQueriesReportWorkAndRejectDepartureDomainsOutsideTheIndex() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("work-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("work-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        PartitionedTHopIndex.QueryResult result = index.query(1, 10, Domain.closed(0, 20));
        PartitionedTHopIndex.QueryResult optimized = index.queryWithSafePruning(
                1, 10, Domain.closed(0, 20));
        assertEquals("cross-partition", result.queryStatistics().queryType());
        assertTrue(result.queryStatistics().boundaryCombinationsExamined() > 0);
        assertTrue(result.queryStatistics().boundaryCombinationsSkipped() > 0);
        assertTrue(result.travelTimeProfile().sameFunction(optimized.travelTimeProfile()));
        assertThrows(IllegalArgumentException.class,
                () -> index.query(1, 10, Domain.closed(-1, 20)));
    }

    @Test
    void preferenceScoresDoNotChangeTheFastestPathProfile() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("score-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("score-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex original = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);
        TDGraph scoreChangedGraph = withUniformScore(graph, 1234);
        PartitionedTHopIndex changed = PartitionedTHopIndex.load(
                scoreChangedGraph, boundaryFile, innerFile);

        assertTrue(original.query(1, 10, Domain.closed(0, 20)).travelTimeProfile().sameFunction(
                changed.query(1, 10, Domain.closed(0, 20)).travelTimeProfile()));
    }

    @Test
    void exactSupportCrossingReturnsTheCompleteQueryInsteadOfRejectingIt() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("support-boundary.bthop");
        Path innerFile = temporaryDirectory.resolve("support-inner.pthop");
        BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);
        PartitionedTHopIndex index = PartitionedTHopIndex.build(graph, boundaryFile, innerFile);

        var result = index.query(4, 1, HORIZON);
        assertTrue(result.exactTravelTimeProfile().domain().contains(
                edu.ipcmax.baselines.exact.ExactTime.parseDecimal("20.666666666667")));
        assertEquals(0, result.queryStatistics().supportBoundaryRejections());
    }

    @Test
    void severalBoundaryShortcutProfilesComposeToTheIndependentFullRoute() throws Exception {
        TDGraph graph = mainGraph();
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments());
        Path boundaryFile = temporaryDirectory.resolve("nested-shortcuts.bthop");
        BoundaryTHopIndex boundary = BoundaryTHopIndex.build(graph, metadata, HORIZON, boundaryFile);

        var first = boundary.query(5, 6, HORIZON).exactTravelTimeProfile().asArrivalProfile();
        var second = boundary.query(6, 7, HORIZON).exactTravelTimeProfile().asArrivalProfile();
        var third = boundary.query(7, 2, HORIZON).exactTravelTimeProfile().asArrivalProfile();
        var nested = first.composeAfter(second).composeAfter(third).asTravelTime();
        var expected = RationalTemporalReference.allDestinationProfiles(graph, 5, HORIZON)
                .get(2).asTravelTime();
        var directBoundary = boundary.query(5, 2, HORIZON).exactTravelTimeProfile();

        assertTrue(expected.sameFunction(RationalTemporalReference.Profile.fromProductionForComparison(nested)));
        assertTrue(directBoundary.sameFunction(nested));
    }

    private static TDGraph mainGraph() {
        PiecewiseLinearFn changing = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 8),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(60, 0)));
        PiecewiseLinearFn plateau = new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, 10),
                new PiecewiseLinearFn.Breakpoint(10, 0),
                new PiecewiseLinearFn.Breakpoint(20, 0),
                new PiecewiseLinearFn.Breakpoint(30, 5),
                new PiecewiseLinearFn.Breakpoint(60, 5)));
        return graph(
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                edge(0, 1, 2, constant(2), 0),
                edge(1, 2, 3, constant(2), 0),
                edge(2, 3, 4, changing, 0),
                edge(3, 4, 5, plateau, 0),
                edge(4, 5, 6, constant(1), 0),
                edge(5, 6, 7, constant(1), 0),
                edge(6, 7, 2, constant(1), 0),
                edge(7, 3, 1, constant(4), 0),
                edge(8, 1, 2, constant(4), 0),
                edge(9, 3, 2, constant(9), 0),
                edge(10, 8, 9, constant(2), 0),
                edge(11, 8, 9, constant(2), 0),
                edge(12, 9, 10, constant(2), 0),
                edge(13, 10, 8, constant(2), 0),
                edge(14, 8, 10, constant(4), 0));
    }

    private static Map<Integer, Integer> assignments() {
        return Map.of(
                1, 10, 2, 10, 3, 10,
                4, 20, 5, 20,
                6, 30, 7, 30,
                8, 40, 9, 40,
                10, 50);
    }

    private static PiecewiseLinearFn constant(double value) {
        return new PiecewiseLinearFn(List.of(
                new PiecewiseLinearFn.Breakpoint(0, value),
                new PiecewiseLinearFn.Breakpoint(60, value)));
    }

    private static Edge edge(int id, int source, int target, PiecewiseLinearFn travel, int score) {
        return new Edge(id, source, target, 1, travel.minTravelTime(), travel,
                PiecewiseConstFn.constant(travel.domain(), score));
    }

    private static TDGraph graph(List<Integer> vertices, Edge... edges) {
        List<Node> nodes = new ArrayList<>();
        for (int id : vertices) {
            nodes.add(new Node(id, id, 0));
        }
        return new TDGraph(nodes, List.of(edges));
    }

    private static TDGraph inducedPartitionGraph(
            TDGraph graph, GraphPartitionMetadata metadata, int partitionId) {
        List<Node> nodes = metadata.verticesByPartition().get(partitionId).stream()
                .map(graph::node)
                .toList();
        List<Edge> edges = new ArrayList<>();
        for (int arcId : metadata.internalArcIdsByPartition().get(partitionId)) {
            Edge original = graph.edges().get(arcId);
            edges.add(new Edge(
                    edges.size(), original.source(), original.target(), original.distance(),
                    original.baseTravelTime(), original.travelTimeFunction(), original.scoreFunction()));
        }
        return new TDGraph(nodes, edges);
    }

    private static TDGraph withUniformScore(TDGraph graph, int score) {
        List<Edge> edges = graph.edges().stream()
                .map(edge -> new Edge(
                        edge.arcId(), edge.source(), edge.target(), edge.distance(),
                        edge.baseTravelTime(), edge.travelTimeFunction(),
                        PiecewiseConstFn.constant(edge.scoreFunction().domain(), score)))
                .toList();
        return new TDGraph(graph.nodeIds().stream().map(graph::node).toList(), edges);
    }

    private static TDGraph withChangedTravelTime(TDGraph graph) {
        List<Edge> edges = graph.edges().stream()
                .map(edge -> {
                    PiecewiseLinearFn travel = edge.arcId() == 0
                            ? constant(3)
                            : edge.travelTimeFunction();
                    return new Edge(
                            edge.arcId(), edge.source(), edge.target(), edge.distance(),
                            travel.minTravelTime(), travel, edge.scoreFunction());
                })
                .toList();
        return new TDGraph(graph.nodeIds().stream().map(graph::node).toList(), edges);
    }

    private static List<String> summarize(ExactTemporalProfile profile) {
        return profile.cells().stream()
                .map(cell -> cell.domain().intervals() + " " + cell.breakpoints())
                .toList();
    }

    private static String firstMismatch(ExactTemporalProfile expected, ExactTemporalProfile actual) {
        if (!expected.domain().equals(actual.domain())) {
            return "domain expected=" + expected.domain().intervals()
                    + " actual=" + actual.domain().intervals();
        }
        TreeSet<Double> cuts = new TreeSet<>(expected.domain().breakpoints());
        expected.cells().forEach(cell -> cell.breakpoints().forEach(point -> cuts.add(point.minute())));
        actual.cells().forEach(cell -> cell.breakpoints().forEach(point -> cuts.add(point.minute())));
        for (double cut : cuts) {
            if (!expected.domain().contains(cut) || !actual.domain().contains(cut)) {
                return "support at " + cut + " expected=" + expected.domain().contains(cut)
                        + " actual=" + actual.domain().contains(cut);
            }
            if (Domain.canonicalTime(expected.valueAt(cut))
                    != Domain.canonicalTime(actual.valueAt(cut))) {
                return "at " + cut + " expected=" + expected.valueAt(cut)
                        + " actual=" + actual.valueAt(cut);
            }
        }
        return "profile cells differ: expected=" + summarize(expected)
                + " actual=" + summarize(actual);
    }

}
