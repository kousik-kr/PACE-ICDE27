package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactProfileEnvelope;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Complete exact route-selection profile over a supplied candidate registry. */
public final class ExactPreferenceEnvelope {
    public record Cell(
            ExactTemporalDomain.Interval interval,
            Path path,
            BigInteger score,
            ExactTime arrivalSlope,
            ExactTime arrivalIntercept) {
        public Cell {
            Objects.requireNonNull(interval, "interval");
            boolean noPath = path == null;
            if (noPath != (score == null) || noPath != (arrivalSlope == null) || noPath != (arrivalIntercept == null)) {
                throw new IllegalArgumentException("NO_PATH cells have no route value; selected cells require all values");
            }
        }
        public boolean found() { return path != null; }
        public ExactTime arrivalAt(ExactTime departure) {
            if (!found()) throw new IllegalStateException("NO_PATH cell has no arrival");
            if (!interval.contains(departure)) throw new IllegalArgumentException("departure outside cell");
            return arrivalSlope.multiply(departure).add(arrivalIntercept);
        }
        public ExactTime travelTimeAt(ExactTime departure) {
            return arrivalAt(departure).subtract(departure);
        }
    }

    private static final Comparator<Cell> CELL_ORDER = Comparator
            .comparing((Cell cell) -> cell.interval().start())
            .thenComparing(cell -> !cell.interval().startInclusive())
            .thenComparing(cell -> cell.interval().end())
            .thenComparing(cell -> !cell.interval().endInclusive());

    private final ExactTemporalDomain domain;
    private final List<Cell> cells;
    private final long envelopeComparisons;

    private ExactPreferenceEnvelope(ExactTemporalDomain domain, List<Cell> cells, long comparisons) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.cells = cells.stream().sorted(CELL_ORDER).toList();
        this.envelopeComparisons = comparisons;
        List<ExactTemporalDomain.Interval> intervals = new ArrayList<>(this.cells.size());
        for (int i = 0; i < this.cells.size(); i++) {
            Cell current = this.cells.get(i);
            if (domain.intersection(ExactTemporalDomain.of(current.interval())).isEmpty()) {
                throw new IllegalArgumentException("envelope cell lies outside requested domain");
            }
            // In CELL_ORDER, cells are pairwise disjoint exactly when each one
            // starts after its predecessor ends: every earlier cell then ends
            // no later than the predecessor, so adjacent checks are complete.
            if (i > 0 && overlapsSuccessor(this.cells.get(i - 1).interval(), current.interval())) {
                throw new IllegalArgumentException("exact envelope cells overlap");
            }
            intervals.add(current.interval());
        }
        if (!ExactTemporalDomain.of(intervals).equals(domain)) {
            throw new IllegalArgumentException("exact envelope does not cover its requested domain");
        }
    }

    private static boolean overlapsSuccessor(ExactTemporalDomain.Interval previous, ExactTemporalDomain.Interval next) {
        int order = next.start().compareTo(previous.end());
        return order < 0 || (order == 0 && next.startInclusive() && previous.endInclusive());
    }

    /** Reload hook used by the deterministic exact profile codec. */
    public static ExactPreferenceEnvelope fromSerialized(
            ExactTemporalDomain domain, List<Cell> cells) {
        return new ExactPreferenceEnvelope(domain, cells, 0);
    }

    /** Validated snapshot of cells maintained by {@link IncrementalExactPreferenceEnvelope}. */
    static ExactPreferenceEnvelope fromIncremental(
            ExactTemporalDomain domain, List<Cell> cells, long comparisons) {
        return new ExactPreferenceEnvelope(domain, cells, comparisons);
    }

    public ExactTemporalDomain domain() { return domain; }
    public List<Cell> cells() { return cells; }

    /**
     * The same winner function with the fewest cells: consecutive contiguous cells that select the same
     * route with the same score and the same exact arrival are merged, singleton cells included. The
     * refined envelope keeps a cut at every boundary of every entry's pieces, so on real queries only
     * about one cell in ten is a change of winner. The result selects, at every departure of the domain,
     * exactly the same route, score and arrival as this envelope, and is idempotent.
     */
    public ExactPreferenceEnvelope canonicalized() {
        List<Cell> merged = new ArrayList<>();
        Cell run = null;
        for (Cell cell : cells) {
            if (run != null && sameValue(run, cell) && contiguous(run.interval(), cell.interval())) {
                ExactTemporalDomain.Interval joined = new ExactTemporalDomain.Interval(run.interval().start(),
                        cell.interval().end(), run.interval().startInclusive(), cell.interval().endInclusive());
                run = new Cell(joined, run.path(), run.score(), run.arrivalSlope(), run.arrivalIntercept());
            } else {
                if (run != null) merged.add(run);
                run = cell;
            }
        }
        if (run != null) merged.add(run);
        return new ExactPreferenceEnvelope(domain, merged, envelopeComparisons);
    }

    private static boolean sameValue(Cell left, Cell right) {
        if (left.found() != right.found()) return false;
        if (!left.found()) return true;
        return left.path().equals(right.path()) && left.score().equals(right.score())
                && left.arrivalSlope().equals(right.arrivalSlope())
                && left.arrivalIntercept().equals(right.arrivalIntercept());
    }

    /** Adjacent with no unowned point between: the shared boundary is owned by exactly one side. */
    private static boolean contiguous(ExactTemporalDomain.Interval left, ExactTemporalDomain.Interval right) {
        return left.end().equals(right.start()) && (left.endInclusive() || right.startInclusive());
    }
    public long envelopeComparisons() { return envelopeComparisons; }

    public int maximumRationalComponentBitLength() {
        int result = 0;
        for (Cell cell : cells) {
            result = Math.max(result, Math.max(
                    bitLength(cell.interval().start()), bitLength(cell.interval().end())));
            if (cell.found()) {
                result = Math.max(result, Math.max(
                        bitLength(cell.arrivalSlope()), bitLength(cell.arrivalIntercept())));
                result = Math.max(result, cell.score().abs().bitLength());
            }
        }
        return result;
    }

    public Optional<Cell> cellAt(ExactTime departure) {
        if (!domain.contains(departure)) return Optional.empty();
        return cells.stream().filter(cell -> cell.interval().contains(departure)).findFirst();
    }

    public boolean hasFeasibleCell() { return cells.stream().anyMatch(Cell::found); }

    /** Exact lower envelope by score, travel time, edge count, then arc-ID sequence. */
    public static ExactPreferenceEnvelope overRetained(
            ExactTemporalDomain requested,
            Map<Path, ExactPreferencePathProfile> profiles) {
        return overRetained(requested, profiles, () -> false);
    }

    public static ExactPreferenceEnvelope overRetained(
            ExactTemporalDomain requested,
            Map<Path, ExactPreferencePathProfile> profiles,
            BooleanSupplier cancellationRequested) {
        TreeSet<ExactTime> cuts = new TreeSet<>();
        List<ExactPreferencePathProfile> usable = profiles.values().stream()
                .filter(profile -> !profile.feasibleDomain().isEmpty()).toList();
        java.util.Set<EvaluationKey> evaluatedFunctions = new java.util.HashSet<>();
        ExactArithmeticDiagnostics.add("exact_candidate_profiles_envelope_processed", usable.size());
        long pairComparisons = 0;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_envelope_event_generation")) {
            for (ExactTemporalDomain.Interval interval : requested.intervals()) {
                checkCancelled(cancellationRequested);
                addCut(cuts, interval.start());
                addCut(cuts, interval.end());
            }
            for (ExactPreferencePathProfile profile : usable) {
                checkCancelled(cancellationRequested);
                ExactArithmeticDiagnostics.add(
                        "exact_profile_segments_processed",
                        profile.arrival().segments().size()
                                + profile.score().segments().size());
                addDomainCuts(cuts, profile.feasibleDomain());
                profile.arrival().segments().forEach(segment -> {
                    checkCancelled(cancellationRequested);
                    addCut(cuts, segment.interval().start());
                    addCut(cuts, segment.interval().end());
                });
                profile.score().segments().forEach(segment -> {
                    checkCancelled(cancellationRequested);
                    addCut(cuts, segment.interval().start());
                    addCut(cuts, segment.interval().end());
                });
            }
            for (int i = 0; i < usable.size(); i++) {
                for (int j = i + 1; j < usable.size(); j++) {
                    checkCancelled(cancellationRequested);
                    ExactPreferencePathProfile left = usable.get(i);
                    ExactPreferencePathProfile right = usable.get(j);
                    ExactTemporalDomain common = left.feasibleDomain().intersection(right.feasibleDomain());
                    if (common.isEmpty()) continue;
                    for (ExactTime crossing : ExactProfileEnvelope.breakpointCrossings(
                            left.arrival(), right.arrival(), cancellationRequested)) {
                        if (common.contains(crossing)) addCut(cuts, crossing);
                    }
                    pairComparisons += (long) left.arrival().segments().size() * right.arrival().segments().size();
                }
            }
        }

        List<ExactTime> ordered = List.copyOf(cuts);
        List<Cell> result = new ArrayList<>();
        long comparisons = pairComparisons;
        try (var ignored = ExactArithmeticDiagnostics.phase(
                "exact_envelope_winner_selection")) {
            for (int i = 0; i + 1 < ordered.size(); i++) {
                checkCancelled(cancellationRequested);
                ExactTime start = ordered.get(i), end = ordered.get(i + 1);
                if (start.equals(end)) continue;
                ExactTime probe = start.add(end).divide(ExactTime.of(2));
                if (!requested.contains(probe)) continue;
                Chosen chosen = select(
                        usable, probe, cancellationRequested, evaluatedFunctions);
                Selection selection = chosen.selection();
                comparisons += chosen.comparisons();
                ExactTemporalDomain.Interval cell = new ExactTemporalDomain.Interval(start, end, false, false);
                result.add(selection == null ? new Cell(cell, null, null, null, null)
                        : selectedCell(cell, selection, probe));
            }
            for (ExactTime point : ordered) {
                checkCancelled(cancellationRequested);
                if (!requested.contains(point)) continue;
                Chosen chosen = select(
                        usable, point, cancellationRequested, evaluatedFunctions);
                Selection selection = chosen.selection();
                comparisons += chosen.comparisons();
                ExactTemporalDomain.Interval cell = ExactTemporalDomain.Interval.singleton(point);
                result.add(selection == null ? new Cell(cell, null, null, null, null)
                        : selectedCell(cell, selection, point));
            }
        }
        ExactArithmeticDiagnostics.add("exact_envelope_comparisons", comparisons);
        ExactArithmeticDiagnostics.add("exact_envelope_cells_created", result.size());
        return new ExactPreferenceEnvelope(requested, result, comparisons);
    }

    private record Selection(ExactPreferencePathProfile profile, BigInteger score, ExactTime arrival) { }

    private record Chosen(Selection selection, long comparisons) { }

    private record EvaluationKey(Path path, ExactTime departure) { }

    private static Chosen select(
            List<ExactPreferencePathProfile> profiles, ExactTime departure,
            BooleanSupplier cancellationRequested,
            java.util.Set<EvaluationKey> evaluatedFunctions) {
        Selection best = null;
        long comparisons = 0;
        for (ExactPreferencePathProfile candidate : profiles) {
            checkCancelled(cancellationRequested);
            if (!candidate.feasibleDomain().contains(departure)) continue;
            boolean repeated = !evaluatedFunctions.add(
                    new EvaluationKey(candidate.path(), departure));
            ExactArithmeticDiagnostics.add("exact_function_evaluations", 2);
            if (repeated) {
                ExactArithmeticDiagnostics.add(
                        "exact_repeated_function_evaluations", 2);
            }
            BigInteger score = candidate.score().valueAt(departure);
            ExactTime arrival = candidate.arrival().valueAtExact(departure);
            Selection current = new Selection(candidate, score, arrival);
            if (best == null) best = current;
            else {
                comparisons++;
                ExactArithmeticDiagnostics.increment(
                        "exact_envelope_winner_comparisons");
                if (compare(current, best) > 0) best = current;
            }
        }
        return new Chosen(best, comparisons);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested) {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new CancellationException("exact preference-envelope commitment cancelled");
        }
    }

    /** Positive means left wins. */
    private static int compare(Selection left, Selection right) {
        return compareWinners(left.score(), left.arrival(), left.profile().path(),
                right.score(), right.arrival(), right.profile().path());
    }

    /**
     * The single winner order for every exact envelope: higher score, earlier
     * arrival, fewer edges, then lexicographically smaller arc IDs. Positive
     * means left wins.
     */
    static int compareWinners(BigInteger leftScore, ExactTime leftArrival, Path leftPath,
            BigInteger rightScore, ExactTime rightArrival, Path rightPath) {
        int score = leftScore.compareTo(rightScore);
        if (score != 0) return score;
        int arrival = rightArrival.compareTo(leftArrival);
        if (arrival != 0) return arrival;
        int edges = Integer.compare(rightPath.arcIds().size(), leftPath.arcIds().size());
        if (edges != 0) return edges;
        return -lexicographic(leftPath.arcIds(), rightPath.arcIds());
    }

    private static int lexicographic(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int order = Integer.compare(left.get(i), right.get(i));
            if (order != 0) return order;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static Cell selectedCell(
            ExactTemporalDomain.Interval interval, Selection selection, ExactTime probe) {
        var affine = selection.profile().arrival().segments().stream()
                .filter(segment -> segment.interval().contains(probe))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("selected cell crosses an arrival event"));
        return new Cell(interval, selection.profile().path(), selection.score(),
                affine.slope(), affine.intercept());
    }

    private static void addDomainCuts(TreeSet<ExactTime> cuts, ExactTemporalDomain domain) {
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            addCut(cuts, interval.start());
            addCut(cuts, interval.end());
        }
    }

    private static void addCut(TreeSet<ExactTime> cuts, ExactTime value) {
        ExactArithmeticDiagnostics.increment("exact_temporal_cuts_attempted");
        if (cuts.add(value)) {
            ExactArithmeticDiagnostics.increment("exact_temporal_cuts_created");
        }
    }

    private static int bitLength(ExactTime value) {
        return Math.max(value.numerator().abs().bitLength(), value.denominator().bitLength());
    }
}
