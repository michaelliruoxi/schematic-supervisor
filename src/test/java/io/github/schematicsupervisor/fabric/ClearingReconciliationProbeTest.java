package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.ChunkLayout;
import io.github.schematicsupervisor.core.ChunkPlan;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.TargetBlock;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClearingReconciliationProbeTest {
    private static final String ID = "b4b38d93-fd0a-4e22-88b7-7b941452fa00";
    private static final String HASH = "a".repeat(64);
    private static final String PLAN = "sha256:" + "b".repeat(64);
    private static final RunContext CONTEXT = new RunContext("sha256:" + "c".repeat(64), "minecraft:overworld");
    @TempDir Path directory;

    @Test
    void clearingRequestBindsOnlyUnchangedGlowstoneAndKeepsInputImmutable() throws IOException {
        JsonObject json = requestJson();
        JsonObject original = json.deepCopy();
        var request = ClearingReconciliationProbe.parseRequest(json);
        assertEquals(original, json);
        assertEquals(ID, request.requestId());
        assertEquals(HASH, request.checkpointHash());
        assertEquals(CONTEXT, request.context());
        assertEquals(3, request.inventoryBefore());
        assertEquals(3, request.expectedAfter());
        assertEquals(4, request.selectedSlot());
        assertEquals("minecraft:air", request.expectedBlock());
        assertEquals("minecraft:jack_o_lantern", request.observedBlock());
        assertEquals(Material.GLOWSTONE, request.material());
        assertNull(request.oldSessionId());
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(json));
    }

    @Test
    void malformedUnknownDuplicateAndNonUtf8RequestsAreRejected() throws IOException {
        JsonObject missing = requestJson();
        missing.remove("player_uuid");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(missing));
        JsonObject unknown = requestJson();
        unknown.addProperty("send_command", "fix");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(unknown));
        JsonObject extraCoordinate = requestJson();
        extraCoordinate.getAsJsonObject("target").addProperty("dimension", "minecraft:the_nether");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(extraCoordinate));
        assertThrows(IOException.class, () -> object("{\"target\":{\"x\":1,\"x\":2}}"));
        assertThrows(IOException.class, () -> object("{} {}"));
        assertThrows(IOException.class, () -> object("{ /* comment */ \"x\":1 }"));
        assertThrows(IOException.class, () -> PlacementReconciliationProbe.strictObject(new byte[]{(byte) 0xff}));
    }

    @Test
    void numericCoercionBoundsUnsupportedMaterialsAndWrongConsumptionAreRejected() throws IOException {
        for (String material : List.of("hoe", "wheat_seeds", "dirt", "birch_planks")) {
            JsonObject json = requestJson();
            json.addProperty("material", material);
            assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        }
        for (int after : new int[]{4, 2, -1}) {
            JsonObject json = requestJson();
            json.addProperty("expected_inventory_after", after);
            assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        }
        JsonObject boundary = requestJson();
        boundary.getAsJsonObject("target").addProperty("x", 30_000_000);
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(boundary));
        JsonObject decimal = requestJson();
        decimal.getAsJsonObject("target").addProperty("y", -60.5);
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(decimal));
        JsonObject stringNumber = requestJson();
        stringNumber.addProperty("inventory_before", "3");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(stringNumber));
        JsonObject wrongBlock = requestJson();
        wrongBlock.addProperty("expected_block", "minecraft:farmland");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(wrongBlock));
    }

    @Test
    void checkpointPlanWorldDimensionAndPlayerEachRemainBound() throws IOException {
        var request = ClearingReconciliationProbe.parseRequest(requestJson());
        assertEquals("", ClearingReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, CONTEXT, ID));
        assertFalse(ClearingReconciliationProbe.bindingProblem(request, "d".repeat(64), PLAN, CONTEXT, CONTEXT, ID).isEmpty());
        assertFalse(ClearingReconciliationProbe.bindingProblem(request, HASH, "sha256:" + "d".repeat(64), CONTEXT, CONTEXT, ID).isEmpty());
        var changed = new RunContext(CONTEXT.worldIdentityHash(), "minecraft:the_nether");
        assertFalse(ClearingReconciliationProbe.bindingProblem(request, HASH, PLAN, changed, CONTEXT, ID).isEmpty());
        assertFalse(ClearingReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, changed, ID).isEmpty());
        assertFalse(ClearingReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, null, ID).isEmpty());
        assertFalse(ClearingReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, CONTEXT, UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void currentProcessOrReplayedSessionCannotSupplyFreshReconciliationEvidence() throws IOException {
        JsonObject json = requestJson();
        json.addProperty("old_session_id", ID);
        var request = ClearingReconciliationProbe.parseRequest(json);
        Instant before = request.createdAt().minusSeconds(1), after = request.createdAt().plusSeconds(1);
        String newSession = UUID.randomUUID().toString();
        assertTrue(ClearingReconciliationProbe.freshSession(request, after, after.plusSeconds(1), newSession));
        assertFalse(ClearingReconciliationProbe.freshSession(request, before, after, newSession));
        assertFalse(ClearingReconciliationProbe.freshSession(request, null, after, newSession));
        assertFalse(ClearingReconciliationProbe.freshSession(request, after, before, newSession));
        assertFalse(ClearingReconciliationProbe.freshSession(request, after, after, ID));
    }

    @Test
    void inventoryNeedsAll36ExactAppliedReceiptsFromTheCurrentObserver() {
        var all = evidence();
        assertEquals(new ClearingReconciliationProbe.InventoryEvidence(true, true),
                ClearingReconciliationProbe.inventoryEvidence(all, ID, 36));
        assertFalse(ClearingReconciliationProbe.inventoryEvidence(all.subList(0, 35), ID, 36).complete());
        for (var invalid : List.of(
                new ClearingReconciliationProbe.SlotEvidence(35, false, true, ID, 36),
                new ClearingReconciliationProbe.SlotEvidence(35, true, true, "old-epoch", 36),
                new ClearingReconciliationProbe.SlotEvidence(35, true, true, ID, 0),
                new ClearingReconciliationProbe.SlotEvidence(35, true, true, ID, 37),
                new ClearingReconciliationProbe.SlotEvidence(0, true, true, ID, 36))) {
            var slots = new ArrayList<>(all);
            slots.set(35, invalid);
            assertFalse(ClearingReconciliationProbe.inventoryEvidence(slots, ID, 36).complete());
        }
        var changedLocally = new ArrayList<>(all);
        changedLocally.set(35, new ClearingReconciliationProbe.SlotEvidence(35, true, false, ID, 36));
        assertEquals(new ClearingReconciliationProbe.InventoryEvidence(true, false),
                ClearingReconciliationProbe.inventoryEvidence(changedLocally, ID, 36));
    }

    @Test
    void appliedSlotLedgerRejectsLocalPredictionsAndDropsEvidenceAfterReconnect() {
        var ledger = new PlayerInventoryUpdateLedger<String>(value -> value, String::equals);
        Object world = new Object(), player = new Object(), connection = new Object();
        for (int slot = 0; slot < 36; slot++) { ledger.applied(world, player, connection, slot, "server", "server"); }
        var mark = ledger.mark(world, player, connection);
        var slots = new ArrayList<ClearingReconciliationProbe.SlotEvidence>();
        for (int slot = 0; slot < 36; slot++) {
            var received = ledger.latest(world, player, connection, slot).orElseThrow();
            slots.add(new ClearingReconciliationProbe.SlotEvidence(slot, true, "local_prediction".equals(received.stack()),
                    received.stamp().epoch(), received.stamp().sequence()));
        }
        assertFalse(ClearingReconciliationProbe.inventoryEvidence(slots, mark.epoch(), mark.sequence()).allMatch());
        assertTrue(ledger.latest(world, player, new Object(), 0).isEmpty());
    }

    @Test
    void cadenceNeverCapturesDuringExecutionAndLimitsEligibleCapturesToOncePerSecond() {
        var cadence = new ClearingReconciliationProbe.Cadence();
        for (String state : List.of("BUILDING", "RESTOCKING", "LOADING", "STUCK", "ERROR", "DONE", "STOPPED")) {
            assertFalse(cadence.due(state, true, 0));
        }
        assertFalse(cadence.due("IDLE", false, 0));
        assertTrue(cadence.due("IDLE", true, 0));
        assertFalse(cadence.due("PAUSED", true, 999_999_999));
        assertTrue(cadence.due("PAUSED", true, 1_000_000_000));
    }

    @Test
    void requestIsOptionalAndBadOrOversizedFilesCannotBreakConstruction() throws IOException {
        assertFalse(new ClearingReconciliationProbe(directory).enabled());
        Path request = directory.resolve("clearing-reconciliation-probe.json");
        Files.writeString(request, "not JSON");
        assertTrue(assertDoesNotThrow(() -> new ClearingReconciliationProbe(directory)).enabled());
        Files.write(request, new byte[ClearingReconciliationProbe.REQUEST_LIMIT + 1]);
        assertThrows(IOException.class, () -> PlacementReconciliationProbe.readBounded(request, ClearingReconciliationProbe.REQUEST_LIMIT));
        assertTrue(assertDoesNotThrow(() -> new ClearingReconciliationProbe(directory)).enabled());
        assertThrows(IOException.class, () -> PlacementReconciliationProbe.readBounded(directory, 4096));
    }

    @Test
    void startupInvalidatesOnlyItsOldObservationAndLeavesCheckpointAndRequestBytesIntact() throws IOException {
        byte[] checkpoint = "original checkpoint bytes".getBytes(StandardCharsets.UTF_8);
        byte[] request = requestJson().toString().getBytes(StandardCharsets.UTF_8);
        Path checkpointPath = directory.resolve("checkpoint.json");
        Path requestPath = directory.resolve("clearing-reconciliation-probe.json");
        Path observation = directory.resolve("clearing-reconciliation-observation.json");
        Files.write(checkpointPath, checkpoint);
        Files.write(requestPath, request);
        Files.writeString(observation, "old evidence");
        var probe = new ClearingReconciliationProbe(directory);
        assertTrue(probe.enabled());
        assertFalse(Files.exists(observation));
        assertArrayEquals(checkpoint, Files.readAllBytes(checkpointPath));
        assertArrayEquals(request, Files.readAllBytes(requestPath));
    }

    @Test
    void selectedSlotBoundsAndUnchangedSingleStackAreStrict() throws IOException {
        for (int slot : new int[]{-1, 9, 35}) {
            var json = requestJson(); json.addProperty("selected_slot", slot);
            assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        }
        for (int count : new int[]{0, 65, 2304}) {
            var json = requestJson(); json.addProperty("inventory_before", count);
            json.addProperty("expected_inventory_after", count);
            assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        }
        var wrongOriginal = requestJson(); wrongOriginal.addProperty("observed_block", "minecraft:moss_block");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(wrongOriginal));
    }

    @Test
    void onlyJackOrOrdinaryAirAreAcceptedAsCurrentTarget() {
        assertTrue(ClearingReconciliationProbe.acceptsTarget("minecraft:jack_o_lantern"));
        assertTrue(ClearingReconciliationProbe.acceptsTarget("minecraft:air"));
        for (String block : List.of("minecraft:glowstone", "minecraft:moss_block", "minecraft:dirt",
                "minecraft:cave_air", "minecraft:void_air", "minecraft:water")) {
            assertFalse(ClearingReconciliationProbe.acceptsTarget(block));
        }
        assertFalse(ClearingReconciliationProbe.acceptsTarget(null));
    }

    @Test
    void replacementRequiresSameSelectedSlotPlainReceiptAndNoOutsideSubstitution() throws IOException {
        var request = ClearingReconciliationProbe.parseRequest(requestJson());
        assertTrue(ClearingReconciliationProbe.replacementMatches(request, 4, true, true, false, 3));
        assertFalse(ClearingReconciliationProbe.replacementMatches(request, 3, true, true, false, 3));
        assertFalse(ClearingReconciliationProbe.replacementMatches(request, 4, false, true, false, 3));
        assertFalse(ClearingReconciliationProbe.replacementMatches(request, 4, true, false, false, 3));
        assertFalse(ClearingReconciliationProbe.replacementMatches(request, 4, true, true, true, 3));
        assertFalse(ClearingReconciliationProbe.replacementMatches(request, 4, true, true, false, 2));
        assertFalse(ClearingReconciliationProbe.replacementMatches(request, 4, true, true, false, 4));
    }

    @Test
    void exactLightingSliceRequiresLoadedMatchingPlanTargetAndUnmodifiedGlowstone() throws IOException {
        var request = ClearingReconciliationProbe.parseRequest(requestJson());
        var checkpoint = checkpointJson();
        var plan = plan(PLAN, new BlockPosition(0, 0, 0), new BlockState("minecraft:glowstone"), Material.GLOWSTONE);
        assertTrue(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, plan));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, null));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan("sha256:" + "e".repeat(64), new BlockPosition(0, 0, 0), new BlockState("minecraft:glowstone"), Material.GLOWSTONE)));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan(PLAN, new BlockPosition(1, 0, 0), new BlockState("minecraft:glowstone"), Material.GLOWSTONE)));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan(PLAN, new BlockPosition(0, 0, 0), new BlockState("minecraft:glowstone", Map.of("changed", "true")), Material.GLOWSTONE)));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan(PLAN, new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"), Material.DIRT)));
        for (String key : List.of("schedule_cursor", "current_chunk_index", "chunk_count")) {
            var changed = checkpoint.deepCopy(); changed.addProperty(key, 100);
            assertFalse(ClearingReconciliationProbe.planTargetMatches(request, changed, plan));
        }
        var changedMode = checkpoint.deepCopy(); changedMode.addProperty("planting_deferred", false);
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, changedMode, plan));
        var active = checkpoint.deepCopy(); active.addProperty("state", "BUILDING");
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, active, plan));
        var wrongSchedule = checkpoint.deepCopy(); wrongSchedule.addProperty("schedule_id", "layers-v1");
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, wrongSchedule, plan));
    }

    private static JsonObject checkpointJson() throws IOException {
        return object("""
                {"version":4,"plan_id":"%s","state":"PAUSED","resume_state":"BUILDING",
                 "phase":"ORDINARY_BLOCKS","schedule_id":"%s",
                 "schedule_cursor":0,"current_chunk_index":0,"chunk_count":1,"planting_deferred":true}
                """.formatted(PLAN, LayerBuildSchedule.DEFERRED_PLANTING_ID));
    }

    private static JsonObject structureRequestJson() throws IOException {
        JsonObject json = requestJson();
        json.addProperty("version", 2);
        json.addProperty("material", "dirt");
        json.addProperty("observed_block", "minecraft:moss_block");
        json.addProperty("inventory_before", 88);
        json.addProperty("expected_inventory_after", 88);
        json.add("replacement_slots", object("{\"slots\":[{\"slot\":4,\"count\":24},{\"slot\":19,\"count\":64}]}").get("slots"));
        return json;
    }

    @Test
    void structureRequestBindsMultiplePlainDirtSlotsWithoutChangingLegacyScope() throws IOException {
        var json = structureRequestJson();
        var original = json.deepCopy();
        var request = ClearingReconciliationProbe.parseRequest(json);
        assertEquals(original, json);
        assertEquals(Material.DIRT, request.material());
        assertEquals(Map.of(4, 24, 19, 64), request.replacementSlots());
        assertEquals(88, request.inventoryBefore());
        assertTrue(ClearingReconciliationProbe.acceptsTarget(request, "minecraft:moss_block"));
        assertTrue(ClearingReconciliationProbe.acceptsTarget(request, "minecraft:air"));
        for (String block : List.of("minecraft:jack_o_lantern", "minecraft:dirt", "minecraft:cave_air", "minecraft:water")) {
            assertFalse(ClearingReconciliationProbe.acceptsTarget(request, block));
        }
        assertFalse(ClearingReconciliationProbe.acceptsTarget(request, null));
        json.addProperty("version", 1);
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        var legacy = requestJson(); legacy.addProperty("version", 2);
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(legacy));
    }

    @Test
    void structureSlotBindingsRejectDuplicatesCountsExtraFieldsAndMissingHand() throws IOException {
        for (String slots : List.of("[]", "[{\"slot\":4,\"count\":88}]",
                "[{\"slot\":4,\"count\":24},{\"slot\":4,\"count\":64}]",
                "[{\"slot\":5,\"count\":24},{\"slot\":19,\"count\":64}]",
                "[{\"slot\":4,\"count\":23},{\"slot\":19,\"count\":64}]",
                "[{\"slot\":4,\"count\":24},{\"slot\":36,\"count\":64}]",
                "[{\"slot\":4,\"count\":24,\"extra\":true},{\"slot\":19,\"count\":64}]")) {
            var json = structureRequestJson(); json.add("replacement_slots", object("{\"slots\":" + slots + "}").get("slots"));
            assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        }
        for (String field : List.of("version", "inventory_before", "expected_inventory_after", "selected_slot")) {
            var json = structureRequestJson(); json.addProperty(field, "2");
            assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        }
    }

    @Test
    void structureInventoryCannotSubstituteSlotsEvenWhenTotalIsUnchanged() throws IOException {
        var request = ClearingReconciliationProbe.parseRequest(structureRequestJson());
        assertTrue(ClearingReconciliationProbe.replacementSlotsMatch(request, Map.of(4, 24, 19, 64), true));
        assertFalse(ClearingReconciliationProbe.replacementSlotsMatch(request, Map.of(4, 24, 19, 64), false));
        assertFalse(ClearingReconciliationProbe.replacementSlotsMatch(request, Map.of(4, 24, 20, 64), true));
        assertFalse(ClearingReconciliationProbe.replacementSlotsMatch(request, Map.of(4, 25, 19, 63), true));
        assertFalse(ClearingReconciliationProbe.replacementSlotsMatch(request, Map.of(4, 24), true));
    }

    @Test
    void structureRecoveryRequiresExactScheduledPlainDirtTarget() throws IOException {
        var request = ClearingReconciliationProbe.parseRequest(structureRequestJson());
        var checkpoint = checkpointJson();
        var plan = plan(PLAN, new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"), Material.DIRT);
        assertTrue(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, plan));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan(PLAN, new BlockPosition(0, 0, 0), new BlockState("minecraft:glowstone"), Material.GLOWSTONE)));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan(PLAN, new BlockPosition(1, 0, 0), new BlockState("minecraft:dirt"), Material.DIRT)));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint,
                plan(PLAN, new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt", Map.of("changed", "true")), Material.DIRT)));
        checkpoint.addProperty("schedule_cursor", 1);
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, plan));
    }

    @Test
    void stemRequestRequiresUnspentDirtAndAcceptsOnlyFreshOrdinaryAir() throws IOException {
        var json = structureRequestJson();
        json.addProperty("version", 3);
        json.addProperty("observed_block", "minecraft:air");
        var original = json.deepCopy();
        var request = ClearingReconciliationProbe.parseRequest(json);
        assertEquals(original, json);
        assertEquals(Map.of(4, 24, 19, 64), request.replacementSlots());
        assertTrue(ClearingReconciliationProbe.acceptsTarget(request, "minecraft:air"));
        for (String block : List.of("minecraft:pumpkin_stem", "minecraft:attached_melon_stem",
                "minecraft:moss_block", "minecraft:cave_air", "minecraft:dirt", "minecraft:water")) {
            assertFalse(ClearingReconciliationProbe.acceptsTarget(request, block));
        }
        assertFalse(ClearingReconciliationProbe.acceptsTarget(request, null));
        json.addProperty("observed_block", "minecraft:moss_block");
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        json.addProperty("observed_block", "minecraft:air");
        json.addProperty("expected_inventory_after", 87);
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
        json.addProperty("expected_inventory_after", 88);
        json.addProperty("version", 2);
        assertThrows(RuntimeException.class, () -> ClearingReconciliationProbe.parseRequest(json));
    }

    @Test
    void stemRecoveryRequiresTheExactScheduledSourceAirOrDeferredCropCell() throws IOException {
        var plan = stemPlan();
        var checkpoint = checkpointJson();
        checkpoint.addProperty("plan_id", plan.planId());
        checkpoint.addProperty("chunk_count", plan.chunkCount());
        for (var target : List.of(new BlockPosition(0, 1, 0), new BlockPosition(1, 1, 0))) {
            var request = stemRequest(plan, target);
            assertTrue(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, plan));
        }
        for (var target : List.of(new BlockPosition(0, 0, 0), new BlockPosition(1, 0, 0),
                new BlockPosition(3, 1, 0), new BlockPosition(1, 2, 0), new BlockPosition(-1, 1, 0),
                new BlockPosition(16, 1, 0))) {
            assertFalse(ClearingReconciliationProbe.planTargetMatches(stemRequest(plan, target), checkpoint, plan),
                    target.toString());
        }
        checkpoint.addProperty("current_chunk_index", 1);
        assertFalse(ClearingReconciliationProbe.planTargetMatches(stemRequest(plan, new BlockPosition(0, 1, 0)), checkpoint, plan));
    }

    @Test
    void stemRecoveryCannotBorrowAnotherStageOrPlantingMode() throws IOException {
        var plan = stemPlan();
        var checkpoint = checkpointJson();
        checkpoint.addProperty("plan_id", plan.planId());
        checkpoint.addProperty("chunk_count", plan.chunkCount());
        var request = stemRequest(plan, new BlockPosition(0, 1, 0));
        checkpoint.addProperty("schedule_cursor", 1);
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, plan));
        checkpoint.addProperty("schedule_cursor", 0);
        checkpoint.addProperty("planting_deferred", false);
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, plan));
        assertFalse(ClearingReconciliationProbe.planTargetMatches(request, checkpoint, null));
    }

    private static ClearingReconciliationProbe.Request stemRequest(SchematicPlan plan, BlockPosition target) throws IOException {
        var json = structureRequestJson();
        json.addProperty("version", 3);
        json.addProperty("observed_block", "minecraft:air");
        json.addProperty("plan_id", plan.planId());
        var position = json.getAsJsonObject("target");
        position.addProperty("x", target.x()); position.addProperty("y", target.y()); position.addProperty("z", target.z());
        return ClearingReconciliationProbe.parseRequest(json);
    }

    private static SchematicPlan stemPlan() {
        return SchematicCompiler.compile(new BuildVolume(0, 0, 0, 16, 3, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:farmland")),
                new TargetBlock(new BlockPosition(0, 1, 0), new BlockState("minecraft:wheat")),
                new TargetBlock(new BlockPosition(1, 0, 0), new BlockState("minecraft:dirt")),
                new TargetBlock(new BlockPosition(3, 1, 0), new BlockState("minecraft:birch_planks")),
                new TargetBlock(new BlockPosition(16, 0, 0), new BlockState("minecraft:dirt"))))
                .withPlantingDeferred(true);
    }

    private static SchematicPlan plan(String id, BlockPosition position, BlockState state, Material material) {
        ChunkCoordinate chunk = new ChunkCoordinate(0, 0);
        ChunkPlan chunkPlan = new ChunkPlan(chunk, List.of(),
                List.of(new OrdinaryPlacement(position, state, material)), List.of(), List.of());
        return new SchematicPlan(id, new ChunkLayout(chunk, 1, 1), new BuildVolume(0, 0, 0, 1, 0, 0),
                List.of(chunkPlan), true);
    }

    private static List<ClearingReconciliationProbe.SlotEvidence> evidence() {
        var result = new ArrayList<ClearingReconciliationProbe.SlotEvidence>();
        for (int slot = 0; slot < 36; slot++) {
            result.add(new ClearingReconciliationProbe.SlotEvidence(slot, true, true, ID, slot + 1));
        }
        return result;
    }

    private static JsonObject object(String json) throws IOException {
        return PlacementReconciliationProbe.strictObject(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject requestJson() throws IOException {
        return object("""
                {"version":1,"request_id":"%s","created_at":"2026-09-16T06:00:00Z",
                 "checkpoint_sha256":"%s","plan_id":"%s",
                 "saved_run_context":{"world_identity_hash":"%s","dimension":"minecraft:overworld"},
                 "player_uuid":"%s","target":{"x":0,"y":0,"z":0},
                 "observed_block":"minecraft:jack_o_lantern","expected_block":"minecraft:air","material":"glowstone",
                 "inventory_before":3,"expected_inventory_after":3,"selected_slot":4}
                """.formatted(ID, HASH, PLAN, CONTEXT.worldIdentityHash(), ID));
    }
}
