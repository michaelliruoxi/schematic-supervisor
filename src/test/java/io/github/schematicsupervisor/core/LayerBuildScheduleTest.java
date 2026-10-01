package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayerBuildScheduleTest {
    @Test
    void crossesAll49ChunksAlongTheTourBeforeAdvancingLayerAndKeepsHangingLightDependencies() {
        LayerBuildSchedule schedule = new LayerBuildSchedule(farmPlan());
        List<String> expected = List.of("STRUCTURE:0", "STRUCTURE:3", "LIGHTING:2",
                "STRUCTURE:6", "LIGHTING:5", "TILL:3", "PLANT:4", "TILL:0", "PLANT:1");
        assertEquals(LayerBuildSchedule.ID, schedule.id());
        assertEquals(9, schedule.stageCount());
        assertEquals(9 * 49, schedule.size());
        assertEquals(expected, schedule.entries().stream()
                .filter(entry -> entry.progress().chunkOrdinal() == 1)
                .map(entry -> entry.progress().stage() + ":" + entry.progress().y()).toList());
        int[] tour = ChunkTour.order(7, 7);
        for (int stage = 0; stage < 9; stage++) {
            for (int step = 0; step < 49; step++) {
                LayerBuildSchedule.Entry entry = schedule.entry(stage * 49 + step);
                assertEquals(tour[step], entry.order().chunkIndex());
                assertEquals(stage + 1, entry.progress().ordinal());
                assertEquals(step + 1, entry.progress().chunkOrdinal());
                assertEquals(49, entry.progress().chunkTotal());
                assertTrue(positions(entry.order()).stream().allMatch(position ->
                        position.y() == entry.progress().y() && entry.order().chunk().contains(position)));
            }
        }
    }

    @Test
    void rowMajorVersionOneSchedulesRemainReadableWithTheSameStagesAndWork() {
        SchematicPlan plan = farmPlan();
        LayerBuildSchedule tour = new LayerBuildSchedule(plan);
        for (boolean deferred : new boolean[]{false, true}) {
            for (boolean structureFirst : new boolean[]{false, true}) {
                SchematicPlan variant = plan.withPlantingDeferred(deferred).withGlowstoneAfterStructure(structureFirst);
                String current = LayerBuildSchedule.id(deferred, structureFirst);
                String legacy = current.replace("layers-v2", "layers-v1");
                assertTrue(current.startsWith("layers-v2"));
                assertEquals(current, new LayerBuildSchedule(variant).id());
                assertEquals(current, LayerBuildSchedule.forId(plan, current).id());
                LayerBuildSchedule rowMajor = LayerBuildSchedule.forId(plan, legacy);
                assertEquals(legacy, rowMajor.id());
                assertTrue(LayerBuildSchedule.rowMajor(legacy));
                assertFalse(LayerBuildSchedule.rowMajor(current));
                for (String id : List.of(current, legacy)) {
                    assertEquals(deferred, LayerBuildSchedule.plantingDeferred(id));
                    assertEquals(structureFirst, LayerBuildSchedule.glowstoneAfterStructure(id));
                }
                LayerBuildSchedule reordered = new LayerBuildSchedule(variant);
                assertEquals(reordered.size(), rowMajor.size());
                assertEquals(reordered.stageCount(), rowMajor.stageCount());
                for (int index = 0; index < rowMajor.size(); index++) {
                    LayerBuildSchedule.Entry entry = rowMajor.entry(index);
                    assertEquals(entry.progress().chunkOrdinal() - 1, entry.order().chunkIndex());
                    assertEquals(entry.progress().ordinal(), reordered.entry(index).progress().ordinal());
                    assertEquals(entry.progress().stage(), reordered.entry(index).progress().stage());
                }
                assertEquals(new HashSet<>(reordered.entries().stream().map(LayerBuildSchedule.Entry::order).toList()),
                        new HashSet<>(rowMajor.entries().stream().map(LayerBuildSchedule.Entry::order).toList()));
            }
        }
        assertEquals(9 * 49, tour.size());
        for (String unknown : List.of("layers-v3", "chunk-first", "")) {
            assertThrows(IllegalArgumentException.class, () -> LayerBuildSchedule.rowMajor(unknown));
            assertThrows(IllegalArgumentException.class, () -> LayerBuildSchedule.plantingDeferred(unknown));
            assertThrows(IllegalArgumentException.class, () -> LayerBuildSchedule.forId(plan, unknown));
        }
    }

    @Test
    void preservesEveryTargetExactlyOnceAndIsIndependentOfInputCollectionOrder() {
        SchematicPlan plan = farmPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        List<TargetBlock> reversed = new ArrayList<>(plan.chunks().stream()
                .flatMap(chunk -> chunk.expectedBlocks().stream()).toList());
        Collections.reverse(reversed);
        LayerBuildSchedule recompiled = new LayerBuildSchedule(SchematicCompiler.compile7x7(
                plan.originChunk(), reversed));
        assertEquals(schedule.entries(), recompiled.entries());
        for (int index = 0; index < 49; index++) {
            int chunkIndex = index;
            List<WorkOrder> orders = schedule.entries().stream().map(LayerBuildSchedule.Entry::order)
                    .filter(order -> order.chunkIndex() == chunkIndex).toList();
            assertEquals(plan.chunk(index).ordinaryPlacements().size(), orders.stream()
                    .filter(WorkOrder.OrdinaryBlocks.class::isInstance)
                    .mapToInt(order -> ((WorkOrder.OrdinaryBlocks) order).placements().size()).sum());
            assertEquals(plan.chunk(index).tillTargets().size(), orders.stream()
                    .filter(WorkOrder.Till.class::isInstance)
                    .mapToInt(order -> ((WorkOrder.Till) order).targets().size()).sum());
            assertEquals(plan.chunk(index).plantTargets().size(), orders.stream()
                    .filter(WorkOrder.Plant.class::isInstance)
                    .mapToInt(order -> ((WorkOrder.Plant) order).targets().size()).sum());
        }
    }

    private static List<BlockPosition> positions(WorkOrder order) {
        return switch (order) {
            case WorkOrder.OrdinaryBlocks ordinary -> ordinary.placements().stream()
                    .map(OrdinaryPlacement::position).toList();
            case WorkOrder.Till till -> till.targets();
            case WorkOrder.Plant plant -> plant.targets();
        };
    }

    static SchematicPlan farmPlan() {
        List<TargetBlock> targets = new ArrayList<>();
        for (int z = 0; z < 7; z++) {
            for (int x = 0; x < 7; x++) {
                for (int floorY : new int[] {0, 3}) {
                    targets.add(target(x, floorY, z, "farmland"));
                    targets.add(target(x, floorY + 1, z, "wheat"));
                    targets.add(target(x, floorY + 2, z, "glowstone"));
                }
                targets.add(target(x, 6, z, "birch_planks"));
            }
        }
        return SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets);
    }

    private static TargetBlock target(int chunkX, int y, int chunkZ, String block) {
        return new TargetBlock(new BlockPosition(chunkX * 16, y, chunkZ * 16),
                new BlockState("minecraft:" + block));
    }
}
