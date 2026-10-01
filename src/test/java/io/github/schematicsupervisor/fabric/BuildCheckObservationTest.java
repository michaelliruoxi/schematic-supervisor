package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildCheck;
import io.github.schematicsupervisor.core.CompletedPieces;
import io.github.schematicsupervisor.core.LayerProgress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BuildCheckObservationTest {
    private static final Instant STARTED = Instant.parse("2026-09-28T16:17:36Z");
    private static final Instant FINISHED = Instant.parse("2026-09-28T16:17:39Z");
    private static final LayerProgress TILL = new LayerProgress("LAYERS", "TILL", 52, 101, 9, 1, 49);
    private static final BuildCheck.Problem EXTRA = new BuildCheck.Problem(BuildCheck.ProblemKind.EXTRA,
            new BlockPosition(8198, -40, -26920), 11, BlockState.AIR, new BlockState("minecraft:cobblestone"));
    private static final BuildCheck.Problem WRONG = new BuildCheck.Problem(BuildCheck.ProblemKind.WRONG,
            new BlockPosition(8199, -38, -26920), 11, new BlockState("minecraft:glowstone"),
            new BlockState("minecraft:jack_o_lantern"));

    @Test
    void summaryNamesTheWorkLeftTheProblemsAndWhereBuildingStarts() {
        String summary = BuildCheckObservation.summarize(result(49, 3, 1, 7, 2, List.of(EXTRA, WRONG)), 2_345, TILL);
        assertEquals("Build check (2.3 s): 49 of 49 chunks checked; 57% built. Left: 8,000 to place, "
                + "2,000 to till, 2,345 to plant. 2 wrong blocks and 5 extra blocks need attention "
                + "(first: extra cobblestone at 8198 -40 -26920); the builder clears 3 moss or stem blocks itself. "
                + "Starting at stage 52 of 101 (till, Y 9).", summary);
    }

    @Test
    void summaryExplainsUncheckedChunksAndAFinishedBuild() {
        BuildCheck.Result finished = new BuildCheck.Result("plan", "layers-v1", 10, pieces(10), 45, 49, 100, 100,
                0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        assertEquals("Build check (0.0 s): 45 of 49 chunks checked (4 were out of range and will be checked when "
                + "the build reaches them); 100% built. Nothing is left to place, till, or plant. No wrong or "
                + "extra blocks need attention. Starting final verification.",
                BuildCheckObservation.summarize(finished, 40, new LayerProgress("LAYERS", "VERIFY", 3, 3, null, 0, 0)));
        BuildCheck.Result one = new BuildCheck.Result("plan", "layers-v1", 10, pieces(4), 48, 49, 100, 40,
                1, 0, 0, 3, 1, 0, 0, 0, 0, List.of(WRONG));
        assertTrue(BuildCheckObservation.summarize(one, 1_000, TILL).contains("(1 was out of range and will be "
                + "checked when the build reaches it); 40% built. Left: 1 to place, 0 to till, 0 to plant. "
                + "1 wrong block and 0 extra blocks need attention (first: jack o lantern instead of glowstone "
                + "at 8199 -38 -26920). Starting"));
    }

    @Test
    void observationEncodesRunningCompleteAndFailedChecks() {
        assertTrue(encode(null).get("build_check").isJsonNull());

        JsonObject running = encode(BuildCheckObservation.running(STARTED, 0.456, 900))
                .getAsJsonObject("build_check");
        assertEquals("RUNNING", running.get("status").getAsString());
        assertEquals(0.456, running.get("progress").getAsDouble(), 1e-9);
        assertEquals("2026-09-28T16:17:36Z", running.get("started_at").getAsString());
        assertTrue(running.get("finished_at").isJsonNull());
        assertFalse(running.has("problems"));

        JsonObject complete = encode(BuildCheckObservation.complete(STARTED, FINISHED, 2_345,
                result(49, 3, 1, 7, 2, List.of(EXTRA, WRONG)), TILL)).getAsJsonObject("build_check");
        assertEquals("COMPLETE", complete.get("status").getAsString());
        assertEquals(1.0, complete.get("progress").getAsDouble());
        assertEquals("2026-09-28T16:17:39Z", complete.get("finished_at").getAsString());
        assertEquals(2_345, complete.get("duration_ms").getAsLong());
        assertTrue(complete.get("summary").getAsString().startsWith("Build check (2.3 s)"));
        assertEquals(49, complete.get("chunks_checked").getAsInt());
        assertEquals(49, complete.get("chunks_total").getAsInt());
        assertEquals(1_000, complete.get("actions_total").getAsLong());
        assertEquals(570, complete.get("actions_done").getAsLong());
        assertEquals(20, complete.get("pieces_total").getAsInt());
        assertEquals(12, complete.get("pieces_done").getAsInt());
        JsonObject left = complete.getAsJsonObject("work_left");
        assertEquals(8_000, left.get("place").getAsLong());
        assertEquals(2_000, left.get("till").getAsLong());
        assertEquals(2_345, left.get("plant").getAsLong());
        assertEquals(0, left.get("unchecked").getAsLong());
        assertEquals(3, complete.get("wrong_blocks").getAsLong());
        assertEquals(1, complete.get("wrong_blocks_cleared").getAsLong());
        assertEquals(7, complete.get("extra_blocks").getAsLong());
        assertEquals(2, complete.get("extra_blocks_cleared").getAsLong());
        assertEquals(0, complete.get("temporary_supports").getAsLong());
        JsonObject problem = complete.getAsJsonArray("problems").get(0).getAsJsonObject();
        assertEquals("EXTRA", problem.get("kind").getAsString());
        assertEquals(8198, problem.get("x").getAsInt());
        assertEquals(-40, problem.get("y").getAsInt());
        assertEquals(-26920, problem.get("z").getAsInt());
        assertEquals(12, problem.get("chunk").getAsInt());
        assertEquals("minecraft:air", problem.get("expected").getAsString());
        assertEquals("minecraft:cobblestone", problem.get("actual").getAsString());

        JsonObject failed = encode(BuildCheckObservation.failed(STARTED, FINISHED, 10, "boom"))
                .getAsJsonObject("build_check");
        assertEquals("FAILED", failed.get("status").getAsString());
        assertTrue(failed.get("summary").getAsString().contains("boom"));
        assertFalse(failed.has("problems"));
    }

    @Test
    void laterTelemetryKeepsTheBuildCheck() {
        AgentObservation observation = observation().withBuildCheck(BuildCheckObservation.running(STARTED, 0.5, 1))
                .withProgress(3, null).withDepots(null).withExecution(null).withSoilWatchpoints(null);
        assertEquals("RUNNING", parse(observation).getAsJsonObject("build_check").get("status").getAsString());
    }

    private static BuildCheck.Result result(int chunks, long wrong, long wrongCleared, long extra, long extraCleared,
                                            List<BuildCheck.Problem> problems) {
        return new BuildCheck.Result("plan", "layers-v1", 20, pieces(12), chunks, 49, 1_000, 570,
                8_000, 2_000, 2_345, 0, wrong, wrongCleared, extra, extraCleared, 0, problems);
    }

    private static CompletedPieces pieces(int count) {
        BitSet bits = new BitSet();
        bits.set(0, count);
        return CompletedPieces.of(bits);
    }

    private static JsonObject encode(BuildCheckObservation check) {
        return parse(observation().withBuildCheck(check));
    }

    private static AgentObservation observation() {
        return new AgentObservation("run-1", "CHECKING", FINISHED, true, true, true,
                List.of(), List.of("PAUSE", "STOP"), "plan", null, null, "", "Ready", "Idle", 0, "", "");
    }

    private static JsonObject parse(AgentObservation observation) {
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
