package edu.ipcmax.experiments.framework;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.pcmax.EnvelopeProfile;
import edu.ipcmax.core.pcmax.EnvelopeSegment;

/** Deterministic serialization, statistics, and exact interval comparisons. */
public final class ProfileSupport {
    private static final ObjectMapper OBJECTIVE_JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private ProfileSupport() {
    }

    public static String checksum(EnvelopeProfile profile) {
        return sha256(canonical(profile));
    }

    /** Stable scientific output only: excludes preparation/query times and other diagnostics. */
    public static String canonicalResult(AlgorithmResult result) {
        Object objective = result.scalars().get("objective_result");
        if (objective instanceof Map<?, ?> objectiveMap
                && objectiveMap.containsKey("profile")) {
            try {
                return OBJECTIVE_JSON.writeValueAsString(objectiveMap.get("profile")) + "\n";
            } catch (java.io.IOException failure) {
                throw new IllegalArgumentException("exact objective profile is not serializable", failure);
            }
        }
        if (result.profile() != null) return canonical(result.profile());
        if (objective == null) return null;
        try {
            return OBJECTIVE_JSON.writeValueAsString(objective) + "\n";
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("objective result is not serializable", failure);
        }
    }

    public static String resultChecksum(AlgorithmResult result) {
        String canonical = canonicalResult(result);
        return canonical == null ? null : sha256(canonical);
    }

    public static String canonical(EnvelopeProfile profile) {
        StringBuilder text = new StringBuilder();
        for (EnvelopeSegment segment : profile.segments()) {
            Domain.Interval interval = segment.interval();
            text.append(interval.start()).append('|').append(interval.end()).append('|')
                    .append(interval.startInclusive()).append('|').append(interval.endInclusive()).append('|');
            if (segment.noPath()) {
                text.append("NO_PATH\n");
                continue;
            }
            var candidate = segment.candidate();
            text.append(candidate.stablePathId()).append('|');
            candidate.arrivalProfile().breakpoints().forEach(point ->
                    text.append(point.minute()).append(':').append(point.value()).append(','));
            text.append('|');
            candidate.scoreProfile().intervals().forEach(piece ->
                    text.append(piece.startMinute()).append(':').append(piece.endMinute())
                            .append(':').append(piece.value()).append(','));
            text.append('\n');
        }
        return text.toString();
    }

    public static Map<String, Object> output(AlgorithmResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (result.profile() == null) {
            addNullOutput(output);
            if (result.scalars().get("objective_result") instanceof Map<?, ?> objective) {
                objective.forEach((key, value) -> output.put(String.valueOf(key), value));
                output.put("profile_checksum", resultChecksum(result));
                output.put("objective_result", objective);
            }
            return output;
        }
        EnvelopeProfile profile = result.profile();
        double total = measure(profile.domain());
        double feasible = 0;
        double integratedScore = 0;
        double integratedTravelTime = 0;
        int bestScore = 0;
        int paths = 0;
        int pathChanges = 0;
        String previousAssignment = null;
        TreeSet<String> distinct = new TreeSet<>();
        double min = Double.POSITIVE_INFINITY;
        for (EnvelopeSegment segment : profile.segments()) {
            double length = Math.max(0, segment.interval().end() - segment.interval().start());
            if (segment.found()) {
                feasible += length;
                distinct.add(segment.path().arcIds().toString());
                if (length > 0) {
                    double startTravel = segment.candidate()
                            .arrivalProfile().valueAtClosure(
                                    segment.interval().start())
                            - segment.interval().start();
                    double endTravel = segment.candidate()
                            .arrivalProfile().valueAtClosure(
                                    segment.interval().end())
                            - segment.interval().end();
                    integratedTravelTime += length
                            * (startTravel + endTravel) / 2.0;
                }
                for (var piece : segment.candidate().scoreProfile().intervals()) {
                    double overlap = Math.max(0, Math.min(segment.interval().end(), piece.endMinute())
                            - Math.max(segment.interval().start(), piece.startMinute()));
                    integratedScore += overlap * piece.value();
                    if (overlap > 0 || (length == 0
                            && piece.startMinute() <= segment.interval().start()
                            && piece.endMinute() >= segment.interval().end())) {
                        bestScore = Math.max(bestScore, piece.value());
                    }
                }
            }
            String assignment = segment.found() ? segment.path().arcIds().toString() : "NO_PATH";
            if (previousAssignment != null && !previousAssignment.equals(assignment)) {
                pathChanges++;
            }
            previousAssignment = assignment;
            if (length > 0) {
                min = Math.min(min, length);
            }
        }
        paths = distinct.size();
        output.put("feasible", paths > 0);
        output.put("feasible_interval_measure", feasible);
        output.put("infeasible_interval_measure", Math.max(0, total - feasible));
        output.put("feasible_coverage_fraction", total == 0 ? (paths > 0 ? 1.0 : 0.0) : feasible / total);
        output.put("elementary_envelope_cells", profile.segments().size());
        output.put("final_profile_intervals", profile.segments().size());
        output.put("distinct_selected_paths", paths);
        output.put("path_changes", pathChanges);
        output.put("average_interval_length", profile.segments().isEmpty()
                ? null : total / profile.segments().size());
        output.put("minimum_interval_length", Double.isFinite(min) ? min : 0.0);
        output.put("average_selected_score", feasible == 0 ? null : integratedScore / feasible);
        output.put("average_selected_travel_time",
                feasible == 0 ? null : integratedTravelTime / feasible);
        output.put("best_selected_score", paths == 0 ? null : bestScore);
        output.put("selected_departure_time", result.scalars().get("selected_departure_time"));
        output.put("selected_path_id", result.scalars().get("selected_path_id"));
        output.put("selected_score", result.scalars().get("selected_score"));
        output.put("selected_travel_time", result.scalars().get("selected_travel_time"));
        output.put("profile_checksum", checksum(profile));
        output.put("profile_file", null);
        if (result.scalars().get("objective_result") instanceof Map<?, ?> objective) {
            Map<String, Object> legacy = new LinkedHashMap<>(output);
            output.put("legacy_projection", legacy);
            objective.forEach((key, value) -> output.put(String.valueOf(key), value));
            output.put("objective_result", objective);
            Object exactChecksum = objective.get("exact_profile_checksum");
            if (exactChecksum != null) output.put("profile_checksum", exactChecksum);
        }
        return output;
    }

    private static void addNullOutput(Map<String, Object> output) {
        for (String name : List.of("feasible", "feasible_interval_measure", "infeasible_interval_measure",
                "feasible_coverage_fraction", "elementary_envelope_cells", "final_profile_intervals",
                "distinct_selected_paths", "path_changes", "average_interval_length",
                "minimum_interval_length", "average_selected_score", "best_selected_score",
                "average_selected_travel_time",
                "selected_departure_time", "selected_path_id",
                "selected_score", "selected_travel_time", "profile_checksum", "profile_file")) {
            output.put(name, null);
        }
    }

    public static Map<String, Object> quality(EnvelopeProfile candidate, EnvelopeProfile reference) {
        Map<String, Object> quality = emptyQuality();
        if (candidate == null || reference == null) {
            return quality;
        }
        TreeSet<Double> cuts = breakpoints(candidate);
        cuts.addAll(breakpoints(reference));
        List<Double> sorted = new ArrayList<>(cuts);
        double intervalMeasure = measure(reference.domain());
        double agreement = 0;
        double feasibilityDisagreement = 0;
        double regretNumerator = 0;
        double regretDenominator = 0;
        double absoluteGap = 0;
        double maxGap = 0;
        double observedMeasure = 0;
        double scoreAgreementMeasure = 0;
        double tieTravelGapIntegral = 0;
        double tieTravelMeasure = 0;
        List<WeightedGap> scoreGaps = new ArrayList<>();
        for (int index = 0; index + 1 < sorted.size(); index++) {
            double start = sorted.get(index);
            double end = sorted.get(index + 1);
            if (end <= start) {
                continue;
            }
            double sample = Domain.canonicalTime(start + (end - start) / 2.0);
            EnvelopeSegment left = candidate.segmentAt(sample);
            EnvelopeSegment right = reference.segmentAt(sample);
            double length = end - start;
            if (samePath(left, right)) {
                agreement += length;
            }
            if (left.found() != right.found()) {
                feasibilityDisagreement += length;
            }
            if (right.found()) {
                int referenceScore = right.candidate().scoreProfile().valueAt(sample);
                int candidateScore = left.found() ? left.candidate().scoreProfile().valueAt(sample) : 0;
                double gap = Math.max(0, referenceScore - candidateScore);
                regretNumerator += gap * length;
                regretDenominator += Math.max(referenceScore, 1) * length;
                absoluteGap += Math.abs(referenceScore - candidateScore) * length;
                maxGap = Math.max(maxGap, Math.abs(referenceScore - candidateScore));
                observedMeasure += length;
                if (left.found() && candidateScore == referenceScore) {
                    scoreAgreementMeasure += length;
                }
                scoreGaps.add(new WeightedGap(Math.abs(referenceScore - candidateScore), length));
                if (left.found() && candidateScore == referenceScore) {
                    double leftStart = travelTime(left, start);
                    double rightStart = travelTime(right, start);
                    double leftEnd = travelTime(left, end);
                    double rightEnd = travelTime(right, end);
                    tieTravelGapIntegral += integrateAbsoluteLinear(
                            leftStart - rightStart, leftEnd - rightEnd, length);
                    tieTravelMeasure += length;
                }
            }
        }
        TreeSet<Double> candidateBreaks = assignmentBreakpoints(candidate);
        TreeSet<Double> referenceBreaks = assignmentBreakpoints(reference);
        long common = candidateBreaks.stream().filter(referenceBreaks::contains).count();
        quality.put("path_agreement_fraction", intervalMeasure == 0
                ? (samePath(candidate.segmentAt(sorted.get(0)), reference.segmentAt(sorted.get(0))) ? 1.0 : 0.0)
                : agreement / intervalMeasure);
        quality.put("score_agreement_fraction", observedMeasure == 0
                ? 1.0 : scoreAgreementMeasure / observedMeasure);
        quality.put("feasibility_disagreement_fraction", intervalMeasure == 0 ? 0.0
                : feasibilityDisagreement / intervalMeasure);
        quality.put("breakpoint_precision", candidateBreaks.isEmpty() ? 1.0
                : (double) common / candidateBreaks.size());
        quality.put("breakpoint_recall", referenceBreaks.isEmpty() ? 1.0
                : (double) common / referenceBreaks.size());
        quality.put("exact_breakpoint_agreement", candidateBreaks.equals(referenceBreaks));
        quality.put("integrated_score_regret", regretDenominator == 0 ? 0.0
                : regretNumerator / regretDenominator);
        quality.put("relative_score_gap_percent", regretDenominator == 0 ? 0.0
                : 100.0 * regretNumerator / regretDenominator);
        quality.put("mean_absolute_score_gap", observedMeasure == 0 ? null : absoluteGap / observedMeasure);
        quality.put("maximum_score_gap", observedMeasure == 0 ? null : maxGap);
        quality.put("p95_score_gap", weightedPercentile(scoreGaps, observedMeasure, 0.95));
        quality.put("mean_travel_time_gap_on_score_ties", tieTravelMeasure == 0
                ? null : tieTravelGapIntegral / tieTravelMeasure);
        quality.put("missed_path_switches", Math.max(0, referenceBreaks.size() - common));
        quality.put("reference_profile_checksum", checksum(reference));
        return quality;
    }

    public static Map<String, Object> emptyQuality() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("path_agreement_fraction", "score_agreement_fraction",
                "feasibility_disagreement_fraction",
                "breakpoint_precision", "breakpoint_recall", "exact_breakpoint_agreement",
                "integrated_score_regret", "relative_score_gap_percent",
                "mean_absolute_score_gap", "maximum_score_gap", "p95_score_gap",
                "mean_travel_time_gap_on_score_ties", "missed_path_switches",
                "reference_profile_checksum")) {
            result.put(name, null);
        }
        return result;
    }

    private static TreeSet<Double> breakpoints(EnvelopeProfile profile) {
        TreeSet<Double> points = new TreeSet<>();
        for (EnvelopeSegment segment : profile.segments()) {
            points.add(segment.interval().start());
            points.add(segment.interval().end());
            if (segment.found()) {
                segment.candidate().arrivalProfile().breakpoints().forEach(point -> points.add(point.minute()));
                points.addAll(segment.candidate().scoreProfile().breakpoints());
            }
        }
        return points;
    }

    private static TreeSet<Double> assignmentBreakpoints(EnvelopeProfile profile) {
        TreeSet<Double> result = new TreeSet<>();
        for (int index = 1; index < profile.segments().size(); index++) {
            result.add(profile.segments().get(index).interval().start());
        }
        return result;
    }

    private static boolean samePath(EnvelopeSegment left, EnvelopeSegment right) {
        if (left == null || right == null || left.found() != right.found()) {
            return false;
        }
        return !left.found() || left.path().arcIds().equals(right.path().arcIds());
    }

    private static double travelTime(EnvelopeSegment segment, double departure) {
        return segment.candidate().arrivalProfile().valueAtClosure(departure) - departure;
    }

    private static double integrateAbsoluteLinear(double start, double end, double length) {
        double left = Math.abs(start);
        double right = Math.abs(end);
        if (start == 0 || end == 0 || Math.signum(start) == Math.signum(end)) {
            return (left + right) * length / 2.0;
        }
        double zeroFraction = left / (left + right);
        return left * length * zeroFraction / 2.0
                + right * length * (1.0 - zeroFraction) / 2.0;
    }

    private static Double weightedPercentile(
            List<WeightedGap> values, double totalWeight, double fraction) {
        if (values.isEmpty() || totalWeight <= 0) {
            return null;
        }
        values.sort(java.util.Comparator.comparingDouble(WeightedGap::gap));
        double threshold = totalWeight * fraction;
        double cumulative = 0;
        for (WeightedGap value : values) {
            cumulative += value.weight();
            if (cumulative >= threshold) {
                return value.gap();
            }
        }
        return values.get(values.size() - 1).gap();
    }

    private static double measure(Domain domain) {
        double result = 0;
        for (Domain.Interval interval : domain.intervals()) {
            result += Math.max(0, interval.end() - interval.start());
        }
        return result;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record WeightedGap(double gap, double weight) {
    }
}
