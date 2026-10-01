package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MossDepositObservationTest {
    private static final Instant NOW = Instant.parse("2026-09-11T18:00:00Z");

    @Test
    void pendingMetadataExposesOnlyDestinationQuantityAndPacketIdentity() {
        MossDepositJournal journal = pending();
        MossDepositObservation facts = MossDepositObservation.capture(true, false,
                "Waiting for the reopened chest", 0, Optional.of(journal));
        assertEquals(MossDepositJournal.Stage.PENDING, journal.stage());
        JsonObject encoded = encode(base("").withMossDeposit(facts)).getAsJsonObject("moss_deposit");
        assertEquals(Set.of("available", "active", "stage", "detail", "pending", "destination", "quantity",
                "confirmed_session_items", "item_id", "confirmed_session_pickup_items", "original_stamp", "receipt_stamp", "reconciliation_barrier", "truncated"),
                encoded.keySet());
        assertTrue(encoded.get("pending").getAsBoolean());
        assertEquals("PENDING", encoded.get("stage").getAsString());
        assertEquals(37, encoded.get("quantity").getAsInt());
        assertEquals("minecraft:moss_block", encoded.get("item_id").getAsString());
        assertEquals("depot-004", encoded.getAsJsonObject("destination").get("id").getAsString());
        assertEquals(12, encoded.getAsJsonObject("destination").get("x").getAsInt());
        assertEquals(1, encoded.getAsJsonObject("original_stamp").get("full_sequence").getAsLong());
        assertTrue(encoded.get("receipt_stamp").isJsonNull());
        String json = encoded.toString();
        assertFalse(json.contains("fingerprint"));
        assertFalse(json.contains("components"));
        assertFalse(json.contains("tripwire_hook"));
        assertFalse(json.contains(journal.before().context().worldIdentityHash()));
        assertFalse(json.contains(journal.before().slots().offhand().fingerprint()));
        assertFalse(json.contains("sourceHandlerSlot"));
    }

    @Test void legacyMossCountRemainsSeparateFromAllPickupCount() {
        var facts = MossDepositObservation.capture(false, false, "Stored pickups", 5, 42, Optional.of(pending()));
        var encoded = encode(base("").withMossDeposit(facts)).getAsJsonObject("moss_deposit");
        assertEquals(5, encoded.get("confirmed_session_items").getAsInt());
        assertEquals(42, encoded.get("confirmed_session_pickup_items").getAsInt());
    }

    @Test
    void confirmedAndRestartBarrierStampsStayDistinctFromSessionCounts() {
        MossDepositJournal completed = pending().confirm(MossDepositControllerTest.after());
        MossDepositObservation confirmed = MossDepositObservation.capture(false, false, "Stored", 37,
                Optional.of(completed));
        assertEquals("CONFIRMED", confirmed.stage());
        assertFalse(confirmed.pending());
        assertEquals(37, confirmed.confirmedSessionItems());
        assertEquals(2, confirmed.receiptStamp().fullSequence());
        var barrier = MossDepositControllerTest.observed(MossDepositControllerTest.NEXT_EPOCH, 1, 1, 1,
                MossDepositControllerTest.initial());
        MossDepositObservation restored = MossDepositObservation.capture(true, false, "Reopening", 0,
                Optional.of(pending().withBarrier(barrier)));
        assertEquals(MossDepositControllerTest.EPOCH, restored.originalStamp().observerEpoch());
        assertEquals(MossDepositControllerTest.NEXT_EPOCH, restored.reconciliationBarrier().observerEpoch());
        assertNull(restored.receiptStamp());
        assertEquals(0, restored.confirmedSessionItems());
    }

    @Test
    void anUnjournaledOpeningDoesNotShowAnOldCompletedDestination() {
        MossDepositObservation opening = MossDepositObservation.capture(true, false, "Opening next chest", 0,
                Optional.of(pending().confirm(MossDepositControllerTest.after())));
        assertEquals("ACTIVE", opening.stage());
        assertFalse(opening.pending());
        assertNull(opening.destination());
        assertNull(opening.quantity());
        assertNull(opening.originalStamp());
        assertNull(opening.receiptStamp());
    }

    @Test
    void unavailableEvidenceDoesNotClaimThereIsNoPendingIntent() {
        MossDepositObservation unavailable = MossDepositObservation.unavailable(false, "Journal unavailable", 12);
        JsonObject encoded = encode(base("").withMossDeposit(unavailable)).getAsJsonObject("moss_deposit");
        assertFalse(encoded.get("available").getAsBoolean());
        assertEquals("UNAVAILABLE", encoded.get("stage").getAsString());
        assertTrue(encoded.get("pending").isJsonNull());
        assertTrue(encoded.get("quantity").isJsonNull());
        assertEquals(12, encoded.get("confirmed_session_items").getAsInt());
    }

    @Test
    void boundedTextAndChainedObservationCopiesPreserveDepositFacts() {
        MossDepositObservation facts = MossDepositObservation.capture(false, true,
                "x".repeat(511) + "\uD83D\uDE00tail", 0, Optional.of(pending()));
        assertEquals(511, facts.detail().length());
        assertTrue(facts.truncated());
        assertTrue(facts.pending());
        assertEquals("FAILED", facts.stage());
        AgentObservation copied = base("").withMossDeposit(facts).withTelemetry(null, null, null)
                .withDepots(null).withExecution(null);
        assertSame(facts, copied.mossDeposit());
        assertFalse(encode(base("")).has("moss_deposit"));
        AgentObservation legacyFull = new AgentObservation("run", "PAUSED", NOW, true, true, true,
                List.of(), List.of("STOP"), "plan", null, null, "", "Ready", "", 0, "", "",
                null, null, null, null, null);
        assertNull(legacyFull.mossDeposit());
    }

    @Test
    void optionalDepositMetadataCannotOverflowAnOtherwiseValidPayload() {
        int baselineBytes = SupervisorProtocolJson.encodeObservation(base("")).length;
        AgentObservation almostFull = base("x".repeat(65_536 - baselineBytes - 16));
        JsonObject original = encode(almostFull);
        byte[] bytes = SupervisorProtocolJson.encodeObservation(almostFull.withMossDeposit(
                MossDepositObservation.capture(true, false, "\u77F3".repeat(512), 0, Optional.of(pending()))));
        assertTrue(bytes.length <= 65_536);
        JsonObject encoded = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(original, encoded);
    }

    private static MossDepositJournal pending() {
        var before = MossDepositControllerTest.before();
        return MossDepositJournal.pending(before, MossDepositPlanning.plan(before.slots(), 2).orElseThrow());
    }

    private static AgentObservation base(String navigationDetail) {
        return new AgentObservation("run", "PAUSED", NOW, true, true, true, List.of(), List.of("STOP"),
                "plan", null, null, "", "Ready", navigationDetail, 0, "", "");
    }

    private static JsonObject encode(AgentObservation observation) {
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
