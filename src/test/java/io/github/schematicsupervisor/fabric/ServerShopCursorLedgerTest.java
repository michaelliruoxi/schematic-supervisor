package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ServerShopCursorLedgerTest {
    static final String EPOCH = "11111111-1111-4111-8111-111111111111";
    final Object world = new Object();
    final Object connection = new Object();
    final Object handler = new Object();

    @Test void markBeforeAnyPacketHasZeroSequenceAndNeverInventsEmptyCursorEvidence() {
        var ledger = ledger();
        var baseline = ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        assertEquals(0, baseline.sequence());
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 63).isEmpty());
        assertEquals(baseline, ledger.mark(world, connection, handler, 1, 63).orElseThrow());
        assertFalse(baseline.isLaterThan(baseline));
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var receipt = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(receipt.stamp().isLaterThan(baseline));
        assertEquals(0, receipt.cursorStack().count);
    }

    @Test void matchingFullPacketCanAcknowledgeButPlayerSyncStaleSyncAndPartialPacketsCannot() {
        var ledger = ledger();
        var baseline = ledger.mark(world, connection, handler, 3, 90).orElseThrow();
        for (int packetSync : new int[] {0, -1, -2, 2, 4}) {
            assertFalse(ledger.acceptFull(world, connection, handler, 3, 90, packetSync, 90, new MutableCursor(0), true));
        }
        assertFalse(ledger.acceptFull(world, connection, handler, 3, 90, 3, 89, new MutableCursor(0), true));
        assertTrue(ledger.latestMatching(world, connection, handler, 3, 90).isEmpty());
        assertEquals(baseline, ledger.mark(world, connection, handler, 3, 90).orElseThrow());
        assertTrue(ledger.acceptFull(world, connection, handler, 3, 90, 3, 90, new MutableCursor(0), true));
        assertTrue(ledger.latestMatching(world, connection, handler, 3, 90).orElseThrow().stamp().isLaterThan(baseline));
    }

    @Test void cursorPacketsSkippedByVanillaIncludingCreativePathAreNotAcknowledgements() {
        var ledger = ledger();
        var baseline = ledger.mark(world, connection, handler, 1, 45).orElseThrow();
        assertFalse(ledger.acceptCursor(world, connection, handler, 1, 45, new MutableCursor(0), false));
        assertFalse(ledger.acceptFull(world, connection, handler, 1, 45, 1, 45, new MutableCursor(0), false));
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 45).isEmpty());
        assertEquals(baseline, ledger.mark(world, connection, handler, 1, 45).orElseThrow());
    }

    @Test void inputOutputAndRepeatedReadCopiesCannotChangeRetainedCursorEvidence() {
        var ledger = ledger();
        MutableCursor packet = new MutableCursor(4);
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, packet, true));
        packet.count = 0;
        var first = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertEquals(4, first.cursorStack().count);
        first.cursorStack().count = 0;
        var second = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertEquals(4, second.cursorStack().count);
        assertNotSame(first.cursorStack(), second.cursorStack());
        assertEquals(first.stamp(), second.stamp());
    }

    @Test void latestNonemptyPacketSupersedesOlderEmptyAcknowledgementWithoutHistoryFallback() {
        var ledger = ledger();
        var baseline = ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var empty = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(empty.stamp().isLaterThan(baseline));
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(1), true));
        var current = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(current.stamp().isLaterThan(empty.stamp()));
        assertEquals(1, current.cursorStack().count);
    }

    @Test void handlerReplacementEvenWithSameSyncCannotSettleOriginalHandlersMark() {
        var ledger = ledger();
        var baseline = ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        Object replacement = new Object();
        assertTrue(ledger.acceptCursor(world, connection, replacement, 1, 63, new MutableCursor(0), true));
        var different = ledger.latestMatching(world, connection, replacement, 1, 63).orElseThrow();
        assertFalse(different.stamp().isLaterThan(baseline));
        assertNotEquals(baseline.handlerGeneration(), different.stamp().handlerGeneration());
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 63).isEmpty());
    }

    @Test void worldAndConnectionUseReferenceIdentityEvenForEqualObjects() {
        for (boolean changeWorld : new boolean[] {true, false}) {
            var ledger = ledger();
            Object sameWorld = new String("world");
            Object sameConnection = new String("connection");
            var baseline = ledger.mark(sameWorld, sameConnection, handler, 1, 63).orElseThrow();
            Object nextWorld = changeWorld ? new String("world") : sameWorld;
            Object nextConnection = changeWorld ? sameConnection : new String("connection");
            assertTrue(ledger.acceptCursor(nextWorld, nextConnection, handler, 1, 63, new MutableCursor(0), true));
            var next = ledger.latestMatching(nextWorld, nextConnection, handler, 1, 63).orElseThrow();
            assertFalse(next.stamp().isLaterThan(baseline));
            assertTrue(next.stamp().contextGeneration() > baseline.contextGeneration());
        }
    }

    @Test void closingAndReusingSameHandlerDoesNotRecycleItsOldAcknowledgement() {
        var ledger = ledger();
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var baseline = ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(ledger.mark(world, connection, null, 0, 0).isEmpty());
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 63).isEmpty());
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var rebound = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertFalse(rebound.stamp().isLaterThan(baseline));
        assertTrue(rebound.stamp().handlerGeneration() > baseline.handlerGeneration());
    }

    @Test void disconnectAndInvalidateKeepSequenceMonotonicButRejectPriorContextMarks() {
        var ledger = ledger();
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var baseline = ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(ledger.mark(null, null, null, 0, 0).isEmpty());
        ledger.invalidate();
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var after = ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(after.stamp().sequence() > baseline.sequence());
        assertFalse(after.stamp().isLaterThan(baseline));
    }

    @Test void malformedBindingAndSameHandlerShapeMutationRemainUnavailable() {
        for (int slots : new int[] {0, 36, 44, 46, 89, 91}) {
            var ledger = ledger();
            assertTrue(ledger.mark(world, connection, handler, 1, slots).isEmpty());
            assertFalse(ledger.acceptCursor(world, connection, handler, 1, slots, new MutableCursor(0), true));
        }
        var ledger = ledger();
        ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        assertTrue(ledger.mark(world, connection, handler, 2, 63).isEmpty());
        assertFalse(ledger.acceptCursor(world, connection, handler, 2, 63, new MutableCursor(0), true));
        assertTrue(ledger.mark(world, connection, handler, 1, 90).isEmpty());
        assertTrue(ledger.mark(world, connection, handler, 0, 63).isEmpty());
    }

    @Test void nullPacketAndCopyFailuresInvalidateEvidenceRatherThanClaimingEmpty() {
        var failCopy = new AtomicBoolean();
        var ledger = new ServerShopCursorLedger<MutableCursor>(EPOCH, cursor -> {
            if (failCopy.get()) { throw new IllegalStateException("unavailable cursor"); }
            return new MutableCursor(cursor.count);
        });
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        var baseline = ledger.mark(world, connection, handler, 1, 63).orElseThrow();
        failCopy.set(true);
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 63).isEmpty());
        failCopy.set(false);
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 63).isEmpty());
        assertFalse(ledger.acceptCursor(world, connection, handler, 1, 63, null, true));
        assertTrue(ledger.latestMatching(world, connection, handler, 1, 63).isEmpty());
        assertTrue(ledger.acceptCursor(world, connection, handler, 1, 63, new MutableCursor(0), true));
        assertFalse(ledger.latestMatching(world, connection, handler, 1, 63).orElseThrow().stamp().isLaterThan(baseline));
    }

    @Test void malformedOrDifferentEpochStampsNeverAuthorizeSameHandlerReceipt() {
        var baseline = new ServerShopCursorStamp(EPOCH, 1, 1, 0, 1);
        assertFalse(new ServerShopCursorStamp(UUID.randomUUID().toString(), 1, 1, 2, 1).isLaterThan(baseline));
        assertFalse(new ServerShopCursorStamp(EPOCH, 1, 1, 2, 2).isLaterThan(baseline));
        assertFalse(baseline.isLaterThan(null));
        assertThrows(IllegalArgumentException.class, () -> new ServerShopCursorStamp(EPOCH, 1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServerShopCursorStamp(EPOCH, 1, 1, 0, 0));
    }

    private static ServerShopCursorLedger<MutableCursor> ledger() {
        return new ServerShopCursorLedger<>(EPOCH, cursor -> new MutableCursor(cursor.count));
    }
    static final class MutableCursor {
        int count;
        MutableCursor(int count) { this.count = count; }
    }
}
