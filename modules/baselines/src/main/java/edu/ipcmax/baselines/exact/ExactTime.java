package edu.ipcmax.baselines.exact;

import edu.ipcmax.core.function.Domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/** Immutable arbitrary precision rational time or value. */
public record ExactTime(BigInteger numerator, BigInteger denominator)
        implements Comparable<ExactTime> {
    /**
     * One-shot permission for the next construction on this thread to skip
     * normalization. Only this class's arithmetic sets it, immediately before
     * constructing a result that is reduced with a positive denominator by
     * construction; every other construction normalizes. Declared before
     * {@link #ZERO} so it exists during static initialization.
     */
    private static final ThreadLocal<boolean[]> TRUSTED_REDUCED = ThreadLocal.withInitial(() -> new boolean[1]);
    public static final ExactTime ZERO = of(0);
    public static final ExactTime ONE = of(1);

    public ExactTime {
        boolean[] trusted = TRUSTED_REDUCED.get();
        boolean reduced = trusted[0];
        trusted[0] = false;
        ExactArithmeticDiagnostics.increment("exact_rational_constructions");
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (!reduced) {
            if (denominator.signum() == 0) {
                throw new ArithmeticException("zero denominator");
            }
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            ExactArithmeticDiagnostics.increment(
                    "exact_rational_normalization_gcd_operations");
            BigInteger gcd = ExactKernel.gcd(numerator, denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }

    /** Constructs a value already reduced with a positive denominator, without a final gcd. */
    private static ExactTime reduced(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() == 0) return ZERO;
        TRUSTED_REDUCED.get()[0] = true;
        return new ExactTime(numerator, denominator);
    }

    public static ExactTime of(long value) {
        return new ExactTime(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static ExactTime of(BigInteger numerator, BigInteger denominator) {
        return new ExactTime(numerator, denominator);
    }

    /** Parses an exact decimal or integer spelling. */
    public static ExactTime parseDecimal(String text) {
        BigDecimal value = new BigDecimal(Objects.requireNonNull(text, "text"));
        BigInteger n = value.unscaledValue();
        int scale = value.scale();
        return scale >= 0
                ? new ExactTime(n, BigInteger.TEN.pow(scale))
                : new ExactTime(n.multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE);
    }

    /**
     * Converts a repository-canonicalized double through its shortest decimal
     * spelling. This is exact for accepted decimal inputs after their existing
     * 12-place canonicalization. It does not recover the original decimal
     * spelling when a caller supplied an arbitrary binary double.
     */
    public static ExactTime fromCanonicalDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("exact decimal source must be finite");
        }
        return parseDecimal(BigDecimal.valueOf(value).toPlainString());
    }

    /*
     * Arithmetic on reduced operands uses the cross-reduction of Knuth, TAOCP
     * vol. 2, 4.5.1: the gcds are taken on the operands rather than on their
     * products, and the results are already in the unique reduced form that
     * normalization of the plain formula would produce.
     */

    public ExactTime add(ExactTime other) {
        ExactArithmeticDiagnostics.increment("exact_add_operations");
        return sum(numerator, denominator, other.numerator, other.denominator);
    }

    public ExactTime subtract(ExactTime other) {
        ExactArithmeticDiagnostics.increment("exact_subtract_operations");
        return sum(numerator, denominator, other.numerator.negate(), other.denominator);
    }

    private static ExactTime sum(BigInteger a, BigInteger b, BigInteger c, BigInteger d) {
        BigInteger g = ExactKernel.gcd(b, d);
        if (g.equals(BigInteger.ONE)) {
            // gcd(ad+cb, bd) = 1 when b and d are coprime and both operands are reduced.
            return reduced(a.multiply(d).add(c.multiply(b)), b.multiply(d));
        }
        BigInteger t = a.multiply(d.divide(g)).add(c.multiply(b.divide(g)));
        BigInteger g2 = ExactKernel.gcd(t, g);
        return reduced(t.divide(g2), b.divide(g).multiply(d.divide(g2)));
    }

    public ExactTime multiply(ExactTime other) {
        ExactArithmeticDiagnostics.increment("exact_multiply_operations");
        return product(numerator, denominator, other.numerator, other.denominator);
    }

    private static ExactTime product(BigInteger a, BigInteger b, BigInteger c, BigInteger d) {
        if (a.signum() == 0 || c.signum() == 0) return ZERO;
        BigInteger g1 = ExactKernel.gcd(a.abs(), d);
        BigInteger g2 = ExactKernel.gcd(c.abs(), b);
        return reduced(a.divide(g1).multiply(c.divide(g2)), b.divide(g2).multiply(d.divide(g1)));
    }

    public ExactTime divide(ExactTime other) {
        ExactArithmeticDiagnostics.increment("exact_divide_operations");
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        // The reciprocal of a reduced value is reduced once its sign moves to the numerator.
        BigInteger c = other.numerator.signum() < 0 ? other.denominator.negate() : other.denominator;
        return product(numerator, denominator, c, other.numerator.abs());
    }

    public ExactTime negate() {
        return reduced(numerator.negate(), denominator);
    }

    public int signum() {
        return numerator.signum();
    }

    public BigDecimal roundedDecimal(int scale) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), scale, RoundingMode.HALF_EVEN);
    }

    /** Final public display boundary: HALF_EVEN to the repository's 12 places. */
    public double toDisplayDouble() {
        return roundedDecimal(Domain.TIME_SCALE).doubleValue();
    }

    public double toDouble() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContextHolder.CONTEXT)
                .doubleValue();
    }

    @Override
    public int compareTo(ExactTime other) {
        ExactArithmeticDiagnostics.increment("exact_compare_operations");
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE) ? numerator.toString() : numerator + "/" + denominator;
    }

    private static final class MathContextHolder {
        private static final java.math.MathContext CONTEXT = new java.math.MathContext(50, RoundingMode.HALF_EVEN);
    }
}
