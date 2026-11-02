package edu.ipcmax.core.pcmax;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic query-level work and typed cap ledger. */
public final class PaceWorkLedger {
    /**
     * M_q v3 counts deterministic pivot admission, connector labels and joins,
     * candidate assembly and verification, replay and admitted temporal
     * composition, profile merging, candidate offers, affected-cell and
     * retention evaluation, fragment restriction/materialization, dominance,
     * and equality-root work.
     */
    public static final String ACCOUNTING_CONTRACT =
            "PACE-MQ-TOTAL-WORK-v3";
    private final PaceOptions options;
    private final AtomicLong queryWork = new AtomicLong();
    private final EnumMap<PaceWorkKind, AtomicLong> typedWork =
            new EnumMap<>(PaceWorkKind.class);
    private final AtomicLong connectorExpansions = new AtomicLong();
    private final AtomicLong connectorCapHits = new AtomicLong();
    private final AtomicLong breakpointCapHits = new AtomicLong();
    private final AtomicLong queryWorkCapHits = new AtomicLong();
    private final EnumSet<PaceCapKind> triggered =
            EnumSet.noneOf(PaceCapKind.class);
    private String firstCanonicalWorkItem = "";

    public PaceWorkLedger(PaceOptions options) {
        this.options = options;
        for (PaceWorkKind kind : PaceWorkKind.values()) {
            typedWork.put(kind, new AtomicLong());
        }
    }

    /**
     * Compatibility spelling for reserving one connector request before task
     * submission. Calls must occur in reducer order.
     */
    public synchronized boolean reserveQueryWork(String canonicalWorkItem) {
        return reserve(
                PaceWorkKind.CONNECTOR_REQUEST,
                canonicalWorkItem);
    }

    /**
     * Reserves one typed unit of real query work. The total and typed count are
     * advanced atomically only when the unit fits under {@code M_q}.
     */
    public synchronized boolean reserve(
            PaceWorkKind kind,
            String canonicalWorkItem) {
        return reserveUnits(kind, 1, canonicalWorkItem);
    }

    /**
     * Atomically reserves a deterministic cohort of identical work units.
     * Either the complete cohort fits or none of it is admitted.
     */
    public synchronized boolean reserveUnits(
            PaceWorkKind kind,
            long units,
            String canonicalWorkItem) {
        if (kind == null) {
            throw new IllegalArgumentException(
                    "work kind is required");
        }
        if (units < 0) {
            throw new IllegalArgumentException(
                    "work units cannot be negative");
        }
        if (units == 0) {
            return true;
        }
        long remaining = remainingQueryWork();
        if (units > remaining) {
            queryWorkCapHits.incrementAndGet();
            trigger(PaceCapKind.QUERY_WORK_M_Q, canonicalWorkItem);
            return false;
        }
        queryWork.addAndGet(units);
        typedWork.get(kind).addAndGet(units);
        return true;
    }

    public void addConnectorExpansions(long count) {
        connectorExpansions.addAndGet(count);
    }

    public synchronized void connectorCapReached(String workItem) {
        connectorCapHits.incrementAndGet();
        trigger(PaceCapKind.CONNECTOR_M_C, workItem);
    }

    public synchronized boolean acceptsBreakpoints(
            int exactBreakpointCount,
            String workItem) {
        if (exactBreakpointCount <= options.breakpointCapMb()) {
            return true;
        }
        breakpointCapHits.incrementAndGet();
        trigger(PaceCapKind.BREAKPOINT_M_B, workItem);
        return false;
    }

    public synchronized void emergencyFrontierGuard(String workItem) {
        trigger(PaceCapKind.EMERGENCY_FRONTIER_GUARD, workItem);
    }

    public long queryWork() {
        return queryWork.get();
    }

    /** Returns the unreserved M_q capacity without changing ledger state. */
    public long remainingQueryWork() {
        long cap = options.queryWorkCapMq();
        long used = queryWork.get();
        return cap == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : Math.max(0L, cap - used);
    }

    public long typedWork(PaceWorkKind kind) {
        AtomicLong counter = typedWork.get(kind);
        if (counter == null) {
            throw new IllegalArgumentException(
                    "unknown work kind: " + kind);
        }
        return counter.get();
    }

    public Map<PaceWorkKind, Long> typedWorkSnapshot() {
        EnumMap<PaceWorkKind, Long> snapshot =
                new EnumMap<>(PaceWorkKind.class);
        for (Map.Entry<PaceWorkKind, AtomicLong> entry :
                typedWork.entrySet()) {
            snapshot.put(
                    entry.getKey(), entry.getValue().get());
        }
        return Map.copyOf(snapshot);
    }

    public long connectorExpansions() {
        return connectorExpansions.get();
    }

    public long connectorCapHits() {
        return connectorCapHits.get();
    }

    public long breakpointCapHits() {
        return breakpointCapHits.get();
    }

    public long queryWorkCapHits() {
        return queryWorkCapHits.get();
    }

    public synchronized PaceCapStatus capStatus() {
        return new PaceCapStatus(
                Set.copyOf(triggered), firstCanonicalWorkItem);
    }

    private void trigger(PaceCapKind kind, String workItem) {
        triggered.add(kind);
        String canonical = workItem == null ? "" : workItem;
        if (firstCanonicalWorkItem.isEmpty()
                || (!canonical.isEmpty()
                    && canonical.compareTo(firstCanonicalWorkItem) < 0)) {
            firstCanonicalWorkItem = canonical;
        }
    }
}
