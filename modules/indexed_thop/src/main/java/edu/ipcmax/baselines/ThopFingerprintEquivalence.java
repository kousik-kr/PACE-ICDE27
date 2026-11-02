package edu.ipcmax.baselines;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphLoader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evidence that the streamed graph fingerprint equals the original one.
 *
 * <p>For graphs whose serialization fits one Java array it compares three digests: the original in-memory implementation, the streamed implementation used by the
 * index, and an independently written encoder here (explicit big-endian {@link ByteBuffer} chunks, no {@code DataOutputStream}). For graphs too large for the
 * original it still compares the streamed and the independent encoders and checks the hashed byte count against the closed-form size of the layout. It also checks
 * that a one-element mutation of the graph changes the digest and that a change outside the fingerprinted fields (a score function) does not.</p>
 *
 * <p>Usage: {@code ThopFingerprintEquivalence DATASET_DIR OUT.json [skipMutations]}.</p>
 */
public final class ThopFingerprintEquivalence {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final long JAVA_ARRAY_LIMIT = Integer.MAX_VALUE - 8L;

    private ThopFingerprintEquivalence() { }

    /** Digest, hashed bytes and elapsed time of the independent encoder. */
    public record Independent(byte[] digest, long bytes, long nanos) {
        public String hex() {
            return HexFormat.of().formatHex(digest);
        }
    }

    /** Closed-form size of the serialization: 4 + 4n + 4 + sum over arcs of (12 + (4 + 18 intervals) + 4 + 16 breakpoints). */
    public static long analyticByteCount(TDGraph graph) {
        long total = 4L + 4L * graph.nodeCount() + 4L;
        for (Edge edge : graph.edges()) {
            total += 12L + 4L + 18L * edge.travelTimeFunction().domain().intervals().size() + 4L + 16L * edge.travelTimeFunction().breakpoints().size();
        }
        return total;
    }

    /** Independent re-implementation of the serialization with explicit big-endian chunked encoding (no DataOutputStream). */
    public static Independent independentFingerprint(TDGraph graph) throws IOException {
        long started = System.nanoTime();
        MessageDigest digest = sha256();
        ByteBuffer buffer = ByteBuffer.allocate(1 << 20); // big-endian by default
        long[] bytes = {0};
        Runnable flush = () -> {
            digest.update(buffer.array(), 0, buffer.position());
            bytes[0] += buffer.position();
            buffer.clear();
        };
        java.util.function.IntConsumer room = need -> {
            if (buffer.remaining() < need) flush.run();
        };
        room.accept(8);
        buffer.putInt(graph.nodeIds().size());
        for (int nodeId : graph.nodeIds()) {
            room.accept(4);
            buffer.putInt(nodeId);
        }
        room.accept(4);
        buffer.putInt(graph.edgeCount());
        for (Edge edge : graph.edges()) {
            var intervals = edge.travelTimeFunction().domain().intervals();
            var points = edge.travelTimeFunction().breakpoints();
            room.accept(12 + 4 + 18 * intervals.size() + 4 + 16 * points.size());
            buffer.putInt(edge.arcId());
            buffer.putInt(edge.source());
            buffer.putInt(edge.target());
            buffer.putInt(intervals.size());
            for (var interval : intervals) {
                buffer.putLong(Double.doubleToLongBits(interval.start()));
                buffer.putLong(Double.doubleToLongBits(interval.end()));
                buffer.put((byte) (interval.startInclusive() ? 1 : 0));
                buffer.put((byte) (interval.endInclusive() ? 1 : 0));
            }
            buffer.putInt(points.size());
            for (PiecewiseLinearFn.Breakpoint point : points) {
                buffer.putLong(Double.doubleToLongBits(point.minute()));
                buffer.putLong(Double.doubleToLongBits(point.value()));
            }
        }
        flush.run();
        return new Independent(digest.digest(), bytes[0], System.nanoTime() - started);
    }

    public static void main(String[] args) throws Exception {
        Path dataset = Path.of(args[0]);
        boolean mutations = !(args.length > 2 && args[2].equals("skipMutations"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "thop-streamed-fingerprint-equivalence-v1");
        out.put("dataset_dir", dataset.toString());
        long t0 = System.nanoTime();
        TDGraph graph = new GeneratedGraphLoader().load(dataset).graph();
        out.put("graph_load_nanos", System.nanoTime() - t0);
        out.put("vertices", graph.nodeCount());
        out.put("directed_edges", graph.edgeCount());
        long analytic = analyticByteCount(graph);
        out.put("analytic_serialization_bytes", analytic);
        out.put("fits_one_java_array", analytic <= JAVA_ARRAY_LIMIT);

        long s0 = System.nanoTime();
        BoundaryTHopIndex.GraphFingerprint streamed = BoundaryTHopIndex.graphFingerprintStreamed(graph);
        long streamedNanos = System.nanoTime() - s0;
        String streamedHex = HexFormat.of().formatHex(streamed.digest());
        out.put("streamed", Map.of("digest_sha256", streamedHex, "bytes_hashed", streamed.bytesHashed(), "nanos", streamedNanos));
        BoundaryTHopIndex.GraphFingerprint again = BoundaryTHopIndex.graphFingerprintStreamed(graph);
        out.put("streamed_repeat_identical", java.util.Arrays.equals(streamed.digest(), again.digest()) && streamed.bytesHashed() == again.bytesHashed());
        Independent independent = independentFingerprint(graph);
        out.put("independent_encoder", Map.of("digest_sha256", independent.hex(), "bytes_hashed", independent.bytes(), "nanos", independent.nanos()));
        out.put("streamed_equals_independent", independent.hex().equals(streamedHex));
        out.put("hashed_bytes_equal_closed_form", streamed.bytesHashed() == analytic && independent.bytes() == analytic);
        boolean referenceApplies = analytic <= JAVA_ARRAY_LIMIT - 64;
        boolean referenceEqual = false;
        if (referenceApplies) {
            long r0 = System.nanoTime();
            byte[] reference = BoundaryTHopIndex.graphFingerprintReference(graph);
            long referenceNanos = System.nanoTime() - r0;
            String referenceHex = HexFormat.of().formatHex(reference);
            referenceEqual = referenceHex.equals(streamedHex);
            out.put("original_in_memory", Map.of("digest_sha256", referenceHex, "nanos", referenceNanos));
            out.put("streamed_equals_original", referenceEqual);
        } else {
            out.put("original_in_memory", "NOT_APPLICABLE: the serialization exceeds a Java array; the original implementation cannot run on this graph");
        }
        if (mutations) {
            Map<String, Object> mutated = new LinkedHashMap<>();
            List<Edge> edges = graph.edges();
            int middle = edges.size() / 2;
            mutated.put("breakpoint_value_changed_by_1e-6", differs(graph, streamedHex, mutateEdge(graph, middle, e -> withBreakpoint(e, e.travelTimeFunction().breakpoints().size() / 2, 0, e.travelTimeFunction().breakpoints().get(e.travelTimeFunction().breakpoints().size() / 2).value() + 1e-6))));
            mutated.put("breakpoint_minute_changed_by_1e-6", differs(graph, streamedHex, mutateEdge(graph, middle, e -> withBreakpoint(e, 1, 1, e.travelTimeFunction().breakpoints().get(1).minute() + 1e-6))));
            mutated.put("arc_target_changed", differs(graph, streamedHex, mutateEdge(graph, middle, e -> new Edge(e.arcId(), e.source(), differentNode(graph, e.target()), e.distance(), e.baseTravelTime(), e.travelTimeFunction(), e.scoreFunction()))));
            mutated.put("last_arc_removed", differs(graph, streamedHex, new TDGraph(graph.nodeIds().stream().map(graph::node).toList(), edges.subList(0, edges.size() - 1))));
            mutated.put("arc_distance_changed_expected_unchanged", unchanged(graph, streamedHex, mutateEdge(graph, middle, e -> new Edge(e.arcId(), e.source(), e.target(), e.distance() + 1, e.baseTravelTime(), e.travelTimeFunction(), e.scoreFunction()))));
            mutated.put("score_function_changed_expected_unchanged", unchanged(graph, streamedHex, mutateEdge(graph, middle, e -> new Edge(e.arcId(), e.source(), e.target(), e.distance(), e.baseTravelTime(), e.travelTimeFunction(),
                    edu.ipcmax.core.function.PiecewiseConstFn.constant(e.scoreFunction().domain(), 7)))));
            out.put("mutation_checks", mutated);
            boolean mutationsOk = mutated.values().stream().allMatch(v -> Boolean.TRUE.equals(v));
            out.put("mutation_checks_as_expected", mutationsOk);
        }
        boolean ok = Boolean.TRUE.equals(out.get("streamed_equals_independent")) && Boolean.TRUE.equals(out.get("hashed_bytes_equal_closed_form"))
                && Boolean.TRUE.equals(out.get("streamed_repeat_identical")) && (!referenceApplies || referenceEqual)
                && (!mutations || Boolean.TRUE.equals(out.get("mutation_checks_as_expected")));
        out.put("verdict", ok ? "EQUIVALENT" : "NOT_EQUIVALENT");
        Files.writeString(Path.of(args[1]), JSON.writeValueAsString(out) + "\n");
        System.out.println(out.get("verdict") + " vertices=" + graph.nodeCount() + " arcs=" + graph.edgeCount() + " bytes=" + analytic + " digest=" + streamedHex);
        if (!ok) System.exit(1);
    }

    private static boolean differs(TDGraph original, String originalHex, TDGraph mutated) throws IOException {
        return !HexFormat.of().formatHex(BoundaryTHopIndex.graphFingerprintStreamed(mutated).digest()).equals(originalHex);
    }

    private static boolean unchanged(TDGraph original, String originalHex, TDGraph mutated) throws IOException {
        return !differs(original, originalHex, mutated);
    }

    private static int differentNode(TDGraph graph, int target) {
        List<Integer> ids = graph.nodeIds();
        return ids.get(0) == target ? ids.get(1) : ids.get(0);
    }

    private static Edge withBreakpoint(Edge e, int index, int field, double replacement) {
        List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>(e.travelTimeFunction().breakpoints());
        PiecewiseLinearFn.Breakpoint old = points.get(index);
        points.set(index, field == 0 ? new PiecewiseLinearFn.Breakpoint(old.minute(), replacement) : new PiecewiseLinearFn.Breakpoint(replacement, old.value()));
        return new Edge(e.arcId(), e.source(), e.target(), e.distance(), e.baseTravelTime(), new PiecewiseLinearFn(points), e.scoreFunction());
    }

    private static TDGraph mutateEdge(TDGraph graph, int index, java.util.function.UnaryOperator<Edge> change) {
        List<Edge> edges = new ArrayList<>(graph.edges());
        edges.set(index, change.apply(edges.get(index)));
        List<Node> nodes = graph.nodeIds().stream().map(graph::node).toList();
        return new TDGraph(nodes, edges);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
