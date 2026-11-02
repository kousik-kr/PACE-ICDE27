package edu.ipcmax.baselines.exact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Low-overhead component counters for the exact THop arithmetic and profile operations. Disabled unless {@code -Dthop.profile=true};
 * when disabled every hook is a branch on a {@code static final} flag that the JIT removes, and nothing here changes a value.
 *
 * <p>Two kinds of measurements are kept, in per-thread arrays (no sharing between worker threads):</p>
 * <ul>
 *   <li><b>arithmetic operations</b> ({@code A_*}): every call is counted, one call in {@code sampleMask+1} is timed with {@code System.nanoTime};
 *       the totals are the sampled mean (minus the measured timer overhead) times the exact call count. {@code A_GCD} and {@code A_BIGMUL} are
 *       nested inside the others.</li>
 *   <li><b>profile operations</b> ({@code P_*}): every call is counted and timed; a per-thread stack gives each operation's <i>exclusive</i> time
 *       (inclusive minus the time of the profile operations it called), so the exclusive times of all operations plus the search remainder add up
 *       to the wall time of the thread.</li>
 * </ul>
 */
public final class ThopProfile {
    public static final boolean ENABLED = Boolean.getBoolean("thop.profile");
    private static final int SAMPLE_MASK = Integer.getInteger("thop.profile.sampleMask", 31);
    private static final long NOT_SAMPLED = Long.MIN_VALUE;

    public static final int A_ADD = 0, A_SUB = 1, A_MUL = 2, A_DIV = 3, A_CMP = 4, A_GCD = 5, A_BIGMUL = 6, A_N = 7;
    private static final String[] A_NAMES = {"rational_add", "rational_subtract", "rational_multiply", "rational_divide",
            "rational_compare", "gcd", "biginteger_multiply"};

    public static final int P_SEARCH = 0, P_COMPOSE = 1, P_PREIMAGE = 2, P_MINIMUM = 3, P_CROSSING = 4, P_CANONICALIZE = 5, P_OFSEGMENTS = 6,
            P_DOMAIN_OPS = 7, P_DOMINATES = 8, P_SAME_FUNCTION = 9, P_PROJECTION = 10, P_ENCODE = 11, P_DECODE = 12, P_LABEL_PRUNE = 13,
            P_SHARD_IO = 14, P_BOUNDARY_QUERY = 15, P_INNER_SEARCH = 16, P_EDGE_ARRIVAL = 17, P_SEARCH_SETUP = 18, P_N = 19;
    private static final String[] P_NAMES = {"search_driver", "profile_composition", "composition_preimage_boundaries", "pointwise_minimum",
            "breakpoint_crossings", "canonicalization", "profile_construction_sort_normalize", "temporal_domain_operations", "dominance_check",
            "same_function_check", "profile_materialization_rounded_projection", "profile_encode", "profile_decode", "label_pruning",
            "shard_disk_io", "boundary_query", "inner_label_search", "edge_arrival_exact_conversion", "search_setup_fifo_validation_and_support_check"};

    private static final List<Local> ALL = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Local> LOCAL = ThreadLocal.withInitial(Local::new);
    private static volatile double timerOverheadNanos = -1;

    private ThopProfile() { }

    /** Per-thread counters. */
    public static final class Local {
        final long[] aCount = new long[A_N];
        final long[] aSampled = new long[A_N];
        final long[] aSampledNanos = new long[A_N];
        final long[] pCount = new long[P_N];
        final long[] pIncl = new long[P_N];
        final long[] pExcl = new long[P_N];
        private final long[] stack = new long[96];
        private int depth = -1;
        long maxNumeratorBits, maxDenominatorBits, resultSamples, resultBitsSum;
        long gcdLargeCalls, gcdOperandBitsSum, gcdOperandSamples;
        long mulOperandBitsSum;
        long intervalIntersections, intervalIntersectionSampled, intervalIntersectionNanos;
        long profilesBuilt, profilePiecesSum, profilePiecesMax, profileBitsMax;

        private Local() {
            ALL.add(this);
        }

        long arithBegin(int op) {
            return (aCount[op]++ & SAMPLE_MASK) == 0 ? System.nanoTime() : NOT_SAMPLED;
        }

        void arithEnd(int op, long started) {
            if (started != NOT_SAMPLED) {
                aSampled[op]++;
                aSampledNanos[op] += System.nanoTime() - started;
            }
        }

        void observeResult(ExactTime value) {
            int numBits = value.numerator().bitLength();
            int denBits = value.denominator().bitLength();
            if (numBits > maxNumeratorBits) maxNumeratorBits = numBits;
            if (denBits > maxDenominatorBits) maxDenominatorBits = denBits;
            if ((resultSamples++ & SAMPLE_MASK) == 0) resultBitsSum += numBits + denBits;
        }

        long begin() {
            if (++depth >= stack.length) depth = stack.length - 1;
            stack[depth] = 0;
            return System.nanoTime();
        }

        void end(int op, long started) {
            long elapsed = System.nanoTime() - started;
            long children = stack[depth];
            if (depth > 0) depth--; else depth = -1;
            pCount[op]++;
            pIncl[op] += elapsed;
            pExcl[op] += elapsed - children;
            if (depth >= 0) stack[depth] += elapsed;
        }
    }

    public static Local local() {
        return LOCAL.get();
    }

    // ---- arithmetic hooks (callers guard with ENABLED)

    public static BigInteger mul(BigInteger a, BigInteger b) {
        if (!ENABLED) return a.multiply(b);
        Local p = LOCAL.get();
        long started = p.arithBegin(A_BIGMUL);
        BigInteger result = a.multiply(b);
        if (started != NOT_SAMPLED) p.mulOperandBitsSum += a.bitLength() + b.bitLength();
        p.arithEnd(A_BIGMUL, started);
        return result;
    }

    public static long arithBegin(int op) {
        return LOCAL.get().arithBegin(op);
    }

    public static void arithEnd(int op, long started, ExactTime result) {
        Local p = LOCAL.get();
        p.arithEnd(op, started);
        if (result != null) p.observeResult(result);
    }

    public static long gcdBegin(int smallerBits, boolean large) {
        Local p = LOCAL.get();
        if (large) p.gcdLargeCalls++;
        long started = p.arithBegin(A_GCD);
        if (started != NOT_SAMPLED) {
            p.gcdOperandBitsSum += smallerBits;
            p.gcdOperandSamples++;
        }
        return started;
    }

    public static void gcdEnd(long started) {
        LOCAL.get().arithEnd(A_GCD, started);
    }

    // ---- profile-operation hooks

    public static long begin() {
        return LOCAL.get().begin();
    }

    public static void end(int op, long started) {
        LOCAL.get().end(op, started);
    }

    public static void observeProfile(int pieces) {
        Local p = LOCAL.get();
        p.profilesBuilt++;
        p.profilePiecesSum += pieces;
        if (pieces > p.profilePiecesMax) p.profilePiecesMax = pieces;
    }

    public static long intervalBegin() {
        Local p = LOCAL.get();
        p.intervalIntersections++;
        return (p.intervalIntersections & SAMPLE_MASK) == 0 ? System.nanoTime() : NOT_SAMPLED;
    }

    public static void intervalEnd(long started) {
        if (started != NOT_SAMPLED) {
            Local p = LOCAL.get();
            p.intervalIntersectionSampled++;
            p.intervalIntersectionNanos += System.nanoTime() - started;
        }
    }

    // ---- reporting

    /** Cost of one {@code System.nanoTime()} pair, measured once, subtracted from every sampled time. */
    public static double timerOverheadNanos() {
        double cached = timerOverheadNanos;
        if (cached >= 0) return cached;
        long sink = 0;
        for (int warm = 0; warm < 200_000; warm++) sink += System.nanoTime();
        int rounds = 2_000_000;
        long started = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            long a = System.nanoTime();
            sink += System.nanoTime() - a;
        }
        double perPair = (System.nanoTime() - started) / (double) rounds;
        if (sink == 42) perPair += 0; // keep the loop alive
        timerOverheadNanos = perPair;
        return perPair;
    }

    /** Aggregate over all threads that have used the counters. Safe to call at any time (values are monotone and read without locking). */
    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!ENABLED) {
            out.put("enabled", false);
            return out;
        }
        double overhead = timerOverheadNanos();
        long[] aCount = new long[A_N], aSampled = new long[A_N], aSampledNanos = new long[A_N];
        long[] pCount = new long[P_N], pIncl = new long[P_N], pExcl = new long[P_N];
        long maxNum = 0, maxDen = 0, resultSamples = 0, resultBits = 0, gcdLarge = 0, gcdBits = 0, gcdSamples = 0, mulBits = 0;
        long ivCount = 0, ivSampled = 0, ivNanos = 0, built = 0, pieceSum = 0, pieceMax = 0;
        for (Local l : ALL) {
            for (int i = 0; i < A_N; i++) {
                aCount[i] += l.aCount[i];
                aSampled[i] += l.aSampled[i];
                aSampledNanos[i] += l.aSampledNanos[i];
            }
            for (int i = 0; i < P_N; i++) {
                pCount[i] += l.pCount[i];
                pIncl[i] += l.pIncl[i];
                pExcl[i] += l.pExcl[i];
            }
            maxNum = Math.max(maxNum, l.maxNumeratorBits);
            maxDen = Math.max(maxDen, l.maxDenominatorBits);
            resultSamples += (l.resultSamples + SAMPLE_MASK) / (SAMPLE_MASK + 1);
            resultBits += l.resultBitsSum;
            gcdLarge += l.gcdLargeCalls;
            gcdBits += l.gcdOperandBitsSum;
            gcdSamples += l.gcdOperandSamples;
            mulBits += l.mulOperandBitsSum;
            ivCount += l.intervalIntersections;
            ivSampled += l.intervalIntersectionSampled;
            ivNanos += l.intervalIntersectionNanos;
            built += l.profilesBuilt;
            pieceSum += l.profilePiecesSum;
            pieceMax = Math.max(pieceMax, l.profilePiecesMax);
        }
        out.put("enabled", true);
        out.put("sample_one_in", SAMPLE_MASK + 1);
        out.put("timer_pair_overhead_ns", overhead);
        out.put("threads_observed", ALL.size());
        Map<String, Object> arithmetic = new LinkedHashMap<>();
        double topLevelNanos = 0;
        for (int i = 0; i < A_N; i++) {
            double mean = aSampled[i] == 0 ? 0 : Math.max(0, aSampledNanos[i] / (double) aSampled[i] - overhead);
            double estimated = mean * aCount[i];
            if (i <= A_CMP) topLevelNanos += estimated;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("calls", aCount[i]);
            row.put("sampled_calls", aSampled[i]);
            row.put("mean_ns_overhead_adjusted", mean);
            row.put("estimated_total_ns", estimated);
            arithmetic.put(A_NAMES[i], row);
        }
        out.put("arithmetic", arithmetic);
        out.put("arithmetic_top_level_estimated_total_ns", topLevelNanos);
        out.put("max_result_numerator_bits", maxNum);
        out.put("max_result_denominator_bits", maxDen);
        out.put("mean_result_bits_sampled", resultSamples == 0 ? 0 : resultBits / (double) resultSamples);
        out.put("gcd_calls_with_operands_at_least_200_bits", gcdLarge);
        out.put("gcd_mean_smaller_operand_bits_sampled", gcdSamples == 0 ? 0 : gcdBits / (double) gcdSamples);
        out.put("biginteger_multiply_mean_operand_bits_sum_sampled", aSampled[A_BIGMUL] == 0 ? 0 : mulBits / (double) aSampled[A_BIGMUL]);
        double ivMean = ivSampled == 0 ? 0 : Math.max(0, ivNanos / (double) ivSampled - overhead);
        out.put("interval_intersections", Map.of("calls", ivCount, "mean_ns_overhead_adjusted", ivMean, "estimated_total_ns", ivMean * ivCount));
        Map<String, Object> operations = new LinkedHashMap<>();
        for (int i = 0; i < P_N; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("calls", pCount[i]);
            row.put("inclusive_ns", pIncl[i]);
            row.put("exclusive_ns", pExcl[i]);
            operations.put(P_NAMES[i], row);
        }
        out.put("profile_operations", operations);
        out.put("profiles_constructed", built);
        out.put("profile_pieces_constructed_total", pieceSum);
        out.put("profile_pieces_max", pieceMax);
        return out;
    }

    public static List<String> arithmeticNames() {
        return List.of(A_NAMES);
    }

    public static List<String> operationNames() {
        return new ArrayList<>(List.of(P_NAMES));
    }
}
