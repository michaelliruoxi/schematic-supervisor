package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LightingRestockReserveTest {
    @Test
    void includesUpcomingChunksButExcludesTheCurrentSlice() {
        LayerBuildSchedule schedule = schedule(3, 10);
        int cursor = firstLighting(schedule);
        assertEquals(20, LightingRestockReserve.additionalPlannedDemand(schedule, cursor, schedule.entry(cursor).order()));
        assertEquals(10, LightingRestockReserve.additionalPlannedDemand(schedule, cursor + 1, schedule.entry(cursor + 1).order()));
    }

    @Test
    void lastChunkCannotBorrowFromTheNextLightingLayer() {
        LayerBuildSchedule schedule = schedule(3, 10);
        int cursor = firstLighting(schedule) + 2;
        assertEquals("LIGHTING", schedule.entry(cursor + 1).progress().stage());
        assertEquals(5, schedule.entry(cursor + 1).progress().y());
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, cursor, schedule.entry(cursor).order()));
    }

    @Test
    void checkedPiecesNeedNoGlowstoneButDoNotEndTheLookahead() {
        LayerBuildSchedule schedule = schedule(3, 10);
        int cursor = firstLighting(schedule);
        java.util.BitSet finished = new java.util.BitSet();
        finished.set(cursor + 1);
        assertEquals(10, LightingRestockReserve.additionalPlannedDemand(schedule, cursor,
                schedule.entry(cursor).order(), CompletedPieces.of(finished)));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, cursor,
                schedule.entry(cursor).order(), null));
    }

    @Test
    void lookaheadIsCappedAtOneStack() {
        LayerBuildSchedule schedule = schedule(49, 10);
        int cursor = firstLighting(schedule);
        assertEquals(64, LightingRestockReserve.additionalPlannedDemand(schedule, cursor, schedule.entry(cursor).order()));
    }

    @Test
    void staleCursorWrongOrderAndUnavailableContextCannotReserveFutureWork() {
        LayerBuildSchedule schedule = schedule(3, 10);
        int cursor = firstLighting(schedule);
        WorkOrder order = schedule.entry(cursor).order();
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, cursor + 1, order));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, -1, order));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, schedule.size(), order));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(null, cursor, order));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, cursor, null));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, 0, schedule.entry(0).order()));
    }

    @Test
    void changedPlacementListIsNotTheAuthoritativeCurrentOrder() {
        LayerBuildSchedule schedule = schedule(3, 10);
        int cursor = firstLighting(schedule);
        WorkOrder.OrdinaryBlocks current = (WorkOrder.OrdinaryBlocks) schedule.entry(cursor).order();
        WorkOrder altered = new WorkOrder.OrdinaryBlocks(current.chunkIndex(), current.chunk(),
                current.placements().subList(0, 1));
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, cursor, altered));
    }

    @Test
    void propertyBearingGlowstoneCannotAuthorizeAnOptionalReserve() {
        List<TargetBlock> targets = new ArrayList<>();
        targets.add(new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt")));
        targets.add(new TargetBlock(new BlockPosition(0, 2, 0), new BlockState("minecraft:glowstone", Map.of("custom", "value"))));
        targets.add(new TargetBlock(new BlockPosition(16, 2, 0), new BlockState("minecraft:glowstone")));
        LayerBuildSchedule schedule = new LayerBuildSchedule(SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets));
        int cursor = firstLighting(schedule);
        assertEquals(0, LightingRestockReserve.additionalPlannedDemand(schedule, cursor, schedule.entry(cursor).order()));
    }

    private static int firstLighting(LayerBuildSchedule schedule) {
        for (int index = 0; index < schedule.size(); index++) {
            if (schedule.entry(index).progress().stage().equals("LIGHTING")) { return index; }
        }
        throw new AssertionError("test plan needs a lighting stage");
    }

    private static LayerBuildSchedule schedule(int lightingChunks, int lightsPerChunk) {
        List<TargetBlock> targets = new ArrayList<>();
        for (int chunk = 0; chunk < 49; chunk++) {
            int x = chunk % 7 * 16;
            int z = chunk / 7 * 16;
            targets.add(new TargetBlock(new BlockPosition(x, 0, z), new BlockState("minecraft:dirt")));
            if (chunk < lightingChunks) {
                for (int cell = 0; cell < lightsPerChunk; cell++) {
                    for (int y : new int[] {2, 5}) {
                        targets.add(new TargetBlock(new BlockPosition(x + cell, y, z), new BlockState("minecraft:glowstone")));
                    }
                }
            }
        }
        return new LayerBuildSchedule(SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets));
    }
}
