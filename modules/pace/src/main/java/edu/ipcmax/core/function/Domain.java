package edu.ipcmax.core.function;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Canonical set of disjoint real-valued intervals.
 *
 * <p>Endpoint inclusion is part of the value.  This is important for temporal
 * functions: a score piece is normally half-open while a query interval is
 * normally closed.  The two-argument {@link Interval} constructor is retained
 * as the convenient spelling for a closed interval.</p>
 */
public final class Domain implements Iterable<Integer> {
    /**
     * Internal decimal precision used by temporal-domain and profile arithmetic.
     * Guard digits keep composed affine roots stable until the public output
     * boundary is reached.
     */
    public static final int TIME_SCALE = 12;

    /** Decimal scale of serialized query-budget values ({@code 10^-9} minute). */
    public static final int REPOSITORY_TIME_UNIT_SCALE = 9;
    /** Signed tick scale for the internal 12-decimal time contract. */
    public static final long TICKS_PER_MINUTE = 1_000_000_000_000L;
    /*
     * Fixed-size direct-mapped memo tables. Canonicalization correctness does
     * not depend on a cache hit; collision replacement simply recomputes the
     * exact BigDecimal contract next time. A fixed bound is essential for
     * routing workloads that legitimately encounter millions of distinct
     * tentative distances.
     */
    private static final int TICK_CACHE_SIZE = 1 << 16;
    private static final ThreadLocal<TickCache> TICK_CACHE =
            ThreadLocal.withInitial(TickCache::new);
    private static final ThreadLocal<TickValueCache>
            TICK_VALUE_CACHE =
            ThreadLocal.withInitial(TickValueCache::new);

    private final List<Interval> intervals;
    private final boolean partition;
    private final List<Double> breakpoints;

    /**
     * Real interval with explicit endpoint inclusion.
     */
    public record Interval(
            double start,
            double end,
            boolean startInclusive,
            boolean endInclusive) {
        /**
         * Creates a closed interval {@code [start,end]}.
         */
        public Interval(double start, double end) {
            this(start, end, true, true);
        }

        /**
         * Creates a validated interval.
         */
        public Interval {
            if (!Double.isFinite(start) || !Double.isFinite(end)) {
                throw new IllegalArgumentException("domain interval bounds must be finite");
            }
            start = canonicalTime(start);
            end = canonicalTime(end);
            if (end < start) {
                throw new IllegalArgumentException("domain interval end must be >= start");
            }
            if (end == start && !(startInclusive && endInclusive)) {
                throw new IllegalArgumentException("a singleton interval must include its endpoint");
            }
        }

        /**
         * True when this interval contains {@code value}.
         */
        public boolean contains(double value) {
            if (!Double.isFinite(value)) {
                return false;
            }
            value = canonicalTime(value);
            boolean afterStart = value > start || (value == start && startInclusive);
            boolean beforeEnd = value < end || (value == end && endInclusive);
            return afterStart && beforeEnd;
        }
    }

    private Domain(List<Interval> intervals) {
        this(intervals, false);
    }

    private Domain(List<Interval> intervals, boolean preservePartition) {
        this.intervals = List.copyOf(preservePartition
                ? validatePartition(intervals)
                : canonicalize(intervals));
        this.partition = preservePartition;
        this.breakpoints = canonicalBreakpoints(this.intervals);
    }

    /**
     * Creates a domain from intervals.
     */
    public static Domain of(Interval... intervals) {
        return new Domain(List.of(intervals));
    }

    /**
     * Creates one closed interval domain.
     */
    public static Domain closed(double start, double end) {
        return new Domain(List.of(new Interval(start, end)));
    }

    /**
     * Creates one half-open interval domain {@code [start,end)}.
     */
    public static Domain halfOpen(double start, double end) {
        start = canonicalTime(start);
        end = canonicalTime(end);
        if (end <= start) {
            return empty();
        }
        return new Domain(List.of(new Interval(start, end, true, false)));
    }

    /**
     * Creates one open interval domain {@code (start,end)}.
     */
    public static Domain open(double start, double end) {
        start = canonicalTime(start);
        end = canonicalTime(end);
        if (end <= start) {
            return empty();
        }
        return new Domain(List.of(new Interval(start, end, false, false)));
    }

    /**
     * Empty domain.
     */
    public static Domain empty() {
        return new Domain(List.of());
    }

    /**
     * Returns the canonical intervals, or the ordered cells for a split view.
     */
    public List<Interval> intervals() {
        return intervals;
    }

    /**
     * Returns all distinct interval endpoints in order.
     */
    public List<Double> breakpoints() {
        return breakpoints;
    }

    /**
     * True when this domain has no times.
     */
    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    /**
     * True when the domain contains the minute.
     */
    public boolean contains(int minute) {
        return contains((double) minute);
    }

    /**
     * True when the domain contains the time value.
     */
    public boolean contains(double minute) {
        if (!Double.isFinite(minute)) {
            return false;
        }
        minute = canonicalTime(minute);
        for (Interval interval : intervals) {
            if (interval.contains(minute)) {
                return true;
            }
            if (minute < interval.start()) {
                return false;
            }
        }
        return false;
    }

    /**
     * Union with another domain.
     */
    public Domain union(Domain other) {
        Objects.requireNonNull(other, "other");
        List<Interval> combined = new ArrayList<>(semanticIntervals());
        combined.addAll(other.semanticIntervals());
        return new Domain(combined);
    }

    /**
     * Intersection with another domain.
     */
    public Domain intersection(Domain other) {
        Objects.requireNonNull(other, "other");
        List<Interval> left = semanticIntervals();
        List<Interval> right = other.semanticIntervals();
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.size() && j < right.size()) {
            Interval a = left.get(i);
            Interval b = right.get(j);
            Interval overlap = intersect(a, b);
            if (overlap != null) {
                result.add(overlap);
            }

            int endComparison = compare(a.end(), b.end());
            if (endComparison < 0) {
                i++;
            } else if (endComparison > 0) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return new Domain(result);
    }

    /**
     * Difference {@code this - other} with exact endpoint ownership.
     */
    public Domain difference(Domain other) {
        Objects.requireNonNull(other, "other");
        List<Interval> fragments = new ArrayList<>(semanticIntervals());
        for (Interval cut : other.semanticIntervals()) {
            List<Interval> next = new ArrayList<>();
            for (Interval fragment : fragments) {
                subtractOne(fragment, cut, next);
            }
            fragments = next;
            if (fragments.isEmpty()) {
                break;
            }
        }
        return new Domain(fragments);
    }

    /**
     * Alias for {@link #difference(Domain)}.
     */
    public Domain subtract(Domain other) {
        return difference(other);
    }

    /**
     * Restricts this domain to a closed interval.
     */
    public Domain restrict(double start, double end) {
        return intersection(Domain.closed(start, end));
    }

    /**
     * Splits intervals at the supplied breakpoints while preserving exact
     * coverage.  Returned cells use the canonical convention {@code [a,b)};
     * the last cell inherits the original right-end inclusion.
     *
     * <p>The returned value is a partition view, so touching cells are kept
     * separate instead of immediately being canonicalized back together.</p>
     */
    public Domain splitAt(List<Double> breakpoints) {
        Objects.requireNonNull(breakpoints, "breakpoints");
        List<Interval> result = new ArrayList<>();
        for (Interval interval : semanticIntervals()) {
            TreeSet<Long> cutTicks = new TreeSet<>();
            long intervalStart = canonicalTick(interval.start());
            long intervalEnd = canonicalTick(interval.end());
            for (double point : breakpoints) {
                if (!Double.isFinite(point)) {
                    throw new IllegalArgumentException("domain breakpoint must be finite");
                }
                long tick = canonicalTick(point);
                if (tick > intervalStart && tick < intervalEnd) {
                    cutTicks.add(tick);
                }
            }

            double cursor = interval.start();
            boolean cursorInclusive = interval.startInclusive();
            for (long tick : cutTicks) {
                double point = timeFromTick(tick);
                result.add(new Interval(cursor, point, cursorInclusive, false));
                cursor = point;
                cursorInclusive = true;
            }
            result.add(new Interval(cursor, interval.end(), cursorInclusive, interval.endInclusive()));
        }
        return new Domain(result, true);
    }

    /**
     * Legacy helper for iterating integer minutes contained in the domain.
     */
    public Iterable<Integer> integerPoints() {
        return this;
    }

    @Override
    public Iterator<Integer> iterator() {
        List<Interval> semantic = semanticIntervals();
        return new Iterator<>() {
            private int intervalIndex;
            private int next = semantic.isEmpty() ? 0 : firstInteger(semantic.get(0));

            @Override
            public boolean hasNext() {
                while (intervalIndex < semantic.size()) {
                    Interval interval = semantic.get(intervalIndex);
                    if (next <= lastInteger(interval)) {
                        return true;
                    }
                    intervalIndex++;
                    if (intervalIndex < semantic.size()) {
                        next = firstInteger(semantic.get(intervalIndex));
                    }
                }
                return false;
            }

            @Override
            public Integer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return next++;
            }
        };
    }

    @Override
    public String toString() {
        return intervals.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Domain domain)) {
            return false;
        }
        return semanticIntervals().equals(domain.semanticIntervals());
    }

    @Override
    public int hashCode() {
        return Objects.hash(semanticIntervals());
    }

    private List<Interval> semanticIntervals() {
        return partition ? canonicalize(intervals) : intervals;
    }

    private static List<Interval> validatePartition(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>(raw);
        sorted.sort(INTERVAL_ORDER);
        for (int i = 1; i < sorted.size(); i++) {
            Interval previous = sorted.get(i - 1);
            Interval current = sorted.get(i);
            int relation = compare(current.start(), previous.end());
            if (relation < 0 || (relation == 0 && previous.endInclusive() && current.startInclusive())) {
                throw new IllegalArgumentException("domain partition cells must not overlap");
            }
        }
        return sorted;
    }

    private static List<Interval> canonicalize(List<Interval> raw) {
        List<Interval> sorted = new ArrayList<>(raw);
        sorted.sort(INTERVAL_ORDER);
        List<Interval> result = new ArrayList<>();
        for (Interval interval : sorted) {
            if (result.isEmpty()) {
                result.add(interval);
                continue;
            }
            Interval last = result.get(result.size() - 1);
            int relation = compare(interval.start(), last.end());
            boolean connected = relation < 0
                    || (relation == 0 && (last.endInclusive() || interval.startInclusive()));
            if (!connected) {
                result.add(interval);
                continue;
            }
            result.set(result.size() - 1, merge(last, interval));
        }
        return result;
    }

    private static Interval merge(Interval left, Interval right) {
        double start = left.start();
        boolean startInclusive = left.startInclusive();
        if (compare(left.start(), right.start()) == 0) {
            start = Math.min(left.start(), right.start());
            startInclusive = left.startInclusive() || right.startInclusive();
        }

        int endComparison = compare(left.end(), right.end());
        if (endComparison > 0) {
            return new Interval(start, left.end(), startInclusive, left.endInclusive());
        }
        if (endComparison < 0) {
            return new Interval(start, right.end(), startInclusive, right.endInclusive());
        }
        return new Interval(start, Math.max(left.end(), right.end()), startInclusive,
                left.endInclusive() || right.endInclusive());
    }

    private static Interval intersect(Interval left, Interval right) {
        int startComparison = compare(left.start(), right.start());
        double start;
        boolean startInclusive;
        if (startComparison > 0) {
            start = left.start();
            startInclusive = left.startInclusive();
        } else if (startComparison < 0) {
            start = right.start();
            startInclusive = right.startInclusive();
        } else {
            start = Math.max(left.start(), right.start());
            startInclusive = left.startInclusive() && right.startInclusive();
        }

        int endComparison = compare(left.end(), right.end());
        double end;
        boolean endInclusive;
        if (endComparison < 0) {
            end = left.end();
            endInclusive = left.endInclusive();
        } else if (endComparison > 0) {
            end = right.end();
            endInclusive = right.endInclusive();
        } else {
            end = Math.min(left.end(), right.end());
            endInclusive = left.endInclusive() && right.endInclusive();
        }

        int relation = compare(start, end);
        if (relation > 0 || (relation == 0 && !(startInclusive && endInclusive))) {
            return null;
        }
        if (relation == 0) {
            double point = (start + end) / 2.0;
            return new Interval(point, point);
        }
        return new Interval(start, end, startInclusive, endInclusive);
    }

    private static void subtractOne(Interval base, Interval cut, List<Interval> output) {
        Interval overlap = intersect(base, cut);
        if (overlap == null) {
            output.add(base);
            return;
        }

        addIfNonEmpty(output, base.start(), overlap.start(), base.startInclusive(), !overlap.startInclusive());
        addIfNonEmpty(output, overlap.end(), base.end(), !overlap.endInclusive(), base.endInclusive());
    }

    private static void addIfNonEmpty(
            List<Interval> output,
            double start,
            double end,
            boolean startInclusive,
            boolean endInclusive) {
        int relation = compare(start, end);
        if (relation < 0) {
            output.add(new Interval(start, end, startInclusive, endInclusive));
        } else if (relation == 0 && startInclusive && endInclusive) {
            double point = (start + end) / 2.0;
            output.add(new Interval(point, point));
        }
    }

    private static int firstInteger(Interval interval) {
        int first = (int) Math.ceil(interval.start());
        if (!interval.startInclusive() && first == interval.start()) {
            first++;
        }
        return first;
    }

    private static int lastInteger(Interval interval) {
        int last = (int) Math.floor(interval.end());
        if (!interval.endInclusive() && last == interval.end()) {
            last--;
        }
        return last;
    }

    private static int compare(double left, double right) {
        return Long.compare(canonicalTick(left), canonicalTick(right));
    }

    /** Converts a temporal value to the repository's fixed decimal representation. */
    public static double canonicalTime(double value) {
        return timeFromTick(canonicalTick(value));
    }

    /**
     * Converts a temporal value to a signed 10^-12-minute HALF_EVEN tick.
     *
     * <p>A small thread-local direct-mapped cache ensures a repeated canonical
     * breakpoint never repeats decimal parsing or allocation on the hot path.</p>
     */
    public static long canonicalTick(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("time value must be finite");
        }
        long bits = Double.doubleToRawLongBits(value);
        TickCache cache = TICK_CACHE.get();
        int existing = cache.find(bits);
        if (existing >= 0) {
            return cache.tickAt(existing);
        }
        long tick;
        try {
            tick = BigDecimal.valueOf(value)
                    .movePointRight(TIME_SCALE)
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .longValueExact();
        } catch (ArithmeticException outOfRange) {
            throw new IllegalArgumentException(
                    "time value is outside signed tick range: " + value,
                    outOfRange);
        }
        cache.put(bits, tick);
        return tick;
    }

    /** Converts one canonical signed tick back to its minute value. */
    public static double timeFromTick(long tick) {
        TickValueCache cache = TICK_VALUE_CACHE.get();
        int existing = cache.find(tick);
        if (existing >= 0) {
            return cache.valueAt(existing);
        }
        /*
         * A floating-point division can differ by one ULP from the legacy
         * BigDecimal.setScale(...).doubleValue() contract. This conversion is
         * paid only once per new tick; comparison and deduplication stay on
         * signed longs.
         */
        double value = BigDecimal.valueOf(
                tick, TIME_SCALE).doubleValue();
        cache.put(tick, value);
        return value;
    }

    /** Exact equality after conversion to the fixed decimal representation. */
    public static boolean sameTime(double left, double right) {
        return canonicalTick(left) == canonicalTick(right);
    }

    private static List<Double> canonicalBreakpoints(
            List<Interval> source) {
        TreeSet<Long> ticks = new TreeSet<>();
        for (Interval interval : source) {
            ticks.add(canonicalTick(interval.start()));
            ticks.add(canonicalTick(interval.end()));
        }
        return ticks.stream().map(Domain::timeFromTick).toList();
    }

    private static final class TickCache {
        private final boolean[] occupied =
                new boolean[TICK_CACHE_SIZE];
        private final long[] bits =
                new long[TICK_CACHE_SIZE];
        private final long[] ticks =
                new long[TICK_CACHE_SIZE];

        int find(long key) {
            int index = hashIndex(
                    key, occupied.length - 1);
            return occupied[index] && bits[index] == key
                    ? index : -1;
        }

        long tickAt(int index) {
            return ticks[index];
        }

        void put(long key, long value) {
            int index = hashIndex(
                    key, occupied.length - 1);
            occupied[index] = true;
            bits[index] = key;
            ticks[index] = value;
        }
    }

    private static final class TickValueCache {
        private final boolean[] occupied =
                new boolean[TICK_CACHE_SIZE];
        private final long[] ticks =
                new long[TICK_CACHE_SIZE];
        private final double[] values =
                new double[TICK_CACHE_SIZE];

        int find(long tick) {
            int index = hashIndex(
                    tick, occupied.length - 1);
            return occupied[index] && ticks[index] == tick
                    ? index : -1;
        }

        double valueAt(int index) {
            return values[index];
        }

        void put(long tick, double value) {
            int index = hashIndex(
                    tick, occupied.length - 1);
            occupied[index] = true;
            ticks[index] = tick;
            values[index] = value;
        }
    }

    private static int hashIndex(
            long value,
            int mask) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value & mask;
    }

    private static final Comparator<Interval> INTERVAL_ORDER = (left, right) -> {
        int startComparison = Double.compare(left.start(), right.start());
        if (startComparison != 0) {
            return startComparison;
        }
        if (left.startInclusive() != right.startInclusive()) {
            return left.startInclusive() ? -1 : 1;
        }
        int endComparison = Double.compare(left.end(), right.end());
        if (endComparison != 0) {
            return endComparison;
        }
        return Boolean.compare(right.endInclusive(), left.endInclusive());
    };
}
