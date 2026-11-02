package edu.ipcmax.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.ipcmax.baselines.ThopPreferenceScoring;
import edu.ipcmax.baselines.ThopRouteWitnesses;
import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.loader.GeneratedGraphLoader;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a THop fastest travel-time profile (as serialized by the THop adapter: exact rational affine pieces) into verified fastest-route witnesses and the exact
 * preference profile {@code S_THop(t)} under the frozen query's travel-time budget.
 *
 * <pre>
 * ThopWitnessCli --dataset DIR --queries FILE.jsonl --query-id ID --thop-profile FILE.json --out FILE.json [--no-preference]
 * </pre>
 * THop's native objective is untouched; the graph is loaded with the repository loader; every figure is an exact rational (rendered as {@code n/d}).
 */
public final class ThopWitnessCli {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ThopWitnessCli() { }

    public static String rational(ExactTime value) {
        return value.denominator().equals(BigInteger.ONE) ? value.numerator().toString() : value.numerator() + "/" + value.denominator();
    }

    static ExactTime parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) return ExactTime.parseDecimal(text);
        return ExactTime.of(new BigInteger(text.substring(0, slash)), new BigInteger(text.substring(slash + 1)));
    }

    static ObjectNode interval(ExactTemporalDomain.Interval interval) {
        ObjectNode node = JSON.createObjectNode();
        node.put("start", rational(interval.start()));
        node.put("end", rational(interval.end()));
        node.put("start_inclusive", interval.startInclusive());
        node.put("end_inclusive", interval.endInclusive());
        return node;
    }

    static ExactTime measure(ExactTemporalDomain domain) {
        ExactTime total = ExactTime.ZERO;
        for (ExactTemporalDomain.Interval part : domain.intervals()) total = total.add(part.end().subtract(part.start()));
        return total;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        boolean preference = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--no-preference")) preference = false;
            else options.put(args[i], args[++i]);
        }
        long loadStarted = System.nanoTime();
        TDGraph graph = new GeneratedGraphLoader().load(Path.of(options.get("--dataset"))).graph();
        long loadNanos = System.nanoTime() - loadStarted;
        String queryId = options.get("--query-id");
        JsonNode row = null;
        for (String line : Files.readAllLines(Path.of(options.get("--queries")))) {
            if (line.isBlank()) continue;
            JsonNode candidate = JSON.readTree(line);
            if (candidate.get("query_id").asText().equals(options.get("--query-id"))) row = candidate;
        }
        if (row == null) throw new IllegalArgumentException("query not found: " + options.get("--query-id"));
        int source = row.get("source").asInt();
        int destination = row.get("destination").asInt();
        ExactTime budget = ExactTime.parseDecimal(row.get("budget").asText());
        JsonNode stored = JSON.readTree(Files.readString(Path.of(options.get("--thop-profile"))));
        List<ExactAffineSegment> pieces = new ArrayList<>();
        for (JsonNode piece : stored.get("travel_time_pieces")) {
            ExactTemporalDomain.Interval interval = new ExactTemporalDomain.Interval(parse(piece.get("start").asText()), parse(piece.get("end").asText()),
                    piece.get("start_inclusive").asBoolean(), piece.get("end_inclusive").asBoolean());
            pieces.add(new ExactAffineSegment(parse(piece.get("slope").asText()), parse(piece.get("intercept").asText()), interval));
        }
        ExactArrivalProfile travel = ExactArrivalProfile.ofSegments(pieces);
        ObjectNode out = render(graph, queryId, source, destination, budget, travel, preference, loadNanos, stored.path("source_record_sha256_of_profile").asText(""));
        Files.writeString(Path.of(options.get("--out")), JSON.writeValueAsString(out) + "\n");
        ObjectNode timing = (ObjectNode) out.get("timing_ns");
        System.out.printf("%s: %d pieces, %d witnesses over %d distinct routes, verified=%s, unverified=%d, disagreements=%d, reconstruction %.2f s%s%n", queryId, pieces.size(),
                out.get("witnesses").size(), out.get("routes").size(), out.get("fully_verified").asBoolean(), out.get("unverified").size(), out.get("disagreements").size(),
                timing.get("route_reconstruction").asLong() / 1e9, preference ? String.format(", preference %.2f s", timing.get("preference_evaluation").asLong() / 1e9) : "");
    }

    /** Fastest-route witnesses, exact preference cells and timings for one THop fastest travel-time profile, as a {@code thop-witness-preference-v1} document. */
    public static ObjectNode render(TDGraph graph, String queryId, int source, int destination, ExactTime budget, ExactArrivalProfile travel, boolean preference, long loadNanos, String profileTag) {
        long renderStarted = System.nanoTime();
        ExactArrivalProfile arrival = travel.asArrivalProfile();

        long witnessStarted = System.nanoTime();
        ThopRouteWitnesses.Result witnesses = ThopRouteWitnesses.reconstruct(graph, source, destination, arrival);
        long witnessNanos = System.nanoTime() - witnessStarted;
        ThopPreferenceScoring.Evaluation evaluation = null;
        long preferenceNanos = 0;
        if (preference) {
            long preferenceStarted = System.nanoTime();
            evaluation = ThopPreferenceScoring.evaluate(graph, source, destination, witnesses.witnesses(), budget);
            preferenceNanos = System.nanoTime() - preferenceStarted;
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("schema", "thop-witness-preference-v1");
        out.put("query_id", queryId);
        out.put("source", source);
        out.put("destination", destination);
        out.put("budget", rational(budget));
        out.put("thop_profile_pieces", travel.segments().size());
        out.put("thop_profile_sha256_of_pieces", profileTag);
        ArrayNode domain = out.putArray("profile_domain");
        for (ExactTemporalDomain.Interval part : arrival.domain().intervals()) domain.add(interval(part));
        Map<List<Integer>, Integer> routeIds = new LinkedHashMap<>();
        ArrayNode witnessNodes = out.putArray("witnesses");
        for (ThopRouteWitnesses.Witness witness : witnesses.witnesses()) {
            int routeId = routeIds.computeIfAbsent(witness.arcIds(), key -> routeIds.size());
            ObjectNode node = witnessNodes.addObject();
            node.set("interval", interval(witness.interval()));
            node.put("route_id", routeId);
            node.put("arrival_slope", rational(witness.arrivalSlope()));
            node.put("arrival_intercept", rational(witness.arrivalIntercept()));
        }
        ArrayNode routeNodes = out.putArray("routes");
        routeIds.forEach((arcs, id) -> {
            ObjectNode node = routeNodes.addObject();
            node.put("route_id", id);
            ArrayNode ids = node.putArray("arc_ids");
            arcs.forEach(ids::add);
        });
        ArrayNode unverified = out.putArray("unverified");
        for (ExactTemporalDomain.Interval part : witnesses.unverified().intervals()) unverified.add(interval(part));
        ArrayNode disagreements = out.putArray("disagreements");
        for (ThopRouteWitnesses.Disagreement d : witnesses.disagreements()) {
            ObjectNode node = disagreements.addObject();
            node.put("departure", rational(d.departure()));
            node.put("thop_arrival", rational(d.thopArrival()));
            node.put("oracle_arrival", rational(d.oracleArrival()));
        }
        out.put("fully_verified", witnesses.fullyVerified());
        ObjectNode coverage = out.putObject("coverage");
        ExactTemporalDomain witnessed = ExactTemporalDomain.empty();
        for (ThopRouteWitnesses.Witness witness : witnesses.witnesses()) witnessed = witnessed.union(ExactTemporalDomain.of(witness.interval()));
        coverage.put("profile_domain_measure", rational(measure(arrival.domain())));
        coverage.put("witnessed_measure", rational(measure(witnessed)));
        coverage.put("witnessed_fraction", measure(arrival.domain()).signum() == 0 ? 0.0 : measure(witnessed).divide(measure(arrival.domain())).toDisplayDouble());
        if (evaluation != null) {
            ArrayNode cells = out.putArray("preference_cells");
            for (ThopPreferenceScoring.Cell cell : evaluation.cells()) {
                ObjectNode node = cells.addObject();
                node.set("interval", interval(cell.interval()));
                node.put("route_id", routeIds.get(witnesses.witnesses().get(cell.witnessIndex()).arcIds()));
                node.put("feasible", cell.feasible());
                node.put("score", cell.score().toString());
            }
            coverage.put("feasible_measure", rational(measure(evaluation.feasibleDomain())));
            coverage.put("infeasible_measure", rational(measure(evaluation.infeasibleDomain())));
        }
        ObjectNode timing = out.putObject("timing_ns");
        timing.put("graph_load", loadNanos);
        timing.put("route_reconstruction", witnessNanos);
        timing.put("preference_evaluation", preferenceNanos);
        ObjectNode stats = out.putObject("route_reconstruction_stats");
        stats.put("profile_pieces", witnesses.stats().profilePieces());
        stats.put("distinct_routes", witnesses.stats().distinctRoutes());
        stats.put("route_searches", witnesses.stats().routeSearches());
        stats.put("route_profile_compositions", witnesses.stats().routeProfileCompositions());
        stats.put("search_ns", witnesses.stats().searchNanos());
        stats.put("verify_ns", witnesses.stats().verifyNanos());
        long finalizationNanos = System.nanoTime() - renderStarted - witnessNanos - preferenceNanos;
        if (finalizationNanos < 0) throw new IllegalStateException("render subphase timers exceed render wall time");
        timing.put("finalization", finalizationNanos);
        timing.put("query_processing", witnessNanos + preferenceNanos + finalizationNanos);
        return out;
    }
}
