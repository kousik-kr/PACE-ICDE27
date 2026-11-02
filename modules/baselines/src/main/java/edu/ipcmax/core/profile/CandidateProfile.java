package edu.ipcmax.core.profile;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Candidate path profile over a root departure domain.
 */
public record CandidateProfile(
        Domain domain,
        TimeProfile arrivalProfile,
        ScoreProfile scoreProfile,
        PathPointer pathPointer,
        int recursionDepth,
        int pivotId,
        boolean compressed,
        Set<Integer> usedPivotArcIds) {
    /**
     * Compatibility constructor for candidates that do not carry selected-pivot
     * provenance (identity paths, connectors, and older fixture builders).
     */
    public CandidateProfile(
            Domain domain,
            TimeProfile arrivalProfile,
            ScoreProfile scoreProfile,
            PathPointer pathPointer,
            int recursionDepth,
            int pivotId,
            boolean compressed) {
        this(
                domain,
                arrivalProfile,
                scoreProfile,
                pathPointer,
                recursionDepth,
                pivotId,
                compressed,
                Set.of());
    }

    /**
     * Creates a validated candidate profile.
     */
    public CandidateProfile {
        if (domain == null || domain.isEmpty()) {
            throw new IllegalArgumentException("candidate domain cannot be null or empty");
        }
        if (arrivalProfile == null || scoreProfile == null || pathPointer == null) {
            throw new IllegalArgumentException("candidate profiles and path pointer are required");
        }
        if (recursionDepth < 0) {
            throw new IllegalArgumentException("explicit anchor count cannot be negative");
        }
        if (usedPivotArcIds == null) {
            throw new IllegalArgumentException("used pivot arc ids are required");
        }
        usedPivotArcIds = Set.copyOf(usedPivotArcIds);
        if (!usedPivotArcIds.isEmpty()
                && usedPivotArcIds.size() != recursionDepth) {
            throw new IllegalArgumentException(
                    "explicit anchor count must equal the used-pivot set size");
        }
    }

    /**
     * Restricts this candidate to a subdomain.
     */
    public CandidateProfile restrict(Domain subdomain) {
        Domain restricted = domain.intersection(subdomain);
        if (restricted.isEmpty()) {
            throw new IllegalArgumentException("restricted candidate domain is empty");
        }
        return new CandidateProfile(
                restricted,
                arrivalProfile.restrict(restricted),
                scoreProfile.restrict(restricted),
                pathPointer,
                recursionDepth,
                pivotId,
                compressed,
                usedPivotArcIds);
    }

    /**
     * Travel time induced by the final arrival profile.
     */
    public double travelTimeAt(double startTime) {
        return arrivalProfile.valueAt(startTime) - startTime;
    }

    /**
     * Number of explicitly introduced anchors. The record component retains its historic
     * {@code recursionDepth} name for source and binary compatibility.
     */
    public int explicitAnchorCount() {
        return recursionDepth;
    }

    /**
     * Ordered stable arc-id sequence used as the stable path identifier.
     */
    public List<Integer> stablePathId() {
        return pathPointer.stablePathId();
    }

    /**
     * Number of edges in the represented path.
     */
    public int edgeCount() {
        return pathPointer.edgeCount();
    }

    /**
     * Materializes the path's vertex sequence and verifies that it belongs to the supplied
     * subproblem. The identity path is represented by the one-element sequence containing
     * the common endpoint.
     */
    public List<Integer> vertexSequence(TDGraph graph, int subproblemSource, int subproblemDestination) {
        if (graph == null) {
            throw new IllegalArgumentException("graph is required to derive path vertices");
        }
        List<Integer> vertices = new ArrayList<>();
        vertices.add(subproblemSource);
        int current = subproblemSource;
        for (int arcId : stablePathId()) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                throw new IllegalArgumentException("candidate contains unknown arc id: " + arcId);
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current) {
                throw new IllegalArgumentException(
                        "candidate path is discontinuous at arc " + arcId + ": expected source " + current);
            }
            current = edge.target();
            vertices.add(current);
        }
        if (current != subproblemDestination) {
            throw new IllegalArgumentException(
                    "candidate path ends at " + current + " instead of subproblem destination " + subproblemDestination);
        }
        return List.copyOf(vertices);
    }

    /**
     * PACE path-consistency signature Omega: internal vertices excluding the current
     * subproblem endpoints.
     */
    public Set<Integer> internalVertices(TDGraph graph, int subproblemSource, int subproblemDestination) {
        List<Integer> vertices = vertexSequence(graph, subproblemSource, subproblemDestination);
        Set<Integer> internal = new LinkedHashSet<>();
        for (int i = 1; i + 1 < vertices.size(); i++) {
            int vertex = vertices.get(i);
            if (vertex != subproblemSource && vertex != subproblemDestination) {
                internal.add(vertex);
            }
        }
        return Set.copyOf(internal);
    }

    /**
     * True when the materialized candidate vertex sequence has no repeated vertex.
     */
    public boolean isVertexSimple(TDGraph graph, int subproblemSource, int subproblemDestination) {
        List<Integer> vertices = vertexSequence(graph, subproblemSource, subproblemDestination);
        return new LinkedHashSet<>(vertices).size() == vertices.size();
    }

    /** Exact vertex membership derived lazily from the immutable path handle. */
    public BitSet vertexMembership(
            TDGraph graph,
            int subproblemSource,
            int subproblemDestination) {
        BitSet membership = new BitSet();
        for (int vertex : vertexSequence(
                graph,
                subproblemSource,
                subproblemDestination)) {
            membership.set(vertex);
        }
        return membership;
    }

    /** Exact directed-arc membership with duplicate-arc rejection. */
    public BitSet edgeMembership() {
        BitSet membership = new BitSet();
        for (int arcId : stablePathId()) {
            if (membership.get(arcId)) {
                throw new IllegalArgumentException(
                        "candidate repeats directed arc " + arcId);
            }
            membership.set(arcId);
        }
        return membership;
    }
}
