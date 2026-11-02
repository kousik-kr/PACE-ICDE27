package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.function.Supplier;

import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.core.function.Domain;

/**
 * Fixed-point approximations of exact rationals with a rigorous absolute error bound, and the certified
 * rounding of such an approximation to the repository's display double.
 *
 * <p>A value is represented by an integer {@code f} with {@code |f - x * 2^SCALE| <= error}. The display
 * double of an exact {@code x} is {@code x} rounded HALF_EVEN to {@link Domain#TIME_SCALE} places; rounding is
 * monotone, so when the lower and upper ends of the error interval round to the same decimal, so does
 * {@code x}, and the double is exactly the one the exact value would give. Otherwise (a value within the
 * error of a rounding boundary, including an exact tie) the exact value is rounded instead, so the result is
 * always the exact one. The approximation costs one division of at most {@link #KEEP}-bit integers where the
 * exact value costs the arithmetic on coefficients thousands of bits long.</p>
 */
final class FixedPoint {
    static final int SCALE = 128;
    /** Significant bits kept of numerator and denominator; the relative error of a quotient is below 2^-190. */
    private static final int KEEP = 192;
    private static final BigInteger TWO = BigInteger.TWO;
    /** 2^-SCALE as an exact decimal. */
    private static final BigDecimal UNIT = new BigDecimal(BigInteger.valueOf(5).pow(SCALE)).scaleByPowerOfTen(-SCALE);

    /** An approximation {@code value} within {@code error} of the exact quantity times 2^SCALE. */
    record Approx(BigInteger value, BigInteger error) {
        static final Approx ZERO = new Approx(BigInteger.ZERO, BigInteger.ZERO);

        Approx plus(Approx other) { return new Approx(value.add(other.value), error.add(other.error)); }
    }

    private FixedPoint() { }

    /** {@code x * 2^SCALE} to within {@link #errorOf}. */
    static BigInteger of(ExactTime x) {
        BigInteger numerator = x.numerator();
        if (numerator.signum() == 0) return BigInteger.ZERO;
        BigInteger denominator = x.denominator();
        int numeratorShift = Math.max(0, numerator.bitLength() - KEEP);
        int denominatorShift = Math.max(0, denominator.bitLength() - KEEP);
        BigInteger top = numerator.shiftRight(numeratorShift);
        BigInteger bottom = denominator.shiftRight(denominatorShift);
        int exponent = SCALE + numeratorShift - denominatorShift;
        return exponent >= 0 ? top.shiftLeft(exponent).divide(bottom) : top.divide(bottom.shiftLeft(-exponent));
    }

    /**
     * The error bound of {@link #of}: two units from the truncations and the final division, plus the relative
     * error of the truncated operands (below 2^-190) of the magnitude.
     */
    static BigInteger errorOf(BigInteger fixed) {
        return TWO.add(fixed.abs().shiftRight(KEEP - 12));
    }

    /**
     * The display double of the exact quantity {@code approx} approximates, decided from the approximation when
     * its error interval rounds to one decimal and from {@code exact} otherwise.
     */
    static double display(Approx approx, Supplier<ExactTime> exact) {
        BigDecimal low = decimal(approx.value().subtract(approx.error())).setScale(Domain.TIME_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal high = decimal(approx.value().add(approx.error())).setScale(Domain.TIME_SCALE, RoundingMode.HALF_EVEN);
        if (low.equals(high)) {
            ExactArithmeticDiagnostics.increment("exact_display_certified");
            return high.doubleValue();
        }
        ExactArithmeticDiagnostics.increment("exact_display_exact_fallbacks");
        return exact.get().toDisplayDouble();
    }

    private static BigDecimal decimal(BigInteger fixed) {
        return new BigDecimal(fixed).multiply(UNIT);
    }
}
