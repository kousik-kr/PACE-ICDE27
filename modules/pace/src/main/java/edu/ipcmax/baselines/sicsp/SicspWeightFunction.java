package edu.ipcmax.baselines.sicsp;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.TimeProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact piecewise-constant accumulated route weight over source departures. */
public final class SicspWeightFunction {
    /** A constant accumulated weight over an exact departure interval. */
    public record Piece(Domain.Interval interval, double value) {
        public Piece {
            Objects.requireNonNull(interval, "interval");
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("accumulated weight must be finite and nonnegative");
            }
            value = Domain.canonicalTime(value);
        }
    }

    private final List<Piece> pieces;
    private final Domain domain;

    public SicspWeightFunction(List<Piece> suppliedPieces) {
        Objects.requireNonNull(suppliedPieces, "suppliedPieces");
        List<Piece> ordered = new ArrayList<>(suppliedPieces);
        ordered.sort(Comparator.comparingDouble((Piece piece) -> piece.interval().start())
                .thenComparing(piece -> !piece.interval().startInclusive())
                .thenComparingDouble(piece -> piece.interval().end()));
        for (int i = 0; i < ordered.size(); i++) {
            Domain current = Domain.of(ordered.get(i).interval());
            for (int j = 0; j < i; j++) {
                if (!current.intersection(Domain.of(ordered.get(j).interval())).isEmpty()) {
                    throw new IllegalArgumentException("accumulated-weight pieces overlap");
                }
            }
        }
        this.pieces = List.copyOf(ordered);
        Domain support = Domain.empty();
        for (Piece piece : ordered) {
            support = support.union(Domain.of(piece.interval()));
        }
        this.domain = support;
    }

    public static SicspWeightFunction constant(Domain domain, double value) {
        Objects.requireNonNull(domain, "domain");
        if (domain.isEmpty()) {
            return new SicspWeightFunction(List.of());
        }
        return new SicspWeightFunction(domain.intervals().stream()
                .map(interval -> new Piece(interval, value))
                .toList());
    }

    /** Pulls an edge weight back through the edge's actual entry-time profile. */
    public static SicspWeightFunction pullBack(
            SicspEdgeWeightFn edgeWeight,
            Domain edgeTravelDomain,
            List<TimeProfile> entryProfiles) {
        Objects.requireNonNull(edgeWeight, "edgeWeight");
        Objects.requireNonNull(edgeTravelDomain, "edgeTravelDomain");
        Objects.requireNonNull(entryProfiles, "entryProfiles");
        List<Piece> result = new ArrayList<>();
        for (TimeProfile entryProfile : entryProfiles) {
            for (SicspEdgeWeightFn.Piece weightPiece : edgeWeight.pieces()) {
                Domain edgeSupport = Domain.of(weightPiece.interval()).intersection(edgeTravelDomain);
                if (edgeSupport.isEmpty()) {
                    continue;
                }
                Domain preimage = entryProfile.preimage(edgeSupport, entryProfile.domain());
                for (Domain.Interval interval : preimage.intervals()) {
                    result.add(new Piece(interval, weightPiece.value()));
                }
            }
        }
        return new SicspWeightFunction(result);
    }

    /** Adds two exact step functions on their common support. */
    public SicspWeightFunction plus(SicspWeightFunction other) {
        Objects.requireNonNull(other, "other");
        List<Piece> result = new ArrayList<>();
        for (Piece left : pieces) {
            for (Piece right : other.pieces) {
                Domain overlap = Domain.of(left.interval()).intersection(Domain.of(right.interval()));
                for (Domain.Interval interval : overlap.intervals()) {
                    result.add(new Piece(interval, left.value() + right.value()));
                }
            }
        }
        return new SicspWeightFunction(result);
    }

    public SicspWeightFunction restrict(Domain requested) {
        Objects.requireNonNull(requested, "requested");
        List<Piece> result = new ArrayList<>();
        for (Piece piece : pieces) {
            Domain overlap = Domain.of(piece.interval()).intersection(requested);
            for (Domain.Interval interval : overlap.intervals()) {
                result.add(new Piece(interval, piece.value()));
            }
        }
        return new SicspWeightFunction(result);
    }

    /** Exact departure domain where accumulated weight is at most {@code bound}. */
    public Domain feasibleDomain(double bound) {
        if (Double.isNaN(bound)) {
            throw new IllegalArgumentException("weight bound cannot be NaN");
        }
        Domain result = Domain.empty();
        for (Piece piece : pieces) {
            if (piece.value() <= bound) {
                result = result.union(Domain.of(piece.interval()));
            }
        }
        return result;
    }

    public double valueAt(double departure) {
        for (Piece piece : pieces) {
            if (piece.interval().contains(departure)) {
                return piece.value();
            }
        }
        throw new IllegalArgumentException("departure is outside accumulated-weight support: " + departure);
    }

    public List<Piece> pieces() {
        return pieces;
    }

    public Domain domain() {
        return domain;
    }

    public long pieceCount() {
        return pieces.size();
    }
}
