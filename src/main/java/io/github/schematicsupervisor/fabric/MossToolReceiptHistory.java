package io.github.schematicsupervisor.fabric;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Bounded historical comparisons of applied hotbar receipts; never a tool-use authorization. */
final class MossToolReceiptHistory<T> {
    private static final int HOTBAR_SLOTS = 9;
    private final UnaryOperator<T> copy;
    private final BiPredicate<T, T> sameOtherComponents;
    private final Function<T, Integer> damage;
    private final Map<Integer, Entry<T>> entries = new HashMap<>();
    private Object world;
    private Object player;
    private Object connection;
    private String epoch;

    /** Stamp and damage refer to the prior receipt; equality compares it with the current receipt. */
    record Transition(String epoch, long sequence, int slot, Integer damage, boolean otherComponentsEqual) {
        Transition {
            if (!validEpoch(epoch) || sequence < 1 || slot < 0 || slot >= HOTBAR_SLOTS
                    || damage != null && damage < 0) {
                throw new IllegalArgumentException("Invalid moss receipt transition");
            }
        }
    }

    private record Entry<T>(PlayerInventoryUpdateLedger.Update<T> update, Transition transition) { }

    MossToolReceiptHistory(UnaryOperator<T> copy, BiPredicate<T, T> sameOtherComponents,
                           Function<T, Integer> damage) {
        this.copy = Objects.requireNonNull(copy);
        this.sameOtherComponents = Objects.requireNonNull(sameOtherComponents);
        this.damage = Objects.requireNonNull(damage);
    }

    Transition observe(Object currentWorld, Object currentPlayer, Object currentConnection,
                       PlayerInventoryUpdateLedger.Update<T> update) {
        if (world != currentWorld || player != currentPlayer || connection != currentConnection) {
            entries.clear();
            epoch = null;
            world = currentWorld;
            player = currentPlayer;
            connection = currentConnection;
        }
        if (world == null || player == null || connection == null) {
            entries.clear();
            epoch = null;
            return null;
        }
        if (update == null || update.slot() < 0 || update.slot() >= HOTBAR_SLOTS) { return null; }
        var stamp = update.stamp();
        if (stamp == null || !validEpoch(stamp.epoch())
                || stamp.sequence() < 1 || update.stack() == null) {
            invalidate(update.slot());
            return null;
        }
        if (!stamp.epoch().equals(epoch)) {
            entries.clear();
            epoch = stamp.epoch();
        }
        Entry<T> previous = entries.get(update.slot());
        if (previous != null) {
            long priorSequence = previous.update().stamp().sequence();
            if (stamp.sequence() == priorSequence) { return previous.transition(); }
            if (stamp.sequence() < priorSequence) {
                // A reversed stream invalidates this slot's comparison chain, without retaining stale data.
                invalidate(update.slot());
                return null;
            }
        }
        try {
            T current = Objects.requireNonNull(copy.apply(update.stack()));
            Transition transition = null;
            if (previous != null && previous.update().stack() != null) {
                T prior = Objects.requireNonNull(copy.apply(previous.update().stack()));
                transition = new Transition(previous.update().stamp().epoch(), previous.update().stamp().sequence(),
                        update.slot(), damage.apply(copy.apply(prior)), sameOtherComponents.test(prior, copy.apply(current)));
            }
            entries.put(update.slot(), new Entry<>(new PlayerInventoryUpdateLedger.Update<>(stamp, update.slot(), current), transition));
            return transition;
        } catch (RuntimeException unavailable) {
            entries.put(update.slot(), new Entry<>(new PlayerInventoryUpdateLedger.Update<>(stamp, update.slot(), null), null));
            throw unavailable;
        }
    }

    private void invalidate(int slot) {
        Entry<T> previous = entries.get(slot);
        if (previous != null) {
            // Retain the high-water stamp so repeated stale observations cannot establish a new baseline.
            entries.put(slot, new Entry<>(new PlayerInventoryUpdateLedger.Update<>(previous.update().stamp(), slot, null), null));
        }
    }

    private static boolean validEpoch(String value) {
        return value != null && value.length() == 36
                && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
