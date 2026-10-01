package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.LightingRestockReserve;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestockBatchPolicyTest {
    @Test
    void seedsFillAvailableInventoryInsteadOfTheCurrent128CellSection() {
        assertEquals(2_304, RestockBatchPolicy.seedTargetAvailable(23_296, 36 * 64));
        assertEquals(1_923, RestockBatchPolicy.seedTargetAvailable(23_296, 30 * 64 + 3));
        assertEquals(37, RestockBatchPolicy.seedTargetAvailable(37, 36 * 64));
        assertEquals(1, RestockBatchPolicy.seedTargetAvailable(0, 36 * 64));
        assertEquals(1, RestockBatchPolicy.seedTargetAvailable(3_456, 0));
    }

    @Test
    void seedInventoryCarriesAcrossSectionsWithoutRefillingAtEveryBoundary() {
        long inventory = 0;
        long depotStock = 6_912;
        long planted = 0;
        List<Long> refills = new ArrayList<>();
        for (int section = 0; section < 20; section++) {
            for (int cell = 0; cell < 128; cell++) {
                if (inventory == 0) {
                    long amount = RestockBatchPolicy.seedTargetAvailable(depotStock, 36 * 64);
                    refills.add(amount);
                    depotStock -= amount;
                    inventory += amount;
                }
                inventory--;
                planted++;
            }
        }
        assertEquals(List.of(2_304L, 2_304L), refills);
        assertEquals(2_560, planted);
        assertEquals(2_048, inventory);
        assertEquals(6_912, depotStock + inventory + planted);
    }

    @Test
    void seedRefillsRejectInvalidStockOrCapacity() {
        assertThrows(IllegalArgumentException.class, () -> RestockBatchPolicy.seedTargetAvailable(-1, 64));
        assertThrows(IllegalArgumentException.class, () -> RestockBatchPolicy.seedTargetAvailable(64, -1));
    }

    @Test
    void fillsToTheSmallestRealBound() {
        assertEquals(1_728, RestockBatchPolicy.targetAvailable(5_000, 8_000, 1_728));
        assertEquals(700, RestockBatchPolicy.targetAvailable(5_000, 700, 1_728));
        assertEquals(300, RestockBatchPolicy.targetAvailable(300, 700, 1_728));
    }

    @Test
    void requestsOneToProduceAnExactMissingOrCapacityFailure() {
        assertEquals(1, RestockBatchPolicy.targetAvailable(5_000, 0, 1_728));
        assertEquals(1, RestockBatchPolicy.targetAvailable(5_000, 700, 0));
    }

    @Test
    void preservesAStackSlotForEveryOtherMissingOrdinaryMaterial() {
        long dirt = RestockBatchPolicy.targetAvailable(6_400, 10_000, 1_728, 64, 2);
        long glowstone = RestockBatchPolicy.targetAvailable(500, 10_000, 128, 64, 1);
        long birchPlanks = RestockBatchPolicy.targetAvailable(500, 10_000, 64, 64, 0);

        assertEquals(1_600, dirt);
        assertEquals(64, glowstone);
        assertEquals(64, birchPlanks);
    }

    @Test
    void rejectsInvalidCounts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RestockBatchPolicy.targetAvailable(0, 1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> RestockBatchPolicy.targetAvailable(1, -1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> RestockBatchPolicy.targetAvailable(1, 1, 1, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> RestockBatchPolicy.targetAvailable(1, 1, 1, 64, -1)
        );
    }

    @Test
    void glowstoneCarriesOneStackInsteadOfOnlyItsTenCellSlice() {
        assertEquals(64, RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 490, 2_304, 1_920, 0));
        assertEquals(10, RestockBatchPolicy.targetAvailable(10, 2_304, 1_920, 64, 0));
        assertEquals(128, RestockBatchPolicy.targetAvailable(128, 2_304, 1_920, 64, 0));
    }

    @Test
    void glowstoneHonorsLayerEndPlanStockCapacityAndOtherMaterialSlots() {
        assertEquals(13, RestockBatchPolicy.glowstoneTargetAvailable(3, 10, 490, 2_304, 1_920, 0));
        assertEquals(3, RestockBatchPolicy.glowstoneTargetAvailable(3, 0, 490, 2_304, 1_920, 0));
        assertEquals(7, RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 7, 2_304, 1_920, 0));
        assertEquals(19, RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 490, 19, 1_920, 0));
        assertEquals(23, RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 490, 2_304, 87, 1));
    }

    @Test
    void zeroStockOrCapacityPreservesOnlyTheExistingPositiveDemandBlocker() {
        assertEquals(1, RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 1, 0, 64, 0));
        assertEquals(1, RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 490, 100, 64, 1));
        assertThrows(IllegalArgumentException.class,
                () -> RestockBatchPolicy.glowstoneTargetAvailable(10, 64, 0, 100, 64, 0));
    }

    @Test
    void invalidLookaheadOrCurrentDemandIsRejectedAndHugeDemandCannotOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> RestockBatchPolicy.glowstoneTargetAvailable(0, 64, 490, 100, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RestockBatchPolicy.glowstoneTargetAvailable(10, -1, 490, 100, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> RestockBatchPolicy.glowstoneTargetAvailable(10, 65, 490, 100, 64, 0));
        assertEquals(64, RestockBatchPolicy.glowstoneTargetAvailable(Long.MAX_VALUE, 64, Long.MAX_VALUE,
                Long.MAX_VALUE, Long.MAX_VALUE, 0));
    }

    @Test
    void complete490CellLightingLayerNeedsEightExactRefillsAndNoNextLayerStock() {
        List<TargetBlock> targets = new ArrayList<>();
        for (int chunk = 0; chunk < 49; chunk++) {
            for (int cell = 0; cell < 10; cell++) {
                for (int y : new int[] {2, 5}) {
                    targets.add(new TargetBlock(new BlockPosition(chunk % 7 * 16 + cell, y, chunk / 7 * 16),
                            new BlockState("minecraft:glowstone")));
                }
            }
        }
        LayerBuildSchedule schedule = new LayerBuildSchedule(SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets));
        long inventory = 0;
        long consumed = 0;
        long withdrawn = 0;
        List<Long> refills = new ArrayList<>();
        for (int cursor = 0; cursor < 49; cursor++) {
            WorkOrder.OrdinaryBlocks order = (WorkOrder.OrdinaryBlocks) schedule.entry(cursor).order();
            for (int missing = order.placements().size(); missing > 0; missing--) {
                if (inventory == 0) {
                    long upcoming = LightingRestockReserve.additionalPlannedDemand(schedule, cursor, order);
                    long batch = RestockBatchPolicy.glowstoneTargetAvailable(missing, upcoming,
                            980 - consumed, 2_304 - withdrawn, 64, 0);
                    refills.add(batch);
                    inventory += batch;
                    withdrawn += batch;
                }
                inventory--;
                consumed++;
            }
        }
        assertEquals(List.of(64L, 64L, 64L, 64L, 64L, 64L, 64L, 42L), refills);
        assertEquals(490, consumed);
        assertEquals(consumed, withdrawn);
        assertEquals(0, inventory);
    }
}
