package edu.ipcmax.experiments;

import edu.ipcmax.experiments.algorithms.ExhaustiveProfileAlgorithm;
import edu.ipcmax.experiments.algorithms.AllFpAlgorithm;
import edu.ipcmax.experiments.algorithms.IScopeAlgorithm;
import edu.ipcmax.experiments.algorithms.IScopePartialLabelAlgorithm;
import edu.ipcmax.experiments.algorithms.IntervalBestAlgorithm;
import edu.ipcmax.experiments.algorithms.KspProfileAlgorithm;
import edu.ipcmax.experiments.algorithms.PaceExperimentAlgorithm;
import edu.ipcmax.experiments.algorithms.EgpiExperimentAlgorithm;
import edu.ipcmax.experiments.algorithms.ProfileLabelingAlgorithm;
import edu.ipcmax.experiments.algorithms.SicspPrefExperimentAlgorithm;
import edu.ipcmax.experiments.algorithms.RpqAlgorithm;
import edu.ipcmax.experiments.algorithms.ThopPartitionedExperimentAlgorithm;
import edu.ipcmax.experiments.algorithms.SicspNativeExperimentAlgorithm;
import edu.ipcmax.experiments.framework.ExperimentAlgorithm;

/** Stable candidate identifier registry. */
public final class AlgorithmRegistry {
    private AlgorithmRegistry() {
    }

    public static ExperimentAlgorithm create(String id) {
        String canonical = canonicalId(id);
        return switch (canonical) {
            case "pace-x", "pace-b", "pace-b-legacy-r13" -> new PaceExperimentAlgorithm(canonical);
            case "pace-b-egpi-v1" -> new EgpiExperimentAlgorithm();
            case "exh-profile" -> new ExhaustiveProfileAlgorithm();
            case "pl-exact" -> new ProfileLabelingAlgorithm();
            case "rpq" -> new RpqAlgorithm();
            case "ksp-profile" -> new KspProfileAlgorithm();
            case "interval-best" -> new IntervalBestAlgorithm();
            case "iscope" -> new IScopeAlgorithm("iscope");
            case "iscope-basic" -> new IScopeAlgorithm("iscope-basic");
            case "iscope-opt1", "iscope-optimized" -> new IScopePartialLabelAlgorithm(id);
            case "allfp" -> new AllFpAlgorithm();
            case "sicsp-pref" -> new SicspPrefExperimentAlgorithm();
            case "sicsp-native" -> new SicspNativeExperimentAlgorithm();
            case "thop-partitioned" -> new ThopPartitionedExperimentAlgorithm();
            default -> throw new IllegalArgumentException("unknown --algorithm: " + id);
        };
    }

    /** Version aliases are explicit; the historical pace-b meaning is preserved. */
    public static String canonicalId(String id) {
        return switch (id) {
            case "PACE_X" -> "pace-x";
            case "PACE_B_LEGACY_R13" -> "pace-b-legacy-r13";
            case "PACE_B_EGPI_V1" -> "pace-b-egpi-v1";
            default -> id;
        };
    }
}
