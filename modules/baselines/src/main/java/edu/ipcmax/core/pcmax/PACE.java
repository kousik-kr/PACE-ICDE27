package edu.ipcmax.core.pcmax;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.index.QueryPreparationIndexes;
import edu.ipcmax.core.profile.CandidateSet;
import edu.ipcmax.core.validate.ExactPathValidator;
import edu.ipcmax.core.validate.GraphValidator;

/**
 * Public Profile-Aware Candidate Envelope algorithm.
 *
 * <p>The forward-layered scalable engine is the default. The former
 * left/right recursion remains reachable only through
 * {@link PaceEngineMode#LEGACY} for fixture diagnostics.</p>
 */
public final class PACE {
    private static final Map<TDGraph, QueryPreparationIndexes>
            PREPARED_INDEXES = new WeakHashMap<>();
    /** FIFO/arc-id validation belongs to immutable graph preparation, not to
     * each timed query construction. Weak keys retain fixture isolation. */
    private static final Map<TDGraph, Boolean>
            VALIDATED_GRAPHS = new WeakHashMap<>();
    private final TDGraph graph;
    private final PaceOptions options;
    private final PaceExecutionMetrics metrics;
    private final PaceFrontierGenerator legacyGenerator;
    private final ForwardLayeredFrontierGenerator scalableGenerator;
    private volatile PaceGenerationResult lastResult;

    public PACE(TDGraph graph, PaceOptions options) {
        this(
                graph,
                options,
                options != null
                        && options.engineMode()
                            == PaceEngineMode.SCALABLE
                        ? preparedIndexes(graph)
                        : null,
                PaceExecutionMetrics.none(),
                PaceDiagnosticTrace.none());
    }

    /**
     * Creates PACE with an explicitly prepared reusable query-index bundle.
     */
    public PACE(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes) {
        this(
                graph, options, indexes,
                PaceExecutionMetrics.none(),
                PaceDiagnosticTrace.none());
    }

    /**
     * Creates PACE with prepared indexes and live query instrumentation.
     */
    public PACE(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes,
            PaceExecutionMetrics metrics) {
        this(
                graph, options, indexes, metrics,
                PaceDiagnosticTrace.none());
    }

    /** Creates PACE with live metrics and target-filtered diagnostics. */
    public PACE(
            TDGraph graph,
            PaceOptions options,
            QueryPreparationIndexes indexes,
            PaceExecutionMetrics metrics,
            PaceDiagnosticTrace diagnosticTrace) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.options = Objects.requireNonNull(options, "options");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        validateGraphOnce(graph);
        if (options.engineMode() == PaceEngineMode.LEGACY) {
            legacyGenerator =
                    new PaceFrontierGenerator(graph, options);
            scalableGenerator = null;
        } else {
            scalableGenerator =
                    new ForwardLayeredFrontierGenerator(
                            graph,
                            options,
                            Objects.requireNonNull(
                                    indexes,
                                    "query preparation indexes"),
                            metrics,
                            Objects.requireNonNull(
                                    diagnosticTrace,
                                    "diagnostic trace"));
            legacyGenerator = null;
        }
        lastResult = new PaceGenerationResult(
                new CandidateSet(),
                PaceCompletion.UNRESOLVED_NO_GENERATED_CANDIDATE,
                PaceExactnessScope.NOT_CERTIFIED,
                PaceCapStatus.none(),
                PaceGenerationStats.empty(),
                "",
                List.of(),
                "");
    }

    public static synchronized QueryPreparationIndexes preparedIndexes(
            TDGraph graph) {
        Objects.requireNonNull(graph, "graph");
        validateGraphOnce(graph);
        return PREPARED_INDEXES.computeIfAbsent(
                graph,
                QueryPreparationIndexes::buildAllowingZero);
    }

    /**
     * Validates one immutable graph once before its indexes or queries are
     * used. Experiment shared preprocessing calls {@link #preparedIndexes}
     * before the per-query timeout starts, so large-dataset validation is not
     * repeatedly charged to every query.
     */
    private static synchronized void validateGraphOnce(
            TDGraph graph) {
        if (VALIDATED_GRAPHS.containsKey(graph)) {
            return;
        }
        GraphValidator.validate(graph, true);
        VALIDATED_GRAPHS.put(graph, Boolean.TRUE);
    }

    /** Generates a completion- and cap-bearing candidate frontier. */
    public synchronized PaceGenerationResult generate(QuerySpec query) {
        Objects.requireNonNull(query, "query");
        if (scalableGenerator != null) {
            lastResult = scalableGenerator.generate(query);
            return lastResult;
        }
        CandidateSet frontier =
                legacyGenerator.generateFrontier(query);
        PaceCompletion completion;
        if (!frontier.isEmpty()) {
            completion = PaceCompletion.COMPLETE;
        } else {
            completion = PaceCompletion
                    .UNRESOLVED_NO_GENERATED_CANDIDATE;
        }
        lastResult = new PaceGenerationResult(
                frontier,
                completion,
                PaceExactnessScope.NOT_CERTIFIED,
                PaceCapStatus.none(),
                legacyGenerator.stats(),
                "",
                List.of(),
                legacyGenerator.stats().outputChecksum());
        return lastResult;
    }

    /**
     * Executes the query and extracts the exact envelope over the retained
     * frontier.
     *
     * <p>A resource-truncated PACE-X result fails closed. PACE-B may return its
     * deterministic retained-frontier envelope, but never receives a global
     * certificate.</p>
     */
    public synchronized EnvelopeProfile run(QuerySpec query) {
        PaceGenerationResult generated = generate(query);
        if (generated.completion() == PaceCompletion.ABORTED) {
            throw new PaceException(
                    PaceStatus.LIMIT_EXCEEDED,
                    "PACE-X candidate generation aborted: "
                            + generated.capStatus().triggered());
        }
        try (PaceExecutionMetrics.Timer ignored =
                     metrics.phase(PaceExecutionMetrics.ENVELOPE_EXTRACTION)) {
            return EnvelopeExtractor.extract(
                    generated.frontier(),
                    query.departureDomain(),
                    metrics);
        }
    }

    public EnvelopeProfile runProfile(QuerySpec query) {
        return run(query);
    }

    public IPCMaxResult bestPointResult(QuerySpec query) {
        EnvelopeProfile profile = run(query);
        return profile.bestResult(
                new ExactPathValidator(graph),
                query.source(),
                query.destination(),
                query.maxTravelTime(),
                query::isOnGrid);
    }

    public PaceOptions options() {
        return options;
    }

    public PaceGenerationStats stats() {
        return lastResult.stats();
    }

    public PaceGenerationResult lastGenerationResult() {
        return lastResult;
    }
}
