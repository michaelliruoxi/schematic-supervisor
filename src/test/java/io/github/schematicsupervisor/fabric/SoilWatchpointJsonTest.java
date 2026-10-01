package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SoilWatchpointJsonTest {
    @Test void additiveTelemetryPreservesNullUnknownFactsAndEveryObservationWither() {
        var fixture = new SoilWatchpointsTest.Fixture();
        fixture.tick(0);
        fixture.watch.tick(1_000_000_000L, SoilWatchpointsTest.NOW.plusSeconds(1), null);
        var soil = fixture.watch.observation();
        var disposal = new MinecraftSurplusDisposal.Observation("REOPENING", "Waiting for receipt", true,
                true, false, 0, 0);
        AgentObservation observation = new AgentObservation("run", "BUILDING", SoilWatchpointsTest.NOW,
                true, true, true, List.of(), List.of("PAUSE"), fixture.plan.planId(), null, null,
                "", "", "", 0, null, null)
                .withSoilWatchpoints(soil).withSurplusDisposal(disposal).withTelemetry(null, null, null).withDepots(null)
                .withExecution(null).withMossDeposit(null).withMaterialShop(null);
        assertSame(soil, observation.soilWatchpoints());
        assertSame(disposal, observation.surplusDisposal());
        assertSame(disposal, observation.withSoilWatchpoints(soil).surplusDisposal());
        var root = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
        var encoded = root.getAsJsonObject("soil_watchpoints");
        assertTrue(root.getAsJsonObject("surplus_disposal").get("pending").getAsBoolean());
        assertEquals("REOPENING", root.getAsJsonObject("surplus_disposal").get("phase").getAsString());
        assertFalse(encoded.get("server_random_ticks_observed").getAsBoolean());
        assertTrue(encoded.get("client_samples_only").getAsBoolean());
        var latest = encoded.getAsJsonArray("points").get(0).getAsJsonObject().getAsJsonObject("latest");
        assertEquals("CONTEXT_UNAVAILABLE", latest.get("status").getAsString());
        assertTrue(latest.get("actual").isJsonNull());
        assertTrue(latest.get("nearby_water").isJsonNull());
        assertTrue(root.getAsJsonArray("blockers").isEmpty(), "Optional soil telemetry is not a control blocker");
    }

    @Test void responseBudgetDropsOptionalHistoryAndThenPointsWithExplicitTruncation() {
        var fixture = new SoilWatchpointsTest.Fixture();
        fixture.tick(0);
        var gson = new GsonBuilder().serializeNulls().create();
        JsonObject root = new JsonObject();
        root.addProperty("state", "BUILDING");
        root.add("soil_watchpoints", SoilWatchpointJson.encode(fixture.watch.observation()));
        SoilWatchpointJson.fit(root, gson, 1_000);
        var soil = root.getAsJsonObject("soil_watchpoints");
        assertTrue(soil.get("truncated").getAsBoolean());
        assertFalse(soil.get("available").getAsBoolean());
        assertTrue(soil.getAsJsonArray("points").isEmpty());
        assertEquals("BUILDING", root.get("state").getAsString());
        assertTrue(gson.toJson(root).getBytes(StandardCharsets.UTF_8).length < 1_000);
    }
}
