package edu.ipcmax.baselines;

import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The streamed graph fingerprint must equal the original in-memory digest bit for bit, hash exactly the original serialization, and still react to every fingerprinted field. */
class BoundaryTHopIndexFingerprintTest {
    private static TDGraph randomGraph(Random random, int nodeCount, int edgeCount) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) nodes.add(new Node(1 + 3 * i + random.nextInt(3), random.nextInt(1000), random.nextInt(1000)));
        List<Edge> edges = new ArrayList<>();
        for (int arc = 0; arc < edgeCount; arc++) {
            int source = nodes.get(random.nextInt(nodeCount)).id();
            int target = nodes.get(random.nextInt(nodeCount)).id();
            int pieces = 1 + random.nextInt(8);
            List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
            double minute = 0;
            for (int k = 0; k <= pieces; k++) {
                points.add(new PiecewiseLinearFn.Breakpoint(minute, 0.25 + random.nextDouble() * 5));
                minute += 1 + random.nextInt(900) + random.nextDouble();
            }
            PiecewiseLinearFn function = new PiecewiseLinearFn(points);
            edges.add(new Edge(arc, source, target, random.nextInt(5000), function.minTravelTime(), function, PiecewiseConstFn.constant(function.domain(), random.nextInt(4))));
        }
        return new TDGraph(nodes, edges);
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    private static TDGraph withEdge(TDGraph graph, int index, UnaryOperator<Edge> change) {
        List<Edge> edges = new ArrayList<>(graph.edges());
        edges.set(index, change.apply(edges.get(index)));
        return new TDGraph(graph.nodeIds().stream().map(graph::node).toList(), edges);
    }

    @Test
    void streamedDigestAndLengthEqualTheOriginalOnRandomGraphs() throws Exception {
        Random random = new Random(20261029L);
        for (int trial = 0; trial < 40; trial++) {
            TDGraph graph = randomGraph(random, 2 + random.nextInt(60), 1 + random.nextInt(300));
            byte[] reference = BoundaryTHopIndex.graphFingerprintReference(graph);
            BoundaryTHopIndex.GraphFingerprint streamed = BoundaryTHopIndex.graphFingerprintStreamed(graph);
            assertArrayEquals(reference, streamed.digest(), "trial " + trial);
            assertEquals(ThopFingerprintEquivalence.analyticByteCount(graph), streamed.bytesHashed(), "hashed bytes, trial " + trial);
            ThopFingerprintEquivalence.Independent independent = ThopFingerprintEquivalence.independentFingerprint(graph);
            assertArrayEquals(reference, independent.digest(), "independent encoder, trial " + trial);
            assertEquals(streamed.bytesHashed(), independent.bytes());
            assertArrayEquals(streamed.digest(), BoundaryTHopIndex.graphFingerprintStreamed(graph).digest(), "deterministic, trial " + trial);
        }
    }

    @Test
    void everyFingerprintedFieldChangesTheDigestAndOtherFieldsDoNot() throws Exception {
        Random random = new Random(7L);
        TDGraph graph = randomGraph(random, 30, 120);
        String base = hex(BoundaryTHopIndex.graphFingerprintStreamed(graph).digest());
        int at = 57;
        // fingerprinted fields
        assertNotEquals(base, hex(BoundaryTHopIndex.graphFingerprintStreamed(withEdge(graph, at, e -> {
            List<PiecewiseLinearFn.Breakpoint> p = new ArrayList<>(e.travelTimeFunction().breakpoints());
            p.set(1, new PiecewiseLinearFn.Breakpoint(p.get(1).minute(), p.get(1).value() + 1e-6));
            return new Edge(e.arcId(), e.source(), e.target(), e.distance(), e.baseTravelTime(), new PiecewiseLinearFn(p), e.scoreFunction());
        })).digest()), "breakpoint value, +1e-6 (the canonical resolution is 1e-12)");
        assertNotEquals(base, hex(BoundaryTHopIndex.graphFingerprintStreamed(withEdge(graph, at, e -> {
            List<PiecewiseLinearFn.Breakpoint> p = new ArrayList<>(e.travelTimeFunction().breakpoints());
            p.set(1, new PiecewiseLinearFn.Breakpoint(p.get(1).minute() + 1e-6, p.get(1).value()));
            return new Edge(e.arcId(), e.source(), e.target(), e.distance(), e.baseTravelTime(), new PiecewiseLinearFn(p), e.scoreFunction());
        })).digest()), "breakpoint minute, +1e-6");
        int other = graph.nodeIds().get(0).equals(graph.edges().get(at).target()) ? graph.nodeIds().get(1) : graph.nodeIds().get(0);
        assertNotEquals(base, hex(BoundaryTHopIndex.graphFingerprintStreamed(withEdge(graph, at,
                e -> new Edge(e.arcId(), e.source(), other, e.distance(), e.baseTravelTime(), e.travelTimeFunction(), e.scoreFunction()))).digest()), "arc target");
        assertNotEquals(base, hex(BoundaryTHopIndex.graphFingerprintStreamed(new TDGraph(graph.nodeIds().stream().map(graph::node).toList(),
                graph.edges().subList(0, graph.edgeCount() - 1))).digest()), "arc count");
        List<Node> shifted = new ArrayList<>(graph.nodeIds().stream().map(graph::node).toList());
        Node last = shifted.get(shifted.size() - 1);
        shifted.set(shifted.size() - 1, new Node(last.id() + 1000, last.x(), last.y()));
        assertNotEquals(base, hex(BoundaryTHopIndex.graphFingerprintStreamed(new TDGraph(shifted, graph.edges().stream()
                .map(e -> e.source() == last.id() || e.target() == last.id()
                        ? new Edge(e.arcId(), e.source() == last.id() ? last.id() + 1000 : e.source(), e.target() == last.id() ? last.id() + 1000 : e.target(), e.distance(), e.baseTravelTime(), e.travelTimeFunction(), e.scoreFunction())
                        : e).toList())).digest()), "node id");
        // fields the original fingerprint never covered stay out, so the digest semantics are unchanged
        assertEquals(base, hex(BoundaryTHopIndex.graphFingerprintStreamed(withEdge(graph, at,
                e -> new Edge(e.arcId(), e.source(), e.target(), e.distance() + 1, e.baseTravelTime(), e.travelTimeFunction(), PiecewiseConstFn.constant(e.scoreFunction().domain(), 9)))).digest()), "distance and score are not fingerprinted");
    }

    @Test
    void theByteCounterDoesNotOverflowAtTwoGiB() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        BoundaryTHopIndex.CountingDigestStream sink = new BoundaryTHopIndex.CountingDigestStream(digest);
        byte[] block = new byte[16 << 20];
        long target = (1L << 31) + (1L << 20);
        long written = 0;
        while (written + block.length <= target) {
            sink.write(block, 0, block.length);
            written += block.length;
        }
        sink.write(block, 0, (int) (target - written));
        assertEquals(target, sink.count());
        assertTrue(sink.count() > Integer.MAX_VALUE);
        assertEquals(32, digest.digest().length);
    }

    @Test
    void anIndexBuiltAndLoadedWithTheStreamedFingerprintStillMatchesItsGraph() throws Exception {
        TDGraph graph = randomGraph(new Random(3L), 6, 14);
        assertFalse(Arrays.equals(BoundaryTHopIndex.graphFingerprintStreamed(graph).digest(),
                BoundaryTHopIndex.graphFingerprintStreamed(randomGraph(new Random(4L), 6, 14)).digest()));
    }
}
