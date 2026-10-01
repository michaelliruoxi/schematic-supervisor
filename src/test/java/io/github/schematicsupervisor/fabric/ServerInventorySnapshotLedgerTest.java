package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerInventorySnapshotLedgerTest {
    private static final String EPOCH = "00000000-0000-0000-0000-000000000001";
    private final Object world = new Object();
    private final Object connection = new Object();
    private final Object handler = new Object();
    private final ServerInventorySnapshotLedger<Count> ledger =
            new ServerInventorySnapshotLedger<>(EPOCH, count -> new Count(count.value));

    @Test
    void localBindingAndRepeatedReadsDoNotCreateServerAcknowledgements() {
        assertTrue(ledger.observeBinding(world, connection, handler, 7, 63));
        assertEquals(0, ledger.fullSequence());
        assertTrue(latest(handler).isEmpty());
        assertTrue(accept(handler, 7, 19, slots(63, 4), new Count(0)));
        ServerInventorySnapshotStamp stamp = latest(handler).orElseThrow().stamp();
        for (int read = 0; read < 10; read++) {
            assertTrue(ledger.observeBinding(world, connection, handler, 7, 63));
            assertEquals(stamp, latest(handler).orElseThrow().stamp());
        }
        assertEquals(1, ledger.fullSequence());
    }

    @Test
    void packetMutationAndReturnedStackMutationCannotChangeRetainedEvidence() {
        List<Count> packet = slots(63, 4);
        Count cursor = new Count(0);
        assertTrue(accept(handler, 7, 0, packet, cursor));
        packet.getFirst().value = 64;
        cursor.value = 9;
        packet.clear();
        var first = latest(handler).orElseThrow();
        assertEquals(4, first.slots().getFirst().value);
        assertEquals(0, first.cursorStack().value);
        first.slots().getFirst().value = 55;
        first.cursorStack().value = 2;
        assertThrows(UnsupportedOperationException.class, () -> first.slots().clear());
        assertEquals(4, latest(handler).orElseThrow().slots().getFirst().value);
        assertEquals(0, latest(handler).orElseThrow().cursorStack().value);
    }

    @Test
    void reopeningRequiresAnotherHandlerAndFullPacketEvenWhenSyncAndRevisionRepeat() {
        assertTrue(accept(handler, 7, 0, slots(63, 64), new Count(0)));
        ServerInventorySnapshotStamp before = latest(handler).orElseThrow().stamp();
        Object reopened = new Object();
        assertTrue(latest(reopened).isEmpty());
        assertEquals(1, ledger.fullSequence());
        assertTrue(accept(reopened, 7, 0, slots(63, 0), new Count(0)));
        ServerInventorySnapshotStamp after = latest(reopened).orElseThrow().stamp();
        assertTrue(after.isLaterReopenThan(before));
        assertEquals(before.revision(), after.revision());
        assertEquals(before.syncId(), after.syncId());
        assertTrue(latest(handler).isEmpty());
    }

    @Test
    void aLocalCloseAndReuseOfOneHandlerDoesNotInventAReopen() {
        assertTrue(accept(handler, 7, 0, slots(63, 64), new Count(0)));
        ServerInventorySnapshotStamp before = latest(handler).orElseThrow().stamp();
        assertFalse(ledger.observeBinding(world, connection, null, 0, 0));
        assertTrue(accept(handler, 7, 1, slots(63, 0), new Count(0)));
        ServerInventorySnapshotStamp after = latest(handler).orElseThrow().stamp();
        assertTrue(after.fullSequence() > before.fullSequence());
        assertEquals(before.openGeneration(), after.openGeneration());
        assertFalse(after.isLaterReopenThan(before));
        assertFalse(ledger.observeBinding(world, connection, handler, 8, 63));
        assertFalse(ledger.observeBinding(world, connection, handler, 7, 54));
    }

    @Test
    void connectionAndWorldReferenceChangesInvalidatePriorReceiptContext() {
        assertTrue(accept(handler, 7, 0, slots(63, 64), new Count(0)));
        ServerInventorySnapshotStamp before = latest(handler).orElseThrow().stamp();
        Object newConnection = new Object();
        assertTrue(ledger.latestMatching(world, newConnection, handler, 7, 63).isEmpty());
        assertTrue(ledger.acceptFull(world, newConnection, handler, 7, 63, 7, 1, slots(63, 0), new Count(0)));
        var reconnected = ledger.latestMatching(world, newConnection, handler, 7, 63).orElseThrow().stamp();
        assertTrue(reconnected.contextGeneration() > before.contextGeneration());
        assertFalse(reconnected.isLaterReopenThan(before));
        Object newWorld = new Object();
        assertTrue(ledger.latestMatching(newWorld, newConnection, handler, 7, 63).isEmpty());
        assertEquals(2, ledger.fullSequence());
        ledger.invalidate();
        assertEquals(2, ledger.fullSequence());
        assertTrue(ledger.latestMatching(null, null, null, 0, 0).isEmpty());
    }

    @Test
    void wrongSyncPartialContentsUnknownCursorAndCopyFailuresNeverAcknowledge() {
        assertTrue(accept(handler, 7, 0, slots(63, 64), new Count(0)));
        assertFalse(accept(handler, 8, 0, slots(63, 0), new Count(0)));
        assertTrue(latest(handler).isEmpty());
        assertFalse(accept(handler, 7, 0, slots(62, 0), new Count(0)));
        assertFalse(accept(handler, 7, 0, slots(64, 0), new Count(0)));
        assertFalse(accept(handler, 7, 0, slots(63, 0), null));
        List<Count> unknownSlot = slots(63, 0);
        unknownSlot.set(20, null);
        assertFalse(accept(handler, 7, 0, unknownSlot, new Count(0)));
        assertFalse(accept(handler, 7, -1, slots(63, 0), new Count(0)));
        assertFalse(ledger.acceptFull(world, connection, handler, 7, 91, 7, 0, slots(91, 0), new Count(0)));
        assertEquals(1, ledger.fullSequence());
        ServerInventorySnapshotLedger<Count> brokenCopy = new ServerInventorySnapshotLedger<>(EPOCH, ignored -> {
            throw new IllegalStateException("copy failed");
        });
        assertFalse(brokenCopy.acceptFull(world, connection, handler, 7, 63, 7, 0, slots(63, 0), new Count(0)));
        assertEquals(0, brokenCopy.fullSequence());
    }

    @Test
    void freshPacketForSameOpenDoesNotQualifyAsReopenedReceiptAndEpochsNeverCompare() {
        assertTrue(accept(handler, 7, 3, slots(63, 64), new Count(0)));
        ServerInventorySnapshotStamp before = latest(handler).orElseThrow().stamp();
        assertTrue(accept(handler, 7, 4, slots(63, 0), new Count(0)));
        ServerInventorySnapshotStamp after = latest(handler).orElseThrow().stamp();
        assertEquals(before.fullSequence() + 1, after.fullSequence());
        assertFalse(after.isLaterReopenThan(before));
        ServerInventorySnapshotStamp restarted = new ServerInventorySnapshotStamp(
                "00000000-0000-0000-0000-000000000002", 9, 9, 9, 7, 4);
        assertFalse(restarted.isLaterReopenThan(before));
        assertThrows(IllegalArgumentException.class, () -> new ServerInventorySnapshotStamp(EPOCH, 0, 1, 1, 7, 0));
    }

    private boolean accept(Object activeHandler, int packetSync, int revision, List<Count> slots, Count cursor) {
        return ledger.acceptFull(world, connection, activeHandler, 7, 63, packetSync, revision, slots, cursor);
    }

    private java.util.Optional<ServerInventorySnapshotLedger.Captured<Count>> latest(Object activeHandler) {
        return ledger.latestMatching(world, connection, activeHandler, 7, 63);
    }

    private static List<Count> slots(int size, int count) {
        List<Count> result = new ArrayList<>();
        for (int index = 0; index < size; index++) { result.add(new Count(count)); }
        return result;
    }

    private static final class Count {
        int value;
        Count(int value) { this.value = value; }
    }
}
