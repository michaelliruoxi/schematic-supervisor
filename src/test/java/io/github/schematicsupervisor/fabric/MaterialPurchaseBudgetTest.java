package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialPurchaseBudgetTest {
    @Test void satisfiedDirtDoesNotHideGlowstoneOrBirchCapacityRecovery() {
        for (Material material : List.of(Material.GLOWSTONE, Material.BIRCH_PLANKS)) {
            var required = MaterialQuantities.of(Map.of(Material.DIRT, 22L, material, 1L));
            int reserved = MaterialPurchaseBudget.reservedSlotsForOtherShortages(material,
                    required, MaterialQuantities.of(Material.DIRT, 22));
            var blocked = MaterialPurchaseBudget.decide(request(material, 12_250, 980, 0, 1,
                    1324, slots(0), reserved));
            assertTrue(blocked.needsCapacityRecovery());
            var recovered = MaterialPurchaseBudget.decide(request(material, 12_250, 980, 0, 1,
                    1324, slots(1), reserved));
            assertEquals(MaterialPurchaseBudget.Route.DEPOT, recovered.route());
            assertEquals(1, recovered.depotWithdrawalUnits());
            assertFalse(recovered.needsCapacityRecovery());
        }
    }

    @Test void missingEvidenceOrSpentPlanBudgetDoesNotAuthorizeCapacityRecovery() {
        assertFalse(MaterialPurchaseBudget.decide(new MaterialPurchaseBudget.Request(
                Material.GLOWSTONE, 100, 0, 0, 1, 1324, false, true, List.of(), 0))
                .needsCapacityRecovery());
        assertFalse(MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 100, 100, 0, 1,
                1324, slots(0), 0)).needsCapacityRecovery());
        assertFalse(MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 100, 0, 1, 1,
                1324, slots(0), 0)).needsCapacityRecovery());
    }

    @Test void nonDirtSupportStarterPurchasePreservesTheMissingTemporaryDirtSlot() {
        for (Material starter : List.of(Material.GLOWSTONE, Material.BIRCH_PLANKS)) {
            var required = MaterialQuantities.of(Map.of(Material.DIRT, 2L, starter, 1L));
            int reserved = MaterialPurchaseBudget.reservedSlotsForOtherShortages(
                    starter, required, MaterialQuantities.empty());
            assertEquals(1, reserved);
            var twoSlots = MaterialPurchaseBudget.decide(request(starter, 10_000, 0, 0, 1, 0, slots(2), reserved));
            assertEquals(MaterialPurchaseBudget.Route.PURCHASE, twoSlots.route());
            assertEquals(1, twoSlots.maximumStacks());
            assertEquals(MaterialPurchaseBudget.Route.BLOCKED,
                    MaterialPurchaseBudget.decide(request(starter, 10_000, 0, 0, 1, 0, slots(1), reserved)).route());
        }
    }

    @Test void alreadyAvailableSupportsDoNotReserveAnotherEmptySlot() {
        var required = MaterialQuantities.of(Map.of(Material.DIRT, 2L, Material.GLOWSTONE, 1L));
        assertEquals(0, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                required, MaterialQuantities.of(Material.DIRT, 2)));
        assertEquals(1, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                required, MaterialQuantities.of(Material.DIRT, 1)));
        assertEquals(1, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.DIRT,
                required, MaterialQuantities.empty()));
        assertEquals(0, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.DIRT,
                required, MaterialQuantities.of(Material.GLOWSTONE, 1)));
    }

    @Test void reservesWholeOtherRequirementsWithoutInventingFutureMaterialsOrPlantingDemand() {
        assertEquals(0, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                MaterialQuantities.of(Material.GLOWSTONE, 1), MaterialQuantities.empty()));
        var required = MaterialQuantities.of(Map.of(Material.GLOWSTONE, 1L, Material.DIRT, 130L,
                Material.BIRCH_PLANKS, 1L, Material.HOE, 1L));
        assertEquals(4, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                required, MaterialQuantities.of(Material.DIRT, 65)));
        var optional = MaterialQuantities.of(Map.of(Material.WHEAT_SEEDS, 65L, Material.FOOD, 2L));
        assertEquals(4, MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                optional, MaterialQuantities.empty()));
    }

    @Test void impossibleOtherRequirementsSaturateToABlockingReservationWithoutOverflow() {
        var required = MaterialQuantities.of(Map.of(Material.DIRT, Long.MAX_VALUE, Material.HOE, Long.MAX_VALUE));
        int reserved = MaterialPurchaseBudget.reservedSlotsForOtherShortages(Material.GLOWSTONE,
                required, MaterialQuantities.empty());
        assertEquals(36, reserved);
        assertEquals(MaterialPurchaseBudget.Route.BLOCKED,
                MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 0, 1, 0, slots(36), reserved)).route());
    }

    @Test void onlyTheThreePlainBuildMaterialsCanReceivePurchaseBudgets() {
        for (Material material : java.util.stream.Stream.concat(Material.builtIns().stream(),
                java.util.stream.Stream.of(Material.block("minecraft:stone"))).toList()) {
            var decision = MaterialPurchaseBudget.decide(request(material, 10_000, 0, 0, 1, 0, slots(36), 0));
            boolean supported = material == Material.DIRT || material == Material.GLOWSTONE || material == Material.BIRCH_PLANKS;
            assertEquals(supported ? MaterialPurchaseBudget.Route.PURCHASE : MaterialPurchaseBudget.Route.BLOCKED, decision.route());
            assertEquals(supported ? 9 : 0, decision.maximumStacks());
        }
    }

    @Test void aTrueCurrentShortageGatesBatchesButOneUnitRequestDoesNotForceOneStackShopping() {
        var enough = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 12_250, 0, 64, 64, 0, slots(10), 0));
        assertEquals(MaterialPurchaseBudget.Route.SATISFIED, enough.route());
        assertEquals(0, enough.maximumStacks());
        var oneUnitRequest = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 12_250, 0, 0, 1, 0, slots(10), 0));
        assertEquals(1, oneUnitRequest.currentShortage());
        assertEquals(9, oneUnitRequest.maximumStacks());
        assertEquals(576, oneUnitRequest.maximumPurchaseUnits());
    }

    @Test void subtractsConsumedAndExistingInventoryBeforeFinalWholeStackRounding() {
        var decision = MaterialPurchaseBudget.decide(request(Material.BIRCH_PLANKS, 1_000, 800, 130, 200, 0, slots(20), 0));
        assertEquals(70, decision.remainingPlanDemand());
        assertEquals(2, decision.maximumStacks());
        assertEquals(58, decision.roundingSurplus());
        var one = MaterialPurchaseBudget.decide(request(Material.BIRCH_PLANKS, 100, 99, 0, 1, 0, slots(1), 0));
        assertEquals(1, one.maximumStacks());
        assertEquals(63, one.roundingSurplus());
    }

    @Test void neverConcealsARealExecutorShortageWhenThePlanBudgetIsSpent() {
        for (long consumed : List.of(100L, 101L, Long.MAX_VALUE)) {
            var decision = MaterialPurchaseBudget.decide(request(Material.DIRT, 100, consumed, 0, 1, 0, slots(10), 0));
            assertEquals(MaterialPurchaseBudget.Route.BLOCKED, decision.route());
            assertEquals(1, decision.currentShortage());
            assertEquals(0, decision.maximumStacks());
        }
    }

    @Test void exactAndPartialDepotStockAlwaysPrecedePurchases() {
        var full = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 10, 100, 500, slots(10), 0));
        assertEquals(MaterialPurchaseBudget.Route.DEPOT, full.route());
        assertEquals(90, full.depotWithdrawalUnits());
        assertEquals(0, full.maximumStacks());
        var partial = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 10, 100, 30, slots(10), 0));
        assertEquals(30, partial.depotWithdrawalUnits());
        assertEquals(0, partial.maximumStacks());
        var afterReceipt = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 40, 100, 0, slots(10), 0));
        assertEquals(MaterialPurchaseBudget.Route.PURCHASE, afterReceipt.route());
        assertEquals(960, afterReceipt.remainingPlanDemand());
        assertEquals(9, afterReceipt.maximumStacks());
    }

    @Test void depotPartialBatchesRespectCapacityAndRemainingPlanInsteadOfHugeRequest() {
        var capacity = MaterialPurchaseBudget.decide(request(Material.DIRT, 10_000, 0, 0, 1_000, 10_000, slots(2), 1));
        assertEquals(64, capacity.depotWithdrawalUnits());
        var plan = MaterialPurchaseBudget.decide(request(Material.DIRT, 20, 10, 0, 1_000, 10_000, slots(2), 0));
        assertEquals(10, plan.depotWithdrawalUnits());
    }

    @Test void compatiblePartialStacksCanHoldAWholePurchaseWithoutTouchingProtectedSlots() {
        List<MaterialPurchaseBudget.SlotCapacity> slots = slots(0);
        slots.set(2, partial(2, 31));
        slots.set(9, partial(9, 33));
        var decision = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 64, 100, 0, slots, 0));
        assertEquals(64, decision.usableCapacity());
        assertEquals(1, decision.maximumStacks());
        slots.set(9, partial(9, 32));
        assertEquals(MaterialPurchaseBudget.Route.BLOCKED,
                MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 64, 100, 0, slots, 0)).route());
    }

    @Test void reservesActualEmptySlotsConservativelyBeforeBudgeting() {
        List<MaterialPurchaseBudget.SlotCapacity> slots = slots(2);
        slots.set(1, new MaterialPurchaseBudget.SlotCapacity(1, MaterialPurchaseBudget.SlotKind.EMPTY, 16));
        slots.set(2, partial(2, 48));
        var decision = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 0, 1, 0, slots, 1));
        assertEquals(64, decision.usableCapacity());
        assertEquals(1, decision.maximumStacks());
        var impossible = MaterialPurchaseBudget.decide(request(Material.GLOWSTONE, 1_000, 0, 0, 1, 0, slots, 3));
        assertEquals(MaterialPurchaseBudget.Route.BLOCKED, impossible.route());
        assertEquals(0, impossible.maximumStacks());
    }

    @Test void unknownInventoryOrIncompleteDepotScansCannotAuthorizePurchases() {
        var unknownInventory = new MaterialPurchaseBudget.Request(Material.DIRT, 1_000, 0, 0, 1, 0,
                false, true, List.of(), 0);
        assertEquals(MaterialPurchaseBudget.Route.BLOCKED, MaterialPurchaseBudget.decide(unknownInventory).route());
        var unknownDepot = new MaterialPurchaseBudget.Request(Material.DIRT, 1_000, 0, 0, 1, 0,
                true, false, slots(2), 0);
        var decision = MaterialPurchaseBudget.decide(unknownDepot);
        assertEquals(MaterialPurchaseBudget.Route.SCAN_DEPOTS, decision.route());
        assertEquals(0, decision.maximumStacks());
        assertEquals(0, decision.depotWithdrawalUnits());
    }

    @Test void maximumLongDemandDoesNotOverflowRoundingOrCapacityMath() {
        var decision = MaterialPurchaseBudget.decide(request(Material.DIRT, Long.MAX_VALUE, 0, 0, 1, 0, slots(36), 0));
        assertEquals(9, decision.maximumStacks());
        assertEquals(0, decision.roundingSurplus());
        var offset = MaterialPurchaseBudget.decide(request(Material.DIRT, Long.MAX_VALUE, Long.MAX_VALUE - 65,
                1, 2, 0, slots(36), 0));
        assertEquals(1, offset.maximumStacks());
        assertEquals(0, offset.roundingSurplus());
    }

    @Test void capacityAndProtectedSlotFactsAreBoundedCompleteAndImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialPurchaseBudget.SlotCapacity(0,
                MaterialPurchaseBudget.SlotKind.PROTECTED, 1));
        assertThrows(IllegalArgumentException.class, () -> partial(0, 64));
        List<MaterialPurchaseBudget.SlotCapacity> facts = slots(2);
        var request = request(Material.DIRT, 100, 0, 0, 1, 0, facts, 0);
        facts.set(0, protectedSlot(0));
        assertEquals(2, MaterialPurchaseBudget.decide(request).maximumStacks());
        assertThrows(UnsupportedOperationException.class, () -> request.mainSlots().clear());
        facts.set(1, protectedSlot(0));
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, 100, 0, 0, 1, 0, facts, 0));
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, 100, 0, 0, 1, 0, List.of(), 0));
    }

    @Test void negativeCountsAndImpossibleReservationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, -1, 0, 0, 1, 0, slots(2), 0));
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, 100, -1, 0, 1, 0, slots(2), 0));
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, 100, 0, -1, 1, 0, slots(2), 0));
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, 100, 0, 0, 1, -1, slots(2), 0));
        assertThrows(IllegalArgumentException.class, () -> request(Material.DIRT, 100, 0, 0, 1, 0, slots(2), 37));
    }

    private static MaterialPurchaseBudget.Request request(Material material, long planned, long consumed, long inventory,
            long required, long depot, List<MaterialPurchaseBudget.SlotCapacity> slots, int reserved) {
        return new MaterialPurchaseBudget.Request(material, planned, consumed, inventory, required, depot, true, true, slots, reserved);
    }

    private static List<MaterialPurchaseBudget.SlotCapacity> slots(int empty) {
        List<MaterialPurchaseBudget.SlotCapacity> facts = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            facts.add(index < empty ? new MaterialPurchaseBudget.SlotCapacity(index, MaterialPurchaseBudget.SlotKind.EMPTY, 64)
                    : protectedSlot(index));
        }
        return facts;
    }
    private static MaterialPurchaseBudget.SlotCapacity protectedSlot(int index) {
        return new MaterialPurchaseBudget.SlotCapacity(index, MaterialPurchaseBudget.SlotKind.PROTECTED, 0);
    }
    private static MaterialPurchaseBudget.SlotCapacity partial(int index, int room) {
        return new MaterialPurchaseBudget.SlotCapacity(index, MaterialPurchaseBudget.SlotKind.COMPATIBLE_PLAIN_STACK, room);
    }
}
