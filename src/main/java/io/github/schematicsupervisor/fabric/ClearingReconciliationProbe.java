package io.github.schematicsupervisor.fabric;

import static io.github.schematicsupervisor.fabric.PlacementReconciliationProbe.readBounded;
import static io.github.schematicsupervisor.fabric.PlacementReconciliationProbe.sha256;
import static io.github.schematicsupervisor.fabric.PlacementReconciliationProbe.strictObject;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.WorkOrder;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/** Optional passive evidence only. This probe never settles an operation or changes game state. */
final class ClearingReconciliationProbe {
    static final int REQUEST_LIMIT = PlacementReconciliationProbe.REQUEST_LIMIT;
    private static final int CHECKPOINT_LIMIT = 65_536;
    private static final int OUTPUT_LIMIT = 65_536;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).serializeNulls().create();
    private final Path directory;
    private final Path output;
    private final String sessionId = UUID.randomUUID().toString();
    private final Instant sessionStarted = Instant.now();
    private final Instant processStarted = ProcessHandle.current().info().startInstant().orElse(null);
    private final Cadence cadence = new Cadence();
    private Request request;
    private JsonObject requestJson;
    private String requestHash;
    private String startupProblem = "";
    private boolean enabled;
    private Object world;
    private Object player;
    private Object connection;
    private long contextGeneration;
    private Instant connectionStarted;

    static ClearingReconciliationProbe open(Path directory) {
        try { return new ClearingReconciliationProbe(directory); }
        catch (RuntimeException unavailable) { return null; }
    }

    ClearingReconciliationProbe(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        output = this.directory.resolve("clearing-reconciliation-observation.json");
        Path input = this.directory.resolve("clearing-reconciliation-probe.json");
        try {
            enabled = Files.exists(input, LinkOption.NOFOLLOW_LINKS);
            if (!enabled) { return; }
            invalidate();
            byte[] bytes = readBounded(input, REQUEST_LIMIT);
            requestHash = sha256(bytes);
            requestJson = strictObject(bytes);
            request = parseRequest(requestJson);
        } catch (IOException | RuntimeException invalid) {
            enabled = true;
            startupProblem = "Probe request is invalid or unavailable";
        }
    }

    boolean enabled() { return enabled; }

    void invalidate() {
        try {
            if (Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(output); }
        } catch (IOException | RuntimeException unavailable) {
            // A stale file remains distinguishable by its session and timestamp; never touch game state.
        }
    }

    void tick(MinecraftClient client, String state, boolean automationIdle, boolean pendingTransactions,
              Path activeCheckpoint, SchematicPlan activePlan) {
        if (!enabled || !client.isOnThread()) { return; }
        try {
            observeBinding(client);
            if (!("IDLE".equals(state) || "PAUSED".equals(state)) || !automationIdle) {
                invalidate();
                return;
            }
            if (!cadence.due(state, automationIdle, System.nanoTime())) { return; }
            JsonObject response = capture(client, state, automationIdle, pendingTransactions,
                    activeCheckpoint, activePlan);
            writeObservation(response);
        } catch (IOException | RuntimeException unavailable) {
            // A diagnostic failure never interrupts the supervisor or changes any journal.
        }
    }

    private void observeBinding(MinecraftClient client) {
        Object currentConnection = client.getNetworkHandler();
        if (world != client.world || player != client.player || connection != currentConnection) {
            world = client.world;
            player = client.player;
            connection = currentConnection;
            contextGeneration++;
            // First observed binding, not a fabricated packet or handshake timestamp.
            connectionStarted = world != null && player != null && connection != null ? Instant.now() : null;
        }
    }

    private JsonObject capture(MinecraftClient client, String state, boolean automationIdle,
                               boolean pendingTransactions, Path activeCheckpoint, SchematicPlan activePlan) {
        JsonObject result = new JsonObject();
        result.addProperty("version", 1);
        result.add("request", requestJson == null ? JsonNull.INSTANCE : requestJson.deepCopy());
        result.addProperty("request_sha256", requestHash);
        result.addProperty("request_id", request == null ? null : request.requestId());
        result.addProperty("session_id", sessionId);
        result.addProperty("process_id", ProcessHandle.current().pid());
        result.addProperty("session_started_at", sessionStarted.toString());
        result.addProperty("process_started_at", processStarted == null ? null : processStarted.toString());
        result.addProperty("connection_started_at", connectionStarted == null ? null : connectionStarted.toString());
        result.addProperty("captured_at", Instant.now().toString());
        result.addProperty("supervisor_state", state);
        result.addProperty("automation_idle", automationIdle);
        result.addProperty("pending_transactions", pendingTransactions);
        result.addProperty("context_generation", contextGeneration);
        List<String> problems = new ArrayList<>();
        if (!startupProblem.isEmpty()) { problems.add(startupProblem); }
        if (pendingTransactions) { problems.add("An inventory or interaction transaction is pending"); }
        boolean connected = client.world != null && client.player != null && client.getNetworkHandler() != null
                && client.getNetworkHandler().isConnectionOpen();
        boolean playerHandler = connected && client.player.currentScreenHandler == client.player.playerScreenHandler;
        boolean cursorEmpty = connected && client.player.currentScreenHandler.getCursorStack().isEmpty();
        result.addProperty("connected_world", connected);
        result.addProperty("player_handler", playerHandler);
        result.addProperty("cursor_empty", cursorEmpty);
        result.addProperty("player_uuid", connected ? client.player.getUuid().toString() : null);
        if (!connected) { problems.add("Client world, player or connection is unavailable"); }
        if (!playerHandler || !cursorEmpty) { problems.add("Player inventory handler and empty cursor are required"); }
        result.addProperty("server_inventory_complete", false);
        result.addProperty("server_inventory_all_match", false);
        result.add("slots", new JsonArray());
        if (request != null) {
            if (!freshSession(request, processStarted, sessionStarted, sessionId)) {
                problems.add("A new client process and probe session after the request are required");
            }
            try {
                Path checkpoint = directory.resolve("checkpoint.json");
                if (!checkpoint.equals(activeCheckpoint.toAbsolutePath().normalize())) {
                    throw new IOException("Active checkpoint path differs from the fixed requested checkpoint");
                }
                byte[] before = readBounded(checkpoint, CHECKPOINT_LIMIT);
                String checkpointHash = sha256(before);
                String planId = requiredString(strictObject(before), "plan_id");
                byte[] savedBytes = readBounded(directory.resolve("run-context.json"), REQUEST_LIMIT);
                JsonObject savedJson = strictObject(savedBytes);
                requireFields(savedJson, Set.of("schemaVersion", "worldIdentityHash", "dimension"), Set.of());
                if (integer(savedJson, "schemaVersion") != 1) { throw new IOException("Unsupported saved context"); }
                RunContext saved = new RunContext(requiredString(savedJson, "worldIdentityHash"),
                        requiredString(savedJson, "dimension"));
                RunContext current = connected ? MinecraftRunContext.capture(client) : null;
                String playerId = connected ? client.player.getUuid().toString() : null;
                result.addProperty("checkpoint_sha256", checkpointHash);
                result.addProperty("checkpoint_matches", request.checkpointHash().equals(checkpointHash));
                result.addProperty("plan_id", planId);
                result.addProperty("plan_matches", request.planId().equals(planId)
                        && activePlan != null && activePlan.planId().equals(planId));
                result.add("saved_run_context", contextJson(saved));
                result.add("current_run_context", contextJson(current));
                result.addProperty("context_matches", request.context().equals(saved) && saved.equals(current));
                result.addProperty("player_matches", request.playerId().equals(playerId));
                String binding = bindingProblem(request, checkpointHash, planId, saved, current, playerId);
                if (!binding.isEmpty()) { problems.add(binding); }
                boolean targetMatches = planTargetMatches(request, strictObject(before), activePlan);
                result.addProperty("plan_target_matches", targetMatches);
                if (!targetMatches) { problems.add("Loaded plan or exact scheduled replacement target differs"); }
                if (connected) { captureWorldAndInventory(client, result, problems); }
                // Refuse a torn pairing if an external writer changed the bound files during capture.
                if (!checkpointHash.equals(sha256(readBounded(checkpoint, CHECKPOINT_LIMIT)))
                        || !sha256(savedBytes).equals(sha256(readBounded(directory.resolve("run-context.json"), REQUEST_LIMIT)))) {
                    problems.add("Bound checkpoint or saved context changed during capture");
                }
            } catch (IOException | RuntimeException unavailable) {
                problems.add("Bound checkpoint, context or live evidence is unavailable");
            }
        }
        result.addProperty("available", request != null && problems.isEmpty());
        result.addProperty("unavailable_reason", String.join("; ", problems));
        return result;
    }

    private void captureWorldAndInventory(MinecraftClient client, JsonObject result, List<String> problems) {
        BlockPosition position = request.target();
        BlockPos target = new BlockPos(position.x(), position.y(), position.z());
        boolean received = target.getY() >= client.world.getBottomY() && target.getY() <= client.world.getTopYInclusive()
                && ClientChunkAvailability.isLoaded(client.world, target);
        JsonObject targetJson = new JsonObject();
        targetJson.addProperty("x", position.x());
        targetJson.addProperty("y", position.y());
        targetJson.addProperty("z", position.z());
        targetJson.addProperty("expected_block", request.expectedBlock());
        targetJson.addProperty("observed_block", request.observedBlock());
        JsonObject actualState = null;
        if (received) {
            var state = MinecraftBlockStates.toCore(client.world.getBlockState(target));
            actualState = new JsonObject();
            actualState.addProperty("block_id", actualBlockId(client, target));
            JsonObject properties = new JsonObject();
            state.properties().forEach(properties::addProperty);
            actualState.add("properties", properties);
        }
        targetJson.add("actual_state", actualState == null ? JsonNull.INSTANCE : actualState);
        targetJson.addProperty("chunk_received", received);
        String actualBlock = received ? Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).toString() : null;
        Boolean prediction = null;
        if (received) {
            var manager = ((ClientWorldPendingUpdatesAccessor) client.world).supervisor$getPendingUpdateManager();
            prediction = ((PendingBlockUpdatesAccessor) manager).supervisor$getPendingBlockUpdates().containsKey(target.asLong());
        }
        targetJson.addProperty("actual_block", actualBlock);
        targetJson.addProperty("prediction_pending", prediction);
        result.add("target", targetJson);
        if (!received || !Boolean.FALSE.equals(prediction)) { problems.add("Target is unreceived or has pending prediction"); }
        if (!acceptsTarget(request, actualBlock)) { problems.add("Target is neither the requested obstruction nor ordinary AIR"); }

        var marker = ServerPlayerInventoryObserver.mark(client);
        result.addProperty("observer_epoch", marker.epoch());
        result.addProperty("observer_sequence", marker.sequence());
        List<SlotEvidence> evidence = new ArrayList<>();
        JsonArray slots = new JsonArray();
        long total = 0;
        Map<Integer, Integer> replacementSlots = new HashMap<>();
        boolean replacementPlain = true;
        if (client.player.getInventory().getMainStacks().size() != 36) {
            throw new IllegalStateException("Main inventory does not contain exactly 36 slots");
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = client.player.getInventory().getStack(slot);
            if (MinecraftMaterials.matches(current, request.material())) {
                total = Math.addExact(total, current.getCount());
                replacementSlots.put(slot, current.getCount());
                replacementPlain &= PlainInteractionItems.plainBlock(request.material()).test(current);
            }
            var update = ServerPlayerInventoryObserver.latest(client, slot).orElse(null);
            boolean matches = update != null && ItemStack.areEqual(update.stack(), current);
            evidence.add(new SlotEvidence(slot, update != null, matches,
                    update == null ? null : update.stamp().epoch(), update == null ? 0 : update.stamp().sequence()));
            JsonObject item = new JsonObject();
            item.addProperty("slot", slot);
            item.addProperty("received", update != null);
            item.addProperty("matches_current", matches);
            item.addProperty("current_item_id", Registries.ITEM.getId(current.getItem()).toString());
            item.addProperty("current_count", current.getCount());
            item.addProperty("current_plain_replacement", PlainInteractionItems.plainBlock(request.material()).test(current));
            item.addProperty("server_item_id", update == null ? null : Registries.ITEM.getId(update.stack().getItem()).toString());
            item.addProperty("server_count", update == null ? null : update.stack().getCount());
            item.addProperty("observer_epoch", update == null ? null : update.stamp().epoch());
            item.addProperty("sequence", update == null ? null : update.stamp().sequence());
            slots.add(item);
        }
        InventoryEvidence summary = inventoryEvidence(evidence, marker.epoch(), marker.sequence());
        result.add("slots", slots);
        result.addProperty("actual_inventory_total", total);
        result.addProperty("replacement_slots_plain", replacementPlain);
        result.addProperty("replacement_slots_match", replacementSlotsMatch(request, replacementSlots, replacementPlain));
        if (!replacementSlotsMatch(request, replacementSlots, replacementPlain)) {
            problems.add("Plain replacement slot identities or quantities changed");
        }
        // Complete means all 36 applied per-slot receipts, not one atomic full inventory packet.
        result.addProperty("server_inventory_complete", summary.complete());
        result.addProperty("server_inventory_all_match", summary.allMatch());
        if (!summary.complete() || !summary.allMatch()) { problems.add("Applied server evidence is missing or differs in one or more inventory slots"); }
        int selected = client.player.getInventory().getSelectedSlot();
        ItemStack selectedStack = client.player.getInventory().getStack(request.selectedSlot());
        var selectedReceipt = ServerPlayerInventoryObserver.latest(client, request.selectedSlot()).orElse(null);
        boolean selectedPlain = PlainInteractionItems.plainBlock(request.material()).test(selectedStack);
        boolean selectedMatches = selectedReceipt != null && ItemStack.areEqual(selectedReceipt.stack(), selectedStack)
                && selectedStack.getCount() == request.replacementSlots().get(request.selectedSlot());
        boolean outside = false;
        var inventory = client.player.getInventory();
        if (inventory.size() != 43) { throw new IllegalStateException("Unexpected player equipment shape"); }
        for (int slot = 36; slot < inventory.size(); slot++) {
            outside |= MinecraftMaterials.matches(inventory.getStack(slot), request.material());
        }
        for (int slot = 0; slot <= 4; slot++) {
            outside |= MinecraftMaterials.matches(client.player.playerScreenHandler.getSlot(slot).getStack(), request.material());
        }
        outside |= MinecraftMaterials.matches(client.player.currentScreenHandler.getCursorStack(), request.material())
                || MinecraftMaterials.matches(client.player.playerScreenHandler.getCursorStack(), request.material());
        result.addProperty("selected_slot", selected);
        result.addProperty("selected_slot_plain", selectedPlain);
        result.addProperty("selected_slot_matches", selectedMatches);
        result.addProperty("replacement_outside_main", outside);
        if (!replacementMatches(request, selected, selectedPlain, selectedMatches, outside, total)) {
            problems.add("The original plain replacement hand or unchanged inventory is not proven");
        }
    }

    static String bindingProblem(Request request, String checkpointHash, String planId, RunContext saved,
                                 RunContext current, String playerId) {
        if (!request.checkpointHash().equals(checkpointHash)) { return "Checkpoint hash differs"; }
        if (!request.planId().equals(planId)) { return "Checkpoint plan differs"; }
        if (!request.context().equals(saved) || !request.context().equals(current)) { return "Saved or current world context differs"; }
        if (!request.playerId().equals(playerId)) { return "Player identity differs"; }
        return "";
    }

    static boolean freshSession(Request request, Instant processStarted, Instant sessionStarted, String sessionId) {
        return processStarted != null && processStarted.isAfter(request.createdAt())
                && !sessionStarted.isBefore(processStarted) && sessionStarted.isAfter(request.createdAt())
                && !sessionId.equals(request.oldSessionId());
    }

    static InventoryEvidence inventoryEvidence(List<SlotEvidence> slots, String epoch, long maximumSequence) {
        boolean complete = slots.size() == 36;
        boolean matches = true;
        Set<Integer> seen = new HashSet<>();
        for (SlotEvidence slot : slots) {
            complete &= slot.slot() >= 0 && slot.slot() < 36 && seen.add(slot.slot()) && slot.received()
                    && epoch != null && epoch.equals(slot.epoch()) && slot.sequence() > 0 && slot.sequence() <= maximumSequence;
            matches &= slot.matches();
        }
        return new InventoryEvidence(complete, complete && matches);
    }

    static Request parseRequest(JsonObject json) {
        int version = integer(json, "version");
        if (version < 1 || version > 3) { throw new IllegalArgumentException("Unsupported clearing request version"); }
        Set<String> required = new HashSet<>(Set.of("version", "request_id", "created_at", "checkpoint_sha256", "plan_id",
                "saved_run_context", "player_uuid", "target", "observed_block", "expected_block", "material",
                "inventory_before", "expected_inventory_after", "selected_slot"));
        if (version >= 2) { required.add("replacement_slots"); }
        requireFields(json, required, Set.of("old_session_id"));
        int before = integer(json, "inventory_before"), after = integer(json, "expected_inventory_after");
        int selected = integer(json, "selected_slot");
        String observed = requiredString(json, "observed_block");
        String expected = requiredString(json, "expected_block");
        String material = version == 1 ? "glowstone" : "dirt";
        String obstruction = switch (version) {
            case 1 -> "minecraft:jack_o_lantern";
            case 2 -> "minecraft:moss_block";
            default -> "minecraft:air";
        };
        if (!material.equals(requiredString(json, "material"))
                || !obstruction.equals(observed) || !"minecraft:air".equals(expected)
                || before < 1 || before > (version == 1 ? 64 : 2304)
                || after != before || selected < 0 || selected >= 9) {
            throw new IllegalArgumentException("Only unchanged planned replacement or stem clearing may be probed");
        }
        Map<Integer, Integer> replacementSlots = new HashMap<>();
        if (version == 1) { replacementSlots.put(selected, before); }
        else {
            JsonArray entries = json.getAsJsonArray("replacement_slots");
            if (entries.isEmpty() || entries.size() > 36) { throw new IllegalArgumentException("Invalid replacement slots"); }
            int total = 0;
            for (JsonElement entry : entries) {
                JsonObject slot = entry.getAsJsonObject();
                requireFields(slot, Set.of("slot", "count"), Set.of());
                int index = integer(slot, "slot"), count = integer(slot, "count");
                if (index < 0 || index >= 36 || count < 1 || count > 64
                        || replacementSlots.putIfAbsent(index, count) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate replacement slot");
                }
                total += count;
            }
            if (total != before || !replacementSlots.containsKey(selected)) {
                throw new IllegalArgumentException("Replacement total or selected stack differs");
            }
        }
        // Delegate common strict bindings and bounds to the unchanged consuming request parser.
        JsonObject common = json.deepCopy();
        common.remove("observed_block");
        common.remove("selected_slot");
        common.remove("replacement_slots");
        common.addProperty("version", 1);
        common.addProperty("expected_block", "minecraft:" + material);
        common.addProperty("expected_inventory_after", before - 1);
        var parsed = PlacementReconciliationProbe.parseRequest(common);
        return new Request(parsed.requestId(), parsed.createdAt(), parsed.checkpointHash(), parsed.planId(),
                parsed.context(), parsed.playerId(), parsed.target(), expected, parsed.material(),
                before, after, parsed.oldSessionId(), observed, selected, Map.copyOf(replacementSlots));
    }

    static boolean acceptsTarget(String blockId) {
        return "minecraft:air".equals(blockId) || "minecraft:jack_o_lantern".equals(blockId);
    }

    static boolean acceptsTarget(Request request, String blockId) {
        return "minecraft:air".equals(blockId) || request.observedBlock().equals(blockId);
    }

    static boolean replacementSlotsMatch(Request request, Map<Integer, Integer> slots, boolean plain) {
        return plain && request.replacementSlots().equals(slots);
    }

    static boolean replacementMatches(Request request, int selected, boolean plain, boolean receiptMatches,
                                      boolean outside, long mainTotal) {
        return selected == request.selectedSlot() && plain && receiptMatches && !outside
                && mainTotal == request.inventoryBefore();
    }

    static boolean planTargetMatches(Request request, JsonObject checkpoint, SchematicPlan plan) {
        if (plan == null || !plan.planId().equals(request.planId()) || !plan.buildVolume().contains(request.target())) {
            return false;
        }
        try {
            if (!request.planId().equals(requiredString(checkpoint, "plan_id"))
                    || integer(checkpoint, "version") != 4 || !"PAUSED".equals(requiredString(checkpoint, "state"))
                    || !"BUILDING".equals(requiredString(checkpoint, "resume_state"))
                    || !"ORDINARY_BLOCKS".equals(requiredString(checkpoint, "phase"))) { return false; }
            LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
            int cursor = integer(checkpoint, "schedule_cursor");
            if (!schedule.id().equals(requiredString(checkpoint, "schedule_id"))
                    || cursor < 0 || cursor >= schedule.size()
                    || !checkpoint.get("planting_deferred").isJsonPrimitive()
                    || !checkpoint.get("planting_deferred").getAsJsonPrimitive().isBoolean()
                    || checkpoint.get("planting_deferred").getAsBoolean() != plan.plantingDeferred()
                    || integer(checkpoint, "chunk_count") != plan.chunkCount()) { return false; }
            var entry = schedule.entry(cursor);
            if (!(request.material() == Material.GLOWSTONE ? "LIGHTING" : "STRUCTURE").equals(entry.progress().stage())
                    || !(entry.order() instanceof WorkOrder.OrdinaryBlocks ordinary)
                    || ordinary.chunkIndex() != integer(checkpoint, "current_chunk_index")) { return false; }
            if (request.observedBlock().equals("minecraft:air")) {
                return request.material() == Material.DIRT && plan.plantingDeferred()
                        && new StemClearingSweep(plan, ordinary).allowsSourcePosition(request.target());
            }
            return ordinary.placements().stream().anyMatch(placement -> placement.position().equals(request.target())
                        && placement.material() == request.material()
                        && (request.material() == Material.GLOWSTONE ? "minecraft:glowstone" : "minecraft:dirt").equals(placement.state().blockId())
                        && placement.state().properties().isEmpty());
        } catch (RuntimeException unavailable) { return false; }
    }

    private static String actualBlockId(MinecraftClient client, BlockPos target) {
        return Registries.BLOCK.getId(client.world.getBlockState(target).getBlock()).toString();
    }

    private static JsonElement contextJson(RunContext context) {
        if (context == null) { return JsonNull.INSTANCE; }
        JsonObject value = new JsonObject();
        value.addProperty("world_identity_hash", context.worldIdentityHash());
        value.addProperty("dimension", context.dimension());
        return value;
    }

    private static void requireFields(JsonObject json, Set<String> required, Set<String> optional) {
        Set<String> fields = new HashSet<>(json.keySet());
        fields.removeAll(optional);
        if (!fields.equals(required)) { throw new IllegalArgumentException("Unknown or missing fields"); }
    }

    private static String requiredString(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected a string");
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("String is empty, unbounded or contains controls");
        }
        return text;
    }

    private static int integer(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.toString().matches("-?(0|[1-9][0-9]*)")) { throw new IllegalArgumentException("Expected an integer"); }
        return Integer.parseInt(value.toString());
    }

    private void writeObservation(JsonObject result) throws IOException {
        byte[] bytes = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > OUTPUT_LIMIT || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) { return; }
        Path temporary = Files.createTempFile(directory, "clearing-observation-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    record Request(String requestId, Instant createdAt, String checkpointHash, String planId, RunContext context,
                   String playerId, BlockPosition target, String expectedBlock, Material material,
                   int inventoryBefore, int expectedAfter, String oldSessionId, String observedBlock, int selectedSlot,
                   Map<Integer, Integer> replacementSlots) { }
    record SlotEvidence(int slot, boolean received, boolean matches, String epoch, long sequence) { }
    record InventoryEvidence(boolean complete, boolean allMatch) { }

    static final class Cadence {
        private boolean sampled;
        private long previous;
        boolean due(String state, boolean automationIdle, long now) {
            if (!("IDLE".equals(state) || "PAUSED".equals(state)) || !automationIdle
                    || sampled && now - previous < 1_000_000_000L) { return false; }
            sampled = true;
            previous = now;
            return true;
        }
    }
}
