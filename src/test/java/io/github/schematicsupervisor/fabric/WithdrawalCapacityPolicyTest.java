package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WithdrawalCapacityPolicyTest {
    private static final MaterialQuantities LIGHTS = MaterialQuantities.of(Material.GLOWSTONE, 64);

    @Test
    void onlyCleanSettledNoClickRejectionsCanRecover() {
        assertTrue(WithdrawalCapacityPolicy.settledCapacityRejection(true, true, false, false, false));
        assertFalse(WithdrawalCapacityPolicy.settledCapacityRejection(false, true, false, false, false));
        assertFalse(WithdrawalCapacityPolicy.settledCapacityRejection(true, false, false, false, false));
        assertFalse(WithdrawalCapacityPolicy.settledCapacityRejection(true, true, true, false, false));
        assertFalse(WithdrawalCapacityPolicy.settledCapacityRejection(true, true, false, true, false));
        assertFalse(WithdrawalCapacityPolicy.settledCapacityRejection(true, true, false, false, true));
    }

    @Test
    void fullPickupInventoryMustTryStorageThenAuthorizedDisposalBeforeRetry() {
        var full = inventory(0, 0);
        assertEquals(WithdrawalCapacityPolicy.Route.STORE, decide(full, false, true, true));
        assertEquals(WithdrawalCapacityPolicy.Route.DISPOSE, decide(full, true, true, true));
        assertEquals(WithdrawalCapacityPolicy.Route.READY, decide(inventory(1, 64), false, true, true));
        assertEquals(WithdrawalCapacityPolicy.Route.BLOCKED, decide(full, true, true, false));
        assertEquals(WithdrawalCapacityPolicy.Route.BLOCKED, decide(full, false, false, true));
    }

    @Test
    void unknownCapacityCannotAdmitWithdrawal() {
        assertEquals(WithdrawalCapacityPolicy.Route.BLOCKED,
                decide(InventoryObservation.unavailable("missing frame"), false, false, true));
    }

    @Test
    void compatiblePartialStacksNeedNoEmptySlotButMustFitWholeAllocation() {
        assertEquals(WithdrawalCapacityPolicy.Route.READY, decide(inventory(0, 64), false, true, true));
        assertEquals(WithdrawalCapacityPolicy.Route.STORE, decide(inventory(0, 63), false, true, true));
    }

    @Test
    void twoMaterialsCannotSpendTheSameEmptySlot() {
        assertEquals(WithdrawalCapacityPolicy.Route.STORE, WithdrawalCapacityPolicy.decide(
                MaterialQuantities.of(Map.of(Material.GLOWSTONE, 64L, Material.DIRT, 64L)),
                inventory(1, 64), false, true, true));
    }

    @Test
    void capacityLostDuringTravelRejectsBothQuickMoveAndCursorPickup() {
        assertTrue(WithdrawalCapacityPolicy.admitsSource(64, 64, 64));
        assertFalse(WithdrawalCapacityPolicy.admitsSource(0, 64, 64));
        assertFalse(WithdrawalCapacityPolicy.admitsSource(29, 30, 64));
        assertTrue(WithdrawalCapacityPolicy.admitsSource(30, 30, 64));
        assertFalse(WithdrawalCapacityPolicy.admitsSource(64, 0, 64));
    }

    private static WithdrawalCapacityPolicy.Route decide(InventoryObservation inventory,
            boolean attempted, boolean pickups, boolean enabled) {
        return WithdrawalCapacityPolicy.decide(LIGHTS, inventory, attempted, pickups, enabled);
    }

    private static InventoryObservation inventory(int empty, long glowstoneCapacity) {
        var slots = new ArrayList<InventoryObservation.Slot>();
        for (int i = 0; i < 36; i++) {
            slots.add(i < empty ? InventoryObservation.Slot.empty(i)
                    : new InventoryObservation.Slot(i, "minecraft:pumpkin_seeds", 64, 64,
                            null, false, false, null, null, false, "Pumpkin Seeds", true));
        }
        return InventoryObservation.capture(slots, InventoryObservation.Slot.empty(40), 0, 64,
                new InventoryObservation.Menu(false, "none", "", 0, InventoryObservation.Slot.empty(-1)))
                .withEquipmentAndCapacity(null, Map.of(Material.DIRT, empty * 64L,
                        Material.GLOWSTONE, glowstoneCapacity, Material.BIRCH_PLANKS, empty * 64L,
                        Material.WHEAT_SEEDS, empty * 64L));
    }
}
