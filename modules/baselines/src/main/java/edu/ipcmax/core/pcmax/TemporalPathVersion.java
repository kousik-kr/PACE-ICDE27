package edu.ipcmax.core.pcmax;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import edu.ipcmax.core.graph.Edge;
import edu.ipcmax.core.graph.TDGraph;

/** Stable hash of every temporal attribute affecting replay of one path. */
final class TemporalPathVersion {
    static final String SEMANTICS_VERSION =
            "PACE-FIFO-INDUCED-SCORE-v1";

    private TemporalPathVersion() {
    }

    static String hash(
            TDGraph graph,
            List<Integer> stableArcIds) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", failure);
        }
        update(digest, SEMANTICS_VERSION);
        for (int arcId : stableArcIds) {
            Edge edge = graph.edges().get(arcId);
            digest.update(integer(arcId));
            digest.update(integer(edge.source()));
            digest.update(integer(edge.target()));
            update(
                    digest,
                    edge.travelTimeFunction().domain().toString());
            edge.travelTimeFunction().breakpoints().forEach(point -> {
                digest.update(number(point.minute()));
                digest.update(number(point.value()));
            });
            update(digest, edge.scoreFunction().domain().toString());
            edge.scoreFunction().intervals().forEach(interval -> {
                digest.update(number(interval.startMinute()));
                digest.update(number(interval.endMinute()));
                digest.update(integer(interval.value()));
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] integer(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .putInt(value).array();
    }

    private static byte[] number(double value) {
        return ByteBuffer.allocate(Long.BYTES)
                .putLong(Double.doubleToLongBits(value)).array();
    }

    private static void update(
            MessageDigest digest,
            String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(integer(bytes.length));
        digest.update(bytes);
    }
}
