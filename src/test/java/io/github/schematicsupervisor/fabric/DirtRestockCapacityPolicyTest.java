package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.Material;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class DirtRestockCapacityPolicyTest {
    @Test
    void existingDirtCanKeepBuildingWithEverySlotOccupied() {
        InventoryObservation full = inventory(0, 64, true);
        assertEquals(DirtRestockCapacityPolicy.Route.SATISFIED, decide(1, full, 0).route());
        assertEquals(DirtRestockCapacityPolicy.Route.SATISFIED, decide(64, full, 0).route());
    }

    @Test
    void aWholeStackShopPurchaseRequiresAnActuallyEmptyMainSlot() {
        assertEquals(DirtRestockCapacityPolicy.Route.SHOP, decide(1, inventory(1, 0, true), 0).route());
        var blocked = decide(1, inventory(0, 0, true), 10_000);
        assertTrue(blocked.blocked());
        assertTrue(blocked.detail().startsWith(DirtRestockCapacityPolicy.BLOCKER_PREFIX));
        assertTrue(blocked.detail().contains("dirt shortage=1, empty main slots=0, compatible dirt capacity=0"));
    }

    @Test
    void compatiblePartialStackUsesDepotWithoutAWholeStackPurchase() {
        InventoryObservation partial = inventory(0, 32, true);
        assertEquals(0, partial.emptyMainSlots());
        assertEquals(32L, partial.dirtCapacity());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, decide(64, partial, 32).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, decide(33, partial, 1).route());
    }

    @Test
    void partialDepotRefillPrecedesUsingExistingDirtOrBuying() {
        InventoryObservation partial = inventory(0, 32, true);
        assertEquals(DirtRestockCapacityPolicy.Route.USE_AVAILABLE, decide(65, partial, 10_000).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, decide(64, partial, 31).route());
        assertEquals(DirtRestockCapacityPolicy.Route.USE_AVAILABLE, decide(64, partial, 0).route());
    }

    @Test
    void scannedDepotsTakePriorityEvenWhenShopStacksWouldFit() {
        var emptyRoom = inventory(2, 0, true);
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, decide(128, emptyRoom, 128).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, decide(128, emptyRoom, 32).route());
        assertEquals(DirtRestockCapacityPolicy.Route.SHOP, decide(128, emptyRoom, 0).route());
    }

    @Test
    void unknownDepotStockCannotAuthorizeShopping() {
        var unknown = DirtRestockCapacityPolicy.decide(64, inventory(2, 0, true), 0, false, 0);
        assertTrue(unknown.blocked());
        assertTrue(unknown.detail().startsWith("Depot scan incomplete:"));
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT,
                DirtRestockCapacityPolicy.decide(64, inventory(2, 0, true), 32, false, 0).route());
    }

    @Test
    void anotherCurrentMaterialRetainsItsReservedSlot() {
        assertTrue(DirtRestockCapacityPolicy.decide(64, inventory(1, 0, true), 0, true, 1).blocked());
        assertTrue(DirtRestockCapacityPolicy.decide(64, inventory(1, 0, true), 64, true, 1).blocked());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT,
                DirtRestockCapacityPolicy.decide(64, inventory(2, 0, true), 64, true, 1).route());
        assertEquals(DirtRestockCapacityPolicy.Route.SHOP,
                DirtRestockCapacityPolicy.decide(64, inventory(2, 0, true), 0, true, 1).route());
    }

    @Test
    void partialDirtStacksCannotSubstituteForAnotherMaterialsEmptySlot() {
        InventoryObservation full = inventory(0, 1, true);
        ArrayList<InventoryObservation.Slot> slots = new ArrayList<>(full.mainSlots());
        slots.set(0, new InventoryObservation.Slot(0, "minecraft:dirt", 1, 64,
                Material.DIRT, true, false, null, null, false));
        slots.set(1, new InventoryObservation.Slot(1, "minecraft:dirt", 1, 64,
                Material.DIRT, true, false, null, null, false));
        var partial = InventoryObservation.capture(slots, full.offhand(), 0, 64, full.menu());
        assertEquals(189L, partial.dirtCapacity());
        assertTrue(DirtRestockCapacityPolicy.decide(67, partial, 64, true, 1).blocked());
    }

    @Test
    void customDirtAndOffhandRoomDoNotInventCompatibleMainCapacity() {
        InventoryObservation custom = inventory(0, 32, false);
        assertEquals(0L, custom.dirtCapacity());
        assertEquals(DirtRestockCapacityPolicy.Route.USE_AVAILABLE, decide(33, custom, 10_000).route());
        InventoryObservation offhandOnly = inventory(0, 0, true);
        assertEquals(0, offhandOnly.mainMaterialTotals().get(Material.DIRT));
        assertEquals(1, offhandOnly.mainAndOffhandMaterialTotals().get(Material.DIRT));
        assertTrue(decide(1, offhandOnly, 10_000).blocked());
    }

    @Test
    void unchangedCapacityStaysBlockedAndRealCapacityOrSupplyChangesReleaseIt() {
        InventoryObservation full = inventory(0, 0, true);
        var first = decide(1, full, 0);
        assertEquals(first, decide(1, full, 0));
        assertFalse(decide(1, inventory(1, 0, true), 0).blocked());
        assertFalse(decide(1, inventory(0, 64, true), 0).blocked());
        InventoryObservation partial = inventory(0, 32, true);
        assertEquals(DirtRestockCapacityPolicy.Route.USE_AVAILABLE, decide(64, partial, 0).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, decide(64, partial, 32).route());
    }

    @Test
    void zeroCapacityDemandRemainsVisibleWithoutAuthorizingAnImpossibleTransfer() {
        long requirement = RestockBatchPolicy.targetAvailable(5_000, 10_000, 0);
        assertEquals(1, requirement);
        assertTrue(decide(requirement, inventory(0, 0, true), 10_000).blocked());
    }

    @Test
    void successfulPartialPurchaseRequestsAnExplicitCoreBatchReduction() {
        InventoryObservation before = inventory(1, 0, true);
        assertEquals(DirtRestockCapacityPolicy.Route.SHOP, decide(128, before, 0).route());
        InventoryObservation afterOneAcknowledgedStack = inventory(0, 64, true);
        var remaining = decide(128, afterOneAcknowledgedStack, 0);
        assertEquals(DirtRestockCapacityPolicy.Route.USE_AVAILABLE, remaining.route());
        assertTrue(remaining.detail().contains("64 Dirt already in inventory"));
    }

    @Test
    void acknowledgedFullInventoryRefillIsSatisfiedUntilActualDirtDepletion() {
        InventoryObservation full = inventory(0, 0, true);
        ArrayList<InventoryObservation.Slot> filled = new ArrayList<>(full.mainSlots());
        for (int index = 0; index < 33; index++) {
            filled.set(index, new InventoryObservation.Slot(index, "minecraft:dirt", 64, 64,
                    Material.DIRT, true, false, null, null, false));
        }
        filled.set(33, new InventoryObservation.Slot(33, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, 0, 1_561, false));
        InventoryObservation stocked = InventoryObservation.capture(filled, full.offhand(), 0, 64, full.menu());
        assertEquals(2_112, stocked.mainMaterialTotals().get(Material.DIRT));
        assertEquals(0, stocked.emptyMainSlots());
        assertEquals(0L, stocked.dirtCapacity());
        assertEquals(DirtRestockCapacityPolicy.Route.SATISFIED, decide(2_112, stocked, 0).route());
        assertTrue(decide(1, full, 0).blocked());
    }

    @Test
    void unavailableFactsBlockRequiredRefillsButDoNotInventARequirement() {
        InventoryObservation unavailable = InventoryObservation.unavailable("not readable");
        assertTrue(decide(1, unavailable, 0).blocked());
        assertEquals(DirtRestockCapacityPolicy.Route.SATISFIED, decide(0, unavailable, 0).route());
        assertThrows(IllegalArgumentException.class, () -> decide(-1, unavailable, 0));
        assertThrows(IllegalArgumentException.class, () -> decide(1, unavailable, -1));
    }

    @Test
    void withPurchasesOffRegisteredChestsAreTheOnlySupply() {
        // Room for the chest refill, or nothing in the chests: the core plans from chests and pauses on what's missing.
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, shopOff(64, inventory(1, 0, true), 64).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, shopOff(64, inventory(1, 0, true), 0).route());
        assertEquals(DirtRestockCapacityPolicy.Route.DEPOT, shopOff(64, inventory(0, 0, true), 0).route());
        // A refill that does not fit keeps the dirt on hand, or blocks without mentioning a purchase.
        var useAvailable = shopOff(128, inventory(0, 32, true), 128);
        assertEquals(DirtRestockCapacityPolicy.Route.USE_AVAILABLE, useAvailable.route());
        var blocked = shopOff(64, inventory(0, 0, true), 64);
        assertTrue(blocked.blocked());
        assertTrue(blocked.detail().startsWith(DirtRestockCapacityPolicy.BLOCKER_PREFIX));
        assertTrue(blocked.detail().contains("shop purchases are off"));
        // Never the shop, even when an empty slot would allow a purchase.
        for (int empty = 0; empty <= 3; empty++) {
            for (long depot : new long[]{0, 64, 10_000}) {
                assertFalse(shopOff(64, inventory(empty, 0, true), depot).route() == DirtRestockCapacityPolicy.Route.SHOP);
            }
        }
    }

    private static DirtRestockCapacityPolicy.Decision shopOff(long required, InventoryObservation inventory,
                                                             long depotDirt) {
        return DirtRestockCapacityPolicy.decide(required, inventory, depotDirt, true, 0, false, false);
    }

    private static DirtRestockCapacityPolicy.Decision decide(long required, InventoryObservation inventory,
                                                            long depotDirt) {
        return DirtRestockCapacityPolicy.decide(required, inventory, depotDirt);
    }

    private static InventoryObservation inventory(int emptySlots, int dirtCount, boolean normalDirt) {
        ArrayList<InventoryObservation.Slot> main = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            main.add(new InventoryObservation.Slot(index, "minecraft:moss_block", 64, 64,
                    null, false, false, null, null, false));
        }
        for (int index = 0; index < emptySlots; index++) {
            main.set(index, InventoryObservation.Slot.empty(index));
        }
        if (dirtCount > 0) {
            main.set(33, new InventoryObservation.Slot(33, "minecraft:dirt", dirtCount, 64,
                    Material.DIRT, normalDirt, false, null, null, false));
        }
        main.set(34, new InventoryObservation.Slot(34, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, 0, 1_561, false));
        main.set(35, new InventoryObservation.Slot(35, "minecraft:tripwire_hook", 5, 64,
                null, false, false, null, null, false));
        InventoryObservation.Slot offhand = new InventoryObservation.Slot(40, "minecraft:dirt", 1, 64,
                Material.DIRT, true, false, null, null, false);
        return InventoryObservation.capture(main, offhand, 0, 64,
                new InventoryObservation.Menu(false, "none", "", 0, InventoryObservation.Slot.empty(-1)));
    }
}
