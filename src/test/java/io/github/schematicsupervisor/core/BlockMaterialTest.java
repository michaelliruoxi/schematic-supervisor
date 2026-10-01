package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.SupervisorFakes.Harness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockMaterialTest {
    private static final Material STONE = Material.block("minecraft:stone");

    @Test
    void builtInsKeepTheirNamesAndOrder() {
        assertEquals(List.of("dirt", "wheat_seeds", "glowstone", "birch_planks", "hoe", "food"),
                Material.builtIns().stream().map(Material::jsonName).toList());
        assertEquals("DIRT", Material.DIRT.name());
        assertEquals("DIRT", Material.DIRT.toString());
        for (Material material : Material.builtIns()) {
            assertSame(material, Material.fromJsonName(material.jsonName()));
            assertSame(material, Material.fromJsonName(material.name()));
            assertTrue(material.builtIn());
        }
        assertEquals(Material.Kind.TOOL, Material.HOE.kind());
        assertFalse(Material.WHEAT_SEEDS.placedAsBlock());
    }

    @Test
    void anyOtherBlockIsABlockMaterialNamedByItsId() {
        assertSame(STONE, Material.block("minecraft:stone"));
        assertSame(STONE, Material.block("  Minecraft:Stone "));
        assertSame(STONE, Material.fromJsonName("minecraft:stone"));
        assertEquals("minecraft:stone", STONE.jsonName());
        assertEquals("minecraft:stone", STONE.itemId());
        assertTrue(STONE.placedAsBlock());
        assertFalse(STONE.builtIn());
        // Blocks the built-ins place resolve to the built-ins.
        assertSame(Material.DIRT, Material.block("minecraft:dirt"));
        assertSame(Material.BIRCH_PLANKS, Material.block("minecraft:birch_planks"));
        for (String invalid : List.of("stone", "minecraft:", "minecraft:air", "minecraft:wheat_seeds", "Stone Block")) {
            assertThrows(IllegalArgumentException.class, () -> Material.block(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> Material.fromJsonName("granite"));
    }

    @Test
    void quantitiesListBuiltInsFirstThenBlocksById() {
        MaterialQuantities quantities = MaterialQuantities.of(Map.of(
                Material.block("minecraft:white_wool"), 3L, STONE, 2L, Material.GLOWSTONE, 1L, Material.DIRT, 4L));
        assertEquals(List.of("dirt", "glowstone", "minecraft:stone", "minecraft:white_wool"),
                List.copyOf(quantities.asJsonMap().keySet()));
        assertEquals("{dirt=4, glowstone=1, minecraft:stone=2, minecraft:white_wool=3}", quantities.toString());
        assertEquals(MaterialQuantities.of(STONE, 2), quantities.minimum(MaterialQuantities.of(STONE, 5)));
        assertEquals(MaterialQuantities.of(STONE, 3), MaterialQuantities.of(STONE, 5).shortageFrom(quantities));
        assertEquals(quantities, MaterialQuantities.fromJsonMap(quantities.asJsonMap()));
    }

    @Test
    void schematicsMayPlaceAnyBlockWithoutProperties() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:stone")),
                new TargetBlock(new BlockPosition(1, 0, 0), new BlockState("minecraft:stone")),
                new TargetBlock(new BlockPosition(2, 0, 0), new BlockState("minecraft:white_wool"))));
        assertEquals(MaterialQuantities.of(Map.of(STONE, 2L, Material.block("minecraft:white_wool"), 1L)),
                plan.plannedMaterials());
        OrdinaryPlacement placement = plan.chunk(0).ordinaryPlacements().getFirst();
        assertEquals(STONE, placement.material());
        assertEquals(new BlockState("minecraft:stone"), placement.state());
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        assertEquals("STRUCTURE", schedule.entry(0).progress().stage());
        assertThrows(IllegalArgumentException.class, () -> new OrdinaryPlacement(new BlockPosition(0, 0, 0),
                new BlockState("minecraft:stone"), Material.WHEAT_SEEDS));
    }

    @Test
    void checkpointsCarryBlockMaterialsAndOlderNamesStillRead() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(STONE, 3));
        SchematicSupervisor supervisor = new SchematicSupervisor(stonePlan(), config(), harness.ports());
        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.pause();
        SupervisorCheckpoint saved = supervisor.checkpoint();
        assertEquals(3, saved.consumedMaterials().get(STONE));
        String json = CheckpointJsonCodec.toJson(saved);
        assertTrue(json.contains("\"minecraft:stone\": 3"), json);
        assertEquals(saved, CheckpointJsonCodec.fromJson(json));
        // A checkpoint written while materials were an enum reads the same.
        assertEquals(MaterialQuantities.of(Material.DIRT, 5), CheckpointJsonCodec.fromJson(json
                .replace("\"consumed_materials\": {\"minecraft:stone\": 3}", "\"consumed_materials\": {\"dirt\": 5}"))
                .consumedMaterials());
    }

    @Test
    void aStonePlanRestocksFromDepotsAndFinishes() {
        Harness harness = new Harness();
        harness.depots.put(new DepotId("stone-chest"), MaterialQuantities.of(STONE, 64));
        SchematicPlan plan = stonePlan();
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, config(), harness.ports());
        supervisor.start();
        for (int tick = 0; tick < 400 && supervisor.status().state() != SupervisorState.DONE; tick++) {
            // The fake executor asks for the slice's stone before it places anything.
            if (harness.execution.started.size() == 1 && supervisor.status().state() == SupervisorState.BUILDING
                    && harness.inventory.snapshot().get(STONE) == 0) {
                harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(0, MaterialQuantities.of(STONE, 3)));
            }
            supervisor.tick();
        }
        assertEquals(SupervisorState.DONE, supervisor.status().state(), supervisor.status().lastError());
        assertEquals(MaterialQuantities.of(STONE, 3), supervisor.checkpoint().consumedMaterials());
        assertEquals(MaterialQuantities.of(STONE, 3), supervisor.checkpoint().withdrawnMaterials());
    }

    private static SchematicPlan stonePlan() {
        List<TargetBlock> targets = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            targets.add(new TargetBlock(new BlockPosition(x, 0, 0), new BlockState("minecraft:stone")));
        }
        return SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets);
    }

    private static SupervisorConfig config() {
        return new SupervisorConfig(Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(15), 0, 0);
    }
}
