package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildCheck;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

final class BuildCheckSessionTest {
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final Executor DIRECT = Runnable::run;

    @Test
    void capturesAcrossTicksThenEvaluatesAndReportsProgress() {
        SchematicPlan plan = twoChunkPlan();
        FakeWorld world = FakeWorld.allReceived(plan);
        world.blocks.put(new BlockPosition(0, 0, 0), DIRT);
        BuildCheckSession session = session(plan);
        long cells = plan.buildVolume().blockCount();

        session.tick(world, 1_000, () -> 0L);
        assertEquals(BuildCheckSession.Status.CAPTURING, session.poll(0));
        assertEquals(1_000.0 / cells, session.progress(), 1e-9);
        for (int tick = 0; tick < 200 && session.status() == BuildCheckSession.Status.CAPTURING; tick++) {
            session.tick(world, 1_000, () -> 0L);
        }
        assertEquals(BuildCheckSession.Status.COMPLETE, session.poll(5_000_000_000L));
        assertEquals(1.0, session.progress());
        BuildCheck.Result result = session.result();
        assertEquals(49, result.chunksChecked());
        assertTrue(result.completePieces().contains(0));
        assertFalse(result.completePieces().contains(1));
        assertEquals(1, result.firstIncompletePiece());
        assertEquals(5_000, session.elapsedMillis(9_000_000_000L));
        assertEquals("", session.failure());
    }

    @Test
    void unreceivedChunksAreSkippedWithoutWaiting() {
        SchematicPlan plan = twoChunkPlan();
        FakeWorld world = FakeWorld.allReceived(plan);
        world.received.remove(new ChunkCoordinate(1, 0));
        world.blocks.put(new BlockPosition(0, 0, 0), DIRT);
        world.blocks.put(new BlockPosition(16, 0, 0), DIRT);
        BuildCheck.Result result = run(plan, world);
        assertEquals(48, result.chunksChecked());
        assertTrue(result.completePieces().contains(0));
        assertFalse(result.completePieces().contains(1));
        assertEquals(1, result.uncheckedActions());
        assertFalse(world.readChunks.contains(new ChunkCoordinate(1, 0)), "an unreceived chunk is never read");
    }

    @Test
    void aChunkThatUnloadsDuringCaptureIsLeftUnchecked() {
        SchematicPlan plan = twoChunkPlan();
        FakeWorld world = FakeWorld.allReceived(plan);
        world.blocks.put(new BlockPosition(0, 0, 0), DIRT);
        BuildCheckSession session = session(plan);
        session.tick(world, 10, () -> 0L);
        world.received.remove(new ChunkCoordinate(0, 0));
        for (int tick = 0; tick < 200 && session.status() == BuildCheckSession.Status.CAPTURING; tick++) {
            session.tick(world, 1_000, () -> 0L);
        }
        session.poll(0);
        BuildCheck.Result result = session.result();
        assertEquals(48, result.chunksChecked());
        assertFalse(result.completePieces().contains(0));
    }

    @Test
    void cellsWithUnconfirmedPredictionsAreUnknown() {
        SchematicPlan plan = twoChunkPlan();
        FakeWorld world = FakeWorld.allReceived(plan);
        world.blocks.put(new BlockPosition(0, 0, 0), DIRT);
        world.blocks.put(new BlockPosition(16, 0, 0), DIRT);
        world.predictions.add(new BlockPosition(16, 0, 0));
        BuildCheck.Result result = run(plan, world);
        assertTrue(result.completePieces().contains(0));
        assertFalse(result.completePieces().contains(1));
        assertEquals(1, result.uncheckedActions());
        assertEquals(0, result.placementsLeft());
    }

    @Test
    void aTimeSliceEndsTheTickEarly() {
        SchematicPlan plan = twoChunkPlan();
        BuildCheckSession session = session(plan);
        long[] now = {0};
        session.tick(FakeWorld.allReceived(plan), 1_000_000, () -> now[0] += 5_000_000L);
        assertEquals(BuildCheckSession.Status.CAPTURING, session.status());
        assertTrue(session.progress() < 0.1);
    }

    @Test
    void anEvaluationFailureIsReportedAndCanBeFallenBackFrom() {
        SchematicPlan plan = twoChunkPlan();
        BuildCheckSession session = new BuildCheckSession(plan, new LayerBuildSchedule(plan),
                new BuildCheck.Clearing() {
                    @Override public boolean replacesAtPlacement(OrdinaryPlacement placement, BlockState actual) {
                        throw new IllegalStateException("clearing rules unavailable");
                    }

                    @Override public boolean sweepsOpenCell(BlockState actual) {
                        throw new IllegalStateException("clearing rules unavailable");
                    }
                }, BuildCheck.Supports.NONE, DIRECT, 0L);
        FakeWorld world = FakeWorld.allReceived(plan);
        world.blocks.put(new BlockPosition(3, 0, 3), new BlockState("minecraft:cobblestone"));
        for (int tick = 0; tick < 200 && session.status() == BuildCheckSession.Status.CAPTURING; tick++) {
            session.tick(world, 1_000, () -> 0L);
        }
        assertEquals(BuildCheckSession.Status.FAILED, session.poll(0));
        assertEquals("clearing rules unavailable", session.failure());
        assertNull(session.result());
    }

    @Test
    void builderClearingMatchesTheExecutorsReplacementAndStemRules() {
        OrdinaryPlacement glowstone = new OrdinaryPlacement(new BlockPosition(0, 0, 0),
                new BlockState("minecraft:glowstone"), Material.GLOWSTONE);
        OrdinaryPlacement dirt = new OrdinaryPlacement(new BlockPosition(0, 0, 0), DIRT, Material.DIRT);
        BuildCheck.Clearing rules = BuildCheckSession.BUILDER_CLEARING;
        assertTrue(rules.replacesAtPlacement(dirt, new BlockState("minecraft:moss_block")));
        assertTrue(rules.replacesAtPlacement(glowstone, new BlockState("minecraft:jack_o_lantern")));
        assertFalse(rules.replacesAtPlacement(dirt, new BlockState("minecraft:jack_o_lantern")));
        assertFalse(rules.replacesAtPlacement(dirt, new BlockState("minecraft:cobblestone")));
        assertTrue(rules.sweepsOpenCell(new BlockState("minecraft:attached_melon_stem", Map.of("facing", "north"))));
        assertFalse(rules.sweepsOpenCell(new BlockState("minecraft:moss_block")));
    }

    private static BuildCheck.Result run(SchematicPlan plan, FakeWorld world) {
        BuildCheckSession session = session(plan);
        for (int tick = 0; tick < 200 && session.status() == BuildCheckSession.Status.CAPTURING; tick++) {
            session.tick(world, 1_000, () -> 0L);
        }
        assertEquals(BuildCheckSession.Status.COMPLETE, session.poll(0));
        return session.result();
    }

    private static BuildCheckSession session(SchematicPlan plan) {
        return new BuildCheckSession(plan, new LayerBuildSchedule(plan), BuildCheckSession.BUILDER_CLEARING,
                BuildCheck.Supports.NONE, DIRECT, 0L);
    }

    /** Two dirt blocks: piece 0 in chunk 0, piece 1 in chunk 1. */
    private static SchematicPlan twoChunkPlan() {
        return SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), DIRT),
                new TargetBlock(new BlockPosition(16, 0, 0), DIRT)));
    }

    private static final class FakeWorld implements BuildCheckSession.WorldReader {
        private final Set<ChunkCoordinate> received = new HashSet<>();
        private final Map<BlockPosition, BlockState> blocks = new HashMap<>();
        private final Set<BlockPosition> predictions = new HashSet<>();
        private final Set<ChunkCoordinate> readChunks = new HashSet<>();

        static FakeWorld allReceived(SchematicPlan plan) {
            FakeWorld world = new FakeWorld();
            for (int chunk = 0; chunk < plan.chunkCount(); chunk++) {
                world.received.add(plan.layout().chunk(chunk));
            }
            return world;
        }

        @Override
        public boolean chunkReceived(int chunkX, int chunkZ) {
            return received.contains(new ChunkCoordinate(chunkX, chunkZ));
        }

        @Override
        public BlockState blockState(int x, int y, int z) {
            readChunks.add(ChunkCoordinate.containing(new BlockPosition(x, y, z)));
            return blocks.getOrDefault(new BlockPosition(x, y, z), BlockState.AIR);
        }

        @Override
        public boolean predictionsPending() {
            return !predictions.isEmpty();
        }

        @Override
        public boolean predictionPending(int x, int y, int z) {
            return predictions.contains(new BlockPosition(x, y, z));
        }
    }
}
