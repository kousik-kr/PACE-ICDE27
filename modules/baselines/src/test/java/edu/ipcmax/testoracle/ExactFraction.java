package edu.ipcmax.testoracle;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** Exact rational arithmetic used by the independent tiny-graph oracle. */
public final class ExactFraction implements Comparable<ExactFraction> {
    public static final ExactFraction ZERO = new ExactFraction(BigInteger.ZERO, BigInteger.ONE);
    public static final ExactFraction ONE = new ExactFraction(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger numerator;
    private final BigInteger denominator;

    private ExactFraction(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("fraction denominator cannot be zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger divisor = numerator.gcd(denominator);
        this.numerator = numerator.divide(divisor);
        this.denominator = denominator.divide(divisor);
    }

    public static ExactFraction of(long value) {
        return value == 0 ? ZERO : new ExactFraction(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static ExactFraction of(BigInteger numerator, BigInteger denominator) {
        return new ExactFraction(numerator, denominator);
    }

    public BigInteger numerator() {
        return numerator;
    }

    public BigInteger denominator() {
        return denominator;
    }

    /** Converts the exact decimal spelling returned by {@link BigDecimal#valueOf(double)}. */
    public static ExactFraction fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("fraction input must be finite");
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        BigInteger numerator = decimal.unscaledValue();
        int scale = decimal.scale();
        if (scale < 0) {
            numerator = numerator.multiply(BigInteger.TEN.pow(-scale));
            return new ExactFraction(numerator, BigInteger.ONE);
        }
        return new ExactFraction(numerator, BigInteger.TEN.pow(scale));
    }

    public ExactFraction add(ExactFraction other) {
        Objects.requireNonNull(other, "other");
        return new ExactFraction(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public ExactFraction subtract(ExactFraction other) {
        Objects.requireNonNull(other, "other");
        return new ExactFraction(
                numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public ExactFraction multiply(ExactFraction other) {
        Objects.requireNonNull(other, "other");
        return new ExactFraction(
                numerator.multiply(other.numerator),
                denominator.multiply(other.denominator));
    }

    public ExactFraction divide(ExactFraction other) {
        Objects.requireNonNull(other, "other");
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return new ExactFraction(
                numerator.multiply(other.denominator),
                denominator.multiply(other.numerator));
    }

    public ExactFraction negate() {
        return numerator.signum() == 0 ? ZERO : new ExactFraction(numerator.negate(), denominator);
    }

    public int signum() {
        return numerator.signum();
    }

    public double toDouble() {
        return numerator.doubleValue() / denominator.doubleValue();
    }

    @Override
    public int compareTo(ExactFraction other) {
        return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ExactFraction other)) {
            return false;
        }
        return numerator.equals(other.numerator) && denominator.equals(other.denominator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numerator, denominator);
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE)
                ? numerator.toString()
                : numerator + "/" + denominator;
    }
}
