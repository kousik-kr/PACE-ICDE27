package edu.ipcmax.core.pcmax.egpi;

import edu.ipcmax.baselines.exact.ExactTemporalDomain;
import edu.ipcmax.baselines.exact.ExactTime;
import edu.ipcmax.baselines.exact.preference.ExactPreferencePathProfile;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceTransferProfile;

import java.math.BigInteger;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Cancellation and exact serialized-payload accounting shared by query-local caches. */
final class ExactCacheSupport {
    static final long DEFAULT_MAX_PAYLOAD_BYTES = 64L * 1024 * 1024;
    private ExactCacheSupport() { }

    static void check(BooleanSupplier cancellationRequested) {
        if (Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean())) {
            throw new CancellationException("EGPI exact evaluation cancelled");
        }
    }

    static <T> T await(CompletableFuture<T> future, BooleanSupplier cancellationRequested) {
        while (true) {
            check(cancellationRequested);
            try {
                return future.get(10, TimeUnit.MILLISECONDS);
            } catch (TimeoutException wait) {
                // Bounded polling lets a waiting worker obey the shared deadline.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("EGPI exact-cache wait interrupted");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("EGPI exact evaluation failed", cause);
            }
        }
    }

    static String field(String value) { return value.length() + ":" + value; }

    /** Exact byte count of a defined canonical numeric payload, not JVM object heap size. */
    static long payloadBytes(ExactPreferenceTransferProfile profile, BooleanSupplier cancel) {
        long bytes = 4L + profile.path().arcIds().size() * 4L + domainBytes(profile.requestedDomain());
        bytes += 4L;
        for (var segment : profile.arrival().segments()) {
            check(cancel);
            bytes += intervalBytes(segment.interval()) + timeBytes(segment.slope()) + timeBytes(segment.intercept());
        }
        bytes += 4L;
        for (var segment : profile.score().segments()) {
            check(cancel);
            bytes += intervalBytes(segment.interval()) + integerBytes(segment.value());
        }
        return bytes + Long.BYTES;
    }

    /** Canonical payload of a source-root prefix state (arrival plus accumulated score pieces). */
    static long payloadBytes(edu.ipcmax.baselines.exact.preference.ExactPreferencePrefixProfile prefix) {
        long bytes = 8L + domainBytes(prefix.departureDomain());
        for (var segment : prefix.arrival().segments()) {
            bytes += intervalBytes(segment.interval()) + timeBytes(segment.slope()) + timeBytes(segment.intercept());
        }
        if (prefix.accumulatedScore().isPresent()) {
            for (var segment : prefix.accumulatedScore().get().segments()) {
                bytes += intervalBytes(segment.interval()) + integerBytes(segment.value());
            }
        }
        return bytes;
    }

    static long payloadBytes(ExactPreferencePathProfile profile, BooleanSupplier cancel) {
        var transfer = new ExactPreferenceTransferProfile(profile.path(), profile.arrival().domain(),
                profile.arrival(), profile.score(), profile.scoreEventPreimages());
        return payloadBytes(transfer, cancel) + domainBytes(profile.feasibleDomain()) + timeBytes(profile.budget());
    }

    private static long domainBytes(ExactTemporalDomain domain) {
        long result = 4;
        for (var interval : domain.intervals()) result += intervalBytes(interval);
        return result;
    }
    private static long intervalBytes(ExactTemporalDomain.Interval interval) {
        return timeBytes(interval.start()) + timeBytes(interval.end()) + 2;
    }
    private static long timeBytes(ExactTime time) {
        return integerBytes(time.numerator()) + integerBytes(time.denominator());
    }
    private static long integerBytes(BigInteger value) {
        return Integer.BYTES + value.bitLength() / 8L + 1;
    }
}
