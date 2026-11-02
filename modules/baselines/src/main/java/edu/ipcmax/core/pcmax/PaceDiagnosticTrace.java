package edu.ipcmax.core.pcmax;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.profile.PathPointer;

/**
 * Diagnostic-only, target-filtered observation of the scalable PACE stages.
 *
 * <p>The production engine installs {@link #none()}. An enabled trace never
 * changes a candidate, ordering key, cap, or frontier operation; it only
 * unions the temporal domains on which a predeclared stable path reaches a
 * stage. Keeping the target set fixed before a query starts prevents the
 * diagnostic from selecting examples after seeing the run.</p>
 */
public final class PaceDiagnosticTrace {
    /** Candidate-generation stages required by the NYC validation audit. */
    public enum Stage {
        CONNECTOR_OUTPUT,
        PARTIAL_STATE_FRONTIER,
        COMPLETE_DESCRIPTOR,
        REPLAYED_CANDIDATE_REGISTRY,
        FINAL_FRONTIER
    }

    private static final PaceDiagnosticTrace NONE =
            new PaceDiagnosticTrace();

    private final boolean enabled;
    private final Map<List<Integer>, Target> targets;
    private final List<ConnectorObservation> connectorObservations;
    private Set<Integer> selectedPivots = Set.of();
    private Domain queryDomain = Domain.empty();
    private long completeDescriptorCount;
    private long replayedCandidateCount;
    private int currentRootFrontierSize;

    private PaceDiagnosticTrace() {
        enabled = false;
        targets = Map.of();
        connectorObservations = List.of();
    }

    /** Creates an enabled trace for the supplied stable arc-id paths. */
    public PaceDiagnosticTrace(
            Collection<? extends List<Integer>> targetPaths) {
        if (targetPaths == null) {
            throw new IllegalArgumentException(
                    "diagnostic target paths are required");
        }
        enabled = true;
        LinkedHashMap<List<Integer>, Target> values =
                new LinkedHashMap<>();
        for (List<Integer> path : targetPaths) {
            if (path == null) {
                throw new IllegalArgumentException(
                        "diagnostic target path cannot be null");
            }
            List<Integer> stable = List.copyOf(path);
            values.putIfAbsent(stable, new Target(stable));
        }
        targets = values;
        connectorObservations = new ArrayList<>();
    }

    /** Returns the shared disabled observer used by production callers. */
    public static PaceDiagnosticTrace none() {
        return NONE;
    }

    /** True when this observer records target-stage coverage. */
    public boolean enabled() {
        return enabled;
    }

    void configure(
            List<Integer> selectedPivotArcIds,
            Domain rootDomain) {
        if (!enabled) {
            return;
        }
        selectedPivots = Set.copyOf(selectedPivotArcIds);
        queryDomain = rootDomain;
        for (Target target : targets.values()) {
            target.configure(selectedPivots, queryDomain);
        }
    }

    void connectorOutput(
            PathPointer pointer,
            Domain domain,
            boolean complete) {
        if (!enabled) {
            return;
        }
        List<Integer> stable = pointer.stablePathId();
        for (Target target : targets.values()) {
            target.connectorOutput(stable, domain, complete);
        }
    }

    synchronized void connectorRequest(
            int source,
            int target,
            Domain entryDomain,
            double residualBudget,
            ConnectorResult result) {
        if (!enabled || connectorObservations.size() >= 256) {
            return;
        }
        connectorObservations.add(new ConnectorObservation(
                source,
                target,
                entryDomain,
                residualBudget,
                result.connectors().stream()
                        .map(CandidateProfile::stablePathId)
                        .toList()));
    }

    void partialStateFrontier(CandidateSet frontier) {
        if (!enabled) {
            return;
        }
        for (CandidateProfile candidate : frontier.candidates()) {
            List<Integer> stable = candidate.stablePathId();
            for (Target target : targets.values()) {
                target.partialFrontier(stable, candidate.domain());
            }
        }
    }

    synchronized void completeDescriptor(
            PathPointer pointer,
            Domain domain) {
        if (!enabled) {
            return;
        }
        completeDescriptorCount++;
        Target target = targets.get(pointer.stablePathId());
        if (target != null) {
            target.add(Stage.COMPLETE_DESCRIPTOR, domain);
        }
    }

    synchronized void replayedCandidate(CandidateProfile candidate) {
        if (!enabled) {
            return;
        }
        replayedCandidateCount++;
        Target target = targets.get(candidate.stablePathId());
        if (target != null) {
            target.add(
                    Stage.REPLAYED_CANDIDATE_REGISTRY,
                    candidate.domain());
        }
    }

    void finalFrontier(CandidateSet frontier) {
        if (!enabled) {
            return;
        }
        for (CandidateProfile candidate : frontier.candidates()) {
            Target target = targets.get(candidate.stablePathId());
            if (target != null) {
                target.add(Stage.FINAL_FRONTIER, candidate.domain());
            }
        }
        currentRootFrontierSize = frontier.size();
    }

    synchronized void rootFrontier(CandidateSet frontier) {
        if (enabled) {
            currentRootFrontierSize = frontier.size();
        }
    }

    /** Returns whether the exact stable path reaches a stage at a departure. */
    public synchronized boolean contains(
            List<Integer> stablePathId,
            Stage stage,
            double departure) {
        Target target = targets.get(stablePathId);
        return target != null && target.contains(stage, departure);
    }

    /** Number of selected pivots occurring on the target stable path. */
    public synchronized int selectedPivotCount(
            List<Integer> stablePathId) {
        Target target = targets.get(stablePathId);
        return target == null ? 0 : target.expectedPrefixes.size();
    }

    /** Immutable stage-domain snapshot for diagnostic serialization. */
    public synchronized Map<List<Integer>, Map<Stage, Domain>> snapshot() {
        LinkedHashMap<List<Integer>, Map<Stage, Domain>> result =
                new LinkedHashMap<>();
        for (Target target : targets.values()) {
            result.put(target.path, target.stageDomains());
        }
        return Map.copyOf(result);
    }

    /** First 256 connector requests in deterministic reduction order. */
    public synchronized List<ConnectorObservation>
            connectorObservations() {
        return List.copyOf(connectorObservations);
    }

    /** Number of complete descriptors admitted before the latest snapshot. */
    public synchronized long completeDescriptorCount() {
        return completeDescriptorCount;
    }

    /** Number of complete candidate profiles successfully replayed. */
    public synchronized long replayedCandidateCount() {
        return replayedCandidateCount;
    }

    /** Current retained root-frontier size, including interrupted runs. */
    public synchronized int currentRootFrontierSize() {
        return currentRootFrontierSize;
    }

    /** Diagnostic description of one actual production connector request. */
    public record ConnectorObservation(
            int source,
            int target,
            Domain entryDomain,
            double residualBudget,
            List<List<Integer>> returnedPaths) {
        public ConnectorObservation {
            returnedPaths = returnedPaths.stream()
                    .map(List::copyOf)
                    .toList();
        }
    }

    private static final class Target {
        private final List<Integer> path;
        private final EnumMap<Stage, Domain> domains =
                new EnumMap<>(Stage.class);
        private List<List<Integer>> expectedPrefixes = List.of();
        private Map<List<Integer>, Domain> connectorPrefixes = Map.of();
        private Map<List<Integer>, Domain> retainedPrefixes = Map.of();
        private Domain finalConnector = Domain.empty();
        private Domain rootDomain = Domain.empty();

        Target(List<Integer> path) {
            this.path = path;
            for (Stage stage : Stage.values()) {
                domains.put(stage, Domain.empty());
            }
        }

        void configure(Set<Integer> pivots, Domain rootDomain) {
            this.rootDomain = rootDomain;
            List<List<Integer>> prefixes = new ArrayList<>();
            for (int index = 0; index < path.size(); index++) {
                if (pivots.contains(path.get(index))) {
                    prefixes.add(List.copyOf(path.subList(0, index + 1)));
                }
            }
            expectedPrefixes = List.copyOf(prefixes);
            LinkedHashMap<List<Integer>, Domain> connector =
                    new LinkedHashMap<>();
            LinkedHashMap<List<Integer>, Domain> retained =
                    new LinkedHashMap<>();
            for (List<Integer> prefix : expectedPrefixes) {
                connector.put(prefix, Domain.empty());
                retained.put(prefix, Domain.empty());
            }
            connectorPrefixes = connector;
            retainedPrefixes = retained;
            if (expectedPrefixes.isEmpty()) {
                domains.put(Stage.PARTIAL_STATE_FRONTIER, rootDomain);
            }
        }

        void connectorOutput(
                List<Integer> stable,
                Domain domain,
                boolean complete) {
            if (complete && stable.equals(path)) {
                finalConnector = finalConnector.union(domain);
            }
            Domain previous = connectorPrefixes.get(stable);
            if (previous != null) {
                connectorPrefixes.put(stable, previous.union(domain));
            }
            updateConnectorCoverage();
        }

        void partialFrontier(
                List<Integer> stable,
                Domain domain) {
            Domain previous = retainedPrefixes.get(stable);
            if (previous == null) {
                return;
            }
            retainedPrefixes.put(stable, previous.union(domain));
            Domain coverage = queryDomainForAll(retainedPrefixes);
            domains.put(Stage.PARTIAL_STATE_FRONTIER, coverage);
        }

        void add(Stage stage, Domain domain) {
            domains.put(stage, domains.get(stage).union(domain));
        }

        boolean contains(Stage stage, double departure) {
            return domains.get(stage).contains(departure);
        }

        Map<Stage, Domain> stageDomains() {
            return Map.copyOf(domains);
        }

        private void updateConnectorCoverage() {
            Domain coverage = finalConnector;
            for (Domain prefix : connectorPrefixes.values()) {
                coverage = coverage.intersection(prefix);
            }
            domains.put(Stage.CONNECTOR_OUTPUT, coverage);
        }

        private Domain queryDomainForAll(
                Map<List<Integer>, Domain> values) {
            Domain coverage = rootDomain;
            for (Domain value : values.values()) {
                coverage = coverage.intersection(value);
            }
            return coverage;
        }
    }
}
