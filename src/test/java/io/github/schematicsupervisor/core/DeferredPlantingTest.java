package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeferredPlantingTest {
    @Test
    void omitsOnlyPlantingAndPreservesSourceIdentityVolumeAndEveryConstructionOrder() {
        SchematicPlan full = plan();
        SchematicPlan deferred = full.withPlantingDeferred(true);
        assertEquals(full.planId(), deferred.planId());
        assertEquals(full.buildVolume(), deferred.buildVolume());
        assertEquals(full.chunks(), deferred.chunks());
        assertEquals(2, deferred.deferredSeedCells());
        assertEquals(0, deferred.plannedMaterials().get(Material.WHEAT_SEEDS));
        for (Material material : List.of(Material.DIRT, Material.GLOWSTONE, Material.BIRCH_PLANKS)) {
            assertEquals(full.plannedMaterials().get(material), deferred.plannedMaterials().get(material));
        }
        List<WorkOrder> expected = new LayerBuildSchedule(full).entries().stream()
                .map(LayerBuildSchedule.Entry::order).filter(order -> !(order instanceof WorkOrder.Plant)).toList();
        LayerBuildSchedule actual = new LayerBuildSchedule(deferred);
        assertEquals(expected, actual.entries().stream().map(LayerBuildSchedule.Entry::order).toList());
        assertEquals(7, actual.stageCount());
        assertEquals(2, actual.entries().stream().filter(entry -> entry.order() instanceof WorkOrder.Till).count());
    }

    @Test
    void deferredVerificationAcceptsEmptySeedCellsAndExistingWheatButRejectsAllOtherObstructions() {
        SchematicPlan full = plan();
        SchematicPlan deferred = full.withPlantingDeferred(true);
        Map<BlockPosition, BlockState> world = new HashMap<>();
        full.chunks().forEach(chunk -> chunk.expectedBlocks().forEach(target -> {
            if (!target.state().blockId().equals("minecraft:wheat")) { world.put(target.position(), target.state()); }
        }));
        assertTrue(verify(deferred, world).clean());
        assertFalse(verify(full, world).clean());
        world.put(new BlockPosition(0, 1, 0), new BlockState("minecraft:wheat", Map.of("age", "7")));
        assertTrue(verify(deferred, world).clean());
        world.put(new BlockPosition(0, 4, 0), new BlockState("minecraft:dirt"));
        assertEquals(1, verify(deferred, world).mismatches().size());
        world.remove(new BlockPosition(0, 4, 0));
        world.put(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"));
        assertFalse(verify(deferred, world).clean(), "Tilling is still mandatory");
        world.put(new BlockPosition(0, 0, 0), new BlockState("minecraft:farmland"));
        world.put(new BlockPosition(2, 6, 0), new BlockState("minecraft:dirt"));
        assertFalse(verify(deferred, world).clean(), "Source AIR outside crop cells is still checked");
    }

    @Test
    void mapsEveryFullScheduleBoundaryToDeferredWithoutLosingConsumedDirtOrSkippingConstruction() {
        SchematicPlan full = plan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(full);
        LayerBuildSchedule deferred = new LayerBuildSchedule(full.withPlantingDeferred(true));
        for (int cursor = 0; cursor <= schedule.size(); cursor++) {
            SupervisorCheckpoint saved = checkpoint(full, cursor, SupervisorState.PAUSED);
            SupervisorCheckpoint converted = PlantingModeTransition.apply(full.withPlantingDeferred(true), saved);
            int expected = (int) schedule.entries().subList(0, cursor).stream()
                    .filter(entry -> !(entry.order() instanceof WorkOrder.Plant)).count();
            assertEquals(expected, converted.scheduleCursor(), "cursor " + cursor);
            assertEquals(saved.consumedMaterials(), converted.consumedMaterials());
            assertEquals(saved.withdrawnMaterials(), converted.withdrawnMaterials());
            assertTrue(converted.plantingDeferred());
            assertEquals(SupervisorState.PAUSED, converted.state());
            assertEquals(0, converted.stableVerificationPasses());
            if (expected < deferred.size()) {
                assertEquals(deferred.entry(expected).order().phase(), converted.phase());
            }
        }
    }

    @Test
    void enablingPlantingResumesAtEarliestDeferredWorkAndNeverRetainsDoneVerification() {
        SchematicPlan deferred = plan().withPlantingDeferred(true);
        LayerBuildSchedule source = new LayerBuildSchedule(deferred);
        LayerBuildSchedule target = new LayerBuildSchedule(deferred.withPlantingDeferred(false));
        int firstPlant = 0;
        while (!(target.entry(firstPlant).order() instanceof WorkOrder.Plant)) { firstPlant++; }
        for (int cursor = 0; cursor <= source.size(); cursor++) {
            SupervisorCheckpoint saved = checkpoint(deferred, cursor,
                    cursor == source.size() ? SupervisorState.DONE : SupervisorState.PAUSED);
            SupervisorCheckpoint converted = PlantingModeTransition.apply(deferred.withPlantingDeferred(false), saved);
            assertEquals(Math.min(cursor, firstPlant), converted.scheduleCursor());
            assertFalse(converted.plantingDeferred());
            assertEquals(SupervisorState.PAUSED, converted.state());
            assertEquals(0, converted.stableVerificationPasses());
            assertEquals("", converted.lastVerificationFingerprint());
            assertEquals(saved.consumedMaterials(), converted.consumedMaterials());
        }
    }

    @Test
    void legacyCheckpointDefaultsToFullPlantingAndModeSurvivesJsonRoundTrip() {
        SupervisorCheckpoint saved = checkpoint(plan(), 0, SupervisorState.PAUSED);
        String legacy = CheckpointJsonCodec.toJson(saved).replace("  \"planting_deferred\": false,\n", "");
        assertFalse(CheckpointJsonCodec.fromJson(legacy).plantingDeferred());
        SupervisorCheckpoint converted = PlantingModeTransition.apply(plan().withPlantingDeferred(true), saved);
        assertEquals(converted, CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(converted)));
        assertEquals(LayerBuildSchedule.DEFERRED_PLANTING_ID, converted.scheduleId());
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                CheckpointJsonCodec.toJson(converted).replace("  \"planting_deferred\": true,\n", "")),
                "A deferred schedule cannot silently become a full planting checkpoint");
        assertSame(converted, PlantingModeTransition.apply(plan().withPlantingDeferred(true), converted));
    }

    @Test
    void refusesUnsettledTransfersActiveSessionsAndInvalidSourceCursorsWithoutChangingSavedData() {
        SchematicPlan target = plan().withPlantingDeferred(true);
        SupervisorCheckpoint saved = checkpoint(plan(), 0, SupervisorState.PAUSED);
        String original = CheckpointJsonCodec.toJson(saved);
        for (String changed : List.of(
                original.replace("\"withdrawal_in_flight\": false", "\"withdrawal_in_flight\": true"),
                original.replace("\"reconciliation_required\": false", "\"reconciliation_required\": true")
                        .replace("\"reconciliation_detail\": \"\"", "\"reconciliation_detail\": \"Uncertain receipt\""),
                original.replace("\"state\": \"PAUSED\"", "\"state\": \"BUILDING\""),
                original.replace("\"schedule_cursor\": 0", "\"schedule_cursor\": 999"),
                original.replace("\"repair_chunk_index\": -1", "\"repair_chunk_index\": 0"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> PlantingModeTransition.apply(target, CheckpointJsonCodec.fromJson(changed)));
        }
        assertEquals(original, CheckpointJsonCodec.toJson(saved));
    }

    private static SupervisorCheckpoint checkpoint(SchematicPlan plan, int cursor, SupervisorState state) {
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        WorkOrder next = cursor < schedule.size() ? schedule.entry(cursor).order() : null;
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, plan.planId(), state, SupervisorState.BUILDING,
                SupervisorState.BUILDING, next == null ? plan.chunkCount() : next.chunkIndex(),
                next == null ? BuildPhase.VERIFY : next.phase(),
                next == null ? VerificationStage.FINAL : VerificationStage.CHUNK, RecoveryStage.NONE,
                state == SupervisorState.DONE ? 2 : 0, state == SupervisorState.DONE ? "old-mode-fingerprint" : "",
                MaterialQuantities.of(Material.DIRT, 1), MaterialQuantities.of(Material.DIRT, 64),
                MaterialQuantities.empty(), MaterialQuantities.empty(), "", 0, false, false, false,
                false, false, "", schedule.id(), cursor, -1, plan.chunkCount(), plan.plantingDeferred());
    }

    private static VerificationResult verify(SchematicPlan plan, Map<BlockPosition, BlockState> world) {
        return SchematicVerification.verify(plan, VerificationScope.fullPlan(), new BlockObservation() {
            public boolean isChunkLoaded(ChunkCoordinate chunk) { return true; }
            public BlockState blockState(BlockPosition position) { return world.getOrDefault(position, BlockState.AIR); }
            public List<BlockPosition> temporaryScaffolding(VerificationScope scope) { return List.of(); }
        });
    }

    private static SchematicPlan plan() {
        return SchematicCompiler.compile(new BuildVolume(0, 0, 0, 2, 6, 0), List.of(
                target(0, 0, "farmland"), target(0, 1, "wheat"), target(1, 2, "glowstone"),
                target(0, 3, "farmland"), target(0, 4, "wheat"), target(1, 5, "glowstone"),
                target(0, 6, "birch_planks"), target(2, 0, "dirt")));
    }

    private static TargetBlock target(int x, int y, String block) {
        return new TargetBlock(new BlockPosition(x, y, 0), new BlockState("minecraft:" + block));
    }
}
