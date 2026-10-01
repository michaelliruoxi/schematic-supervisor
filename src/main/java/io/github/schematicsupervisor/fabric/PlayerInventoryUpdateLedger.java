package io.github.schematicsupervisor.fabric;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

/** Bounded passive receipts for actually applied main-inventory packets in one live context. */
final class PlayerInventoryUpdateLedger<T> {
    record Stamp(String epoch, long sequence) { }
    record Update<T>(Stamp stamp, int slot, T stack) { }
    private final String epoch = UUID.randomUUID().toString();
    private final UnaryOperator<T> copy;
    private final BiPredicate<T, T> same;
    private final Map<Integer, Update<T>> updates = new HashMap<>();
    private Object world;
    private Object player;
    private Object connection;
    private long sequence;

    PlayerInventoryUpdateLedger(UnaryOperator<T> copy, BiPredicate<T, T> same) {
        this.copy = Objects.requireNonNull(copy);
        this.same = Objects.requireNonNull(same);
    }

    Stamp mark(Object currentWorld, Object currentPlayer, Object currentConnection) {
        bind(currentWorld, currentPlayer, currentConnection);
        return new Stamp(epoch, sequence);
    }

    void applied(Object currentWorld, Object currentPlayer, Object currentConnection, int slot, T packet, T actual) {
        bind(currentWorld, currentPlayer, currentConnection);
        if (world == null || player == null || connection == null || slot < 0 || slot >= 36
                || packet == null || actual == null || !same.test(packet, actual)) { return; }
        updates.put(slot, new Update<>(new Stamp(epoch, ++sequence), slot, copy.apply(packet)));
    }

    Optional<Update<T>> latest(Object currentWorld, Object currentPlayer, Object currentConnection, int slot) {
        bind(currentWorld, currentPlayer, currentConnection);
        Update<T> update = updates.get(slot);
        return update == null ? Optional.empty()
                : Optional.of(new Update<>(update.stamp(), update.slot(), copy.apply(update.stack())));
    }

    private void bind(Object currentWorld, Object currentPlayer, Object currentConnection) {
        if (world != currentWorld || player != currentPlayer || connection != currentConnection) {
            updates.clear();
            world = currentWorld;
            player = currentPlayer;
            connection = currentConnection;
        }
    }
}
