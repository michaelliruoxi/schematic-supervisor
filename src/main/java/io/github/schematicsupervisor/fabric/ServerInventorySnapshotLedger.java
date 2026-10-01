package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** One retained full packet; identities use reference equality and every read owns fresh copies. */
final class ServerInventorySnapshotLedger<T> {
    static final int MAX_SLOTS = 90;

    record Captured<T>(ServerInventorySnapshotStamp stamp, List<T> slots, T cursorStack) {
        Captured { slots = List.copyOf(slots); }
    }

    private final String epoch;
    private final UnaryOperator<T> copy;
    private Object world;
    private Object connection;
    private Object handler;
    private Object lastOpenedHandler;
    private int syncId;
    private int slotCount;
    private long contextGeneration;
    private long openGeneration;
    private long fullSequence;
    private Captured<T> latest;

    ServerInventorySnapshotLedger(UnaryOperator<T> copy) {
        this(UUID.randomUUID().toString(), copy);
    }

    ServerInventorySnapshotLedger(String epoch, UnaryOperator<T> copy) {
        if (!UUID.fromString(epoch).toString().equals(epoch)) {
            throw new IllegalArgumentException("Observer epoch must be a canonical UUID");
        }
        this.epoch = epoch;
        this.copy = Objects.requireNonNull(copy, "copy");
    }

    boolean observeBinding(Object currentWorld, Object currentConnection, Object currentHandler,
                           int currentSyncId, int currentSlotCount) {
        if (currentWorld == null || currentConnection == null) {
            invalidate();
            return false;
        }
        if (world != currentWorld || connection != currentConnection) {
            contextGeneration = Math.incrementExact(contextGeneration);
            world = currentWorld;
            connection = currentConnection;
            handler = null;
            lastOpenedHandler = null;
            latest = null;
        }
        if (currentHandler == null || currentSyncId < 1 || currentSlotCount < 1
                || currentSlotCount > MAX_SLOTS) {
            handler = null;
            latest = null;
            return false;
        }
        if (lastOpenedHandler == currentHandler && (syncId != currentSyncId || slotCount != currentSlotCount)) {
            latest = null;
            return false;
        }
        if (lastOpenedHandler != currentHandler) {
            openGeneration = Math.incrementExact(openGeneration);
            lastOpenedHandler = currentHandler;
            handler = currentHandler;
            syncId = currentSyncId;
            slotCount = currentSlotCount;
            latest = null;
        } else if (handler != currentHandler) {
            // Reusing the same handler object after a local close is not evidence of another opening.
            handler = currentHandler;
            latest = null;
        }
        return true;
    }

    boolean acceptFull(Object currentWorld, Object currentConnection, Object currentHandler,
                       int currentSyncId, int currentSlotCount, int packetSyncId, int packetRevision,
                       List<T> packetSlots, T packetCursor) {
        latest = null;
        try {
            if (!observeBinding(currentWorld, currentConnection, currentHandler, currentSyncId, currentSlotCount)
                    || packetSyncId != syncId || packetRevision < 0 || packetSlots == null
                    || packetSlots.size() != slotCount || packetCursor == null) { return false; }
            List<T> slots = copySlots(packetSlots);
            T cursor = Objects.requireNonNull(copy.apply(packetCursor), "copied cursor");
            long nextSequence = Math.incrementExact(fullSequence);
            ServerInventorySnapshotStamp stamp = new ServerInventorySnapshotStamp(
                    epoch, contextGeneration, openGeneration, nextSequence, syncId, packetRevision);
            latest = new Captured<>(stamp, slots, cursor);
            fullSequence = nextSequence;
            return true;
        } catch (RuntimeException unavailable) {
            latest = null;
            return false;
        }
    }

    Optional<Captured<T>> latestMatching(Object currentWorld, Object currentConnection, Object currentHandler,
                                         int currentSyncId, int currentSlotCount) {
        try {
            if (!observeBinding(currentWorld, currentConnection, currentHandler, currentSyncId, currentSlotCount)
                    || latest == null) { return Optional.empty(); }
            return Optional.of(new Captured<>(latest.stamp(), copySlots(latest.slots()),
                    Objects.requireNonNull(copy.apply(latest.cursorStack()), "copied cursor")));
        } catch (RuntimeException unavailable) {
            latest = null;
            return Optional.empty();
        }
    }

    long fullSequence() { return fullSequence; }

    void invalidate() {
        world = null;
        connection = null;
        handler = null;
        lastOpenedHandler = null;
        latest = null;
    }

    private List<T> copySlots(List<T> source) {
        List<T> result = new ArrayList<>(source.size());
        for (T stack : source) {
            result.add(Objects.requireNonNull(copy.apply(Objects.requireNonNull(stack, "slot")), "copied slot"));
        }
        return List.copyOf(result);
    }
}
