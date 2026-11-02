package edu.ipcmax.experiments.framework;

import java.util.Locale;

/** Stable PACE ablation identifiers. */
public enum Ablation {
    NONE("none"),
    NO_ANCHOR("no-anchor"),
    NO_SAFE_DOM("no-safe-dom"),
    NO_MEMO("no-memo"),
    GLOBAL_K("global-k"),
    RANK_ONLY("rank-only"),
    SERIAL("serial"),
    ALL_ANCHORS("all-anchors"),
    NO_ANCHOR_LB("no-anchor-lb"),
    NO_COMPRESSION("no-compression"),
    NO_MERGE("no-merge"),
    NO_SAFE_CORRIDOR("no-safe-corridor"),
    NO_PIVOT_DIVERSIFICATION("no-pivot-diversification"),
    FAST_ONLY_CONNECTOR("fast-only-connector"),
    LEGACY_CONNECTOR_PORTFOLIO("legacy-connector-portfolio"),
    NO_CONNECTOR_CACHE("no-connector-cache"),
    NO_SCORE_UPPER_BOUND("no-score-upper-bound");

    private final String id;

    Ablation(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Ablation parse(String value) {
        String normalized = value == null ? "none" : value.toLowerCase(Locale.ROOT);
        for (Ablation item : values()) {
            if (item.id.equals(normalized)) {
                return item;
            }
        }
        throw new IllegalArgumentException("unknown ablation: " + value);
    }
}
