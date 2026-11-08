package edu.ipcmax.baselines.exact;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Integer kernels of the exact rational arithmetic. {@link #gcd} returns the same value as
 * {@link BigInteger#gcd} for every input; the {@code LEHMER} kernel computes it with Lehmer's
 * algorithm (Knuth, TAOCP vol. 2, Algorithm 4.5.2L) on 32-bit limbs, which does a few multi-limb
 * passes per 29 bits of reduction instead of the JDK's one pass per bit. Any inconsistency in its
 * bookkeeping falls back to the JDK result, so the kernel cannot change an exact value.
 *
 * <p>The selection is process wide and read on every call; it exists so that runs of the same
 * executable can be compared with and without the kernel. The default is the {@code JDK} kernel.</p>
 */
public final class ExactKernel {
    public enum Mode { JDK, LEHMER }

    /** Smallest operand (in bits) for which Lehmer's passes beat the JDK's hybrid algorithm. */
    static final int LEHMER_MIN_BITS = 200;
    private static final long COFACTOR_LIMIT = 1L << 29;
    private static volatile Mode mode = parse(System.getProperty("ipcmax.exact.kernel", "jdk"));

    private ExactKernel() { }

    public static Mode mode() { return mode; }

    public static void configure(Mode selected) { mode = selected; }

    public static Mode parse(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "jdk" -> Mode.JDK;
            case "lehmer" -> Mode.LEHMER;
            default -> throw new IllegalArgumentException("unknown exact kernel: " + name);
        };
    }

    /** Greatest common divisor of the magnitudes; {@code gcd(0, 0) = 0}. */
    public static BigInteger gcd(BigInteger a, BigInteger b) {
        int smaller = Math.min(a.bitLength(), b.bitLength());
        ExactArithmeticDiagnostics.increment("exact_gcd_calls");
        if (smaller >= LEHMER_MIN_BITS) {
            ExactArithmeticDiagnostics.increment("exact_gcd_large_operand_calls");
            ExactArithmeticDiagnostics.add("exact_gcd_large_operand_bits", smaller);
        }
        if (mode == Mode.JDK || smaller < LEHMER_MIN_BITS) return a.gcd(b);
        return lehmer(a.abs(), b.abs());
    }

    static BigInteger lehmer(BigInteger first, BigInteger second) {
        BigInteger large = first.compareTo(second) >= 0 ? first : second;
        BigInteger small = large == first ? second : first;
        int capacity = ((large.bitLength() + 31) >>> 5) + 2;
        int[] u = new int[capacity], v = new int[capacity], tu = new int[capacity], tv = new int[capacity];
        int nu = load(large, u), nv = load(small, v);
        while (nv > 2) {
            int shift = bitLength(u, nu) - 62;
            long uh = leading62(u, nu, shift);
            long vh = leading62(v, nv, shift);
            long a = 1, b = 0, c = 0, d = 1;
            if (vh != 0) {
                while (true) {
                    long vc = vh + c, vd = vh + d;
                    if (vc == 0 || vd == 0) break;
                    long q = (uh + a) / vc;
                    if (q != (uh + b) / vd || q >= COFACTOR_LIMIT) break;
                    long nc = a - q * c, nd = b - q * d;
                    if (Math.abs(nc) >= COFACTOR_LIMIT || Math.abs(nd) >= COFACTOR_LIMIT) break;
                    a = c;
                    c = nc;
                    b = d;
                    d = nd;
                    long t = uh - q * vh;
                    uh = vh;
                    vh = t;
                }
            }
            if (b == 0) {
                // The leading digits do not determine the quotient: one full division step.
                BigInteger remainder = store(u, nu).mod(store(v, nv));
                int[] swap = u;
                u = v;
                v = swap;
                nu = nv;
                nv = load(remainder, v);
                if (nv == 0) return store(u, nu);
                continue;
            }
            int length = Math.max(nu, nv);
            long carryU = 0, carryV = 0;
            for (int i = 0; i < length; i++) {
                long ui = i < nu ? u[i] & 0xFFFFFFFFL : 0, vi = i < nv ? v[i] & 0xFFFFFFFFL : 0;
                long x = a * ui + b * vi + carryU;
                tu[i] = (int) x;
                carryU = x >> 32;
                long y = c * ui + d * vi + carryV;
                tv[i] = (int) y;
                carryV = y >> 32;
            }
            if (carryU != 0 || carryV != 0) return first.gcd(second); // inconsistent bookkeeping: never trust it
            int[] swap = u;
            u = tu;
            tu = swap;
            swap = v;
            v = tv;
            tv = swap;
            nu = trim(u, length);
            nv = trim(v, length);
            if (nv == 0) return store(u, nu);
            if (compare(u, nu, v, nv) < 0) {
                swap = u;
                u = v;
                v = swap;
                int t = nu;
                nu = nv;
                nv = t;
            }
        }
        return store(u, nu).gcd(store(v, nv));
    }

    private static int load(BigInteger x, int[] out) {
        java.util.Arrays.fill(out, 0);
        byte[] bytes = x.toByteArray();
        for (int i = bytes.length - 1, k = 0; i >= 0; i--, k++) {
            int limb = k >> 2;
            if (limb < out.length) out[limb] |= (bytes[i] & 0xFF) << ((k & 3) << 3);
        }
        return trim(out, out.length);
    }

    private static BigInteger store(int[] a, int n) {
        if (n == 0) return BigInteger.ZERO;
        byte[] bytes = new byte[n * 4 + 1];
        for (int i = 0; i < n; i++) {
            int limb = a[i];
            int base = bytes.length - 1 - i * 4;
            bytes[base] = (byte) limb;
            bytes[base - 1] = (byte) (limb >>> 8);
            bytes[base - 2] = (byte) (limb >>> 16);
            bytes[base - 3] = (byte) (limb >>> 24);
        }
        return new BigInteger(bytes);
    }

    private static int trim(int[] a, int n) {
        while (n > 0 && a[n - 1] == 0) n--;
        return n;
    }

    private static int bitLength(int[] a, int n) {
        return n == 0 ? 0 : (n - 1) * 32 + (32 - Integer.numberOfLeadingZeros(a[n - 1]));
    }

    /** {@code floor(a / 2^shift)} for {@code shift > 0} and {@code a < 2^(shift + 62)}. */
    private static long leading62(int[] a, int n, int shift) {
        int limb = shift >>> 5, offset = shift & 31;
        long low = limb < n ? a[limb] & 0xFFFFFFFFL : 0;
        long middle = limb + 1 < n ? a[limb + 1] & 0xFFFFFFFFL : 0;
        long high = limb + 2 < n ? a[limb + 2] & 0xFFFFFFFFL : 0;
        long bottom = low | (middle << 32);
        long value = offset == 0 ? bottom : (bottom >>> offset) | (high << (64 - offset));
        return value & ((1L << 62) - 1);
    }

    private static int compare(int[] a, int na, int[] b, int nb) {
        if (na != nb) return na < nb ? -1 : 1;
        for (int i = na - 1; i >= 0; i--) {
            if (a[i] != b[i]) return Integer.compareUnsigned(a[i], b[i]);
        }
        return 0;
    }
}
