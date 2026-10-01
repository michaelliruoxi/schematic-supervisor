package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructureFirstScheduleTest {
    @Test
    void finishesEveryStructuralLayerBeforeLightingWithoutChangingTargetsOrMaterials() {
        for (boolean deferred : new boolean[] {false, true}) {
            SchematicPlan original = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(deferred);
            SchematicPlan reordered = original.withGlowstoneAfterStructure(true);
            LayerBuildSchedule before = new LayerBuildSchedule(original);
            LayerBuildSchedule after = new LayerBuildSchedule(reordered);
            assertEquals(original.planId(), reordered.planId());
            assertEquals(original.plannedMaterials(), reordered.plannedMaterials());
            assertEquals(before.size(), after.size());
            assertEquals(new HashSet<>(before.entries().stream().map(LayerBuildSchedule.Entry::order).toList()),
                    new HashSet<>(after.entries().stream().map(LayerBuildSchedule.Entry::order).toList()));
            assertEquals(List.of("STRUCTURE:0", "STRUCTURE:3", "STRUCTURE:6", "LIGHTING:2", "LIGHTING:5"),
                    after.entries().stream().filter(entry -> entry.progress().chunkOrdinal() == 1)
                            .limit(5).map(entry -> entry.progress().stage() + ":" + entry.progress().y()).toList());
            int[] tour = ChunkTour.order(7, 7);
            for (int i = 0; i < after.size(); i++) {
                assertEquals(tour[i % 49], after.entry(i).order().chunkIndex());
                assertEquals(i / 49 + 1, after.entry(i).progress().ordinal());
            }
        }
    }

    @Test
    void everyCursorAcrossBothOrderingAndPlantingModesResumesAtTheFirstUnfinishedOrder() {
        for (boolean sourceDeferred : new boolean[] {false, true}) {
            for (boolean sourceStructureFirst : new boolean[] {false, true}) {
                SchematicPlan sourcePlan = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(sourceDeferred)
                        .withGlowstoneAfterStructure(sourceStructureFirst);
                LayerBuildSchedule source = new LayerBuildSchedule(sourcePlan);
                for (boolean targetDeferred : new boolean[] {false, true}) {
                    for (boolean targetStructureFirst : new boolean[] {false, true}) {
                        SchematicPlan targetPlan = sourcePlan.withPlantingDeferred(targetDeferred)
                                .withGlowstoneAfterStructure(targetStructureFirst);
                        LayerBuildSchedule target = new LayerBuildSchedule(targetPlan);
                        var completed = new HashSet<WorkOrder>();
                        for (int cursor = 0; cursor <= source.size(); cursor++) {
                            SupervisorCheckpoint saved = checkpoint(sourcePlan, cursor);
                            SupervisorCheckpoint mapped = PlantingModeTransition.apply(targetPlan, saved);
                            int expected = 0;
                            while (expected < target.size() && completed.contains(target.entry(expected).order())) {
                                expected++;
                            }
                            assertEquals(expected, mapped.scheduleCursor(), source.id() + " -> " + target.id() + " @ " + cursor);
                            assertEquals(saved.consumedMaterials(), mapped.consumedMaterials());
                            assertEquals(saved.withdrawnMaterials(), mapped.withdrawnMaterials());
                            assertEquals(saved.lastAppliedPlannedCredit(), mapped.lastAppliedPlannedCredit());
                            assertEquals(saved.lastError(), mapped.lastError());
                            assertEquals(SupervisorState.PAUSED, mapped.state());
                            assertEquals(mapped, CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(mapped)));
                            assertSame(mapped, PlantingModeTransition.apply(targetPlan, mapped));
                            if (cursor < source.size()) { completed.add(source.entry(cursor).order()); }
                        }
                    }
                }
            }
        }
    }

    @Test
    void switchingDuringLightingReturnsToUnfinishedDirtAndRetainsBlockedSliceRecoveryLimits() {
        SchematicPlan source = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true);
        SchematicPlan target = source.withGlowstoneAfterStructure(true);
        SupervisorCheckpoint lighting = checkpoint(source, 2 * 49 + 20);
        SupervisorCheckpoint mapped = PlantingModeTransition.apply(target, lighting);
        assertEquals(2 * 49, mapped.scheduleCursor());
        assertEquals("STRUCTURE", new LayerBuildSchedule(target).entry(mapped.scheduleCursor()).progress().stage());
        SupervisorCheckpoint blockedDirt = checkpoint(source, 3 * 49 + 7);
        mapped = PlantingModeTransition.apply(target, blockedDirt);
        assertEquals(2 * 49 + 7, mapped.scheduleCursor());
        assertTrue(mapped.repathAttempted());
        assertTrue(mapped.safeReturnAttempted());
        assertTrue(mapped.advisorAttempted());
    }

    @Test
    void unsettledTransfersRepairsActiveRunsAndInvalidCursorsCannotChangeSchedule() {
        SchematicPlan source = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true);
        String original = CheckpointJsonCodec.toJson(checkpoint(source, 0));
        for (String changed : List.of(
                original.replace("\"withdrawal_in_flight\": false", "\"withdrawal_in_flight\": true"),
                original.replace("\"reconciliation_required\": false", "\"reconciliation_required\": true")
                        .replace("\"reconciliation_detail\": \"\"", "\"reconciliation_detail\": \"Uncertain receipt\""),
                original.replace("\"state\": \"PAUSED\"", "\"state\": \"BUILDING\""),
                original.replace("\"schedule_cursor\": 0", "\"schedule_cursor\": 99999"),
                original.replace("\"repair_chunk_index\": -1", "\"repair_chunk_index\": 0"))) {
            assertThrows(IllegalArgumentException.class, () -> PlantingModeTransition.apply(
                    source.withGlowstoneAfterStructure(true), CheckpointJsonCodec.fromJson(changed)));
        }
    }

    @Test
    void finalVerificationStillRejectsMissingGlowstone() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true).withGlowstoneAfterStructure(true);
        Map<BlockPosition, BlockState> world = new HashMap<>();
        plan.chunks().forEach(chunk -> chunk.expectedBlocks().forEach(target -> {
            if (!target.state().blockId().equals("minecraft:wheat")) { world.put(target.position(), target.state()); }
        }));
        BlockObservation observation = new BlockObservation() {
            public boolean isChunkLoaded(ChunkCoordinate chunk) { return true; }
            public BlockState blockState(BlockPosition position) { return world.getOrDefault(position, BlockState.AIR); }
            public List<BlockPosition> temporaryScaffolding(VerificationScope scope) { return List.of(); }
        };
        assertTrue(SchematicVerification.verify(plan, VerificationScope.fullPlan(), observation).clean());
        world.remove(new BlockPosition(0, 2, 0));
        assertFalse(SchematicVerification.verify(plan, VerificationScope.fullPlan(), observation).clean());
    }

    private static SupervisorCheckpoint checkpoint(SchematicPlan plan, int cursor) {
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        WorkOrder next = cursor < schedule.size() ? schedule.entry(cursor).order() : null;
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, plan.planId(), SupervisorState.PAUSED,
                SupervisorState.BUILDING, SupervisorState.BUILDING, next == null ? plan.chunkCount() : next.chunkIndex(),
                next == null ? BuildPhase.VERIFY : next.phase(),
                next == null ? VerificationStage.FINAL : VerificationStage.CHUNK, RecoveryStage.NONE, 0, "",
                MaterialQuantities.of(Material.DIRT, 1), MaterialQuantities.of(Material.DIRT, 64),
                MaterialQuantities.empty(), MaterialQuantities.empty(), "Protected obstruction", 2, true, true, true,
                false, false, "", schedule.id(), cursor, -1, plan.chunkCount(), plan.plantingDeferred(),
                new PlannedConsumptionCredit("123e4567-e89b-12d3-a456-426614174000", plan.planId(), Material.DIRT, 1));
    }
}
