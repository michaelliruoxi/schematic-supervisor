package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.LightingRestockReserve;

final class RestockBatchPolicy {
    private RestockBatchPolicy() {
    }

    static long targetAvailable(long remaining, long depotStock, long inventoryCapacity) {
        return targetAvailable(remaining, depotStock, inventoryCapacity, 1, 0);
    }

    /** Seed refills use all available room and carry across planting work orders. */
    static long seedTargetAvailable(long depotStock, long inventoryCapacity) {
        return targetAvailable(Long.MAX_VALUE, depotStock, inventoryCapacity, 64, 0);
    }

    /** Called only for an exhausted Glowstone stack in a scanned ordinary work order. */
    static long glowstoneTargetAvailable(
            long currentMissing,
            long upcomingSameLayer,
            long remainingPlanDemand,
            long depotStock,
            long inventoryCapacity,
            int reservedSlots
    ) {
        if (currentMissing < 1 || upcomingSameLayer < 0
                || upcomingSameLayer > LightingRestockReserve.STACK_SIZE || remainingPlanDemand < 1) {
            throw new IllegalArgumentException("Glowstone demand requires current work and a positive remaining plan budget");
        }
        // Saturate before adding: the requested target can never exceed one stack.
        long current = Math.min(currentMissing, LightingRestockReserve.STACK_SIZE);
        long remaining = Math.min(remainingPlanDemand,
                current + Math.min(upcomingSameLayer, LightingRestockReserve.STACK_SIZE - current));
        return targetAvailable(remaining, depotStock, inventoryCapacity,
                LightingRestockReserve.STACK_SIZE, reservedSlots);
    }

    static long targetAvailable(
            long remaining,
            long depotStock,
            long inventoryCapacity,
            int stackSize,
            int reservedSlots
    ) {
        if (remaining < 1) {
            throw new IllegalArgumentException("remaining work must be positive");
        }
        if (depotStock < 0 || inventoryCapacity < 0) {
            throw new IllegalArgumentException("stock and capacity must be non-negative");
        }
        if (stackSize < 1 || reservedSlots < 0) {
            throw new IllegalArgumentException("stack size must be positive and reserved slots non-negative");
        }
        long reservedCapacity = Math.multiplyExact((long) stackSize, reservedSlots);
        long usableCapacity = Math.max(0, inventoryCapacity - reservedCapacity);
        if (depotStock == 0 || usableCapacity == 0) {
            // Preserve the outstanding demand. The runtime must choose a feasible supply
            // route or expose a capacity blocker; returning zero would hide missing work.
            return 1;
        }
        return Math.max(1, Math.min(remaining, Math.min(depotStock, usableCapacity)));
    }
}
