package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/** Optional passive evidence only. This probe never settles an operation or changes game state. */
final class PlacementReconciliationProbe {
    static final int REQUEST_LIMIT = 4096;
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

    static PlacementReconciliationProbe open(Path directory) {
        try { return new PlacementReconciliationProbe(directory); }
        catch (RuntimeException unavailable) { return null; }
    }

    PlacementReconciliationProbe(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        output = this.directory.resolve("placement-reconciliation-observation.json");
        Path input = this.directory.resolve("placement-reconciliation-probe.json");
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
              Path activeCheckpoint, String activePlanId) {
        if (!enabled || !client.isOnThread()) { return; }
        try {
            observeBinding(client);
            if (!("IDLE".equals(state) || "PAUSED".equals(state)) || !automationIdle) {
                invalidate();
                return;
            }
            if (!cadence.due(state, automationIdle, System.nanoTime())) { return; }
            JsonObject response = capture(client, state, automationIdle, pendingTransactions,
                    activeCheckpoint, activePlanId);
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
                               boolean pendingTransactions, Path activeCheckpoint, String activePlanId) {
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
                        && (activePlanId == null || activePlanId.equals(planId)));
                result.add("saved_run_context", contextJson(saved));
                result.add("current_run_context", contextJson(current));
                result.addProperty("context_matches", request.context().equals(saved) && saved.equals(current));
                result.addProperty("player_matches", request.playerId().equals(playerId));
                String binding = bindingProblem(request, checkpointHash, planId, saved, current, playerId);
                if (!binding.isEmpty()) { problems.add(binding); }
                if (activePlanId != null && !request.planId().equals(activePlanId)) { problems.add("Loaded plan differs"); }
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
        if (!request.expectedBlock().equals(actualBlock)) { problems.add("Target does not match the requested block"); }

        var marker = ServerPlayerInventoryObserver.mark(client);
        result.addProperty("observer_epoch", marker.epoch());
        result.addProperty("observer_sequence", marker.sequence());
        List<SlotEvidence> evidence = new ArrayList<>();
        JsonArray slots = new JsonArray();
        long total = 0;
        if (client.player.getInventory().getMainStacks().size() != 36) {
            throw new IllegalStateException("Main inventory does not contain exactly 36 slots");
        }
        for (int slot = 0; slot < 36; slot++) {
            ItemStack current = client.player.getInventory().getStack(slot);
            if (MinecraftMaterials.matches(current, request.material())) { total = Math.addExact(total, current.getCount()); }
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
            item.addProperty("server_item_id", update == null ? null : Registries.ITEM.getId(update.stack().getItem()).toString());
            item.addProperty("server_count", update == null ? null : update.stack().getCount());
            item.addProperty("observer_epoch", update == null ? null : update.stamp().epoch());
            item.addProperty("sequence", update == null ? null : update.stamp().sequence());
            slots.add(item);
        }
        InventoryEvidence summary = inventoryEvidence(evidence, marker.epoch(), marker.sequence());
        result.add("slots", slots);
        result.addProperty("actual_inventory_total", total);
        // Complete means all 36 applied per-slot receipts, not one atomic full inventory packet.
        result.addProperty("server_inventory_complete", summary.complete());
        result.addProperty("server_inventory_all_match", summary.allMatch());
        if (!summary.complete() || !summary.allMatch()) { problems.add("Applied server evidence is missing or differs in one or more inventory slots"); }
        if (total != request.expectedAfter()) { problems.add("Main inventory total differs from the requested post-placement count"); }
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
        requireFields(json, Set.of("version", "request_id", "created_at", "checkpoint_sha256", "plan_id",
                "saved_run_context", "player_uuid", "target", "expected_block", "material", "inventory_before",
                "expected_inventory_after"), Set.of("old_session_id"));
        if (integer(json, "version") != 1) { throw new IllegalArgumentException("Unsupported probe version"); }
        String requestId = canonicalUuid(requiredString(json, "request_id"));
        String created = requiredString(json, "created_at");
        if (!created.endsWith("Z")) { throw new IllegalArgumentException("created_at must be UTC"); }
        Instant createdAt = Instant.parse(created);
        String checkpointHash = requiredString(json, "checkpoint_sha256");
        String planId = requiredString(json, "plan_id");
        if (!checkpointHash.matches("[0-9a-f]{64}") || !planId.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid checkpoint or plan hash");
        }
        JsonObject context = json.getAsJsonObject("saved_run_context");
        requireFields(context, Set.of("world_identity_hash", "dimension"), Set.of());
        String dimension = requiredString(context, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) { throw new IllegalArgumentException("Noncanonical dimension"); }
        RunContext runContext = new RunContext(requiredString(context, "world_identity_hash"), dimension);
        String playerId = canonicalUuid(requiredString(json, "player_uuid"));
        JsonObject target = json.getAsJsonObject("target");
        requireFields(target, Set.of("x", "y", "z"), Set.of());
        BlockPosition position = new BlockPosition(integer(target, "x"), integer(target, "y"), integer(target, "z"));
        if (Math.abs((long) position.x()) >= 30_000_000 || Math.abs((long) position.z()) >= 30_000_000
                || position.y() < -2048 || position.y() > 2047) { throw new IllegalArgumentException("Target is out of bounds"); }
        String materialName = requiredString(json, "material");
        Material material = switch (materialName) {
            case "dirt" -> Material.DIRT;
            case "glowstone" -> Material.GLOWSTONE;
            case "birch_planks" -> Material.BIRCH_PLANKS;
            default -> throw new IllegalArgumentException("Only ordinary structural materials may be probed");
        };
        String expected = requiredString(json, "expected_block");
        if (!expected.equals("minecraft:" + materialName)) { throw new IllegalArgumentException("Expected block and material differ"); }
        int before = integer(json, "inventory_before"), after = integer(json, "expected_inventory_after");
        if (before < 1 || before > 36 * 64 || after != before - 1) { throw new IllegalArgumentException("Exactly one consumed item is required"); }
        String oldSession = json.has("old_session_id") ? canonicalUuid(requiredString(json, "old_session_id")) : null;
        return new Request(requestId, createdAt, checkpointHash, planId, runContext, playerId, position,
                expected, material, before, after, oldSession);
    }

    private static JsonElement contextJson(RunContext context) {
        if (context == null) { return JsonNull.INSTANCE; }
        JsonObject value = new JsonObject();
        value.addProperty("world_identity_hash", context.worldIdentityHash());
        value.addProperty("dimension", context.dimension());
        return value;
    }

    static byte[] readBounded(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) {
            throw new IOException("Evidence must be a bounded regular file");
        }
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) { throw new IOException("Evidence exceeds its size limit"); }
            return bytes;
        }
    }

    static JsonObject strictObject(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) {
                throw new IOException("Expected one complete JSON object");
            }
            return value.getAsJsonObject();
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 8) { throw new IOException("JSON nesting exceeds its limit"); }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) { throw new IOException("Duplicate JSON field"); }
                    object.add(name, readJson(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) { array.add(readJson(reader, depth + 1)); }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON value");
        };
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

    private static String canonicalUuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) { throw new IllegalArgumentException("Noncanonical UUID"); }
        return value;
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void writeObservation(JsonObject result) throws IOException {
        byte[] bytes = GSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > OUTPUT_LIMIT || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) { return; }
        Path temporary = Files.createTempFile(directory, "placement-observation-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    record Request(String requestId, Instant createdAt, String checkpointHash, String planId, RunContext context,
                   String playerId, BlockPosition target, String expectedBlock, Material material,
                   int inventoryBefore, int expectedAfter, String oldSessionId) { }
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
