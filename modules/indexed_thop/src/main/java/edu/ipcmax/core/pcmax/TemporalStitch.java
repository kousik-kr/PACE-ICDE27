package edu.ipcmax.core.pcmax;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;
import edu.ipcmax.core.validate.LooplessChecker;
import edu.ipcmax.core.validate.Path;

/**
 * Exact staged temporal stitching of {@code C_L}, one anchor edge, and {@code C_R}.
 */
public final class TemporalStitch {
    private TemporalStitch() {
    }

    /**
     * Stitches through a query-specific anchor. Traversal validity uses {@code V_a}, never
     * {@code D_a+}; consequently an anchor remains traversable while its instantaneous score is zero.
     */
    public static Optional<CandidateProfile> stitch(
            TDGraph graph,
            CandidateProfile left,
            Anchor anchor,
            CandidateProfile right,
            Domain rootDomain,
            double budget) {
        return stitch(graph, left, anchor.edge(), anchor.validDomain(), right, rootDomain, budget);
    }

    /**
     * Backward-compatible edge overload. New PACE recursion uses the {@link Anchor} overload.
     */
    public static Optional<CandidateProfile> stitch(
            TDGraph graph,
            CandidateProfile left,
            Edge anchor,
            CandidateProfile right,
            Domain rootDomain,
            double budget) {
        Domain valid = PaceProfiles.travelFunctionDomain(anchor)
                .intersection(PaceProfiles.scoreFunctionDomain(anchor));
        return stitch(graph, left, anchor, valid, right, rootDomain, budget);
    }

    private static Optional<CandidateProfile> stitch(
            TDGraph graph,
            CandidateProfile left,
            Edge anchor,
            Domain anchorValidDomain,
            CandidateProfile right,
            Domain rootDomain,
            double budget) {
        if (anchor.source() == anchor.target() || budget < 0 || !Double.isFinite(budget)) {
            return Optional.empty();
        }
        if (!isVertexSimpleConcatenation(graph, left, anchor, right)) {
            return Optional.empty();
        }

        // D_0 = D intersect D_L.
        Domain domain0 = rootDomain.intersection(left.domain());
        if (domain0.isEmpty()) {
            return Optional.empty();
        }

        // D_1: the actual arrival at x belongs to V_a.
        Domain domain1 = left.arrivalProfile().preimage(anchorValidDomain, domain0);
        if (domain1.isEmpty()) {
            return Optional.empty();
        }
        TimeProfile leftArrival = left.arrivalProfile().restrict(domain1);
        TimeProfile anchorArrival = PaceProfiles.edgeArrivalProfile(
                anchor,
                anchorValidDomain,
                "temporal-stitch-anchor");
        TimeProfile afterAnchor = leftArrival.compose(
                anchorArrival,
                "temporal-stitch-after-anchor:left=" + left.stablePathId() + ":a=" + anchor.arcId());

        // D_2: the actual arrival at y belongs to the right-candidate entry domain.
        Domain domain2 = afterAnchor.preimage(right.domain(), domain1);
        if (domain2.isEmpty()) {
            return Optional.empty();
        }
        TimeProfile afterAnchorOnRight = afterAnchor.restrict(domain2);
        TimeProfile joinedArrival = afterAnchorOnRight.compose(
                right.arrivalProfile(),
                "temporal-stitch-arrival:left=" + left.stablePathId()
                        + ":a=" + anchor.arcId() + ":right=" + right.stablePathId());

        // D_J: exact root departures whose propagated travel time satisfies B.
        Domain joinedDomain = joinedArrival.domainWhereTravelTimeAtMost(domain2, budget);
        if (joinedDomain.isEmpty()) {
            return Optional.empty();
        }
        TimeProfile finalArrival = joinedArrival.restrict(joinedDomain);

        // Scores are pulled back at the actual x/y entry times.
        TimeProfile leftEntryOnJoinedDomain = left.arrivalProfile().restrict(joinedDomain);
        ScoreProfile leftScore = left.scoreProfile().restrict(joinedDomain);
        ScoreProfile anchorScore = ScoreProfile.compose(
                leftEntryOnJoinedDomain,
                anchor.scoreFunction(),
                joinedDomain,
                "temporal-stitch-anchor-score:a=" + anchor.arcId());
        TimeProfile yEntryOnJoinedDomain = afterAnchor.restrict(joinedDomain);
        ScoreProfile rightScore = right.scoreProfile().compose(
                yEntryOnJoinedDomain,
                "temporal-stitch-right-score:path=" + right.stablePathId());
        ScoreProfile finalScore = leftScore
                .add(anchorScore, joinedDomain, "temporal-stitch-left-anchor-score")
                .add(rightScore, joinedDomain, "temporal-stitch-total-score");

        PathPointer path = PathPointer.concat(
                left.pathPointer(),
                PathPointer.arc(anchor.arcId()),
                right.pathPointer());
        Set<Integer> usedPivotArcIds = new HashSet<>(
                left.usedPivotArcIds());
        usedPivotArcIds.addAll(right.usedPivotArcIds());
        usedPivotArcIds.add(anchor.arcId());
        return Optional.of(new CandidateProfile(
                joinedDomain,
                finalArrival,
                finalScore.restrict(joinedDomain),
                path,
                left.explicitAnchorCount() + right.explicitAnchorCount() + 1,
                anchor.arcId(),
                false,
                usedPivotArcIds));
    }

    private static boolean isVertexSimpleConcatenation(
            TDGraph graph,
            CandidateProfile left,
            Edge anchor,
            CandidateProfile right) {
        Set<Integer> leftVertices = vertices(graph, left.pathPointer(), anchor.source(), true);
        if (leftVertices == null) {
            return false;
        }
        Set<Integer> rightVertices = vertices(graph, right.pathPointer(), anchor.target(), false);
        if (rightVertices == null) {
            return false;
        }
        if (leftVertices.contains(anchor.target()) || rightVertices.contains(anchor.source())) {
            return false;
        }
        Set<Integer> leftWithoutX = new HashSet<>(leftVertices);
        leftWithoutX.remove(anchor.source());
        Set<Integer> rightWithoutY = new HashSet<>(rightVertices);
        rightWithoutY.remove(anchor.target());
        leftWithoutX.retainAll(rightWithoutY);
        if (!leftWithoutX.isEmpty()) {
            return false;
        }
        PathPointer combined = PathPointer.concat(
                left.pathPointer(),
                PathPointer.arc(anchor.arcId()),
                right.pathPointer());
        return LooplessChecker.isLoopless(graph, new Path(combined.arcIds()));
    }

    /**
     * Returns path vertices, checking the endpoint adjacent to the anchor. Identity candidates
     * contribute just that endpoint.
     */
    private static Set<Integer> vertices(
            TDGraph graph,
            PathPointer pointer,
            int anchorEndpoint,
            boolean left) {
        List<Integer> arcs = pointer.arcIds();
        if (arcs.isEmpty()) {
            return Set.of(anchorEndpoint);
        }
        if (arcs.get(0) < 0 || arcs.get(0) >= graph.edgeCount()) {
            return null;
        }
        Set<Integer> vertices = new HashSet<>();
        Edge first = graph.edges().get(arcs.get(0));
        int current = first.source();
        vertices.add(current);
        for (int arcId : arcs) {
            if (arcId < 0 || arcId >= graph.edgeCount()) {
                return null;
            }
            Edge edge = graph.edges().get(arcId);
            if (edge.source() != current || !vertices.add(edge.target())) {
                return null;
            }
            current = edge.target();
        }
        if (left && current != anchorEndpoint) {
            return null;
        }
        if (!left && first.source() != anchorEndpoint) {
            return null;
        }
        return vertices;
    }
}
