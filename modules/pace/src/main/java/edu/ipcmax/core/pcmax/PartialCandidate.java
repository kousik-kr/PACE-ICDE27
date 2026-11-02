package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.CandidateProfile;
import edu.ipcmax.core.profile.PathPointer;
import edu.ipcmax.core.profile.ScoreProfile;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * Immutable forward-layer state with persistent path and exact visited sets.
 */
public final class PartialCandidate {
    private final int endpoint;
    private final CandidateProfile profile;
    private final BitSet visitedVertices;
    private final BitSet visitedEdges;
    private final BitSet usedPivots;
    private final BitSet coveredPivots;
    private final List<Integer> pivotSequence;
    private final int pivotDepth;
    private final String candidateId;
    private final Set<PaceCapKind> provenanceCaps;

    public PartialCandidate(
            int endpoint,
            CandidateProfile profile,
            BitSet visitedVertices,
            BitSet usedPivots,
            int pivotDepth,
            Set<PaceCapKind> provenanceCaps) {
        this(
                endpoint,
                profile,
                visitedVertices,
                new BitSet(),
                usedPivots,
                usedPivots,
                pivotDepth,
                provenanceCaps);
    }

    public PartialCandidate(
            int endpoint,
            CandidateProfile profile,
            BitSet visitedVertices,
            BitSet visitedEdges,
            BitSet usedPivots,
            BitSet coveredPivots,
            int pivotDepth,
            Set<PaceCapKind> provenanceCaps) {
        this(
                endpoint,
                profile,
                visitedVertices,
                visitedEdges,
                usedPivots,
                coveredPivots,
                usedPivots.stream().boxed().toList(),
                pivotDepth,
                provenanceCaps);
    }

    public PartialCandidate(
            int endpoint,
            CandidateProfile profile,
            BitSet visitedVertices,
            BitSet visitedEdges,
            BitSet usedPivots,
            BitSet coveredPivots,
            List<Integer> pivotSequence,
            int pivotDepth,
            Set<PaceCapKind> provenanceCaps) {
        if (endpoint <= 0
                || profile == null
                || visitedVertices == null
                || visitedEdges == null
                || usedPivots == null
                || coveredPivots == null
                || pivotSequence == null
                || pivotDepth < 0) {
            throw new IllegalArgumentException(
                    "invalid partial candidate state");
        }
        if (pivotDepth != pivotSequence.size()
                || pivotSequence.stream().distinct().count()
                        != pivotSequence.size()
                || pivotSequence.stream().anyMatch(
                        rank -> rank == null
                                || rank < 0
                                || !usedPivots.get(rank))) {
            throw new IllegalArgumentException(
                    "pivot sequence must contain each explicit "
                            + "used pivot exactly once");
        }
        this.endpoint = endpoint;
        this.profile = profile;
        this.visitedVertices = (BitSet) visitedVertices.clone();
        this.visitedEdges = (BitSet) visitedEdges.clone();
        this.usedPivots = (BitSet) usedPivots.clone();
        this.coveredPivots = (BitSet) coveredPivots.clone();
        this.pivotSequence = List.copyOf(pivotSequence);
        this.pivotDepth = pivotDepth;
        this.provenanceCaps = Set.copyOf(provenanceCaps);
        this.candidateId = candidateId(
                endpoint,
                profile,
                usedPivots,
                coveredPivots,
                pivotSequence,
                pivotDepth);
    }

    public static PartialCandidate identity(
            int source,
            Domain rootDomain) {
        BitSet visited = new BitSet();
        visited.set(source);
        return new PartialCandidate(
                source,
                new CandidateProfile(
                        rootDomain,
                        TimeProfile.identity(rootDomain),
                        ScoreProfile.constant(rootDomain, 0),
                        PathPointer.empty(),
                        0,
                        -1,
                false),
                visited,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                0,
                Set.of());
    }

    public int endpoint() {
        return endpoint;
    }

    public CandidateProfile profile() {
        return profile;
    }

    public int pivotDepth() {
        return pivotDepth;
    }

    public String candidateId() {
        return candidateId;
    }

    public Set<PaceCapKind> provenanceCaps() {
        return provenanceCaps;
    }

    public boolean visited(int vertex) {
        return vertex >= 0 && visitedVertices.get(vertex);
    }

    public BitSet visitedVertices() {
        return (BitSet) visitedVertices.clone();
    }

    public boolean traversedEdge(int arcId) {
        return arcId >= 0 && visitedEdges.get(arcId);
    }

    public BitSet visitedEdges() {
        return (BitSet) visitedEdges.clone();
    }

    public boolean usedPivot(int canonicalRank) {
        return canonicalRank >= 0 && usedPivots.get(canonicalRank);
    }

    public BitSet usedPivots() {
        return (BitSet) usedPivots.clone();
    }

    public boolean coveredPivot(int canonicalRank) {
        return canonicalRank >= 0
                && coveredPivots.get(canonicalRank);
    }

    public BitSet coveredPivots() {
        return (BitSet) coveredPivots.clone();
    }

    /** Ordered explicit pivot choices, independent of physical coverage. */
    public List<Integer> pivotSequence() {
        return pivotSequence;
    }

    private static String candidateId(
            int endpoint,
            CandidateProfile profile,
            BitSet usedPivots,
            BitSet coveredPivots,
            List<Integer> pivotSequence,
            int depth) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
        update(digest, "PACE-PARTIAL-CANDIDATE-v3");
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(endpoint).array());
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(depth).array());
        for (int arcId : profile.stablePathId()) {
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(arcId).array());
        }
        update(digest, profile.domain().toString());
        digest.update(usedPivots.toByteArray());
        digest.update(coveredPivots.toByteArray());
        for (int rank : pivotSequence) {
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(rank).array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value)
                .getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }
}
