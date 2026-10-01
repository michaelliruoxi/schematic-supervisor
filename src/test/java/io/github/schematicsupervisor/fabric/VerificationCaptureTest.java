package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SchematicVerification;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.VerificationScope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class VerificationCaptureTest {
    @Test
    void unloadedDataIsNeverReadOrSubmittedAsMissingBlocks() {
        SchematicPlan plan = dirtPlan();
        MinecraftVerificationPort.ScanSession capture =
                new MinecraftVerificationPort.ScanSession(plan, VerificationScope.chunk(0));
        MutableObservation observation = new MutableObservation();

        capture.capture(observation, 20_000, 0);

        assertFalse(capture.complete());
        assertTrue(observation.reads.isEmpty());
        assertTrue(capture.waitDetail().contains("received chunk data"));
        assertThrows(IllegalStateException.class, () -> capture.snapshot(List.of()));

        observation.loaded.add(new ChunkCoordinate(0, 0));
        capture.capture(observation, 20_000, TimeUnit.SECONDS.toNanos(1));
        assertTrue(capture.complete());
        assertTrue(SchematicVerification.verify(plan, VerificationScope.chunk(0), capture.snapshot(List.of()))
                .clean());
    }

    @Test
    void unloadingDuringCaptureRestartsTheEntireChunkWhenDataReturns() {
        SchematicPlan plan = dirtPlan();
        MinecraftVerificationPort.ScanSession capture =
                new MinecraftVerificationPort.ScanSession(plan, VerificationScope.chunk(0));
        MutableObservation observation = new MutableObservation();
        ChunkCoordinate chunk = new ChunkCoordinate(0, 0);
        observation.loaded.add(chunk);
        capture.capture(observation, 1, 0);
        assertFalse(capture.complete());
        observation.loaded.clear();
        capture.capture(observation, 20_000, 1);
        assertEquals(1, observation.reads.size());

        observation.loaded.add(chunk);
        observation.dirt = new BlockState("minecraft:glowstone");
        capture.capture(observation, 20_000, 2);

        assertTrue(capture.complete());
        assertEquals(2, observation.reads.stream().filter(new BlockPosition(0, 0, 0)::equals).count());
        assertEquals(observation.dirt, capture.snapshot(List.of()).blockState(new BlockPosition(0, 0, 0)));
    }

    @Test
    void absentChunkTimesOutWithoutProducingVerificationResult() {
        MinecraftVerificationPort.ScanSession capture =
                new MinecraftVerificationPort.ScanSession(dirtPlan(), VerificationScope.chunk(0));
        MutableObservation observation = new MutableObservation();
        capture.capture(observation, 20_000, 0);
        capture.capture(observation, 20_000, TimeUnit.SECONDS.toNanos(14));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> capture.capture(observation, 20_000, TimeUnit.SECONDS.toNanos(15)));

        assertTrue(failure.getMessage().contains("timed out after 15 seconds"));
        assertTrue(observation.reads.isEmpty());
        assertFalse(capture.complete());
    }

    @Test
    void activeApproachAllowsTravelTimeButStillRequiresReceivedData() {
        MinecraftVerificationPort.ScanSession capture =
                new MinecraftVerificationPort.ScanSession(dirtPlan(), VerificationScope.chunk(0));
        MutableObservation observation = new MutableObservation();
        capture.capture(observation, 20_000, 0);
        capture.capture(observation, 20_000, TimeUnit.SECONDS.toNanos(30), true);
        assertEquals(new ChunkCoordinate(0, 0), capture.waitingChunk());
        assertTrue(observation.reads.isEmpty());
        assertFalse(capture.complete());

        capture.capture(observation, 20_000, TimeUnit.SECONDS.toNanos(44));
        assertThrows(IllegalStateException.class,
                () -> capture.capture(observation, 20_000, TimeUnit.SECONDS.toNanos(45)));
    }

    @Test
    void fullScopeRetainsEveryChunkAndResumesAsEachChunkIsReceived() {
        SchematicPlan plan = dirtPlan();
        MinecraftVerificationPort.ScanSession capture =
                new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = new MutableObservation();
        for (int index = 0; index < SchematicPlan.CHUNK_COUNT; index++) {
            assertFalse(capture.complete());
            observation.loaded.clear();
            observation.loaded.add(plan.chunk(index).chunk());
            capture.capture(observation, 20_000, index);
        }

        assertTrue(capture.complete());
        BlockObservation snapshot = capture.snapshot(List.of());
        assertTrue(plan.chunks().stream().allMatch(chunk -> snapshot.isChunkLoaded(chunk.chunk())));
        assertTrue(SchematicVerification.verify(plan, VerificationScope.fullPlan(), snapshot).clean());
    }

    @Test
    void fullRectangularCaptureWaitsForEachOfSeventyRealChunksAndKeepsExactCoverage() {
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 159, 0, 111),
                List.of(new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"))));
        MinecraftVerificationPort.ScanSession capture =
                new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = new MutableObservation();
        for (int index = 0; index < plan.chunkCount(); index++) {
            assertFalse(capture.complete());
            observation.loaded.clear();
            observation.loaded.add(plan.chunk(index).chunk());
            capture.capture(observation, 20_000, index);
        }
        assertTrue(capture.complete());
        assertEquals(plan.buildVolume().blockCount(), observation.reads.size());
        assertEquals(observation.reads.size(), new HashSet<>(observation.reads).size());
        assertTrue(SchematicVerification.verify(plan, VerificationScope.fullPlan(), capture.snapshot(List.of())).clean());
    }

    @Test
    void predictedCorrectBlockWaitsWithoutReadingOrNavigatingAndResumesWithinTheCaptureBudget() {
        SchematicPlan plan = tinyDirtPlan(1);
        var capture = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        observation.pending.add(new BlockPosition(0, 0, 0));

        capture.capture(observation, 1, 0);
        assertFalse(capture.complete());
        assertTrue(observation.reads.isEmpty());
        assertNull(capture.waitingChunk());
        assertTrue(capture.waitDetail().contains("server acknowledgement"));
        assertThrows(IllegalStateException.class, () -> capture.snapshot(List.of()));

        observation.pending.clear();
        capture.capture(observation, 1, TimeUnit.SECONDS.toNanos(14));
        assertEquals(List.of(new BlockPosition(0, 0, 0)), observation.reads);
        assertFalse(capture.complete(), "Settling a prediction must not exceed the per-tick block budget");
        assertTrue(capture.waitDetail().isEmpty());
        capture.capture(observation, 1, TimeUnit.SECONDS.toNanos(14));
        assertTrue(capture.complete());
        assertTrue(SchematicVerification.verify(plan, VerificationScope.fullPlan(), capture.snapshot(List.of())).clean());
    }

    @Test
    void rejectedPlacementRecordsTheServerCorrectionInsteadOfTheEarlierPredictedBlock() {
        SchematicPlan plan = tinyDirtPlan(0);
        var capture = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        observation.pending.add(new BlockPosition(0, 0, 0));
        capture.capture(observation, 10, 0);

        observation.dirt = BlockState.AIR;
        observation.pending.clear();
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(1));

        var result = SchematicVerification.verify(plan, VerificationScope.fullPlan(), capture.snapshot(List.of()));
        assertFalse(result.clean());
        assertEquals(1, result.missingCount());
        assertEquals(1, observation.reads.size());
    }

    @Test
    void rejectedRemovalAtSourceAirCannotHideAnExtraneousBlock() {
        SchematicPlan plan = tinyDirtPlan(1);
        var capture = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        BlockPosition sourceAir = new BlockPosition(1, 0, 0);
        observation.pending.add(sourceAir);
        capture.capture(observation, 10, 0);
        assertEquals(List.of(new BlockPosition(0, 0, 0)), observation.reads);
        assertNull(capture.waitingChunk());

        observation.states.put(sourceAir, new BlockState("minecraft:dirt"));
        observation.pending.clear();
        capture.capture(observation, 10, 1);

        var result = SchematicVerification.verify(plan, VerificationScope.fullPlan(), capture.snapshot(List.of()));
        assertFalse(result.clean());
        assertEquals(1, result.extraneousCount());
    }

    @Test
    void predictionTimesOutWithoutASnapshotAndApproachCannotExtendItsDeadline() {
        var capture = new MinecraftVerificationPort.ScanSession(tinyDirtPlan(0), VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        observation.pending.add(new BlockPosition(0, 0, 0));
        capture.capture(observation, 10, 0, true);
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(14), true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(15), true));
        assertTrue(failure.getMessage().contains("server acknowledgement"));
        assertTrue(failure.getMessage().contains("timed out after 15 seconds"));
        assertTrue(observation.reads.isEmpty());
        assertFalse(capture.complete());
        assertNull(capture.waitingChunk());
        assertThrows(IllegalStateException.class, () -> capture.snapshot(List.of()));
    }

    @Test
    void consecutivePendingPositionsEachRetainTheirOwnWaitAndAreNeverSkipped() {
        SchematicPlan plan = tinyDirtPlan(1);
        var capture = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        BlockPosition first = new BlockPosition(0, 0, 0);
        BlockPosition second = new BlockPosition(1, 0, 0);
        observation.pending.add(first);
        observation.pending.add(second);
        capture.capture(observation, 10, 0);

        observation.pending.remove(first);
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(14));
        assertEquals(List.of(first), observation.reads);
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(28));
        assertEquals(List.of(first), observation.reads);
        assertFalse(capture.complete());

        observation.pending.remove(second);
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(28));
        assertEquals(List.of(first, second), observation.reads);
        assertTrue(capture.complete());
    }

    @Test
    void unloadingWhilePredictionIsPendingRestartsTheReceivedChunkCapture() {
        SchematicPlan plan = tinyDirtPlan(1);
        var capture = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        BlockPosition first = new BlockPosition(0, 0, 0);
        BlockPosition second = new BlockPosition(1, 0, 0);
        observation.pending.add(second);
        capture.capture(observation, 10, 0);
        assertEquals(List.of(first), observation.reads);

        observation.loaded.clear();
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(1));
        assertEquals(new ChunkCoordinate(0, 0), capture.waitingChunk());
        assertTrue(capture.waitDetail().contains("received chunk data"));
        observation.loaded.add(new ChunkCoordinate(0, 0));
        observation.dirt = new BlockState("minecraft:glowstone");
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(15));
        assertEquals(List.of(first, first), observation.reads);
        assertNull(capture.waitingChunk());
        assertFalse(capture.complete());

        observation.pending.clear();
        capture.capture(observation, 10, TimeUnit.SECONDS.toNanos(16));
        var result = SchematicVerification.verify(plan, VerificationScope.fullPlan(), capture.snapshot(List.of()));
        assertEquals(1, result.incorrectCount());
    }

    @Test
    void twoFullPassesCannotPublishTheSameUnacknowledgedLocalPrediction() {
        SchematicPlan plan = tinyDirtPlan(0);
        MutableObservation observation = receivedObservation();
        observation.pending.add(new BlockPosition(0, 0, 0));
        var first = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        var second = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        for (var pass : List.of(first, second)) {
            pass.capture(observation, 10, 0);
            assertFalse(pass.complete());
            assertThrows(IllegalStateException.class, () -> pass.snapshot(List.of()));
        }
        assertTrue(observation.reads.isEmpty());

        observation.pending.clear();
        first.capture(observation, 10, 1);
        second.capture(observation, 10, 2);
        var firstResult = SchematicVerification.verify(plan, VerificationScope.fullPlan(), first.snapshot(List.of()));
        var secondResult = SchematicVerification.verify(plan, VerificationScope.fullPlan(), second.snapshot(List.of()));
        assertTrue(firstResult.clean());
        assertTrue(secondResult.clean());
        assertEquals(firstResult.normalizedFingerprint(), secondResult.normalizedFingerprint());
    }

    @Test
    void deferredWheatAirStillWaitsForAcknowledgementBeforeItCanPass() {
        BlockPosition soil = new BlockPosition(0, 0, 0);
        BlockPosition crop = new BlockPosition(0, 1, 0);
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 0, 1, 0), List.of(
                new TargetBlock(soil, new BlockState("minecraft:farmland")),
                new TargetBlock(crop, new BlockState("minecraft:wheat")))).withPlantingDeferred(true);
        var capture = new MinecraftVerificationPort.ScanSession(plan, VerificationScope.fullPlan());
        MutableObservation observation = receivedObservation();
        observation.states.put(soil, new BlockState("minecraft:farmland", Map.of("moisture", "7")));
        observation.pending.add(crop);
        capture.capture(observation, 10, 0);
        assertEquals(List.of(soil), observation.reads);
        assertFalse(capture.complete());
        assertNull(capture.waitingChunk());

        observation.pending.clear();
        capture.capture(observation, 10, 1);
        assertTrue(SchematicVerification.verify(plan, VerificationScope.fullPlan(), capture.snapshot(List.of())).clean());
    }

    private static SchematicPlan tinyDirtPlan(int maximumX) {
        return SchematicCompiler.compile(new BuildVolume(0, 0, 0, maximumX, 0, 0), List.of(new TargetBlock(
                new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"))));
    }

    private static MutableObservation receivedObservation() {
        MutableObservation observation = new MutableObservation();
        observation.loaded.add(new ChunkCoordinate(0, 0));
        return observation;
    }

    private static SchematicPlan dirtPlan() {
        return SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(new TargetBlock(
                new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"))));
    }

    private static final class MutableObservation implements MinecraftVerificationPort.CaptureObservation {
        private final Set<ChunkCoordinate> loaded = new HashSet<>();
        private final Set<BlockPosition> pending = new HashSet<>();
        private final Map<BlockPosition, BlockState> states = new HashMap<>();
        private final List<BlockPosition> reads = new ArrayList<>();
        private BlockState dirt = new BlockState("minecraft:dirt");

        @Override
        public boolean isChunkLoaded(ChunkCoordinate chunk) {
            return loaded.contains(chunk);
        }

        @Override
        public BlockState blockState(BlockPosition position) {
            assertTrue(isChunkLoaded(ChunkCoordinate.containing(position)), "read from unreceived chunk");
            assertFalse(predictionPending(position), "read from an unacknowledged prediction");
            reads.add(position);
            return states.getOrDefault(position,
                    position.equals(new BlockPosition(0, 0, 0)) ? dirt : BlockState.AIR);
        }

        @Override
        public boolean predictionPending(BlockPosition position) {
            return pending.contains(position);
        }

        @Override
        public List<BlockPosition> temporaryScaffolding(VerificationScope ignored) {
            return List.of();
        }
    }
}
