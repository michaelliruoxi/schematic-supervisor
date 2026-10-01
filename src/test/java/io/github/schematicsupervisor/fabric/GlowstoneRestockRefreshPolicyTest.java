package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.core.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GlowstoneRestockRefreshPolicyTest {
    private final SchematicPlan plan = plan();
    private final LayerBuildSchedule schedule = new LayerBuildSchedule(plan);

    @Test void freshCompleteStockExpandsRestoredOneUnitRequestWithoutRequiringFlight() {
        assertEquals(64, target(saved(0, 7, false), inventory(0, 2304), depots(1914, true, "NONE")));
        assertEquals(17, target(saved(0, 7, false), inventory(0, 2304), depots(17, true, "NONE")));
        assertEquals(32, target(saved(0, 7, false), inventory(0, 32), depots(1914, true, "NONE")));
    }

    @Test void zeroCapacityAndCapacityReservedForOtherRequirementsNeverIncreaseDemand() {
        assertEquals(0, target(saved(0, 7, false), inventory(0, 0), depots(1914, true, "NONE")));
        assertEquals(0, target(saved(0, 7, true), inventory(0, 64), depots(1914, true, "NONE")));
        assertEquals(64, target(saved(0, 7, true), inventory(0, 128), depots(1914, true, "NONE")));
        assertEquals(0, target(saved(0, 7, false), inventory(0, 2304), depots(0, true, "NONE")));
    }

    @Test void partialUnknownBusyOrBlockedDepotObservationsCannotRefresh() {
        var saved = saved(0, 7, false);
        var inventory = inventory(0, 2304);
        assertEquals(0, target(saved, inventory, depots(1914, false, "NONE")));
        assertEquals(0, target(saved, inventory, depots(1914, true, "SCAN")));
        assertEquals(0, target(saved, inventory, DepotObservation.unavailable("not received")));
        DepotObservation good = depots(1914, true, "NONE");
        assertEquals(0, target(saved, inventory, new DepotObservation(true, "NONE", "IDLE", null,
                List.of(), false, false, "", 2, good.entries(), false)));
        assertEquals(0, target(saved, inventory, new DepotObservation(true, "NONE", "IDLE", null,
                List.of(), false, true, "", 1, good.entries(), false)));
        assertEquals(0, target(saved, inventory, new DepotObservation(true, "NONE", "IDLE", null,
                List.of(), false, false, "", 1, good.entries(), true)));
    }

    @Test void authoritativeStageAndRemainingPlanLimitTheOptionalLookahead() {
        var inventory = inventory(0, 2304);
        var depots = depots(1914, true, "NONE");
        assertEquals(16, target(saved(0, 80, false), inventory, depots));
        assertEquals(0, target(saved(0, 96, false), inventory, depots));
        assertEquals(0, target(saved(schedule.size() - 1, 7, false), inventory, depots));
        var stale = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved(0, 7, false))
                .replace("\"schedule_cursor\": 0", "\"schedule_cursor\": 1"));
        assertEquals(0, target(stale, inventory, depots));
        var wrongSchedule = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved(0, 7, false))
                .replace(LayerBuildSchedule.DEFERRED_PLANTING_ID, LayerBuildSchedule.ID)
                .replace("\"planting_deferred\": true", "\"planting_deferred\": false"));
        assertEquals(0, target(wrongSchedule, inventory, depots));
    }

    @Test void nonemptyInventoryUnknownCapacityAndForeignPendingStateNeverRefresh() {
        var saved = saved(0, 7, false);
        var depots = depots(1914, true, "NONE");
        assertEquals(0, target(saved, inventory(1, 2303), depots));
        assertEquals(0, target(saved, InventoryObservation.unavailable("unknown"), depots));
        InventoryObservation good = inventory(0, 2304);
        assertEquals(0, target(saved, new InventoryObservation(true, good.mainSlots(), good.offhand(),
                0, good.emptyMainSlots(), good.dirtCapacity(), good.mainMaterialTotals(),
                good.mainAndOffhandMaterialTotals(), good.menu(), ""), depots));
        for (String replacement : List.of("\"withdrawal_in_flight\": true", "\"reconciliation_required\": true")) {
            String key = replacement.substring(0, replacement.indexOf(':') + 1);
            String json = CheckpointJsonCodec.toJson(saved).replace(key + " false", replacement);
            if (replacement.startsWith("\"reconciliation_required\"")) {
                json = json.replace("\"reconciliation_detail\": \"\"",
                        "\"reconciliation_detail\": \"An original receipt is unresolved\"");
            }
            var pending = CheckpointJsonCodec.fromJson(json);
            assertEquals(0, target(pending, good, depots));
        }
        assertEquals(0, target(null, good, depots));
    }

    @Test void activeRecoveryAndUnrelatedPauseCannotBorrowRestockRefresh() {
        var saved = saved(0, 7, false);
        var inventory = inventory(0, 2304);
        var depots = depots(1914, true, "NONE");
        var recovering = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved)
                .replace("\"recovery_stage\": \"NONE\"", "\"recovery_stage\": \"STOP_MOVEMENT\""));
        assertEquals(0, target(recovering, inventory, depots));
        var unrelated = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved)
                .replace("\"resume_state\": \"RESTOCKING\"", "\"resume_state\": \"BUILDING\""));
        assertEquals(0, target(unrelated, inventory, depots));
    }

    @Test void onlyTheOneUnitSentinelMayRefreshAndLookaheadCannotCompound() {
        var original = saved(schedule.size() - 2, 7, false);
        var inventory = inventory(0, 2304);
        var depots = depots(1914, true, "NONE");
        assertEquals(17, target(original, inventory, depots));
        var enlarged = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(original)
                .replace("\"glowstone\": 1}", "\"glowstone\": 17}"));
        assertEquals(17, enlarged.restockRequirement().get(Material.GLOWSTONE));
        assertEquals(0, target(enlarged, inventory, depots));
        var ordinaryBatch = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved(0, 7, false))
                .replace("\"glowstone\": 1}", "\"glowstone\": 2}"));
        assertEquals(0, target(ordinaryBatch, inventory, depots));
    }

    private long target(SupervisorCheckpoint saved, InventoryObservation inventory, DepotObservation depots) {
        return GlowstoneRestockRefreshPolicy.target(plan, schedule, saved, inventory, depots);
    }

    private SupervisorCheckpoint saved(int cursor, long consumed, boolean mixed) {
        MaterialQuantities requirement = MaterialQuantities.of(mixed
                ? Map.of(Material.GLOWSTONE, 1L, Material.DIRT, 2L) : Map.of(Material.GLOWSTONE, 1L));
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, plan.planId(),
                SupervisorState.PAUSED, SupervisorState.RESTOCKING, SupervisorState.BUILDING,
                schedule.entry(cursor).order().chunkIndex(), BuildPhase.ORDINARY_BLOCKS, VerificationStage.CHUNK,
                RecoveryStage.NONE, 0, "", MaterialQuantities.of(Material.GLOWSTONE, consumed),
                MaterialQuantities.of(Material.GLOWSTONE, 11), requirement, requirement, "resolved incident",
                1, true, true, true, false, false, "", schedule.id(), cursor, -1, plan.chunkCount(), true, null);
    }

    private static SchematicPlan plan() {
        List<TargetBlock> targets = new ArrayList<>();
        for (int x = 0; x < 96; x++) {
            targets.add(new TargetBlock(new BlockPosition(x, -61, 0), new BlockState("minecraft:glowstone")));
        }
        return SchematicCompiler.compile(new BuildVolume(0, -61, 0, 95, -61, 0), targets).withPlantingDeferred(true);
    }

    private static DepotObservation depots(long stock, boolean scanned, String operation) {
        return new DepotObservation(true, operation, "IDLE", null, List.of(), false, false, "", 1,
                List.of(new DepotObservation.Entry("lights", 0, 0, 0, scanned,
                        scanned ? MaterialQuantities.of(Material.GLOWSTONE, stock) : null, "", false, false)), false);
    }

    private static InventoryObservation inventory(int glowstone, long capacity) {
        List<InventoryObservation.Slot> slots = new ArrayList<>();
        for (int index = 0; index < 36; index++) { slots.add(InventoryObservation.Slot.empty(index)); }
        if (glowstone > 0) {
            slots.set(0, new InventoryObservation.Slot(0, "minecraft:glowstone", glowstone, 64,
                    Material.GLOWSTONE, false, false, null, null, false, "", true));
        }
        return InventoryObservation.capture(slots, InventoryObservation.Slot.empty(40), 0, 64,
                new InventoryObservation.Menu(false, "none", "", 0, InventoryObservation.Slot.empty(-1)))
                .withEquipmentAndCapacity(List.of(InventoryObservation.Slot.empty(36), InventoryObservation.Slot.empty(37),
                        InventoryObservation.Slot.empty(38), InventoryObservation.Slot.empty(39)),
                        Map.of(Material.DIRT, 2304L, Material.GLOWSTONE, capacity,
                                Material.BIRCH_PLANKS, 2304L, Material.WHEAT_SEEDS, 2304L));
    }
}
