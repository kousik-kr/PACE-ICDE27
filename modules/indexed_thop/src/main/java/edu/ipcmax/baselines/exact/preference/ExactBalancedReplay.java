package edu.ipcmax.baselines.exact.preference;

import edu.ipcmax.baselines.exact.ExactAffineSegment;
import edu.ipcmax.baselines.exact.ExactArithmeticDiagnostics;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.validate.Path;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Exact replay of a long path by pairwise composition of segment transfers.
 *
 * <p>Edge-by-edge replay composes one more edge onto a profile whose coefficients already carry the
 * product of every earlier edge's slope, so the rational arithmetic of step {@code k} works on
 * numbers of about {@code k} edges' size and the whole cost grows with the cube of the path length.
 * The transfer of a path is also the composition of the transfers of its segments, and a segment's
 * transfer needs only a domain that <em>covers</em> the entry times that can reach it. Such a
 * domain is cheap to bound without any composition: the hull of the exact image of one edge over a
 * hull is read off the edge's own pieces, and rounding it outward to a coarse binary grid keeps every
 * band endpoint a small rational. Each segment is then replayed independently on its band with the
 * ordinary replay (small numbers, a handful of pieces) and the segments are composed pairwise in a
 * balanced tree, so the large coefficients only occur in the few compositions near the root.</p>
 *
 * <p>Exactness. Every band contains the exact image of the true entry domain through the preceding
 * edges (each step maps a superset into a superset), a segment transfer on a superset equals the
 * transfer on the true entry set where the latter is defined, and composition is exact. The pairwise
 * composition ({@link ExactPreferencePrefixProfile#then}) verifies exactly that the right transfer
 * was evaluated on every entry time the left one can produce and throws otherwise; the caller then
 * falls back to edge-by-edge replay, so a wrong band can cost time but cannot change a result.</p>
 */
final class ExactBalancedReplay {
    /** Bands are rounded outward to multiples of 2^-GRID_BITS minutes. */
    static final int GRID_BITS = 20;
    private static final BigInteger GRID = BigInteger.ONE.shiftLeft(GRID_BITS);

    private ExactBalancedReplay() { }

    /** Thrown only for the caller to fall back; never a result. */
    static final class BandNotCoveringException extends IllegalArgumentException {
        BandNotCoveringException(String message, Throwable cause) { super(message, cause); }
    }

    static ExactPreferenceTransferProfile replay(TDGraph graph, Path path, ExactTemporalDomain entry,
            int leafEdges, BooleanSupplier cancellationRequested) {
        List<Integer> arcs = path.arcIds();
        int n = arcs.size();
        if (entry.isEmpty()) return emptyTransfer(path, entry);
        ExactArithmeticDiagnostics.increment("exact_balanced_replays");
        List<ExactEdgeProfiles.Profiles> profiles = new ArrayList<>(n);
        for (int arc : arcs) {
            check(cancellationRequested);
            profiles.add(ExactEdgeProfiles.of(graph.edges().get(arc), cancellationRequested));
        }
        int leaves = (n + leafEdges - 1) / leafEdges;
        ExactTemporalDomain[] leafEntry = new ExactTemporalDomain[leaves];
        ExactTime low = entry.intervals().getFirst().start();
        ExactTime high = entry.intervals().getLast().end();
        for (int edge = 0; edge < n; edge++) {
            check(cancellationRequested);
            if (edge % leafEdges == 0) {
                leafEntry[edge / leafEdges] = edge == 0 ? entry
                        : ExactTemporalDomain.of(ExactTemporalDomain.Interval.closed(low, high));
            }
            ExactTime[] image = imageHull(profiles.get(edge), low, high);
            if (image == null) return emptyTransfer(path, entry);
            low = floorToGrid(image[0]);
            high = ceilToGrid(image[1]);
        }
        Tree tree = new Tree(graph, path, arcs, leafEdges, leafEntry, cancellationRequested);
        return tree.reduce(0, leaves);
    }

    private static final class Tree {
        private final TDGraph graph;
        private final Path path;
        private final List<Integer> arcs;
        private final int leafEdges;
        private final ExactTemporalDomain[] leafEntry;
        private final BooleanSupplier cancellationRequested;

        Tree(TDGraph graph, Path path, List<Integer> arcs, int leafEdges, ExactTemporalDomain[] leafEntry,
                BooleanSupplier cancellationRequested) {
            this.graph = graph;
            this.path = path;
            this.arcs = arcs;
            this.leafEdges = leafEdges;
            this.leafEntry = leafEntry;
            this.cancellationRequested = cancellationRequested;
        }

        private Path range(int fromLeaf, int toLeaf) {
            int start = fromLeaf * leafEdges;
            int end = Math.min(arcs.size(), toLeaf * leafEdges);
            return start == 0 && end == arcs.size() ? path : new Path(arcs.subList(start, end));
        }

        ExactPreferenceTransferProfile reduce(int fromLeaf, int toLeaf) {
            check(cancellationRequested);
            Path segment = range(fromLeaf, toLeaf);
            if (toLeaf - fromLeaf == 1) {
                ExactArithmeticDiagnostics.increment("exact_balanced_leaf_replays");
                int firstArc = segment.arcIds().getFirst();
                return ExactPreferenceReplay.replayEdges(graph, graph.edges().get(firstArc).source(), segment,
                        leafEntry[fromLeaf], true, cancellationRequested);
            }
            int middle = (fromLeaf + toLeaf) >>> 1;
            ExactPreferenceTransferProfile left = reduce(fromLeaf, middle);
            if (left.arrival().isEmpty()) return emptyTransfer(segment, left.requestedDomain());
            ExactPreferenceTransferProfile right = reduce(middle, toLeaf);
            ExactArithmeticDiagnostics.increment("exact_balanced_combines");
            ExactPreferencePrefixProfile combined;
            try {
                combined = ExactPreferencePrefixProfile.ofTransfer(left).then(right, cancellationRequested);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (IllegalArgumentException notCovered) {
                throw new BandNotCoveringException("segment band does not cover the left image", notCovered);
            }
            ExactPreferenceTransferProfile result = combined.toTransfer(segment);
            ExactArithmeticDiagnostics.add("exact_balanced_intermediate_pieces",
                    result.arrival().pieceCount() + result.score().pieceCount());
            return result;
        }
    }

    private static ExactPreferenceTransferProfile emptyTransfer(Path path, ExactTemporalDomain requested) {
        return new ExactPreferenceTransferProfile(path, requested, ExactArrivalProfile.empty(),
                ExactScoreProfile.empty(), 0);
    }

    /**
     * The smallest and largest value the edge's arrival function takes over the closure of the
     * supported entry times inside {@code [low, high]}, or {@code null} when there are none. The
     * extremes of a piecewise affine function over closed pieces are at piece ends, so the hull holds
     * for any slope sign.
     */
    static ExactTime[] imageHull(ExactEdgeProfiles.Profiles profile, ExactTime low, ExactTime high) {
        List<ExactAffineSegment> segments = profile.arrival().segments();
        List<ExactTemporalDomain.Interval> scoreDomain = profile.score().domain().intervals();
        ExactTime minimum = null, maximum = null;
        for (int i = ExactArrivalProfile.firstEndingAtOrAfter(segments, low); i < segments.size(); i++) {
            ExactAffineSegment segment = segments.get(i);
            if (segment.interval().start().compareTo(high) > 0) break;
            ExactTime start = max(segment.interval().start(), low);
            ExactTime end = min(segment.interval().end(), high);
            if (start.compareTo(end) > 0) continue;
            for (ExactTemporalDomain.Interval scored : scoreDomain) {
                if (scored.start().compareTo(end) > 0) break;
                ExactTime from = max(start, scored.start());
                ExactTime to = min(end, scored.end());
                if (from.compareTo(to) > 0) continue;
                ExactTime first = segment.valueAtClosure(from);
                ExactTime last = segment.valueAtClosure(to);
                ExactTime lower = first.compareTo(last) <= 0 ? first : last;
                ExactTime upper = first.compareTo(last) <= 0 ? last : first;
                if (minimum == null || lower.compareTo(minimum) < 0) minimum = lower;
                if (maximum == null || upper.compareTo(maximum) > 0) maximum = upper;
            }
        }
        return minimum == null ? null : new ExactTime[] {minimum, maximum};
    }

    static ExactTime floorToGrid(ExactTime value) {
        BigInteger[] division = value.numerator().shiftLeft(GRID_BITS).divideAndRemainder(value.denominator());
        BigInteger floor = division[1].signum() < 0 ? division[0].subtract(BigInteger.ONE) : division[0];
        return ExactTime.of(floor, GRID);
    }

    static ExactTime ceilToGrid(ExactTime value) {
        BigInteger[] division = value.numerator().shiftLeft(GRID_BITS).divideAndRemainder(value.denominator());
        BigInteger ceil = division[1].signum() > 0 ? division[0].add(BigInteger.ONE) : division[0];
        return ExactTime.of(ceil, GRID);
    }

    private static ExactTime max(ExactTime left, ExactTime right) { return left.compareTo(right) >= 0 ? left : right; }
    private static ExactTime min(ExactTime left, ExactTime right) { return left.compareTo(right) <= 0 ? left : right; }

    private static void check(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("balanced exact replay cancelled");
        }
    }
}
