package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.schematicsupervisor.core.BuildCheck;
import io.github.schematicsupervisor.core.LayerProgress;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RecoveryAdvice;
import io.github.schematicsupervisor.core.RecoveryIncident;
import io.github.schematicsupervisor.core.ScheduleProgress;
import io.github.schematicsupervisor.core.SupervisorStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Strict, bounded JSON mapping for the loopback companion protocol.
 */
final class SupervisorProtocolJson {
    static final int MAX_RESPONSE_BYTES = 16_384;
    static final int MAX_CONTROL_REQUEST_BYTES = 16_384;
    private static final int MAX_OUTBOUND_BYTES = 65_536;
    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final Gson GSON = MaterialGson.register(new GsonBuilder()).create();
    private static final Gson OBSERVATION_GSON = MaterialGson.register(new GsonBuilder()).serializeNulls().create();

    private SupervisorProtocolJson() {
    }

    static EncodedIncident encodeIncident(RecoveryIncident incident, Instant createdAt) {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(createdAt, "createdAt");

        String incidentId = opaqueIncidentId(incident);
        JsonObject root = new JsonObject();
        root.addProperty("incident_id", incidentId);
        root.addProperty("category", "RECOVERY_EXHAUSTED");
        root.addProperty("summary", boundedOrFallback(
                incident.lastError(),
                "Deterministic recovery was exhausted"
        ));
        root.addProperty("recovery_exhausted", true);
        root.add("status", incidentStatus(incident, createdAt));

        JsonArray attempted = new JsonArray();
        attempted.add("STOP_MOVEMENT");
        if (incident.serverLagging()) {
            attempted.add("WAIT_FOR_LAG");
        }
        if (incident.repathAttempted()) {
            attempted.add("RESTART_PATH");
        }
        if (incident.safeReturnAttempted()) {
            attempted.add("RETURN_TO_SAFE_POSITION");
        }
        root.add("attempted_recovery", attempted);

        JsonObject details = new JsonObject();
        details.addProperty("stalled_seconds", incident.stalledSeconds());
        details.addProperty("server_lag_suspected", incident.serverLagging());
        details.add("missing_materials", materialCounts(incident.missingMaterials()));
        root.add("details", details);
        root.addProperty("created_at", DateTimeFormatter.ISO_INSTANT.format(createdAt));
        return new EncodedIncident(incidentId, encode(root));
    }

    static byte[] encodeStatus(
            SupervisorStatus status,
            String baritoneStatus,
            Instant updatedAt
    ) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");

        JsonObject root = new JsonObject();
        if (status.currentChunkOrdinal() == 0) {
            root.add("current_chunk", null);
        } else {
            JsonObject chunk = new JsonObject();
            chunk.addProperty("index", status.currentChunkOrdinal());
            chunk.addProperty("total", status.chunkTotal());
            root.add("current_chunk", chunk);
        }
        root.add("current_layer", layerProgress(status.layerProgress()));
        root.addProperty("planting_deferred", status.plantingDeferred());
        root.addProperty("glowstone_after_structure", status.glowstoneAfterStructure());
        root.addProperty("deferred_seed_cells", status.deferredSeedCells());
        root.addProperty("phase", status.state().name() + ":" + status.phase().name());
        root.add("materials", statusMaterials(status.inventory(), status.missingMaterials()));
        root.addProperty(
                "baritone_status",
                boundedOrFallback(baritoneStatus, "Unavailable")
        );
        if (status.lastError().isBlank()) {
            root.add("last_error", null);
        } else {
            root.addProperty("last_error", boundedOrFallback(status.lastError(), "Unknown error"));
        }
        root.addProperty("updated_at", DateTimeFormatter.ISO_INSTANT.format(updatedAt));
        return encode(root, OBSERVATION_GSON);
    }

    static RecoveryAdvice decodeAdvice(
            InputStream stream,
            int maximumBytes,
            String expectedIncidentId
    ) throws IOException {
        String json = decodeUtf8(readBounded(stream, maximumBytes));
        try (JsonReader reader = strictReader(json)) {
            require(reader, JsonToken.BEGIN_OBJECT, "diagnosis response must be an object");
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            String incidentId = null;
            String action = null;
            String reason = null;
            String source = null;
            Boolean usedFallback = null;
            while (reader.hasNext()) {
                String name = uniqueName(reader, fields);
                switch (name) {
                    case "incident_id" ->
                            incidentId = requiredString(reader, "incident_id", 128);
                    case "action" -> action = requiredString(reader, "action", 64);
                    case "reason" -> reason = requiredString(reader, "reason", 1_000);
                    case "source" -> source = requiredString(reader, "source", 128);
                    case "used_fallback" ->
                            usedFallback = requiredBoolean(reader, "used_fallback");
                    default -> throw new ProtocolException(
                            "diagnosis response contains an unsupported field"
                    );
                }
            }
            reader.endObject();
            requireEnd(reader);
            requirePresent(incidentId, "incident_id");
            requirePresent(action, "action");
            requirePresent(reason, "reason");
            requirePresent(source, "source");
            requirePresent(usedFallback, "used_fallback");
            if (!MessageDigest.isEqual(
                    expectedIncidentId.getBytes(StandardCharsets.UTF_8),
                    incidentId.getBytes(StandardCharsets.UTF_8)
            )) {
                throw new ProtocolException("diagnosis incident_id does not match the request");
            }
            try {
                return RecoveryAdvice.valueOf(action);
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException("diagnosis action is not allowlisted", exception);
            }
        }
    }

    static boolean decodeStatusAcknowledgement(InputStream stream, int maximumBytes)
            throws IOException {
        String json = decodeUtf8(readBounded(stream, maximumBytes));
        try (JsonReader reader = strictReader(json)) {
            require(reader, JsonToken.BEGIN_OBJECT, "status response must be an object");
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            Boolean accepted = null;
            String updatedAt = null;
            while (reader.hasNext()) {
                String name = uniqueName(reader, fields);
                switch (name) {
                    case "accepted" -> accepted = requiredBoolean(reader, "accepted");
                    case "updated_at" ->
                            updatedAt = requiredString(reader, "updated_at", 128);
                    default -> throw new ProtocolException(
                            "status response contains an unsupported field"
                    );
                }
            }
            reader.endObject();
            requireEnd(reader);
            requirePresent(accepted, "accepted");
            requirePresent(updatedAt, "updated_at");
            parseOffsetTimestamp(updatedAt, "updated_at");
            return accepted;
        }
    }

    static ControlHttpServer.ControlRequest decodeControlRequest(byte[] body) {
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_CONTROL_REQUEST_BYTES) {
            throw new ProtocolException("control request exceeds the size limit");
        }
        String json = decodeUtf8(body);
        try (JsonReader reader = strictReader(json)) {
            require(reader, JsonToken.BEGIN_OBJECT, "control request must be an object");
            reader.beginObject();
            Set<String> fields = new HashSet<>();
            String action = null;
            String requestId = null;
            String sentAt = null;
            String expectedRunId = null;
            String expectedState = null;
            Long expectedControlSequence = null;
            while (reader.hasNext()) {
                String name = uniqueName(reader, fields);
                switch (name) {
                    case "action" -> action = requiredString(reader, "action", 16);
                    case "request_id" ->
                            requestId = requiredString(reader, "request_id", 128);
                    case "sent_at" -> sentAt = requiredString(reader, "sent_at", 128);
                    case "expected_run_id" ->
                            expectedRunId = requiredString(reader, "expected_run_id", 128);
                    case "expected_state" ->
                            expectedState = requiredString(reader, "expected_state", 128);
                    case "expected_control_sequence" -> {
                        require(reader, JsonToken.NUMBER,
                                "expected_control_sequence must be a nonnegative integer");
                        String value = reader.nextString();
                        if (!value.matches("0|[1-9][0-9]{0,18}")) {
                            throw new ProtocolException(
                                    "expected_control_sequence must be a nonnegative integer");
                        }
                        try {
                            expectedControlSequence = Long.parseLong(value);
                        } catch (NumberFormatException exception) {
                            throw new ProtocolException("expected_control_sequence is too large", exception);
                        }
                    }
                    default -> throw new ProtocolException(
                            "control request contains an unsupported field"
                    );
                }
            }
            reader.endObject();
            requireEnd(reader);
            requirePresent(action, "action");
            requirePresent(requestId, "request_id");
            requirePresent(sentAt, "sent_at");
            if ((expectedRunId == null) != (expectedState == null)) {
                throw new ProtocolException("expected_run_id and expected_state must be supplied together");
            }
            ControlHttpServer.ControlAction parsedAction;
            try {
                parsedAction = ControlHttpServer.ControlAction.valueOf(action);
            } catch (IllegalArgumentException exception) {
                throw new ProtocolException("control action is not allowlisted", exception);
            }
            return new ControlHttpServer.ControlRequest(
                    parsedAction,
                    requestId,
                    parseOffsetTimestamp(sentAt, "sent_at"),
                    expectedRunId,
                    expectedState,
                    expectedControlSequence
            );
        } catch (IOException exception) {
            throw new ProtocolException("control request could not be parsed", exception);
        }
    }

    static byte[] encodeControlResult(ControlHttpServer.ControlResult result) {
        Objects.requireNonNull(result, "result");
        JsonObject root = new JsonObject();
        root.addProperty("accepted", result.accepted());
        root.addProperty("message", result.message());
        root.addProperty("state", result.state());
        return encode(root);
    }

    static byte[] encodeObservation(AgentObservation observation) {
        JsonObject root = new JsonObject();
        root.addProperty("protocol_version", 1);
        root.addProperty("run_id", observation.runId());
        root.addProperty("state", observation.state());
        root.addProperty("updated_at", DateTimeFormatter.ISO_INSTANT.format(observation.updatedAt()));
        root.addProperty("world_connected", observation.worldConnected());
        root.addProperty("context_matches", observation.contextMatches());
        root.addProperty("control_token_configured", observation.controlTokenConfigured());
        root.addProperty("ready", observation.blockers().isEmpty());
        JsonArray blockers = new JsonArray();
        observation.blockers().forEach(value -> blockers.add(boundedOrFallback(value, "Not ready")));
        root.add("blockers", blockers);
        JsonArray actions = new JsonArray();
        observation.allowedActions().forEach(actions::add);
        root.add("allowed_actions", actions);
        root.addProperty("plan_id", observation.planId());
        root.addProperty("loading_progress", observation.loadingProgress());
        root.addProperty("progress_revision", observation.progressRevision());
        root.addProperty("last_progress_at", observation.lastProgressAt() == null
                ? null : DateTimeFormatter.ISO_INSTANT.format(observation.lastProgressAt()));
        root.add("build_check", buildCheckObservation(observation.buildCheck()));
        root.addProperty("last_error", observation.lastError().isBlank()
                ? null : boundedOrFallback(observation.lastError(), "Runtime error"));
        root.addProperty("last_message", boundedOrFallback(observation.lastMessage(), "Ready."));
        root.addProperty("baritone_status", observation.baritoneStatus());
        JsonObject lastControl = new JsonObject();
        lastControl.addProperty("sequence", observation.controlSequence());
        lastControl.addProperty("action", observation.controlAction());
        lastControl.addProperty("request_id", observation.controlRequestId());
        root.add("last_control", lastControl);
        SupervisorStatus status = observation.status();
        root.addProperty("phase", status == null ? null : status.phase().name());
        root.addProperty("recovery_stage", status == null ? null : status.recoveryStage().name());
        root.addProperty("verification_stage", status == null ? null : status.verificationStage().name());
        root.addProperty("stable_verification_passes", status == null ? 0 : status.stableVerificationPasses());
        if (status == null || status.currentChunkOrdinal() == 0) {
            root.add("current_chunk", null);
        } else {
            JsonObject chunk = new JsonObject();
            chunk.addProperty("index", status.currentChunkOrdinal());
            chunk.addProperty("total", status.chunkTotal());
            root.add("current_chunk", chunk);
        }
        root.add("current_layer", layerProgress(status == null ? null : status.layerProgress()));
        root.addProperty("planting_deferred", status == null ? null : status.plantingDeferred());
        root.addProperty("glowstone_after_structure", status == null ? null : status.glowstoneAfterStructure());
        root.addProperty("deferred_seed_cells", status == null ? 0 : status.deferredSeedCells());
        root.add("materials", statusMaterials(
                observation.inventory() != null && observation.inventory().available()
                        ? observation.inventory().mainMaterialTotals()
                        : status == null ? MaterialQuantities.empty() : status.inventory(),
                status == null ? MaterialQuantities.empty() : status.missingMaterials()));
        JsonObject ledger = new JsonObject();
        ledger.add("planned", materialCounts(status == null
                ? MaterialQuantities.empty() : status.materials().planned()));
        ledger.add("consumed", materialCounts(status == null
                ? MaterialQuantities.empty() : status.materials().consumed()));
        ledger.add("withdrawn", materialCounts(status == null
                ? MaterialQuantities.empty() : status.materials().withdrawn()));
        ledger.add("remaining_plan", materialCounts(status == null
                ? MaterialQuantities.empty() : status.materials().remainingPlan()));
        root.add("material_ledger", ledger);
        if (observation.inventory() != null) {
            root.add("inventory", inventoryObservation(observation.inventory()));
        }
        if (observation.shop() != null) {
            root.add("shop", shopObservation(observation.shop()));
        }
        if (observation.player() != null) {
            root.add("player", playerObservation(observation.player()));
        }
        if (observation.execution() != null) {
            root.add("execution", executionObservation(observation.execution()));
        }
        if (observation.depots() != null) {
            root.add("depots", depotObservation(observation.depots()));
        }
        if (observation.mossDeposit() != null) {
            root.add("moss_deposit", mossDepositObservation(observation.mossDeposit()));
        }
        if (observation.materialShop() != null) {
            root.add("material_shop", materialShopObservation(observation.materialShop()));
        }
        if (observation.soilWatchpoints() != null) {
            root.add("soil_watchpoints", SoilWatchpointJson.encode(observation.soilWatchpoints()));
        }
        if (observation.surplusDisposal() != null) {
            var disposal = observation.surplusDisposal();
            JsonObject facts = new JsonObject();
            facts.addProperty("phase", clipped(disposal.phase(), 32));
            facts.addProperty("detail", clipped(disposal.detail(), 512));
            facts.addProperty("active", disposal.active());
            facts.addProperty("pending", disposal.pending());
            facts.addProperty("unavailable", disposal.unavailable());
            facts.addProperty("confirmed_session_stacks", disposal.confirmedStacks());
            facts.addProperty("confirmed_session_items", disposal.confirmedItems());
            root.add("surplus_disposal", facts);
        }
        SoilWatchpointJson.fit(root, OBSERVATION_GSON, MAX_OUTBOUND_BYTES);
        fitMossToolSelection(root);
        fitMaterialShopTelemetry(root);
        fitShopMenuHistory(root);
        fitMossDepositTelemetry(root);
        if (root.has("depots")) { fitDepotTelemetry(root); }
        if (root.has("execution")
                && OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
            JsonObject execution = root.getAsJsonObject("execution");
            execution.addProperty("truncated", true);
            execution.add("last_failure", null);
            execution.addProperty("detail", "");
            execution.addProperty("flight_status", "");
            if (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES
                    && execution.has("last_obstruction") && !execution.get("last_obstruction").isJsonNull()) {
                // Keep the failed target and its captured blocker facts ahead of optional execution history.
                JsonObject summary = new JsonObject();
                summary.add("available", execution.get("available"));
                summary.add("mode", execution.get("mode"));
                summary.add("last_obstruction", execution.get("last_obstruction"));
                summary.addProperty("truncated", true);
                root.add("execution", summary);
                if (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
                    JsonObject obstruction = summary.getAsJsonObject("last_obstruction");
                    obstruction.add("entities", new JsonArray());
                    obstruction.addProperty("truncated", true);
                    obstruction.addProperty("reason", "");
                    obstruction.addProperty("error", "");
                }
            }
            if (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
                root.remove("execution");
            }
        }
        return encode(root, OBSERVATION_GSON);
    }

    static final String TOO_MANY_STAGES = "The plan has too many stages to report progress.";
    private static final long PROGRESS_STAGE_OVERHEAD_BYTES = 96;
    private static final long PROGRESS_HEADER_BYTES = 2_048;

    static byte[] encodeProgress(ScheduleProgress progress, long revision) {
        return encodeProgress(progress, revision, MAX_OUTBOUND_BYTES - PROGRESS_HEADER_BYTES);
    }

    /** {@code detailBudget} bounds the stage list; the current stage always keeps its chunk letters. */
    static byte[] encodeProgress(ScheduleProgress progress, long revision, long detailBudget) {
        Objects.requireNonNull(progress, "progress");
        long stageCount = progress.stageCount();
        if (stageCount * PROGRESS_STAGE_OVERHEAD_BYTES > detailBudget) {
            return encodeProgressUnavailable(TOO_MANY_STAGES, revision);
        }
        boolean truncated = stageCount * (PROGRESS_STAGE_OVERHEAD_BYTES + progress.layout().chunkCount())
                > detailBudget;
        JsonObject root = new JsonObject();
        root.addProperty("protocol_version", 1);
        root.addProperty("available", true);
        root.addProperty("revision", revision);
        root.addProperty("plan_id", progress.planId());
        root.addProperty("schedule_id", progress.scheduleId());
        JsonObject layout = new JsonObject();
        layout.addProperty("origin_x", progress.layout().origin().x());
        layout.addProperty("origin_z", progress.layout().origin().z());
        layout.addProperty("columns", progress.layout().columns());
        layout.addProperty("rows", progress.layout().rows());
        root.add("layout", layout);
        OptionalInt current = progress.currentStage();
        JsonObject totals = new JsonObject();
        totals.addProperty("actions", progress.totalActions());
        totals.addProperty("done", progress.doneActions());
        totals.addProperty("stages", progress.stageCount());
        totals.addProperty("current_stage", current.isPresent() ? Integer.valueOf(current.getAsInt() + 1) : null);
        root.add("totals", totals);
        MaterialQuantities done = progress.doneMaterials();
        JsonObject materials = new JsonObject();
        progress.plannedMaterials().asMap().forEach((material, planned) -> {
            JsonObject counts = new JsonObject();
            counts.addProperty("planned", planned);
            counts.addProperty("done", done.get(material));
            materials.add(material.jsonName(), counts);
        });
        root.add("materials", materials);
        JsonArray stages = new JsonArray();
        for (int stage = 0; stage < progress.stageCount(); stage++) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", progress.stageKind(stage));
            item.addProperty("y", progress.stageY(stage));
            item.addProperty("actions", progress.stageActions(stage));
            item.addProperty("done", progress.stageDone(stage));
            boolean detail = !truncated || (current.isPresent() && current.getAsInt() == stage);
            item.addProperty("chunks", detail ? progress.chunkStatuses(stage) : null);
            stages.add(item);
        }
        root.add("stages", stages);
        root.addProperty("chunk_detail_truncated", truncated);
        return encode(root, OBSERVATION_GSON);
    }

    static byte[] encodeProgressUnavailable(String reason, long revision) {
        JsonObject root = new JsonObject();
        root.addProperty("protocol_version", 1);
        root.addProperty("available", false);
        root.addProperty("revision", revision);
        root.addProperty("reason", boundedOrFallback(reason, "Progress is unavailable."));
        return encode(root, OBSERVATION_GSON);
    }

    private static JsonObject materialShopObservation(MaterialShopObservation shop) {
        JsonObject result = new JsonObject();
        result.addProperty("available", shop.available());
        result.addProperty("active", shop.active());
        result.addProperty("stage", clipped(shop.stage(), 32));
        result.addProperty("detail", clipped(shop.detail(), 512));
        result.addProperty("pending", shop.pending());
        result.addProperty("product", shop.product() == null ? null : shop.product().itemId());
        result.addProperty("quantity", shop.quantity());
        result.addProperty("confirmed", shop.confirmed());
        result.add("original_stamp", inventoryStamp(shop.originalStamp()));
        result.add("receipt_stamp", inventoryStamp(shop.receiptStamp()));
        result.add("reconciliation_barrier", inventoryStamp(shop.reconciliationBarrier()));
        result.addProperty("truncated", shop.truncated());
        return result;
    }

    private static void fitMaterialShopTelemetry(JsonObject root) {
        if (!root.has("material_shop")
                || OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length <= MAX_OUTBOUND_BYTES) { return; }
        JsonObject shop = root.getAsJsonObject("material_shop");
        shop.addProperty("detail", "");
        shop.add("original_stamp", null);
        shop.add("receipt_stamp", null);
        shop.add("reconciliation_barrier", null);
        shop.addProperty("truncated", true);
        if (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
            // Optional metadata must not displace existing control, inventory or obstruction facts.
            root.remove("material_shop");
        }
    }

    private static JsonObject mossDepositObservation(MossDepositObservation deposit) {
        JsonObject result = new JsonObject();
        result.addProperty("available", deposit.available());
        result.addProperty("active", deposit.active());
        result.addProperty("stage", clipped(deposit.stage(), 32));
        result.addProperty("detail", clipped(deposit.detail(), 512));
        result.addProperty("pending", deposit.pending());
        result.addProperty("quantity", deposit.quantity());
        result.addProperty("confirmed_session_items", deposit.confirmedSessionItems());
        result.addProperty("item_id", deposit.itemId());
        result.addProperty("confirmed_session_pickup_items", deposit.confirmedSessionPickupItems());
        JsonObject destination = null;
        if (deposit.destination() != null) {
            destination = new JsonObject();
            destination.addProperty("id", clipped(deposit.destination().id(), 128));
            destination.addProperty("x", deposit.destination().x());
            destination.addProperty("y", deposit.destination().y());
            destination.addProperty("z", deposit.destination().z());
        }
        result.add("destination", destination);
        result.add("original_stamp", inventoryStamp(deposit.originalStamp()));
        result.add("receipt_stamp", inventoryStamp(deposit.receiptStamp()));
        result.add("reconciliation_barrier", inventoryStamp(deposit.reconciliationBarrier()));
        result.addProperty("truncated", deposit.truncated());
        return result;
    }

    private static JsonObject inventoryStamp(ServerInventorySnapshotStamp stamp) {
        if (stamp == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("observer_epoch", stamp.observerEpoch());
        result.addProperty("context_generation", stamp.contextGeneration());
        result.addProperty("open_generation", stamp.openGeneration());
        result.addProperty("full_sequence", stamp.fullSequence());
        result.addProperty("sync_id", stamp.syncId());
        result.addProperty("revision", stamp.revision());
        return result;
    }

    private static void fitMossDepositTelemetry(JsonObject root) {
        if (!root.has("moss_deposit")
                || OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length <= MAX_OUTBOUND_BYTES) { return; }
        JsonObject deposit = root.getAsJsonObject("moss_deposit");
        deposit.addProperty("detail", "");
        deposit.add("original_stamp", null);
        deposit.add("receipt_stamp", null);
        deposit.add("reconciliation_barrier", null);
        deposit.addProperty("truncated", true);
        if (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
            // Added optional metadata must not displace existing control and obstruction evidence.
            root.remove("moss_deposit");
        }
    }

    private static JsonObject executionObservation(ExecutionObservation execution) {
        JsonObject result = new JsonObject();
        result.addProperty("available", execution.available());
        result.addProperty("mode", execution.mode());
        result.addProperty("detail", execution.detail());
        result.add("target", executionTarget(execution.target()));
        result.add("receipt", executionReceipt(execution.receipt()));
        result.add("last_receipt", executionReceipt(execution.lastReceipt()));
        result.add("last_failure", executionReceipt(execution.lastFailure()));
        result.add("last_obstruction", executionObstruction(execution.lastObstruction()));
        result.add("last_moss_tool_selection", mossToolSelection(execution.lastMossToolSelection()));
        result.addProperty("owned_mining", execution.ownedMining());
        result.addProperty("manager_breaking", execution.managerBreaking());
        result.addProperty("flight_status", execution.flightStatus());
        result.addProperty("error", execution.error());
        result.addProperty("truncated", false);
        return result;
    }

    private static void fitMossToolSelection(JsonObject root) {
        if (!root.has("execution") || OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length
                <= MAX_OUTBOUND_BYTES) { return; }
        JsonObject execution = root.getAsJsonObject("execution");
        if (!execution.has("last_moss_tool_selection") || execution.get("last_moss_tool_selection").isJsonNull()) { return; }
        JsonObject selection = execution.getAsJsonObject("last_moss_tool_selection");
        selection.add("candidates", new JsonArray());
        selection.addProperty("truncated", true);
        if (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
            execution.add("last_moss_tool_selection", null);
        }
    }

    private static JsonObject mossToolSelection(MossToolSelection selection) {
        if (selection == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("captured_at", selection.capturedAt().toString());
        result.add("target", executionPosition(selection.target()));
        result.addProperty("current_context_matches", selection.currentContextMatches());
        result.addProperty("outcome", selection.outcome());
        result.addProperty("selected_slot", selection.selectedSlot());
        result.addProperty("selected_item", selection.selectedItem());
        result.addProperty("tracked_identities_before", selection.trackedIdentitiesBefore());
        result.addProperty("tracked_identities_after", selection.trackedIdentitiesAfter());
        result.addProperty("identity_cap", selection.identityCap());
        result.addProperty("repair_enabled", selection.repairEnabled());
        result.addProperty("guard_facts_phase", "BEFORE_ADMISSION");
        result.addProperty("candidate_scope", "HOTBAR");
        result.addProperty("truncated", selection.truncated());
        result.addProperty("error", selection.error());
        JsonArray candidates = new JsonArray();
        for (MossToolSelection.Candidate candidate : selection.candidates()) {
            JsonObject item = new JsonObject();
            item.addProperty("slot", candidate.slot());
            item.addProperty("item_id", candidate.itemId());
            item.addProperty("count", candidate.count());
            item.addProperty("damage", candidate.damage());
            item.addProperty("maximum", candidate.maximum());
            item.addProperty("unbreakable", candidate.unbreakable());
            item.addProperty("usable", candidate.usable());
            item.addProperty("damage_per_block", candidate.damagePerBlock());
            item.addProperty("mining_speed", candidate.miningSpeed());
            item.addProperty("evaluated", candidate.evaluated());
            item.addProperty("error", candidate.error());
            var guard = candidate.guard();
            item.addProperty("guard_reason", guard == null ? null : guard.reason());
            item.addProperty("identity_tracked", guard == null ? null : guard.identityTracked());
            item.addProperty("tracked_identities", guard == null ? null : guard.trackedIdentities());
            item.addProperty("allowance", guard == null ? null : guard.allowance());
            item.addProperty("effective_allowance", guard == null ? null : guard.effectiveAllowance());
            item.addProperty("reserve", guard == null ? null : guard.reserve());
            var fingerprint = candidate.fingerprint();
            item.addProperty("identity_sha256", fingerprint == null ? null : fingerprint.sha256());
            item.addProperty("fingerprint_complete", fingerprint == null ? null : fingerprint.complete());
            item.addProperty("fingerprint_truncated", fingerprint == null ? null : fingerprint.truncated());
            JsonArray components = new JsonArray();
            if (fingerprint != null) {
                for (var component : fingerprint.components()) {
                    JsonObject hash = new JsonObject();
                    hash.addProperty("id", component.id());
                    hash.addProperty("sha256", component.sha256());
                    components.add(hash);
                }
            }
            item.add("component_hashes", components);
            item.add("custom_data_evidence", mossMetadataEvidence(candidate.metadataEvidence()));
            candidates.add(item);
        }
        result.add("candidates", candidates);
        return result;
    }

    private static JsonObject mossMetadataEvidence(MossToolSelection.MetadataEvidence evidence) {
        if (evidence == null) { return null; }
        JsonObject result = new JsonObject();
        result.add("current", mossMetadataProbe(evidence.current()));
        result.addProperty("error", evidence.error());
        var applied = evidence.latestAppliedSlotReceipt();
        JsonObject receipt = null;
        if (applied != null) {
            receipt = new JsonObject();
            receipt.addProperty("epoch", applied.epoch());
            receipt.addProperty("sequence", applied.sequence());
            receipt.addProperty("slot", applied.slot());
            receipt.addProperty("damage", applied.damage());
            receipt.addProperty("exact_current_match", applied.exactCurrentMatch());
            receipt.add("custom_data", mossMetadataProbe(applied.customData()));
            JsonObject prior = null;
            if (applied.previousReceipt() != null) {
                var previous = applied.previousReceipt();
                prior = new JsonObject();
                prior.addProperty("epoch", previous.epoch());
                prior.addProperty("sequence", previous.sequence());
                prior.addProperty("slot", previous.slot());
                prior.addProperty("damage", previous.damage());
                prior.addProperty("other_components_equal", previous.otherComponentsEqual());
            }
            receipt.add("previous_applied_slot_receipt", prior);
        }
        result.add("latest_applied_slot_receipt", receipt);
        return result;
    }

    private static JsonObject mossMetadataProbe(MossToolMetadataProbe.Snapshot probe) {
        if (probe == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("available", probe.available());
        result.addProperty("complete", probe.complete());
        result.addProperty("truncated", probe.truncated());
        result.addProperty("reason", probe.reason());
        JsonArray leaves = new JsonArray();
        for (var leaf : probe.leaves()) {
            JsonObject item = new JsonObject();
            JsonArray path = new JsonArray();
            for (String segment : leaf.path()) { path.add(segment); }
            item.add("path", path);
            item.addProperty("nbt_type", leaf.type());
            item.addProperty("sha256", leaf.sha256());
            item.addProperty("equals_item_damage", leaf.equalsItemDamage());
            leaves.add(item);
        }
        result.add("leaves", leaves);
        return result;
    }

    private static JsonObject executionTarget(ExecutionObservation.Target target) {
        if (target == null) { return null; }
        JsonObject result = executionPosition(target.position());
        result.addProperty("expected_block", target.expectedBlock());
        result.addProperty("actual_block", target.actualBlock());
        result.addProperty("chunk_received", target.chunkReceived());
        return result;
    }

    private static JsonObject executionObstruction(ExecutionObstruction obstruction) {
        if (obstruction == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("captured_at", DateTimeFormatter.ISO_INSTANT.format(obstruction.capturedAt()));
        result.addProperty("mode", obstruction.mode());
        result.addProperty("reason", obstruction.reason());
        result.add("target", executionTarget(obstruction.target()));
        result.addProperty("source_world_matches", obstruction.sourceWorldMatches());
        result.addProperty("current_world_matches", obstruction.currentWorldMatches());
        result.addProperty("player_overlaps_target", obstruction.playerOverlapsTarget());
        result.addProperty("player_overlaps_support_box", obstruction.playerOverlapsSupportBox());
        result.addProperty("support_box_extension_up", 2);
        result.addProperty("total_entities", obstruction.totalEntities());
        JsonArray samples = new JsonArray();
        for (ExecutionObstruction.EntitySample entity : obstruction.entities()) {
            JsonObject sample = new JsonObject();
            sample.addProperty("entity_type", entity.entityType());
            sample.addProperty("living", entity.living());
            sample.addProperty("player", entity.player());
            sample.addProperty("intersects_target", entity.intersectsTarget());
            sample.addProperty("intersects_support_box", entity.intersectsSupportBox());
            sample.addProperty("item_id", entity.itemId());
            sample.addProperty("item_count", entity.itemCount());
            samples.add(sample);
        }
        result.add("entities", samples);
        result.addProperty("truncated", obstruction.truncated());
        result.addProperty("error", obstruction.error());
        return result;
    }

    private static JsonObject executionPosition(io.github.schematicsupervisor.core.BlockPosition position) {
        if (position == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("x", position.x());
        result.addProperty("y", position.y());
        result.addProperty("z", position.z());
        return result;
    }

    private static JsonObject executionReceipt(ExecutionObservation.Receipt receipt) {
        if (receipt == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("captured_at", DateTimeFormatter.ISO_INSTANT.format(receipt.capturedAt()));
        result.addProperty("mode", receipt.mode());
        result.add("target", executionTarget(receipt.target()));
        result.addProperty("prediction_pending", receipt.predictionPending());
        result.addProperty("material", receipt.material());
        result.addProperty("inventory_before", receipt.inventoryBefore());
        result.addProperty("inventory_now", receipt.inventoryNow());
        result.addProperty("result", receipt.result());
        result.addProperty("age_ticks", receipt.ageTicks());
        result.addProperty("budget_ticks", receipt.budgetTicks());
        result.addProperty("world_matches", receipt.worldMatches());
        result.addProperty("owned_mining", receipt.ownedMining());
        result.addProperty("manager_breaking", receipt.managerBreaking());
        result.add("breaking_position", executionPosition(receipt.breakingPosition()));
        result.addProperty("breaking_progress", receipt.breakingProgress());
        result.addProperty("local_breaking_delta", receipt.localBreakingDelta());
        result.addProperty("breaking_cooldown", receipt.breakingCooldown());
        result.addProperty("selected_slot", receipt.selectedSlot());
        result.addProperty("selected_item", receipt.selectedItem());
        result.addProperty("error", receipt.error());
        return result;
    }

    private static JsonObject depotObservation(DepotObservation depots) {
        JsonObject result = new JsonObject();
        result.addProperty("available", depots.available());
        result.addProperty("operation", depots.operation());
        result.addProperty("stage", depots.stage());
        result.addProperty("active_depot_id", depots.activeDepotId());
        JsonArray queue = new JsonArray();
        depots.queuedDepotIds().forEach(queue::add);
        result.add("queued_depot_ids", queue);
        result.addProperty("automatic_scans_paused", depots.automaticScansPaused());
        result.addProperty("blocked", depots.blocked());
        result.addProperty("detail", depots.detail());
        result.addProperty("registered_count", depots.registeredCount());
        JsonArray entries = new JsonArray();
        for (DepotObservation.Entry depot : depots.entries()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", depot.id());
            row.addProperty("x", depot.x());
            row.addProperty("y", depot.y());
            row.addProperty("z", depot.z());
            row.addProperty("scanned", depot.scanned());
            row.add("observed_stock", depot.observedStock() == null ? null : materialCounts(depot.observedStock()));
            row.addProperty("last_error", depot.lastError());
            row.addProperty("active", depot.active());
            row.addProperty("queued", depot.queued());
            entries.add(row);
        }
        result.add("entries", entries);
        result.addProperty("truncated", depots.truncated());
        return result;
    }

    private static void fitDepotTelemetry(JsonObject root) {
        JsonObject depots = root.getAsJsonObject("depots");
        JsonArray entries = depots.getAsJsonArray("entries");
        JsonArray queue = depots.getAsJsonArray("queued_depot_ids");
        while (OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
            depots.addProperty("truncated", true);
            if (entries.size() > 0) {
                entries.remove(entries.size() - 1);
            } else if (queue.size() > 0) {
                queue.remove(queue.size() - 1);
            } else if (!depots.get("detail").getAsString().isEmpty()) {
                depots.addProperty("detail", "");
            } else {
                // Optional telemetry must not make an otherwise valid control observation unavailable.
                root.remove("depots");
                break;
            }
        }
    }

    private static void fitShopMenuHistory(JsonObject root) {
        if (!root.has("shop")) { return; }
        JsonObject shop = root.getAsJsonObject("shop");
        JsonArray history = shop.getAsJsonArray("menu_history");
        while (history.size() > 0
                && OBSERVATION_GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length > MAX_OUTBOUND_BYTES) {
            history.remove(0);
            shop.addProperty("menu_history_truncated", true);
        }
    }

    private static JsonObject playerObservation(PlayerObservation player) {
        JsonObject result = new JsonObject();
        result.addProperty("x", player.x());
        result.addProperty("y", player.y());
        result.addProperty("z", player.z());
        result.addProperty("flying", player.flying());
        result.addProperty("allow_flying", player.allowFlying());
        result.addProperty("on_ground", player.onGround());
        result.addProperty("health", player.health());
        result.addProperty("hunger", player.hunger());
        result.addProperty("block_interaction_reach", player.blockInteractionReach());
        result.addProperty("screen_kind", player.screenKind());
        result.addProperty("container_open", player.containerOpen());
        if (player.background() != null) {
            var background = player.background();
            JsonObject screen = new JsonObject();
            screen.addProperty("screen_class", background.screenClass());
            screen.addProperty("window_focused", background.windowFocused());
            screen.addProperty("window_minimized", background.windowMinimized());
            screen.addProperty("world_actions_allowed", background.worldActionsAllowed());
            screen.addProperty("keeps_world_ticking", background.keepsWorldTicking());
            result.add("background", screen);
        }
        return result;
    }

    private static JsonObject inventoryObservation(InventoryObservation inventory) {
        JsonObject result = new JsonObject();
        result.addProperty("available", inventory.available());
        result.addProperty("error", clipped(inventory.error(), 256));
        if (!inventory.available()) { return result; }
        JsonArray slots = new JsonArray();
        inventory.mainSlots().forEach(slot -> slots.add(inventorySlot(slot)));
        result.add("main_slots", slots);
        result.add("offhand", inventorySlot(inventory.offhand()));
        JsonArray armor = null;
        if (inventory.armorSlots() != null) {
            armor = new JsonArray();
            for (InventoryObservation.Slot slot : inventory.armorSlots()) {
                JsonObject equipment = inventorySlot(slot);
                equipment.addProperty("equipment_slot",
                        java.util.List.of("feet", "legs", "chest", "head").get(slot.slot() - 36));
                armor.add(equipment);
            }
        }
        result.add("armor_slots", armor);
        result.addProperty("selected_hotbar_slot", inventory.selectedHotbarSlot());
        result.addProperty("empty_main_slots", inventory.emptyMainSlots());
        result.addProperty("normal_dirt_capacity", inventory.dirtCapacity());
        JsonObject capacity = null;
        if (inventory.normalMaterialCapacity() != null) {
            capacity = new JsonObject();
            for (Material material : InventoryObservation.CAPACITY_MATERIALS) {
                capacity.addProperty(material.jsonName(), inventory.normalMaterialCapacity().get(material));
            }
        }
        result.add("normal_material_capacity", capacity);
        result.add("main_material_totals", materialCounts(inventory.mainMaterialTotals()));
        result.add("main_and_offhand_material_totals", materialCounts(inventory.mainAndOffhandMaterialTotals()));
        InventoryObservation.Menu current = inventory.menu();
        JsonObject menu = new JsonObject();
        menu.addProperty("open", current.open());
        menu.addProperty("kind", current.kind());
        menu.addProperty("title", clipped(current.title(), 128));
        menu.addProperty("sync_id", current.syncId());
        menu.add("cursor", inventorySlot(current.cursor()));
        result.add("menu", menu);
        return result;
    }

    private static JsonObject inventorySlot(InventoryObservation.Slot slot) {
        JsonObject result = new JsonObject();
        result.addProperty("slot", slot.slot());
        result.addProperty("item_id", slot.itemId());
        result.addProperty("display_name", slot.displayName());
        result.addProperty("plain_default_components", slot.plainDefaultComponents());
        result.addProperty("count", slot.count());
        result.addProperty("max_count", slot.maxCount());
        result.addProperty("material", slot.material() == null ? null : slot.material().jsonName());
        result.addProperty("normal_dirt_stackable", slot.normalDirtStackable());
        if (slot.hasDurabilityFacts()) {
            JsonObject durability = new JsonObject();
            durability.addProperty("damage", slot.damage());
            durability.addProperty("maximum", slot.maxDamage());
            durability.addProperty("remaining", slot.remainingDurability());
            durability.addProperty("unbreakable", slot.unbreakable());
            result.add("durability", durability);
            if (slot.hoe()) { result.add("hoe_durability", durability.deepCopy()); }
        }
        return result;
    }

    private static JsonObject shopObservation(DirtShopObservation shop) {
        JsonObject result = new JsonObject();
        result.addProperty("available", shop.available());
        result.addProperty("state", clipped(shop.state(), 64));
        result.addProperty("active", shop.active());
        result.addProperty("expected_step", clipped(shop.expectedStep(), 128));
        result.addProperty("waiting_reason", clipped(shop.waitingReason(), 256));
        result.addProperty("purchased_stacks", shop.purchasedStacks());
        result.addProperty("pending_stacks", shop.pendingStacks());
        result.addProperty("target_stacks", shop.targetStacks());
        result.addProperty("reserved_empty_slots", shop.reservedEmptySlots());
        result.addProperty("empty_slots", shop.emptySlots());
        result.addProperty("dirt_count", shop.dirtCount());
        result.addProperty("cursor_empty", shop.cursorEmpty());
        result.addProperty("menu_title", clipped(shop.menuTitle(), 128));
        result.addProperty("menu_id", shop.menuId());
        result.addProperty("sync_id", shop.syncId());
        result.addProperty("menu_matches_expected", shop.menuMatchesExpected());
        result.addProperty("dirt_evidence", shop.dirtEvidence());
        result.addProperty("error", clipped(shop.error(), 256));
        JsonArray entries = new JsonArray();
        int textBudget = 6_000;
        boolean truncated = shop.truncated();
        for (DirtShopObservation.Entry entry : shop.entries()) {
            if (entries.size() >= 54) { truncated = true; break; }
            JsonObject row = new JsonObject();
            row.addProperty("slot", entry.slot());
            String label = clipped(entry.label(), Math.min(96, textBudget));
            textBudget -= label.length();
            String normalized = clipped(entry.normalizedLabel(), Math.min(96, textBudget));
            textBudget -= normalized.length();
            String itemId = clipped(entry.itemId(), 128);
            row.addProperty("label", label);
            row.addProperty("normalized_label", normalized);
            row.addProperty("item_id", itemId);
            row.addProperty("dirt_item", entry.dirtItem());
            truncated |= label.length() < entry.label().length()
                    || normalized.length() < entry.normalizedLabel().length()
                    || itemId.length() < entry.itemId().length();
            JsonArray lore = new JsonArray();
            for (String line : entry.lore()) {
                if (lore.size() >= 2 || textBudget <= 0) { truncated = true; break; }
                String bounded = clipped(line, Math.min(128, textBudget));
                truncated |= bounded.length() < line.length();
                textBudget -= bounded.length();
                lore.add(bounded);
            }
            row.add("lore", lore);
            row.addProperty("stack_quantity", entry.stackQuantity());
            row.addProperty("matches_current_step", entry.matchesCurrentStep());
            entries.add(row);
        }
        result.add("entries", entries);
        result.addProperty("truncated", truncated);
        JsonArray history = new JsonArray();
        boolean historyTruncated = shop.menuHistory().size() > 4;
        for (ShopMenuHistory.Menu menu : shop.menuHistory().subList(Math.max(0, shop.menuHistory().size() - 4),
                shop.menuHistory().size())) {
            JsonObject captured = new JsonObject();
            captured.addProperty("captured_at", clipped(menu.capturedAt(), 64));
            String title = clipped(menu.title(), 128);
            captured.addProperty("title", title);
            boolean menuTruncated = menu.truncated() || title.length() < menu.title().length();
            JsonArray menuEntries = new JsonArray();
            for (ShopMenuHistory.Entry entry : menu.entries()) {
                if (menuEntries.size() == 54) { menuTruncated = true; break; }
                if (entry.slot() < 0 || entry.slot() >= 54 || entry.stackCount() < 1 || entry.itemId() == null
                        || entry.itemId().length() > 128 || !entry.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                    menuTruncated = true;
                    continue;
                }
                JsonObject row = new JsonObject();
                row.addProperty("slot", entry.slot());
                row.addProperty("item_id", entry.itemId());
                row.addProperty("stack_count", entry.stackCount());
                menuEntries.add(row);
            }
            captured.add("entries", menuEntries);
            captured.addProperty("truncated", menuTruncated);
            history.add(captured);
        }
        result.add("menu_history", history);
        result.addProperty("menu_history_truncated", historyTruncated);
        return result;
    }

    private static String clipped(String value, int maximum) {
        if (value == null) { return ""; }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    static byte[] readBounded(InputStream stream, int maximumBytes) throws IOException {
        Objects.requireNonNull(stream, "stream");
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 4_096));
        byte[] buffer = new byte[Math.min(maximumBytes + 1, 4_096)];
        int total = 0;
        while (true) {
            int read = stream.read(buffer, 0, Math.min(buffer.length, maximumBytes + 1 - total));
            if (read == -1) {
                return output.toByteArray();
            }
            total += read;
            if (total > maximumBytes) {
                throw new ProtocolException("response exceeds the size limit");
            }
            output.write(buffer, 0, read);
        }
    }

    private static JsonObject incidentStatus(RecoveryIncident incident, Instant updatedAt) {
        JsonObject status = new JsonObject();
        JsonObject chunk = new JsonObject();
        chunk.addProperty("index", incident.chunkOrdinal());
        chunk.addProperty("total", incident.chunkTotal());
        status.add("current_chunk", chunk);
        status.addProperty("phase", incident.phase().name());
        status.add(
                "materials",
                statusMaterials(MaterialQuantities.empty(), incident.missingMaterials())
        );
        status.addProperty(
                "baritone_status",
                incident.serverLagging()
                        ? "Recovery exhausted; server lag suspected"
                        : "Recovery exhausted"
        );
        if (incident.lastError().isBlank()) {
            status.add("last_error", null);
        } else {
            status.addProperty(
                    "last_error",
                    boundedOrFallback(incident.lastError(), "Unknown recovery error")
            );
        }
        status.addProperty("updated_at", DateTimeFormatter.ISO_INSTANT.format(updatedAt));
        return status;
    }

    /** Small and bounded: counts plus at most {@link BuildCheck#MAX_PROBLEMS} sample blocks. */
    private static JsonObject buildCheckObservation(BuildCheckObservation check) {
        if (check == null) {
            return null;
        }
        JsonObject root = new JsonObject();
        root.addProperty("status", check.status());
        root.addProperty("progress", check.progress());
        root.addProperty("started_at", DateTimeFormatter.ISO_INSTANT.format(check.startedAt()));
        root.addProperty("finished_at", check.finishedAt() == null
                ? null : DateTimeFormatter.ISO_INSTANT.format(check.finishedAt()));
        root.addProperty("duration_ms", check.durationMillis());
        root.addProperty("summary", clipped(check.summary(), 1000));
        BuildCheck.Result result = check.result();
        if (result == null) {
            return root;
        }
        root.addProperty("chunks_checked", result.chunksChecked());
        root.addProperty("chunks_total", result.chunkCount());
        root.addProperty("actions_total", result.totalActions());
        root.addProperty("actions_done", result.doneActions());
        root.addProperty("pieces_total", result.pieceCount());
        root.addProperty("pieces_done", result.completePieces().count());
        JsonObject left = new JsonObject();
        left.addProperty("place", result.placementsLeft());
        left.addProperty("till", result.tillingLeft());
        left.addProperty("plant", result.plantingLeft());
        left.addProperty("unchecked", result.uncheckedActions());
        root.add("work_left", left);
        root.addProperty("wrong_blocks", result.wrongBlocks());
        root.addProperty("wrong_blocks_cleared", result.wrongBlocksCleared());
        root.addProperty("extra_blocks", result.extraBlocks());
        root.addProperty("extra_blocks_cleared", result.extraBlocksCleared());
        root.addProperty("temporary_supports", result.temporarySupports());
        JsonArray problems = new JsonArray();
        for (BuildCheck.Problem problem : result.problems()) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", problem.kind().name());
            item.addProperty("x", problem.position().x());
            item.addProperty("y", problem.position().y());
            item.addProperty("z", problem.position().z());
            item.addProperty("chunk", problem.chunkIndex() + 1);
            item.addProperty("expected", clipped(problem.expected().blockId(), 128));
            item.addProperty("actual", clipped(problem.actual().blockId(), 128));
            problems.add(item);
        }
        root.add("problems", problems);
        return root;
    }

    private static JsonObject layerProgress(LayerProgress progress) {
        if (progress == null) {
            return null;
        }
        JsonObject layer = new JsonObject();
        layer.addProperty("order", progress.order());
        layer.addProperty("stage", progress.stage());
        layer.addProperty("index", progress.ordinal());
        layer.addProperty("total", progress.total());
        layer.addProperty("y", progress.y());
        layer.addProperty("chunk_index", progress.chunkOrdinal());
        layer.addProperty("chunk_total", progress.chunkTotal());
        return layer;
    }

    private static JsonObject statusMaterials(
            MaterialQuantities available,
            MaterialQuantities missing
    ) {
        JsonObject materials = new JsonObject();
        java.util.TreeSet<Material> named = new java.util.TreeSet<>(available.asMap().keySet());
        named.addAll(missing.asMap().keySet());
        for (Material material : named) {
            long availableAmount = available.get(material);
            long missingAmount = missing.get(material);
            if (availableAmount == 0 && missingAmount == 0) {
                continue;
            }
            JsonObject counts = new JsonObject();
            counts.addProperty("available", availableAmount);
            counts.addProperty("required", Math.addExact(availableAmount, missingAmount));
            counts.addProperty("missing", missingAmount);
            materials.add(material.jsonName(), counts);
        }
        return materials;
    }

    private static JsonObject materialCounts(MaterialQuantities quantities) {
        JsonObject result = new JsonObject();
        quantities.asJsonMap().forEach(result::addProperty);
        return result;
    }

    private static String opaqueIncidentId(RecoveryIncident incident) {
        String source = String.join(
                "\u001f",
                incident.planId(),
                Integer.toString(incident.chunkOrdinal()),
                incident.phase().name(),
                Long.toString(incident.stalledSeconds()),
                Boolean.toString(incident.serverLagging()),
                incident.missingMaterials().toString(),
                incident.lastError(),
                Boolean.toString(incident.repathAttempted()),
                Boolean.toString(incident.safeReturnAttempted())
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "incident-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static byte[] encode(JsonObject object) {
        return encode(object, GSON);
    }

    private static byte[] encode(JsonObject object, Gson gson) {
        byte[] encoded = gson.toJson(object).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_OUTBOUND_BYTES) {
            throw new ProtocolException("outbound payload exceeds the size limit");
        }
        return encoded;
    }

    private static JsonReader strictReader(String json) {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(Strictness.STRICT);
        return reader;
    }

    private static String uniqueName(JsonReader reader, Set<String> fields) throws IOException {
        String name = reader.nextName();
        if (!fields.add(name)) {
            throw new ProtocolException("JSON object contains a duplicate field");
        }
        return name;
    }

    private static String requiredString(JsonReader reader, String name, int maximumLength)
            throws IOException {
        require(reader, JsonToken.STRING, name + " must be a string");
        String value = reader.nextString();
        if (value.isBlank()) {
            throw new ProtocolException(name + " must not be blank");
        }
        if (value.length() > maximumLength) {
            throw new ProtocolException(name + " exceeds its length limit");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new ProtocolException(name + " contains a control character");
            }
        }
        return value;
    }

    private static Boolean requiredBoolean(JsonReader reader, String name) throws IOException {
        require(reader, JsonToken.BOOLEAN, name + " must be a boolean");
        return reader.nextBoolean();
    }

    private static void require(JsonReader reader, JsonToken token, String message)
            throws IOException {
        if (reader.peek() != token) {
            throw new ProtocolException(message);
        }
    }

    private static void requireEnd(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new ProtocolException("JSON contains trailing data");
        }
    }

    private static void requirePresent(Object value, String name) {
        if (value == null) {
            throw new ProtocolException(name + " is required");
        }
    }

    private static OffsetDateTime parseOffsetTimestamp(String value, String name) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeException exception) {
            throw new ProtocolException(name + " must be an ISO-8601 offset timestamp", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("JSON body is not valid UTF-8", exception);
        }
    }

    private static String boundedOrFallback(String value, String fallback) {
        String selected = value == null || value.isBlank() ? fallback : value.strip();
        if (selected.length() <= MAX_TEXT_LENGTH) {
            return selected;
        }
        return selected.substring(0, MAX_TEXT_LENGTH);
    }

    record EncodedIncident(String incidentId, byte[] body) {
        EncodedIncident {
            Objects.requireNonNull(incidentId, "incidentId");
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    static final class ProtocolException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        ProtocolException(String message) {
            super(message);
        }

        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
