package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildPhase;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.LayerProgress;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialLedgerSnapshot;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RecoveryAdvice;
import io.github.schematicsupervisor.core.RecoveryIncident;
import io.github.schematicsupervisor.core.RecoveryStage;
import io.github.schematicsupervisor.core.ScheduleProgress;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SupervisorState;
import io.github.schematicsupervisor.core.SupervisorStatus;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.VerificationStage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class SupervisorProtocolJsonTest {
    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void guardedControlsRejectChangedRunStateAndOperatorSequence() {
        String body = """
                {"action":"RESUME","request_id":"request-1","sent_at":"2026-07-23T12:00:00Z",
                 "expected_run_id":"run-1","expected_state":"PAUSED","expected_control_sequence":7}
                """;
        ControlHttpServer.ControlRequest request = SupervisorProtocolJson.decodeControlRequest(
                body.getBytes(StandardCharsets.UTF_8));
        assertTrue(request.matches("run-1", "PAUSED", 7));
        assertFalse(request.matches("run-2", "PAUSED", 7));
        assertFalse(request.matches("run-1", "BUILDING", 7));
        assertFalse(request.matches("run-1", "PAUSED", 8));
    }

    @Test
    void controlPreconditionsMustBePairedAndSequenceMustBeAnInteger() {
        String prefix = """
                {"action":"START","request_id":"request-1","sent_at":"2026-07-23T12:00:00Z",
                """;
        for (String extra : List.of(
                "\"expected_run_id\":\"run-1\"}",
                "\"expected_state\":\"IDLE\"}",
                "\"expected_control_sequence\":-1}",
                "\"expected_control_sequence\":1.5}",
                "\"expected_control_sequence\":\"1\"}",
                "\"expected_control_sequence\":9223372036854775808}",
                "\"expected_run_id\":null,\"expected_state\":\"IDLE\"}")) {
            assertThrows(SupervisorProtocolJson.ProtocolException.class,
                    () -> SupervisorProtocolJson.decodeControlRequest(
                            (prefix + extra).getBytes(StandardCharsets.UTF_8)), extra);
        }
    }

    @Test
    void idleObservationIsExplicitAndPreservesOperatorStopEvidence() {
        AgentObservation observation = new AgentObservation(
                "run-1", "IDLE", NOW, false, false, true,
                List.of("Join the target world."), List.of("STOP"),
                null, null, null, "", "Stopped.", "Disconnected", 9, "STOP", null);
        JsonObject root = JsonParser.parseString(new String(
                SupervisorProtocolJson.encodeObservation(observation), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(1, root.get("protocol_version").getAsInt());
        assertEquals("run-1", root.get("run_id").getAsString());
        assertEquals("IDLE", root.get("state").getAsString());
        assertFalse(root.get("ready").getAsBoolean());
        assertTrue(root.get("control_token_configured").getAsBoolean());
        assertTrue(root.get("phase").isJsonNull());
        assertTrue(root.get("current_chunk").isJsonNull());
        assertTrue(root.get("current_layer").isJsonNull());
        assertTrue(root.get("last_error").isJsonNull());
        assertEquals(9, root.getAsJsonObject("last_control").get("sequence").getAsLong());
        assertEquals("STOP", root.getAsJsonObject("last_control").get("action").getAsString());
        assertTrue(root.getAsJsonObject("last_control").get("request_id").isJsonNull());
    }

    @Test
    void incidentIsCompleteButOmitsCoordinatesAndWorkOrderContents() {
        RecoveryIncident incident = new RecoveryIncident(
                "private-plan-name",
                7,
                BuildPhase.PLANT,
                45,
                true,
                MaterialQuantities.of(Material.WHEAT_SEEDS, 12),
                "No verified progress",
                true,
                true
        );

        SupervisorProtocolJson.EncodedIncident encoded =
                SupervisorProtocolJson.encodeIncident(incident, NOW);
        JsonObject root = JsonParser.parseString(
                new String(encoded.body(), StandardCharsets.UTF_8)
        ).getAsJsonObject();

        assertTrue(root.get("recovery_exhausted").getAsBoolean());
        assertTrue(root.has("status"));
        assertFalse(root.getAsJsonObject("status").entrySet().isEmpty());
        assertFalse(root.getAsJsonArray("attempted_recovery").isEmpty());
        assertEquals("STOP_MOVEMENT", root.getAsJsonArray("attempted_recovery")
                .get(0).getAsString());
        JsonObject chunk = root.getAsJsonObject("status")
                .getAsJsonObject("current_chunk");
        assertEquals(7, chunk.get("index").getAsInt());
        assertFalse(chunk.has("x"));
        assertFalse(chunk.has("z"));
        assertFalse(root.has("plan_id"));
        assertFalse(root.getAsJsonObject("details").has("plan_id"));
        assertFalse(new String(encoded.body(), StandardCharsets.UTF_8)
                .contains("private-plan-name"));
    }

    @Test
    void incidentIdentifierIsStableAndOpaque() {
        RecoveryIncident incident = incident();
        SupervisorProtocolJson.EncodedIncident first =
                SupervisorProtocolJson.encodeIncident(incident, NOW);
        SupervisorProtocolJson.EncodedIncident second =
                SupervisorProtocolJson.encodeIncident(incident, NOW.plusSeconds(1));

        assertEquals(first.incidentId(), second.incidentId());
        assertTrue(first.incidentId().startsWith("incident-"));
        assertFalse(first.incidentId().contains(incident.planId()));
    }

    @ParameterizedTest
    @EnumSource(RecoveryAdvice.class)
    void everyAllowlistedAdviceDecodes(RecoveryAdvice advice) throws IOException {
        String response = """
                {
                  "incident_id": "incident-1",
                  "action": "%s",
                  "reason": "Fixed local reason.",
                  "source": "disabled",
                  "used_fallback": false
                }
                """.formatted(advice.name());

        RecoveryAdvice decoded = SupervisorProtocolJson.decodeAdvice(
                bytes(response),
                SupervisorProtocolJson.MAX_RESPONSE_BYTES,
                "incident-1"
        );

        assertEquals(advice, decoded);
    }

    @Test
    void adviceRejectsUnknownActionMismatchedIdAndUnknownField() {
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> decodeAdvice("RUN_COMMAND", "incident-1", "")
        );
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> decodeAdvice("WAIT", "incident-other", "")
        );
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> decodeAdvice("WAIT", "incident-1", ",\"command\":\"/warp shop\"")
        );
    }

    @Test
    void statusOmitsChunkCoordinatesAndBoundsMaterialShape() {
        MaterialQuantities inventory = MaterialQuantities.of(Map.of(
                Material.DIRT, 20L,
                Material.FOOD, 4L
        ));
        MaterialQuantities missing = MaterialQuantities.of(Material.DIRT, 5);
        MaterialLedgerSnapshot ledger = MaterialLedgerSnapshot.calculate(
                MaterialQuantities.of(Material.DIRT, 100),
                MaterialQuantities.empty(),
                MaterialQuantities.empty()
        );
        SupervisorStatus status = new SupervisorStatus(
                SupervisorState.BUILDING,
                BuildPhase.ORDINARY_BLOCKS,
                VerificationStage.CHUNK,
                2,
                new ChunkCoordinate(12, -3),
                RecoveryStage.NONE,
                0,
                ledger,
                inventory,
                missing,
                ""
        );

        JsonObject root = JsonParser.parseString(new String(
                SupervisorProtocolJson.encodeStatus(status, "Walking", NOW),
                StandardCharsets.UTF_8
        )).getAsJsonObject();
        JsonObject chunk = root.getAsJsonObject("current_chunk");

        assertEquals(2, chunk.get("index").getAsInt());
        assertFalse(chunk.has("x"));
        assertFalse(chunk.has("z"));
        JsonObject dirt = root.getAsJsonObject("materials").getAsJsonObject("dirt");
        assertEquals(20, dirt.get("available").getAsLong());
        assertEquals(25, dirt.get("required").getAsLong());
        assertEquals(5, dirt.get("missing").getAsLong());
    }

    @Test
    void liveObservationAndCompanionStatusExposeIdenticalLayerProgress() {
        SupervisorStatus status = statusWithLayer(new LayerProgress(
                "LAYERS", "STRUCTURE", 7, 101, -12, 3, 41));
        AgentObservation observation = new AgentObservation(
                "run-1", "BUILDING", NOW, true, true, true,
                List.of(), List.of("PAUSE", "STOP"),
                "plan-1", null, status, "", "Building.", "Walking", 1, "START", "request-1");
        JsonObject live = JsonParser.parseString(new String(
                SupervisorProtocolJson.encodeObservation(observation), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject companion = JsonParser.parseString(new String(
                SupervisorProtocolJson.encodeStatus(status, "Walking", NOW), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject layer = live.getAsJsonObject("current_layer");
        assertEquals(layer, companion.getAsJsonObject("current_layer"));
        assertEquals("LAYERS", layer.get("order").getAsString());
        assertEquals("STRUCTURE", layer.get("stage").getAsString());
        assertEquals(7, layer.get("index").getAsInt());
        assertEquals(101, layer.get("total").getAsInt());
        assertEquals(-12, layer.get("y").getAsInt());
        assertEquals(3, layer.get("chunk_index").getAsInt());
        assertEquals(41, layer.get("chunk_total").getAsInt());
        assertEquals(2, live.getAsJsonObject("current_chunk").get("index").getAsInt());
    }

    @Test
    void allThreeStatusContractsReportActualPlanTotalAndKeepSparseLayerTotalSeparate() {
        SupervisorStatus status = new SupervisorStatus(SupervisorState.BUILDING, BuildPhase.ORDINARY_BLOCKS,
                VerificationStage.CHUNK, 70, new ChunkCoordinate(9, 6), RecoveryStage.NONE, 0,
                MaterialLedgerSnapshot.calculate(MaterialQuantities.empty(), MaterialQuantities.empty(),
                        MaterialQuantities.empty()), MaterialQuantities.empty(), MaterialQuantities.empty(), "",
                new LayerProgress("LAYERS", "STRUCTURE", 1, 1, 0, 2, 2), 70, true, 1, true);
        AgentObservation observation = new AgentObservation("run-rect", "BUILDING", NOW, true, true, true,
                List.of(), List.of("PAUSE", "STOP"), "plan-rect", null, status,
                "", "Building", "Flying", 1, "START", "request-rect");
        RecoveryIncident incident = new RecoveryIncident("plan-rect", 70, BuildPhase.ORDINARY_BLOCKS,
                15, false, MaterialQuantities.empty(), "No progress", true, true, 70);
        JsonObject live = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject companion = JsonParser.parseString(new String(SupervisorProtocolJson.encodeStatus(status, "Flying", NOW),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject recovery = JsonParser.parseString(new String(SupervisorProtocolJson.encodeIncident(incident, NOW).body(),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("status");
        for (JsonObject encoded : List.of(live, companion, recovery)) {
            assertEquals(70, encoded.getAsJsonObject("current_chunk").get("index").getAsInt());
            assertEquals(70, encoded.getAsJsonObject("current_chunk").get("total").getAsInt());
        }
        assertEquals(2, live.getAsJsonObject("current_layer").get("chunk_total").getAsInt());
        assertEquals(2, companion.getAsJsonObject("current_layer").get("chunk_total").getAsInt());
        assertTrue(live.get("glowstone_after_structure").getAsBoolean());
        assertTrue(companion.get("glowstone_after_structure").getAsBoolean());
        assertTrue(live.get("planting_deferred").getAsBoolean());
    }

    @Test
    void observationOptionallyExposesRealInventoryAndPlayerFactsWithoutChangingLegacyConstructor() {
        AgentObservation old = new AgentObservation("run-1", "IDLE", NOW, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "");
        JsonObject oldJson = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(old),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(oldJson.has("inventory"));
        assertFalse(oldJson.has("shop"));
        assertFalse(oldJson.has("player"));
        PlayerObservation player = new PlayerObservation(6_000.5, -61.25, -47_000.5,
                true, true, false, 20, 18, 4.5, "container", true,
                new PlayerObservation.Background("GenericContainerScreen", false, true, false, false));
        AgentObservation updated = old.withTelemetry(InventoryObservationTest.sampleInventory(), null, player);
        JsonObject encoded = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(updated),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject inventory = encoded.getAsJsonObject("inventory");
        assertEquals(36, inventory.getAsJsonArray("main_slots").size());
        assertEquals(0, inventory.getAsJsonArray("main_slots").get(0).getAsJsonObject().get("slot").getAsInt());
        assertEquals(40, inventory.getAsJsonObject("offhand").get("slot").getAsInt());
        assertEquals(48, inventory.getAsJsonObject("main_material_totals").get("dirt").getAsInt());
        assertEquals(64, inventory.getAsJsonObject("main_and_offhand_material_totals").get("dirt").getAsInt());
        assertEquals(48, encoded.getAsJsonObject("materials").getAsJsonObject("dirt").get("available").getAsInt());
        assertEquals(1_551, inventory.getAsJsonArray("main_slots").get(2).getAsJsonObject()
                .getAsJsonObject("hoe_durability").get("remaining").getAsInt());
        assertEquals(12, inventory.getAsJsonObject("menu").getAsJsonObject("cursor").get("count").getAsInt());
        assertEquals(-61.25, encoded.getAsJsonObject("player").get("y").getAsDouble());
        JsonObject background = encoded.getAsJsonObject("player").getAsJsonObject("background");
        assertEquals("GenericContainerScreen", background.get("screen_class").getAsString());
        assertFalse(background.get("window_focused").getAsBoolean());
        assertTrue(background.get("window_minimized").getAsBoolean());
        assertFalse(background.get("world_actions_allowed").getAsBoolean());
        assertFalse(background.get("keeps_world_ticking").getAsBoolean());
        assertFalse(encoded.getAsJsonObject("player").has("uuid"));
        assertFalse(encoded.getAsJsonObject("player").has("name"));
        assertFalse(encoded.getAsJsonObject("player").has("server_address"));
        assertFalse(inventory.has("chat"));
        assertTrue(inventory.getAsJsonArray("main_slots").get(0).getAsJsonObject()
                .get("display_name").isJsonNull());
        assertTrue(inventory.getAsJsonArray("main_slots").get(0).getAsJsonObject()
                .get("plain_default_components").isJsonNull());
        assertTrue(inventory.get("armor_slots").isJsonNull());
        assertTrue(inventory.get("normal_material_capacity").isJsonNull());
    }

    @Test
    void inventoryReportsEquipmentGenericDurabilityAndIndependentNormalMaterialCapacity() {
        InventoryObservation base = InventoryObservationTest.sampleInventory();
        java.util.ArrayList<InventoryObservation.Slot> armor = InventoryObservationTest.emptyArmor();
        armor.set(0, new InventoryObservation.Slot(36, "minecraft:diamond_boots", 1, 1,
                null, false, false, 73, 429, false, "Boots", false));
        armor.set(2, new InventoryObservation.Slot(38, "minecraft:elytra", 1, 1,
                null, false, false, 73, 432, true, "Wings", false));
        InventoryObservation inventory = base.withEquipmentAndCapacity(armor, Map.of(
                Material.DIRT, 100L, Material.GLOWSTONE, 80L, Material.BIRCH_PLANKS, 0L,
                Material.WHEAT_SEEDS, 64L));
        AgentObservation observation = new AgentObservation("run-1", "PAUSED", NOW, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "")
                .withTelemetry(inventory, null);
        JsonObject encoded = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("inventory");
        JsonArray equipment = encoded.getAsJsonArray("armor_slots");
        assertEquals(4, equipment.size());
        JsonObject boots = equipment.get(0).getAsJsonObject();
        assertEquals(36, boots.get("slot").getAsInt());
        assertEquals("feet", boots.get("equipment_slot").getAsString());
        assertEquals("Boots", boots.get("display_name").getAsString());
        assertEquals(356, boots.getAsJsonObject("durability").get("remaining").getAsInt());
        assertFalse(boots.has("hoe_durability"));
        assertTrue(equipment.get(2).getAsJsonObject().getAsJsonObject("durability").get("remaining").isJsonNull());
        assertEquals("head", equipment.get(3).getAsJsonObject().get("equipment_slot").getAsString());
        assertEquals("minecraft:air", equipment.get(3).getAsJsonObject().get("item_id").getAsString());
        JsonObject hoe = encoded.getAsJsonArray("main_slots").get(2).getAsJsonObject();
        assertEquals(hoe.get("hoe_durability"), hoe.get("durability"));
        JsonObject capacity = encoded.getAsJsonObject("normal_material_capacity");
        assertEquals(4, capacity.size());
        assertEquals(100, capacity.get("dirt").getAsInt());
        assertEquals(80, capacity.get("glowstone").getAsInt());
        assertEquals(0, capacity.get("birch_planks").getAsInt());
        assertEquals(64, capacity.get("wheat_seeds").getAsInt());
        assertFalse(capacity.has("food"));
        for (com.google.gson.JsonElement item : equipment) {
            assertFalse(item.getAsJsonObject().has("components"));
            assertFalse(item.getAsJsonObject().has("nbt"));
        }
    }

    @Test
    void inventoryNamesAndComponentFlagsCoverMainOffhandAndCursorWithoutItemPayloads() {
        InventoryObservation sample = InventoryObservationTest.sampleInventory();
        java.util.ArrayList<InventoryObservation.Slot> slots = new java.util.ArrayList<>(sample.mainSlots());
        slots.set(0, new InventoryObservation.Slot(0, "minecraft:tripwire_hook", 5, 64,
                null, false, false, null, null, false, "Rare Key\nUse carefully", false));
        InventoryObservation.Slot offhand = new InventoryObservation.Slot(40, "minecraft:dirt", 16, 64,
                Material.DIRT, true, false, null, null, false, "Dirt", true);
        InventoryObservation.Slot cursor = new InventoryObservation.Slot(-1, "minecraft:tripwire_hook", 1, 64,
                null, false, false, null, null, false, "A named key", false);
        InventoryObservation inventory = InventoryObservation.capture(slots, offhand, 0, 64,
                new InventoryObservation.Menu(true, "container", "Shop", 7, cursor));
        AgentObservation observation = new AgentObservation("run-1", "PAUSED", NOW, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "")
                .withTelemetry(inventory, null);
        JsonObject encoded = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("inventory");
        JsonObject mainKey = encoded.getAsJsonArray("main_slots").get(0).getAsJsonObject();
        assertEquals("minecraft:tripwire_hook", mainKey.get("item_id").getAsString());
        assertEquals(5, mainKey.get("count").getAsInt());
        assertEquals("Rare Key Use carefully", mainKey.get("display_name").getAsString());
        assertFalse(mainKey.get("plain_default_components").getAsBoolean());
        assertEquals("Dirt", encoded.getAsJsonObject("offhand").get("display_name").getAsString());
        assertTrue(encoded.getAsJsonObject("offhand").get("plain_default_components").getAsBoolean());
        JsonObject heldCursor = encoded.getAsJsonObject("menu").getAsJsonObject("cursor");
        assertEquals("A named key", heldCursor.get("display_name").getAsString());
        assertFalse(heldCursor.get("plain_default_components").getAsBoolean());
        for (JsonObject slot : List.of(mainKey, encoded.getAsJsonObject("offhand"), heldCursor)) {
            assertFalse(slot.has("nbt"));
            assertFalse(slot.has("components"));
            assertFalse(slot.has("lore"));
        }
    }

    @Test
    void shopTelemetryBoundsLargeObservedMenuAndReportsTruncation() {
        java.util.ArrayList<DirtShopObservation.Entry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < 54; index++) {
            entries.add(new DirtShopObservation.Entry(index, "石".repeat(200), "dirt".repeat(50),
                    "minecraft:dirt", true, List.of("石".repeat(300), "x".repeat(300), "extra"), 64, false));
        }
        java.util.ArrayList<ShopMenuHistory.Entry> menuEntries = new java.util.ArrayList<>();
        for (int index = 0; index < 54; index++) {
            menuEntries.add(new ShopMenuHistory.Entry(index, "minecraft:" + "x".repeat(118), 64));
        }
        List<ShopMenuHistory.Menu> history = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> new ShopMenuHistory.Menu("石".repeat(127) + index, menuEntries, false)).toList();
        DirtShopObservation shop = new DirtShopObservation(true, "FAILED", false, "DIRT",
                "Waiting", 0, 0, 32, 48, true, "Shop", 1L, 2, false, true, entries, false, "", history);
        java.util.ArrayList<InventoryObservation.Slot> inventorySlots = new java.util.ArrayList<>();
        for (int index = 0; index < 36; index++) {
            inventorySlots.add(new InventoryObservation.Slot(index, "minecraft:tripwire_hook", 5, 64,
                    null, false, false, null, null, false, "<".repeat(1_000), false));
        }
        InventoryObservation.Slot offhand = new InventoryObservation.Slot(40, "minecraft:tripwire_hook", 1, 64,
                null, false, false, null, null, false, "<".repeat(1_000), false);
        InventoryObservation.Slot cursor = new InventoryObservation.Slot(-1, "minecraft:tripwire_hook", 1, 64,
                null, false, false, null, null, false, "<".repeat(1_000), false);
        java.util.ArrayList<InventoryObservation.Slot> armor = InventoryObservationTest.emptyArmor();
        for (int index = 0; index < 4; index++) {
            armor.set(index, new InventoryObservation.Slot(index + 36, "minecraft:diamond_helmet", 1, 1,
                    null, false, false, 10, 363, false, "<".repeat(1_000), false));
        }
        InventoryObservation namedInventory = InventoryObservation.capture(inventorySlots, offhand, 0, 64,
                new InventoryObservation.Menu(true, "container", "Shop", 2, cursor))
                .withEquipmentAndCapacity(armor, Map.of(Material.DIRT, 0L, Material.GLOWSTONE, 0L,
                        Material.BIRCH_PLANKS, 0L, Material.WHEAT_SEEDS, 0L));
        ExecutionObstruction obstruction = new ExecutionObstruction(NOW, "FLIGHT_ORDINARY_SELECTING",
                "An entity occupies or stands on the clearing target",
                new ExecutionObservation.Target(new io.github.schematicsupervisor.core.BlockPosition(8206, -63, -26976),
                        "minecraft:dirt", "minecraft:moss_block", true), true, true, false, false, 8,
                java.util.Collections.nCopies(8, new ExecutionObstruction.EntitySample("minecraft:item",
                        false, false, true, true, "minecraft:moss_block", 1)), false, "");
        ExecutionObservation execution = new ExecutionObservation(true, "FAILED", "Rejected", null,
                null, null, null, false, false, "", "", obstruction);
        AgentObservation observation = new AgentObservation("run-1", "PAUSED", NOW, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "")
                .withTelemetry(namedInventory, shop).withExecution(execution);
        byte[] bytes = SupervisorProtocolJson.encodeObservation(observation);
        assertTrue(bytes.length <= 65_536);
        JsonObject encoded = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject menu = encoded.getAsJsonObject("shop");
        assertTrue(menu.get("truncated").getAsBoolean());
        assertEquals(54, menu.getAsJsonArray("entries").size());
        assertEquals("minecraft:dirt", menu.getAsJsonArray("entries").get(53).getAsJsonObject()
                .get("item_id").getAsString());
        assertTrue(menu.getAsJsonArray("entries").get(0).getAsJsonObject().get("label").getAsString().length() <= 96);
        assertEquals(2, menu.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("lore").size());
        assertEquals(96, encoded.getAsJsonObject("inventory").getAsJsonArray("main_slots")
                .get(35).getAsJsonObject().get("display_name").getAsString().length());
        assertTrue(menu.get("menu_history_truncated").getAsBoolean());
        assertTrue(menu.getAsJsonArray("menu_history").size() < 4);
        JsonObject saved = encoded.getAsJsonObject("execution").getAsJsonObject("last_obstruction");
        assertEquals(8206, saved.getAsJsonObject("target").get("x").getAsInt());
        assertEquals(8, saved.getAsJsonArray("entities").size());
        assertEquals(8, saved.get("total_entities").getAsInt());
        assertFalse(saved.get("truncated").getAsBoolean());
    }

    @Test
    void passiveMenuHistoryExportsOnlyBoundedExactItemFactsAndPreservesLegacyShopFields() {
        List<ShopMenuHistory.Entry> rows = List.of(
                new ShopMenuHistory.Entry(4, "minecraft:dirt", 64),
                new ShopMenuHistory.Entry(5, "minecraft:birch_planks", 32),
                new ShopMenuHistory.Entry(6, "not an item identifier", 1),
                new ShopMenuHistory.Entry(54, "minecraft:glowstone", 64));
        List<ShopMenuHistory.Menu> history = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> new ShopMenuHistory.Menu("Menu " + index + "x".repeat(200), rows, false)).toList();
        DirtShopObservation shop = new DirtShopObservation(true, "WAITING_OPEN", true, "BLOCKS",
                "Waiting", 0, 0, 32, 48, true, "Shop", 1L, 2, false, false, List.of(), false, "", history);
        AgentObservation observation = new AgentObservation("run-1", "PAUSED", NOW, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "")
                .withTelemetry(null, shop);
        JsonObject encoded = JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("shop");
        assertEquals("BLOCKS", encoded.get("expected_step").getAsString());
        assertTrue(encoded.get("menu_history_truncated").getAsBoolean());
        assertEquals(4, encoded.getAsJsonArray("menu_history").size());
        JsonObject first = encoded.getAsJsonArray("menu_history").get(0).getAsJsonObject();
        assertTrue(first.has("captured_at"));
        assertTrue(first.get("captured_at").getAsString().length() <= 64);
        assertTrue(first.get("title").getAsString().startsWith("Menu 2"));
        assertEquals(128, first.get("title").getAsString().length());
        assertTrue(first.get("truncated").getAsBoolean());
        assertEquals(2, first.getAsJsonArray("entries").size());
        JsonObject row = first.getAsJsonArray("entries").get(1).getAsJsonObject();
        assertEquals(java.util.Set.of("slot", "item_id", "stack_count"), row.keySet());
        assertEquals("minecraft:birch_planks", row.get("item_id").getAsString());
        assertEquals(32, row.get("stack_count").getAsInt());
    }

    @Test
    void finalVerificationExplicitlyReportsNoActiveLayerHeight() {
        SupervisorStatus status = statusWithLayer(new LayerProgress(
                "LAYERS", "VERIFY", 101, 101, null, 0, 0));
        JsonObject companion = JsonParser.parseString(new String(
                SupervisorProtocolJson.encodeStatus(status, "Stopped", NOW), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject layer = companion.getAsJsonObject("current_layer");
        assertEquals("VERIFY", layer.get("stage").getAsString());
        assertTrue(layer.get("y").isJsonNull());
        assertEquals(0, layer.get("chunk_total").getAsInt());
    }

    private static SupervisorStatus statusWithLayer(LayerProgress layer) {
        MaterialQuantities empty = MaterialQuantities.empty();
        return new SupervisorStatus(
                SupervisorState.BUILDING, BuildPhase.ORDINARY_BLOCKS, VerificationStage.CHUNK,
                2, new ChunkCoordinate(12, -3), RecoveryStage.NONE, 0,
                MaterialLedgerSnapshot.calculate(empty, empty, empty), empty, empty, "", layer);
    }

    @Test
    void controlRequestRequiresExactFieldsTypesAndAllowlistedAction() {
        String valid = """
                {
                  "action": "PAUSE",
                  "request_id": "request-1",
                  "sent_at": "2026-07-23T08:00:00-04:00"
                }
                """;

        ControlHttpServer.ControlRequest request =
                SupervisorProtocolJson.decodeControlRequest(
                        valid.getBytes(StandardCharsets.UTF_8)
                );

        assertEquals(ControlHttpServer.ControlAction.PAUSE, request.action());
        assertEquals("request-1", request.requestId());
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> SupervisorProtocolJson.decodeControlRequest(
                        valid.replace("\"PAUSE\"", "\"FLY\"")
                                .getBytes(StandardCharsets.UTF_8)
                )
        );
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> SupervisorProtocolJson.decodeControlRequest(
                        valid.replace(
                                "\"request_id\": \"request-1\",",
                                "\"request_id\":\"one\",\"request_id\":\"two\","
                        ).getBytes(StandardCharsets.UTF_8)
                )
        );
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> SupervisorProtocolJson.decodeControlRequest(
                        valid.replace(
                                "\"sent_at\": \"2026-07-23T08:00:00-04:00\"",
                                "\"sent_at\": \"not-a-time\""
                        ).getBytes(StandardCharsets.UTF_8)
                )
        );
    }

    @Test
    void boundedReaderStopsAfterLimit() throws IOException {
        byte[] input = new byte[33];
        assertThrows(
                SupervisorProtocolJson.ProtocolException.class,
                () -> SupervisorProtocolJson.readBounded(
                        new ByteArrayInputStream(input),
                        32
                )
        );
        assertArrayEquals(
                new byte[32],
                SupervisorProtocolJson.readBounded(
                        new ByteArrayInputStream(new byte[32]),
                        32
                )
        );
    }

    private static RecoveryAdvice decodeAdvice(
            String action,
            String incidentId,
            String extraField
    ) throws IOException {
        String response = """
                {
                  "incident_id": "%s",
                  "action": "%s",
                  "reason": "Fixed local reason.",
                  "source": "disabled",
                  "used_fallback": false%s
                }
                """.formatted(incidentId, action, extraField);
        return SupervisorProtocolJson.decodeAdvice(
                bytes(response),
                SupervisorProtocolJson.MAX_RESPONSE_BYTES,
                "incident-1"
        );
    }

    private static RecoveryIncident incident() {
        return new RecoveryIncident(
                "plan-secret",
                1,
                BuildPhase.TILL,
                30,
                false,
                MaterialQuantities.empty(),
                "Path stalled",
                true,
                true
        );
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void progressReportsTotalsStagesAndChunkLetters() {
        ScheduleProgress progress = layerProgress(1, 1);
        JsonObject json = progressJson(SupervisorProtocolJson.encodeProgress(progress, 7));
        assertEquals(1, json.get("protocol_version").getAsInt());
        assertTrue(json.get("available").getAsBoolean());
        assertEquals(7, json.get("revision").getAsLong());
        assertEquals(progress.planId(), json.get("plan_id").getAsString());
        assertEquals(LayerBuildSchedule.ID, json.get("schedule_id").getAsString());
        JsonObject layout = json.getAsJsonObject("layout");
        assertEquals(0, layout.get("origin_x").getAsInt());
        assertEquals(0, layout.get("origin_z").getAsInt());
        assertEquals(7, layout.get("columns").getAsInt());
        assertEquals(7, layout.get("rows").getAsInt());
        JsonObject totals = json.getAsJsonObject("totals");
        assertEquals(2, totals.get("actions").getAsLong());
        assertEquals(1, totals.get("done").getAsLong());
        assertEquals(1, totals.get("stages").getAsInt());
        assertEquals(1, totals.get("current_stage").getAsInt());
        JsonObject materials = json.getAsJsonObject("materials");
        assertEquals(1, materials.size());
        assertEquals(2, materials.getAsJsonObject("dirt").get("planned").getAsLong());
        assertEquals(1, materials.getAsJsonObject("dirt").get("done").getAsLong());
        JsonObject stage = json.getAsJsonArray("stages").get(0).getAsJsonObject();
        assertEquals("STRUCTURE", stage.get("kind").getAsString());
        assertEquals(0, stage.get("y").getAsInt());
        assertEquals(2, stage.get("actions").getAsLong());
        assertEquals(1, stage.get("done").getAsLong());
        assertEquals("DC" + ".".repeat(47), stage.get("chunks").getAsString());
        assertFalse(json.get("chunk_detail_truncated").getAsBoolean());
    }

    @Test
    void finishedProgressHasNoCurrentStage() {
        JsonObject json = progressJson(SupervisorProtocolJson.encodeProgress(layerProgress(1, 2), 8));
        assertTrue(json.getAsJsonObject("totals").get("current_stage").isJsonNull());
    }

    @Test
    void largeProgressKeepsOnlyTheCurrentStageChunkDetail() {
        JsonObject json = progressJson(SupervisorProtocolJson.encodeProgress(layerProgress(3, 2), 3, 3 * 96L));
        assertTrue(json.get("chunk_detail_truncated").getAsBoolean());
        JsonArray stages = json.getAsJsonArray("stages");
        assertTrue(stages.get(0).getAsJsonObject().get("chunks").isJsonNull());
        assertEquals("C-" + ".".repeat(47), stages.get(1).getAsJsonObject().get("chunks").getAsString());
        assertTrue(stages.get(2).getAsJsonObject().get("chunks").isJsonNull());
    }

    @Test
    void progressTooLargeEvenWithoutChunkDetailIsReportedUnavailable() {
        JsonObject json = progressJson(SupervisorProtocolJson.encodeProgress(layerProgress(3, 0), 4, 2 * 96L));
        assertFalse(json.get("available").getAsBoolean());
        assertEquals(4, json.get("revision").getAsLong());
        assertEquals("The plan has too many stages to report progress.", json.get("reason").getAsString());
    }

    @Test
    void unavailableProgressCarriesTheReasonAndRevision() {
        JsonObject json = progressJson(SupervisorProtocolJson.encodeProgressUnavailable("No plan is loaded.", 5));
        assertEquals(1, json.get("protocol_version").getAsInt());
        assertFalse(json.get("available").getAsBoolean());
        assertEquals(5, json.get("revision").getAsLong());
        assertEquals("No plan is loaded.", json.get("reason").getAsString());
    }

    @Test
    void observationCarriesProgressRevisionAndLastProgressTimeThroughLaterTelemetry() {
        AgentObservation base = new AgentObservation("run-1", "BUILDING", NOW, true, true, true,
                List.of(), List.of("STOP"), null, null, null, "", "Ready", "Idle", 0, "", "");
        JsonObject before = progressJson(SupervisorProtocolJson.encodeObservation(base));
        assertEquals(0, before.get("progress_revision").getAsLong());
        assertTrue(before.get("last_progress_at").isJsonNull());
        AgentObservation updated = base.withProgress(12, NOW.minusSeconds(4)).withDepots(null);
        JsonObject after = progressJson(SupervisorProtocolJson.encodeObservation(updated));
        assertEquals(12, after.get("progress_revision").getAsLong());
        assertEquals("2026-07-23T11:59:56Z", after.get("last_progress_at").getAsString());
    }

    /** One STRUCTURE stage per layer, each with work in chunks 0 and 1. */
    private static ScheduleProgress layerProgress(int layers, int cursor) {
        List<TargetBlock> targets = new java.util.ArrayList<>();
        for (int y = 0; y < layers; y++) {
            targets.add(new TargetBlock(new BlockPosition(0, y, 0), new BlockState("minecraft:dirt")));
            targets.add(new TargetBlock(new BlockPosition(16, y, 0), new BlockState("minecraft:dirt")));
        }
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets);
        return ScheduleProgress.of(ScheduleProgress.index(plan, new LayerBuildSchedule(plan)), cursor, -1);
    }

    private static JsonObject progressJson(byte[] encoded) {
        return JsonParser.parseString(new String(encoded, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
