package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointJsonCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsEveryCheckpointFieldAndEscapedText() {
        SupervisorCheckpoint checkpoint = sampleCheckpoint();

        String json = CheckpointJsonCodec.toJson(checkpoint);
        SupervisorCheckpoint restored = CheckpointJsonCodec.fromJson(json);

        assertEquals(checkpoint, restored);
        assertTrue(json.contains("\"plan_id\""));
        assertTrue(json.contains("line 1\\n\\\"line 2\\\""));
        assertFalse(json.contains("io.github"));
    }

    @Test
    void atomicFileStoreLoadsSavesReplacesAndClears() {
        Path path = temporaryDirectory.resolve("nested").resolve("checkpoint.json");
        JsonFileCheckpointStore store = new JsonFileCheckpointStore(path);

        assertTrue(store.load().isEmpty());
        store.save(sampleCheckpoint());
        assertEquals(sampleCheckpoint(), store.load().orElseThrow());

        SupervisorCheckpoint replacement = new SupervisorCheckpoint(
                SupervisorCheckpoint.CURRENT_VERSION,
                "replacement",
                SupervisorState.DONE,
                SupervisorState.VERIFYING,
                SupervisorState.VERIFYING,
                49,
                BuildPhase.VERIFY,
                VerificationStage.FINAL,
                RecoveryStage.NONE,
                2,
                "sha256:complete",
                MaterialQuantities.of(Material.DIRT, 10),
                MaterialQuantities.of(Material.DIRT, 10),
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                "",
                0,
                false,
                false,
                false,
                false,
                false,
                "",
                LayerBuildSchedule.ID,
                4949,
                -1
        );
        store.save(replacement);
        assertEquals(replacement, store.load().orElseThrow());

        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void rejectsMalformedMissingAndOutOfRangeData() {
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson("{"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CheckpointJsonCodec.fromJson("{\"version\":1}")
        );
        String invalidIndex = CheckpointJsonCodec.toJson(sampleCheckpoint())
                .replace("\"current_chunk_index\": 3", "\"current_chunk_index\": 50");
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(invalidIndex));
    }

    @Test
    void rejectsLegacyChunkCheckpointsWithoutReinterpretingTheirCursorOrDiscardingReconciliation()
            throws Exception {
        for (int legacyVersion : new int[] {1, 2}) {
            String legacy = CheckpointJsonCodec.toJson(sampleCheckpoint())
                    .replace("\"version\": " + SupervisorCheckpoint.CURRENT_VERSION, "\"version\": " + legacyVersion);
            Path path = temporaryDirectory.resolve("legacy-" + legacyVersion + ".json");
            java.nio.file.Files.writeString(path, legacy);
            JsonFileCheckpointStore store = new JsonFileCheckpointStore(path);
            RuntimeException exception = assertThrows(RuntimeException.class, store::load);
            String detail = exception.toString()
                    + (exception.getCause() == null ? "" : exception.getCause().toString());
            assertTrue(detail.contains("reset and restart"));
            assertTrue(detail.contains("Reconcile any unsettled depot transfer"));
            assertEquals(legacy, java.nio.file.Files.readString(path));
        }
    }

    @Test
    void rejectsUnknownScheduleAndNegativeCursor() {
        String json = CheckpointJsonCodec.toJson(sampleCheckpoint());
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointJsonCodec.fromJson(json.replace(LayerBuildSchedule.ID, "chunk-first")));
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointJsonCodec.fromJson(json.replace(
                        "\"schedule_cursor\": 4321", "\"schedule_cursor\": -1")));
    }

    @Test
    void rowMajorVersionOneScheduleStillDecodesForMappingOnLoad() {
        String json = CheckpointJsonCodec.toJson(sampleCheckpoint());
        SupervisorCheckpoint legacy = CheckpointJsonCodec.fromJson(json.replace(
                "\"" + LayerBuildSchedule.ID + "\"", "\"" + LayerBuildSchedule.ROW_MAJOR_ID + "\""));
        assertEquals(LayerBuildSchedule.ROW_MAJOR_ID, legacy.scheduleId());
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(json.replace(
                "\"" + LayerBuildSchedule.ID + "\"", "\"" + LayerBuildSchedule.ROW_MAJOR_DEFERRED_PLANTING_ID + "\"")));
    }

    @Test
    void legacyCountDefaultsToFortyNineAndDynamicCountBoundsEveryCursor() {
        String json = CheckpointJsonCodec.toJson(sampleCheckpoint());
        assertEquals(sampleCheckpoint(), CheckpointJsonCodec.fromJson(
                json.replace("  \"chunk_count\": 49,\n", "")));
        String dynamic = json.replace("\"chunk_count\": 49", "\"chunk_count\": 70")
                .replace("\"current_chunk_index\": 3", "\"current_chunk_index\": 69")
                .replace("\"repair_chunk_index\": 3", "\"repair_chunk_index\": 69");
        SupervisorCheckpoint restored = CheckpointJsonCodec.fromJson(dynamic);
        assertEquals(70, restored.chunkCount());
        assertEquals(69, restored.currentChunkIndex());
        assertEquals(restored, CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(restored)));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                dynamic.replace("\"chunk_count\": 70", "\"chunk_count\": 68")));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                dynamic.replace("\"current_chunk_index\": 69", "\"current_chunk_index\": 71")));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                dynamic.replace("\"repair_chunk_index\": 69", "\"repair_chunk_index\": 70")));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                dynamic.replace("\"chunk_count\": 70", "\"chunk_count\": 1025")));
    }

    @Test
    void checkedPiecesRoundTripAndCheckpointsWithoutThemLoadWithNone() {
        SupervisorCheckpoint base = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(sampleCheckpoint())
                .replace("\"repair_chunk_index\": 3", "\"repair_chunk_index\": -1"));
        java.util.BitSet bits = new java.util.BitSet();
        bits.set(4322, 4400);
        CompletedPieces pieces = CompletedPieces.of(bits);
        SupervisorCheckpoint checked = withCheckedPieces(base, pieces);

        String json = CheckpointJsonCodec.toJson(checked);
        assertTrue(json.contains("\"checked_pieces\": \"" + pieces.encode() + "\""));
        assertEquals(checked, CheckpointJsonCodec.fromJson(json));
        String earlier = CheckpointJsonCodec.toJson(base).replace("  \"checked_pieces\": \"\",\n", "");
        assertFalse(earlier.contains("checked_pieces"));
        assertEquals(base, CheckpointJsonCodec.fromJson(earlier));
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointJsonCodec.fromJson(json.replace(pieces.encode(), "%%%")));
        assertThrows(IllegalArgumentException.class, () -> withCheckedPieces(sampleCheckpoint(), pieces),
                "a chunk repair re-walks its chunk without skipping");
    }

    static SupervisorCheckpoint withCheckedPieces(SupervisorCheckpoint saved, CompletedPieces pieces) {
        return new SupervisorCheckpoint(saved.version(), saved.planId(), saved.state(), saved.resumeState(),
                saved.restockResumeState(), saved.currentChunkIndex(), saved.phase(), saved.verificationStage(),
                saved.recoveryStage(), saved.stableVerificationPasses(), saved.lastVerificationFingerprint(),
                saved.consumedMaterials(), saved.withdrawnMaterials(), saved.missingMaterials(),
                saved.restockRequirement(), saved.lastError(), saved.verificationRetries(), saved.repathAttempted(),
                saved.safeReturnAttempted(), saved.advisorAttempted(), saved.withdrawalInFlight(),
                saved.reconciliationRequired(), saved.reconciliationDetail(), saved.scheduleId(),
                saved.scheduleCursor(), saved.repairChunkIndex(), saved.chunkCount(), saved.plantingDeferred(),
                saved.lastAppliedPlannedCredit(), pieces);
    }

    private static SupervisorCheckpoint sampleCheckpoint() {
        return new SupervisorCheckpoint(
                SupervisorCheckpoint.CURRENT_VERSION,
                "farm-\"north\"",
                SupervisorState.PAUSED,
                SupervisorState.RESTOCKING,
                SupervisorState.BUILDING,
                3,
                BuildPhase.PLANT,
                VerificationStage.CHUNK,
                RecoveryStage.CHECK_MATERIALS,
                0,
                "",
                MaterialQuantities.of(Map.of(
                        Material.DIRT, 6400L,
                        Material.WHEAT_SEEDS, 12L
                )),
                MaterialQuantities.of(Material.DIRT, 6400),
                MaterialQuantities.of(Material.WHEAT_SEEDS, 4),
                MaterialQuantities.of(Map.of(
                        Material.WHEAT_SEEDS, 64L,
                        Material.FOOD, 1L
                )),
                "line 1\n\"line 2\"",
                1,
                true,
                false,
                false,
                false,
                true,
                "Cancelled depot transfer requires Reset.",
                LayerBuildSchedule.ID,
                4321,
                3
        );
    }
}
