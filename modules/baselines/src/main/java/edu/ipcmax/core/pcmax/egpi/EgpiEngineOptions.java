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
 * <p>The {@link #referenceProfile() reference profile} reproduces the original ordered-commit
 * executable's behavior; {@link #anytimeProfile() anytime profile} is the candidate whose
 * individual parts are accepted or rejected by the acceptance gates.</p>
 */
public record EgpiEngineOptions(CommitPolicy commitPolicy, EnvelopeEngine envelopeEngine, ResultForm resultForm,
        long resultReserveMs) {
    public static final String VERSION = "EGPI_ENGINE_OPTIONS_V1";
    /**
     * The anytime search stops this long before the query deadline so that the answer is assembled and returned
     * inside the deadline instead of after it. Chosen before the equal-time experiments from the measured
     * assembly cost of canonical results: 0.04-0.1 s for the 120-cell results of 95-arc routes, 0.17 s for the
     * 453 cells of a 482-arc route and 0.41 s for the 726 cells of a 635-arc route (12,000-bit coefficients),
     * plus the result bookkeeping.
     */
    public static final long DEFAULT_ANYTIME_RESERVE_MS = 750;

    /** How a finished exact unit becomes part of the published committed state. */
    public enum CommitPolicy {
        /** Reference: units of a batch are committed in batch order, each waiting for every earlier unit. */
        ORDERED_BATCH_V1,
        /**
         * Anytime: a finished unit is committed as soon as the coordinator is free. The committed
         * collection and its envelope do not depend on completion order; the search decisions
         * (improvement credit, frontier promotion) are taken at the epoch boundary in batch order.
         */
        COMPLETION_AWARE_V1
    }

    /** Internal representation of the committed envelope. */
    public enum EnvelopeEngine {
        /** Reference: every cut of every entry is kept; an add copies the whole slot map before changing it. */
        REFINED_STAGED_COPY_V1,
        /** Anytime: same cells as the refined envelope, changed in place with an undo journal and lazy probes. */
        REFINED_JOURNALED_V2,
        /**
         * Anytime: canonical cells only (cuts where the winner changes), exact crossings only where scores tie, lazy
         * exact gains; the published cells are those of the refined envelope with equal neighbours merged.
         */
        CANONICAL_LAZY_V3
    }

    /** Cell form of the published profile; the committed envelope itself is always the refined one. */
    public enum ResultForm {
        /** Reference: one cell per cut of every entry's pieces. */
        REFINED_CELLS_V1,
        /** Anytime: adjacent cells of one winner merged; the same route, score and arrival at every departure. */
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

    public static EgpiEngineOptions referenceProfile() {
        return new EgpiEngineOptions(CommitPolicy.ORDERED_BATCH_V1, EnvelopeEngine.REFINED_STAGED_COPY_V1,
                ResultForm.REFINED_CELLS_V1, 0);
    }

    public static EgpiEngineOptions anytimeProfile() {
        return new EgpiEngineOptions(CommitPolicy.COMPLETION_AWARE_V1, EnvelopeEngine.CANONICAL_LAZY_V3,
                ResultForm.CANONICAL_CELLS_V1, DEFAULT_ANYTIME_RESERVE_MS);
    }

    public static EgpiEngineOptions defaults() { return referenceProfile(); }

    /** Parses a profile name ({@code reference}, {@code anytime}) with optional per-policy overrides. */
    public static EgpiEngineOptions parse(String profile, String commit, String envelope, String form, String reserve) {
        EgpiEngineOptions base = switch (profile == null ? "reference" : profile.trim().toLowerCase(Locale.ROOT)) {
            case "reference" -> referenceProfile();
            case "anytime", "anytime_v1" -> anytimeProfile();
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
        if (equals(referenceProfile())) return "REFERENCE";
        if (equals(anytimeProfile())) return "ANYTIME_V1";
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
