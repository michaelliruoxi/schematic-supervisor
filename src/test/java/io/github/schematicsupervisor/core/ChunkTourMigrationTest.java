package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkTourMigrationTest {
    private static final PlannedConsumptionCredit NO_CREDIT = null;

    @Test
    void everyRowMajorCursorKeepsFinishedPiecesAndResumesAtTheFirstUnfinishedTourPiece() {
        for (boolean deferred : new boolean[] {false, true}) {
            for (boolean structureFirst : new boolean[] {false, true}) {
                SchematicPlan plan = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(deferred)
                        .withGlowstoneAfterStructure(structureFirst);
                LayerBuildSchedule rowMajor = LayerBuildSchedule.forId(plan,
                        LayerBuildSchedule.id(deferred, structureFirst).replace("layers-v2", "layers-v1"));
                LayerBuildSchedule tour = new LayerBuildSchedule(plan);
                for (int cursor = 0; cursor <= rowMajor.size(); cursor++) {
                    BitSet checked = new BitSet();
                    if (cursor < rowMajor.size()) {
                        for (int piece = cursor + 1; piece < rowMajor.size(); piece += 3) { checked.set(piece); }
                    }
                    SupervisorCheckpoint saved = checkpoint(plan, rowMajor, cursor, checked, SupervisorState.PAUSED);
                    SupervisorCheckpoint mapped = PlantingModeTransition.apply(plan, saved);
                    String label = rowMajor.id() + " @ " + cursor;
                    assertEquals(tour.id(), mapped.scheduleId(), label);
                    Set<WorkOrder> finished = finished(rowMajor, saved);
                    assertEquals(finished, finished(tour, mapped), label);
                    int expected = 0;
                    while (expected < tour.size() && finished.contains(tour.entry(expected).order())) { expected++; }
                    assertEquals(expected, mapped.scheduleCursor(), label);
                    assertEquals(SupervisorState.PAUSED, mapped.state());
                    assertEquals(saved.consumedMaterials(), mapped.consumedMaterials());
                    assertEquals(saved.withdrawnMaterials(), mapped.withdrawnMaterials());
                    assertEquals(saved.lastError(), mapped.lastError());
                    assertEquals(saved.plantingDeferred(), mapped.plantingDeferred());
                    if (cursor < rowMajor.size()) {
                        WorkOrder next = tour.entry(mapped.scheduleCursor()).order();
                        assertEquals(next.chunkIndex(), mapped.currentChunkIndex());
                        assertEquals(next.phase(), mapped.phase());
                        boolean samePiece = next.equals(rowMajor.entry(cursor).order());
                        assertEquals(samePiece, mapped.repathAttempted(), label);
                        assertEquals(samePiece ? 2 : 0, mapped.verificationRetries(), label);
                    } else {
                        assertEquals(BuildPhase.VERIFY, mapped.phase());
                        assertTrue(mapped.checkedPieces().isEmpty());
                    }
                    assertEquals(mapped, CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(mapped)));
                    assertSame(mapped, PlantingModeTransition.apply(plan, mapped));
                }
            }
        }
    }

    @Test
    void anyStateAndPendingSettlementIsKeptBecauseOnlyTheChunkOrderChanges() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule rowMajor = LayerBuildSchedule.forId(plan, LayerBuildSchedule.ROW_MAJOR_ID);
        for (SupervisorState state : List.of(SupervisorState.STOPPED, SupervisorState.BUILDING,
                SupervisorState.RESTOCKING, SupervisorState.STUCK)) {
            SupervisorCheckpoint saved = checkpoint(plan, rowMajor, 49 + 9, new BitSet(), state);
            SupervisorCheckpoint mapped = PlantingModeTransition.apply(plan, saved);
            assertEquals(state, mapped.state());
            assertEquals(LayerBuildSchedule.ID, mapped.scheduleId());
        }
        String json = CheckpointJsonCodec.toJson(checkpoint(plan, rowMajor, 49 + 9, new BitSet(), SupervisorState.PAUSED));
        SupervisorCheckpoint uncertain = CheckpointJsonCodec.fromJson(json
                .replace("\"withdrawal_in_flight\": false", "\"withdrawal_in_flight\": true")
                .replace("\"reconciliation_required\": false", "\"reconciliation_required\": true")
                .replace("\"reconciliation_detail\": \"\"", "\"reconciliation_detail\": \"Uncertain receipt\""));
        SupervisorCheckpoint mapped = PlantingModeTransition.apply(plan, uncertain);
        assertTrue(mapped.withdrawalInFlight());
        assertTrue(mapped.reconciliationRequired());
        assertEquals("Uncertain receipt", mapped.reconciliationDetail());
    }

    @Test
    void finishedBuildStaysDoneAndAChunkRepairContinuesAtTheSamePiece() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule rowMajor = LayerBuildSchedule.forId(plan, LayerBuildSchedule.ROW_MAJOR_ID);
        LayerBuildSchedule tour = new LayerBuildSchedule(plan);
        SupervisorCheckpoint done = checkpoint(plan, rowMajor, rowMajor.size(), new BitSet(), SupervisorState.DONE);
        SupervisorCheckpoint mappedDone = PlantingModeTransition.apply(plan, done);
        assertEquals(SupervisorState.DONE, mappedDone.state());
        assertEquals(tour.size(), mappedDone.scheduleCursor());
        assertEquals(done.verificationStage(), mappedDone.verificationStage());
        assertEquals(done.currentChunkIndex(), mappedDone.currentChunkIndex());

        int cursor = rowMajor.nextForChunk(3 * 49, 9);
        SupervisorCheckpoint repair = checkpoint(plan, rowMajor, cursor, new BitSet(), SupervisorState.PAUSED, 9);
        SupervisorCheckpoint mapped = PlantingModeTransition.apply(plan, repair);
        assertEquals(9, mapped.repairChunkIndex());
        assertEquals(rowMajor.entry(cursor).order(), tour.entry(mapped.scheduleCursor()).order());
        assertTrue(mapped.repathAttempted(), "a repair keeps its recovery attempts");
        assertTrue(mapped.checkedPieces().isEmpty());
    }

    @Test
    void chunkOrderAndPlantingModeCanChangeTogetherWhenSettled() {
        SchematicPlan saved = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true);
        SchematicPlan target = saved.withPlantingDeferred(false);
        LayerBuildSchedule rowMajor = LayerBuildSchedule.forId(saved, LayerBuildSchedule.ROW_MAJOR_DEFERRED_PLANTING_ID);
        SupervisorCheckpoint paused = checkpoint(saved, rowMajor, 49 + 9, new BitSet(), SupervisorState.PAUSED);
        SupervisorCheckpoint mapped = PlantingModeTransition.apply(target, paused);
        assertEquals(LayerBuildSchedule.ID, mapped.scheduleId());
        assertFalse(mapped.plantingDeferred());
        assertEquals(PlantingModeTransition.apply(target, PlantingModeTransition.apply(saved, paused)), mapped);
        SupervisorCheckpoint active = checkpoint(saved, rowMajor, 49 + 9, new BitSet(), SupervisorState.BUILDING);
        assertThrows(IllegalArgumentException.class, () -> PlantingModeTransition.apply(target, active));
        SupervisorCheckpoint otherPlan = checkpoint(saved, rowMajor, 0, new BitSet(), SupervisorState.PAUSED);
        assertThrows(IllegalArgumentException.class, () -> PlantingModeTransition.apply(
                SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(new TargetBlock(
                        new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt")))), otherPlan));
    }

    private static Set<WorkOrder> finished(LayerBuildSchedule schedule, SupervisorCheckpoint checkpoint) {
        Set<WorkOrder> finished = new HashSet<>();
        for (int piece = 0; piece < schedule.size(); piece++) {
            if (piece < checkpoint.scheduleCursor() || checkpoint.checkedPieces().contains(piece)) {
                finished.add(schedule.entry(piece).order());
            }
        }
        return finished;
    }

    private static SupervisorCheckpoint checkpoint(SchematicPlan plan, LayerBuildSchedule schedule, int cursor,
                                                   BitSet checked, SupervisorState state) {
        return checkpoint(plan, schedule, cursor, checked, state, -1);
    }

    private static SupervisorCheckpoint checkpoint(SchematicPlan plan, LayerBuildSchedule schedule, int cursor,
                                                   BitSet checked, SupervisorState state, int repairChunk) {
        WorkOrder next = cursor < schedule.size() ? schedule.entry(cursor).order() : null;
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, plan.planId(), state,
                SupervisorState.BUILDING, SupervisorState.BUILDING, next == null ? plan.chunkCount() : next.chunkIndex(),
                next == null ? BuildPhase.VERIFY : next.phase(),
                next == null ? VerificationStage.FINAL : VerificationStage.CHUNK, RecoveryStage.NONE,
                next == null && state == SupervisorState.DONE ? 2 : 0, next == null && state == SupervisorState.DONE
                        ? "sha256:" + "a".repeat(64) : "",
                MaterialQuantities.of(Material.DIRT, 5), MaterialQuantities.of(Material.DIRT, 64),
                MaterialQuantities.empty(), MaterialQuantities.empty(), "Protected obstruction", 2, true, true, true,
                false, false, "", schedule.id(), cursor, repairChunk, plan.chunkCount(), plan.plantingDeferred(),
                NO_CREDIT, CompletedPieces.of(checked));
    }
}
