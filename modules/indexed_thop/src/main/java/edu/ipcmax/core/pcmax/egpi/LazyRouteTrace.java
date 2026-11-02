package edu.ipcmax.core.pcmax.egpi;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * The per-route search trace whose exact credit totals are formed when the trace is first read.
 *
 * <p>The totals are diagnostics of the search, not inputs of any decision: the search decides with display
 * doubles of the exact values that it certifies from approximations. Forming each route's exact total costs
 * exact rational additions on coefficients thousands of bits long, so it is left to the reader of the trace
 * (the experiment record, a test) instead of the query. A read produces exactly the entries that building
 * them eagerly would have produced.</p>
 */
final class LazyRouteTrace extends AbstractList<Map<String, Object>> implements RandomAccess {
    private final List<Map<String, Object>> entries;
    private final Runnable completion;
    private boolean complete;

    /** @param completion adds the exact fields to {@code entries}; runs once, before the first read. */
    LazyRouteTrace(List<Map<String, Object>> entries, Runnable completion) {
        this.entries = entries;
        this.completion = completion;
    }

    @Override public int size() { return entries.size(); }

    @Override public Map<String, Object> get(int index) {
        complete();
        return entries.get(index);
    }

    private synchronized void complete() {
        if (complete) return;
        completion.run();
        complete = true;
    }
}
