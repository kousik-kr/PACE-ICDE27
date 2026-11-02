package edu.ipcmax.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * End-to-end THop run over a frozen query file on one stored partitioned index: the graph and the index are loaded once; every distinct
 * (source, destination, window) is answered by a live fastest-profile query; every query row then gets fastest-route witnesses and the exact preference
 * profile {@code S_THop(t)} under its own budget. One {@code thop-live-end-to-end-v1} document per query row records the THop profile itself, the witnesses,
 * the preference cells and the separate query, reconstruction and scoring times.
 * Args: dataset boundaryFile innerFile queries.jsonl outDir [maxDistinctOd]
 */
public final class ThopNyCtrlBatch {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ThopNyCtrlBatch() { }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        TDGraph graph = new GeneratedGraphLoader().load(Path.of(args[0])).graph();
        long t1 = System.nanoTime();
        PartitionedTHopIndex index = PartitionedTHopIndex.load(graph, Path.of(args[1]), Path.of(args[2]));
        long t2 = System.nanoTime();
        Path outDir = Path.of(args[4]);
        Files.createDirectories(outDir);
        int limit = args.length > 5 ? Integer.parseInt(args[5]) : Integer.MAX_VALUE;
        Map<String, ExactArrivalProfile> profiles = new HashMap<>();
        Map<String, Long> queryNanos = new HashMap<>();
        Map<String, Long> compositions = new HashMap<>();
        Map<String, String> failures = new HashMap<>();
        int rows = 0;
        for (String line : Files.readAllLines(Path.of(args[3]))) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line);
            int source = row.get("source").asInt();
            int destination = row.get("destination").asInt();
            double start = row.get("interval_start").asDouble();
            double end = row.get("interval_end").asDouble();
            String key = source + ">" + destination + "@" + start + "-" + end;
            String queryId = row.get("query_id").asText();
            if (!profiles.containsKey(key)) {
                if (profiles.size() >= limit) continue;
                long q0 = System.nanoTime();
                try {
                    var result = index.query(source, destination, Domain.closed(start, end));
                    queryNanos.put(key, System.nanoTime() - q0);
                    compositions.put(key, result.queryStatistics().profileCompositions());
                    profiles.put(key, result.exactTravelTimeProfile());
                } catch (RuntimeException failed) {
                    queryNanos.put(key, System.nanoTime() - q0);
                    failures.put(key, failed.getClass().getSimpleName() + ": " + failed.getMessage());
                    profiles.put(key, null);
                }
            }
            ExactArrivalProfile travel = profiles.get(key);
            if (travel == null || travel.isEmpty()) {
                // a THop query that fails or has no profile is reported as such; it is never a zero-score result
                ObjectNode status = JSON.createObjectNode();
                status.put("schema", "thop-live-end-to-end-v1");
                status.put("query_id", queryId);
                status.put("thop_status", travel == null ? "THOP_QUERY_FAILED" : "THOP_NO_PROFILE_UNREACHABLE_OR_UNSUPPORTED");
                status.put("detail", failures.getOrDefault(key, "empty profile"));
                Files.writeString(outDir.resolve("live_" + queryId + ".json"), JSON.writeValueAsString(status) + "\n");
                System.out.printf("%s: %s%n", queryId, status.get("thop_status").asText());
                rows++;
                continue;
            }
            ObjectNode out = ThopWitnessCli.render(graph, queryId, source, destination, ThopWitnessCli.parse(row.get("budget").asText()), travel, true, t1 - t0, "live");
            out.put("schema", "thop-live-end-to-end-v1");
            ObjectNode live = out.putObject("thop_query");
            live.put("od_window_key", key);
            live.put("query_ns", queryNanos.get(key));
            live.put("profile_compositions", compositions.get(key));
            live.put("index_load_ns", t2 - t1);
            ArrayNode pieces = live.putArray("travel_time_pieces");
            for (ExactAffineSegment piece : travel.segments()) {
                ObjectNode node = pieces.addObject();
                node.set("interval", ThopWitnessCli.interval(piece.interval()));
                node.put("slope", ThopWitnessCli.rational(piece.slope()));
                node.put("intercept", ThopWitnessCli.rational(piece.intercept()));
            }
            Files.writeString(outDir.resolve("live_" + queryId + ".json"), JSON.writeValueAsString(out) + "\n");
            ObjectNode timing = (ObjectNode) out.get("timing_ns");
            System.out.printf("%s: query %.2f s, %d pieces, %d witnesses/%d routes, verified=%s, reconstruction %.2f s, preference %.2f s, feasible %s of window%n", queryId,
                    queryNanos.get(key) / 1e9, travel.segments().size(), out.get("witnesses").size(), out.get("routes").size(), out.get("fully_verified").asBoolean(),
                    timing.get("route_reconstruction").asLong() / 1e9, timing.get("preference_evaluation").asLong() / 1e9,
                    out.get("coverage").get("feasible_measure").asText() + "/" + out.get("coverage").get("profile_domain_measure").asText());
            rows++;
        }
        System.out.printf("rows=%d distinct_od_windows=%d graph_load=%.2fs index_load=%.2fs%n", rows, profiles.size(), (t1 - t0) / 1e9, (t2 - t1) / 1e9);
    }
}
