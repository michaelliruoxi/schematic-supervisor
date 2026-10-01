package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchematicCompilerTest {
    @Test
    void compilesFixedRowMajorGridAndConvertsSpecialBlocks() {
        ChunkCoordinate origin = new ChunkCoordinate(-2, 4);
        List<TargetBlock> targets = List.of(
                target(-32, 0, 64, "minecraft:farmland", Map.of("moisture", "7")),
                target(-32, 1, 64, "minecraft:wheat", Map.of("age", "0")),
                target(-31, 2, 64, "minecraft:glowstone", Map.of()),
                target(-31, 3, 64, "minecraft:birch_planks", Map.of())
        );

        SchematicPlan plan = SchematicCompiler.compile7x7(origin, targets);

        assertEquals(49, plan.chunks().size());
        assertEquals(new ChunkCoordinate(-2, 4), plan.chunk(0).chunk());
        assertEquals(new ChunkCoordinate(4, 10), plan.chunk(48).chunk());
        ChunkPlan first = plan.chunk(0);
        assertEquals(4, first.expectedBlocks().size());
        assertEquals(List.of(new BlockPosition(-32, 0, 64)), first.tillTargets());
        assertEquals(List.of(new BlockPosition(-32, 1, 64)), first.plantTargets());
        assertEquals(
                new BlockState("minecraft:dirt"),
                first.ordinaryPlacements().getFirst().state()
        );
        assertEquals(
                MaterialQuantities.of(Map.of(
                        Material.DIRT, 1L,
                        Material.WHEAT_SEEDS, 1L,
                        Material.GLOWSTONE, 1L,
                        Material.BIRCH_PLANKS, 1L
                )),
                plan.plannedMaterials()
        );
        assertTrue(plan.chunk(1).expectedBlocks().isEmpty());
    }

    @Test
    void ordinaryWorkIsBottomUpAndTillAndPlantWorkAreTopDown() {
        List<TargetBlock> targets = new ArrayList<>();
        targets.add(target(0, 30, 0, "minecraft:farmland", Map.of()));
        targets.add(target(0, 1, 0, "minecraft:farmland", Map.of()));
        targets.add(target(1, 31, 0, "minecraft:wheat", Map.of()));
        targets.add(target(1, 2, 0, "minecraft:wheat", Map.of()));

        ChunkPlan chunk = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets).chunk(0);

        assertEquals(
                List.of(new BlockPosition(0, 1, 0), new BlockPosition(0, 30, 0)),
                chunk.ordinaryPlacements().stream().map(OrdinaryPlacement::position).toList()
        );
        assertEquals(
                List.of(new BlockPosition(0, 30, 0), new BlockPosition(0, 1, 0)),
                chunk.tillTargets()
        );
        assertEquals(
                List.of(new BlockPosition(1, 31, 0), new BlockPosition(1, 2, 0)),
                chunk.plantTargets()
        );
    }

    @Test
    void planIdentityIsStableAndIgnoresOnlyAllowedGrowthProperties() {
        ChunkCoordinate origin = new ChunkCoordinate(0, 0);
        SchematicPlan first = SchematicCompiler.compile7x7(origin, List.of(
                target(0, 0, 0, "minecraft:farmland", Map.of("moisture", "0"))
        ));
        SchematicPlan second = SchematicCompiler.compile7x7(origin, List.of(
                target(0, 0, 0, "minecraft:farmland", Map.of("moisture", "7"))
        ));
        SchematicPlan changed = SchematicCompiler.compile7x7(origin, List.of(
                target(0, 0, 0, "minecraft:dirt", Map.of())
        ));

        assertEquals(first.planId(), second.planId());
        assertNotEquals(first.planId(), changed.planId());
    }

    @Test
    void rejectsTargetsOutsideGridDuplicatesAirAndUnsupportedBlocks() {
        ChunkCoordinate origin = new ChunkCoordinate(0, 0);
        TargetBlock dirt = target(0, 0, 0, "minecraft:dirt", Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> SchematicCompiler.compile7x7(origin, List.of(
                        target(112, 0, 0, "minecraft:dirt", Map.of())
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SchematicCompiler.compile7x7(origin, List.of(dirt, dirt))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new TargetBlock(new BlockPosition(0, 0, 0), BlockState.AIR)
        );
        // Any other block must be placeable as it is: a block with properties to orient or set is not.
        assertThrows(
                IllegalArgumentException.class,
                () -> SchematicCompiler.compile7x7(origin, List.of(
                        target(0, 0, 0, "minecraft:oak_log", Map.of("axis", "y"))
                ))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SchematicCompiler.compile7x7(
                        origin,
                        new BuildVolume(0, 0, 0, 0, 0, 0),
                        List.of(target(1, 0, 0, "minecraft:dirt", Map.of()))
                )
        );
    }

    @Test
    void accountsForKnownNormalChunkQuantitiesExactly() {
        List<TargetBlock> targets = new ArrayList<>(10_106);
        for (int y = 0; y < 6_400; y++) {
            targets.add(target(0, y, 0, "minecraft:farmland", Map.of("moisture", "7")));
        }
        for (int y = 0; y < 3_200; y++) {
            targets.add(target(1, y, 0, "minecraft:wheat", Map.of("age", "0")));
        }
        for (int y = 0; y < 250; y++) {
            targets.add(target(2, y, 0, "minecraft:glowstone", Map.of()));
        }
        for (int y = 0; y < 256; y++) {
            targets.add(target(3, y, 0, "minecraft:birch_planks", Map.of()));
        }

        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0),
                new BuildVolume(0, 0, 0, 3, 6_399, 0), targets);

        assertEquals(
                MaterialQuantities.of(Map.of(
                        Material.DIRT, 6_400L,
                        Material.WHEAT_SEEDS, 3_200L,
                        Material.GLOWSTONE, 250L,
                        Material.BIRCH_PLANKS, 256L
                )),
                plan.plannedMaterials()
        );
        assertEquals(6_400, plan.chunk(0).tillTargets().size());
        assertEquals(3_200, plan.chunk(0).plantTargets().size());
    }

    private static TargetBlock target(
            int x,
            int y,
            int z,
            String blockId,
            Map<String, String> properties
    ) {
        return new TargetBlock(new BlockPosition(x, y, z), new BlockState(blockId, properties));
    }
}
