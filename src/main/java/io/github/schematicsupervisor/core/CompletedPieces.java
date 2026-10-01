package io.github.schematicsupervisor.core;

import java.util.Base64;
import java.util.BitSet;
import java.util.Objects;

/** Immutable set of schedule pieces that a build check found already finished. */
public final class CompletedPieces {
    private static final CompletedPieces NONE = new CompletedPieces(new BitSet());
    private final BitSet pieces;
    private final int count;

    private CompletedPieces(BitSet pieces) {
        this.pieces = pieces;
        count = pieces.cardinality();
    }

    public static CompletedPieces none() {
        return NONE;
    }

    public static CompletedPieces of(BitSet pieces) {
        Objects.requireNonNull(pieces, "pieces");
        return pieces.isEmpty() ? NONE : new CompletedPieces((BitSet) pieces.clone());
    }

    public boolean contains(int piece) {
        return piece >= 0 && pieces.get(piece);
    }

    public int count() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** One past the highest finished piece, or zero when the set is empty. */
    public int limit() {
        return pieces.length();
    }

    /** The first piece at or after {@code from} that is not finished, or {@code size} when none remains. */
    public int nextIncomplete(int from, int size) {
        if (from < 0 || size < 0) {
            throw new IllegalArgumentException("piece bounds must be non-negative");
        }
        return from >= size ? size : Math.min(pieces.nextClearBit(from), size);
    }

    /** Base64 of the little-endian bit set; empty when no piece is finished. */
    public String encode() {
        return isEmpty() ? "" : Base64.getEncoder().encodeToString(pieces.toByteArray());
    }

    public static CompletedPieces decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty()) {
            return NONE;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("checked pieces are not valid Base64", exception);
        }
        if ((long) bytes.length * Byte.SIZE > PlanLimits.MAX_TARGET_BLOCKS) {
            throw new IllegalArgumentException("checked pieces exceed the supported schedule size");
        }
        return of(BitSet.valueOf(bytes));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompletedPieces that && pieces.equals(that.pieces);
    }

    @Override
    public int hashCode() {
        return pieces.hashCode();
    }

    @Override
    public String toString() {
        return "CompletedPieces[" + count + "]";
    }
}
