package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.SafeProfileDominance;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finalized PACE frontier compression: exact duplicate removal, cell-local
 * extension-safe dominance, and deterministic bounded retention for PACE-B.
 */
public final class FrontierCompressor {
    private FrontierCompressor() {
    }

    /**
     * Compresses a subproblem frontier without introducing a second frontier type.
     * PACE-X ignores {@code k}; PACE-B requires it to be positive and retains at most
     * {@code k} fragments on every temporal cell.
     */
    public static CandidateSet compress(
            TDGraph graph,
            CandidateSet candidateFrontier,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int subproblemSource,
            int subproblemDestination) {
        return compress(graph, candidateFrontier, subproblemDomain, budget, k, policy,
                subproblemSource, subproblemDestination, PaceFeatures.defaults());
    }

    /**
     * Normalizes and deduplicates the current retained fragments plus one offer.
     * This is shared by the incremental implementation and the batch oracle.
     */
    static List<CandidateProfile> normalizeCandidates(
            CandidateSet frontier,
            Domain subproblemDomain) {
        return normalizeAndDeduplicate(
                frontier, subproblemDomain);
    }

    /**
     * Re-evaluates one already-partitioned temporal cell without rebuilding the
     * complete frontier partition.
     */
    static CandidateSet retainPartitionCell(
            TDGraph graph,
            List<CandidateProfile> normalized,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int source,
            int destination,
            PaceFeatures features,
            DominanceMemo dominanceMemo,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem) {
        List<RetainedCellReference> fragments =
                retainPartitionCellReferences(
                        graph,
                        normalized,
                        cell,
                        subproblemDomain,
                        budget,
                        k,
                        policy,
                        source,
                        destination,
                        features,
                        dominanceMemo,
                        metrics,
                        ledger,
                        workItem);
        CandidateSet unmerged = fragmentsWithoutMerge(fragments);
        return features.adjacentMergeEnabled()
                ? mergeCandidateFragments(
                        unmerged.candidates(), metrics)
                : unmerged;
    }

    /**
     * Returns cell-local retention references without materializing restricted
     * profile fragments. The reference interval preserves exact endpoint
     * ownership; callers may carry it through unchanged cell splits and defer
     * restriction until a stitched or serialized frontier is requested.
     */
    static List<RetainedCellReference> retainPartitionCellReferences(
            TDGraph graph,
            List<CandidateProfile> normalized,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int source,
            int destination,
            PaceFeatures features,
            DominanceMemo dominanceMemo,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem) {
        double activeSample = cell.start() == cell.end()
                ? cell.start()
                : ProfileCellPartition.midpoint(cell);
        List<CandidateProfile> active = normalized.stream()
                .filter(candidate ->
                        candidate.domain().contains(activeSample))
                .sorted(CandidateSet.STABLE_ORDER)
                .toList();
        List<RetainedCellReference> references =
                new ArrayList<>();
        retainCellExactly(
                graph,
                normalized,
                active,
                cell,
                subproblemDomain,
                budget,
                k,
                policy,
                source,
                destination,
                features,
                dominanceMemo,
                metrics,
                ledger,
                workItem,
                references);
        return List.copyOf(references);
    }

    /** Merges adjacent compatible fragments without recomputing retention. */
    static CandidateSet mergeCandidateFragments(
            List<CandidateProfile> source,
            PaceExecutionMetrics metrics) {
        return mergeCandidateFragments(
                source, metrics, null, "");
    }

    /** Merges adjacent fragments and charges each created merged profile. */
    static CandidateSet mergeCandidateFragments(
            List<CandidateProfile> source,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem) {
        List<CandidateProfile> pieces =
                new ArrayList<>(source);
        pieces.sort(Comparator
                .comparing(
                        CandidateProfile::stablePathId,
                        PathPointer.STABLE_PATH_ORDER)
                .thenComparingDouble(candidate ->
                        candidate.domain().intervals().get(0).start())
                .thenComparing(candidate ->
                        candidate.domain().toString()));
        List<CandidateProfile> merged = new ArrayList<>();
        long mergeRuns = 0;
        long maximumRun = 0;
        metrics.addCounter(
                "fragment_merge_input_fragments",
                pieces.size());
        for (int start = 0; start < pieces.size();) {
            int end = start + 1;
            while (end < pieces.size()
                    && canMergeAdjacent(
                            pieces.get(end - 1),
                            pieces.get(end))) {
                end++;
            }
            int mergeCount = end - start - 1;
            if (mergeCount == 0) {
                merged.add(pieces.get(start));
            } else {
                mergeRuns++;
                maximumRun = Math.max(
                        maximumRun, end - start);
                if (ledger != null) {
                    if (!ledger.reserve(
                            PaceWorkKind.PROFILE_MERGE,
                            workItem + ":merge-run:"
                                    + merged.size())) {
                        throw PaceWorkLimitReachedException.INSTANCE;
                    }
                    for (int offset = 0;
                            offset < mergeCount;
                            offset++) {
                        if (!ledger.reserve(
                                PaceWorkKind.FRAGMENT_MATERIALIZATION,
                                workItem + ":merge:"
                                        + (merged.size() + offset))) {
                            throw PaceWorkLimitReachedException.INSTANCE;
                        }
                    }
                }
                metrics.addCounter(
                        "fragments_merged", mergeCount);
                if (ledger != null) {
                    metrics.addCounter(
                            "fragment_materializations",
                            mergeCount);
                }
                merged.add(mergeCompatibleRun(
                        pieces.subList(start, end)));
            }
            start = end;
        }
        metrics.addCounter(
                "fragment_merge_runs", mergeRuns);
        metrics.observeCounter(
                "fragment_merge_maximum_run",
                maximumRun);
        CandidateSet result = new CandidateSet();
        merged.stream()
                .sorted(CandidateSet.STABLE_ORDER)
                .forEach(result::add);
        return removeExactDuplicates(result);
    }

    /** Compresses with explicit experiment feature switches. */
    public static CandidateSet compress(
            TDGraph graph,
            CandidateSet candidateFrontier,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int subproblemSource,
            int subproblemDestination,
            PaceFeatures features) {
        return compress(
                graph,
                candidateFrontier,
                subproblemDomain,
                budget,
                k,
                policy,
                subproblemSource,
                subproblemDestination,
                features,
                PaceExecutionMetrics.none());
    }

    /** Batch compression oracle with explicit work instrumentation. */
    public static CandidateSet compress(
            TDGraph graph,
            CandidateSet candidateFrontier,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int subproblemSource,
            int subproblemDestination,
            PaceFeatures features,
            PaceExecutionMetrics metrics) {
        if (graph == null || candidateFrontier == null || subproblemDomain == null || policy == null
                || features == null) {
            throw new IllegalArgumentException("graph, frontier, domain, and policy are required");
        }
        if (budget < 0.0 || !Double.isFinite(budget)) {
            throw new IllegalArgumentException("budget must be finite and non-negative");
        }
        if (policy == PaceExecutionPolicy.PACE_B && k < 1) {
            throw new IllegalArgumentException("PACE-B requires K >= 1");
        }
        if (subproblemDomain.isEmpty() || candidateFrontier.isEmpty()) {
            return new CandidateSet();
        }

        List<CandidateProfile> normalized = normalizeAndDeduplicate(candidateFrontier, subproblemDomain);
        if (normalized.isEmpty()) {
            return new CandidateSet();
        }

        if (!features.compressionEnabled()) {
            CandidateSet exactDuplicatesOnly = new CandidateSet();
            normalized.forEach(exactDuplicatesOnly::add);
            return exactDuplicatesOnly;
        }

        if (policy == PaceExecutionPolicy.PACE_B && !features.perCellRetentionEnabled()) {
            DominanceMemo dominanceMemo =
                    new DominanceMemo();
            Domain.Interval whole = new Domain.Interval(
                    subproblemDomain.intervals().get(0).start(),
                    subproblemDomain.intervals().get(subproblemDomain.intervals().size() - 1).end());
            List<CandidateProfile> safe = features.safeDominanceEnabled()
                    ? safePrune(
                            graph, normalized, whole,
                            subproblemSource, subproblemDestination,
                            dominanceMemo, metrics, null, "")
                    : normalized;
            List<CandidateProfile> selected = boundedRetainMeasured(
                    graph, safe, whole, subproblemDomain,
                    budget, k, subproblemSource, subproblemDestination,
                    features.representativeRetentionEnabled(), metrics,
                    null, "");
            CandidateSet global = new CandidateSet();
            selected.forEach(global::add);
            return global;
        }

        List<Domain.Interval> cells = ProfileCellPartition.cells(
                subproblemDomain, normalized, true, metrics);
        DominanceMemo dominanceMemo =
                new DominanceMemo();
        List<RetainedCellReference> retained =
                new ArrayList<>();
        for (Domain.Interval cell : cells) {
            double activeSample = cell.start() == cell.end()
                    ? cell.start()
                    : ProfileCellPartition.midpoint(cell);
            List<CandidateProfile> active = normalized.stream()
                    .filter(candidate -> candidate.domain().contains(activeSample))
                    .sorted(CandidateSet.STABLE_ORDER)
                    .toList();
            retainCellExactly(
                    graph,
                    normalized,
                    active,
                    cell,
                    subproblemDomain,
                    budget,
                    k,
                    policy,
                    subproblemSource,
                    subproblemDestination,
                    features,
                    dominanceMemo,
                    metrics,
                    null,
                    "",
                    retained);
        }
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FRAGMENT_MERGE)) {
            CandidateSet result = features.adjacentMergeEnabled()
                    ? mergeAdjacentFragments(retained)
                    : fragmentsWithoutMerge(retained);
            result.setTemporalCells(cells);
            metrics.addCounter(
                    "retained_fragments", result.size());
            metrics.addCounter(
                    "dropped_fragments",
                    Math.max(0, normalized.size() - result.size()));
            return result;
        }
    }

    private static void retainCellExactly(
            TDGraph graph,
            List<CandidateProfile> normalized,
            List<CandidateProfile> active,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int source,
            int destination,
            PaceFeatures features,
            DominanceMemo dominanceMemo,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem,
            List<RetainedCellReference> output) {
        if (cell.start() == cell.end()) {
            for (CandidateProfile candidate : retainAtPoint(
                    graph, active, cell.start(), subproblemDomain, budget, k, policy, source, destination,
                    features, dominanceMemo, metrics, ledger, workItem)) {
                output.add(new RetainedCellReference(
                        candidate, cell));
            }
            return;
        }

        Domain.Interval interiorCell = new Domain.Interval(
                cell.start(), cell.end(), false, false);
        List<CandidateProfile> safelyRetained = features.safeDominanceEnabled()
                ? safePrune(
                        graph, active, interiorCell,
                        source, destination, dominanceMemo,
                        metrics, ledger, workItem)
                : active;
        List<CandidateProfile> interior = policy == PaceExecutionPolicy.PACE_B
                ? boundedRetainMeasured(
                graph, safelyRetained, interiorCell, subproblemDomain, budget, k, source, destination,
                features.representativeRetentionEnabled(), metrics,
                ledger, workItem + ":interior")
                : safelyRetained;
        List<CandidateProfile> atStart = cell.startInclusive()
                ? retainAtPoint(
                graph,
                activeAtPoint(normalized, cell.start()),
                cell.start(),
                subproblemDomain,
                budget,
                k,
                policy,
                source,
                destination,
                features,
                dominanceMemo,
                metrics,
                ledger,
                workItem)
                : List.of();
        List<CandidateProfile> atEnd = cell.endInclusive()
                ? retainAtPoint(
                graph,
                activeAtPoint(normalized, cell.end()),
                cell.end(),
                subproblemDomain,
                budget,
                k,
                policy,
                source,
                destination,
                features,
                dominanceMemo,
                metrics,
                ledger,
                workItem)
                : List.of();

        Set<CandidateProfile> handled = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (CandidateProfile candidate : interior) {
            boolean ownsStart = containsIdentity(atStart, candidate);
            boolean ownsEnd = containsIdentity(atEnd, candidate);
            output.add(new RetainedCellReference(
                    candidate, new Domain.Interval(
                    cell.start(),
                    cell.end(),
                    cell.startInclusive() && ownsStart,
                    cell.endInclusive() && ownsEnd)));
            handled.add(candidate);
        }
        for (CandidateProfile candidate : atStart) {
            if (!handled.contains(candidate)) {
                output.add(new RetainedCellReference(
                        candidate,
                        new Domain.Interval(
                                cell.start(), cell.start())));
            }
        }
        for (CandidateProfile candidate : atEnd) {
            if (!handled.contains(candidate)) {
                output.add(new RetainedCellReference(
                        candidate,
                        new Domain.Interval(
                                cell.end(), cell.end())));
            }
        }
    }

    private static List<CandidateProfile> retainAtPoint(
            TDGraph graph,
            List<CandidateProfile> active,
            double point,
            Domain subproblemDomain,
            double budget,
            int k,
            PaceExecutionPolicy policy,
            int source,
            int destination,
            PaceFeatures features,
            DominanceMemo dominanceMemo,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem) {
        if (active.isEmpty() || policy == PaceExecutionPolicy.PACE_X) {
            if (active.isEmpty()) {
                return active;
            }
        }
        Domain.Interval singleton = new Domain.Interval(point, point);
        List<CandidateProfile> safelyRetained = features.safeDominanceEnabled()
                ? safePrune(
                        graph, active, singleton,
                        source, destination, dominanceMemo,
                        metrics, ledger, workItem)
                : active;
        return policy == PaceExecutionPolicy.PACE_B
                ? boundedRetainMeasured(
                graph, safelyRetained, singleton, subproblemDomain, budget, k, source, destination,
                features.representativeRetentionEnabled(), metrics,
                ledger,
                workItem + ":point:"
                        + Domain.canonicalTick(point))
                : safelyRetained;
    }

    private static List<CandidateProfile> activeAtPoint(
            List<CandidateProfile> candidates,
            double point) {
        return candidates.stream()
                .filter(candidate -> candidate.domain().contains(point))
                .sorted(CandidateSet.STABLE_ORDER)
                .toList();
    }

    private static boolean containsIdentity(List<CandidateProfile> candidates, CandidateProfile sought) {
        for (CandidateProfile candidate : candidates) {
            if (candidate == sought) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes only repeated representations of the same path, domain, arrival, and score.
     */
    public static CandidateSet removeExactDuplicates(CandidateSet frontier) {
        CandidateSet result = new CandidateSet();
        List<CandidateProfile> unique = new ArrayList<>();
        for (CandidateProfile candidate : frontier.candidates().stream().sorted(CandidateSet.STABLE_ORDER).toList()) {
            boolean duplicate = unique.stream().anyMatch(existing -> sameRepresentation(existing, candidate));
            if (!duplicate) {
                unique.add(candidate);
                result.add(candidate);
            }
        }
        return result;
    }

    private static List<CandidateProfile> normalizeAndDeduplicate(
            CandidateSet frontier,
            Domain subproblemDomain) {
        CandidateSet restricted = new CandidateSet();
        for (CandidateProfile candidate : frontier.candidates()) {
            Domain domain = candidate.domain().intersection(subproblemDomain);
            if (!domain.isEmpty()) {
                restricted.add(candidate.domain().equals(domain) ? candidate : candidate.restrict(domain));
            }
        }
        return removeExactDuplicates(restricted).candidates();
    }

    private static boolean sameRepresentation(CandidateProfile left, CandidateProfile right) {
        if (!left.stablePathId().equals(right.stablePathId())
                || !left.domain().equals(right.domain())
                || left.explicitAnchorCount()
                        != right.explicitAnchorCount()
                || !left.usedPivotArcIds().equals(
                        right.usedPivotArcIds())) {
            return false;
        }
        for (Domain.Interval component : left.domain().intervals()) {
            if (component.end() <= component.start()) {
                double point = component.start();
                if (!Domain.sameTime(left.arrivalProfile().valueAt(point), right.arrivalProfile().valueAt(point))
                        || left.scoreProfile().valueAt(point) != right.scoreProfile().valueAt(point)) {
                    return false;
                }
                continue;
            }
            List<Domain.Interval> cells = ProfileCellPartition.cells(
                    Domain.of(component), List.of(left, right), false);
            for (Domain.Interval cell : cells) {
                if (!SafeProfileDominance.sameArrival(left, right, cell)
                        || !SafeProfileDominance.sameScore(left, right, cell)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<CandidateProfile> safePrune(
            TDGraph graph,
            List<CandidateProfile> active,
            Domain.Interval cell,
            int source,
            int destination,
            DominanceMemo dominanceMemo,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem) {
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.DOMINANCE)) {
            DominancePlan plan = dominanceMemo.plan(
                    graph, active, source, destination);
            metrics.addCounter(
                    "dominance_structural_rejections",
                    plan.structuralRejections());
            List<CandidateProfile> retained = new ArrayList<>();
            for (int dominatedIndex = 0;
                    dominatedIndex < active.size();
                    dominatedIndex++) {
                CandidateProfile dominated = active.get(dominatedIndex);
                boolean isDominated = false;
                for (int dominatorIndex :
                        plan.eligibleDominators()[
                                dominatedIndex]) {
                    CandidateProfile dominator =
                            active.get(dominatorIndex);
                    /*
                     * Exact safe dominance requires identical arrival at every
                     * point and a no-lower score. A deterministic witness point
                     * is therefore a necessary (not sufficient) compatibility
                     * signature and can reject the overwhelmingly common
                     * incompatible pair without an exact profile comparison.
                     */
                    double witness =
                            ProfileCellPartition.midpoint(cell);
                    if (!Domain.sameTime(
                            dominator.arrivalProfile()
                                    .valueAtClosure(witness),
                            dominated.arrivalProfile()
                                    .valueAtClosure(witness))
                            || dominator.scoreProfile()
                                    .valueAtClosure(witness)
                            < dominated.scoreProfile()
                                    .valueAtClosure(witness)) {
                        metrics.increment(
                                "dominance_arrival_signature_rejections");
                        continue;
                    }
                    reserveDominance(
                            ledger,
                            workItem,
                            dominatedIndex,
                            dominatorIndex,
                            "forward");
                    metrics.increment("dominance_comparisons");
                    boolean forward = SafeProfileDominance.dominates(
                            dominator,
                            dominated,
                            cell,
                            plan.internalVertices().get(
                                    dominatorIndex),
                            plan.internalVertices().get(
                                    dominatedIndex));
                    if (!forward) {
                        continue;
                    }
                    boolean reverse = false;
                    if (plan.equivalentSignatures()[
                            dominatorIndex][dominatedIndex]) {
                        reserveDominance(
                                ledger,
                                workItem,
                                dominatedIndex,
                                dominatorIndex,
                                "reverse");
                        metrics.increment(
                                "dominance_comparisons");
                        reverse = SafeProfileDominance.dominates(
                                dominated,
                                dominator,
                                cell,
                                plan.internalVertices().get(
                                        dominatedIndex),
                                plan.internalVertices().get(
                                        dominatorIndex));
                    }
                    if (!reverse || dominatorIndex < dominatedIndex) {
                        isDominated = true;
                        break;
                    }
                }
                if (!isDominated) {
                    retained.add(dominated);
                }
            }
            return retained;
        }
    }

    /**
     * Query/frontier-local structural plan cache. Temporal dominance remains
     * cell-specific; only the necessary visited/pivot subset relation is
     * cached.
     */
    static final class DominanceMemo {
        private static final int MAXIMUM_ENTRIES = 4_096;
        private final Map<CandidateStructure, Set<Integer>>
                internalVertices = boundedMap();
        private final Map<PlanKey, DominancePlan> plans =
                boundedMap();

        private static <K, V> Map<K, V> boundedMap() {
            return new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<K, V> eldest) {
                    return size() > MAXIMUM_ENTRIES;
                }
            };
        }

        synchronized void clear() {
            internalVertices.clear();
            plans.clear();
        }

        synchronized DominancePlan plan(
                TDGraph graph,
                List<CandidateProfile> active,
                int source,
                int destination) {
            List<CandidateStructure> structures =
                    active.stream()
                            .map(CandidateStructure::of)
                            .toList();
            PlanKey key = new PlanKey(structures);
            return plans.computeIfAbsent(
                    key,
                    ignored -> buildPlan(
                            graph,
                            active,
                            structures,
                            source,
                            destination));
        }

        private DominancePlan buildPlan(
                TDGraph graph,
                List<CandidateProfile> active,
                List<CandidateStructure> structures,
                int source,
                int destination) {
            List<Set<Integer>> omega =
                    new ArrayList<>(active.size());
            for (int index = 0;
                    index < active.size();
                    index++) {
                CandidateProfile candidate =
                        active.get(index);
                CandidateStructure structure =
                        structures.get(index);
                omega.add(internalVertices.computeIfAbsent(
                        structure,
                        ignored -> candidate.internalVertices(
                                graph, source, destination)));
            }
            int[][] eligible = new int[active.size()][];
            boolean[][] equivalent =
                    new boolean[active.size()][active.size()];
            Map<CompatibilityBucket, List<Integer>> buckets =
                    new TreeMap<>(
                            Comparator.comparingInt(
                                            CompatibilityBucket::omegaSize)
                                    .thenComparingInt(
                                            CompatibilityBucket::pivotSize)
                                    .thenComparing(
                                            CompatibilityBucket
                                                    ::arrivalSignature));
            for (int index = 0;
                    index < active.size();
                    index++) {
                CompatibilityBucket bucket =
                        new CompatibilityBucket(
                                omega.get(index).size(),
                                structures.get(index)
                                        .usedPivotArcIds().size(),
                                active.get(index).arrivalProfile()
                                        .fingerprint());
                buckets.computeIfAbsent(
                        bucket,
                        ignored -> new ArrayList<>()).add(index);
            }
            long rejected = 0;
            for (int dominated = 0;
                    dominated < active.size();
                    dominated++) {
                List<Integer> indices =
                        new ArrayList<>();
                List<Integer> compatibleBucketMembers =
                        new ArrayList<>();
                int dominatedOmega =
                        omega.get(dominated).size();
                int dominatedPivots =
                        structures.get(dominated)
                                .usedPivotArcIds().size();
                for (Map.Entry<CompatibilityBucket,
                        List<Integer>> entry : buckets.entrySet()) {
                    CompatibilityBucket bucket = entry.getKey();
                    if (bucket.omegaSize() <= dominatedOmega
                            && bucket.pivotSize()
                            <= dominatedPivots) {
                        compatibleBucketMembers.addAll(
                                entry.getValue());
                    } else {
                        rejected += entry.getValue().size();
                    }
                }
                compatibleBucketMembers.sort(
                        Integer::compareTo);
                for (int dominator :
                        compatibleBucketMembers) {
                    if (dominator == dominated) {
                        continue;
                    }
                    boolean structurallyEligible =
                            omega.get(dominated).containsAll(
                                    omega.get(dominator))
                            && structures.get(dominated)
                                    .usedPivotArcIds()
                                    .containsAll(
                                            structures.get(dominator)
                                                    .usedPivotArcIds());
                    if (structurallyEligible) {
                        indices.add(dominator);
                        equivalent[dominator][dominated] =
                                omega.get(dominator).equals(
                                        omega.get(dominated))
                                && structures.get(dominator)
                                        .usedPivotArcIds().equals(
                                                structures.get(dominated)
                                                        .usedPivotArcIds());
                    } else {
                        rejected++;
                    }
                }
                eligible[dominated] = indices.stream()
                        .mapToInt(Integer::intValue)
                        .toArray();
            }
            return new DominancePlan(
                    List.copyOf(omega),
                    eligible,
                    equivalent,
                    rejected);
        }
    }

    private record CandidateStructure(
            List<Integer> stablePathId,
            Set<Integer> usedPivotArcIds) {
        static CandidateStructure of(
                CandidateProfile candidate) {
            return new CandidateStructure(
                    candidate.stablePathId(),
                    candidate.usedPivotArcIds());
        }

        CandidateStructure {
            stablePathId = List.copyOf(stablePathId);
            usedPivotArcIds = Set.copyOf(
                    usedPivotArcIds);
        }
    }

    private record PlanKey(
            List<CandidateStructure> structures) {
        PlanKey {
            structures = List.copyOf(structures);
        }
    }

    /**
     * Endpoint is constant for one IncrementalFrontier. These remaining fields
     * are the safe structural/arrival buckets reused by its dominance memo.
     */
    private record CompatibilityBucket(
            int omegaSize,
            int pivotSize,
            String arrivalSignature) {
    }

    private record DominancePlan(
            List<Set<Integer>> internalVertices,
            int[][] eligibleDominators,
            boolean[][] equivalentSignatures,
            long structuralRejections) {
    }

    private static void reserveDominance(
            PaceWorkLedger ledger,
            String workItem,
            int dominatedIndex,
            int dominatorIndex,
            String direction) {
        if (ledger != null
                && !ledger.reserve(
                        PaceWorkKind.DOMINANCE_CHECK,
                        workItem + ":dominance:"
                                + dominatedIndex + ":"
                                + dominatorIndex + ":"
                                + direction)) {
            throw PaceWorkLimitReachedException.INSTANCE;
        }
    }

    private static List<CandidateProfile> boundedRetainMeasured(
            TDGraph graph,
            List<CandidateProfile> candidates,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            int source,
            int destination,
            boolean representativesEnabled,
            PaceExecutionMetrics metrics,
            PaceWorkLedger ledger,
            String workItem) {
        if (ledger != null
                && !ledger.reserve(
                        PaceWorkKind.RETENTION_EVALUATION,
                        workItem + ":retention")) {
            throw PaceWorkLimitReachedException.INSTANCE;
        }
        try (PaceExecutionMetrics.Timer ignored = metrics.phase(
                PaceExecutionMetrics.FRONTIER_RETENTION)) {
            List<CandidateProfile> retained = boundedRetain(
                    graph, candidates, cell, subproblemDomain,
                    budget, k, source, destination,
                    representativesEnabled);
            metrics.addCounter(
                    "frontier_retention_evaluations", 1);
            metrics.addCounter(
                    "retained_fragments", retained.size());
            metrics.addCounter(
                    "dropped_fragments",
                    Math.max(0, candidates.size() - retained.size()));
            return retained;
        }
    }

    private static List<CandidateProfile> boundedRetain(
            TDGraph graph,
            List<CandidateProfile> candidates,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int k,
            int source,
            int destination,
            boolean representativesEnabled) {
        if (candidates.size() <= k) {
            return candidates;
        }

        Map<CandidateProfile, Metrics> metrics = new IdentityHashMap<>();
        for (CandidateProfile candidate : candidates) {
            metrics.put(candidate, metrics(
                    graph, candidate, cell, subproblemDomain, budget, source, destination));
        }

        List<CandidateProfile> selected = new ArrayList<>(k);
        if (representativesEnabled) {
            addIfNew(selected, minimum(candidates, championComparator(cell)));
            if (selected.size() == k) {
                return selected.stream()
                        .sorted(CandidateSet.STABLE_ORDER).toList();
            }
            addIfNew(selected, minimum(candidates, metricComparator(metrics, MetricOrder.EARLIEST)));
            if (selected.size() == k) {
                return selected.stream()
                        .sorted(CandidateSet.STABLE_ORDER).toList();
            }
            addIfNew(selected, minimum(candidates, metricComparator(metrics, MetricOrder.COVERAGE)));
            if (selected.size() == k) {
                return selected.stream()
                        .sorted(CandidateSet.STABLE_ORDER).toList();
            }
            addIfNew(selected, minimum(candidates, metricComparator(metrics, MetricOrder.LEAST_RESTRICTIVE)));
        }
        if (selected.size() < k) {
            List<CandidateProfile> fill = candidates.stream()
                    .sorted(metricComparator(metrics, MetricOrder.FILL))
                    .toList();
            for (CandidateProfile candidate : fill) {
                addIfNew(selected, candidate);
                if (selected.size() == k) {
                    break;
                }
            }
        }
        return selected.stream().sorted(CandidateSet.STABLE_ORDER).toList();
    }

    private static Comparator<CandidateProfile> championComparator(Domain.Interval cell) {
        double sample = ProfileCellPartition.midpoint(cell);
        return (left, right) -> {
            int comparison = Integer.compare(
                    right.scoreProfile().valueAtClosure(sample),
                    left.scoreProfile().valueAtClosure(sample));
            if (comparison != 0) {
                return comparison;
            }
            comparison = Double.compare(
                    left.arrivalProfile().valueAtClosure(sample)
                            - sample,
                    right.arrivalProfile().valueAtClosure(sample)
                            - sample);
            if (comparison != 0) {
                return comparison;
            }
            comparison = Integer.compare(left.edgeCount(), right.edgeCount());
            if (comparison != 0) {
                return comparison;
            }
            comparison = PathPointer.STABLE_PATH_ORDER.compare(left.stablePathId(), right.stablePathId());
            return comparison != 0 ? comparison : canonicalProfileCompare(left, right);
        };
    }

    private static Comparator<CandidateProfile> metricComparator(
            Map<CandidateProfile, Metrics> metrics,
            MetricOrder order) {
        return (left, right) -> {
            Metrics l = metrics.get(left);
            Metrics r = metrics.get(right);
            int comparison;
            if (order == MetricOrder.EARLIEST) {
                comparison = Double.compare(l.averageArrival(), r.averageArrival());
                if (comparison == 0) {
                    comparison = -Double.compare(l.averageScore(), r.averageScore());
                }
            } else if (order == MetricOrder.COVERAGE) {
                comparison = -Double.compare(
                        l.temporalCoverage(), r.temporalCoverage());
                if (comparison == 0) {
                    comparison = -Double.compare(
                            l.averageScore(), r.averageScore());
                }
                if (comparison == 0) {
                    comparison = Double.compare(
                            l.averageArrival(), r.averageArrival());
                }
            } else if (order == MetricOrder.LEAST_RESTRICTIVE) {
                comparison = Integer.compare(l.omegaSize(), r.omegaSize());
                if (comparison == 0) {
                    comparison = Double.compare(l.averageArrival(), r.averageArrival());
                }
                if (comparison == 0) {
                    comparison = -Double.compare(l.averageScore(), r.averageScore());
                }
            } else {
                comparison = -Double.compare(l.averageScore(), r.averageScore());
                if (comparison == 0) {
                    comparison = Double.compare(l.averageArrival(), r.averageArrival());
                }
                if (comparison == 0) {
                    comparison = -Double.compare(l.minimumSlack(), r.minimumSlack());
                }
                if (comparison == 0) {
                    comparison = -Double.compare(l.temporalCoverage(), r.temporalCoverage());
                }
                if (comparison == 0) {
                    comparison = Integer.compare(l.omegaSize(), r.omegaSize());
                }
            }
            if (comparison == 0) {
                comparison = Integer.compare(left.edgeCount(), right.edgeCount());
            }
            if (comparison == 0) {
                comparison = PathPointer.STABLE_PATH_ORDER.compare(left.stablePathId(), right.stablePathId());
            }
            return comparison != 0 ? comparison : canonicalProfileCompare(left, right);
        };
    }

    private static int canonicalProfileCompare(CandidateProfile left, CandidateProfile right) {
        int comparison = left.domain().toString().compareTo(right.domain().toString());
        if (comparison == 0) {
            comparison = left.arrivalProfile().fingerprint().compareTo(right.arrivalProfile().fingerprint());
        }
        if (comparison == 0) {
            comparison = left.scoreProfile().fingerprint().compareTo(right.scoreProfile().fingerprint());
        }
        return comparison;
    }

    private static CandidateProfile minimum(
            List<CandidateProfile> candidates,
            Comparator<CandidateProfile> comparator) {
        return candidates.stream().min(comparator).orElseThrow();
    }

    private static void addIfNew(List<CandidateProfile> selected, CandidateProfile candidate) {
        for (CandidateProfile existing : selected) {
            if (existing == candidate) {
                return;
            }
        }
        selected.add(candidate);
    }

    private static Metrics metrics(
            TDGraph graph,
            CandidateProfile candidate,
            Domain.Interval cell,
            Domain subproblemDomain,
            double budget,
            int source,
            int destination) {
        double duration = cell.end() - cell.start();
        double averageArrival;
        double averageScore;
        if (duration <= 0) {
            averageArrival = candidate.arrivalProfile()
                    .valueAtClosure(cell.start());
            averageScore = candidate.scoreProfile()
                    .valueAtClosure(cell.start());
        } else {
            averageArrival = integrateArrival(candidate.arrivalProfile(), cell) / duration;
            averageScore = integrateScore(candidate.scoreProfile(), cell) / duration;
        }
        double minimumSlack = budget - maximumTravelTime(candidate.arrivalProfile(), cell);
        double denominator = ProfileCellPartition.measure(subproblemDomain);
        double temporalCoverage = denominator <= 0
                ? 1.0
                : ProfileCellPartition.measure(candidate.domain()) / denominator;
        int omegaSize = candidate.internalVertices(graph, source, destination).size();
        return new Metrics(averageScore, averageArrival, minimumSlack, temporalCoverage, omegaSize);
    }

    private static double integrateArrival(TimeProfile profile, Domain.Interval cell) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(cell.start());
        cuts.add(cell.end());
        for (TimeProfile.Breakpoint breakpoint : profile.breakpoints()) {
            if (breakpoint.minute() > cell.start()
                    && breakpoint.minute() < cell.end()) {
                cuts.add(breakpoint.minute());
            }
        }
        List<Double> sorted = ProfileCellPartition.uniqueSorted(cuts);
        double integral = 0.0;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            double start = sorted.get(i);
            double end = sorted.get(i + 1);
            integral += (profile.valueAtClosure(start) + profile.valueAtClosure(end))
                    * (end - start) / 2.0;
        }
        return integral;
    }

    private static double integrateScore(ScoreProfile profile, Domain.Interval cell) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(cell.start());
        cuts.add(cell.end());
        for (double breakpoint : profile.breakpoints()) {
            if (breakpoint > cell.start() && breakpoint < cell.end()) {
                cuts.add(breakpoint);
            }
        }
        List<Double> sorted = ProfileCellPartition.uniqueSorted(cuts);
        double integral = 0.0;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            double start = sorted.get(i);
            double end = sorted.get(i + 1);
            integral += profile.valueAtClosure(start)
                    * (end - start);
        }
        return integral;
    }

    private static double maximumTravelTime(TimeProfile profile, Domain.Interval cell) {
        List<Double> points = new ArrayList<>();
        points.add(cell.start());
        points.add(cell.end());
        for (TimeProfile.Breakpoint breakpoint : profile.breakpoints()) {
            if (breakpoint.minute() > cell.start()
                    && breakpoint.minute() < cell.end()) {
                points.add(breakpoint.minute());
            }
        }
        double maximum = Double.NEGATIVE_INFINITY;
        for (double point : points) {
            maximum = Math.max(maximum, profile.valueAtClosure(point) - point);
        }
        return maximum;
    }

    private static CandidateSet mergeAdjacentFragments(
            List<RetainedCellReference> fragments) {
        List<CandidateProfile> pieces = new ArrayList<>();
        for (RetainedCellReference fragment : fragments) {
            pieces.add(restrictAndMarkCompressed(fragment.source(), Domain.of(fragment.cell())));
        }
        pieces.sort(Comparator
                .comparing(CandidateProfile::stablePathId, PathPointer.STABLE_PATH_ORDER)
                .thenComparingDouble(candidate -> candidate.domain().intervals().get(0).start())
                .thenComparing(candidate -> candidate.domain().toString()));

        List<CandidateProfile> merged = new ArrayList<>();
        for (CandidateProfile piece : pieces) {
            if (!merged.isEmpty()) {
                int lastIndex = merged.size() - 1;
                CandidateProfile joined = mergeAdjacentCompatible(merged.get(lastIndex), piece);
                if (joined != null) {
                    merged.set(lastIndex, joined);
                    continue;
                }
            }
            merged.add(piece);
        }
        CandidateSet result = new CandidateSet();
        merged.stream().sorted(CandidateSet.STABLE_ORDER).forEach(result::add);
        return removeExactDuplicates(result);
    }

    private static CandidateSet fragmentsWithoutMerge(
            List<RetainedCellReference> fragments) {
        CandidateSet result = new CandidateSet();
        for (RetainedCellReference fragment : fragments) {
            result.add(restrictAndMarkCompressed(fragment.source(), Domain.of(fragment.cell())));
        }
        return removeExactDuplicates(result);
    }

    /**
     * Merges adjacent compatible restrictions of the same stable path, or returns
     * {@code null} when the fragments must remain separate.
     */
    static CandidateProfile mergeAdjacentCompatible(
            CandidateProfile first,
            CandidateProfile second) {
        return mergeAdjacentCompatible(
                first, second, null, "");
    }

    private static CandidateProfile mergeAdjacentCompatible(
            CandidateProfile first,
            CandidateProfile second,
            PaceWorkLedger ledger,
            String workItem) {
        if (!canMergeAdjacent(first, second)) {
            return null;
        }
        if (ledger != null
                && !ledger.reserve(
                        PaceWorkKind.FRAGMENT_MATERIALIZATION,
                        workItem)) {
            throw PaceWorkLimitReachedException.INSTANCE;
        }
        return mergeCompatibleRun(List.of(first, second));
    }

    private static boolean canMergeAdjacent(
            CandidateProfile first,
            CandidateProfile second) {
        if (!first.stablePathId().equals(second.stablePathId())
                || first.explicitAnchorCount() != second.explicitAnchorCount()
                || !first.usedPivotArcIds().equals(
                        second.usedPivotArcIds())
                || !sameProfileLineage(
                        first.arrivalProfile().fingerprint(),
                        second.arrivalProfile().fingerprint())
                || !sameProfileLineage(
                        first.scoreProfile().fingerprint(),
                        second.scoreProfile().fingerprint())
                || first.domain().intervals().size() != 1
                || second.domain().intervals().size() != 1) {
            return false;
        }
        CandidateProfile left = first;
        CandidateProfile right = second;
        if (left.domain().intervals().get(0).start() > right.domain().intervals().get(0).start()) {
            left = second;
            right = first;
        }
        Domain.Interval leftInterval = left.domain().intervals().get(0);
        Domain.Interval rightInterval = right.domain().intervals().get(0);
        if (!Domain.sameTime(leftInterval.end(), rightInterval.start())
                || !(leftInterval.endInclusive() || rightInterval.startInclusive())) {
            return false;
        }
        double boundary = leftInterval.end();
        if (!Domain.sameTime(
                left.arrivalProfile().valueAtClosure(boundary),
                right.arrivalProfile().valueAtClosure(boundary))) {
            return false;
        }
        if (leftInterval.endInclusive() && rightInterval.startInclusive()
                && left.scoreProfile().valueAt(boundary) != right.scoreProfile().valueAt(boundary)) {
            return false;
        }
        return true;
    }

    /**
     * Materializes one maximal compatible run in linear time. The historical
     * pairwise implementation repeatedly copied the whole accumulated profile,
     * making a run of n temporal cells quadratic.
     */
    private static CandidateProfile mergeCompatibleRun(
            List<CandidateProfile> run) {
        CandidateProfile first = run.get(0);
        Domain union = Domain.empty();
        List<TimeProfile.Breakpoint> arrivalPoints =
                new ArrayList<>();
        int pivotId = first.pivotId();
        for (CandidateProfile piece : run) {
            union = union.union(piece.domain());
            for (TimeProfile.Breakpoint point :
                    piece.arrivalProfile().breakpoints()) {
                if (!arrivalPoints.isEmpty()
                        && Domain.sameTime(
                                arrivalPoints.get(
                                        arrivalPoints.size() - 1)
                                        .minute(),
                                point.minute())) {
                    if (!Domain.sameTime(
                            arrivalPoints.get(
                                    arrivalPoints.size() - 1)
                                    .value(),
                            point.value())) {
                        throw new IllegalArgumentException(
                                "incompatible arrival fragments "
                                        + "for one path");
                    }
                    continue;
                }
                arrivalPoints.add(point);
            }
            if (piece.pivotId() != pivotId) {
                pivotId = -1;
            }
        }
        TimeProfile arrival = TimeProfile.piecewise(
                union,
                arrivalPoints,
                profileLineage(
                        first.arrivalProfile().fingerprint())
                        + "|restrict:" + union.intervals());
        ScoreProfile score = mergeScoreProfiles(run, union);
        return new CandidateProfile(
                union,
                arrival,
                score,
                first.pathPointer(),
                first.explicitAnchorCount(),
                pivotId,
                true,
                first.usedPivotArcIds());
    }

    private static TimeProfile mergeArrivalProfiles(
            CandidateProfile left,
            CandidateProfile right,
            Domain union) {
        List<TimeProfile.Breakpoint> points = new ArrayList<>();
        points.addAll(left.arrivalProfile().breakpoints());
        points.addAll(right.arrivalProfile().breakpoints());
        points.sort(Comparator.comparingDouble(TimeProfile.Breakpoint::minute));
        List<TimeProfile.Breakpoint> unique = new ArrayList<>();
        for (TimeProfile.Breakpoint point : points) {
            if (!unique.isEmpty()
                    && Domain.sameTime(unique.get(unique.size() - 1).minute(), point.minute())) {
                if (!Domain.sameTime(unique.get(unique.size() - 1).value(), point.value())) {
                    throw new IllegalArgumentException("incompatible arrival fragments for one path");
                }
                continue;
            }
            unique.add(point);
        }
        return TimeProfile.piecewise(
                union,
                unique,
                profileLineage(left.arrivalProfile().fingerprint())
                        + "|restrict:" + union.intervals());
    }

    private static ScoreProfile mergeScoreProfiles(
            CandidateProfile left,
            CandidateProfile right,
            Domain union) {
        List<Double> cuts = new ArrayList<>(union.breakpoints());
        cuts.addAll(left.scoreProfile().breakpoints());
        cuts.addAll(right.scoreProfile().breakpoints());
        List<ScoreProfile.Interval> pieces = new ArrayList<>();
        for (Domain.Interval cell : union.splitAt(ProfileCellPartition.uniqueSorted(cuts)).intervals()) {
            if (cell.start() == cell.end()) {
                pieces.add(new ScoreProfile.Interval(
                        cell.start(),
                        cell.end(),
                        scoreAt(left, right, cell.start())));
                continue;
            }
            double sample = ProfileCellPartition.midpoint(cell);
            int value = scoreAt(left, right, sample);
            pieces.add(new ScoreProfile.Interval(cell.start(), cell.end(), value));
            if (cell.endInclusive()) {
                int endpointValue = scoreAt(left, right, cell.end());
                if (endpointValue != value) {
                    pieces.add(new ScoreProfile.Interval(cell.end(), cell.end(), endpointValue));
                }
            }
        }
        return ScoreProfile.piecewise(
                union,
                pieces,
                profileLineage(left.scoreProfile().fingerprint())
                        + "|restrict:" + union.intervals());
    }

    /**
     * Reconstructs the score profile of a maximal compatible run with the same
     * cell and endpoint-ownership rule as the historical pairwise merge.
     */
    private static ScoreProfile mergeScoreProfiles(
            List<CandidateProfile> run,
            Domain union) {
        List<Double> cuts =
                new ArrayList<>(union.breakpoints());
        for (CandidateProfile candidate : run) {
            cuts.addAll(
                    candidate.scoreProfile().breakpoints());
        }
        List<ScoreProfile.Interval> pieces =
                new ArrayList<>();
        for (Domain.Interval cell : union.splitAt(
                ProfileCellPartition.uniqueSorted(cuts))
                .intervals()) {
            if (cell.start() == cell.end()) {
                pieces.add(new ScoreProfile.Interval(
                        cell.start(),
                        cell.end(),
                        scoreAt(run, cell.start())));
                continue;
            }
            double sample =
                    ProfileCellPartition.midpoint(cell);
            int value = scoreAt(run, sample);
            pieces.add(new ScoreProfile.Interval(
                    cell.start(),
                    cell.end(),
                    value));
            if (cell.endInclusive()) {
                int endpointValue =
                        scoreAt(run, cell.end());
                if (endpointValue != value) {
                    pieces.add(new ScoreProfile.Interval(
                            cell.end(),
                            cell.end(),
                            endpointValue));
                }
            }
        }
        return ScoreProfile.piecewise(
                union,
                pieces,
                profileLineage(
                        run.get(0).scoreProfile()
                                .fingerprint())
                        + "|restrict:" + union.intervals());
    }

    private static boolean sameProfileLineage(String left, String right) {
        return profileLineage(left).equals(profileLineage(right));
    }

    private static String profileLineage(String fingerprint) {
        int restriction = fingerprint.indexOf("|restrict:");
        return restriction < 0 ? fingerprint : fingerprint.substring(0, restriction);
    }

    private static int scoreAt(
            CandidateProfile left,
            CandidateProfile right,
            double departure) {
        boolean inLeft = left.domain().contains(departure);
        boolean inRight = right.domain().contains(departure);
        if (inLeft && inRight) {
            int leftValue = left.scoreProfile().valueAt(departure);
            int rightValue = right.scoreProfile().valueAt(departure);
            if (leftValue != rightValue) {
                throw new IllegalArgumentException("incompatible score fragments for one path");
            }
            return leftValue;
        }
        if (inLeft) {
            return left.scoreProfile().valueAt(departure);
        }
        if (inRight) {
            return right.scoreProfile().valueAt(departure);
        }
        throw new IllegalArgumentException("merged profile has an uncovered departure " + departure);
    }

    private static int scoreAt(
            List<CandidateProfile> run,
            double departure) {
        int low = 0;
        int high = run.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            double start = run.get(middle)
                    .domain().intervals().get(0).start();
            if (start < departure
                    || Domain.sameTime(start, departure)) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        Integer value = null;
        for (int index = Math.min(
                run.size() - 1, low - 1);
                index >= 0;
                index--) {
            Domain.Interval interval = run.get(index)
                    .domain().intervals().get(0);
            if (interval.end() < departure
                    && !Domain.sameTime(
                            interval.end(), departure)) {
                break;
            }
            if (!run.get(index).domain()
                    .contains(departure)) {
                continue;
            }
            int observed = run.get(index)
                    .scoreProfile()
                    .valueAt(departure);
            if (value != null && value != observed) {
                throw new IllegalArgumentException(
                        "incompatible score fragments "
                                + "for one path");
            }
            value = observed;
        }
        for (int index = low;
                index < run.size();
                index++) {
            Domain.Interval interval = run.get(index)
                    .domain().intervals().get(0);
            if (!Domain.sameTime(
                    interval.start(), departure)) {
                break;
            }
            if (!run.get(index).domain()
                    .contains(departure)) {
                continue;
            }
            int observed = run.get(index)
                    .scoreProfile()
                    .valueAt(departure);
            if (value != null && value != observed) {
                throw new IllegalArgumentException(
                        "incompatible score fragments "
                                + "for one path");
            }
            value = observed;
        }
        if (value == null) {
            throw new IllegalArgumentException(
                    "merged profile has an uncovered departure "
                            + departure);
        }
        return value;
    }

    private static CandidateProfile restrictAndMarkCompressed(CandidateProfile source, Domain domain) {
        CandidateProfile restricted = source.restrict(domain);
        return new CandidateProfile(
                restricted.domain(),
                restricted.arrivalProfile(),
                restricted.scoreProfile(),
                restricted.pathPointer(),
                restricted.explicitAnchorCount(),
                restricted.pivotId(),
                true,
                restricted.usedPivotArcIds());
    }

    private enum MetricOrder {
        EARLIEST,
        COVERAGE,
        LEAST_RESTRICTIVE,
        FILL
    }

    private record Metrics(
            double averageScore,
            double averageArrival,
            double minimumSlack,
            double temporalCoverage,
            int omegaSize) {
    }

    static record RetainedCellReference(
            CandidateProfile source,
            Domain.Interval cell) {
    }
}
