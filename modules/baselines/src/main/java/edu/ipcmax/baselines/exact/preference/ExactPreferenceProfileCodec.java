package edu.ipcmax.baselines.exact.preference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ipcmax.baselines.exact.ExactKernel;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Deterministic JSON codec. Every rational is a reduced integer or {@code numerator/denominator} string. */
public final class ExactPreferenceProfileCodec {
    public static final String SCHEMA = "exact-preference-envelope-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExactPreferenceProfileCodec() { }

    public static String encode(ExactPreferenceEnvelope envelope) {
        StringBuilder out = new StringBuilder(128 + envelope.cells().size() * 160);
        out.append("{\"schema\":\"").append(SCHEMA).append("\",\"domain\":[");
        for (int i = 0; i < envelope.domain().intervals().size(); i++) {
            if (i > 0) out.append(',');
            appendInterval(out, envelope.domain().intervals().get(i));
        }
        out.append("],\"cells\":[");
        List<ExactPreferenceEnvelope.Cell> cells = envelope.cells();
        if (ExactKernel.mode() == ExactKernel.Mode.LEHMER && cells.size() >= PARALLEL_MIN_CELLS) {
            // Cells are independent text: converting a few hundred 8,800-bit coefficients is the dominant cost of
            // returning a long route's answer, and every core is idle when the search has stopped. The text is
            // the same as the sequential text below, cell for cell.
            String[] texts = new String[cells.size()];
            java.util.stream.IntStream.range(0, texts.length).parallel().forEach(i -> {
                StringBuilder cellOut = new StringBuilder(512);
                appendCell(cellOut, cells.get(i), null, null);
                texts[i] = cellOut.toString();
            });
            for (int i = 0; i < texts.length; i++) {
                if (i > 0) out.append(',');
                out.append(texts[i]);
            }
            return out.append("]}\n").toString();
        }
        // An endpoint shared by neighbouring cells, and the intercept that arrival and travel time have in
        // common, are converted to text once: with 8,800-bit coefficients each conversion is a real cost.
        ExactTime previousEnd = null;
        String previousEndText = null;
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) out.append(',');
            ExactPreferenceEnvelope.Cell cell = cells.get(i);
            ExactTemporalDomain.Interval interval = cell.interval();
            String startText = previousEnd != null && previousEnd.equals(interval.start())
                    ? previousEndText : interval.start().toString();
            String endText = interval.end().equals(interval.start()) ? startText : interval.end().toString();
            previousEnd = interval.end();
            previousEndText = endText;
            appendCell(out, cell, startText, endText);
        }
        return out.append("]}\n").toString();
    }

    /** Fewest cells for which converting them on several threads pays. */
    private static final int PARALLEL_MIN_CELLS = 48;

    /** One cell's text; end point texts are supplied when the caller already has them. */
    private static void appendCell(StringBuilder out, ExactPreferenceEnvelope.Cell cell, String startText, String endText) {
        ExactTemporalDomain.Interval interval = cell.interval();
        if (startText == null) startText = interval.start().toString();
        if (endText == null) endText = interval.end().equals(interval.start()) ? startText : interval.end().toString();
        out.append("{\"interval\":");
        appendInterval(out, startText, endText, interval);
        if (!cell.found()) {
            out.append(",\"status\":\"NO_PATH\",\"budget_feasible\":false}");
            return;
        }
        out.append(",\"status\":\"SELECTED\",\"budget_feasible\":true,\"score\":\"")
                .append(cell.score()).append("\",\"path\":[");
        for (int arc = 0; arc < cell.path().arcIds().size(); arc++) {
            if (arc > 0) out.append(',');
            out.append(cell.path().arcIds().get(arc));
        }
        ExactTime slope = cell.arrivalSlope();
        String denominatorText = slope.denominator().equals(BigInteger.ONE) ? null : slope.denominator().toString();
        String slopeText = denominatorText == null ? slope.numerator().toString()
                : slope.numerator() + "/" + denominatorText;
        // slope - 1 = (n - d) / d is already reduced when n / d is
        String travelSlopeText = denominatorText == null ? slope.numerator().subtract(BigInteger.ONE).toString()
                : slope.numerator().subtract(slope.denominator()) + "/" + denominatorText;
        String interceptText = cell.arrivalIntercept().toString();
        out.append("],\"arrival\":{\"slope\":\"").append(slopeText)
                .append("\",\"intercept\":\"").append(interceptText)
                .append("\"},\"travel_time\":{\"slope\":\"").append(travelSlopeText)
                .append("\",\"intercept\":\"").append(interceptText).append("\"}}");
    }

    public static byte[] encodeUtf8(ExactPreferenceEnvelope envelope) {
        return encode(envelope).getBytes(StandardCharsets.UTF_8);
    }

    public static ExactPreferenceEnvelope decode(String encoded) {
        try {
            JsonNode root = JSON.readTree(encoded);
            if (!SCHEMA.equals(root.path("schema").asText())) throw new IllegalArgumentException("unsupported exact profile schema");
            List<ExactTemporalDomain.Interval> domain = new ArrayList<>();
            for (JsonNode interval : root.path("domain")) domain.add(readInterval(interval));
            List<ExactPreferenceEnvelope.Cell> cells = new ArrayList<>();
            for (JsonNode cell : root.path("cells")) {
                ExactTemporalDomain.Interval interval = readInterval(cell.path("interval"));
                if ("NO_PATH".equals(cell.path("status").asText())) {
                    if (cell.path("budget_feasible").asBoolean(true)) {
                        throw new IllegalArgumentException("NO_PATH cell cannot be budget feasible");
                    }
                    cells.add(new ExactPreferenceEnvelope.Cell(interval, null, null, null, null));
                } else if ("SELECTED".equals(cell.path("status").asText())) {
                    if (!cell.path("budget_feasible").asBoolean(false)) {
                        throw new IllegalArgumentException("selected cell must be budget feasible");
                    }
                    List<Integer> path = new ArrayList<>();
                    for (JsonNode arc : cell.path("path")) path.add(arc.intValue());
                    JsonNode arrival = cell.path("arrival");
                    ExactTime arrivalSlope = parseTime(arrival.path("slope").asText());
                    ExactTime arrivalIntercept = parseTime(arrival.path("intercept").asText());
                    JsonNode travel = cell.path("travel_time");
                    if (!arrivalSlope.subtract(ExactTime.ONE).equals(parseTime(travel.path("slope").asText()))
                            || !arrivalIntercept.equals(parseTime(travel.path("intercept").asText()))) {
                        throw new IllegalArgumentException("serialized travel relation does not match arrival");
                    }
                    cells.add(new ExactPreferenceEnvelope.Cell(interval,
                            new edu.ipcmax.core.validate.Path(path),
                            new BigInteger(cell.path("score").asText()),
                            arrivalSlope,
                            arrivalIntercept));
                } else {
                    throw new IllegalArgumentException("unknown exact profile cell status");
                }
            }
            return ExactPreferenceEnvelope.fromSerialized(ExactTemporalDomain.of(domain), cells);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("malformed exact preference profile JSON", malformed);
        }
    }

    private static void appendInterval(StringBuilder out, String start, String end, ExactTemporalDomain.Interval interval) {
        out.append("{\"start\":\"").append(start)
                .append("\",\"end\":\"").append(end)
                .append("\",\"start_inclusive\":").append(interval.startInclusive())
                .append(",\"end_inclusive\":").append(interval.endInclusive()).append('}');
    }

    private static void appendInterval(StringBuilder out, ExactTemporalDomain.Interval interval) {
        out.append("{\"start\":\"").append(interval.start())
                .append("\",\"end\":\"").append(interval.end())
                .append("\",\"start_inclusive\":").append(interval.startInclusive())
                .append(",\"end_inclusive\":").append(interval.endInclusive()).append('}');
    }

    private static ExactTemporalDomain.Interval readInterval(JsonNode interval) {
        return new ExactTemporalDomain.Interval(
                parseTime(interval.path("start").asText()),
                parseTime(interval.path("end").asText()),
                interval.path("start_inclusive").asBoolean(),
                interval.path("end_inclusive").asBoolean());
    }

    private static ExactTime parseTime(String value) {
        String[] fraction = value.split("/", -1);
        if (fraction.length == 1) return ExactTime.of(new BigInteger(value), BigInteger.ONE);
        if (fraction.length != 2) throw new IllegalArgumentException("invalid rational: " + value);
        return ExactTime.of(new BigInteger(fraction[0]), new BigInteger(fraction[1]));
    }
}
