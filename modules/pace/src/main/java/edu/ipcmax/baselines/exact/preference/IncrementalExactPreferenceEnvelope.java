package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.ExactProfileEnvelope;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Exact preference envelope maintained over an append-only set of entries.
 *
 * <p>After every {@link #add}, {@link #snapshot()} has exactly the cells of
 * {@link ExactPreferenceEnvelope#overRetained} over the entries added so far:
 * the cut set is the union of each entry's domain and segment endpoints with
 * pairwise arrival crossings inside common feasible support, and each cell
 * keeps the winner of the same total order. Within an existing open cell no
 * entry has a cut, so the old winner holds on every sub-cell; an add therefore
 * only splits cells at the new entry's cuts and compares the new entry with
 * the incumbent where it is feasible.</p>
 *
 * <p>An entry is either a whole route profile or the profile of one departure
 * chunk of a route. Entries for one route must have disjoint departure
 * supports; the envelope then equals the one over the per-route union.</p>
 *
 * <p>An add is staged and becomes visible atomically: if it is cancelled, the
 * previous state is unchanged. Not thread-safe; one coordinator owns it.</p>
 */
public final class IncrementalExactPreferenceEnvelope implements ExactEnvelopeBuilder {
    private record Winner(ExactPreferencePathProfile profile, BigInteger score, ExactAffineSegment affine) { }

    /**
     * The point cell at one cut and the open cell from it to the next cut.
     * The open cell's probe is computed once, when the cell is created or
     * split; winners' arrivals at their probes are computed only when a score
     * tie needs them and are then kept until the winner or probe changes.
     */
    private static final class Slot {
        final boolean pointInDomain;
        Winner point;
        ExactTime pointArrival;
        boolean openInDomain;
        ExactTime openProbe;
        Winner open;
        ExactTime openArrival;

        Slot(boolean pointInDomain, Winner point, boolean openInDomain, Winner open) {
            this.pointInDomain = pointInDomain;
            this.point = point;
            this.openInDomain = openInDomain;
            this.open = open;
        }

        Slot copy() {
            Slot copy = new Slot(pointInDomain, point, openInDomain, open);
            copy.pointArrival = pointArrival;
            copy.openProbe = openProbe;
            copy.openArrival = openArrival;
            return copy;
        }
    }

    /** Outcome of one comparison, with any arrivals it had to evaluate. */
    private record Decision(boolean challengerWins, ExactTime challengerArrival, ExactTime incumbentArrival) { }

    /**
     * Where one add spent its time and how much of the envelope it touched. Diagnostic only:
     * nothing in the envelope depends on it.
     */
    public record Stats(int usableBefore, int cutsBefore, int cutsAfter, int pairsWithCommonSupport,
            long crossingRoots, long freshCuts, int cutsScanned, long comparisons,
            long cutsNs, long crossingNs, long stageNs, long insertNs, long scanNs, long totalNs) {
        public static final Stats NONE = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Exact effect of one add on the published envelope. The gains may be computed lazily; whether the add
     * improved anything is always known without arithmetic.
     */
    public static final class Update {
        private final boolean changed;
        private final int newCuts;
        private final int cellsWon;
        private final EnvelopeGain gain;
        private final long version;
        private final Stats stats;

        public Update(boolean changed, int newCuts, int cellsWon, EnvelopeGain gain, long version, Stats stats) {
            this.changed = changed;
            this.newCuts = newCuts;
            this.cellsWon = cellsWon;
            this.gain = gain;
            this.version = version;
            this.stats = stats;
        }

        public Update(boolean changed, int newCuts, int cellsWon, ExactTime coverageGain,
                ExactTime integratedPreferenceGain, long version, Stats stats) {
            this(changed, newCuts, cellsWon, EnvelopeGain.eager(coverageGain, integratedPreferenceGain), version, stats);
        }

        public Update(boolean changed, int newCuts, int cellsWon, ExactTime coverageGain,
                ExactTime integratedPreferenceGain, long version) {
            this(changed, newCuts, cellsWon, coverageGain, integratedPreferenceGain, version, Stats.NONE);
        }

        public boolean changed() { return changed; }
        public int newCuts() { return newCuts; }
        public int cellsWon() { return cellsWon; }
        public EnvelopeGain gain() { return gain; }
        public ExactTime coverageGain() { return gain.coverage(); }
        public ExactTime integratedPreferenceGain() { return gain.preference(); }
        public long version() { return version; }
        public Stats stats() { return stats; }
        public boolean improved() { return gain.improved(); }
    }

    private final ExactTemporalDomain requested;
    private TreeMap<ExactTime, Slot> slots = new TreeMap<>();
    private final List<ExactPreferencePathProfile> usable = new ArrayList<>();
    private final Set<Path> usableRoutes = new HashSet<>();
    private final Map<Path, List<ExactPreferencePathProfile>> pendingInfeasible = new HashMap<>();
    private long comparisons;
    private long version;

    public IncrementalExactPreferenceEnvelope(ExactTemporalDomain requested) {
        this.requested = Objects.requireNonNull(requested, "requested");
        TreeSet<ExactTime> cuts = new TreeSet<>();
        for (ExactTemporalDomain.Interval interval : requested.intervals()) {
            cuts.add(interval.start());
            cuts.add(interval.end());
        }
        ExactTime previous = null;
        for (ExactTime cut : cuts) {
            if (previous != null) {
                Slot before = slots.get(previous);
                ExactTime probe = midpoint(previous, cut);
                before.openInDomain = requested.contains(probe);
                before.openProbe = before.openInDomain ? probe : null;
            }
            slots.put(cut, new Slot(requested.contains(cut), null, false, null));
            previous = cut;
        }
    }

    @Override public ExactTemporalDomain requestedDomain() { return requested; }
    @Override public long version() { return version; }
    @Override public int entryCount() { return usable.size(); }
    @Override public long comparisons() { return comparisons; }

    @Override public int cellCount() {
        int cells = 0, index = 0, size = slots.size();
        for (Slot slot : slots.values()) {
            if (slot.pointInDomain) cells++;
            if (slot.openInDomain && ++index < size) cells++;
            else if (!slot.openInDomain) index++;
        }
        return cells;
    }

    @Override public int cutCount() { return slots.size(); }

    /**
     * Adds one validated entry. {@code overRetained} uses a route only when it
     * is feasible somewhere, but then takes cuts from all of its segments,
     * including over-budget ones. An entry without feasible support is
     * therefore held until its route becomes usable; for a route that already
     * is usable, its segment cuts are inserted at once.
     */
    @Override public Update add(ExactPreferencePathProfile entry, BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(entry, "entry");
        if (slots.isEmpty()) return new Update(false, 0, 0, ExactTime.ZERO, ExactTime.ZERO, version);
        ExactArithmeticDiagnostics.increment("exact_incremental_envelope_adds");
        long tStart = System.nanoTime();
        long comparisonsBefore = comparisons;
        int cutsBefore = slots.size();
        boolean routeUsable = usableRoutes.contains(entry.path());
        TreeSet<ExactTime> fresh = new TreeSet<>();
        if (entry.feasibleDomain().isEmpty()) {
            if (!routeUsable) {
                pendingInfeasible.computeIfAbsent(entry.path(), ignored -> new ArrayList<>()).add(entry);
                return new Update(false, 0, 0, ExactTime.ZERO, ExactTime.ZERO, version);
            }
            addSegmentCuts(fresh, entry, cancellationRequested);
        } else {
            addDomainCuts(fresh, entry.feasibleDomain());
            addSegmentCuts(fresh, entry, cancellationRequested);
            if (!routeUsable) {
                for (ExactPreferencePathProfile held : pendingInfeasible.getOrDefault(entry.path(), List.of())) {
                    addSegmentCuts(fresh, held, cancellationRequested);
                }
            }
        }
        long tCuts = System.nanoTime();
        long pairComparisons = 0;
        int commonPairs = 0;
        long roots = 0;
        for (ExactPreferencePathProfile other : usable) {
            check(cancellationRequested);
            ExactTemporalDomain common = entry.feasibleDomain().intersection(
                    other.feasibleDomain(), cancellationRequested);
            if (common.isEmpty()) continue;
            commonPairs++;
            for (ExactTime crossing : ExactProfileEnvelope.breakpointCrossings(
                    entry.arrival(), other.arrival(), cancellationRequested)) {
                if (common.contains(crossing)) {
                    fresh.add(crossing);
                    roots++;
                }
            }
            pairComparisons += (long) entry.arrival().segments().size() * other.arrival().segments().size();
        }
        long tCrossings = System.nanoTime();

        TreeMap<ExactTime, Slot> staged = new TreeMap<>();
        for (Map.Entry<ExactTime, Slot> slot : slots.entrySet()) staged.put(slot.getKey(), slot.getValue().copy());
        long tStaged = System.nanoTime();
        ExactTime first = staged.firstKey();
        ExactTime last = staged.lastKey();
        int inserted = 0;
        for (ExactTime cut : fresh) {
            check(cancellationRequested);
            // Cuts outside the requested hull cannot split a requested cell.
            if (cut.compareTo(first) < 0 || cut.compareTo(last) > 0 || staged.containsKey(cut)) continue;
            Map.Entry<ExactTime, Slot> lower = staged.lowerEntry(cut);
            ExactTime upper = staged.higherKey(cut);
            Slot split = lower.getValue();
            Winner inherited = split.openInDomain ? split.open : null;
            boolean pointIn = requested.contains(cut);
            ExactTime rightProbe = midpoint(cut, upper);
            boolean rightIn = requested.contains(rightProbe);
            ExactTime leftProbe = midpoint(lower.getKey(), cut);
            split.openInDomain = requested.contains(leftProbe);
            split.openProbe = split.openInDomain ? leftProbe : null;
            split.openArrival = null;
            if (!split.openInDomain) split.open = null;
            Slot created = new Slot(pointIn, pointIn ? inherited : null, rightIn, rightIn ? inherited : null);
            created.openProbe = rightIn ? rightProbe : null;
            staged.put(cut, created);
            inserted++;
        }

        long tInserted = System.nanoTime();
        Cursor cursor = new Cursor(entry);
        int won = 0;
        int scanned = 0;
        ExactTime coverage = ExactTime.ZERO;
        ExactTime preference = ExactTime.ZERO;
        boolean feasible = !entry.feasibleDomain().isEmpty();
        ExactTemporalDomain.Interval hull = feasible ? hull(entry.feasibleDomain()) : null;
        Map.Entry<ExactTime, Slot> at = !feasible ? null : staged.floorEntry(hull.start());
        if (feasible && at == null) at = staged.firstEntry();
        while (at != null && at.getKey().compareTo(hull.end()) <= 0) {
            check(cancellationRequested);
            scanned++;
            ExactTime cut = at.getKey();
            Slot slot = at.getValue();
            if (slot.pointInDomain && entry.feasibleDomain().contains(cut)) {
                Winner challenger = cursor.at(cut);
                Decision decision = decide(challenger, slot.point, slot.pointArrival, cut);
                if (decision.challengerWins()) {
                    slot.point = challenger;
                    slot.pointArrival = decision.challengerArrival();
                    won++;
                } else {
                    slot.pointArrival = decision.incumbentArrival();
                }
            }
            Map.Entry<ExactTime, Slot> next = staged.higherEntry(cut);
            if (slot.openInDomain && next != null) {
                ExactTime probe = slot.openProbe;
                if (entry.feasibleDomain().contains(probe)) {
                    Winner incumbent = slot.open;
                    Winner challenger = cursor.at(probe);
                    Decision decision = decide(challenger, incumbent, slot.openArrival, probe);
                    if (!decision.challengerWins()) slot.openArrival = decision.incumbentArrival();
                    if (decision.challengerWins()) {
                        ExactTime length = next.getKey().subtract(cut);
                        if (incumbent == null) {
                            coverage = coverage.add(length);
                            preference = preference.add(length.multiply(ExactTime.of(challenger.score(), BigInteger.ONE)));
                        } else {
                            preference = preference.add(length.multiply(ExactTime.of(
                                    challenger.score().subtract(incumbent.score()), BigInteger.ONE)));
                        }
                        slot.open = challenger;
                        slot.openArrival = decision.challengerArrival();
                        won++;
                    }
                }
            }
            at = next;
        }
        check(cancellationRequested);
        slots = staged;
        if (feasible) {
            usable.add(entry);
            usableRoutes.add(entry.path());
            pendingInfeasible.remove(entry.path());
        }
        comparisons += pairComparisons;
        if (inserted > 0 || won > 0) version++;
        ExactArithmeticDiagnostics.add("exact_incremental_envelope_cuts_inserted", inserted);
        long tEnd = System.nanoTime();
        Stats stats = new Stats(usable.size() - (feasible ? 1 : 0), cutsBefore, slots.size(), commonPairs, roots,
                fresh.size(), scanned, comparisons - comparisonsBefore, tCuts - tStart, tCrossings - tCuts,
                tStaged - tCrossings, tInserted - tStaged, tEnd - tInserted, tEnd - tStart);
        return new Update(inserted > 0 || won > 0, inserted, won, coverage, preference, version, stats);
    }

    private static void addSegmentCuts(TreeSet<ExactTime> cuts, ExactPreferencePathProfile entry,
            BooleanSupplier cancellationRequested) {
        for (ExactAffineSegment segment : entry.arrival().segments()) {
            check(cancellationRequested);
            cuts.add(segment.interval().start());
            cuts.add(segment.interval().end());
        }
        for (ExactScoreProfile.Segment segment : entry.score().segments()) {
            check(cancellationRequested);
            cuts.add(segment.interval().start());
            cuts.add(segment.interval().end());
        }
    }

    /**
     * The shared winner order decides on score first, so arrivals are only
     * evaluated on a score tie; the outcome equals a full comparison.
     */
    private Decision decide(Winner challenger, Winner incumbent, ExactTime incumbentArrival, ExactTime at) {
        if (incumbent == null) return new Decision(true, null, null);
        comparisons++;
        int score = challenger.score().compareTo(incumbent.score());
        if (score != 0) return new Decision(score > 0, null, incumbentArrival);
        ExactTime challengerArrival = challenger.affine().valueAt(at);
        ExactTime known = incumbentArrival != null ? incumbentArrival : incumbent.affine().valueAt(at);
        boolean wins = ExactPreferenceEnvelope.compareWinners(
                challenger.score(), challengerArrival, challenger.profile().path(),
                incumbent.score(), known, incumbent.profile().path()) > 0;
        return new Decision(wins, challengerArrival, known);
    }

    /** Immutable, validated envelope over every entry added so far. */
    @Override public ExactPreferenceEnvelope snapshot() {
        List<ExactPreferenceEnvelope.Cell> cells = new ArrayList<>();
        Map.Entry<ExactTime, Slot> at = slots.isEmpty() ? null : slots.firstEntry();
        while (at != null) {
            ExactTime cut = at.getKey();
            Slot slot = at.getValue();
            if (slot.pointInDomain) cells.add(cell(ExactTemporalDomain.Interval.singleton(cut), slot.point));
            Map.Entry<ExactTime, Slot> next = slots.higherEntry(cut);
            if (slot.openInDomain && next != null) {
                cells.add(cell(new ExactTemporalDomain.Interval(cut, next.getKey(), false, false), slot.open));
            }
            at = next;
        }
        return ExactPreferenceEnvelope.fromIncremental(requested, cells, comparisons);
    }

    private static ExactPreferenceEnvelope.Cell cell(ExactTemporalDomain.Interval interval, Winner winner) {
        return winner == null ? new ExactPreferenceEnvelope.Cell(interval, null, null, null, null)
                : new ExactPreferenceEnvelope.Cell(interval, winner.profile().path(), winner.score(),
                        winner.affine().slope(), winner.affine().intercept());
    }

    /** Monotone evaluation of one entry at nondecreasing probe times. */
    private static final class Cursor {
        private final List<ExactAffineSegment> arrival;
        private final List<ExactScoreProfile.Segment> score;
        private final ExactPreferencePathProfile entry;
        private int arrivalAt;
        private int scoreAt;

        Cursor(ExactPreferencePathProfile entry) {
            this.entry = entry;
            this.arrival = entry.arrival().segments();
            this.score = entry.score().segments();
        }

        Winner at(ExactTime time) {
            while (arrivalAt < arrival.size() && !arrival.get(arrivalAt).interval().contains(time)
                    && arrival.get(arrivalAt).interval().end().compareTo(time) <= 0) arrivalAt++;
            while (scoreAt < score.size() && !score.get(scoreAt).interval().contains(time)
                    && score.get(scoreAt).interval().end().compareTo(time) <= 0) scoreAt++;
            if (arrivalAt == arrival.size() || !arrival.get(arrivalAt).interval().contains(time)
                    || scoreAt == score.size() || !score.get(scoreAt).interval().contains(time)) {
                throw new IllegalStateException("feasible entry has no exact value at " + time);
            }
            return new Winner(entry, score.get(scoreAt).value(), arrival.get(arrivalAt));
        }
    }

    private static ExactTemporalDomain.Interval hull(ExactTemporalDomain domain) {
        var intervals = domain.intervals();
        var low = intervals.getFirst();
        var high = intervals.getLast();
        return new ExactTemporalDomain.Interval(low.start(), high.end(), true, true);
    }

    private static void addDomainCuts(TreeSet<ExactTime> cuts, ExactTemporalDomain domain) {
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            cuts.add(interval.start());
            cuts.add(interval.end());
        }
    }

    private static ExactTime midpoint(ExactTime left, ExactTime right) {
        return left.add(right).divide(ExactTime.of(2));
    }

    private static void check(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("incremental exact envelope update cancelled");
        }
    }
}
