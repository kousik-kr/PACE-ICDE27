package edu.ipcmax.baselines.exact;

import java.math.BigInteger;

/**
 * Double approximations of exact rationals with a rigorous error bound, used only to skip exact
 * work when an order or sign is certain. A comparison that is not certain from the approximations
 * is always decided exactly, so results never differ from exact comparison.
 *
 * <p>{@link #of} returns a double within a relative 2^-50 of the exact value: numerator and
 * denominator are truncated to at most 62 significant bits (relative error below 2^-61 each), each
 * converted to a double (2^-53), divided once (2^-53) and rescaled exactly. Two approximations
 * decide the order when they differ by more than 2^-40 of their magnitude, a margin 2^10 times the
 * worst error. A non-finite approximation (overflow) is never certain.</p>
 */
public final class ExactTimeApproximation {
    /** Returned by {@link #certainOrder} when the approximations cannot decide the order. */
    public static final int UNDECIDED = 2;
    private static final double MARGIN = 0x1p-40;

    private ExactTimeApproximation() { }

    public static double of(ExactTime x) {
        BigInteger numerator = x.numerator();
        if (numerator.signum() == 0) return 0;
        BigInteger denominator = x.denominator();
        int numeratorShift = Math.max(0, numerator.bitLength() - 62);
        int denominatorShift = Math.max(0, denominator.bitLength() - 62);
        double ratio = numerator.shiftRight(numeratorShift).doubleValue()
                / denominator.shiftRight(denominatorShift).doubleValue();
        return Math.scalb(ratio, numeratorShift - denominatorShift);
    }

    /** -1, 0 or 1 when the approximations alone prove the order of the exact values; otherwise {@link #UNDECIDED}. */
    public static int certainOrder(double a, double b) {
        double difference = a - b;
        double bound = (Math.abs(a) + Math.abs(b)) * MARGIN + Double.MIN_NORMAL;
        if (!Double.isFinite(difference) || !Double.isFinite(bound)) return UNDECIDED;
        if (difference > bound) return 1;
        if (difference < -bound) return -1;
        return UNDECIDED;
    }

    /** Exact order of {@code a} and {@code b}, skipping the multiplications when the approximations are certain. */
    public static int compare(ExactTime a, double approximateA, ExactTime b, double approximateB) {
        int certain = certainOrder(approximateA, approximateB);
        if (certain != UNDECIDED) return certain;
        if (a.numerator().equals(b.numerator()) && a.denominator().equals(b.denominator())) return 0;
        return a.compareTo(b);
    }
}
