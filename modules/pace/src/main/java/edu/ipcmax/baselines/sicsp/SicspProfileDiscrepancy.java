package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

import java.util.Objects;

/** Replayed candidate values compared with the constructed route profiles. */
public record SicspProfileDiscrepancy(
        Path path,
        double departure,
        double replayedArrival,
        double profileArrival,
        double replayedWeight,
        double profileWeight,
        String detail) {
    public SicspProfileDiscrepancy {
        Objects.requireNonNull(path, "path");
        detail = detail == null ? "" : detail;
    }
}
