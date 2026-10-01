package io.github.schematicsupervisor.fabric;

/** Bounded cleanup admission; only plain approved pickups count as disposable inventory. */
final class InventoryCleanupPolicy {
    static final int INTERVAL_TICKS = 2_400;
    static final int MINIMUM_FREE_SLOTS = 8;
    static final int REFILL_FREE_SLOTS = 12;
    static final int MAXIMUM_BATCH_STACKS = 36;
    private InventoryCleanupPolicy() { }

    static boolean mayDeferReadTimeout(boolean inPlace, boolean beforeDispatch, boolean pending, boolean unavailable,
                                       InventoryObservation inventory) {
        return inPlace && beforeDispatch && !pending && !unavailable && inventory != null && inventory.available()
                && inventory.emptyMainSlots() >= MINIMUM_FREE_SLOTS;
    }

    static int purchaseReserve(boolean enabled, int otherMaterialSlots, InventoryObservation inventory) {
        if (!enabled || inventory == null || !inventory.available()) { return otherMaterialSlots; }
        // Retain room for pickups when possible without making a feasible one-stack refill impossible.
        return Math.max(otherMaterialSlots, Math.min(REFILL_FREE_SLOTS, Math.max(0, inventory.emptyMainSlots() - 1)));
    }

    static boolean due(boolean enabled, int elapsedTicks, InventoryObservation inventory) {
        return enabled && inventory != null && inventory.available() && hasSurplus(inventory)
                && (elapsedTicks >= INTERVAL_TICKS || inventory.emptyMainSlots() < MINIMUM_FREE_SLOTS);
    }

    static boolean hasSurplus(InventoryObservation inventory) {
        return inventory != null && inventory.available() && inventory.mainSlots().stream().anyMatch(slot ->
                slot.count() > 0 && Boolean.TRUE.equals(slot.plainDefaultComponents())
                        && SurplusPickupPolicy.allowed(slot.itemId()));
    }
}
