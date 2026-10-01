package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.SchematicPlan;
import java.io.IOException;
import org.junit.jupiter.api.Test;

final class SchematicSourceScanTest {
    @Test
    void checkedInFarmReadsEverySourceCellAndAll49ChunksIncludingSeventhColumn() throws IOException {
        LitematicSourceFixture.Source source = LitematicSourceFixture.read();
        assertEquals(new BlockPosition(-112, 76, -112), source.signedSize());
        assertEquals(new BlockPosition(111, 0, 111), source.regionPosition());
        assertEquals(495_193, source.expectedNonAir());
        int[] reads = {0};
        int[] seventhColumnReads = {0};
        SchematicSourceScan scan = new SchematicSourceScan(source.width(), source.height(), source.depth(),
                identity(new BlockPosition(6_144, -63, -47_456)),
                new BuildVolume(6_144, -63, -47_456, 6_255, 12, -47_345), source.expectedNonAir(), (x, y, z) -> {
                    reads[0]++;
                    if (x >= 96) { seventhColumnReads[0]++; }
                    return source.get(x, y, z);
                });
        while (!scan.complete()) {
            int previous = reads[0];
            scan.tick(4_096);
            assertTrue(reads[0] - previous <= 4_096);
        }
        assertEquals(112 * 76 * 112, reads[0]);
        assertEquals(16 * 76 * 112, seventhColumnReads[0]);
        SchematicPlan plan = scan.result();
        assertEquals("sha256:38e55e5ccc1daf1471ef377bdde45333e9ff6e110f34666dca36129cf184e4a2", plan.planId());
        assertEquals(49, plan.chunks().stream().filter(chunk -> !chunk.expectedBlocks().isEmpty()).count());
        assertEquals(495_193, plan.chunks().stream().mapToLong(chunk -> chunk.expectedBlocks().size()).sum());
        assertEquals(313_600, plan.plannedMaterials().get(Material.DIRT));
        assertEquals(156_800, plan.plannedMaterials().get(Material.WHEAT_SEEDS));
        assertEquals(12_250, plan.plannedMaterials().get(Material.GLOWSTONE));
        assertEquals(12_543, plan.plannedMaterials().get(Material.BIRCH_PLANKS));
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        assertEquals(101, schedule.stageCount());
        assertEquals(49, schedule.entry(0).progress().chunkTotal());
        assertEquals(49 * 101, schedule.size());
    }

    @Test
    void missingSeventhColumnCannotBeAcceptedAsACompleteFarm() throws IOException {
        LitematicSourceFixture.Source source = LitematicSourceFixture.read();
        SchematicSourceScan scan = new SchematicSourceScan(source.width(), source.height(), source.depth(),
                identity(new BlockPosition(0, 0, 0)), new BuildVolume(0, 0, 0, 111, 75, 111),
                source.expectedNonAir(), (x, y, z) -> x < 96 ? source.get(x, y, z) : BlockState.AIR);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            while (!scan.complete()) { scan.tick(4_096); }
        });
        assertTrue(failure.getMessage().contains("metadata requires 495193"));
        assertFalse(scan.complete());
        assertThrows(IllegalStateException.class, scan::result);
    }

    @Test
    void rejectsTruncatedSourceDimensionsBeforeReadingOrCompiling() {
        int[] reads = {0};
        assertThrows(IllegalStateException.class, () -> new SchematicSourceScan(96, 76, 112,
                identity(new BlockPosition(0, 0, 0)), new BuildVolume(0, 0, 0, 111, 75, 111),
                495_192, (x, y, z) -> { reads[0]++; return BlockState.AIR; }));
        assertEquals(0, reads[0]);
    }

    @Test
    void mirroredAndRotatedSourceHasExactWorldCoordinatesAndSparseAirIsPreserved() {
        SchematicSourceScan.Transform transform = new SchematicSourceScan.Transform(
                new BlockPosition(-32, -63, -17), new BlockPosition(0, 0, -1),
                new BlockPosition(0, 1, 0), new BlockPosition(1, 0, 0));
        SchematicSourceScan scan = new SchematicSourceScan(112, 1, 112, transform,
                new BuildVolume(-32, -63, -128, 79, -63, -17), 1,
                (x, y, z) -> x == 111 && z == 0 ? new BlockState("minecraft:birch_planks") : BlockState.AIR);
        while (!scan.complete()) { scan.tick(100); }
        assertEquals(new BlockPosition(-32, -63, -128), scan.result().chunk(0).expectedBlocks().getFirst().position());
        assertEquals(1, scan.result().plannedMaterials().get(Material.BIRCH_PLANKS));
        assertEquals(1.0, scan.progress());
    }

    @Test
    void unsupportedSourceDataFailsWithoutPublishingAPlan() {
        SchematicSourceScan scan = new SchematicSourceScan(112, 1, 112,
                identity(new BlockPosition(0, 0, 0)), new BuildVolume(0, 0, 0, 111, 0, 111), -1,
                (x, y, z) -> new BlockState("minecraft:stone"));
        assertThrows(IllegalStateException.class, () -> scan.tick(1));
        assertFalse(scan.complete());
        assertThrows(IllegalArgumentException.class, () -> scan.tick(0));
    }

    @Test
    void rotatedSmallSourceRetainsExactBoundsAndAllAirCellsWithoutChunkAlignment() {
        SchematicSourceScan.Transform transform = new SchematicSourceScan.Transform(
                new BlockPosition(-1, 4, 17), new BlockPosition(0, 0, -1),
                new BlockPosition(0, 1, 0), new BlockPosition(-1, 0, 0));
        int[] reads = {0};
        SchematicSourceScan scan = new SchematicSourceScan(34, 2, 3, transform,
                new BuildVolume(-3, 4, -16, -1, 5, 17), 1, (x, y, z) -> {
                    reads[0]++;
                    return x == 33 && y == 1 && z == 2 ? new BlockState("minecraft:dirt") : BlockState.AIR;
                });
        while (!scan.complete()) { scan.tick(7); }
        assertEquals(204, reads[0]);
        assertEquals(3, scan.result().chunkCount());
        assertEquals(new BlockPosition(-3, 5, -16), scan.result().chunk(0).expectedBlocks().getFirst().position());
        assertEquals(1, scan.result().plannedMaterials().get(Material.DIRT));
    }

    @Test
    void volumeAndMetadataLimitsFailBeforeReadingSourceData() {
        int[] reads = {0};
        SchematicSourceScan.Reader source = (x, y, z) -> { reads[0]++; return BlockState.AIR; };
        assertThrows(IllegalArgumentException.class, () -> new SchematicSourceScan(128, 129, 128,
                identity(new BlockPosition(0, 0, 0)), new BuildVolume(0, 0, 0, 127, 128, 127), -1, source));
        assertThrows(IllegalArgumentException.class, () -> new SchematicSourceScan(100, 101, 100,
                identity(new BlockPosition(0, 0, 0)), new BuildVolume(0, 0, 0, 99, 100, 99), 1_000_001, source));
        assertThrows(IllegalArgumentException.class, () -> new SchematicSourceScan(16_385, 1, 1,
                identity(new BlockPosition(0, 0, 0)), new BuildVolume(0, 0, 0, 16_384, 0, 0), -1, source));
        assertEquals(0, reads[0]);
    }

    @Test
    void blocksOutsideTheBuiltInPaletteNeedTheirPlaceabilityChecked() {
        SchematicSourceScan.Reader stoneThenSand = (x, y, z) -> new BlockState(x == 0 ? "minecraft:stone" : "minecraft:sand");
        java.util.function.UnaryOperator<String> placeable =
                blockId -> blockId.equals("minecraft:sand") ? "falls when placed" : "";

        SchematicSourceScan stoneOnly = new SchematicSourceScan(1, 1, 1, identity(new BlockPosition(0, 0, 0)),
                new BuildVolume(0, 0, 0, 0, 0, 0), 1, stoneThenSand, placeable);
        stoneOnly.tick(16);
        assertEquals(1, stoneOnly.result().plannedMaterials().get(Material.block("minecraft:stone")));

        SchematicSourceScan withSand = new SchematicSourceScan(2, 1, 1, identity(new BlockPosition(0, 0, 0)),
                new BuildVolume(0, 0, 0, 1, 0, 0), 2, stoneThenSand, placeable);
        IllegalStateException sand = assertThrows(IllegalStateException.class, () -> withSand.tick(16));
        assertTrue(sand.getMessage().startsWith("Unsupported block minecraft:sand at "), sand.getMessage());
        assertTrue(sand.getMessage().contains("it falls when placed"), sand.getMessage());

        SchematicSourceScan builtInOnly = new SchematicSourceScan(1, 1, 1, identity(new BlockPosition(0, 0, 0)),
                new BuildVolume(0, 0, 0, 0, 0, 0), 1, stoneThenSand);
        assertTrue(assertThrows(IllegalStateException.class, () -> builtInOnly.tick(16)).getMessage()
                .contains("outside the built-in palette"));
    }

    private static SchematicSourceScan.Transform identity(BlockPosition origin) {
        return new SchematicSourceScan.Transform(origin, new BlockPosition(1, 0, 0),
                new BlockPosition(0, 1, 0), new BlockPosition(0, 0, 1));
    }
}
