package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Bounded packet evidence; object identities and defensive copies are independent of local prediction. */
final class ServerShopCursorLedger<T> {
    record Captured<T>(ServerShopCursorStamp stamp, T cursorStack) { }
    private final String epoch;
    private final UnaryOperator<T> copy;
    private Object world;
    private Object connection;
    private Object handler;
    private Object lastHandler;
    private int syncId;
    private int slotCount;
    private long contextGeneration;
    private long handlerGeneration;
    private long sequence;
    private Captured<T> latest;

    ServerShopCursorLedger(UnaryOperator<T> copy) { this(UUID.randomUUID().toString(), copy); }
    ServerShopCursorLedger(String epoch, UnaryOperator<T> copy) {
        if (!UUID.fromString(epoch).toString().equals(epoch)) {
            throw new IllegalArgumentException("cursor observer epoch must be a canonical UUID");
        }
        this.epoch = epoch;
        this.copy = Objects.requireNonNull(copy, "copy");
    }

    Optional<ServerShopCursorStamp> mark(Object currentWorld, Object currentConnection,
                                        Object currentHandler, int currentSyncId, int currentSlotCount) {
        return bind(currentWorld, currentConnection, currentHandler, currentSyncId, currentSlotCount)
                ? Optional.of(stamp()) : Optional.empty();
    }

    boolean acceptFull(Object currentWorld, Object currentConnection, Object currentHandler,
                       int currentSyncId, int currentSlotCount, int packetSyncId, int packetSlotCount,
                       T packetCursor, boolean vanillaApplied) {
        if (!vanillaApplied || packetSyncId != currentSyncId || packetSlotCount != currentSlotCount) {
            return false;
        }
        return acceptCursor(currentWorld, currentConnection, currentHandler, currentSyncId,
                currentSlotCount, packetCursor, true);
    }

    boolean acceptCursor(Object currentWorld, Object currentConnection, Object currentHandler,
                         int currentSyncId, int currentSlotCount, T packetCursor, boolean vanillaApplied) {
        if (!vanillaApplied) { return false; }
        try {
            if (!bind(currentWorld, currentConnection, currentHandler, currentSyncId, currentSlotCount)) {
                return false;
            }
            T captured = Objects.requireNonNull(copy.apply(Objects.requireNonNull(packetCursor, "cursor")), "copied cursor");
            sequence = Math.incrementExact(sequence);
            latest = new Captured<>(stamp(), captured);
            return true;
        } catch (RuntimeException unavailable) {
            invalidate();
            return false;
        }
    }

    Optional<Captured<T>> latestMatching(Object currentWorld, Object currentConnection,
                                        Object currentHandler, int currentSyncId, int currentSlotCount) {
        try {
            if (!bind(currentWorld, currentConnection, currentHandler, currentSyncId, currentSlotCount)
                    || latest == null) { return Optional.empty(); }
            return Optional.of(new Captured<>(latest.stamp(),
                    Objects.requireNonNull(copy.apply(latest.cursorStack()), "copied cursor")));
        } catch (RuntimeException unavailable) {
            invalidate();
            return Optional.empty();
        }
    }

    void invalidate() {
        world = null;
        connection = null;
        handler = null;
        lastHandler = null;
        latest = null;
    }

    private boolean bind(Object currentWorld, Object currentConnection, Object currentHandler,
                         int currentSyncId, int currentSlotCount) {
        if (currentWorld == null || currentConnection == null) { invalidate(); return false; }
        if (world != currentWorld || connection != currentConnection) {
            contextGeneration = Math.incrementExact(contextGeneration);
            world = currentWorld;
            connection = currentConnection;
            handler = null;
            lastHandler = null;
            latest = null;
        }
        if (currentHandler == null || currentSyncId < 1 || currentSlotCount < 45
                || currentSlotCount > 90 || (currentSlotCount - 36) % 9 != 0) {
            handler = null;
            latest = null;
            return false;
        }
        if (lastHandler == currentHandler && (syncId != currentSyncId || slotCount != currentSlotCount)) {
            handler = null;
            latest = null;
            return false;
        }
        if (handler != currentHandler) {
            handlerGeneration = Math.incrementExact(handlerGeneration);
            handler = currentHandler;
            lastHandler = currentHandler;
            syncId = currentSyncId;
            slotCount = currentSlotCount;
            latest = null;
        }
        return true;
    }

    private ServerShopCursorStamp stamp() {
        return new ServerShopCursorStamp(epoch, contextGeneration, handlerGeneration, sequence, syncId);
    }
}
