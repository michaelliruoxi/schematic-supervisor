package io.github.schematicsupervisor.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.schematicsupervisor.core.BlockState;
import java.nio.charset.StandardCharsets;

/** Optional bounded telemetry, independent of controls and material accounting. */
final class SoilWatchpointJson {
    private SoilWatchpointJson() { }

    static JsonObject encode(SoilWatchpointObservation observation) {
        JsonObject result = new JsonObject();
        result.addProperty("available", observation.available());
        result.addProperty("reason", observation.reason());
        result.addProperty("plan_id", observation.planId());
        result.addProperty("world_identity_hash", observation.context() == null
                ? null : observation.context().worldIdentityHash());
        result.addProperty("dimension", observation.context() == null ? null : observation.context().dimension());
        result.addProperty("world_epoch", observation.worldEpoch());
        result.addProperty("sampled_at", observation.sampledAt() == null ? null : observation.sampledAt().toString());
        result.addProperty("sampling_interval_ms", 1_000);
        result.addProperty("client_samples_only", true);
        result.addProperty("server_random_ticks_observed", false);
        result.addProperty("selection", "first_received_bare_source_farmland");
        result.addProperty("truncated", false);
        JsonArray points = new JsonArray();
        for (var point : observation.points()) {
            JsonObject entry = new JsonObject();
            JsonObject position = new JsonObject();
            position.addProperty("x", point.position().x());
            position.addProperty("y", point.position().y());
            position.addProperty("z", point.position().z());
            entry.add("position", position);
            entry.add("first_fresh", sample(point.firstFresh()));
            entry.add("last_fresh", sample(point.lastFresh()));
            entry.add("latest", sample(point.latest()));
            entry.addProperty("fresh_samples", point.freshSamples());
            entry.addProperty("changes", point.changes());
            entry.addProperty("continuity_unknown", point.continuityUnknown());
            entry.addProperty("history_truncated", point.historyTruncated());
            JsonArray history = new JsonArray();
            point.history().forEach(value -> history.add(sample(value)));
            entry.add("history", history);
            points.add(entry);
        }
        result.add("points", points);
        return result;
    }

    static void fit(JsonObject root, Gson gson, int maximumBytes) {
        if (!root.has("soil_watchpoints") || bytes(root, gson) <= maximumBytes) { return; }
        JsonObject soil = root.getAsJsonObject("soil_watchpoints");
        soil.addProperty("truncated", true);
        for (var point : soil.getAsJsonArray("points")) {
            point.getAsJsonObject().add("history", new JsonArray());
            point.getAsJsonObject().addProperty("history_truncated", true);
        }
        if (bytes(root, gson) > maximumBytes) {
            soil.add("points", new JsonArray());
            soil.addProperty("available", false);
            soil.addProperty("reason", "Optional soil telemetry exceeded response budget");
        }
    }

    private static int bytes(JsonObject root, Gson gson) {
        return gson.toJson(root).getBytes(StandardCharsets.UTF_8).length;
    }

    private static JsonObject sample(SoilWatchpointObservation.Sample value) {
        JsonObject result = new JsonObject();
        result.addProperty("at", value.at().toString());
        result.addProperty("status", value.status());
        result.addProperty("received", value.received());
        result.addProperty("prediction_pending", value.predictionPending());
        result.addProperty("above_received", value.aboveReceived());
        result.addProperty("above_prediction_pending", value.abovePredictionPending());
        result.add("actual", state(value.actual()));
        result.add("above", state(value.above()));
        result.addProperty("moisture", value.moisture());
        result.addProperty("environment_complete", value.environmentComplete());
        result.addProperty("nearby_water", value.nearbyWater());
        result.addProperty("rain_at_above", value.rainAtAbove());
        return result;
    }

    private static JsonObject state(BlockState state) {
        if (state == null) { return null; }
        JsonObject result = new JsonObject();
        result.addProperty("block_id", state.blockId());
        JsonObject properties = new JsonObject();
        state.properties().forEach(properties::addProperty);
        result.add("properties", properties);
        return result;
    }
}
