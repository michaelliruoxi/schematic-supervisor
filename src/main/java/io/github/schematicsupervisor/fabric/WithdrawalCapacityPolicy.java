package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.MaterialQuantities;

/** Admission runs after depot scans and before creating a durable withdrawal intent. */
final class WithdrawalCapacityPolicy {
    enum Route { READY, STORE, DISPOSE, BLOCKED }

    private WithdrawalCapacityPolicy() { }

    static Route decide(MaterialQuantities shortage, InventoryObservation inventory,
                        boolean storageAttempted, boolean eligiblePickups, boolean disposalEnabled) {
        boolean fits = inventory.available() && inventory.normalMaterialCapacity() != null;
        long neededEmptySlots = 0;
        if (fits) {
            for (var material : InventoryObservation.CAPACITY_MATERIALS) {
                long needed = shortage.get(material);
                long capacity = inventory.normalMaterialCapacity().get(material);
                if (needed > capacity) { fits = false; }
                long existingRoom = Math.max(0, capacity - inventory.emptyMainSlots() * 64L);
                long remaining = Math.max(0, needed - existingRoom);
                neededEmptySlots += remaining / 64 + (remaining % 64 == 0 ? 0 : 1);
            }
            fits &= neededEmptySlots <= inventory.emptyMainSlots();
        }
        if (shortage.isEmpty() || fits) { return Route.READY; }
        if (!storageAttempted && eligiblePickups) { return Route.STORE; }
        return storageAttempted && disposalEnabled ? Route.DISPOSE : Route.BLOCKED;
    }

    /** Only a settled pre-click rejection may release the durable withdrawal intent. */
    static boolean settledCapacityRejection(boolean capacityRejected, boolean cleanupSucceeded,
                                            boolean automationBlocked, boolean pendingAction,
                                            boolean heldSource) {
        return capacityRejected && cleanupSucceeded && !automationBlocked && !pendingAction && !heldSource;
    }

    static boolean admitsSource(long capacity, long remaining, int sourceCount) {
        return sourceCount > 0 && remaining > 0 && capacity >= Math.min(remaining, sourceCount);
    }
}
