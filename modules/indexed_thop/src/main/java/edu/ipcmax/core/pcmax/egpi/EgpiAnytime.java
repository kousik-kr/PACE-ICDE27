package edu.ipcmax.core.pcmax.egpi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.EnvelopeGain;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.CanonicalExactPreferenceEnvelope;
import edu.ipcmax.core.validate.Path;

/**
 * The exactly validated answer of an EGPI search at any point of its run.
 *
 * <p>Every exact unit that is committed leaves one immutable {@link Commit}; the
 * committed collection is append-only. A {@link Snapshot} is rebuilt from the
 * first {@code k} commits into a fresh envelope, so it is internally consistent
 * and shares no mutable state with the running search: nothing a worker or the
 * coordinator does later can change it. A snapshot at time {@code T} is the
 * snapshot after the last commit that completed at or before {@code T}; a unit
 * that finished at 8 s is never part of the snapshot at 3 s.</p>
 *
 * <p>All times are nanoseconds on the search clock, which starts when the
 * query's search budget starts.</p>
 */
public final class EgpiAnytime {
    public static final String SCHEMA = "egpi-anytime-v1";
    public static final String CHECKPOINT_SCHEMA = "egpi-anytime-checkpoint-v1";

    /** Unit outcomes in {@link UnitEvent#outcome()}. */
    public static final String COMMITTED_IMPROVING = "COMMITTED_IMPROVING";
    public static final String COMMITTED_NONIMPROVING = "COMMITTED_NONIMPROVING";
    public static final String COMMITTED_NO_FEASIBLE_DEPARTURE = "COMMITTED_NO_FEASIBLE_DEPARTURE";
    public static final String VALIDATED_UNCOMMITTED_AT_STOP = "VALIDATED_UNCOMMITTED_AT_STOP";
    public static final String CANCELLED_UNVALIDATED = "CANCELLED_UNVALIDATED";

    private EgpiAnytime() { }

    /** Lifecycle of one (route, departure chunk) unit. */
    public record UnitEvent(int epoch, int batchIndex, int routeId, int pivots, int chunk,
            long submitNs, long startNs, long finishNs, long evalNs,
            long commitStartNs, long commitEndNs, String outcome,
            int reusedElements, int composedElements, String worker,
            boolean validatedProfile, boolean feasibleSupport, String feasibleMeasureExact) {
        public Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("epoch", epoch);
            values.put("batch_index", batchIndex);
            values.put("route_id", routeId);
            values.put("pivots", pivots);
            values.put("chunk", chunk);
            values.put("submit_ns", submitNs);
            values.put("start_ns", startNs);
            values.put("finish_ns", finishNs);
            values.put("eval_ns", evalNs);
            values.put("commit_start_ns", commitStartNs);
            values.put("commit_end_ns", commitEndNs);
            values.put("outcome", outcome);
            values.put("reused_elements", reusedElements);
            values.put("composed_elements", composedElements);
            values.put("worker", worker);
            values.put("validated_profile", validatedProfile);
            values.put("feasible_support", feasibleSupport);
            values.put("feasible_measure_exact", feasibleMeasureExact);
            return values;
        }
    }

    /** One batch of exact units: submission to the last commit. */
    public record EpochEvent(int epoch, int units, long submitNs, long lastFinishNs, long lastCommitEndNs,
            long coordinatorWaitNs, long coordinatorCommitNs, long coordinatorCreditNs, long workerBusyNs,
            int committed, int cancelled, int validatedUncommitted, int poolThreads) {
        public Map<String, Object> asMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("epoch", epoch);
            values.put("units", units);
            values.put("submit_ns", submitNs);
            values.put("last_finish_ns", lastFinishNs);
            values.put("last_commit_end_ns", lastCommitEndNs);
            values.put("coordinator_wait_ns", coordinatorWaitNs);
            values.put("coordinator_commit_ns", coordinatorCommitNs);
            values.put("coordinator_credit_ns", coordinatorCreditNs);
            values.put("worker_busy_ns", workerBusyNs);
            values.put("committed", committed);
            values.put("cancelled", cancelled);
            values.put("validated_uncommitted", validatedUncommitted);
            values.put("pool_threads", poolThreads);
            return values;
        }
    }

    /**
     * One committed exact unit. {@code profile} is {@code null} for a unit whose route
     * is unsupported on every departure of its chunk (committed, nothing to publish).
     * {@code gain} is the exact change of the published envelope caused by this commit; its amounts are
     * computed when first read (the running totals are formed only by {@link Timeline#commitRows()}),
     * {@code improving} is known at once.
     */
    public record Commit(int sequence, int epoch, int batchIndex, int routeId, int chunk, long commitNs,
            ExactPreferencePathProfile profile, boolean feasibleSupport, boolean improving,
            EnvelopeGain gain, int envelopeCells,
            int registeredRoutes, long proposalPops, long connectorSearches, int routesAdmitted,
            int inFlightUnits, int pendingUnits, int activeWorkers, long envelopeUpdateNs) {
        public ExactTime coverageGain() { return gain.coverage(); }
        public ExactTime preferenceGain() { return gain.preference(); }

        public Map<String, Object> asMap(ExactTime totalMeasure, ExactTime cumulativeCoverage,
                ExactTime cumulativePreference) {
            ExactTime coverageGain = gain.coverage(), preferenceGain = gain.preference();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sequence", sequence);
            values.put("epoch", epoch);
            values.put("batch_index", batchIndex);
            values.put("route_id", routeId);
            values.put("chunk", chunk);
            values.put("commit_ns", commitNs);
            values.put("feasible_support", feasibleSupport);
            values.put("improving", improving);
            values.put("coverage_gain_exact", coverageGain.toString());
            values.put("preference_gain_exact", preferenceGain.toString());
            values.put("cumulative_coverage_exact", cumulativeCoverage.toString());
            values.put("cumulative_integrated_preference_exact", cumulativePreference.toString());
            values.put("coverage_fraction", ratio(cumulativeCoverage, totalMeasure));
            values.put("average_preference", ratio(cumulativePreference, cumulativeCoverage));
            values.put("envelope_cells", envelopeCells);
            values.put("registered_routes", registeredRoutes);
            values.put("proposal_pops", proposalPops);
            values.put("connector_searches", connectorSearches);
            values.put("routes_admitted_for_exact", routesAdmitted);
            values.put("in_flight_units", inFlightUnits);
            values.put("pending_units", pendingUnits);
            values.put("active_workers", activeWorkers);
            values.put("envelope_update_ns", envelopeUpdateNs);
            return values;
        }
    }

    /** Immutable committed state after {@link #commitCount()} commits. */
    public record Snapshot(String algorithmVersion, int commitCount, long lastCommitNs,
            long lastImprovementNs, ExactPreferenceEnvelope envelope, ExactTemporalDomain covered,
            ExactTemporalDomain unresolved, List<Path> registeredRoutes, List<Path> winners,
            ExactTime coveredMeasure, ExactTime totalMeasure, ExactTime integratedPreference,
            int feasibleUnits, int unitsWithoutFeasibleDeparture,
            int inFlightUnits, int pendingUnits, long proposalPops, long connectorSearches, int routesAdmitted) {
        public Snapshot {
            registeredRoutes = List.copyOf(registeredRoutes);
            winners = List.copyOf(winners);
        }

        public boolean empty() { return !envelope.hasFeasibleCell(); }

        /** The status a consumer may report for this state; unresolved departures are never infeasible. */
        public String coverageStatus() {
            return empty() ? "UNRESOLVED_NO_VALIDATED_PROFILE"
                    : fullCoverage() ? "FULL_VALIDATED_COVERAGE" : "PARTIAL_VALIDATED_COVERAGE";
        }
        public boolean fullCoverage() { return unresolved.isEmpty() && !empty(); }
        public double coverageFraction() { return ratio(coveredMeasure, totalMeasure); }
        /** Average accumulated preference over the validated covered departures; 0 when none are covered. */
        public double averagePreference() { return ratio(integratedPreference, coveredMeasure); }

        /**
         * Compact exact serialization: cell endpoints and scores, no arrival coefficients, over the
         * canonical cells (adjacent cells of one winner merged), which select the same route and score
         * at every departure as the refined envelope.
         */
        public Map<String, Object> checkpointMap() {
            Map<String, Integer> routeIndex = new LinkedHashMap<>();
            List<List<Integer>> routes = new ArrayList<>();
            ExactPreferenceEnvelope canonical = envelope.canonicalized();
            List<List<Object>> cells = new ArrayList<>(canonical.cells().size());
            for (ExactPreferenceEnvelope.Cell cell : canonical.cells()) {
                List<Object> row = new ArrayList<>(6);
                row.add(cell.interval().start().toString());
                row.add(cell.interval().end().toString());
                row.add(cell.interval().startInclusive());
                row.add(cell.interval().endInclusive());
                if (cell.found()) {
                    String key = cell.path().arcIds().toString();
                    Integer index = routeIndex.get(key);
                    if (index == null) {
                        index = routes.size();
                        routeIndex.put(key, index);
                        routes.add(cell.path().arcIds());
                    }
                    row.add(cell.score().toString());
                    row.add(index);
                } else {
                    row.add(null);
                    row.add(-1);
                }
                cells.add(row);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("schema", CHECKPOINT_SCHEMA);
            values.put("algorithm_version", algorithmVersion);
            values.put("commit_count", commitCount);
            values.put("last_commit_ns", lastCommitNs);
            values.put("last_improvement_ns", lastImprovementNs);
            values.put("total_measure_exact", totalMeasure.toString());
            values.put("covered_measure_exact", coveredMeasure.toString());
            values.put("integrated_preference_exact", integratedPreference.toString());
            values.put("coverage_fraction", coverageFraction());
            values.put("average_preference", averagePreference());
            values.put("full_coverage", fullCoverage());
            values.put("cells", cells.size());
            values.put("cell_form", "CANONICAL_MERGED_CELLS");
            values.put("distinct_winners", winners.size());
            values.put("registered_routes", registeredRoutes.size());
            values.put("committed_units_with_feasible_departure", feasibleUnits);
            values.put("committed_units_without_feasible_departure", unitsWithoutFeasibleDeparture);
            values.put("in_flight_units_at_last_commit", inFlightUnits);
            values.put("pending_units_at_last_commit", pendingUnits);
            values.put("proposal_pops_at_last_commit", proposalPops);
            values.put("connector_searches_at_last_commit", connectorSearches);
            values.put("routes_admitted_for_exact_at_last_commit", routesAdmitted);
            values.put("coverage_status", coverageStatus());
            values.put("unresolved_domain_exact", unresolved.toString());
            List<List<Integer>> registered = new ArrayList<>(registeredRoutes.size());
            for (Path route : registeredRoutes) registered.add(route.arcIds());
            values.put("registered_routes_arcs", registered);
            values.put("cell_columns", List.of("start", "end", "start_inclusive", "end_inclusive", "score", "route_index"));
            values.put("cell_rows", cells);
            values.put("routes", routes);
            return values;
        }
    }

    /** The append-only commit log of one search plus the departure domain it covers. */
    public static final class Timeline {
        private final String algorithmVersion;
        private final ExactTemporalDomain departures;
        private final ExactTime totalMeasure;
        private final List<Commit> commits;

        public Timeline(String algorithmVersion, ExactTemporalDomain departures, List<Commit> commits) {
            this.algorithmVersion = Objects.requireNonNull(algorithmVersion, "algorithmVersion");
            this.departures = Objects.requireNonNull(departures, "departures");
            this.commits = List.copyOf(commits);
            ExactTime total = ExactTime.ZERO;
            for (ExactTemporalDomain.Interval interval : departures.intervals()) {
                total = total.add(interval.end().subtract(interval.start()));
            }
            this.totalMeasure = total;
        }

        public List<Commit> commits() { return commits; }
        public ExactTime totalMeasure() { return totalMeasure; }

        /** Number of commits that completed at or before {@code nanos}. */
        public int commitsBy(long nanos) {
            int count = 0;
            for (Commit commit : commits) {
                if (commit.commitNs() <= nanos) count++;
            }
            return count;
        }

        /** Rebuilds the committed state after exactly {@code count} commits (canonical cells; see {@link CanonicalExactPreferenceEnvelope}). */
        public Snapshot snapshotAfter(int count) {
            if (count < 0 || count > commits.size()) throw new IllegalArgumentException("no such commit count: " + count);
            return snapshotsAfter(List.of(count)).get(count);
        }

        /** The committed state a consumer could have observed at {@code nanos}. */
        public Snapshot snapshotAt(long nanos) { return snapshotAfter(commitsBy(nanos)); }

        /** Several snapshots from one pass over the log; keys are the requested commit counts. */
        public Map<Integer, Snapshot> snapshotsAfter(List<Integer> counts) {
            java.util.TreeSet<Integer> wanted = new java.util.TreeSet<>(counts);
            Map<Integer, Snapshot> result = new LinkedHashMap<>();
            CanonicalExactPreferenceEnvelope envelope = new CanonicalExactPreferenceEnvelope(departures);
            Set<Path> routes = new LinkedHashSet<>();
            int feasible = 0, without = 0;
            long lastCommit = 0, lastImprovement = -1;
            for (int i = 0; i <= commits.size(); i++) {
                if (i > 0) {
                    Commit commit = commits.get(i - 1);
                    lastCommit = commit.commitNs();
                    if (commit.profile() != null) envelope.add(commit.profile(), null);
                    if (commit.feasibleSupport()) {
                        routes.add(commit.profile().path());
                        feasible++;
                    } else {
                        without++;
                    }
                    if (commit.improving()) lastImprovement = commit.commitNs();
                }
                if (wanted.contains(i)) {
                    result.put(i, describe(envelope.snapshot(), i, lastCommit, lastImprovement, routes, feasible, without,
                            i == 0 ? null : commits.get(i - 1)));
                }
            }
            return result;
        }

        private Snapshot describe(ExactPreferenceEnvelope snapshot, int count, long lastCommit, long lastImprovement,
                Set<Path> routes, int feasible, int without, Commit last) {
            ExactTime covered = ExactTime.ZERO, integrated = ExactTime.ZERO;
            List<ExactTemporalDomain.Interval> found = new ArrayList<>();
            Set<Path> winners = new LinkedHashSet<>();
            for (ExactPreferenceEnvelope.Cell cell : snapshot.cells()) {
                if (!cell.found()) continue;
                found.add(cell.interval());
                winners.add(cell.path());
                ExactTime length = cell.interval().end().subtract(cell.interval().start());
                if (length.signum() > 0) {
                    covered = covered.add(length);
                    integrated = integrated.add(length.multiply(ExactTime.of(cell.score(), java.math.BigInteger.ONE)));
                }
            }
            ExactTemporalDomain coveredDomain = ExactTemporalDomain.of(found);
            return new Snapshot(algorithmVersion, count, lastCommit, lastImprovement, snapshot, coveredDomain,
                    departures.difference(coveredDomain), new ArrayList<>(routes), new ArrayList<>(winners),
                    covered, totalMeasure, integrated, feasible, without,
                    last == null ? 0 : last.inFlightUnits(), last == null ? 0 : last.pendingUnits(),
                    last == null ? 0 : last.proposalPops(), last == null ? 0 : last.connectorSearches(),
                    last == null ? 0 : last.routesAdmitted());
        }

        /** One row per commit with the exact running totals; evaluates the commits' gains. */
        public List<Map<String, Object>> commitRows() {
            List<Map<String, Object>> rows = new ArrayList<>(commits.size());
            ExactTime coverage = ExactTime.ZERO, preference = ExactTime.ZERO;
            for (Commit commit : commits) {
                coverage = coverage.add(commit.gain().coverage());
                preference = preference.add(commit.gain().preference());
                rows.add(commit.asMap(totalMeasure, coverage, preference));
            }
            return rows;
        }
    }

    static double ratio(ExactTime numerator, ExactTime denominator) {
        if (denominator.signum() == 0) return 0;
        return new java.math.BigDecimal(numerator.numerator().multiply(denominator.denominator()))
                .divide(new java.math.BigDecimal(numerator.denominator().multiply(denominator.numerator())),
                        java.math.MathContext.DECIMAL64).doubleValue();
    }
}
