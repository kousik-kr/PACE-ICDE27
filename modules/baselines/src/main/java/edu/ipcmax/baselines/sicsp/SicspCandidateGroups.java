package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.validate.Path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Path grouping shared by native and preference-adapted SICSP selection policies. */
public final class SicspCandidateGroups {
    private SicspCandidateGroups() {
    }

    /** Groups event points in first-seen order by their complete arc-ID witness. */
    public static Map<Path, List<SicspBreakpointCandidate>> byPath(
            List<SicspBreakpointCandidate> candidates) {
        Map<Path, List<SicspBreakpointCandidate>> groups = new LinkedHashMap<>();
        for (SicspBreakpointCandidate candidate : candidates) {
            groups.computeIfAbsent(candidate.path().orElseThrow(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        Map<Path, List<SicspBreakpointCandidate>> immutable = new LinkedHashMap<>();
        groups.forEach((path, values) -> immutable.put(path, List.copyOf(values)));
        return java.util.Collections.unmodifiableMap(immutable);
    }
}
