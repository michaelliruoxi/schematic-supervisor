package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;

/** Chooses a feasible Dirt supply route before any shop or depot interaction. */
final class DirtRestockCapacityPolicy {
    static final String BLOCKER_PREFIX = "Inventory capacity blocked:";

    private DirtRestockCapacityPolicy() { }

    enum Route { SATISFIED, SHOP, DEPOT, STORAGE, USE_AVAILABLE, BLOCKED }

    record Decision(Route route, String detail) {
        boolean blocked() { return route == Route.BLOCKED; }
    }

    static Decision decide(long requiredAvailable, InventoryObservation inventory, long depotDirt) {
        return decide(requiredAvailable, inventory, depotDirt, true, 0);
    }

    static Decision decide(long requiredAvailable, InventoryObservation inventory, long depotDirt,
                           boolean depotScanComplete, int reservedEmptySlots) {
        return decide(requiredAvailable, inventory, depotDirt, depotScanComplete, reservedEmptySlots, false);
    }

    static Decision decide(long requiredAvailable, InventoryObservation inventory, long depotDirt,
                           boolean depotScanComplete, int reservedEmptySlots, boolean buyInPlace) {
        return decide(requiredAvailable, inventory, depotDirt, depotScanComplete, reservedEmptySlots, buyInPlace, true);
    }

    /**
     * With shop purchases off, registered chests are the only supply: the core plans the shortage from
     * them and pauses with what is still missing, so only a refill that cannot fit is decided here.
     */
    static Decision decide(long requiredAvailable, InventoryObservation inventory, long depotDirt,
                           boolean depotScanComplete, int reservedEmptySlots, boolean buyInPlace,
                           boolean shopEnabled) {
        if (requiredAvailable < 0 || depotDirt < 0 || reservedEmptySlots < 0 || reservedEmptySlots > 36) {
            throw new IllegalArgumentException("Dirt requirements and depot stock must not be negative");
        }
        if (requiredAvailable == 0) { return new Decision(Route.SATISFIED, ""); }
        if (!inventory.available()) {
            return new Decision(Route.BLOCKED, BLOCKER_PREFIX
                    + " main inventory capacity is unavailable; no supply interaction was started.");
        }
        long availableDirt = inventory.mainMaterialTotals().get(Material.DIRT);
        long shortage = Math.max(0, requiredAvailable - availableDirt);
        if (shortage == 0) { return new Decision(Route.SATISFIED, ""); }
        if (inventory.emptyMainSlots() < reservedEmptySlots) {
            return new Decision(Route.BLOCKED, BLOCKER_PREFIX
                    + " the other current material requirements need " + reservedEmptySlots
                    + " empty main slots, but only " + inventory.emptyMainSlots() + " are available.");
        }
        long usableCapacity = Math.max(0, inventory.dirtCapacity() - 64L * reservedEmptySlots);
        long depotUnits = Math.min(shortage, depotDirt);
        if (!shopEnabled) {
            if (depotUnits == 0 || usableCapacity >= depotUnits) { return new Decision(Route.DEPOT, ""); }
            if (availableDirt > 0) {
                return new Decision(Route.USE_AVAILABLE,
                        "Using the " + availableDirt + " Dirt already in inventory; the next refill needs more space.");
            }
            return new Decision(Route.BLOCKED, BLOCKER_PREFIX + " dirt shortage=" + shortage
                    + ", compatible dirt capacity=" + usableCapacity + ", reserved main slots=" + reservedEmptySlots
                    + ", scanned depot dirt=" + depotDirt + ". The registered-chest refill does not fit, and shop"
                    + " purchases are off. Resume becomes available when the inventory has room.");
        }
        if (!buyInPlace && depotUnits > 0 && usableCapacity >= depotUnits) {
            return new Decision(Route.DEPOT, "");
        }
        if (!buyInPlace && !depotScanComplete) {
            return new Decision(Route.BLOCKED,
                    "Depot scan incomplete: refresh registered chests before purchasing Dirt; unknown stock is not zero.");
        }
        if (inventory.emptyMainSlots() > reservedEmptySlots) { return new Decision(Route.SHOP, ""); }
        if (availableDirt > 0) {
            return new Decision(Route.USE_AVAILABLE,
                    "Using the " + availableDirt + " Dirt already in inventory; the next refill needs more space.");
        }
        return new Decision(Route.BLOCKED, BLOCKER_PREFIX + " dirt shortage=" + shortage
                + ", empty main slots=" + inventory.emptyMainSlots() + ", compatible dirt capacity=" + usableCapacity
                + ", reserved main slots=" + reservedEmptySlots
                + ", scanned depot dirt=" + depotDirt
                + ". Whole-stack shop purchases need an empty main slot; "
                + (usableCapacity < shortage
                        ? "the exact depot refill does not fit."
                        : "the compatible partial stack has insufficient scanned depot supply.")
                + " Resume becomes available when the shortage is supplied or a refill route has room.");
    }
}
