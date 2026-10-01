package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class CompletedPiecesTest {
    @Test
    void findsTheNextUnfinishedPieceWithinTheSchedule() {
        CompletedPieces pieces = CompletedPieces.of(bits(0, 1, 2, 5, 6));
        assertEquals(3, pieces.nextIncomplete(0, 10));
        assertEquals(4, pieces.nextIncomplete(4, 10));
        assertEquals(7, pieces.nextIncomplete(5, 10));
        assertEquals(7, pieces.nextIncomplete(5, 7));
        assertEquals(10, pieces.nextIncomplete(12, 10));
        assertEquals(0, CompletedPieces.none().nextIncomplete(0, 10));
        assertEquals(0, CompletedPieces.none().nextIncomplete(0, 0));
        assertEquals(5, pieces.count());
        assertEquals(7, pieces.limit());
        assertTrue(pieces.contains(6));
        assertFalse(pieces.contains(3));
        assertFalse(pieces.contains(-1));
        assertThrows(IllegalArgumentException.class, () -> pieces.nextIncomplete(-1, 10));
    }

    @Test
    void encodingRoundTripsAndEmptyIsBlank() {
        CompletedPieces pieces = CompletedPieces.of(bits(0, 9, 4_000));
        assertEquals(pieces, CompletedPieces.decode(pieces.encode()));
        assertEquals("", CompletedPieces.none().encode());
        assertSame(CompletedPieces.none(), CompletedPieces.decode(""));
        assertSame(CompletedPieces.none(), CompletedPieces.of(new BitSet()));
        assertThrows(IllegalArgumentException.class, () -> CompletedPieces.decode("not base64!"));
    }

    @Test
    void copiesItsInputAndComparesByContent() {
        BitSet source = bits(1, 2);
        CompletedPieces pieces = CompletedPieces.of(source);
        source.set(3);
        assertFalse(pieces.contains(3));
        assertEquals(CompletedPieces.of(bits(1, 2)), pieces);
        assertEquals(CompletedPieces.of(bits(1, 2)).hashCode(), pieces.hashCode());
        assertNotEquals(CompletedPieces.of(bits(1)), pieces);
    }

    private static BitSet bits(int... values) {
        BitSet bits = new BitSet();
        for (int value : values) { bits.set(value); }
        return bits;
    }
}
