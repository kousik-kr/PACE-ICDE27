package edu.ipcmax.core.pcmax.egpi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Scheduling and envelope-engine policies of an EGPI search. None of them
 * changes which complete routes are proposed or how they are prioritized: the
 * {@link EgpiOptions work budgets} and the candidate-generation semantics are
 * the same for every policy, so the output of a search that is not cut short
 * by the deadline is mathematically the same for every combination.
 *
 * <p>The {@link #r21Reference() R21 reference} reproduces the M5-R21
 * executable's behavior; {@link #r22Anytime() R22} is the candidate whose
 * individual parts are accepted or rejected by the R22 gates.</p>
 */
public record EgpiEngineOptions(CommitPolicy commitPolicy, EnvelopeEngine envelopeEngine, ResultForm resultForm,
        long resultReserveMs) {
    public static final String VERSION = "EGPI_ENGINE_OPTIONS_V1";
    /**
     * The R22 search stops this long before the query deadline so that the answer is assembled and returned
     * inside the deadline instead of after it. Chosen before the equal-time experiments from the measured
     * assembly cost of canonical results: 0.04-0.1 s for the 120-cell results of 95-arc routes, 0.17 s for the
     * 453 cells of a 482-arc route and 0.41 s for the 726 cells of a 635-arc route (12,000-bit coefficients),
     * plus the result bookkeeping.
     */
    public static final long DEFAULT_R22_RESERVE_MS = 750;

    /** How a finished exact unit becomes part of the published committed state. */
    public enum CommitPolicy {
        /** R21: units of a batch are committed in batch order, each waiting for every earlier unit. */
        ORDERED_BATCH_V1,
        /**
         * R22: a finished unit is committed as soon as the coordinator is free. The committed
         * collection and its envelope do not depend on completion order; the search decisions
         * (improvement credit, frontier promotion) are taken at the epoch boundary in batch order.
         */
        COMPLETION_AWARE_V1
    }

    /** Internal representation of the committed envelope. */
    public enum EnvelopeEngine {
        /** R21: every cut of every entry is kept; an add copies the whole slot map before changing it. */
        REFINED_STAGED_COPY_V1,
        /** R22: same cells as the refined envelope, changed in place with an undo journal and lazy probes. */
        REFINED_JOURNALED_V2,
        /**
         * R22: canonical cells only (cuts where the winner changes), exact crossings only where scores tie, lazy
         * exact gains; the published cells are those of the refined envelope with equal neighbours merged.
         */
        CANONICAL_LAZY_V3
    }

    /** Cell form of the published profile; the committed envelope itself is always the refined one. */
    public enum ResultForm {
        /** R21: one cell per cut of every entry's pieces. */
        REFINED_CELLS_V1,
        /** R22: adjacent cells of one winner merged; the same route, score and arrival at every departure. */
        CANONICAL_CELLS_V1
    }

    public EgpiEngineOptions {
        Objects.requireNonNull(commitPolicy, "commitPolicy");
        Objects.requireNonNull(envelopeEngine, "envelopeEngine");
        Objects.requireNonNull(resultForm, "resultForm");
        if (resultReserveMs < 0 || resultReserveMs > 60_000) throw new IllegalArgumentException("invalid result reserve");
    }

    public EgpiEngineOptions(CommitPolicy commitPolicy, EnvelopeEngine envelopeEngine, ResultForm resultForm) {
        this(commitPolicy, envelopeEngine, resultForm, 0);
    }

    public EgpiEngineOptions(CommitPolicy commitPolicy, EnvelopeEngine envelopeEngine) {
        this(commitPolicy, envelopeEngine, ResultForm.REFINED_CELLS_V1, 0);
    }

    public static EgpiEngineOptions r21Reference() {
        return new EgpiEngineOptions(CommitPolicy.ORDERED_BATCH_V1, EnvelopeEngine.REFINED_STAGED_COPY_V1,
                ResultForm.REFINED_CELLS_V1, 0);
    }

    public static EgpiEngineOptions r22Anytime() {
        return new EgpiEngineOptions(CommitPolicy.COMPLETION_AWARE_V1, EnvelopeEngine.CANONICAL_LAZY_V3,
                ResultForm.CANONICAL_CELLS_V1, DEFAULT_R22_RESERVE_MS);
    }

    public static EgpiEngineOptions defaults() { return r21Reference(); }

    /** Parses a profile name ({@code r21}, {@code r22}) with optional per-policy overrides. */
    public static EgpiEngineOptions parse(String profile, String commit, String envelope, String form, String reserve) {
        EgpiEngineOptions base = switch (profile == null ? "r21" : profile.trim().toLowerCase(Locale.ROOT)) {
            case "r21", "r21-reference", "r21_reference" -> r21Reference();
            case "r22", "r22-anytime", "r22_anytime_v1" -> r22Anytime();
            default -> throw new IllegalArgumentException("unknown EGPI engine profile: " + profile);
        };
        CommitPolicy commitPolicy = commit == null ? base.commitPolicy()
                : CommitPolicy.valueOf(commit.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        EnvelopeEngine envelopeEngine = envelope == null ? base.envelopeEngine()
                : EnvelopeEngine.valueOf(envelope.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        ResultForm resultForm = form == null ? base.resultForm()
                : ResultForm.valueOf(form.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        long resultReserve = reserve == null ? base.resultReserveMs() : Long.parseLong(reserve.trim());
        return new EgpiEngineOptions(commitPolicy, envelopeEngine, resultForm, resultReserve);
    }

    public String profileName() {
        if (equals(r21Reference())) return "R21_REFERENCE";
        if (equals(r22Anytime())) return "R22_ANYTIME_V1";
        return "CUSTOM";
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("engine_options_version", VERSION);
        values.put("engine_profile", profileName());
        values.put("commit_policy", commitPolicy.name());
        values.put("envelope_engine", envelopeEngine.name());
        values.put("result_form", resultForm.name());
        values.put("result_reserve_ms", resultReserveMs);
        return Collections.unmodifiableMap(values);
    }
}
