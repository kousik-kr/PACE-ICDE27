package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactScoreProfile;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Incremental breakpoint merging for approximate iSCOPE partial functions. */
final class ApproximatePartialFunctions {
    record Result(
            ExactArrivalProfile arrival,
            ExactScoreProfile score,
            long incomingBreakpoints,
            long retainedBreakpoints,
            long mergedBreakpoints,
            long rouletteDraws,
            long scoreReplacements,
            long scoreRetentions,
            long peakBufferedMergeState,
            ExactTime maximumArrivalError,
            long piecesBefore,
            long piecesAfter,
            long temporalCompositionNanos,
            long temporalCompositionOperationCount) { }

    /** Event shape used by the independent sequence-level tests. */
    record BreakpointEvent(
            ExactTime time,
            ExactTime arrival,
            BigInteger scoreOnRight,
            BigInteger scoreAtPoint,
            boolean structural) {
        BreakpointEvent {
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(scoreAtPoint, "scoreAtPoint");
        }
    }

    private record MergeState(
            BreakpointEvent point, BigInteger representativeScore, boolean representativeUpdated) { }

    private ApproximatePartialFunctions() { }

    /**
     * Builds the approximate composition directly from the input profiles.
     * It enumerates one prefix/edge segment pair at a time and immediately
     * feeds its ordered events to the merger. The full composed arrival and
     * score profiles are never constructed.
     */
    static Result composeAndMerge(
            ExactArrivalProfile prefixArrival,
            ExactArrivalProfile edgeArrival,
            ExactScoreProfile prefixScore,
            ExactScoreProfile edgeScore,
            ExactTemporalDomain support,
            ExactTime threshold,
            SplittableRandom random,
            BooleanSupplier stopRequested) {
        Objects.requireNonNull(random, "random");
        return composeAndMerge(prefixArrival, edgeArrival, prefixScore, edgeScore,
                support, threshold, () -> random.nextInt(101), stopRequested);
    }

    static Result composeAndMerge(
            ExactArrivalProfile prefixArrival,
            ExactArrivalProfile edgeArrival,
            ExactScoreProfile prefixScore,
            ExactScoreProfile edgeScore,
            ExactTemporalDomain support,
            ExactTime threshold,
            IntSupplier randomInclusiveZeroToOneHundred,
            BooleanSupplier stopRequested) {
        Objects.requireNonNull(prefixArrival, "prefixArrival");
        Objects.requireNonNull(edgeArrival, "edgeArrival");
        Objects.requireNonNull(prefixScore, "prefixScore");
        Objects.requireNonNull(edgeScore, "edgeScore");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(randomInclusiveZeroToOneHundred, "randomInclusiveZeroToOneHundred");
        if (threshold.signum() <= 0) throw new IllegalArgumentException("Opt2 threshold must be positive");

        Builder builder = new Builder(support, threshold, randomInclusiveZeroToOneHundred, stopRequested);
        long inputPieces = prefixArrival.pieceCount() + edgeArrival.pieceCount()
                + prefixScore.pieceCount() + edgeScore.pieceCount();
        long compositionNanos = 0;
        long compositionOperations = 0;
        for (ExactTemporalDomain.Interval component : support.intervals()) {
            check(stopRequested);
            CompositionEventIterator events = new CompositionEventIterator(
                    prefixArrival, edgeArrival, prefixScore, edgeScore, component, stopRequested);
            builder.consumeComponent(component, uniqueByTime(events, stopRequested));
            compositionNanos += events.temporalCompositionNanos();
            compositionOperations += events.temporalCompositionOperationCount();
        }
        return builder.finish(inputPieces, compositionNanos, compositionOperations);
    }

    /** Streaming sequence entry point used to test insertion decisions independently. */
    static Result mergeEvents(
            ExactTemporalDomain support,
            ExactTime threshold,
            Iterable<BreakpointEvent> events,
            IntSupplier randomInclusiveZeroToOneHundred,
            BooleanSupplier stopRequested) {
        Objects.requireNonNull(events, "events");
        Builder builder = new Builder(support, threshold,
                randomInclusiveZeroToOneHundred, stopRequested);
        List<BreakpointEvent> input = new ArrayList<>();
        events.forEach(input::add);
        for (ExactTemporalDomain.Interval component : support.intervals()) {
            List<BreakpointEvent> componentEvents = input.stream()
                    .filter(event -> component.containsClosure(event.time())).toList();
            builder.consumeComponent(component,
                    uniqueByTime(componentEvents.iterator(), stopRequested));
        }
        return builder.finish(0, 0, 0);
    }

    private static Iterator<BreakpointEvent> uniqueByTime(
            Iterator<BreakpointEvent> input, BooleanSupplier stopRequested) {
        return new Iterator<>() {
            private BreakpointEvent pending = input.hasNext() ? input.next() : null;
            @Override public boolean hasNext() { return pending != null; }
            @Override public BreakpointEvent next() {
                if (pending == null) throw new java.util.NoSuchElementException();
                check(stopRequested);
                BreakpointEvent selected = pending;
                pending = null;
                while (input.hasNext()) {
                    BreakpointEvent candidate = input.next();
                    int order = candidate.time().compareTo(selected.time());
                    if (order < 0) throw new IllegalArgumentException("breakpoint events are not time ordered");
                    if (order > 0) {
                        pending = candidate;
                        break;
                    }
                    // Prefer the right-owned function event at coincident cuts.
                    if (candidate.scoreOnRight() != null) selected = candidate;
                    else if (selected.scoreOnRight() == null && candidate.structural()) selected = candidate;
                    if (candidate.structural() && !selected.structural()) {
                        selected = new BreakpointEvent(selected.time(), selected.arrival(),
                                selected.scoreOnRight(), selected.scoreAtPoint(), true);
                    }
                }
                return selected;
            }
        };
    }

    /**
     * Ordered lazy event stream for a composed function. Only the score cuts
     * for the current input-segment pair are buffered; the complete output
     * breakpoint sequence is never retained.
     */
    private static final class CompositionEventIterator implements Iterator<BreakpointEvent> {
        private final ExactArrivalProfile prefixArrival;
        private final ExactArrivalProfile edgeArrival;
        private final ExactScoreProfile prefixScore;
        private final ExactScoreProfile edgeScore;
        private final ExactTemporalDomain.Interval component;
        private final BooleanSupplier stopRequested;
        private int innerIndex;
        private int outerIndex;
        private Iterator<BreakpointEvent> current = List.<BreakpointEvent>of().iterator();
        private BreakpointEvent next;
        private long temporalCompositionNanos;
        private long temporalCompositionOperationCount;

        CompositionEventIterator(ExactArrivalProfile prefixArrival,
                ExactArrivalProfile edgeArrival,
                ExactScoreProfile prefixScore,
                ExactScoreProfile edgeScore,
                ExactTemporalDomain.Interval component,
                BooleanSupplier stopRequested) {
            this.prefixArrival = prefixArrival;
            this.edgeArrival = edgeArrival;
            this.prefixScore = prefixScore;
            this.edgeScore = edgeScore;
            this.component = component;
            this.stopRequested = stopRequested;
            advance();
        }

        @Override public boolean hasNext() { return next != null; }
        @Override public BreakpointEvent next() {
            if (next == null) throw new java.util.NoSuchElementException();
            BreakpointEvent result = next;
            advance();
            return result;
        }

        private void advance() {
            long started = System.nanoTime();
            try {
                advanceEvents();
            } finally {
                temporalCompositionNanos += System.nanoTime() - started;
            }
        }

        private void advanceEvents() {
            next = null;
            while (true) {
                check(stopRequested);
                if (current.hasNext()) {
                    next = current.next();
                    return;
                }
                List<ExactAffineSegment> innerPieces = prefixArrival.segments();
                List<ExactAffineSegment> outerPieces = edgeArrival.segments();
                if (innerIndex >= innerPieces.size()) return;
                ExactAffineSegment inner = innerPieces.get(innerIndex);
                if (outerIndex >= outerPieces.size()) {
                    innerIndex++;
                    outerIndex = 0;
                    continue;
                }
                ExactAffineSegment outer = outerPieces.get(outerIndex++);
                ExactTemporalDomain.Interval preimage = preimage(inner, outer.interval());
                if (preimage == null) continue;
                ExactTemporalDomain.Interval overlap = inner.interval().intersection(preimage);
                if (overlap == null) continue;
                overlap = overlap.intersection(component);
                if (overlap == null) continue;
                current = eventsForPair(inner, outer, overlap).iterator();
                temporalCompositionOperationCount++;
            }
        }

        long temporalCompositionNanos() { return temporalCompositionNanos; }
        long temporalCompositionOperationCount() { return temporalCompositionOperationCount; }

        private List<BreakpointEvent> eventsForPair(
                ExactAffineSegment inner,
                ExactAffineSegment outer,
                ExactTemporalDomain.Interval overlap) {
            TreeSet<ExactTime> cuts = new TreeSet<>();
            cuts.add(overlap.start());
            cuts.add(overlap.end());
            for (ExactScoreProfile.Segment segment : prefixScore.segments()) {
                check(stopRequested);
                addOnClosure(cuts, segment.interval(), overlap);
            }
            for (ExactScoreProfile.Segment segment : edgeScore.segments()) {
                check(stopRequested);
                addPreimageCut(cuts, inner, segment.interval().start(), overlap);
                addPreimageCut(cuts, inner, segment.interval().end(), overlap);
            }
            List<ExactTime> ordered = List.copyOf(cuts);
            ExactAffineSegment composed = inner.then(outer, overlap);
            List<BreakpointEvent> events = new ArrayList<>(Math.max(1, ordered.size()));
            if (ordered.size() == 1) {
                ExactTime at = ordered.getFirst();
                if (component.contains(at) && prefixScore.domain().contains(at)) {
                    ExactTime mapped = inner.valueAtClosure(at);
                    if (edgeScore.domain().contains(mapped)) {
                        BigInteger score = prefixScore.valueAt(at, stopRequested)
                                .add(edgeScore.valueAt(mapped, stopRequested));
                        events.add(new BreakpointEvent(at, composed.valueAtClosure(at), score, score,
                                isStructural(at)));
                    }
                }
                return events;
            }
            for (int index = 0; index + 1 < ordered.size(); index++) {
                check(stopRequested);
                ExactTime start = ordered.get(index);
                ExactTime end = ordered.get(index + 1);
                if (start.equals(end)) continue;
                ExactTime probe = start.add(end).divide(ExactTime.of(2));
                if (!overlap.contains(probe) || !prefixScore.domain().contains(probe)) continue;
                ExactTime mapped = inner.valueAt(probe);
                if (!edgeScore.domain().contains(mapped)) continue;
                BigInteger score = prefixScore.valueAt(probe, stopRequested)
                        .add(edgeScore.valueAt(mapped, stopRequested));
                events.add(new BreakpointEvent(start, composed.valueAtClosure(start), score,
                        pointScoreAt(start, inner), isStructural(start)));
            }
            ExactTime terminal = ordered.getLast();
            events.add(new BreakpointEvent(terminal, composed.valueAtClosure(terminal), null,
                    pointScoreAt(terminal, inner), isStructural(terminal)));
            return events;
        }

        private BigInteger pointScoreAt(ExactTime rootTime, ExactAffineSegment inner) {
            if (!component.contains(rootTime) || !prefixScore.domain().contains(rootTime)) return BigInteger.ZERO;
            ExactTime mapped = inner.valueAtClosure(rootTime);
            if (!edgeScore.domain().contains(mapped)) return BigInteger.ZERO;
            return prefixScore.valueAt(rootTime, stopRequested).add(edgeScore.valueAt(mapped, stopRequested));
        }

        private boolean isStructural(ExactTime time) {
            return time.equals(component.start()) || time.equals(component.end());
        }
    }

    private static void addOnClosure(TreeSet<ExactTime> cuts,
            ExactTemporalDomain.Interval event, ExactTemporalDomain.Interval component) {
        if (component.containsClosure(event.start())) cuts.add(event.start());
        if (component.containsClosure(event.end())) cuts.add(event.end());
    }

    private static void addPreimageCut(TreeSet<ExactTime> cuts,
            ExactAffineSegment source, ExactTime target, ExactTemporalDomain.Interval clip) {
        if (source.slope().signum() == 0) return;
        ExactTime root = target.subtract(source.intercept()).divide(source.slope());
        if (clip.containsClosure(root)) cuts.add(root);
    }

    private static ExactTemporalDomain.Interval preimage(
            ExactAffineSegment source, ExactTemporalDomain.Interval target) {
        if (source.slope().signum() == 0) {
            return target.contains(source.intercept()) ? source.interval() : null;
        }
        ExactTime first = target.start().subtract(source.intercept()).divide(source.slope());
        ExactTime last = target.end().subtract(source.intercept()).divide(source.slope());
        boolean firstIncluded = target.startInclusive();
        boolean lastIncluded = target.endInclusive();
        if (source.slope().signum() < 0) {
            ExactTime swap = first; first = last; last = swap;
            boolean included = firstIncluded; firstIncluded = lastIncluded; lastIncluded = included;
        }
        return new ExactTemporalDomain.Interval(first, last, firstIncluded, lastIncluded);
    }

    private static final class Builder {
        private final ExactTemporalDomain support;
        private final ExactTime threshold;
        private final IntSupplier random;
        private final BooleanSupplier stopRequested;
        private final List<ExactAffineSegment> arrivalOutput = new ArrayList<>();
        private final List<ExactScoreProfile.Segment> scoreOutput = new ArrayList<>();
        private long incoming;
        private long retained;
        private long merged;
        private long rouletteDraws;
        private long replacements;
        private long retentions;
        private long peakBuffered;
        private ExactTime maximumArrivalError = ExactTime.ZERO;

        Builder(ExactTemporalDomain support, ExactTime threshold,
                IntSupplier random, BooleanSupplier stopRequested) {
            this.support = Objects.requireNonNull(support, "support");
            this.threshold = Objects.requireNonNull(threshold, "threshold");
            this.random = Objects.requireNonNull(random, "random");
            this.stopRequested = stopRequested;
            if (threshold.signum() <= 0) throw new IllegalArgumentException("Opt2 threshold must be positive");
        }

        void consumeComponent(ExactTemporalDomain.Interval component,
                Iterator<BreakpointEvent> events) {
            if (!events.hasNext()) return;
            BreakpointEvent first = events.next();
            if (!first.time().equals(component.start())) {
                throw new IllegalArgumentException("component stream must begin at its support start");
            }
            if (component.isSingleton()) {
                appendSingleton(first.time(), first.arrival(), first.scoreAtPoint());
                retained++;
                return;
            }

            MergeState left = new MergeState(first, first.scoreOnRight(), false);
            retained++;
            List<BreakpointEvent> pendingOmitted = new ArrayList<>();
            peakBuffered = Math.max(peakBuffered, 1);
            while (events.hasNext()) {
                check(stopRequested);
                BreakpointEvent candidate = events.next();
                if (candidate.time().equals(first.time())) continue;
                incoming++;
                ExactTime gap = candidate.time().subtract(left.point().time());
                boolean mandatoryEndpoint = candidate.structural()
                        || candidate.time().equals(component.end());
                if (!mandatoryEndpoint && gap.compareTo(threshold) <= 0) {
                    if (candidate.scoreOnRight() == null) continue;
                    int draw = random.getAsInt();
                    if (draw < 0 || draw > 100) {
                        throw new IllegalArgumentException("roulette draw must be in [0,100]");
                    }
                    rouletteDraws++;
                    BigInteger selected = roulette(left.representativeScore(), candidate.scoreOnRight(), draw);
                    if (selected.equals(candidate.scoreOnRight())
                            && !selected.equals(left.representativeScore())) replacements++;
                    else retentions++;
                    left = new MergeState(left.point(), selected,
                            left.representativeUpdated()
                                    || !selected.equals(left.point().scoreAtPoint()));
                    pendingOmitted.add(candidate);
                    merged++;
                    peakBuffered = Math.max(peakBuffered, pendingOmitted.size() + 1L);
                    continue;
                }

                recordInterpolationError(left.point(), candidate, pendingOmitted);
                appendSegment(left, candidate, component, false);
                retained++;
                pendingOmitted.clear();
                left = new MergeState(candidate,
                        candidate.scoreOnRight() == null ? candidate.scoreAtPoint() : candidate.scoreOnRight(), false);
                peakBuffered = Math.max(peakBuffered, 2);
            }
            // A well-formed stream emits its mandatory support end. If it was
            // omitted as a duplicate, its structural event must still be kept.
            if (!left.point().time().equals(component.end())) {
                throw new IllegalArgumentException("component stream omitted mandatory support end");
            }
            // The last retained event is the endpoint, so all buffered points
            // belong to the segment that was just finalized.
        }

        private void recordInterpolationError(
                BreakpointEvent left, BreakpointEvent right, List<BreakpointEvent> omitted) {
            if (left.time().equals(right.time())) return;
            ExactTime width = right.time().subtract(left.time());
            for (BreakpointEvent event : omitted) {
                check(stopRequested);
                ExactTime interpolated = left.arrival().add(
                        right.arrival().subtract(left.arrival())
                                .multiply(event.time().subtract(left.time())).divide(width));
                ExactTime error = event.arrival().subtract(interpolated);
                if (error.signum() < 0) error = error.negate();
                if (error.compareTo(maximumArrivalError) > 0) maximumArrivalError = error;
            }
        }

        private void appendSegment(MergeState left, BreakpointEvent right,
                ExactTemporalDomain.Interval component, boolean includeEnd) {
            ExactTime width = right.time().subtract(left.point().time());
            if (width.signum() <= 0) return;
            ExactTime slope = right.arrival().subtract(left.point().arrival()).divide(width);
            ExactTime intercept = left.point().arrival().subtract(slope.multiply(left.point().time()));
            boolean separatePoint = !left.representativeUpdated()
                    && component.contains(left.point().time())
                    && !left.point().scoreAtPoint().equals(left.representativeScore());
            if (separatePoint) {
                appendSingleton(left.point().time(), left.point().arrival(), left.point().scoreAtPoint());
            }
            boolean startIncluded = left.point().time().equals(component.start())
                    ? component.startInclusive() && !separatePoint : !separatePoint;
            boolean endIncluded = includeEnd && right.time().equals(component.end())
                    && component.endInclusive();
            ExactTemporalDomain.Interval interval = new ExactTemporalDomain.Interval(
                    left.point().time(), right.time(), startIncluded, endIncluded);
            arrivalOutput.add(new ExactAffineSegment(slope, intercept, interval));
            scoreOutput.add(new ExactScoreProfile.Segment(left.representativeScore(), interval));
            if (right.time().equals(component.end()) && component.endInclusive()) {
                appendSingleton(right.time(), right.arrival(), right.scoreAtPoint());
            }
        }

        private void appendSingleton(ExactTime time, ExactTime arrival, BigInteger score) {
            ExactTemporalDomain.Interval singleton = ExactTemporalDomain.Interval.singleton(time);
            arrivalOutput.add(new ExactAffineSegment(ExactTime.ZERO, arrival, singleton));
            scoreOutput.add(new ExactScoreProfile.Segment(score, singleton));
        }

        Result finish(long piecesBefore, long compositionNanos, long compositionOperations) {
            return new Result(
                    ExactArrivalProfile.ofSegments(arrivalOutput),
                    ExactScoreProfile.ofSegments(scoreOutput),
                    incoming,
                    retained,
                    merged,
                    rouletteDraws,
                    replacements,
                    retentions,
                    peakBuffered,
                    maximumArrivalError,
                    piecesBefore == 0 ? incoming + retained : piecesBefore,
                    arrivalOutput.size() + scoreOutput.size(),
                    compositionNanos,
                    compositionOperations);
        }
    }

    static BigInteger roulette(BigInteger current, BigInteger incoming, int randomInclusiveZeroToOneHundred) {
        BigInteger total = current.add(incoming);
        if (total.signum() == 0) return randomInclusiveZeroToOneHundred <= 50 ? current : incoming;
        return BigInteger.valueOf(randomInclusiveZeroToOneHundred).multiply(total)
                .compareTo(BigInteger.valueOf(100).multiply(current)) <= 0 ? current : incoming;
    }

    private static void check(BooleanSupplier stopRequested) {
        if (stopRequested != null && stopRequested.getAsBoolean()) {
            throw new CancellationException("Opt2 breakpoint merge cancelled");
        }
    }
}
