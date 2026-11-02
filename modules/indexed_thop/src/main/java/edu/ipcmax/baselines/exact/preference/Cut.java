package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.ExactTimeApproximation;

/**
 * An ordered exact time with a double approximation that decides most comparisons. The order is always the
 * exact one: the approximations decide only when they differ by more than their rigorous error bound, and
 * every other comparison falls back to the exact comparison.
 */
final class Cut implements Comparable<Cut> {
    final ExactTime value;
    final double approximation;
    /** {@link FixedPoint#of(ExactTime)} of the value, formed when a length first needs it. */
    private java.math.BigInteger fixed;

    Cut(ExactTime value) {
        this.value = value;
        this.approximation = ExactTimeApproximation.of(value);
    }

    java.math.BigInteger fixed() {
        java.math.BigInteger result = fixed;
        if (result == null) fixed = result = FixedPoint.of(value);
        return result;
    }

    @Override public int compareTo(Cut other) {
        return ExactTimeApproximation.compare(value, approximation, other.value, other.approximation);
    }

    /** True when both denote the same exact time. */
    boolean sameAs(Cut other) { return compareTo(other) == 0; }

    @Override public String toString() { return value.toString(); }
}
