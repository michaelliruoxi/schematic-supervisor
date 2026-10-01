package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
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

class PlacementReconciliationProbeTest {
    private static final String ID = "b4b38d93-fd0a-4e22-88b7-7b941452fa00";
    private static final String HASH = "a".repeat(64);
    private static final String PLAN = "sha256:" + "b".repeat(64);
    private static final RunContext CONTEXT = new RunContext("sha256:" + "c".repeat(64), "minecraft:overworld");
    @TempDir Path directory;

    @Test
    void exactOrdinaryRequestPreservesItsBindingAndOneItemConsumption() throws IOException {
        var request = PlacementReconciliationProbe.parseRequest(requestJson());
        assertEquals(ID, request.requestId());
        assertEquals(HASH, request.checkpointHash());
        assertEquals(CONTEXT, request.context());
        assertEquals(94, request.inventoryBefore());
        assertEquals(93, request.expectedAfter());
        assertEquals("minecraft:dirt", request.expectedBlock());
        assertNull(request.oldSessionId());
        for (String material : List.of("dirt", "glowstone", "birch_planks")) {
            JsonObject json = requestJson();
            json.addProperty("material", material);
            json.addProperty("expected_block", "minecraft:" + material);
            assertEquals(material, PlacementReconciliationProbe.parseRequest(json).material().jsonName());
        }
    }

    @Test
    void malformedUnknownDuplicateAndNonUtf8RequestsAreRejected() throws IOException {
        JsonObject missing = requestJson();
        missing.remove("player_uuid");
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(missing));
        JsonObject unknown = requestJson();
        unknown.addProperty("send_command", "fix");
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(unknown));
        JsonObject extraCoordinate = requestJson();
        extraCoordinate.getAsJsonObject("target").addProperty("dimension", "minecraft:the_nether");
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(extraCoordinate));
        assertThrows(IOException.class, () -> object("{\"target\":{\"x\":1,\"x\":2}}"));
        assertThrows(IOException.class, () -> object("{} {}"));
        assertThrows(IOException.class, () -> object("{ /* comment */ \"x\":1 }"));
        assertThrows(IOException.class, () -> PlacementReconciliationProbe.strictObject(new byte[]{(byte) 0xff}));
    }

    @Test
    void numericCoercionBoundsUnsupportedMaterialsAndWrongConsumptionAreRejected() throws IOException {
        for (String material : List.of("hoe", "wheat_seeds", "minecraft:dirt", "DIRT")) {
            JsonObject json = requestJson();
            json.addProperty("material", material);
            assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(json));
        }
        for (int after : new int[]{94, 92, -1}) {
            JsonObject json = requestJson();
            json.addProperty("expected_inventory_after", after);
            assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(json));
        }
        JsonObject boundary = requestJson();
        boundary.getAsJsonObject("target").addProperty("x", 30_000_000);
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(boundary));
        JsonObject decimal = requestJson();
        decimal.getAsJsonObject("target").addProperty("y", -60.5);
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(decimal));
        JsonObject stringNumber = requestJson();
        stringNumber.addProperty("inventory_before", "94");
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(stringNumber));
        JsonObject wrongBlock = requestJson();
        wrongBlock.addProperty("expected_block", "minecraft:farmland");
        assertThrows(RuntimeException.class, () -> PlacementReconciliationProbe.parseRequest(wrongBlock));
    }

    @Test
    void checkpointPlanWorldDimensionAndPlayerEachRemainBound() throws IOException {
        var request = PlacementReconciliationProbe.parseRequest(requestJson());
        assertEquals("", PlacementReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, CONTEXT, ID));
        assertFalse(PlacementReconciliationProbe.bindingProblem(request, "d".repeat(64), PLAN, CONTEXT, CONTEXT, ID).isEmpty());
        assertFalse(PlacementReconciliationProbe.bindingProblem(request, HASH, "sha256:" + "d".repeat(64), CONTEXT, CONTEXT, ID).isEmpty());
        var changed = new RunContext(CONTEXT.worldIdentityHash(), "minecraft:the_nether");
        assertFalse(PlacementReconciliationProbe.bindingProblem(request, HASH, PLAN, changed, CONTEXT, ID).isEmpty());
        assertFalse(PlacementReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, changed, ID).isEmpty());
        assertFalse(PlacementReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, null, ID).isEmpty());
        assertFalse(PlacementReconciliationProbe.bindingProblem(request, HASH, PLAN, CONTEXT, CONTEXT, UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void currentProcessOrReplayedSessionCannotSupplyFreshReconciliationEvidence() throws IOException {
        JsonObject json = requestJson();
        json.addProperty("old_session_id", ID);
        var request = PlacementReconciliationProbe.parseRequest(json);
        Instant before = request.createdAt().minusSeconds(1), after = request.createdAt().plusSeconds(1);
        String newSession = UUID.randomUUID().toString();
        assertTrue(PlacementReconciliationProbe.freshSession(request, after, after.plusSeconds(1), newSession));
        assertFalse(PlacementReconciliationProbe.freshSession(request, before, after, newSession));
        assertFalse(PlacementReconciliationProbe.freshSession(request, null, after, newSession));
        assertFalse(PlacementReconciliationProbe.freshSession(request, after, before, newSession));
        assertFalse(PlacementReconciliationProbe.freshSession(request, after, after, ID));
    }

    @Test
    void inventoryNeedsAll36ExactAppliedReceiptsFromTheCurrentObserver() {
        var all = evidence();
        assertEquals(new PlacementReconciliationProbe.InventoryEvidence(true, true),
                PlacementReconciliationProbe.inventoryEvidence(all, ID, 36));
        assertFalse(PlacementReconciliationProbe.inventoryEvidence(all.subList(0, 35), ID, 36).complete());
        for (var invalid : List.of(
                new PlacementReconciliationProbe.SlotEvidence(35, false, true, ID, 36),
                new PlacementReconciliationProbe.SlotEvidence(35, true, true, "old-epoch", 36),
                new PlacementReconciliationProbe.SlotEvidence(35, true, true, ID, 0),
                new PlacementReconciliationProbe.SlotEvidence(35, true, true, ID, 37),
                new PlacementReconciliationProbe.SlotEvidence(0, true, true, ID, 36))) {
            var slots = new ArrayList<>(all);
            slots.set(35, invalid);
            assertFalse(PlacementReconciliationProbe.inventoryEvidence(slots, ID, 36).complete());
        }
        var changedLocally = new ArrayList<>(all);
        changedLocally.set(35, new PlacementReconciliationProbe.SlotEvidence(35, true, false, ID, 36));
        assertEquals(new PlacementReconciliationProbe.InventoryEvidence(true, false),
                PlacementReconciliationProbe.inventoryEvidence(changedLocally, ID, 36));
    }

    @Test
    void appliedSlotLedgerRejectsLocalPredictionsAndDropsEvidenceAfterReconnect() {
        var ledger = new PlayerInventoryUpdateLedger<String>(value -> value, String::equals);
        Object world = new Object(), player = new Object(), connection = new Object();
        for (int slot = 0; slot < 36; slot++) { ledger.applied(world, player, connection, slot, "server", "server"); }
        var mark = ledger.mark(world, player, connection);
        var slots = new ArrayList<PlacementReconciliationProbe.SlotEvidence>();
        for (int slot = 0; slot < 36; slot++) {
            var received = ledger.latest(world, player, connection, slot).orElseThrow();
            slots.add(new PlacementReconciliationProbe.SlotEvidence(slot, true, "local_prediction".equals(received.stack()),
                    received.stamp().epoch(), received.stamp().sequence()));
        }
        assertFalse(PlacementReconciliationProbe.inventoryEvidence(slots, mark.epoch(), mark.sequence()).allMatch());
        assertTrue(ledger.latest(world, player, new Object(), 0).isEmpty());
    }

    @Test
    void cadenceNeverCapturesDuringExecutionAndLimitsEligibleCapturesToOncePerSecond() {
        var cadence = new PlacementReconciliationProbe.Cadence();
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
        assertFalse(new PlacementReconciliationProbe(directory).enabled());
        Path request = directory.resolve("placement-reconciliation-probe.json");
        Files.writeString(request, "not JSON");
        assertTrue(assertDoesNotThrow(() -> new PlacementReconciliationProbe(directory)).enabled());
        Files.write(request, new byte[PlacementReconciliationProbe.REQUEST_LIMIT + 1]);
        assertThrows(IOException.class, () -> PlacementReconciliationProbe.readBounded(request, PlacementReconciliationProbe.REQUEST_LIMIT));
        assertTrue(assertDoesNotThrow(() -> new PlacementReconciliationProbe(directory)).enabled());
        assertThrows(IOException.class, () -> PlacementReconciliationProbe.readBounded(directory, 4096));
    }

    @Test
    void startupInvalidatesOnlyItsOldObservationAndLeavesCheckpointAndRequestBytesIntact() throws IOException {
        byte[] checkpoint = "original checkpoint bytes".getBytes(StandardCharsets.UTF_8);
        byte[] request = requestJson().toString().getBytes(StandardCharsets.UTF_8);
        Path checkpointPath = directory.resolve("checkpoint.json");
        Path requestPath = directory.resolve("placement-reconciliation-probe.json");
        Path observation = directory.resolve("placement-reconciliation-observation.json");
        Files.write(checkpointPath, checkpoint);
        Files.write(requestPath, request);
        Files.writeString(observation, "old evidence");
        var probe = new PlacementReconciliationProbe(directory);
        assertTrue(probe.enabled());
        assertFalse(Files.exists(observation));
        assertArrayEquals(checkpoint, Files.readAllBytes(checkpointPath));
        assertArrayEquals(request, Files.readAllBytes(requestPath));
    }

    private static List<PlacementReconciliationProbe.SlotEvidence> evidence() {
        var result = new ArrayList<PlacementReconciliationProbe.SlotEvidence>();
        for (int slot = 0; slot < 36; slot++) {
            result.add(new PlacementReconciliationProbe.SlotEvidence(slot, true, true, ID, slot + 1));
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
                 "player_uuid":"%s","target":{"x":8195,"y":-60,"z":-26973},
                 "expected_block":"minecraft:dirt","material":"dirt",
                 "inventory_before":94,"expected_inventory_after":93}
                """.formatted(ID, HASH, PLAN, CONTEXT.worldIdentityHash(), ID));
    }
}
