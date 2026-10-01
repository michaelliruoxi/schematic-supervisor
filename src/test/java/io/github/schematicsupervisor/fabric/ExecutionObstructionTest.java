package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ExecutionObstructionTest {
    private static final BlockPosition FAILED_TARGET = new BlockPosition(8206, -63, -26976);
    private static final Instant CAPTURED = Instant.parse("2026-09-11T15:00:00Z");

    @Test
    void preflightFailureHasItsOwnTargetAndDoesNotInventAnInteractionReceipt() {
        ExecutionObstruction obstruction = obstruction(true, true, List.of(item()), 1);
        ExecutionObservation recovered = new ExecutionObservation(true, "SAFE_RETURN", "Returning",
                new ExecutionObservation.Target(new BlockPosition(8241, -62, -26981), null, null, true),
                null, null, null, false, false, "", "", obstruction);
        JsonObject execution = encoded(recovered);
        assertTrue(execution.get("receipt").isJsonNull());
        assertTrue(execution.get("last_receipt").isJsonNull());
        assertTrue(execution.get("last_failure").isJsonNull());
        JsonObject captured = execution.getAsJsonObject("last_obstruction");
        assertEquals(8206, captured.getAsJsonObject("target").get("x").getAsInt());
        assertEquals(-63, captured.getAsJsonObject("target").get("y").getAsInt());
        assertEquals("minecraft:moss_block", captured.getAsJsonObject("target").get("actual_block").getAsString());
        assertEquals("FLIGHT_ORDINARY_SELECTING", captured.get("mode").getAsString());
        assertEquals(CAPTURED.toString(), captured.get("captured_at").getAsString());
        assertTrue(captured.get("source_world_matches").getAsBoolean());
        assertFalse(captured.get("player_overlaps_target").getAsBoolean());
        assertTrue(captured.get("player_overlaps_support_box").getAsBoolean());
        assertEquals(2, captured.get("support_box_extension_up").getAsInt());
    }

    @Test
    void historicalFactsSurviveRecoveryDisconnectAndDifferentWorldWithoutClaimingFreshness() {
        Object world = new Object();
        Object player = new Object();
        ExecutionObstructionHistory history = new ExecutionObstructionHistory();
        assertNull(history.observation(world, player));
        history.capture(obstruction(true, true, List.of(item()), 1), world, player);
        assertTrue(history.observation(world, player).currentWorldMatches());
        ExecutionObstruction changed = history.observation(new Object(), player);
        assertFalse(changed.currentWorldMatches());
        assertTrue(changed.sourceWorldMatches());
        assertEquals(CAPTURED, changed.capturedAt());
        assertEquals(FAILED_TARGET, changed.target().position());
        assertEquals("minecraft:moss_block", changed.target().actualBlock());
        assertEquals(List.of(item()), changed.entities());
        assertFalse(history.observation(world, new Object()).currentWorldMatches());
        ExecutionObstruction disconnected = history.observation(null, null);
        assertNull(disconnected.currentWorldMatches());
        ExecutionObservation unavailable = ExecutionObservation.unavailable("SUSPENDED", "Paused", null, null,
                "World unavailable", disconnected);
        JsonObject execution = encoded(unavailable);
        assertTrue(execution.get("target").isJsonNull());
        assertTrue(execution.get("receipt").isJsonNull());
        JsonObject saved = execution.getAsJsonObject("last_obstruction");
        assertTrue(saved.get("current_world_matches").isJsonNull());
        assertEquals(1, saved.get("total_entities").getAsInt());
        assertTrue(history.observation(world, player).currentWorldMatches());
    }

    @Test
    void sampleCapPreservesActualTotalAndCopiesOnlyBoundedImmutableFacts() {
        List<ExecutionObstruction.EntitySample> many = new ArrayList<>();
        for (int index = 0; index < 40; index++) { many.add(item()); }
        ExecutionObstruction captured = obstruction(true, true, many, 40);
        many.clear();
        assertEquals(8, captured.entities().size());
        assertEquals(40, captured.totalEntities());
        assertTrue(captured.truncated());
        assertThrows(UnsupportedOperationException.class, () -> captured.entities().clear());
        JsonObject json = encoded(with(captured)).getAsJsonObject("last_obstruction");
        assertEquals(8, json.getAsJsonArray("entities").size());
        assertEquals(40, json.get("total_entities").getAsInt());
        assertTrue(json.get("truncated").getAsBoolean());
        ExecutionObstruction unknownTotal = obstruction(true, true,
                java.util.Collections.nCopies(9, item()), null);
        assertNull(unknownTotal.totalEntities());
        assertTrue(unknownTotal.truncated());
    }

    @Test
    void unreceivedOrMismatchedSourceDoesNotInventAirEmptyEntityCountsOrPlayerFacts() {
        for (ExecutionObstruction unknown : List.of(
                obstruction(true, false, List.of(item()), 1),
                obstruction(false, true, List.of(item()), 1))) {
            assertNull(unknown.target().actualBlock());
            assertNull(unknown.playerOverlapsTarget());
            assertNull(unknown.playerOverlapsSupportBox());
            assertNull(unknown.totalEntities());
            assertTrue(unknown.entities().isEmpty());
            assertFalse(unknown.truncated());
            JsonObject json = encoded(with(unknown)).getAsJsonObject("last_obstruction");
            assertTrue(json.get("total_entities").isJsonNull());
            assertTrue(json.get("player_overlaps_target").isJsonNull());
        }
        assertEquals(Boolean.FALSE, obstruction(true, false, List.of(), 0).target().chunkReceived());
        assertNull(obstruction(false, true, List.of(), 0).target().chunkReceived());
    }

    @Test
    void entitySerializationContainsOnlyApprovedRegistryAndCollisionFacts() {
        ExecutionObstruction.EntitySample player = new ExecutionObstruction.EntitySample(
                "minecraft:player", true, true, false, true, null, null);
        JsonObject json = encoded(with(obstruction(true, true, List.of(item(), player), 2)))
                .getAsJsonObject("last_obstruction");
        JsonObject drop = json.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals(Set.of("entity_type", "living", "player", "intersects_target", "intersects_support_box",
                "item_id", "item_count"), drop.keySet());
        assertEquals("minecraft:item", drop.get("entity_type").getAsString());
        assertEquals("minecraft:moss_block", drop.get("item_id").getAsString());
        assertEquals(3, drop.get("item_count").getAsInt());
        assertFalse(drop.get("living").getAsBoolean());
        assertTrue(drop.get("intersects_target").getAsBoolean());
        JsonObject person = json.getAsJsonArray("entities").get(1).getAsJsonObject();
        assertTrue(person.get("living").getAsBoolean());
        assertTrue(person.get("player").getAsBoolean());
        assertTrue(person.get("item_id").isJsonNull());
        assertTrue(person.get("item_count").isJsonNull());
    }

    @Test
    void legacyExecutionHasNullObstructionAndDiagnosticTextAndNumbersAreBounded() {
        JsonObject legacy = encoded(new ExecutionObservation(true, "IDLE", "", null,
                null, null, null, false, false, "", ""));
        assertTrue(legacy.get("last_obstruction").isJsonNull());
        ExecutionObstruction bounded = new ExecutionObstruction(CAPTURED, "m".repeat(100), "石".repeat(10_000),
                target(true), true, true, false, false, 8,
                java.util.Collections.nCopies(8, item()), false, "e".repeat(10_000));
        assertEquals(64, bounded.mode().length());
        assertEquals(512, bounded.reason().length());
        assertEquals(256, bounded.error().length());
        assertTrue(encoded(with(bounded)).toString().getBytes(StandardCharsets.UTF_8).length < 8_192);
        assertThrows(IllegalArgumentException.class, () -> obstruction(true, true, List.of(item()), 0));
        assertThrows(IllegalArgumentException.class, () -> obstruction(true, true, List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionObstruction.EntitySample(
                "Untrusted player name", true, true, false, true, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionObstruction.EntitySample(
                "minecraft:item", false, false, false, true, "minecraft:dirt", -1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionObstruction.EntitySample(
                "minecraft:item", false, false, false, true, "minecraft:dirt", null));
    }

    private static ExecutionObservation.Target target(boolean received) {
        return new ExecutionObservation.Target(FAILED_TARGET, "minecraft:dirt", "minecraft:moss_block", received);
    }

    private static ExecutionObstruction obstruction(boolean worldMatches, boolean received,
                                                   List<ExecutionObstruction.EntitySample> entities, Integer total) {
        return new ExecutionObstruction(CAPTURED, "FLIGHT_ORDINARY_SELECTING",
                "An entity occupies or stands on the clearing target", target(received), worldMatches, true,
                false, true, total, entities, false, "");
    }

    private static ExecutionObstruction.EntitySample item() {
        return new ExecutionObstruction.EntitySample("minecraft:item", false, false, true, true,
                "minecraft:moss_block", 3);
    }

    private static ExecutionObservation with(ExecutionObstruction obstruction) {
        return new ExecutionObservation(true, "FAILED", "Clearing rejected", null,
                null, null, null, false, false, "", "", obstruction);
    }

    private static JsonObject encoded(ExecutionObservation execution) {
        AgentObservation base = new AgentObservation("run", "PAUSED", Instant.EPOCH, true, true, true,
                List.of(), List.of("RESUME", "STOP"), "plan", null, null, "", "Paused", "", 1, "PAUSE", "request");
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(base.withExecution(execution)),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("execution");
    }
}
