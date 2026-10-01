package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.DepotId;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DepotObservationTest {
    private static final RunContext CONTEXT = new RunContext("sha256:" + "ab".repeat(32), "minecraft:overworld");

    @Test
    void filtersOtherWorldsAndDimensionsWithoutReadingOrChangingStock() {
        RegisteredDepot scanned = depot("depot-a", CONTEXT, 10).withScan(MaterialQuantities.of(Material.DIRT, 64));
        RegisteredDepot active = depot("depot-b", CONTEXT, 11);
        RunContext otherWorld = new RunContext("sha256:" + "cd".repeat(32), "minecraft:overworld");
        RegisteredDepot foreign = depot("foreign", otherWorld, 999);
        RegisteredDepot otherDimension = depot("nether", new RunContext(CONTEXT.worldIdentityHash(),
                "minecraft:the_nether"), 888);
        ArrayList<RegisteredDepot> source = new ArrayList<>(List.of(scanned, active, foreign, otherDimension));
        DepotObservation observed = DepotObservation.capture(CONTEXT, source, active.id(),
                List.of(scanned.id(), foreign.id()), "SCAN", "NAVIGATING", false, false, "Flying");
        source.clear();

        assertEquals(2, observed.registeredCount());
        assertEquals(2, observed.entries().size());
        assertEquals("depot-b", observed.activeDepotId());
        assertEquals(List.of("depot-a"), observed.queuedDepotIds());
        assertTrue(observed.entries().get(0).active());
        assertTrue(observed.entries().get(1).queued());
        assertEquals(64, observed.entries().get(1).observedStock().get(Material.DIRT));
        String encoded = new String(SupervisorProtocolJson.encodeObservation(base().withDepots(observed)),
                StandardCharsets.UTF_8);
        assertFalse(encoded.contains(CONTEXT.worldIdentityHash()));
        assertFalse(encoded.contains(otherWorld.worldIdentityHash()));
        assertFalse(encoded.contains("foreign"));
        assertFalse(encoded.contains("nether"));
        assertFalse(encoded.contains("999"));
    }

    @Test
    void retainsTerminalDepotFailureAfterOperationHasReturnedToIdle() {
        RegisteredDepot failed = depot("depot-a", CONTEXT, 10)
                .withError("Flight search exhausted before reaching the registered depot");
        DepotObservation observed = DepotObservation.capture(CONTEXT, List.of(failed), null,
                List.of(), "NONE", "IDLE", false, false, "");
        JsonObject root = encode(base().withDepots(observed));
        JsonObject row = root.getAsJsonObject("depots").getAsJsonArray("entries").get(0).getAsJsonObject();

        assertEquals("IDLE", root.getAsJsonObject("depots").get("stage").getAsString());
        assertTrue(row.get("last_error").getAsString().contains("Flight search exhausted"));
        assertFalse(row.get("scanned").getAsBoolean());
        assertTrue(row.get("observed_stock").isJsonNull());
        assertFalse(row.get("active").getAsBoolean());
    }

    @Test
    void distinguishesConfirmedEmptyChestFromUnscannedAndUnavailableData() {
        RegisteredDepot empty = depot("empty", CONTEXT, 10).withScan(MaterialQuantities.empty());
        RegisteredDepot unknown = depot("unknown", CONTEXT, 11);
        DepotObservation observed = DepotObservation.capture(CONTEXT, List.of(empty, unknown), null,
                List.of(), "NONE", "IDLE", true, false, "");
        assertEquals(MaterialQuantities.empty(), observed.entries().get(0).observedStock());
        assertEquals(null, observed.entries().get(1).observedStock());
        assertThrows(IllegalArgumentException.class, () -> new DepotObservation.Entry("invalid", 0, 0, 0,
                false, MaterialQuantities.empty(), "", false, false));
        DepotObservation unavailable = DepotObservation.capture(null, List.of(empty), empty.id(),
                List.of(empty.id()), "SCAN", "NAVIGATING", false, false, "");
        assertFalse(unavailable.available());
        assertTrue(unavailable.entries().isEmpty());
        assertTrue(unavailable.queuedDepotIds().isEmpty());
        assertEquals(null, unavailable.activeDepotId());
    }

    @Test
    void boundsRegistryTelemetryPrioritizesActiveDepotAndRedactsPrivateHashesFromErrors() {
        ArrayList<RegisteredDepot> registered = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            registered.add(depot("depot-" + index, CONTEXT, index));
        }
        RegisteredDepot last = registered.getLast().withError(CONTEXT.worldIdentityHash() + " failed " + "x".repeat(512));
        registered.set(39, last);
        DepotObservation observed = DepotObservation.capture(CONTEXT, registered, last.id(),
                registered.stream().map(RegisteredDepot::id).toList(), "SCAN", "NAVIGATING", false, false,
                CONTEXT.worldIdentityHash());

        assertTrue(observed.truncated());
        assertEquals(40, observed.registeredCount());
        assertEquals(DepotObservation.MAXIMUM_ENTRIES, observed.entries().size());
        assertEquals(DepotObservation.MAXIMUM_ENTRIES, observed.queuedDepotIds().size());
        assertEquals(last.id().value(), observed.entries().getFirst().id());
        assertTrue(observed.entries().getFirst().lastError().length() <= 256);
        assertFalse(observed.entries().getFirst().lastError().contains(CONTEXT.worldIdentityHash()));
        assertFalse(observed.detail().contains(CONTEXT.worldIdentityHash()));
    }

    @Test
    void optionalDepotTelemetryPreservesOtherFactsAndFitsCombinedProtocolBudget() {
        ArrayList<RegisteredDepot> registered = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            registered.add(depot("depot-" + index, CONTEXT, index).withError("石".repeat(256)));
        }
        DepotObservation depots = DepotObservation.capture(CONTEXT, registered, registered.getFirst().id(),
                List.of(), "NONE", "IDLE", true, false, "");
        String lengthy = "石".repeat(4_096);
        AgentObservation observation = new AgentObservation("run-1", "PAUSED", Instant.EPOCH,
                true, true, true, List.of(lengthy, lengthy), List.of("STOP"), null, null, null,
                lengthy, lengthy, "Idle", 0, "", "").withDepots(depots)
                .withTelemetry(InventoryObservationTest.sampleInventory(), null);
        byte[] bytes = SupervisorProtocolJson.encodeObservation(observation);
        assertTrue(bytes.length <= 65_536);
        JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(root.has("inventory"));
        assertTrue(root.has("depots"));
        assertTrue(root.getAsJsonObject("depots").get("truncated").getAsBoolean());
        assertEquals("depot-0", root.getAsJsonObject("depots").getAsJsonArray("entries")
                .get(0).getAsJsonObject().get("id").getAsString());
        assertFalse(encode(base()).has("depots"));
    }

    private static RegisteredDepot depot(String id, RunContext context, int x) {
        return RegisteredDepot.unscanned(new DepotId(id), context.worldIdentityHash(), context.dimension(),
                x, -62, -20);
    }

    private static AgentObservation base() {
        return new AgentObservation("run-1", "IDLE", Instant.EPOCH, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "");
    }

    private static JsonObject encode(AgentObservation observation) {
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
