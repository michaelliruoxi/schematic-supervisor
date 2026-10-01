package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure purchase limits over current plain-material observations; this policy sends no input. */
final class MaterialPurchaseBudget {
    static final int STACK_SIZE = 64;
    static final int MAXIMUM_STACKS_PER_CLICK = 9;
    private MaterialPurchaseBudget() { }

    /**
     * Reserve room for other requirements in the current execution request only. Aggregate inventory
     * counts do not prove compatible partial-stack room, so reserve whole empty slots conservatively.
     * Food has no single stack limit in these facts; one slot per missing item is the safe bound.
     * This does not create seed, tool, food, or future-layer demands or authorize their purchase.
     */
    static int reservedSlotsForOtherShortages(Material selected, MaterialQuantities requiredAvailable,
                                              MaterialQuantities inventory) {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(requiredAvailable, "requiredAvailable");
        Objects.requireNonNull(inventory, "inventory");
        int reserved = 0;
        for (Material material : requiredAvailable.asMap().keySet()) {
            if (material.equals(selected)) { continue; }
            long shortage = Math.max(0, requiredAvailable.get(material) - inventory.get(material));
            int stackSize = material.kind() == Material.Kind.TOOL || material.kind() == Material.Kind.FOOD
                    ? 1 : STACK_SIZE;
            long slots = shortage / stackSize + (shortage % stackSize == 0 ? 0 : 1);
            // Saturating at main36 makes every impossible larger reservation block purchasing.
            if (slots >= 36 - reserved) { return 36; }
            reserved += (int) slots;
        }
        return reserved;
    }

    enum Route { SATISFIED, SCAN_DEPOTS, DEPOT, PURCHASE, BLOCKED }
    enum SlotKind { EMPTY, COMPATIBLE_PLAIN_STACK, PROTECTED }

    /** Room is observed insertion-compatible capacity for this exact plain item, after actual slot limits. */
    record SlotCapacity(int mainIndex, SlotKind kind, int compatibleRoom) {
        SlotCapacity {
            Objects.requireNonNull(kind, "kind");
            if (mainIndex < 0 || mainIndex >= 36 || compatibleRoom < 0 || compatibleRoom > STACK_SIZE
                    || kind == SlotKind.PROTECTED && compatibleRoom != 0
                    || kind == SlotKind.COMPATIBLE_PLAIN_STACK && compatibleRoom == STACK_SIZE) {
                throw new IllegalArgumentException("invalid main slot capacity evidence");
            }
        }
    }

    /**
     * Inventory is usable plain material in main36, excluding cursor/offhand and protected components.
     * RequiredAvailable is the current execution request, not an estimate from the full schematic.
     * Scanned stock is usable same-material stock from the current registered-depot scan.
     */
    record Request(Material material, long plannedTotal, long consumed, long inventory,
                   long requiredAvailable, long scannedDepotStock, boolean inventoryAvailable,
                   boolean depotScanComplete, List<SlotCapacity> mainSlots, int reservedEmptySlots) {
        Request {
            Objects.requireNonNull(material, "material");
            mainSlots = List.copyOf(mainSlots);
            if (plannedTotal < 0 || consumed < 0 || inventory < 0 || requiredAvailable < 0
                    || scannedDepotStock < 0 || reservedEmptySlots < 0 || reservedEmptySlots > 36
                    || mainSlots.size() != 36 && (inventoryAvailable || !mainSlots.isEmpty())) {
                throw new IllegalArgumentException("invalid bounded purchase request");
            }
            for (int index = 0; index < mainSlots.size(); index++) {
                if (mainSlots.get(index).mainIndex() != index) {
                    throw new IllegalArgumentException("main capacity facts must contain every slot exactly once in order");
                }
            }
        }
    }

    record Decision(Route route, long currentShortage, long remainingPlanDemand, int usableCapacity,
                    long depotWithdrawalUnits, int maximumStacks, int roundingSurplus, String detail) {
        Decision {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(detail, "detail");
            if (currentShortage < 0 || remainingPlanDemand < 0 || usableCapacity < 0 || usableCapacity > 36 * STACK_SIZE
                    || depotWithdrawalUnits < 0 || maximumStacks < 0 || maximumStacks > MAXIMUM_STACKS_PER_CLICK
                    || roundingSurplus < 0 || roundingSurplus >= STACK_SIZE || detail.length() > 256
                    || (route == Route.PURCHASE) != (maximumStacks > 0)
                    || (route == Route.DEPOT) != (depotWithdrawalUnits > 0)
                    || route != Route.PURCHASE && roundingSurplus != 0
                    || route == Route.PURCHASE && (currentShortage == 0 || remainingPlanDemand == 0
                        || maximumStacks * STACK_SIZE > usableCapacity
                        || roundingSurplus != Math.max(0, (long) maximumStacks * STACK_SIZE - remainingPlanDemand))
                    || route == Route.DEPOT && (depotWithdrawalUnits > usableCapacity
                        || depotWithdrawalUnits > currentShortage || depotWithdrawalUnits > remainingPlanDemand)) {
                throw new IllegalArgumentException("invalid bounded purchase decision");
            }
        }

        int maximumPurchaseUnits() { return maximumStacks * STACK_SIZE; }

        boolean needsCapacityRecovery() {
            return route == Route.BLOCKED && currentShortage > 0 && remainingPlanDemand > 0
                    && usableCapacity < STACK_SIZE;
        }
    }

    static Decision decide(Request request) { return decide(request, false); }

    static Decision decide(Request request, boolean buyInPlace) {
        Objects.requireNonNull(request, "request");
        if (!supported(request.material())) {
            return idle(Route.BLOCKED, 0, 0, 0, "Only Dirt, Glowstone, and Birch Planks have block purchase budgets.");
        }
        if (!request.inventoryAvailable()) {
            return idle(Route.BLOCKED, 0, 0, 0, "Current main inventory and compatible capacity are unavailable.");
        }
        long currentShortage = Math.max(0, request.requiredAvailable() - request.inventory());
        long remainingWork = Math.max(0, request.plannedTotal() - request.consumed());
        long remainingDemand = Math.max(0, remainingWork - request.inventory());
        if (currentShortage == 0) {
            return idle(Route.SATISFIED, 0, remainingDemand, 0, "Current execution already has its required material.");
        }
        if (remainingDemand == 0) {
            return idle(Route.BLOCKED, currentShortage, 0, 0,
                    "Execution requests material outside the remaining planned purchase budget.");
        }
        List<SlotCapacity> empty = request.mainSlots().stream().filter(slot -> slot.kind() == SlotKind.EMPTY)
                .sorted(Comparator.comparingInt(SlotCapacity::compatibleRoom).reversed()).toList();
        if (empty.size() < request.reservedEmptySlots()) {
            return idle(Route.BLOCKED, currentShortage, remainingDemand, 0,
                    "The required reserved empty main slots are not available.");
        }
        int capacity = request.mainSlots().stream().mapToInt(SlotCapacity::compatibleRoom).sum();
        // Reserving the largest available slots is conservative and cannot spend another material's slot.
        for (int index = 0; index < request.reservedEmptySlots(); index++) {
            capacity -= empty.get(index).compatibleRoom();
        }
        if (capacity == 0) {
            return idle(Route.BLOCKED, currentShortage, remainingDemand, 0,
                    "No observed compatible capacity remains after reserving empty slots.");
        }
        if (!buyInPlace && !request.depotScanComplete()) {
            return idle(Route.SCAN_DEPOTS, currentShortage, remainingDemand, capacity,
                    "Finish the registered-depot scan before treating missing supply as zero.");
        }
        if (!buyInPlace && request.scannedDepotStock() > 0) {
            long withdrawal = Math.min(Math.min(currentShortage, remainingDemand),
                    Math.min(request.scannedDepotStock(), capacity));
            return new Decision(Route.DEPOT, currentShortage, remainingDemand, capacity, withdrawal, 0, 0,
                    "Use observed registered-depot stock first, then recompute after its receipt.");
        }
        long demandStacks = remainingDemand / STACK_SIZE + (remainingDemand % STACK_SIZE == 0 ? 0 : 1);
        int stacks = (int) Math.min(MAXIMUM_STACKS_PER_CLICK, Math.min(capacity / STACK_SIZE, demandStacks));
        if (stacks == 0) {
            return idle(Route.BLOCKED, currentShortage, remainingDemand, capacity,
                    "A complete 64-unit purchase cannot fit the observed compatible capacity.");
        }
        int surplus = (int) Math.max(0, (long) stacks * STACK_SIZE - remainingDemand);
        return new Decision(Route.PURCHASE, currentShortage, remainingDemand, capacity, 0, stacks, surplus,
                "Buy at most the bounded 64-unit stack amount; confirm its receipt before recalculating.");
    }

    private static boolean supported(Material material) {
        return material == Material.DIRT || material == Material.GLOWSTONE || material == Material.BIRCH_PLANKS;
    }

    private static Decision idle(Route route, long shortage, long demand, int capacity, String detail) {
        return new Decision(route, shortage, demand, capacity, 0, 0, 0, detail);
    }
}
