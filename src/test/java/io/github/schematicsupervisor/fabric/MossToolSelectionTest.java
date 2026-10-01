package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

class MossToolSelectionTest {
    private static final Instant CAPTURED = Instant.parse("2026-09-16T08:10:14Z");
    private static final BlockPosition TARGET = new BlockPosition(8209, -60, -26955);

    @Test
    void historyKeepsOriginalTimestampAndDistinguishesDisconnectedFromChangedContext() {
        var history = new MossToolSelection.History();
        Object world = new Object();
        Object player = new Object();
        assertNull(history.observation(world, player));
        history.capture(selection(List.of(candidate(0)), false), world, player);
        assertTrue(history.observation(world, player).currentContextMatches());
        assertNull(history.observation(null, player).currentContextMatches());
        var changed = history.observation(new Object(), player);
        assertFalse(changed.currentContextMatches());
        assertEquals(CAPTURED, changed.capturedAt());
        assertEquals(TARGET, changed.target());
        assertEquals("PLAIN_HAND", changed.outcome());
    }

    @Test
    void evaluatedCandidateUsesItsActualAdmissionMomentAndKeepsDiagnosticFailureUnknown() {
        var before = candidate(3);
        var actual = new MossMiningToolGuard.Diagnostic(true, 35, 36, 64, 64, 64, "ALLOWANCE_RESERVE");
        var evaluated = before.withEvaluation(true, actual);
        assertSame(actual, evaluated.guard());
        assertTrue(evaluated.evaluated());
        assertEquals("ALLOWANCE_RESERVE", evaluated.guard().reason());
        assertNull(before.withEvaluation(true, null).guard());
        assertSame(before.guard(), before.withEvaluation(false, null).guard());
    }

    @Test
    void historicalSelectionDoesNotReplaceCurrentReceiptAndUnknownFactsStayNull() {
        var unknown = new MossToolSelection.Candidate(3, "minecraft:diamond_hoe", 1, null, null,
                null, null, null, null, null, null, false, "CANDIDATE_UNAVAILABLE");
        JsonObject execution = encoded(selection(List.of(unknown), false), "").getAsJsonObject("execution");
        assertEquals("WAITING", execution.getAsJsonObject("receipt").get("result").getAsString());
        var diagnostic = execution.getAsJsonObject("last_moss_tool_selection");
        assertEquals(CAPTURED.toString(), diagnostic.get("captured_at").getAsString());
        var item = diagnostic.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertTrue(item.get("usable").isJsonNull());
        assertTrue(item.get("identity_tracked").isJsonNull());
        assertTrue(item.get("allowance").isJsonNull());
        assertTrue(item.get("fingerprint_complete").isJsonNull());
        assertFalse(item.get("evaluated").getAsBoolean());
    }

    @Test
    void candidateAndComponentPayloadsAreBoundedImmutableAndContainOnlyIdsAndHashes() {
        var source = new ArrayList<MossToolSelection.Candidate>();
        for (int index = 0; index < 40; index++) { source.add(candidate(index % 9)); }
        var snapshot = selection(source, false);
        source.clear();
        assertEquals(9, snapshot.candidates().size());
        assertTrue(snapshot.truncated());
        JsonObject encoded = encoded(snapshot, "");
        assertTrue(encoded.toString().getBytes(StandardCharsets.UTF_8).length < 40_000);
        var item = encoded.getAsJsonObject("execution").getAsJsonObject("last_moss_tool_selection")
                .getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals(24, item.getAsJsonArray("component_hashes").size());
        assertEquals(2, item.getAsJsonArray("component_hashes").get(0).getAsJsonObject().size());
        assertThrows(IllegalArgumentException.class, () -> new MinecraftMossToolFingerprint.ComponentHash(
                "private raw lore", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftMossToolFingerprint.ComponentHash(
                "minecraft:custom_data", "private raw data"));
        assertThrows(IllegalArgumentException.class, () -> new MossToolSelection.Candidate(0,
                "private raw item label", 1, null, null, null, null, null, null, null, null, false, ""));
    }

    @Test
    void optionalDiagnosticIsPrunedBeforeLiveOrHistoricalReceiptsUnderPayloadPressure() {
        var candidates = IntStream.range(0, 9).mapToObj(index -> withMetadata(candidate(index), true)).toList();
        JsonObject root = encoded(selection(candidates, false), "x".repeat(50_000));
        assertTrue(root.toString().getBytes(StandardCharsets.UTF_8).length <= 65_536);
        JsonObject execution = root.getAsJsonObject("execution");
        assertFalse(execution.get("receipt").isJsonNull());
        assertFalse(execution.get("last_receipt").isJsonNull());
        assertFalse(execution.get("last_failure").isJsonNull());
        var diagnostic = execution.getAsJsonObject("last_moss_tool_selection");
        assertTrue(diagnostic.get("truncated").getAsBoolean());
        assertEquals(0, diagnostic.getAsJsonArray("candidates").size());
        assertEquals("run", root.get("run_id").getAsString());
        assertEquals(7, root.getAsJsonObject("last_control").get("sequence").getAsInt());
    }

    @Test
    void metadataSeparatesCurrentValuesFromHistoricalAppliedPacketAndKeepsTransitionProvenance() {
        var candidate = withMetadata(candidate(3), false);
        var evaluated = candidate.withEvaluation(true, candidate.guard());
        assertSame(candidate.metadataEvidence(), evaluated.metadataEvidence());
        JsonObject item = encoded(selection(List.of(evaluated), false), "").getAsJsonObject("execution")
                .getAsJsonObject("last_moss_tool_selection").getAsJsonArray("candidates").get(0).getAsJsonObject();
        JsonObject evidence = item.getAsJsonObject("custom_data_evidence");
        JsonObject receipt = evidence.getAsJsonObject("latest_applied_slot_receipt");
        assertFalse(receipt.get("exact_current_match").getAsBoolean());
        assertEquals(22, receipt.get("sequence").getAsLong());
        assertEquals(3, receipt.get("slot").getAsInt());
        assertEquals(81, receipt.get("damage").getAsInt());
        JsonObject previous = receipt.getAsJsonObject("previous_applied_slot_receipt");
        assertEquals(11, previous.get("sequence").getAsLong());
        assertEquals(80, previous.get("damage").getAsInt());
        assertTrue(previous.get("other_components_equal").getAsBoolean());
        assertTrue(evidence.getAsJsonObject("current").get("available").getAsBoolean());
        assertFalse(evidence.toString().contains("private-value-do-not-render"));
        assertTrue(receipt.has("custom_data"));
    }

    @Test
    void absentOrFailedMetadataNeverPretendsToBeAnAppliedReceipt() {
        var failed = candidate(0).withMetadataEvidence(new MossToolSelection.MetadataEvidence(
                null, null, "METADATA_UNAVAILABLE"));
        JsonObject evidence = encoded(selection(List.of(failed), false), "").getAsJsonObject("execution")
                .getAsJsonObject("last_moss_tool_selection").getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("custom_data_evidence");
        assertTrue(evidence.get("current").isJsonNull());
        assertTrue(evidence.get("latest_applied_slot_receipt").isJsonNull());
        assertEquals("METADATA_UNAVAILABLE", evidence.get("error").getAsString());
    }

    @Test
    void exactCurrentMatchIsIndependentOfOtherComponentEquality() {
        var source = withMetadata(candidate(0), true);
        var applied = source.metadataEvidence().latestAppliedSlotReceipt();
        var comparison = new MossToolReceiptHistory.Transition(applied.epoch(), 11, 0, 80, false);
        var changed = source.withMetadataEvidence(new MossToolSelection.MetadataEvidence(
                source.metadataEvidence().current(), new MossToolSelection.AppliedMetadata(applied.epoch(), 22, 0,
                        81, true, applied.customData(), comparison), ""));
        JsonObject receipt = encoded(selection(List.of(changed), false), "").getAsJsonObject("execution")
                .getAsJsonObject("last_moss_tool_selection").getAsJsonArray("candidates").get(0).getAsJsonObject()
                .getAsJsonObject("custom_data_evidence").getAsJsonObject("latest_applied_slot_receipt");
        assertTrue(receipt.get("exact_current_match").getAsBoolean());
        assertFalse(receipt.getAsJsonObject("previous_applied_slot_receipt")
                .get("other_components_equal").getAsBoolean());
    }

    private static MossToolSelection.Candidate withMetadata(MossToolSelection.Candidate candidate, boolean exactMatch) {
        NbtCompound data = new NbtCompound();
        for (int index = 0; index < 23; index++) { data.putInt("damage_" + index, 81); }
        data.putString("note", "private-value-do-not-render");
        var probe = MossToolMetadataProbe.capture(data, 81);
        String epoch = "b7e9ec4d-e663-4858-b15a-8f316fbc3725";
        var previous = new MossToolReceiptHistory.Transition(epoch, 11, candidate.slot(), 80, true);
        var applied = new MossToolSelection.AppliedMetadata(epoch, 22, candidate.slot(), 81, exactMatch, probe, previous);
        return candidate.withMetadataEvidence(new MossToolSelection.MetadataEvidence(probe, applied, ""));
    }

    private static MossToolSelection.Candidate candidate(int slot) {
        var components = IntStream.range(0, 24).mapToObj(index ->
                new MinecraftMossToolFingerprint.ComponentHash("minecraft:component_" + index, "a".repeat(64))).toList();
        var fingerprint = new MinecraftMossToolFingerprint.Fingerprint("b".repeat(64), true, components, false);
        var guard = new MossMiningToolGuard.Diagnostic(false, 36, 36, null, null, 64, "IDENTITY_LIMIT");
        return new MossToolSelection.Candidate(slot, "minecraft:diamond_hoe", 1, 81, 1561,
                false, true, 1, 8F, guard, fingerprint, true, "");
    }

    private static MossToolSelection selection(List<MossToolSelection.Candidate> candidates, boolean truncated) {
        return new MossToolSelection(CAPTURED, TARGET, true, "PLAIN_HAND", 7, "minecraft:dirt",
                36, 36, 36, true, candidates, truncated, "");
    }

    private static JsonObject encoded(MossToolSelection selection, String padding) {
        var receipt = new ExecutionObservation.Receipt(CAPTURED.plusSeconds(1), "FLIGHT_CLEARING_MOSS",
                new ExecutionObservation.Target(TARGET, "minecraft:air", "minecraft:moss_block", true),
                false, "dirt", 102, 102L, "WAITING", 13, 100, true,
                true, true, TARGET, 0.533F, 0.066F, 0, 7, "minecraft:dirt", "");
        var execution = new ExecutionObservation(true, "FLIGHT_CLEARING_MOSS", "", null,
                receipt, receipt, receipt, true, true, "", "").withMossToolSelection(selection);
        var base = new AgentObservation("run", "BUILDING", CAPTURED.plusSeconds(2), true, true, true,
                List.of(), List.of("PAUSE", "STOP"), "plan", null, null, "", "Building", padding, 7, "RESUME", "request");
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(base.withExecution(execution)),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
