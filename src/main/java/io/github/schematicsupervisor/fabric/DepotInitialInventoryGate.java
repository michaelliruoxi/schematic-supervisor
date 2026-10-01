package io.github.schematicsupervisor.fabric;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** An opened chest is not stock evidence until its full server contents match the owned window. */
final class DepotInitialInventoryGate<T> {
    enum Decision { WAIT, READY, FAIL_CONTEXT, FAIL_WINDOW, FAIL_RECEIPT, FAIL_TIMEOUT }

    record Window<T>(Object identity, int syncId, int rows, List<T> slots, T cursor) { }
    record Receipt<T>(ServerInventorySnapshotStamp stamp, int rows, List<T> slots, T cursor) { }

    private final Object world;
    private final Object player;
    private final Object connection;
    private final int openingSyncId;
    private final int expectedRows;
    private final int timeoutTicks;
    private final BiPredicate<T, T> sameStack;
    private final Predicate<T> emptyStack;
    private Object acceptedHandler;
    private int acceptedSyncId = -1;

    DepotInitialInventoryGate(Object world, Object player, Object connection, int openingSyncId,
                             int expectedRows, int timeoutTicks, BiPredicate<T, T> sameStack,
                             Predicate<T> emptyStack) {
        this.world = Objects.requireNonNull(world);
        this.player = Objects.requireNonNull(player);
        this.connection = Objects.requireNonNull(connection);
        if (openingSyncId < 0 || expectedRows != 3 && expectedRows != 6 || timeoutTicks < 1) {
            throw new IllegalArgumentException("Invalid initial chest window or timeout");
        }
        this.openingSyncId = openingSyncId;
        this.expectedRows = expectedRows;
        this.timeoutTicks = timeoutTicks;
        this.sameStack = Objects.requireNonNull(sameStack);
        this.emptyStack = Objects.requireNonNull(emptyStack);
    }

    Decision observe(Object currentWorld, Object currentPlayer, Object currentConnection,
                     Window<T> window, Receipt<T> packet, int waitedTicks) {
        if (!sameContext(currentWorld, currentPlayer, currentConnection)) { return Decision.FAIL_CONTEXT; }
        if (window == null || !acceptWindow(currentWorld, currentPlayer, currentConnection,
                window.identity(), window.syncId(), window.rows())) { return Decision.FAIL_WINDOW; }
        if (waitedTicks < 0 || waitedTicks > timeoutTicks) { return Decision.FAIL_TIMEOUT; }
        int expectedSlots = expectedRows * 9 + 36;
        if (window.slots() == null || window.slots().size() != expectedSlots || window.cursor() == null) {
            return Decision.FAIL_WINDOW;
        }
        if (packet == null) { return Decision.WAIT; }
        if (packet.stamp() == null || packet.stamp().syncId() != acceptedSyncId
                || packet.rows() != expectedRows || packet.slots() == null
                || packet.slots().size() != expectedSlots || packet.cursor() == null) {
            return Decision.FAIL_RECEIPT;
        }
        if (!emptyStack.test(packet.cursor()) || !emptyStack.test(window.cursor())
                || !sameStack.test(packet.cursor(), window.cursor())) { return Decision.WAIT; }
        for (int slot = 0; slot < expectedSlots; slot++) {
            T server = packet.slots().get(slot);
            T current = window.slots().get(slot);
            if (server == null || current == null || !sameStack.test(server, current)) { return Decision.WAIT; }
        }
        return Decision.READY;
    }

    /** Cancellation can bind a late first response, but cannot replace an already observed window. */
    boolean acceptWindow(Object currentWorld, Object currentPlayer, Object currentConnection,
                         Object handler, int syncId, int rows) {
        if (!sameContext(currentWorld, currentPlayer, currentConnection) || handler == null
                || syncId < 1 || syncId == openingSyncId || rows != expectedRows) { return false; }
        if (acceptedHandler == null) {
            acceptedHandler = handler;
            acceptedSyncId = syncId;
        }
        return acceptedHandler == handler && acceptedSyncId == syncId;
    }

    private boolean sameContext(Object currentWorld, Object currentPlayer, Object currentConnection) {
        return world == currentWorld && player == currentPlayer && connection == currentConnection;
    }
}
