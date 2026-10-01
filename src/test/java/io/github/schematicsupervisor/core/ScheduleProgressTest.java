package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ScheduleProgressTest {
    private static final int CHUNKS = 49;

    @Test
    void freshPlanMarksTheFirstPieceCurrentAndNothingDone() {
        ScheduleProgress progress = progress(LayerBuildScheduleTest.farmPlan(), 0, -1);
        assertEquals(9 * CHUNKS, progress.totalActions());
        assertEquals(0, progress.doneActions());
        assertEquals(OptionalInt.of(0), progress.currentStage());
        assertEquals("C" + "-".repeat(CHUNKS - 1), progress.chunkStatuses(0));
        assertEquals("-".repeat(CHUNKS), progress.chunkStatuses(1));
        assertEquals("STRUCTURE", progress.stageKind(0));
        assertEquals(0, progress.stageY(0));
        assertEquals(LayerBuildSchedule.ID, progress.scheduleId());
    }

    @Test
    void cursorInsideAStageCountsOnlyFinishedPieces() {
        ScheduleProgress progress = progress(LayerBuildScheduleTest.farmPlan(), CHUNKS + 40, -1);
        assertEquals(CHUNKS + 40, progress.doneActions());
        assertEquals(CHUNKS, progress.stageDone(0));
        assertEquals(40, progress.stageDone(1));
        assertEquals(0, progress.stageDone(2));
        assertEquals(OptionalInt.of(1), progress.currentStage());
        assertEquals(alongTour(40, 'C', '-'), progress.chunkStatuses(1));
    }

    @Test
    void finishedScheduleMarksEveryPieceDone() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        ScheduleProgress progress = progress(plan, new LayerBuildSchedule(plan).size(), -1);
        assertEquals(progress.totalActions(), progress.doneActions());
        assertTrue(progress.currentStage().isEmpty());
        for (int stage = 0; stage < progress.stageCount(); stage++) {
            assertEquals("D".repeat(CHUNKS), progress.chunkStatuses(stage));
        }
    }

    @Test
    void chunkRepairRewalksOnlyTheRepairChunk() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        int cursor = schedule.nextForChunk(0, 17);
        ScheduleProgress repairing = progress(plan, cursor, 17);
        assertEquals(9 * CHUNKS - 9, repairing.doneActions());
        assertEquals(OptionalInt.of(0), repairing.currentStage());
        assertEquals("D".repeat(17) + "C" + "D".repeat(31), repairing.chunkStatuses(0));
        assertEquals("D".repeat(17) + "-" + "D".repeat(31), repairing.chunkStatuses(8));
        ScheduleProgress later = progress(plan, schedule.nextForChunk(cursor + 1, 17), 17);
        assertEquals(9 * CHUNKS - 8, later.doneActions());
        assertEquals("D".repeat(CHUNKS), later.chunkStatuses(0));
        assertEquals("D".repeat(17) + "C" + "D".repeat(31), later.chunkStatuses(1));
    }

    @Test
    void checkedPiecesAfterTheCursorCountAsDone() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        java.util.BitSet finished = new java.util.BitSet();
        finished.set(0, 2 * CHUNKS);
        finished.clear(5);
        finished.set(4 * CHUNKS + 10);
        ScheduleProgress progress = ScheduleProgress.of(ScheduleProgress.index(plan, new LayerBuildSchedule(plan)),
                5, -1, CompletedPieces.of(finished));
        assertEquals(2 * CHUNKS - 1 + 1, progress.doneActions());
        assertEquals(CHUNKS - 1, progress.stageDone(0));
        assertEquals(CHUNKS, progress.stageDone(1));
        assertEquals(1, progress.stageDone(4));
        assertEquals(OptionalInt.of(0), progress.currentStage());
        assertEquals("DDDDDC" + "D".repeat(CHUNKS - 6), progress.chunkStatuses(0));
        assertEquals("D".repeat(CHUNKS), progress.chunkStatuses(1));
        char[] lastChecked = "-".repeat(CHUNKS).toCharArray();
        lastChecked[ChunkTour.order(7, 7)[10]] = 'D';
        assertEquals(new String(lastChecked), progress.chunkStatuses(4));
    }

    @Test
    void theCurrentPieceIsNeverCountedAsCheckedAndRepairsIgnoreCheckedPieces() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        ScheduleProgress.Index index = ScheduleProgress.index(plan, schedule);
        java.util.BitSet finished = new java.util.BitSet();
        finished.set(0, 3);
        ScheduleProgress atCheckedPiece = ScheduleProgress.of(index, 1, -1, CompletedPieces.of(finished));
        assertEquals(2, atCheckedPiece.doneActions());
        assertEquals("DCD" + "-".repeat(CHUNKS - 3), atCheckedPiece.chunkStatuses(0));
        int cursor = schedule.nextForChunk(0, 17);
        assertEquals(progress(plan, cursor, 17).doneActions(),
                ScheduleProgress.of(index, cursor, 17, CompletedPieces.of(finished)).doneActions());
        finished.set(schedule.size());
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleProgress.of(index, 0, -1, CompletedPieces.of(finished)));
    }

    @Test
    void materialsCountTheItemsOfFinishedPieces() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        ScheduleProgress.Index index = ScheduleProgress.index(plan, schedule);
        ScheduleProgress fresh = ScheduleProgress.of(index, 0, -1);
        assertEquals(plan.plannedMaterials(), fresh.plannedMaterials());
        assertEquals(MaterialQuantities.empty(), fresh.doneMaterials());
        // The birch planks at Y 6 start at entry 3 * CHUNKS; 40 of their pieces are finished.
        assertEquals(materials(98, 49, 40, 0), ScheduleProgress.of(index, 3 * CHUNKS + 40, -1).doneMaterials());
        assertEquals(plan.plannedMaterials(), ScheduleProgress.of(index, schedule.size(), -1).doneMaterials());
    }

    @Test
    void materialsCountCheckedPiecesAndRepairsLikeActions() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        ScheduleProgress.Index index = ScheduleProgress.index(plan, schedule);
        java.util.BitSet finished = new java.util.BitSet();
        finished.set(0, 2 * CHUNKS);
        finished.clear(5);
        finished.set(4 * CHUNKS + 10);
        assertEquals(materials(97, 1, 0, 0),
                ScheduleProgress.of(index, 5, -1, CompletedPieces.of(finished)).doneMaterials());
        // A repair re-walks chunk 17: two dirt, two Glowstone, one birch planks, and two seeds.
        assertEquals(materials(96, 96, 48, 96),
                ScheduleProgress.of(index, schedule.nextForChunk(0, 17), 17).doneMaterials());
    }

    @Test
    void materialsSplitAPieceThatMixesMaterials() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                dirt(0, 0, 0), new TargetBlock(new BlockPosition(1, 0, 0), new BlockState("minecraft:birch_planks")),
                dirt(2, 0, 0), dirt(16, 0, 0)));
        ScheduleProgress second = progress(plan, 1, -1);
        assertEquals(1, second.stageCount());
        assertEquals(MaterialQuantities.of(Map.of(Material.DIRT, 3L, Material.BIRCH_PLANKS, 1L)),
                second.plannedMaterials());
        assertEquals(MaterialQuantities.of(Map.of(Material.DIRT, 2L, Material.BIRCH_PLANKS, 1L)),
                second.doneMaterials());
    }

    @Test
    void chunksWithoutWorkAtAStageAreMarkedNoWork() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                dirt(0, 0, 0), dirt(1, 0, 0), dirt(2, 0, 0), dirt(16, 0, 0)));
        ScheduleProgress start = progress(plan, 0, -1);
        assertEquals(1, start.stageCount());
        assertEquals(4, start.stageActions(0));
        assertEquals("C-" + ".".repeat(CHUNKS - 2), start.chunkStatuses(0));
        ScheduleProgress second = progress(plan, 1, -1);
        assertEquals(3, second.doneActions());
        assertEquals("DC" + ".".repeat(CHUNKS - 2), second.chunkStatuses(0));
    }

    @Test
    void scheduleVariantsCountTheWorkTheyContain() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        assertEquals(9 * CHUNKS, progress(plan.withGlowstoneAfterStructure(true), 0, -1).totalActions());
        ScheduleProgress deferred = progress(plan.withPlantingDeferred(true), 0, -1);
        assertEquals(7, deferred.stageCount());
        assertEquals(7 * CHUNKS, deferred.totalActions());
        for (SchematicPlan variant : List.of(plan.withGlowstoneAfterStructure(true), plan.withPlantingDeferred(true),
                plan.withGlowstoneAfterStructure(true).withPlantingDeferred(true))) {
            assertEquals(variant.plannedMaterials(), progress(variant, 0, -1).plannedMaterials());
        }
        assertEquals(0, deferred.plannedMaterials().get(Material.WHEAT_SEEDS));
    }

    @Test
    void emptyPlanHasNoStagesAndNothingToDo() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of());
        ScheduleProgress progress = progress(plan, 0, -1);
        assertEquals(0, progress.stageCount());
        assertEquals(0, progress.totalActions());
        assertTrue(progress.currentStage().isEmpty());
        assertEquals(MaterialQuantities.empty(), progress.plannedMaterials());
        assertEquals(MaterialQuantities.empty(), progress.doneMaterials());
    }

    @Test
    void rejectsCursorOrRepairChunkOutsideThePlan() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        ScheduleProgress.Index index = ScheduleProgress.index(plan, new LayerBuildSchedule(plan));
        assertThrows(IllegalArgumentException.class, () -> ScheduleProgress.of(index, -1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleProgress.of(index, index.entryCount() + 1, -1));
        assertThrows(IllegalArgumentException.class, () -> ScheduleProgress.of(index, 0, CHUNKS));
    }

    private static ScheduleProgress progress(SchematicPlan plan, int cursor, int repairChunkIndex) {
        return ScheduleProgress.of(ScheduleProgress.index(plan, new LayerBuildSchedule(plan)),
                cursor, repairChunkIndex);
    }

    private static TargetBlock dirt(int x, int y, int z) {
        return new TargetBlock(new BlockPosition(x, y, z), new BlockState("minecraft:dirt"));
    }

    private static MaterialQuantities materials(long dirt, long glowstone, long birchPlanks, long seeds) {
        return MaterialQuantities.of(Map.of(Material.DIRT, dirt, Material.GLOWSTONE, glowstone,
                Material.BIRCH_PLANKS, birchPlanks, Material.WHEAT_SEEDS, seeds));
    }

    /** Chunk letters for a stage whose first {@code done} tour pieces are done and the next is {@code next}. */
    private static String alongTour(int done, char next, char rest) {
        int[] tour = ChunkTour.order(7, 7);
        char[] letters = new char[CHUNKS];
        for (int step = 0; step < CHUNKS; step++) {
            letters[tour[step]] = step < done ? 'D' : step == done ? next : rest;
        }
        return new String(letters);
    }
}
