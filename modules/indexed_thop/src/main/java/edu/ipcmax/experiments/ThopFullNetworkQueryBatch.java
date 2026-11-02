package edu.ipcmax.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.baselines.ThopRouteWitnesses;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphLoader;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent-JVM query phase of the THop full-network experiment: the graph and one complete, verified index are loaded once, then every planned execution of the dataset
 * (frozen query x repetition, in the Experiment B manifest's order) is warmed up, measured, its fastest-route witnesses recovered, its constraint-aware preference evaluated and
 * independently spot-checked, and an immutable receipt is written. Executions that already have a valid receipt are not repeated.
 *
 * <p>A THop query that fails, has no profile, or whose evidence does not verify is recorded as such (never as a zero score) and is not accepted.
 * {@code S_THop(t) = Preference(P_fastest(t))} when the fastest route satisfies the query's budget at departure t, otherwise 0 with feasible=false.</p>
 *
 * <p>Argument: the path of a JSON configuration (see {@link #run(JsonNode)}). Exit status: 0 every planned execution has a receipt; 2 identity or integrity failure; 3 an existing
 * receipt is inconsistent with its raw file.</p>
 */
public final class ThopFullNetworkQueryBatch {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT = new ObjectMapper();

    private ThopFullNetworkQueryBatch() { }

    public static void main(String[] args) throws Exception {
        int status = run(JSON.readTree(Path.of(args[0]).toFile()));
        System.exit(status);
    }

    /**
     * Configuration fields: dataset, dataset_dir, boundary_file, inner_file, manifest_file (Experiment B manifest), queries_file (the frozen JSONL), out_dir, campaign_id,
     * executable_sha256, expected {boundary_index_sha256, inner_index_sha256, graph_semantic_checksum}, independent_samples, max_executions (optional, tests).
     */
    public static int run(JsonNode config) throws Exception {
        return run(config, null);
    }

    /** As {@link #run(JsonNode)}, with an already loaded graph (tests); the graph is then not read from {@code dataset_dir}. */
    public static int run(JsonNode config, TDGraph preloaded) throws Exception {
        String dataset = config.get("dataset").asText();
        Path outDir = Path.of(config.get("out_dir").asText());
        Path receipts = outDir.resolve("receipts");
        Path raw = outDir.resolve("raw");
        Files.createDirectories(receipts);
        Files.createDirectories(raw);
        long jvmStarted = System.nanoTime();
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("schema", "thop-full-network-query-batch-v1");
        batch.put("dataset", dataset);
        batch.put("campaign_id", config.path("campaign_id").asText());
        batch.put("executable_sha256", config.path("executable_sha256").asText());
        batch.put("started_utc", Instant.now().toString());
        batch.put("pid", ProcessHandle.current().pid());
        batch.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        batch.put("jvm_input_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());

        // ---- load the graph once and check that it is the graph the index was built for
        long t0 = System.nanoTime();
        TDGraph graph = preloaded != null ? preloaded : new GeneratedGraphLoader().load(Path.of(config.get("dataset_dir").asText())).graph();
        batch.put("graph_load_nanos", System.nanoTime() - t0);
        JsonNode expected = config.path("expected");
        t0 = System.nanoTime();
        String semantic = PaceBench.graphSemanticChecksum(graph);
        batch.put("graph_semantic_checksum", semantic);
        batch.put("graph_semantic_checksum_nanos", System.nanoTime() - t0);
        Path boundaryFile = Path.of(config.get("boundary_file").asText());
        Path innerFile = Path.of(config.get("inner_file").asText());
        t0 = System.nanoTime();
        String boundarySha = sha256(boundaryFile);
        String innerSha = sha256(innerFile);
        batch.put("index_checksum_nanos", System.nanoTime() - t0);
        batch.put("boundary_index_sha256", boundarySha);
        batch.put("inner_index_sha256", innerSha);
        List<String> problems = new ArrayList<>();
        if (expected.has("graph_semantic_checksum") && !expected.get("graph_semantic_checksum").asText().equals(semantic)) problems.add("graph semantic checksum differs from the index build's");
        if (expected.has("boundary_index_sha256") && !expected.get("boundary_index_sha256").asText().equals(boundarySha)) problems.add("boundary index file differs from the verified one");
        if (expected.has("inner_index_sha256") && !expected.get("inner_index_sha256").asText().equals(innerSha)) problems.add("inner index file differs from the verified one");
        if (!problems.isEmpty()) {
            batch.put("status", "IDENTITY_MISMATCH");
            batch.put("problems", problems);
            writeAtomically(outDir.resolve("query_batch_manifest.json"), JSON.writeValueAsString(batch) + "\n");
            return 2;
        }
        t0 = System.nanoTime();
        PartitionedTHopIndex index = PartitionedTHopIndex.load(graph, boundaryFile, innerFile);
        batch.put("index_load_nanos", System.nanoTime() - t0);
        batch.put("index_load_note", "metadata and boundary labels; inner partition blocks are decoded on first use, which the per-execution warm-up absorbs");

        // ---- the planned executions of this dataset and the frozen query rows
        JsonNode manifest = JSON.readTree(Path.of(config.get("manifest_file").asText()).toFile());
        List<JsonNode> planned = new ArrayList<>();
        for (JsonNode execution : manifest.get("thop_planned_executions")) {
            if (execution.get("dataset").asText().equals(dataset)) planned.add(execution);
        }
        Map<String, JsonNode> rows = new HashMap<>();
        Map<String, String> rowShas = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(config.get("queries_file").asText()), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode row = COMPACT.readTree(line);
            rows.put(row.get("query_id").asText(), row);
            rowShas.put(row.get("query_id").asText(), sha256(line.getBytes(StandardCharsets.UTF_8)));
        }
        batch.put("planned_executions", planned.size());
        ThopRouteWitnesses.Oracle oracle = ThopRouteWitnesses.oracle(graph);
        int samples = config.path("independent_samples").asInt(12);
        int limit = config.path("max_executions").asInt(Integer.MAX_VALUE);
        int done = 0, skipped = 0;
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        for (JsonNode execution : planned) {
            if (done + skipped >= limit) break;
            String execId = execution.get("exec_id").asText();
            Path receiptFile = receipts.resolve(execId + ".json");
            if (Files.exists(receiptFile)) {
                JsonNode existing = JSON.readTree(receiptFile.toFile());
                Path rawFile = raw.resolve(execId + ".json");
                if (!Files.exists(rawFile) || !sha256(rawFile).equals(existing.path("raw_result_sha256").asText())) {
                    batch.put("status", "RECEIPT_INCONSISTENT");
                    batch.put("inconsistent_receipt", execId);
                    writeAtomically(outDir.resolve("query_batch_manifest.json"), JSON.writeValueAsString(batch) + "\n");
                    return 3;
                }
                outcomes.merge(existing.path("state").asText(), 1, Integer::sum);
                skipped++;
                continue;
            }
            String queryId = execution.get("query_id").asText();
            JsonNode row = rows.get(queryId);
            if (row == null || !rowShas.get(queryId).equals(execution.get("query_row_sha256").asText())) {
                batch.put("status", "FROZEN_QUERY_MISMATCH");
                batch.put("mismatching_query", queryId);
                writeAtomically(outDir.resolve("query_batch_manifest.json"), JSON.writeValueAsString(batch) + "\n");
                return 2;
            }
            ObjectNode receipt = executeOne(graph, index, oracle, execution, row, rowShas.get(queryId), samples, raw, receipts, config, boundarySha, innerSha, semantic, batch);
            Files.writeString(receiptFile, JSON.writeValueAsString(receipt) + "\n", StandardOpenOption.CREATE_NEW);
            Files.writeString(receipts.resolve("index.jsonl"), COMPACT.writeValueAsString(receipt) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            outcomes.merge(receipt.get("state").asText(), 1, Integer::sum);
            done++;
        }
        batch.put("executions_run_now", done);
        batch.put("executions_already_receipted", skipped);
        batch.put("outcomes", outcomes);
        batch.put("jvm_wall_nanos", System.nanoTime() - jvmStarted);
        batch.put("ended_utc", Instant.now().toString());
        batch.put("status", done + skipped >= planned.size() ? "COMPLETE" : "PARTIAL");
        writeAtomically(outDir.resolve("query_batch_manifest.json"), JSON.writeValueAsString(batch) + "\n");
        return 0;
    }

    private static ObjectNode executeOne(TDGraph graph, PartitionedTHopIndex index, ThopRouteWitnesses.Oracle oracle, JsonNode execution, JsonNode row, String rowSha, int samples,
                                         Path raw, Path receipts, JsonNode config, String boundarySha, String innerSha, String semantic, Map<String, Object> batch) throws Exception {
        String execId = execution.get("exec_id").asText();
        String queryId = execution.get("query_id").asText();
        int repetition = execution.get("rep").asInt();
        int source = row.get("source").asInt();
        int destination = row.get("destination").asInt();
        double start = row.get("interval_start").asDouble();
        double end = row.get("interval_end").asDouble();
        ExactTime budget = ThopWitnessCli.parse(row.get("budget").decimalValue().toPlainString());
        long wallStarted = System.nanoTime();
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schema", "thop-full-network-receipt-v1");
        receipt.put("exec_id", execId);
        receipt.put("dataset", config.get("dataset").asText());
        receipt.put("query_id", queryId);
        receipt.put("repetition", repetition);
        receipt.put("query_row_sha256", rowSha);
        receipt.put("campaign_id", config.path("campaign_id").asText());
        receipt.put("executable_sha256", config.path("executable_sha256").asText());
        ObjectNode identity = receipt.putObject("index_identity");
        identity.put("boundary_index_sha256", boundarySha);
        identity.put("inner_index_sha256", innerSha);
        identity.put("graph_semantic_checksum", semantic);
        receipt.put("started_utc", Instant.now().toString());
        receipt.put("s_thop_semantics", "S_THop(t) = Preference(P_fastest(t)) if the fastest route satisfies the query's travel-time budget, else 0 with feasible=false; a failed or unverified query is never scored 0");
        ObjectNode timing = receipt.putObject("timing_ns");
        Domain window = Domain.closed(start, end);

        // warm-up (not measured): absorbs lazy partition decoding and JIT; its outcome is recorded
        long w0 = System.nanoTime();
        String warmupOutcome = "OK";
        try {
            index.query(source, destination, window);
        } catch (RuntimeException failure) {
            warmupOutcome = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        timing.put("warmup", System.nanoTime() - w0);
        receipt.put("warmup_outcome", warmupOutcome);

        // measured native THop profile query
        ExactArrivalProfile travel = null;
        String failure = null;
        long q0 = System.nanoTime();
        try {
            PartitionedTHopIndex.QueryResult result = index.query(source, destination, window);
            timing.put("query_native", System.nanoTime() - q0);
            travel = result.exactTravelTimeProfile();
            receipt.put("profile_compositions", result.queryStatistics().profileCompositions());
        } catch (RuntimeException problem) {
            timing.put("query_native", System.nanoTime() - q0);
            failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
        }
        ObjectNode rawDocument = JSON.createObjectNode();
        String state;
        List<String> reasons = new ArrayList<>();
        if (failure != null) {
            state = "QUERY_FAILED";
            reasons.add(failure);
        } else if (travel == null || travel.isEmpty()) {
            state = "NO_PROFILE";
            reasons.add("THop returned an empty fastest profile (unreachable or unsupported); not a zero score");
        } else {
            String profileSha = profileSha256(travel);
            ObjectNode rendered = ThopWitnessCli.render(graph, queryId, source, destination, budget, travel, true, 0L, profileSha);
            ObjectNode renderedTiming = (ObjectNode) rendered.get("timing_ns");
            timing.put("route_reconstruction", renderedTiming.get("route_reconstruction").asLong());
            timing.put("preference_evaluation", renderedTiming.get("preference_evaluation").asLong());
            timing.put("finalization", renderedTiming.get("finalization").asLong());
            timing.put("corridor_preparation", 0L);
            timing.put("functional_search", timing.get("query_native").asLong());
            timing.put("query_processing", timing.get("functional_search").asLong()
                    + timing.get("route_reconstruction").asLong()
                    + timing.get("preference_evaluation").asLong()
                    + timing.get("finalization").asLong());
            // independent spot check against exact fixed-departure Dijkstra on the original graph (no index involved)
            long v0 = System.nanoTime();
            int matched = 0, mismatched = 0, skippedSamples = 0;
            ExactArrivalProfile arrivalProfile = travel.asArrivalProfile();
            ExactTime from = ExactTime.fromCanonicalDouble(start);
            ExactTime to = ExactTime.fromCanonicalDouble(end);
            List<ObjectNode> mismatches = new ArrayList<>();
            for (int i = 0; i < Math.max(2, samples); i++) {
                ExactTime departure = from.add(to.subtract(from).multiply(ExactTime.of(i)).divide(ExactTime.of(Math.max(2, samples) - 1)));
                ExactTime thop;
                try {
                    thop = arrivalProfile.valueAtExact(departure);
                } catch (RuntimeException unsupported) {
                    skippedSamples++;
                    continue;
                }
                ExactTime truth = oracle.fastestArrival(source, destination, departure);
                if (truth != null && truth.equals(thop)) {
                    matched++;
                } else {
                    mismatched++;
                    ObjectNode m = JSON.createObjectNode();
                    m.put("departure", ThopWitnessCli.rational(departure));
                    m.put("thop_arrival", ThopWitnessCli.rational(thop));
                    m.put("oracle_arrival", truth == null ? "UNREACHABLE" : ThopWitnessCli.rational(truth));
                    mismatches.add(m);
                }
            }
            timing.put("validation", System.nanoTime() - v0);
            ObjectNode validation = rawDocument.putObject("independent_validation");
            validation.put("method", "exact fixed-departure time-dependent Dijkstra on the original graph (no THop index), compared with the THop fastest arrival at evenly spaced departures including both window ends");
            validation.put("samples", matched + mismatched);
            validation.put("matched", matched);
            validation.put("mismatched", mismatched);
            validation.put("unsupported_samples_skipped", skippedSamples);
            mismatches.forEach(m -> validation.withArray("mismatches").add(m));
            boolean verified = rendered.get("fully_verified").asBoolean();
            if (!verified) reasons.add("route witnesses do not cover and verify the whole profile (unverified parts or oracle disagreements)");
            if (mismatched > 0) reasons.add(mismatched + " independent spot check(s) disagree with THop");
            if (matched == 0) reasons.add("no independent spot check could be evaluated");
            // repetition consistency: the second repetition must reproduce the first exactly (no contamination, deterministic ties)
            ObjectNode repetitionCheck = rawDocument.putObject("repetition_check");
            String routesSha = sha256(rendered.get("routes").toString().getBytes(StandardCharsets.UTF_8));
            repetitionCheck.put("profile_sha256", profileSha);
            repetitionCheck.put("routes_sha256", routesSha);
            if (repetition > 1) {
                Path firstReceipt = receipts.resolve(execId.replaceAll("__r\\d+$", "__r1") + ".json");
                if (Files.exists(firstReceipt)) {
                    JsonNode first = JSON.readTree(firstReceipt.toFile());
                    boolean same = first.path("profile").path("sha256").asText().equals(profileSha) && first.path("witnesses").path("routes_sha256").asText().equals(routesSha);
                    repetitionCheck.put("matches_repetition_1", same);
                    if (!same) reasons.add("repetition 2 differs from repetition 1 (profile or routes)");
                } else {
                    repetitionCheck.put("matches_repetition_1", (String) null);
                }
            }
            ObjectNode profile = receipt.putObject("profile");
            profile.put("pieces", travel.segments().size());
            profile.put("sha256", profileSha);
            ObjectNode witnesses = receipt.putObject("witnesses");
            witnesses.put("count", rendered.get("witnesses").size());
            witnesses.put("distinct_routes", rendered.get("routes").size());
            witnesses.put("routes_sha256", routesSha);
            witnesses.put("fully_verified", verified);
            witnesses.put("unverified_parts", rendered.get("unverified").size());
            witnesses.put("disagreements", rendered.get("disagreements").size());
            witnesses.set("coverage", rendered.get("coverage"));
            ObjectNode independent = receipt.putObject("independent_validation");
            independent.put("samples", matched + mismatched);
            independent.put("matched", matched);
            independent.put("mismatched", mismatched);
            rendered.set("travel_time_pieces", piecesNode(travel));
            rawDocument.set("thop_result", rendered);
            state = reasons.isEmpty() ? "ACCEPTED" : "VALIDATION_FAILED";
        }
        receipt.put("state", state);
        receipt.set("reasons", COMPACT.valueToTree(reasons));
        timing.put("execution_wall", System.nanoTime() - wallStarted);
        ObjectNode context = receipt.putObject("batch_context_ns");
        context.put("graph_load", ((Number) batch.get("graph_load_nanos")).longValue());
        context.put("index_load", ((Number) batch.get("index_load_nanos")).longValue());
        receipt.put("ended_utc", Instant.now().toString());
        rawDocument.put("exec_id", execId);
        rawDocument.set("receipt_preview", receipt.deepCopy());
        Path rawFile = raw.resolve(execId + ".json");
        writeAtomically(rawFile, JSON.writeValueAsString(rawDocument) + "\n");
        receipt.put("raw_result_file", "raw/" + execId + ".json");
        receipt.put("raw_result_sha256", sha256(rawFile));
        return receipt;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode piecesNode(ExactArrivalProfile travel) {
        com.fasterxml.jackson.databind.node.ArrayNode pieces = JSON.createArrayNode();
        for (ExactAffineSegment piece : travel.segments()) {
            ObjectNode node = pieces.addObject();
            node.set("interval", ThopWitnessCli.interval(piece.interval()));
            node.put("slope", ThopWitnessCli.rational(piece.slope()));
            node.put("intercept", ThopWitnessCli.rational(piece.intercept()));
        }
        return pieces;
    }

    private static String profileSha256(ExactArrivalProfile travel) {
        StringBuilder text = new StringBuilder();
        for (ExactAffineSegment piece : travel.segments()) {
            text.append(ThopWitnessCli.interval(piece.interval())).append('|').append(ThopWitnessCli.rational(piece.slope())).append('|').append(ThopWitnessCli.rational(piece.intercept())).append('\n');
        }
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path target, String text) throws java.io.IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
