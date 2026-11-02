package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactTimeApproximation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static edu.ipcmax.baselines.exact.ExactTimeApproximation.compare;

/**
 * The exact preference envelope kept in canonical form: cuts exist only where the winner function changes (and at
 * the requested domain's own boundaries), so after every add {@link #snapshot()} is
 * {@code ExactPreferenceEnvelope.overRetained(...).canonicalized()} over the entries added so far, for any add
 * order. Where the refined envelopes ({@link IncrementalExactPreferenceEnvelope},
 * {@link JournaledExactPreferenceEnvelope}) cut at every boundary of every entry and at every pairwise arrival
 * crossing, this one does exact work only where a new entry competes with an incumbent:
 *
 * <ul>
 *   <li>the entry's boundaries are inserted as temporary cuts so that every cell lies inside one of its pieces;</li>
 *   <li>a cell is decided by scores, which are small integers; only on a score tie are arrival times compared, and
 *       then only that pair's exact crossing, if it lies inside the cell, splits it;</li>
 *   <li>afterwards every cut whose two sides and point have the same winner is removed, so the structure stays
 *       minimal and the cost of a later add depends on the number of winner changes, not on the number of pieces
 *       ever seen;</li>
 *   <li>the covered measure and integrated preference an add gained are recorded as ranges and computed only when
 *       asked for.</li>
 * </ul>
 *
 * <p>An add is atomic: cells and cuts changed before a cancellation are restored from an undo journal. The final
 * merge runs after the last cancellation point. Not thread-safe; one coordinator owns it.</p>
 */
public final class CanonicalExactPreferenceEnvelope implements ExactEnvelopeBuilder {
    private record Winner(ExactPreferencePathProfile profile, BigInteger score, ExactAffineSegment affine) {
        /** Same route, score and exact arrival function: the value the envelope publishes. */
        boolean sameValue(Winner other) {
            if (this == other) return true;
            if (other == null) return false;
            return score.equals(other.score) && affine.slope().equals(other.affine.slope())
                    && affine.intercept().equals(other.affine.intercept())
                    && profile.path().equals(other.profile.path());
        }
    }

    private static boolean same(Winner left, Winner right) {
        return left == null ? right == null : left.sameValue(right);
    }

    /** The point cell at one cut and the open cell from it to the next cut. */
    private static final class Slot {
        long born;
        boolean pointInDomain;
        Winner point;
        boolean openInDomain;
        Winner open;

        Slot(long born, boolean pointInDomain, Winner point, boolean openInDomain, Winner open) {
            this.born = born;
            this.pointInDomain = pointInDomain;
            this.point = point;
            this.openInDomain = openInDomain;
            this.open = open;
        }

        Slot copy() { return new Slot(born, pointInDomain, point, openInDomain, open); }

        void restore(Slot from) {
            pointInDomain = from.pointInDomain;
            point = from.point;
            openInDomain = from.openInDomain;
            open = from.open;
        }
    }

    private static final class Journal {
        final IdentityHashMap<Slot, Slot> before = new IdentityHashMap<>();
        final List<Cut> inserted = new ArrayList<>();

        void touch(Slot slot, long sequence) {
            if (slot.born != sequence && !before.containsKey(slot)) before.put(slot, slot.copy());
        }
    }

    private final ExactTemporalDomain requested;
    private final TreeMap<Cut, Slot> slots = new TreeMap<>();
    private int usable;
    private long comparisons;
    private long version;
    private long addSequence;

    public CanonicalExactPreferenceEnvelope(ExactTemporalDomain requested) {
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
    @Override public int entryCount() { return usable; }
    @Override public long comparisons() { return comparisons; }
    @Override public int cutCount() { return slots.size(); }

    /** Number of cells of {@link #snapshot()}: runs of equal winners over the point and open cells. */
    @Override public int cellCount() {
        int runs = 0;
        Winner previous = null;
        boolean havePrevious = false;
        Iterator<Map.Entry<Cut, Slot>> iterator = slots.entrySet().iterator();
        Map.Entry<Cut, Slot> current = iterator.hasNext() ? iterator.next() : null;
        boolean previousEndsOwned = false;
        ExactTime previousEnd = null;
        while (current != null) {
            Map.Entry<Cut, Slot> next = iterator.hasNext() ? iterator.next() : null;
            Slot slot = current.getValue();
            ExactTime cut = current.getKey().value;
            for (int part = 0; part < 2; part++) {
                boolean present = part == 0 ? slot.pointInDomain : slot.openInDomain && next != null;
                if (!present) continue;
                Winner winner = part == 0 ? slot.point : slot.open;
                boolean startOwned = part == 0;
                boolean endOwned = part == 0;
                ExactTime end = part == 0 ? cut : next.getKey().value;
                if (havePrevious && same(previous, winner) && previousEnd.equals(cut)
                        && (previousEndsOwned || startOwned)) {
                    // continues the current run
                } else {
                    runs++;
                }
                previous = winner;
                havePrevious = true;
                previousEnd = end;
                previousEndsOwned = endOwned;
            }
            current = next;
        }
        return runs;
    }

    @Override
    public IncrementalExactPreferenceEnvelope.Update add(ExactPreferencePathProfile entry,
            BooleanSupplier cancellationRequested) {
        Objects.requireNonNull(entry, "entry");
        // An entry with no feasible departure never wins anywhere: it cannot change the winner function.
        if (slots.isEmpty() || entry.feasibleDomain().isEmpty()) {
            return new IncrementalExactPreferenceEnvelope.Update(false, 0, 0, EnvelopeGain.NONE, version,
                    IncrementalExactPreferenceEnvelope.Stats.NONE);
        }
        ExactArithmeticDiagnostics.increment("exact_incremental_envelope_adds");
        long tStart = System.nanoTime();
        int cutsBefore = slots.size();
        long sequence = ++addSequence;
        TreeSet<Cut> fresh = new TreeSet<>();
        for (ExactTemporalDomain.Interval interval : entry.feasibleDomain().intervals()) {
            fresh.add(new Cut(interval.start()));
            fresh.add(new Cut(interval.end()));
        }
        for (ExactAffineSegment segment : entry.arrival().segments()) {
            check(cancellationRequested);
            fresh.add(new Cut(segment.interval().start()));
            fresh.add(new Cut(segment.interval().end()));
        }
        for (ExactScoreProfile.Segment segment : entry.score().segments()) {
            check(cancellationRequested);
            fresh.add(new Cut(segment.interval().start()));
            fresh.add(new Cut(segment.interval().end()));
        }
        long tCuts = System.nanoTime();
        Journal journal = new Journal();
        int inserted;
        Sweep sweep;
        long tInserted;
        try {
            inserted = insertCuts(fresh, new Cut(entry.feasibleDomain().intervals().getFirst().start()),
                    new Cut(entry.feasibleDomain().intervals().getLast().end()), journal, sequence, cancellationRequested);
            tInserted = System.nanoTime();
            sweep = sweep(entry, journal, sequence, cancellationRequested);
            check(cancellationRequested);
        } catch (RuntimeException | Error failure) {
            rollback(journal);
            throw failure;
        }
        long tSwept = System.nanoTime();
        // Committed: drop the temporary cuts that no longer separate two winners.
        int merged = mergeRange(new Cut(entry.feasibleDomain().intervals().getFirst().start()),
                new Cut(entry.feasibleDomain().intervals().getLast().end()));
        usable++;
        comparisons += sweep.comparisons;
        if (sweep.won > 0) version++;
        ExactArithmeticDiagnostics.add("exact_incremental_envelope_cuts_inserted", inserted);
        ExactArithmeticDiagnostics.add("exact_canonical_envelope_cuts_merged", merged);
        ExactArithmeticDiagnostics.add("exact_canonical_envelope_tie_crossings", sweep.crossings);
        long tEnd = System.nanoTime();
        var stats = new IncrementalExactPreferenceEnvelope.Stats(usable - 1, cutsBefore, slots.size(), 0, sweep.crossings,
                fresh.size(), sweep.scanned, sweep.comparisons, tCuts - tStart, 0, 0, tInserted - tCuts,
                tSwept - tInserted, tEnd - tStart);
        return new IncrementalExactPreferenceEnvelope.Update(sweep.won > 0, inserted, sweep.won,
                EnvelopeGain.lazy(sweep.raised), version, stats);
    }

    private void rollback(Journal journal) {
        for (Map.Entry<Slot, Slot> saved : journal.before.entrySet()) saved.getKey().restore(saved.getValue());
        for (Cut cut : journal.inserted) slots.remove(cut);
    }

    /**
     * Splits the cells at the entry's boundaries inside its feasible hull: the entry competes nowhere else, and
     * a cut outside the hull would never be visited again by the merge that removes the temporary ones.
     */
    private int insertCuts(TreeSet<Cut> fresh, Cut hullStart, Cut hullEnd, Journal journal, long sequence,
            BooleanSupplier cancellationRequested) {
        Cut first = slots.firstKey();
        Cut last = slots.lastKey();
        int inserted = 0;
        for (Cut cut : fresh) {
            check(cancellationRequested);
            if (cut.compareTo(first) < 0 || cut.compareTo(last) > 0) continue;
            if (cut.compareTo(hullStart) < 0 || cut.compareTo(hullEnd) > 0) continue;
            if (split(cut, journal, sequence)) inserted++;
        }
        return inserted;
    }

    /**
     * Makes {@code cut} a cut by splitting the cell around it: both halves and the new point inherit the winner of
     * the cell, because every requested boundary is a cut and a cell is wholly inside or outside the requested domain.
     */
    private boolean split(Cut cut, Journal journal, long sequence) {
        Map.Entry<Cut, Slot> lower = slots.floorEntry(cut);
        if (lower.getKey().compareTo(cut) == 0) return false;
        Slot parent = lower.getValue();
        journal.touch(parent, sequence);
        boolean inside = parent.openInDomain;
        Winner inherited = inside ? parent.open : null;
        slots.put(cut, new Slot(sequence, inside, inherited, inside, inherited));
        journal.inserted.add(cut);
        return true;
    }

    private record Sweep(int won, int scanned, long comparisons, long crossings, List<EnvelopeGain.Range> raised) { }

    private static int order(ExactTime boundary, double approximation, Cut cut) {
        return compare(boundary, approximation, cut.value, cut.approximation);
    }

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
        // The cells to visit, fixed before any cell is split during the pass.
        List<Map.Entry<Cut, Slot>> cells = new ArrayList<>();
        for (Iterator<Map.Entry<Cut, Slot>> it = (floor == null ? slots : slots.tailMap(floor.getKey(), true))
                .entrySet().iterator(); it.hasNext();) {
            Map.Entry<Cut, Slot> candidate = it.next();
            cells.add(candidate);
            if (candidate.getKey().compareTo(hullEnd) > 0) break;
        }
        int openFeasible = 0, openArrival = 0, openScore = 0;
        int pointFeasible = 0, pointArrival = 0, pointScore = 0;
        int won = 0, scanned = 0;
        long decisions = 0, crossings = 0;
        List<EnvelopeGain.Range> raised = new ArrayList<>();

        for (int index = 0; index < cells.size(); index++) {
            check(cancellationRequested);
            Cut cut = cells.get(index).getKey();
            if (cut.compareTo(hullEnd) > 0) break;
            Slot slot = cells.get(index).getValue();
            Cut next = index + 1 < cells.size() ? cells.get(index + 1).getKey() : null;
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
                    pointArrival = advanceOwned(arrival, arrivalEnd, pointArrival, cut);
                    pointScore = advanceOwnedScore(score, scoreEnd, pointScore, cut);
                    Winner challenger = challenger(entry, arrival, arrivalStart, score, scoreStart,
                            pointArrival, pointScore, cut);
                    decisions += slot.point == null ? 0 : 1;
                    if (beatsAtPoint(challenger, slot.point, cut.value)) {
                        journal.touch(slot, sequence);
                        // a point has measure zero: it contributes nothing to a gain
                        slot.point = challenger;
                        won++;
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
                    if (incumbent == null) {
                        journal.touch(slot, sequence);
                        slot.open = challenger;
                        raised.add(new EnvelopeGain.Range(cut.value, next.value, challenger.score(), true));
                        won++;
                    } else {
                        decisions++;
                        int byScore = challenger.score().compareTo(incumbent.score());
                        if (byScore > 0) {
                            journal.touch(slot, sequence);
                            slot.open = challenger;
                            raised.add(new EnvelopeGain.Range(cut.value, next.value,
                                    challenger.score().subtract(incumbent.score()), false));
                            won++;
                        } else if (byScore == 0 && !incumbent.sameValue(challenger)) {
                            int[] tied = decideTie(slot, cut, next, challenger, incumbent, journal, sequence);
                            won += tied[0];
                            crossings += tied[1];
                        }
                    }
                }
            }
        }
        return new Sweep(won, scanned, decisions, crossings, raised);
    }

    /**
     * An equal-score contest on the open cell (cut, next): the earlier exact arrival wins. Identical arrival
     * functions are decided by the route order. Different ones cross at one exact time; if it lies strictly inside the
     * cell the cell is split there and the crossing point goes to the route order.
     *
     * @return {cells won, crossings computed}
     */
    private int[] decideTie(Slot slot, Cut cut, Cut next, Winner challenger, Winner incumbent, Journal journal,
            long sequence) {
        ExactAffineSegment c = challenger.affine(), i = incumbent.affine();
        if (c.slope().equals(i.slope())) {
            // parallel arrivals: the difference is the constant intercept difference
            int byArrival = i.intercept().compareTo(c.intercept());
            boolean wins = byArrival > 0 || (byArrival == 0 && routeOrderWins(challenger, incumbent));
            if (wins) {
                journal.touch(slot, sequence);
                slot.open = challenger;
                return new int[] {1, 0};
            }
            return new int[] {0, 0};
        }
        // D(t) = arrival_challenger(t) - arrival_incumbent(t) = slopeDifference * (t - crossing)
        ExactTime slopeDifference = c.slope().subtract(i.slope());
        ExactTime crossing = i.intercept().subtract(c.intercept()).divide(slopeDifference);
        Cut crossingCut = new Cut(crossing);
        boolean challengerFasterAfterCrossing = slopeDifference.signum() < 0;
        if (crossingCut.compareTo(cut) > 0 && crossingCut.compareTo(next) < 0) {
            int won = 0;
            // the cell is split at the crossing: left of it D < 0 exactly when the slope difference is positive
            boolean leftWins = !challengerFasterAfterCrossing;
            boolean pointWins = routeOrderWins(challenger, incumbent);
            split(crossingCut, journal, sequence);
            Slot created = slots.get(crossingCut);
            journal.touch(slot, sequence);
            if (leftWins) {
                slot.open = challenger;
                won++;
            }
            if (pointWins) {
                created.point = challenger;
                won++;
            }
            if (challengerFasterAfterCrossing) {
                created.open = challenger;
                won++;
            }
            return new int[] {won, 1};
        }
        // the crossing is not inside the cell: one side of it covers the whole open cell
        boolean entirelyAfter = crossingCut.compareTo(cut) <= 0;
        boolean wins = entirelyAfter ? challengerFasterAfterCrossing : !challengerFasterAfterCrossing;
        if (wins) {
            journal.touch(slot, sequence);
            slot.open = challenger;
            return new int[] {1, 1};
        }
        return new int[] {0, 1};
    }

    /** The point contest at {@code at}: higher score, then earlier arrival at that exact time, then the route order. */
    private boolean beatsAtPoint(Winner challenger, Winner incumbent, ExactTime at) {
        if (incumbent == null) return true;
        int byScore = challenger.score().compareTo(incumbent.score());
        if (byScore != 0) return byScore > 0;
        ExactAffineSegment c = challenger.affine(), i = incumbent.affine();
        if (c.slope().equals(i.slope()) && c.intercept().equals(i.intercept())) return routeOrderWins(challenger, incumbent);
        return ExactPreferenceEnvelope.compareWinners(challenger.score(), arrivalAt(c, at), challenger.profile().path(),
                incumbent.score(), arrivalAt(i, at), incumbent.profile().path()) > 0;
    }

    /**
     * The winner's arrival function at {@code at}. A merged winner stands for several equal pieces, so the piece it
     * happens to hold may not own {@code at}; the formula is what every one of them has in common.
     */
    private static ExactTime arrivalAt(ExactAffineSegment affine, ExactTime at) {
        return affine.slope().multiply(at).add(affine.intercept());
    }

    /** Equal score and equal arrival: fewer edges, then lexicographically smaller arc ids. */
    private static boolean routeOrderWins(Winner challenger, Winner incumbent) {
        return ExactPreferenceEnvelope.compareWinners(challenger.score(), ExactTime.ZERO, challenger.profile().path(),
                incumbent.score(), ExactTime.ZERO, incumbent.profile().path()) > 0;
    }

    private static int advanceOwned(List<ExactAffineSegment> pieces, double[] ends, int from, Cut cut) {
        int index = from;
        while (index < pieces.size()) {
            int end = order(pieces.get(index).interval().end(), ends[index], cut);
            if (end < 0 || (end == 0 && !pieces.get(index).interval().endInclusive())) index++;
            else break;
        }
        return index;
    }

    private static int advanceOwnedScore(List<ExactScoreProfile.Segment> pieces, double[] ends, int from, Cut cut) {
        int index = from;
        while (index < pieces.size()) {
            int end = order(pieces.get(index).interval().end(), ends[index], cut);
            if (end < 0 || (end == 0 && !pieces.get(index).interval().endInclusive())) index++;
            else break;
        }
        return index;
    }

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

    /**
     * Removes every cut in [from, to] (and the cut before it) whose point and both neighbouring open cells have the
     * same winner. Removing one cut never makes another removable that was not, except through the predecessor,
     * which the walk keeps as the reference.
     */
    private int mergeRange(Cut from, Cut to) {
        Map.Entry<Cut, Slot> start = slots.lowerEntry(from);
        if (start == null) start = slots.firstEntry();
        List<Cut> removable = new ArrayList<>();
        Slot previous = start.getValue();
        Cut lastKey = slots.lastKey();
        boolean beyond = false;
        for (Map.Entry<Cut, Slot> entry : slots.tailMap(start.getKey(), false).entrySet()) {
            Cut cut = entry.getKey();
            Slot slot = entry.getValue();
            boolean hasNext = cut != lastKey;
            if (previous.openInDomain && slot.pointInDomain && slot.openInDomain && hasNext
                    && same(previous.open, slot.point) && same(slot.point, slot.open)) {
                removable.add(cut);
            } else {
                previous = slot;
            }
            if (beyond) break;
            if (cut.compareTo(to) > 0) beyond = true;
        }
        for (Cut cut : removable) slots.remove(cut);
        return removable.size();
    }

    @Override public ExactPreferenceEnvelope snapshot() { return canonicalSnapshot(); }

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
            for (int part = 0; part < 2; part++) {
                boolean present = part == 0 ? slot.pointInDomain : slot.openInDomain && next != null;
                if (!present) continue;
                Winner winner = part == 0 ? slot.point : slot.open;
                ExactTime start = cut;
                ExactTime end = part == 0 ? cut : next.getKey().value;
                boolean startInclusive = part == 0, endInclusive = part == 0;
                if (runStart != null && same(runWinner, winner) && runEnd.equals(start)
                        && (runEndInclusive || startInclusive)) {
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

    private static ExactPreferenceEnvelope.Cell cell(ExactTemporalDomain.Interval interval, Winner winner) {
        return winner == null ? new ExactPreferenceEnvelope.Cell(interval, null, null, null, null)
                : new ExactPreferenceEnvelope.Cell(interval, winner.profile().path(), winner.score(),
                        winner.affine().slope(), winner.affine().intercept());
    }

    private static ExactTime midpoint(ExactTime left, ExactTime right) {
        return left.add(right).divide(ExactTime.of(2));
    }

    private static void check(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("canonical exact envelope update cancelled");
        }
    }
}
