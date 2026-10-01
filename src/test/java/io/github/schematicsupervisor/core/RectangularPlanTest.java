package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RectangularPlanTest {
    @Test
    void unalignedNegativeRectangleRetainsEmptyChunksAndRowMajorOrder() {
        BuildVolume volume = new BuildVolume(-17, -3, -1, 16, -2, 17);
        SchematicPlan plan = SchematicCompiler.compile(volume, List.of(dirt(-17, -3, -1), dirt(16, -2, 17)));
        assertEquals(new ChunkLayout(new ChunkCoordinate(-2, -1), 4, 3), plan.layout());
        assertEquals(12, plan.chunkCount());
        assertEquals(new ChunkCoordinate(-2, -1), plan.chunk(0).chunk());
        assertEquals(new ChunkCoordinate(1, -1), plan.chunk(3).chunk());
        assertEquals(new ChunkCoordinate(-2, 0), plan.chunk(4).chunk());
        assertEquals(new ChunkCoordinate(1, 1), plan.chunk(11).chunk());
        assertTrue(plan.chunk(5).expectedBlocks().isEmpty());
        assertEquals(2, plan.plannedMaterials().get(Material.DIRT));
        assertThrows(IndexOutOfBoundsException.class, () -> plan.chunk(12));
        assertThrows(IndexOutOfBoundsException.class, () -> plan.chunk(-1));
    }

    @Test
    void legacyFingerprintAndScheduleRemainIdenticalForTheSameSevenBySevenVolume() {
        BuildVolume volume = new BuildVolume(0, 0, 0, 111, 0, 111);
        List<TargetBlock> targets = List.of(dirt(0, 0, 0));
        SchematicPlan legacy = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), volume, targets);
        SchematicPlan rectangular = SchematicCompiler.compile(volume, targets);
        assertEquals("sha256:2463b6f76d112f7838681ca5d848e04ae0873ffda8fa3846c40838b323573efa", legacy.planId());
        assertEquals(legacy, rectangular);
        assertEquals(new LayerBuildSchedule(legacy).entries(), new LayerBuildSchedule(rectangular).entries());
    }

    @Test
    void smallTightRectangleCannotReuseTheIdentityOfALegacyPaddedPlan() {
        BuildVolume volume = new BuildVolume(0, 0, 0, 15, 0, 31);
        List<TargetBlock> targets = List.of(dirt(0, 0, 0));
        SchematicPlan legacy = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), volume, targets);
        SchematicPlan rectangular = SchematicCompiler.compile(volume, targets);
        assertEquals(49, legacy.chunkCount());
        assertEquals(2, rectangular.chunkCount());
        assertNotEquals(legacy.planId(), rectangular.planId());
        assertEquals(rectangular.planId(), SchematicCompiler.compile(volume, targets).planId());
    }

    @Test
    void layerOrdersCanUseIndexesAboveFortyEightWithoutChangingStageProgressMeaning() {
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 159, 2, 111),
                List.of(dirt(144, 0, 96), dirt(0, 0, 0), dirt(144, 2, 96)));
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        assertEquals(70, plan.chunkCount());
        assertEquals(List.of(0, 69, 69), schedule.entries().stream().map(entry -> entry.order().chunkIndex()).toList());
        assertEquals(2, schedule.entry(0).progress().chunkTotal());
        assertEquals(1, schedule.entry(2).progress().chunkTotal());
        assertEquals(VerificationScope.chunk(69), new VerificationScope(VerificationScope.Kind.CHUNK, 69));
    }

    @Test
    void guardsRejectOversizedVolumesLayoutsAndTargetsBeforeCompilation() {
        assertThrows(IllegalArgumentException.class, () -> SchematicCompiler.compile(
                new BuildVolume(0, 0, 0, 127, 128, 127), List.of()));
        assertThrows(IllegalArgumentException.class, () -> ChunkLayout.covering(
                new BuildVolume(0, 0, 0, 16_384, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new ChunkLayout(new ChunkCoordinate(0, 0), 0, 1));
        assertThrows(ArithmeticException.class,
                () -> new ChunkLayout(new ChunkCoordinate(Integer.MAX_VALUE, 0), 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> PlanLimits.requireTargetCount((long) PlanLimits.MAX_TARGET_BLOCKS + 1));
        PlanLimits.requireVolume(new BuildVolume(0, 0, 0, 199, 49, 199));
        PlanLimits.requireTargetCount(PlanLimits.MAX_TARGET_BLOCKS);
        assertEquals(1_024, new ChunkLayout(new ChunkCoordinate(0, 0), 32, 32).chunkCount());
    }

    private static TargetBlock dirt(int x, int y, int z) {
        return new TargetBlock(new BlockPosition(x, y, z), new BlockState("minecraft:dirt"));
    }
}
