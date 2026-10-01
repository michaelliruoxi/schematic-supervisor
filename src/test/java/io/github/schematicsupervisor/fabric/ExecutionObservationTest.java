package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExecutionObservationTest {
    private static final BlockPosition TARGET = new BlockPosition(12, -63, 30);

    @Test
    void terminalReceiptRetainsExactFailureEvidenceAfterLiveReceiptHasCleared() {
        ExecutionObservation.Receipt last = receipt(true, true);
        ExecutionObservation observation = new ExecutionObservation(true, "FAILED", "Moss clearing timed out",
                new ExecutionObservation.Target(TARGET, "minecraft:dirt", "minecraft:moss_block", true),
                null, last, last, false, false, "", "");
        JsonObject json = encoded(base().withExecution(observation)).getAsJsonObject("execution");
        assertTrue(json.get("receipt").isJsonNull());
        assertEquals("minecraft:dirt", json.getAsJsonObject("target").get("expected_block").getAsString());
        JsonObject terminal = json.getAsJsonObject("last_receipt");
        assertEquals("minecraft:air", terminal.getAsJsonObject("target").get("expected_block").getAsString());
        assertEquals("minecraft:moss_block", terminal.getAsJsonObject("target").get("actual_block").getAsString());
        assertFalse(terminal.get("prediction_pending").getAsBoolean());
        assertEquals("RETRYABLE", terminal.get("result").getAsString());
        assertEquals(1152, terminal.get("inventory_before").getAsLong());
        assertEquals(1152, terminal.get("inventory_now").getAsLong());
        assertEquals(100, terminal.get("age_ticks").getAsInt());
        assertEquals(100, terminal.get("budget_ticks").getAsInt());
        assertTrue(terminal.get("owned_mining").getAsBoolean());
        assertFalse(terminal.get("manager_breaking").getAsBoolean());
        assertEquals(-63, terminal.getAsJsonObject("breaking_position").get("y").getAsInt());
        assertEquals(0.5f, terminal.get("breaking_progress").getAsFloat());
        assertEquals(0.02f, terminal.get("local_breaking_delta").getAsFloat());
        assertEquals(0, terminal.get("breaking_cooldown").getAsInt());
        assertEquals(2, terminal.get("selected_slot").getAsInt());
        assertEquals("minecraft:air", terminal.get("selected_item").getAsString());
        assertEquals("1970-01-01T00:00:00Z", terminal.get("captured_at").getAsString());
        assertEquals(terminal, json.get("last_failure"));
        assertFalse(json.get("owned_mining").getAsBoolean());
    }

    @Test
    void unavailableLiveFactsStayNullWithoutErasingHistoricalReceipt() {
        ExecutionObservation.Receipt last = receipt(true, true);
        ExecutionObservation unavailable = ExecutionObservation.unavailable("SUSPENDED", "Paused", last, last,
                "Client unavailable");
        assertFalse(unavailable.available());
        assertNull(unavailable.target());
        assertNull(unavailable.receipt());
        assertNull(unavailable.ownedMining());
        assertSame(last, unavailable.lastReceipt());
        JsonObject json = encoded(base().withExecution(unavailable)).getAsJsonObject("execution");
        assertTrue(json.get("manager_breaking").isJsonNull());
        assertTrue(json.get("target").isJsonNull());
        assertEquals("RETRYABLE", json.getAsJsonObject("last_receipt").get("result").getAsString());
    }

    @Test
    void unreceivedAndDifferentWorldFactsCannotClaimAirOrAcknowledgement() {
        ExecutionObservation.Receipt unreceived = receipt(true, false);
        assertNull(unreceived.target().actualBlock());
        assertFalse(unreceived.target().chunkReceived());
        assertNull(unreceived.predictionPending());
        assertNull(unreceived.localBreakingDelta());
        ExecutionObservation.Receipt changed = receipt(false, true);
        assertNull(changed.target().actualBlock());
        assertNull(changed.target().chunkReceived());
        assertNull(changed.predictionPending());
        assertNull(changed.inventoryNow());
        assertNull(changed.ownedMining());
        assertNull(changed.managerBreaking());
        assertNull(changed.breakingPosition());
        assertNull(changed.breakingProgress());
        assertNull(changed.localBreakingDelta());
        assertNull(changed.breakingCooldown());
        assertNull(changed.selectedSlot());
        assertNull(changed.selectedItem());
        assertEquals(1152, changed.inventoryBefore());
    }

    @Test
    void optionalExecutionAndExistingConstructorsPreserveOtherTelemetry() {
        AgentObservation old = base();
        assertFalse(encoded(old).has("execution"));
        ExecutionObservation execution = new ExecutionObservation(true, "IDLE", "", null,
                null, null, null, false, false, "", "");
        InventoryObservation inventory = InventoryObservationTest.sampleInventory();
        AgentObservation attached = old.withExecution(execution).withTelemetry(inventory, null).withDepots(null);
        assertSame(execution, attached.execution());
        assertSame(inventory, attached.inventory());
        assertEquals(36, encoded(attached).getAsJsonObject("inventory").getAsJsonArray("main_slots").size());
        AgentObservation legacy = new AgentObservation("run", "IDLE", Instant.EPOCH, true, true, true,
                List.of(), List.of(), null, null, null, "", "Ready", "", 0, "", "",
                inventory, null, null, null);
        assertNull(legacy.execution());
        assertSame(inventory, legacy.inventory());
    }

    @Test
    void diagnosticTextIsBoundedAndInvalidReceiptNumbersAreRejected() {
        ExecutionObservation observation = new ExecutionObservation(true, "M".repeat(100), "石".repeat(10_000),
                null, null, null, null, false, false, "F".repeat(10_000), "E".repeat(10_000));
        assertEquals(64, observation.mode().length());
        assertEquals(512, observation.detail().length());
        assertEquals(512, observation.flightStatus().length());
        assertEquals(256, observation.error().length());
        assertTrue(SupervisorProtocolJson.encodeObservation(base().withExecution(observation)).length <= 65_536);
        assertThrows(IllegalArgumentException.class, () -> new ExecutionObservation.Receipt(Instant.EPOCH, "",
                new ExecutionObservation.Target(TARGET, "minecraft:air", null, null), null,
                "dirt", -1, null, "WAITING", 0, 100, true,
                null, null, null, null, null, null, null, null, ""));
    }

    private static ExecutionObservation.Receipt receipt(boolean worldMatches, boolean received) {
        return new ExecutionObservation.Receipt(Instant.EPOCH, "FLIGHT_CLEARING_MOSS",
                new ExecutionObservation.Target(TARGET, "minecraft:air", "minecraft:moss_block", received),
                false, "dirt", 1152, 1152L, "RETRYABLE", 100, 100, worldMatches,
                true, false, TARGET, 0.5f, 0.02f, 0, 2, "minecraft:air", "");
    }

    private static AgentObservation base() {
        return new AgentObservation("run", "PAUSED", Instant.EPOCH, true, true, true,
                List.of(), List.of("RESUME", "STOP"), "plan", null, null, "", "Paused", "", 1, "PAUSE", "request");
    }

    private static JsonObject encoded(AgentObservation observation) {
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
