package edu.ipcmax.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.ipcmax.baselines.BoundaryTHopIndex;
import edu.ipcmax.baselines.GraphPartitionMetadata;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.function.PiecewiseConstFn;
import edu.ipcmax.core.function.PiecewiseLinearFn;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end regression of the query phase on a small synthetic graph (mechanics only): a built index, frozen-style rows, two repetitions, receipts, resume and refusal of a changed index. */
class ThopFullNetworkQueryBatchTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static TDGraph ring(Random random, int nodeCount, int extraArcs) {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) nodes.add(new Node(i + 1, random.nextInt(1000), random.nextInt(1000)));
        List<Edge> edges = new ArrayList<>();
        for (int arc = 0; arc < nodeCount + extraArcs; arc++) {
            int source = 1 + (arc < nodeCount ? arc : random.nextInt(nodeCount));
            int target = 1 + (arc < nodeCount ? (arc + 1) % nodeCount : random.nextInt(nodeCount));
            if (source == target) target = source % nodeCount + 1;
            List<PiecewiseLinearFn.Breakpoint> points = new ArrayList<>();
            double minute = 0;
            double value = 1 + random.nextInt(6) + random.nextInt(4) / 4.0;
            points.add(new PiecewiseLinearFn.Breakpoint(minute, value));
            for (int k = 0; k < 3 + random.nextInt(3); k++) {
                double step = 20 + random.nextInt(40);
                minute += step;
                value = Math.max(0.5, value + (random.nextInt(21) - 10) / 10.0 * 0.8 * (step / 20.0));
                points.add(new PiecewiseLinearFn.Breakpoint(minute, value));
            }
            points.add(new PiecewiseLinearFn.Breakpoint(300, points.get(points.size() - 1).value()));
            PiecewiseLinearFn function = new PiecewiseLinearFn(points);
            edges.add(new Edge(arc, source, target, 100, function.minTravelTime(), function, PiecewiseConstFn.constant(function.domain(), arc % 3 == 0 ? 2 : 0)));
        }
        return new TDGraph(nodes, edges);
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void batchProducesImmutableReceiptsResumesAndRefusesAChangedIndex(@TempDir Path dir) throws Exception {
        TDGraph graph = ring(new Random(5L), 10, 14);
        Map<Integer, Integer> assignment = new TreeMap<>();
        for (int node = 1; node <= 10; node++) assignment.put(node, node <= 5 ? 0 : 1);
        GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignment);
        Path boundary = dir.resolve("boundary.bthp");
        Path inner = dir.resolve("inner.pthp");
        BoundaryTHopIndex.build(graph, metadata, Domain.closed(0, 300), boundary, metadata.boundaryVertexIds(), 1);
        PartitionedTHopIndex.build(graph, boundary, inner);

        // frozen-style query rows (the rows are hashed as lines) and an Experiment-B-style manifest with two repetitions each
        String[] lines = {
                "{\"query_id\":\"SYN-A\",\"source\":2,\"destination\":9,\"interval_start\":20.0,\"interval_end\":60.0,\"budget\":100.0}",
                "{\"query_id\":\"SYN-B\",\"source\":8,\"destination\":3,\"interval_start\":30.0,\"interval_end\":70.0,\"budget\":4.5}",
                "{\"query_id\":\"SYN-C\",\"source\":1,\"destination\":6,\"interval_start\":10.0,\"interval_end\":50.0,\"budget\":60.0}"};
        Path queries = dir.resolve("queries.jsonl");
        Files.write(queries, List.of(lines));
        ObjectNode manifest = JSON.createObjectNode();
        ArrayNode planned = manifest.putArray("thop_planned_executions");
        for (String line : lines) {
            String id = JSON.readTree(line).get("query_id").asText();
            for (int rep = 1; rep <= 2; rep++) {
                ObjectNode e = planned.addObject();
                e.put("exec_id", "thop-fastest-profile__SYN__" + id + "__r" + rep);
                e.put("dataset", "SYN");
                e.put("query_id", id);
                e.put("query_row_sha256", sha(line));
                e.put("rep", rep);
            }
        }
        Path manifestFile = dir.resolve("manifest.json");
        Files.writeString(manifestFile, manifest.toString());
        ObjectNode config = JSON.createObjectNode();
        config.put("dataset", "SYN");
        config.put("dataset_dir", dir.toString());
        config.put("boundary_file", boundary.toString());
        config.put("inner_file", inner.toString());
        config.put("manifest_file", manifestFile.toString());
        config.put("queries_file", queries.toString());
        config.put("out_dir", dir.resolve("out").toString());
        config.put("campaign_id", "test");
        config.put("executable_sha256", "test");
        config.put("independent_samples", 9);
        config.putObject("expected").put("graph_semantic_checksum", PaceBench.graphSemanticChecksum(graph));

        assertEquals(0, ThopFullNetworkQueryBatch.run(config, graph));
        JsonNode first = JSON.readTree(dir.resolve("out/query_batch_manifest.json").toFile());
        assertEquals("COMPLETE", first.get("status").asText());
        assertEquals(6, first.get("executions_run_now").asInt());
        for (String line : lines) {
            String id = JSON.readTree(line).get("query_id").asText();
            JsonNode r1 = JSON.readTree(dir.resolve("out/receipts/thop-fastest-profile__SYN__" + id + "__r1.json").toFile());
            JsonNode r2 = JSON.readTree(dir.resolve("out/receipts/thop-fastest-profile__SYN__" + id + "__r2.json").toFile());
            assertEquals("ACCEPTED", r1.get("state").asText(), id + " " + r1.get("reasons"));
            assertEquals("ACCEPTED", r2.get("state").asText(), id + " " + r2.get("reasons"));
            assertEquals(r1.at("/profile/sha256").asText(), r2.at("/profile/sha256").asText(), "repetitions reproduce each other exactly");
            assertTrue(r1.at("/witnesses/fully_verified").asBoolean());
            assertEquals(0, r1.at("/independent_validation/mismatched").asInt());
            assertTrue(r1.at("/independent_validation/matched").asInt() > 0);
            assertTrue(r1.at("/timing_ns/query_native").asLong() > 0 && r1.at("/timing_ns/warmup").asLong() > 0);
            assertTrue(r1.at("/timing_ns/finalization").asLong() >= 0);
            assertEquals(r1.at("/timing_ns/query_native").asLong()
                            + r1.at("/timing_ns/route_reconstruction").asLong()
                            + r1.at("/timing_ns/preference_evaluation").asLong()
                            + r1.at("/timing_ns/finalization").asLong(),
                    r1.at("/timing_ns/query_processing").asLong(), "nonoverlapping phases reconcile");
            assertTrue(Files.exists(dir.resolve("out/raw/thop-fastest-profile__SYN__" + id + "__r1.json")));
        }
        // the tight budget of SYN-B marks parts of its profile infeasible, which is a feasible=false cell, not a failure
        JsonNode rawB = JSON.readTree(dir.resolve("out/raw/thop-fastest-profile__SYN__SYN-B__r1.json").toFile());
        assertTrue(rawB.at("/thop_result/preference_cells").size() > 0 || rawB.at("/thop_result/coverage/infeasible_measure").asText().length() > 0);

        // resume: nothing is run again and the receipts are untouched
        long before = Files.getLastModifiedTime(dir.resolve("out/receipts/thop-fastest-profile__SYN__SYN-A__r1.json")).toMillis();
        assertEquals(0, ThopFullNetworkQueryBatch.run(config, graph));
        JsonNode second = JSON.readTree(dir.resolve("out/query_batch_manifest.json").toFile());
        assertEquals(0, second.get("executions_run_now").asInt());
        assertEquals(6, second.get("executions_already_receipted").asInt());
        assertEquals(before, Files.getLastModifiedTime(dir.resolve("out/receipts/thop-fastest-profile__SYN__SYN-A__r1.json")).toMillis());

        // a graph that is not the one the index was built for is refused before any query
        ((ObjectNode) config.get("expected")).put("graph_semantic_checksum", "0".repeat(64));
        assertEquals(2, ThopFullNetworkQueryBatch.run(config, graph));
        assertFalse(JSON.readTree(dir.resolve("out/query_batch_manifest.json").toFile()).get("status").asText().equals("COMPLETE"));
    }
}
