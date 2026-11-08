package edu.ipcmax.experiments;

import edu.ipcmax.core.graph.Node;
import edu.ipcmax.core.graph.TDGraph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic recursive spatial-median bisection, the partitioning of the controlled THop index, for graphs of any size.
 *
 * <p>x and y alternate starting with x; at every level the vertices are ordered by (primary coordinate, other coordinate, vertex id) and split at
 * position {@code size/2}, the lower half taking the lower partition ids. Depth d gives 2^d partitions numbered in recursion order. The split needs only
 * the median of a strict total order, so it is found by quickselect on primitive arrays (linear per level) instead of a full sort; the resulting sets are
 * exactly those of the sorted bisection.</p>
 */
final class ThopSpatialPartitioner {
    private ThopSpatialPartitioner() { }

    /** Assignment for every vertex id (sorted order of {@code graph.nodeIds()}), as {@code vertex -> partition}. */
    static Map<Integer, Integer> bisect(TDGraph graph, int depth) {
        List<Integer> ids = graph.nodeIds();
        int n = ids.size();
        int[] id = new int[n];
        long[] x = new long[n];
        long[] y = new long[n];
        for (int i = 0; i < n; i++) {
            int vertex = ids.get(i);
            Node node = graph.node(vertex);
            id[i] = vertex;
            x[i] = node.x();
            y[i] = node.y();
        }
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        int[] partitionOfIndex = new int[n];
        recurse(order, 0, n, 0, depth, 0, id, x, y, partitionOfIndex);
        Map<Integer, Integer> assignment = new HashMap<>((int) (n / 0.75) + 16);
        for (int i = 0; i < n; i++) assignment.put(id[i], partitionOfIndex[i]);
        return assignment;
    }

    private static int recurse(int[] order, int from, int to, int axis, int depth, int first,
                               int[] id, long[] x, long[] y, int[] out) {
        if (depth == 0) {
            for (int i = from; i < to; i++) out[order[i]] = first;
            return first + 1;
        }
        int half = (to - from) / 2;
        select(order, from, to, from + half, axis, id, x, y);
        first = recurse(order, from, from + half, 1 - axis, depth - 1, first, id, x, y, out);
        return recurse(order, from + half, to, 1 - axis, depth - 1, first, id, x, y, out);
    }

    /** Strict total order: (primary coordinate, other coordinate, vertex id). */
    private static int compare(int a, int b, int axis, int[] id, long[] x, long[] y) {
        long pa = axis == 0 ? x[a] : y[a];
        long pb = axis == 0 ? x[b] : y[b];
        if (pa != pb) return pa < pb ? -1 : 1;
        long oa = axis == 0 ? y[a] : x[a];
        long ob = axis == 0 ? y[b] : x[b];
        if (oa != ob) return oa < ob ? -1 : 1;
        return Integer.compare(id[a], id[b]);
    }

    /** Rearranges order[from, to) so that position k holds its sorted element, smaller ones before and larger after (Hoare quickselect, median of three). */
    private static void select(int[] order, int from, int to, int k, int axis, int[] id, long[] x, long[] y) {
        int lo = from;
        int hi = to - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            // median of three as pivot value
            int a = order[lo], b = order[mid], c = order[hi];
            int pivot = compare(a, b, axis, id, x, y) < 0
                    ? (compare(b, c, axis, id, x, y) < 0 ? b : (compare(a, c, axis, id, x, y) < 0 ? c : a))
                    : (compare(a, c, axis, id, x, y) < 0 ? a : (compare(b, c, axis, id, x, y) < 0 ? c : b));
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (compare(order[i], pivot, axis, id, x, y) < 0) i++;
                while (compare(order[j], pivot, axis, id, x, y) > 0) j--;
                if (i <= j) {
                    int t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else return;
        }
    }
}
