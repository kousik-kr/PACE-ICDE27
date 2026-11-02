package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.ExactProfileEnvelope;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactTimeApproximation;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static edu.ipcmax.baselines.exact.ExactTimeApproximation.compare;

/**
 * The exact preference envelope of {@link IncrementalExactPreferenceEnvelope}, changed in place.
 *
 * <p>After every add, {@link #snapshot()} has exactly the cells of
 * {@link ExactPreferenceEnvelope#overRetained} over the entries added so far, for any add order:
 * the same cut set (each entry's domain and segment endpoints plus arrival crossings inside common
 * feasible support) and the same total winner order. What changes is the work per add:</p>
 * <ul>
 *   <li>cells are modified in place and an undo journal restores the previous state if the add is
 *       cancelled, instead of copying every cell first;</li>
 *   <li>cuts are ordered by a certain-margin double approximation and fall back to the exact
 *       comparison only when the approximations cannot decide, so the order is always the exact one;</li>
 *   <li>one sweep over the cells in the entry's hull replaces a search per cell: every boundary of the
 *       entry is a cut, so each open cell lies in exactly one piece;</li>
 *   <li>probe points are computed only for score ties, where arrival times decide.</li>
 * </ul>
 * <p>Not thread-safe; one coordinator owns it.</p>
 */
public final class JournaledExactPreferenceEnvelope implements ExactEnvelopeBuilder {
    private record Winner(ExactPreferencePathProfile profile, BigInteger score, ExactAffineSegment affine) { }

    /** The point cell at one cut and the open cell from it to the next cut. */
    private static final class Slot {
        long born;
        boolean pointInDomain;
        Winner point;
        ExactTime pointArrival;
        boolean openInDomain;
        Winner open;
        ExactTime openArrival;
        ExactTime openProbe;

        Slot(long born, boolean pointInDomain, Winner point, boolean openInDomain, Winner open) {
            this.born = born;
            this.pointInDomain = pointInDomain;
            this.point = point;
            this.openInDomain = openInDomain;
            this.open = open;
        }

        Slot copy() {
            Slot copy = new Slot(born, pointInDomain, point, openInDomain, open);
            copy.pointArrival = pointArrival;
            copy.openArrival = openArrival;
            copy.openProbe = openProbe;
            return copy;
        }

        void restore(Slot from) {
            pointInDomain = from.pointInDomain;
            point = from.point;
            pointArrival = from.pointArrival;
            openInDomain = from.openInDomain;
            open = from.open;
            openArrival = from.openArrival;
            openProbe = from.openProbe;
        }
    }

    /** Undo log of one add: the prior state of every changed pre-existing cell and the cuts it inserted. */
    private static final class Journal {
        final IdentityHashMap<Slot, Slot> before = new IdentityHashMap<>();
        final List<Cut> inserted = new ArrayList<>();

        void touch(Slot slot, long sequence) {
            if (slot.born != sequence && !before.containsKey(slot)) before.put(slot, slot.copy());
        }
    }

    private final ExactTemporalDomain requested;
    private final TreeMap<Cut, Slot> slots = new TreeMap<>();
    private final List<ExactPreferencePathProfile> usable = new ArrayList<>();
    private final Set<Path> usableRoutes = new HashSet<>();
    private final Map<Path, List<ExactPreferencePathProfile>> pendingInfeasible = new HashMap<>();
    private long comparisons;
    private long version;
    private long addSequence;

    public JournaledExactPreferenceEnvelope(ExactTemporalDomain requested) {
        this.requested = Objects.requireNonNull(requested, "requested");
        TreeSet<Cut> cuts = new TreeSet<>();
        for (ExactTemporalDomain.Interval interval : requested.intervals()) {
            cuts.add(new Cut(interval.start()));
            cuts.add(new Cut(interval.end()));
        }
        Cut previous = null;
        for (Cut cut : cuts) {
            if (previous != null) {
                Slot before = slots.get(previous);
                before.openInDomain = requested.contains(midpoint(previous.value, cut.value));
            }
            slots.put(cut, new Slot(0, requested.contains(cut.value), null, false, null));
            previous = cut;
        }
    }

    @Override public ExactTemporalDomain requestedDomain() { return requested; }
    @Override public long version() { return version; }
    @Override public int entryCount() { return usable.size(); }
    @Override public long comparisons() { return comparisons; }
    @Override public int cutCount() { return slots.size(); }

    @Override public int cellCount() {
        int cells = 0, remaining = slots.size();
        for (Slot slot : slots.values()) {
            remaining--;
            if (slot.pointInDomain) cells++;
            if (slot.openInDomain && remaining > 0) cells++;
        }
        return cells;
    }

    @Override
    public IncrementalExactPreferenceEnvelope.Update add(ExactPreferencePathProfile entry,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(entry, "entry");
        if (slots.isEmpty()) return update(false, 0, 0, ExactTime.ZERO, ExactTime.ZERO, null);
        ExactArithmeticDiagnostics.increment("exact_incremental_envelope_adds");
        long tStart = System.nanoTime();
        int cutsBefore = slots.size();
        long sequence = ++addSequence;
        boolean routeUsable = usableRoutes.contains(entry.path());
        boolean feasible = !entry.feasibleDomain().isEmpty();
        TreeSet<Cut> fresh = new TreeSet<>();
        if (!feasible) {
            if (!routeUsable) {
                pendingInfeasible.computeIfAbsent(entry.path(), ignored -> new ArrayList<>()).add(entry);
                return update(false, 0, 0, ExactTime.ZERO, ExactTime.ZERO, null);
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
                    fresh.add(new Cut(crossing));
                    roots++;
                }
            }
            pairComparisons += (long) entry.arrival().segments().size() * other.arrival().segments().size();
        }
        long tCrossings = System.nanoTime();

        Journal journal = new Journal();
        int inserted;
        Sweep sweep;
        long tInserted;
        try {
            inserted = insertCuts(fresh, journal, sequence, cancellationRequested);
            tInserted = System.nanoTime();
            sweep = feasible ? sweep(entry, journal, sequence, cancellationRequested) : Sweep.NONE;
            check(cancellationRequested);
        } catch (RuntimeException | Error failure) {
            rollback(journal);
            throw failure;
        }
        if (feasible) {
            usable.add(entry);
            usableRoutes.add(entry.path());
            pendingInfeasible.remove(entry.path());
        }
        comparisons += pairComparisons + sweep.comparisons;
        if (inserted > 0 || sweep.won > 0) version++;
        ExactArithmeticDiagnostics.add("exact_incremental_envelope_cuts_inserted", inserted);
        long tEnd = System.nanoTime();
        var stats = new IncrementalExactPreferenceEnvelope.Stats(usable.size() - (feasible ? 1 : 0), cutsBefore,
                slots.size(), commonPairs, roots, fresh.size(), sweep.scanned, sweep.comparisons,
                tCuts - tStart, tCrossings - tCuts, 0, tInserted - tCrossings, tEnd - tInserted, tEnd - tStart);
        return update(inserted > 0 || sweep.won > 0, inserted, sweep.won, sweep.coverage, sweep.preference, stats);
    }

    private IncrementalExactPreferenceEnvelope.Update update(boolean changed, int inserted, int won, ExactTime coverage,
            ExactTime preference, IncrementalExactPreferenceEnvelope.Stats stats) {
        return new IncrementalExactPreferenceEnvelope.Update(changed, inserted, won, coverage, preference, version,
                stats == null ? IncrementalExactPreferenceEnvelope.Stats.NONE : stats);
    }

    private void rollback(Journal journal) {
        for (Map.Entry<Slot, Slot> saved : journal.before.entrySet()) saved.getKey().restore(saved.getValue());
        for (Cut cut : journal.inserted) slots.remove(cut);
    }

    /** Splits the cells at the cuts that are not yet present; inside the requested hull only. */
    private int insertCuts(TreeSet<Cut> fresh, Journal journal, long sequence, BooleanSupplier cancellationRequested) {
        Cut first = slots.firstKey();
        Cut last = slots.lastKey();
        int inserted = 0;
        for (Cut cut : fresh) {
            check(cancellationRequested);
            if (cut.compareTo(first) < 0 || cut.compareTo(last) > 0) continue;
            Map.Entry<Cut, Slot> lower = slots.floorEntry(cut);
            if (lower.getKey().compareTo(cut) == 0) continue;
            Slot split = lower.getValue();
            journal.touch(split, sequence);
            // Every requested endpoint is a cut, so a cell lies wholly inside or outside the requested
            // domain and both halves (and the new point cut) inherit that.
            boolean inside = split.openInDomain;
            Winner inherited = inside ? split.open : null;
            Slot created = new Slot(sequence, inside, inherited, inside, inherited);
            split.openProbe = null;
            split.openArrival = null;
            slots.put(cut, created);
            journal.inserted.add(cut);
            inserted++;
        }
        return inserted;
    }

    private record Sweep(int won, int scanned, long comparisons, ExactTime coverage, ExactTime preference) {
        static final Sweep NONE = new Sweep(0, 0, 0, ExactTime.ZERO, ExactTime.ZERO);
    }

    /** Position of an entry boundary relative to a cut: -1 below, 0 equal, 1 above. */
    private static int order(ExactTime boundary, double approximation, Cut cut) {
        return compare(boundary, approximation, cut.value, cut.approximation);
    }

    /**
     * One pass over the cells in the entry's feasible hull. The entry's feasible intervals and its
     * arrival and score segments are sorted and disjoint and all of their boundaries are cuts, so
     * three monotone pointers locate the piece of every cell with a comparison or two per cell.
     */
    private Sweep sweep(ExactPreferencePathProfile entry, Journal journal, long sequence,
            BooleanSupplier cancellationRequested) {
        List<ExactTemporalDomain.Interval> feasible = entry.feasibleDomain().intervals();
        List<ExactAffineSegment> arrival = entry.arrival().segments();
        List<ExactScoreProfile.Segment> score = entry.score().segments();
        double[] feasibleStart = new double[feasible.size()], feasibleEnd = new double[feasible.size()];
        for (int i = 0; i < feasible.size(); i++) {
            feasibleStart[i] = ExactTimeApproximation.of(feasible.get(i).start());
            feasibleEnd[i] = ExactTimeApproximation.of(feasible.get(i).end());
        }
        double[] arrivalStart = new double[arrival.size()], arrivalEnd = new double[arrival.size()];
        for (int i = 0; i < arrival.size(); i++) {
            arrivalStart[i] = ExactTimeApproximation.of(arrival.get(i).interval().start());
            arrivalEnd[i] = ExactTimeApproximation.of(arrival.get(i).interval().end());
        }
        double[] scoreStart = new double[score.size()], scoreEnd = new double[score.size()];
        for (int i = 0; i < score.size(); i++) {
            scoreStart[i] = ExactTimeApproximation.of(score.get(i).interval().start());
            scoreEnd[i] = ExactTimeApproximation.of(score.get(i).interval().end());
        }
        Cut hullStart = new Cut(feasible.getFirst().start());
        Cut hullEnd = new Cut(feasible.getLast().end());
        Map.Entry<Cut, Slot> floor = slots.floorEntry(hullStart);
        NavigableMap<Cut, Slot> tail = floor == null ? slots : slots.tailMap(floor.getKey(), true);
        // monotone pointers: one set for open cells, one for point cells
        int openFeasible = 0, openArrival = 0, openScore = 0;
        int pointFeasible = 0, pointArrival = 0, pointScore = 0;
        int won = 0, scanned = 0;
        long decisions = 0;
        ExactTime coverage = ExactTime.ZERO;
        ExactTime preference = ExactTime.ZERO;

        Iterator<Map.Entry<Cut, Slot>> iterator = tail.entrySet().iterator();
        Map.Entry<Cut, Slot> current = iterator.hasNext() ? iterator.next() : null;
        while (current != null) {
            check(cancellationRequested);
            Cut cut = current.getKey();
            if (cut.compareTo(hullEnd) > 0) break;
            Slot slot = current.getValue();
            Map.Entry<Cut, Slot> next = iterator.hasNext() ? iterator.next() : null;
            scanned++;

            if (slot.pointInDomain) {
                while (pointFeasible < feasible.size()) {
                    int end = order(feasible.get(pointFeasible).end(), feasibleEnd[pointFeasible], cut);
                    if (end < 0 || (end == 0 && !feasible.get(pointFeasible).endInclusive())) pointFeasible++;
                    else break;
                }
                boolean inFeasible = false;
                if (pointFeasible < feasible.size()) {
                    int start = order(feasible.get(pointFeasible).start(), feasibleStart[pointFeasible], cut);
                    inFeasible = start < 0 || (start == 0 && feasible.get(pointFeasible).startInclusive());
                }
                if (inFeasible) {
                    pointArrival = advanceOwned(arrival, arrivalStart, arrivalEnd, pointArrival, cut);
                    pointScore = advanceOwnedScore(score, scoreStart, scoreEnd, pointScore, cut);
                    Winner challenger = challenger(entry, arrival, arrivalStart, score, scoreStart,
                            pointArrival, pointScore, cut);
                    decisions += slot.point == null ? 0 : 1;
                    Decision decision = decide(challenger, slot.point, slot.pointArrival, cut.value);
                    if (decision.challengerWins()) {
                        journal.touch(slot, sequence);
                        slot.point = challenger;
                        slot.pointArrival = decision.challengerArrival();
                        won++;
                    } else if (decision.incumbentArrival() != slot.pointArrival) {
                        journal.touch(slot, sequence);
                        slot.pointArrival = decision.incumbentArrival();
                    }
                }
            }

            if (slot.openInDomain && next != null && cut.compareTo(hullEnd) < 0) {
                while (openFeasible < feasible.size()
                        && order(feasible.get(openFeasible).end(), feasibleEnd[openFeasible], cut) <= 0) openFeasible++;
                boolean inFeasible = openFeasible < feasible.size()
                        && order(feasible.get(openFeasible).start(), feasibleStart[openFeasible], cut) <= 0;
                if (inFeasible) {
                    while (openArrival < arrival.size()
                            && order(arrival.get(openArrival).interval().end(), arrivalEnd[openArrival], cut) <= 0) openArrival++;
                    while (openScore < score.size()
                            && order(score.get(openScore).interval().end(), scoreEnd[openScore], cut) <= 0) openScore++;
                    if (openArrival == arrival.size() || openScore == score.size()
                            || order(arrival.get(openArrival).interval().start(), arrivalStart[openArrival], cut) > 0
                            || order(score.get(openScore).interval().start(), scoreStart[openScore], cut) > 0) {
                        throw new IllegalStateException("feasible entry has no exact value on the cell after " + cut.value);
                    }
                    Winner challenger = new Winner(entry, score.get(openScore).value(), arrival.get(openArrival));
                    Winner incumbent = slot.open;
                    boolean wins;
                    ExactTime challengerArrival = null;
                    ExactTime incumbentArrival = slot.openArrival;
                    if (incumbent == null) {
                        wins = true;
                    } else {
                        decisions++;
                        int byScore = challenger.score().compareTo(incumbent.score());
                        if (byScore != 0) {
                            wins = byScore > 0;
                        } else {
                            ExactTime probe = probe(slot, cut, next.getKey(), journal, sequence);
                            Decision decision = decide(challenger, incumbent, slot.openArrival, probe);
                            wins = decision.challengerWins();
                            challengerArrival = decision.challengerArrival();
                            incumbentArrival = decision.incumbentArrival();
                            if (!wins && incumbentArrival != slot.openArrival) {
                                journal.touch(slot, sequence);
                                slot.openArrival = incumbentArrival;
                            }
                        }
                    }
                    if (wins) {
                        ExactTime length = next.getKey().value.subtract(cut.value);
                        if (incumbent == null) {
                            coverage = coverage.add(length);
                            preference = preference.add(length.multiply(ExactTime.of(challenger.score(), BigInteger.ONE)));
                        } else {
                            preference = preference.add(length.multiply(ExactTime.of(
                                    challenger.score().subtract(incumbent.score()), BigInteger.ONE)));
                        }
                        journal.touch(slot, sequence);
                        slot.open = challenger;
                        slot.openArrival = challengerArrival;
                        won++;
                    }
                }
            }
            current = next;
        }
        return new Sweep(won, scanned, decisions, coverage, preference);
    }

    /** Index of the arrival piece that owns {@code cut}, advancing the monotone pointer. */
    private static int advanceOwned(List<ExactAffineSegment> pieces, double[] starts, double[] ends, int from, Cut cut) {
        int index = from;
        while (index < pieces.size()) {
            int end = order(pieces.get(index).interval().end(), ends[index], cut);
            if (end < 0 || (end == 0 && !pieces.get(index).interval().endInclusive())) index++;
            else break;
        }
        return index;
    }

    private static int advanceOwnedScore(List<ExactScoreProfile.Segment> pieces, double[] starts, double[] ends,
            int from, Cut cut) {
        int index = from;
        while (index < pieces.size()) {
            int end = order(pieces.get(index).interval().end(), ends[index], cut);
            if (end < 0 || (end == 0 && !pieces.get(index).interval().endInclusive())) index++;
            else break;
        }
        return index;
    }

    /**
     * The entry's value at {@code cut}. The pointers were advanced past every piece that ends before
     * the cut (or ends at it without owning it), so the piece at the pointer owns the cut when it
     * starts before it, or at it with an owned start.
     */
    private static Winner challenger(ExactPreferencePathProfile entry, List<ExactAffineSegment> arrival,
            double[] arrivalStart, List<ExactScoreProfile.Segment> score, double[] scoreStart,
            int arrivalIndex, int scoreIndex, Cut cut) {
        if (arrivalIndex >= arrival.size() || scoreIndex >= score.size()
                || !ownsStart(arrival.get(arrivalIndex).interval(), arrivalStart[arrivalIndex], cut)
                || !ownsStart(score.get(scoreIndex).interval(), scoreStart[scoreIndex], cut)) {
            throw new IllegalStateException("feasible entry has no exact value at " + cut.value);
        }
        return new Winner(entry, score.get(scoreIndex).value(), arrival.get(arrivalIndex));
    }

    private static boolean ownsStart(ExactTemporalDomain.Interval interval, double approximateStart, Cut cut) {
        int start = order(interval.start(), approximateStart, cut);
        return start < 0 || (start == 0 && interval.startInclusive());
    }

    private ExactTime probe(Slot slot, Cut cut, Cut next, Journal journal, long sequence) {
        if (slot.openProbe == null) {
            journal.touch(slot, sequence);
            slot.openProbe = midpoint(cut.value, next.value);
        }
        return slot.openProbe;
    }

    /** Outcome of one comparison, with any arrivals it had to evaluate. */
    private record Decision(boolean challengerWins, ExactTime challengerArrival, ExactTime incumbentArrival) { }

    /**
     * The shared winner order decides on score first, so arrivals are only evaluated on a score tie;
     * the outcome equals a full comparison. Identical arrival functions need no evaluation.
     */
    private Decision decide(Winner challenger, Winner incumbent, ExactTime incumbentArrival, ExactTime at) {
        if (incumbent == null) return new Decision(true, null, null);
        int score = challenger.score().compareTo(incumbent.score());
        if (score != 0) return new Decision(score > 0, null, incumbentArrival);
        ExactAffineSegment c = challenger.affine(), i = incumbent.affine();
        if (c.slope().equals(i.slope()) && c.intercept().equals(i.intercept())) {
            boolean wins = ExactPreferenceEnvelope.compareWinners(
                    challenger.score(), ExactTime.ZERO, challenger.profile().path(),
                    incumbent.score(), ExactTime.ZERO, incumbent.profile().path()) > 0;
            return new Decision(wins, null, incumbentArrival);
        }
        ExactTime challengerArrival = c.valueAt(at);
        ExactTime known = incumbentArrival != null ? incumbentArrival : i.valueAt(at);
        boolean wins = ExactPreferenceEnvelope.compareWinners(
                challenger.score(), challengerArrival, challenger.profile().path(),
                incumbent.score(), known, incumbent.profile().path()) > 0;
        return new Decision(wins, challengerArrival, known);
    }

    /** Immutable, validated envelope over every entry added so far. */
    @Override public ExactPreferenceEnvelope snapshot() {
        List<ExactPreferenceEnvelope.Cell> cells = new ArrayList<>();
        Iterator<Map.Entry<Cut, Slot>> iterator = slots.entrySet().iterator();
        Map.Entry<Cut, Slot> current = iterator.hasNext() ? iterator.next() : null;
        while (current != null) {
            Map.Entry<Cut, Slot> next = iterator.hasNext() ? iterator.next() : null;
            Slot slot = current.getValue();
            ExactTime cut = current.getKey().value;
            if (slot.pointInDomain) cells.add(cell(ExactTemporalDomain.Interval.singleton(cut), slot.point));
            if (slot.openInDomain && next != null) {
                cells.add(cell(new ExactTemporalDomain.Interval(cut, next.getKey().value, false, false), slot.open));
            }
            current = next;
        }
        return ExactPreferenceEnvelope.fromIncremental(requested, cells, comparisons);
    }

    /** Merges equal winners while walking the cells, so only the canonical cells are ever built and validated. */
    @Override public ExactPreferenceEnvelope canonicalSnapshot() {
        List<ExactPreferenceEnvelope.Cell> cells = new ArrayList<>();
        Winner runWinner = null;
        ExactTime runStart = null, runEnd = null;
        boolean runStartInclusive = false, runEndInclusive = false;
        Iterator<Map.Entry<Cut, Slot>> iterator = slots.entrySet().iterator();
        Map.Entry<Cut, Slot> current = iterator.hasNext() ? iterator.next() : null;
        while (current != null) {
            Map.Entry<Cut, Slot> next = iterator.hasNext() ? iterator.next() : null;
            Slot slot = current.getValue();
            ExactTime cut = current.getKey().value;
            // the cells of this cut in order: its point, then the open cell up to the next cut
            for (int part = 0; part < 2; part++) {
                boolean present = part == 0 ? slot.pointInDomain : slot.openInDomain && next != null;
                if (!present) continue;
                Winner winner = part == 0 ? slot.point : slot.open;
                ExactTime start = cut;
                ExactTime end = part == 0 ? cut : next.getKey().value;
                boolean startInclusive = part == 0, endInclusive = part == 0;
                if (runStart != null && sameWinner(runWinner, winner)
                        && runEnd.equals(start) && (runEndInclusive || startInclusive)) {
                    runEnd = end;
                    runEndInclusive = endInclusive;
                } else {
                    if (runStart != null) {
                        cells.add(cell(new ExactTemporalDomain.Interval(runStart, runEnd, runStartInclusive,
                                runEndInclusive), runWinner));
                    }
                    runWinner = winner;
                    runStart = start;
                    runEnd = end;
                    runStartInclusive = startInclusive;
                    runEndInclusive = endInclusive;
                }
            }
            current = next;
        }
        if (runStart != null) {
            cells.add(cell(new ExactTemporalDomain.Interval(runStart, runEnd, runStartInclusive, runEndInclusive),
                    runWinner));
        }
        return ExactPreferenceEnvelope.fromIncremental(requested, cells, comparisons);
    }

    private static boolean sameWinner(Winner left, Winner right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.score().equals(right.score()) && left.affine().slope().equals(right.affine().slope())
                && left.affine().intercept().equals(right.affine().intercept())
                && left.profile().path().equals(right.profile().path());
    }

    private static ExactPreferenceEnvelope.Cell cell(ExactTemporalDomain.Interval interval, Winner winner) {
        return winner == null ? new ExactPreferenceEnvelope.Cell(interval, null, null, null, null)
                : new ExactPreferenceEnvelope.Cell(interval, winner.profile().path(), winner.score(),
                        winner.affine().slope(), winner.affine().intercept());
    }

    private static void addSegmentCuts(TreeSet<Cut> cuts, ExactPreferencePathProfile entry,
            BooleanSupplier cancellationRequested) {
        for (ExactAffineSegment segment : entry.arrival().segments()) {
            check(cancellationRequested);
            cuts.add(new Cut(segment.interval().start()));
            cuts.add(new Cut(segment.interval().end()));
        }
        for (ExactScoreProfile.Segment segment : entry.score().segments()) {
            check(cancellationRequested);
            cuts.add(new Cut(segment.interval().start()));
            cuts.add(new Cut(segment.interval().end()));
        }
    }

    private static void addDomainCuts(TreeSet<Cut> cuts, ExactTemporalDomain domain) {
        for (ExactTemporalDomain.Interval interval : domain.intervals()) {
            cuts.add(new Cut(interval.start()));
            cuts.add(new Cut(interval.end()));
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
