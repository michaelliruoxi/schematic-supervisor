package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildCheckTest {
    private static final int CHUNKS = 49;
    private static final BlockState COBBLESTONE = new BlockState("minecraft:cobblestone");
    private static final BlockState MOSS = new BlockState("minecraft:moss_block");
    private static final BlockState STEM = new BlockState("minecraft:pumpkin_stem", Map.of("age", "3"));
    private static final BuildCheck.Clearing MOSS_AND_STEMS = new BuildCheck.Clearing() {
        @Override public boolean replacesAtPlacement(OrdinaryPlacement placement, BlockState actual) {
            return actual.equals(MOSS);
        }

        @Override public boolean sweepsOpenCell(BlockState actual) {
            return actual.blockId().equals("minecraft:pumpkin_stem");
        }
    };

    @Test
    void finishedWorldMarksEveryPieceComplete() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Result result = check(plan, finished(plan));
        assertEquals(9 * CHUNKS, result.pieceCount());
        assertEquals(9 * CHUNKS, result.completePieces().count());
        assertEquals(result.totalActions(), result.doneActions());
        assertEquals(9 * CHUNKS, result.firstIncompletePiece());
        assertEquals(0, result.workLeft());
        assertEquals(0, result.attentionBlocks());
        assertEquals(CHUNKS, result.chunksChecked());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void emptyWorldLeavesEveryPieceToDo() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Result result = check(plan, received(plan));
        assertTrue(result.completePieces().isEmpty());
        assertEquals(0, result.doneActions());
        assertEquals(5 * CHUNKS, result.placementsLeft());
        assertEquals(2 * CHUNKS, result.tillingLeft());
        assertEquals(2 * CHUNKS, result.plantingLeft());
        assertEquals(0, result.firstIncompletePiece());
    }

    @Test
    void buildingStartsAtTheFirstUnfinishedPieceAndLaterFinishedPiecesAreKept() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        // Chunk 5 lost its lower dirt; the upper floor of chunk 9 was never planted.
        world.put(5 * 16, 0, 0, BlockState.AIR);
        world.put(2 * 16, 4, 16, BlockState.AIR);
        BuildCheck.Result result = check(plan, world);
        assertEquals(piece(0, 5), result.firstIncompletePiece());
        assertFalse(result.completePieces().contains(piece(0, 5)));
        assertTrue(result.completePieces().contains(piece(0, 6)));
        assertFalse(result.completePieces().contains(piece(6, 9)));
        // Dirt missing under farmland leaves both its placement and its tilling to do.
        assertFalse(result.completePieces().contains(piece(7, 5)));
        assertEquals(9 * CHUNKS - 3, result.completePieces().count());
        assertEquals(1, result.placementsLeft());
        assertEquals(1, result.tillingLeft());
        assertEquals(1, result.plantingLeft());
        assertEquals(result.totalActions() - 3, result.doneActions());
    }

    @Test
    void untilledDirtFinishesStructureButNotTilling() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        world.put(0, 3, 0, new BlockState("minecraft:dirt"));
        world.put(0, 4, 0, BlockState.AIR);
        BuildCheck.Result result = check(plan, world);
        assertTrue(result.completePieces().contains(piece(1, 0)));
        assertFalse(result.completePieces().contains(piece(5, 0)));
        assertFalse(result.completePieces().contains(piece(6, 0)));
        assertEquals(0, result.placementsLeft());
        assertEquals(1, result.tillingLeft());
        assertEquals(1, result.plantingLeft());
        assertEquals(0, result.wrongBlocks());
    }

    @Test
    void wrongAndExtraBlocksAreCountedAndSampledInPositionOrder() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        world.put(40, 5, 40, COBBLESTONE);
        world.put(1, 1, 1, COBBLESTONE);
        world.put(16, 2, 0, COBBLESTONE);
        world.put(32, 2, 0, MOSS);
        BuildCheck.Result result = check(plan, world, MOSS_AND_STEMS);
        assertEquals(2, result.wrongBlocks());
        assertEquals(1, result.wrongBlocksCleared());
        assertEquals(2, result.extraBlocks());
        assertEquals(0, result.extraBlocksCleared());
        assertEquals(3, result.attentionBlocks());
        assertEquals(List.of(
                new BuildCheck.Problem(BuildCheck.ProblemKind.EXTRA, new BlockPosition(1, 1, 1), 0,
                        BlockState.AIR, COBBLESTONE),
                new BuildCheck.Problem(BuildCheck.ProblemKind.WRONG, new BlockPosition(16, 2, 0), 1,
                        new BlockState("minecraft:glowstone"), COBBLESTONE),
                new BuildCheck.Problem(BuildCheck.ProblemKind.EXTRA, new BlockPosition(40, 5, 40), 16,
                        BlockState.AIR, COBBLESTONE)), result.problems());
        assertFalse(result.completePieces().contains(piece(2, 1)));
        assertFalse(result.completePieces().contains(piece(2, 2)));
        assertEquals(2, result.placementsLeft());
    }

    @Test
    void sampleIsBoundedButCountsAreComplete() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        for (int x = 1; x < 30; x++) {
            world.put(x, 5, 1, COBBLESTONE);
        }
        BuildCheck.Result result = check(plan, world);
        assertEquals(29, result.extraBlocks());
        assertEquals(BuildCheck.MAX_PROBLEMS, result.problems().size());
        assertEquals(new BlockPosition(1, 5, 1), result.problems().getFirst().position());
        assertEquals(9 * CHUNKS, result.completePieces().count(), "extra blocks do not reopen pieces");
    }

    @Test
    void unreceivedChunksStayUncheckedAndAreNotBlamed() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        world.put(3 * 16 + 5, 5, 5, COBBLESTONE);
        world.markUnreceived(3);
        BuildCheck.Result result = check(plan, world);
        assertEquals(CHUNKS - 1, result.chunksChecked());
        assertEquals(9, result.uncheckedActions());
        assertEquals(0, result.extraBlocks());
        assertEquals(piece(0, 3), result.firstIncompletePiece());
        for (int stage = 0; stage < 9; stage++) {
            assertFalse(result.completePieces().contains(piece(stage, 3)));
        }
        assertEquals(9 * CHUNKS - 9, result.completePieces().count());
    }

    @Test
    void unconfirmedCellIsUnknownRatherThanMissing() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        world.put(0, 6, 0, null);
        BuildCheck.Result result = check(plan, world);
        assertEquals(1, result.uncheckedActions());
        assertEquals(0, result.placementsLeft());
        assertFalse(result.completePieces().contains(piece(3, 0)));
        assertEquals(CHUNKS, result.chunksChecked());
    }

    @Test
    void removableStemReopensTheFirstPieceWhoseSweepReachesIt() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        BuildCheck.Snapshot world = finished(plan);
        // Chunk 7 starts at z = 16; y = 5 is first reached by the chunk's STRUCTURE:6 sweep (stage 4).
        world.put(3, 5, 17, STEM);
        BuildCheck.Result result = check(plan, world, MOSS_AND_STEMS);
        assertEquals(1, result.extraBlocks());
        assertEquals(1, result.extraBlocksCleared());
        assertEquals(0, result.attentionBlocks());
        assertTrue(result.problems().isEmpty());
        assertEquals(piece(3, 7), result.firstIncompletePiece());
        assertEquals(9 * CHUNKS - 1, result.completePieces().count());
    }

    @Test
    void stemOutsideEverySweepNeedsAttention() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt")),
                new TargetBlock(new BlockPosition(0, 2, 0), new BlockState("minecraft:dirt"))));
        BuildCheck.Snapshot world = finished(plan);
        world.put(20, 1, 0, STEM);
        BuildCheck.Result result = check(plan, world, MOSS_AND_STEMS);
        assertEquals(1, result.extraBlocks());
        assertEquals(0, result.extraBlocksCleared());
        assertEquals(BuildCheck.ProblemKind.EXTRA, result.problems().getFirst().kind());
        assertEquals(2, result.completePieces().count());
    }

    @Test
    void deferredCropStemReopensACoveringPiece() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true);
        BuildCheck.Snapshot world = finished(plan);
        world.put(0, 1, 0, BlockState.AIR);
        BuildCheck.Result unplanted = check(plan, world, MOSS_AND_STEMS);
        assertEquals(7 * CHUNKS, unplanted.completePieces().count(), "deferred crop cells may stay empty");
        assertEquals(0, unplanted.wrongBlocks());

        world.put(0, 1, 0, STEM);
        BuildCheck.Result result = check(plan, world, MOSS_AND_STEMS);
        assertEquals(1, result.wrongBlocks());
        assertEquals(1, result.wrongBlocksCleared());
        assertEquals(0, result.firstIncompletePiece(), "the STRUCTURE:0 sweep reaches y = 1");
    }

    @Test
    void temporarySupportsAreNotExtraAndTheirSliceRunsNext() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        BuildCheck.Snapshot world = finished(plan);
        world.put(2, 1, 2, COBBLESTONE);
        WorkOrder slice = schedule.entry(CHUNKS + 4).order();
        BuildCheck.Result result = BuildCheck.evaluate(plan, schedule, world, BuildCheck.Clearing.NONE,
                new BuildCheck.Supports(List.of(new BlockPosition(2, 1, 2)), slice));
        assertEquals(1, result.temporarySupports());
        assertEquals(0, result.extraBlocks());
        assertEquals(CHUNKS + 4, result.firstIncompletePiece());
        assertEquals(9 * CHUNKS - 1, result.completePieces().count());
    }

    @Test
    void rejectsASnapshotOrScheduleFromAnotherPlan() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan();
        SchematicPlan other = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"))));
        assertThrows(IllegalArgumentException.class, () -> BuildCheck.evaluate(plan, new LayerBuildSchedule(plan),
                new BuildCheck.Snapshot(other.buildVolume(), other.layout()), BuildCheck.Clearing.NONE,
                BuildCheck.Supports.NONE));
        assertThrows(IllegalArgumentException.class, () -> BuildCheck.evaluate(plan,
                new LayerBuildSchedule(plan.withPlantingDeferred(true)), finished(plan), BuildCheck.Clearing.NONE,
                BuildCheck.Supports.NONE));
    }

    /** The schedule index of a stage's piece in one chunk; every farm stage visits all chunks along the tour. */
    private static int piece(int stage, int chunk) {
        int[] tour = ChunkTour.order(7, 7);
        for (int position = 0; position < tour.length; position++) {
            if (tour[position] == chunk) { return stage * CHUNKS + position; }
        }
        throw new IllegalArgumentException("no chunk " + chunk);
    }

    static BuildCheck.Result check(SchematicPlan plan, BuildCheck.Snapshot world) {
        return check(plan, world, BuildCheck.Clearing.NONE);
    }

    private static BuildCheck.Result check(SchematicPlan plan, BuildCheck.Snapshot world,
                                           BuildCheck.Clearing clearing) {
        return BuildCheck.evaluate(plan, new LayerBuildSchedule(plan), world, clearing, BuildCheck.Supports.NONE);
    }

    /** Every chunk received, every cell air. */
    static BuildCheck.Snapshot received(SchematicPlan plan) {
        BuildCheck.Snapshot world = new BuildCheck.Snapshot(plan.buildVolume(), plan.layout());
        BuildVolume volume = plan.buildVolume();
        for (int y = volume.minY(); y <= volume.maxY(); y++) {
            for (int x = volume.minX(); x <= volume.maxX(); x++) {
                for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                    world.put(x, y, z, BlockState.AIR);
                }
            }
        }
        for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
            world.markReceived(chunk);
        }
        return world;
    }

    /** Every chunk received and every planned block in its final state. */
    static BuildCheck.Snapshot finished(SchematicPlan plan) {
        BuildCheck.Snapshot world = received(plan);
        for (ChunkPlan chunk : plan.chunks()) {
            for (TargetBlock target : chunk.expectedBlocks()) {
                world.put(target.position().x(), target.position().y(), target.position().z(), target.state());
            }
        }
        return world;
    }
}
