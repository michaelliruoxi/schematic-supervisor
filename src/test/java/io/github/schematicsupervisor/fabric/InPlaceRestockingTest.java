package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import io.github.schematicsupervisor.core.Material;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InPlaceRestockingTest {
    @Test void optionalCleanupReadTimeoutLeavesRoomForInPlacePurchasesAndLaterCleanup() {
        var observed = inventory(24, "minecraft:melon_seeds", true);
        assertTrue(InventoryCleanupPolicy.mayDeferReadTimeout(true, true, false, false, observed));
        int reserve = InventoryCleanupPolicy.purchaseReserve(true, 0, observed);
        assertEquals(DirtRestockCapacityPolicy.Route.SHOP,
                DirtRestockCapacityPolicy.decide(768, observed, 0, false, reserve, true).route());
        assertFalse(InventoryCleanupPolicy.due(true, 0, observed));
        assertTrue(InventoryCleanupPolicy.due(true, InventoryCleanupPolicy.INTERVAL_TICKS, observed));
    }

    @Test void timeoutsNeverDeferPendingUnknownPostDispatchOrCapacityCriticalCleanup() {
        var observed = inventory(24, "minecraft:melon_seeds", true);
        assertFalse(InventoryCleanupPolicy.mayDeferReadTimeout(true, true, true, false, observed));
        assertFalse(InventoryCleanupPolicy.mayDeferReadTimeout(true, true, false, true, observed));
        assertFalse(InventoryCleanupPolicy.mayDeferReadTimeout(true, false, false, false, observed));
        assertFalse(InventoryCleanupPolicy.mayDeferReadTimeout(false, true, false, false, observed));
        assertFalse(InventoryCleanupPolicy.mayDeferReadTimeout(true, true, false, false, InventoryObservation.unavailable("missing")));
        for (int empty = 0; empty < InventoryCleanupPolicy.MINIMUM_FREE_SLOTS; empty++) {
            assertFalse(InventoryCleanupPolicy.mayDeferReadTimeout(true, true, false, false, inventory(empty, "minecraft:melon_seeds", true)));
        }
        assertTrue(InventoryCleanupPolicy.mayDeferReadTimeout(true, true, false, false,
                inventory(InventoryCleanupPolicy.MINIMUM_FREE_SLOTS, "minecraft:melon_seeds", true)));
    }
    @Test void purchasesLeavePickupHeadroomWhilePreservingOtherMaterialReservations() {
        assertEquals(12, InventoryCleanupPolicy.purchaseReserve(true, 2, inventory(30, "minecraft:moss_block", true)));
        assertEquals(16, InventoryCleanupPolicy.purchaseReserve(true, 16, inventory(30, "minecraft:moss_block", true)));
        assertEquals(2, InventoryCleanupPolicy.purchaseReserve(false, 2, inventory(30, "minecraft:moss_block", true)));
    }

    @Test void smallInventoriesCanStillBuyOneStackWithoutSpendingAnotherMaterialsSlot() {
        assertEquals(0, InventoryCleanupPolicy.purchaseReserve(true, 0, inventory(1, "minecraft:moss_block", true)));
        assertEquals(1, InventoryCleanupPolicy.purchaseReserve(true, 0, inventory(2, "minecraft:moss_block", true)));
        assertEquals(1, InventoryCleanupPolicy.purchaseReserve(true, 1, inventory(1, "minecraft:moss_block", true)));
    }

    @Test void dirtPurchasesIgnoreUnknownAndAvailableDepotStockOnlyWhenEnabled() {
        var inventory = inventory(30, "minecraft:moss_block", true);
        for (long stock : new long[] {0, 4096}) {
            var result = DirtRestockCapacityPolicy.decide(128, inventory, stock, false, 0, true);
            assertEquals(DirtRestockCapacityPolicy.Route.SHOP, result.route());
        }
        assertEquals(DirtRestockCapacityPolicy.Route.BLOCKED,
                DirtRestockCapacityPolicy.decide(128, inventory, 0, false, 0, false).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT,
                DirtRestockCapacityPolicy.decide(128, inventory, 4096, true, 0, false).route());
    }

    @Test void localDirtPurchasesStillRequireObservedCapacityAndReservations() {
        assertTrue(DirtRestockCapacityPolicy.decide(1, InventoryObservation.unavailable("missing"), 0, false, 0, true).blocked());
        assertTrue(DirtRestockCapacityPolicy.decide(1, inventory(0, "minecraft:dirt", true), 0, false, 1, true).blocked());
        assertTrue(DirtRestockCapacityPolicy.decide(1, inventory(1, "minecraft:moss_block", true), 0, false, 1, true).blocked());
    }

    @Test void everySupportedPurchaseUsesBoundedShopCapacityWithoutDepotTravel() {
        for (Material material : List.of(Material.DIRT, Material.GLOWSTONE, Material.BIRCH_PLANKS)) {
            var request = new MaterialPurchaseBudget.Request(material, 2000, 0, 0, 1, 4096, true, false, capacities(3), 1);
            var result = MaterialPurchaseBudget.decide(request, true);
            assertEquals(MaterialPurchaseBudget.Route.PURCHASE, result.route());
            assertEquals(2, result.maximumStacks());
            assertEquals(0, result.depotWithdrawalUnits());
            assertEquals(MaterialPurchaseBudget.Route.SCAN_DEPOTS, MaterialPurchaseBudget.decide(request).route());
        }
    }

    @Test void inPlacePreferenceDoesNotExpandPlanBudgetOrAuthorizeToolsAndSeeds() {
        var spent = new MaterialPurchaseBudget.Request(Material.GLOWSTONE, 64, 64, 0, 1, 0, true, false, capacities(30), 0);
        assertEquals(MaterialPurchaseBudget.Route.BLOCKED, MaterialPurchaseBudget.decide(spent, true).route());
        for (Material material : List.of(Material.HOE, Material.WHEAT_SEEDS, Material.FOOD)) {
            var request = new MaterialPurchaseBudget.Request(material, 64, 0, 0, 1, 0, true, false, capacities(30), 0);
            assertEquals(MaterialPurchaseBudget.Route.BLOCKED, MaterialPurchaseBudget.decide(request, true).route());
        }
    }

    @Test void cleanupRunsPeriodicallyOrWhenSpaceIsLowButOnlyForApprovedPlainPickups() {
        for (String id : SurplusPickupPolicy.ITEM_IDS) {
            assertFalse(InventoryCleanupPolicy.due(true, 2399, inventory(30, id, true)));
            assertTrue(InventoryCleanupPolicy.due(true, 2400, inventory(30, id, true)));
            assertTrue(InventoryCleanupPolicy.due(true, 1, inventory(7, id, true)));
            assertFalse(InventoryCleanupPolicy.due(true, 1, inventory(8, id, true)));
            assertFalse(InventoryCleanupPolicy.due(false, 5000, inventory(1, id, true)));
            assertFalse(InventoryCleanupPolicy.due(true, 5000, inventory(1, id, false)));
        }
        for (String id : List.of("minecraft:diamond_hoe", "minecraft:tripwire_hook", "minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks")) {
            assertFalse(InventoryCleanupPolicy.due(true, 5000, inventory(0, id, true)));
        }
        assertFalse(InventoryCleanupPolicy.due(true, 5000, InventoryObservation.unavailable("missing")));
    }

    private static InventoryObservation inventory(int empty, String itemId, boolean plain) {
        var main = new ArrayList<InventoryObservation.Slot>();
        for (int index = 0; index < 36; index++) {
            main.add(index < empty ? InventoryObservation.Slot.empty(index) : new InventoryObservation.Slot(
                    index, itemId, 1, 64, null, false, false, null, null, false, "", plain));
        }
        return InventoryObservation.capture(main, InventoryObservation.Slot.empty(40), 0, 64,
                new InventoryObservation.Menu(false, "none", "", 0, InventoryObservation.Slot.empty(-1)));
    }

    private static List<MaterialPurchaseBudget.SlotCapacity> capacities(int empty) {
        var result = new ArrayList<MaterialPurchaseBudget.SlotCapacity>();
        for (int index = 0; index < 36; index++) {
            result.add(new MaterialPurchaseBudget.SlotCapacity(index, index < empty
                    ? MaterialPurchaseBudget.SlotKind.EMPTY : MaterialPurchaseBudget.SlotKind.PROTECTED, index < empty ? 64 : 0));
        }
        return result;
    }
}
