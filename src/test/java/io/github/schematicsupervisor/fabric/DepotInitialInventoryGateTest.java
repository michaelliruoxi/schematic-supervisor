package io.github.schematicsupervisor.fabric;

import static io.github.schematicsupervisor.fabric.DepotInitialInventoryGate.Decision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepotInitialInventoryGateTest {
    private static final String EPOCH = "00000000-0000-0000-0000-000000000021";
    private static final Stack EMPTY = new Stack("air", 0, "");
    private final Object world = new Object();
    private final Object player = new Object();
    private final Object connection = new Object();
    private final Object handler = new Object();
    private final ServerInventorySnapshotLedger<Stack> packets =
            new ServerInventorySnapshotLedger<>(EPOCH, value -> value);
    private final DepotInitialInventoryGate<Stack> gate = newGate();

    @Test
    void openedEmptyClientChestWaitsUntilFullContentsArriveBeforeStockCanBeRead() {
        var empty = window(handler, 7, 6, slots(90), EMPTY);
        assertTrue(packets.observeBinding(world, connection, handler, 7, 90));
        for (int tick = 1; tick < 60; tick++) {
            assertEquals(WAIT, observe(empty, latest(handler), tick));
        }
        List<Stack> server = stock(90, 128);
        assertTrue(packets.acceptFull(world, connection, handler, 7, 90, 7, 1, server, EMPTY));
        var received = window(handler, 7, 6, new ArrayList<>(server), EMPTY);
        var receipt = latest(handler);
        assertEquals(READY, observe(received, receipt, 60));
        assertEquals(128, receipt.slots().subList(0, 54).stream().mapToInt(Stack::count).sum());
    }

    @Test
    void oldOpeningCannotSupplyInitialContentsEvenWhenSyncIdAndRevisionRepeat() {
        Object previous = new Object();
        assertTrue(packets.acceptFull(world, connection, previous, 7, 90, 7, 1, stock(90, 128), EMPTY));
        assertTrue(packets.latestMatching(world, connection, previous, 7, 90).isPresent());
        var current = window(handler, 7, 6, slots(90), EMPTY);
        assertEquals(WAIT, observe(current, latest(handler), 1));
        // A genuinely empty server chest is a valid zero-stock observation, unlike its local initial state.
        assertTrue(packets.acceptFull(world, connection, handler, 7, 90, 7, 1, slots(90), EMPTY));
        assertEquals(READY, observe(current, latest(handler), 2));
    }

    @Test
    void delayedOrPermanentlyMismatchedContentsHaveAnExactBoundedDeadline() {
        var current = window(handler, 7, 6, slots(90), EMPTY);
        assertEquals(WAIT, observe(current, null, 60));
        assertEquals(FAIL_TIMEOUT, observe(current, null, 61));
        var mismatched = receipt(7, 6, stock(90, 128), EMPTY);
        assertEquals(WAIT, observe(current, mismatched, 60));
        assertEquals(FAIL_TIMEOUT, observe(current, mismatched, 61));
        assertEquals(FAIL_TIMEOUT, observe(current, receipt(7, 6, slots(90), EMPTY), 61));
    }

    @Test
    void worldPlayerAndConnectionMustRemainTheSameObjectsAsAtDispatch() {
        var current = window(handler, 7, 6, slots(90), EMPTY);
        var receipt = receipt(7, 6, slots(90), EMPTY);
        assertEquals(WAIT, observe(current, null, 1));
        assertEquals(FAIL_CONTEXT, gate.observe(new Object(), player, connection, current, receipt, 2));
        assertEquals(FAIL_CONTEXT, gate.observe(world, new Object(), connection, current, receipt, 2));
        assertEquals(FAIL_CONTEXT, gate.observe(world, player, new Object(), current, receipt, 2));
        assertFalse(gate.acceptWindow(world, player, new Object(), handler, 7, 6));
    }

    @Test
    void theFirstObservedHandlerCannotBeReplacedByAnIdenticalLookingWindow() {
        assertEquals(WAIT, observe(window(handler, 7, 6, slots(90), EMPTY), null, 1));
        Object replacement = new Object();
        var contents = receipt(7, 6, stock(90, 128), EMPTY);
        assertEquals(FAIL_WINDOW, observe(window(replacement, 7, 6, stock(90, 128), EMPTY), contents, 2));
        assertFalse(gate.acceptWindow(world, player, connection, replacement, 7, 6));
        assertEquals(FAIL_WINDOW, observe(window(handler, 8, 6, stock(90, 128), EMPTY), contents, 2));
        assertFalse(gate.acceptWindow(world, player, connection, handler, 8, 6));
    }

    @Test
    void cancellationMayOwnALateFirstResponseWithoutClaimingAnyInventoryReceipt() {
        assertTrue(gate.acceptWindow(world, player, connection, handler, 7, 6));
        assertTrue(gate.acceptWindow(world, player, connection, handler, 7, 6));
        assertEquals(WAIT, observe(window(handler, 7, 6, slots(90), EMPTY), null, 1));
        assertFalse(gate.acceptWindow(world, player, connection, new Object(), 7, 6));
        assertFalse(gate.acceptWindow(new Object(), player, connection, handler, 7, 6));
    }

    @Test
    void wrongOpenIdentityShapeOrIncompleteCurrentWindowCannotBeStockEvidence() {
        assertEquals(FAIL_WINDOW, observe(window(handler, 0, 6, slots(90), EMPTY), null, 1));
        assertEquals(FAIL_WINDOW, observe(window(handler, 7, 3, slots(63), EMPTY), null, 1));
        assertEquals(FAIL_WINDOW, observe(window(handler, 7, 6, slots(89), EMPTY), null, 1));
        assertEquals(FAIL_WINDOW, observe(window(handler, 7, 6, slots(90), null), null, 1));
    }

    @Test
    void wrongSyncPartialPacketOrDifferentPacketShapeCannotAuthorizeScanOrWithdrawal() {
        var current = window(handler, 7, 6, slots(90), EMPTY);
        assertEquals(FAIL_RECEIPT, observe(current, receipt(8, 6, slots(90), EMPTY), 1));
        assertEquals(FAIL_RECEIPT, observe(current, receipt(7, 3, slots(90), EMPTY), 1));
        assertEquals(FAIL_RECEIPT, observe(current, receipt(7, 6, slots(89), EMPTY), 1));
        assertEquals(FAIL_RECEIPT, observe(current, receipt(7, 6, slots(90), null), 1));
        assertFalse(packets.acceptFull(world, connection, handler, 7, 90, 7, 1, slots(89), EMPTY));
        assertEquals(WAIT, observe(current, latest(handler), 2));
        assertFalse(packets.acceptFull(world, connection, handler, 7, 90, 8, 1, slots(90), EMPTY));
        assertEquals(WAIT, observe(current, latest(handler), 3));
    }

    @Test
    void retainedFullPacketMustStillMatchCountsComponentsPlayerSlotsAndEmptyCursor() {
        List<Stack> full = stock(90, 128);
        var receipt = receipt(7, 6, full, EMPTY);
        List<Stack> changedCount = new ArrayList<>(full);
        changedCount.set(0, new Stack("dirt", 63, "plain"));
        assertEquals(WAIT, observe(window(handler, 7, 6, changedCount, EMPTY), receipt, 1));
        List<Stack> changedComponents = new ArrayList<>(full);
        changedComponents.set(0, new Stack("dirt", 64, "custom"));
        assertEquals(WAIT, observe(window(handler, 7, 6, changedComponents, EMPTY), receipt, 2));
        List<Stack> changedPlayer = new ArrayList<>(full);
        changedPlayer.set(89, new Stack("dirt", 1, "plain"));
        assertEquals(WAIT, observe(window(handler, 7, 6, changedPlayer, EMPTY), receipt, 3));
        Stack cursor = new Stack("dirt", 1, "plain");
        assertEquals(WAIT, observe(window(handler, 7, 6, full, cursor), receipt, 4));
        assertEquals(WAIT, observe(window(handler, 7, 6, full, cursor), receipt(7, 6, full, cursor), 5));
        assertEquals(READY, observe(window(handler, 7, 6, full, EMPTY), receipt, 6));
    }

    @Test
    void reconnectCannotReuseThePreviousWorldFullReceipt() {
        assertTrue(packets.acceptFull(world, connection, handler, 7, 90, 7, 1, stock(90, 128), EMPTY));
        Object reconnected = new Object();
        assertTrue(packets.latestMatching(world, reconnected, handler, 7, 90).isEmpty());
        var current = window(handler, 7, 6, stock(90, 128), EMPTY);
        assertEquals(FAIL_CONTEXT, gate.observe(world, player, reconnected, current, null, 2));
    }

    @Test
    void singleChestUsesExactlyIts27ChestAnd36PlayerSlots() {
        var single = new DepotInitialInventoryGate<>(world, player, connection, 0, 3, 60, Stack::equals,
                (Stack stack) -> stack.count() == 0);
        var current = window(handler, 7, 3, stock(63, 128), EMPTY);
        assertEquals(READY, single.observe(world, player, connection, current,
                receipt(7, 3, stock(63, 128), EMPTY), 1));
        assertEquals(FAIL_RECEIPT, single.observe(world, player, connection, current,
                receipt(7, 3, stock(90, 128), EMPTY), 2));
    }

    private DepotInitialInventoryGate<Stack> newGate() {
        return new DepotInitialInventoryGate<>(world, player, connection, 0, 6, 60,
                Stack::equals, stack -> stack.count() == 0);
    }

    private DepotInitialInventoryGate.Decision observe(DepotInitialInventoryGate.Window<Stack> window,
                                                       DepotInitialInventoryGate.Receipt<Stack> receipt, int ticks) {
        return gate.observe(world, player, connection, window, receipt, ticks);
    }

    private DepotInitialInventoryGate.Receipt<Stack> latest(Object currentHandler) {
        return packets.latestMatching(world, connection, currentHandler, 7, 90)
                .map(packet -> new DepotInitialInventoryGate.Receipt<>(packet.stamp(), 6, packet.slots(), packet.cursorStack()))
                .orElse(null);
    }

    private static DepotInitialInventoryGate.Window<Stack> window(Object identity, int sync, int rows,
                                                                  List<Stack> slots, Stack cursor) {
        return new DepotInitialInventoryGate.Window<>(identity, sync, rows, slots, cursor);
    }

    private static DepotInitialInventoryGate.Receipt<Stack> receipt(int sync, int rows, List<Stack> slots, Stack cursor) {
        return new DepotInitialInventoryGate.Receipt<>(new ServerInventorySnapshotStamp(EPOCH, 1, 1, 1, sync, 1),
                rows, slots, cursor);
    }

    private static List<Stack> slots(int count) {
        List<Stack> slots = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) { slots.add(EMPTY); }
        return slots;
    }

    private static List<Stack> stock(int slots, int dirt) {
        List<Stack> result = slots(slots);
        for (int slot = 0; dirt > 0; slot++) {
            int count = Math.min(64, dirt);
            result.set(slot, new Stack("dirt", count, "plain"));
            dirt -= count;
        }
        return result;
    }

    private record Stack(String item, int count, String components) { }
}
