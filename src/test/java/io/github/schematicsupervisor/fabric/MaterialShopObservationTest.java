package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.schematicsupervisor.core.BlockPosition;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialShopObservationTest {
    private static final Instant NOW = Instant.parse("2026-09-11T20:00:00Z");
    private static final String EPOCH = "11111111-1111-4111-8111-111111111111";
    private static final String NEXT_EPOCH = "22222222-2222-4222-8222-222222222222";
    private static final MaterialPurchaseFacts.Product PRODUCT = MaterialPurchaseFacts.Product.BIRCH_PLANKS;

    @Test void pendingExportContainsOnlyBoundedPurchaseAndReceiptMetadata() {
        MaterialPurchaseJournal journal = pending();
        MaterialShopObservation facts = capture("SETTLING", true, journal);
        JsonObject encoded = encode(base("").withMaterialShop(facts)).getAsJsonObject("material_shop");
        assertEquals(Set.of("available", "active", "stage", "detail", "pending", "product", "quantity",
                "confirmed", "original_stamp", "receipt_stamp", "reconciliation_barrier", "truncated"), encoded.keySet());
        assertEquals("SETTLING", encoded.get("stage").getAsString());
        assertEquals("minecraft:birch_planks", encoded.get("product").getAsString());
        assertEquals(128, encoded.get("quantity").getAsInt());
        assertTrue(encoded.get("pending").getAsBoolean());
        assertFalse(encoded.get("confirmed").getAsBoolean());
        assertEquals(1, encoded.getAsJsonObject("original_stamp").get("full_sequence").getAsLong());
        assertTrue(encoded.get("receipt_stamp").isJsonNull());
        String json = encoded.toString();
        for (String privateValue : List.of("fingerprint", "components", "tripwire_hook", "quoted_price",
                "route_hash", "planId", journal.operationId(), journal.before().context().worldIdentityHash(),
                journal.before().slots().offhand().fingerprint())) {
            assertFalse(json.contains(privateValue), privateValue);
        }
    }

    @Test void completeMeansTheCurrentJournalHasADurableConfirmedReceipt() {
        MaterialShopObservation completed = capture("COMPLETE", false, pending().confirm(after(EPOCH, 2)));
        assertTrue(completed.confirmed());
        assertFalse(completed.pending());
        assertEquals(128, completed.quantity());
        assertEquals(2, completed.receiptStamp().fullSequence());
        assertEquals(1, completed.originalStamp().fullSequence());
        assertNull(completed.reconciliationBarrier());
        MaterialShopObservation merelyNamedComplete = MaterialShopObservation.capture("COMPLETE", false,
                PRODUCT, "No saved receipt", Optional.empty());
        assertFalse(merelyNamedComplete.confirmed());
        assertNull(merelyNamedComplete.receiptStamp());
    }

    @Test void restartBarrierAndOriginalPurchaseRemainDistinct() {
        MaterialPurchaseJournal pending = pending();
        var barrier = new MaterialPurchaseJournal.Observation(pending.before().context(), stamp(NEXT_EPOCH, 1), beforeSlots());
        MaterialShopObservation facts = capture("OPENING_RECEIPT", true, pending.withBarrier(barrier));
        assertEquals(EPOCH, facts.originalStamp().observerEpoch());
        assertEquals(NEXT_EPOCH, facts.reconciliationBarrier().observerEpoch());
        assertNull(facts.receiptStamp());
        assertFalse(facts.confirmed());
    }

    @Test void oldCompletedReceiptsDoNotDescribeANewNavigationOrFailedUnjournaledAttempt() {
        MaterialPurchaseJournal previous = pending().confirm(after(EPOCH, 2));
        for (String stage : List.of("NAVIGATING", "FAILED", "IDLE", "CANCELLED")) {
            MaterialShopObservation fresh = MaterialShopObservation.capture(stage, stage.equals("NAVIGATING"),
                    MaterialPurchaseFacts.Product.GLOWSTONE, "New route", Optional.of(previous));
            assertEquals(MaterialPurchaseFacts.Product.GLOWSTONE, fresh.product());
            assertFalse(fresh.pending());
            assertFalse(fresh.confirmed());
            assertNull(fresh.quantity());
            assertNull(fresh.originalStamp());
            assertNull(fresh.receiptStamp());
            assertNull(fresh.reconciliationBarrier());
        }
    }

    @Test void pausedFailedOrCancelledPendingReceiptKeepsItsOriginalProductAndQuantity() {
        for (String stage : List.of("FAILED", "CANCELLED", "IDLE")) {
            MaterialShopObservation facts = MaterialShopObservation.capture(stage, false,
                    MaterialPurchaseFacts.Product.GLOWSTONE, "Preserve the pending receipt", Optional.of(pending()));
            assertEquals(PRODUCT, facts.product());
            assertEquals(128, facts.quantity());
            assertTrue(facts.pending());
            assertFalse(facts.confirmed());
            assertEquals(EPOCH, facts.originalStamp().observerEpoch());
        }
    }

    @Test void unavailableJournalDoesNotClaimZeroQuantityOrNoPendingPurchase() {
        MaterialShopObservation unknown = MaterialShopObservation.unavailable(false, "Journal unavailable");
        JsonObject encoded = encode(base("").withMaterialShop(unknown)).getAsJsonObject("material_shop");
        assertFalse(encoded.get("available").getAsBoolean());
        assertEquals("UNAVAILABLE", encoded.get("stage").getAsString());
        for (String field : List.of("pending", "product", "quantity", "confirmed", "original_stamp",
                "receipt_stamp", "reconciliation_barrier")) {
            assertTrue(encoded.get(field).isJsonNull(), field);
        }
    }

    @Test void navigationShowsTheSelectedProductWithoutInventingAPurchase() {
        MaterialShopObservation facts = MaterialShopObservation.capture("NAVIGATING", true, PRODUCT,
                "Opening Blocks", Optional.empty());
        assertEquals(PRODUCT, facts.product());
        assertFalse(facts.pending());
        assertFalse(facts.confirmed());
        assertNull(facts.quantity());
        assertNull(facts.originalStamp());
    }

    @Test void boundedSanitizedTextNeverSplitsASupplementaryCharacter() {
        MaterialShopObservation facts = MaterialShopObservation.capture("S".repeat(40), false, null,
                "x".repeat(511) + "\uD83D\uDE00tail", Optional.empty());
        assertEquals(32, facts.stage().length());
        assertEquals(511, facts.detail().length());
        assertTrue(facts.truncated());
        MaterialShopObservation controls = MaterialShopObservation.unavailable(true, "Missing\nreceipt\tdata");
        assertEquals("Missing receipt data", controls.detail());
        assertTrue(controls.truncated());
    }

    @Test void legacyConstructorsAndAllCopyHelpersPreserveIndependentTelemetry() {
        MaterialShopObservation facts = capture("SETTLING", true, pending());
        MossDepositObservation moss = MossDepositObservation.unavailable(false, "No deposit", 0);
        AgentObservation copied = base("").withMaterialShop(facts).withTelemetry(null, null)
                .withTelemetry(null, null, null).withDepots(null).withExecution(null).withMossDeposit(moss);
        assertSame(facts, copied.materialShop());
        assertSame(moss, copied.mossDeposit());
        assertSame(moss, copied.withMaterialShop(facts).mossDeposit());
        assertFalse(encode(base("")).has("material_shop"));
        AgentObservation legacyFull = new AgentObservation("run", "PAUSED", NOW, true, true, true,
                List.of(), List.of("STOP"), "plan", null, null, "", "Ready", "", 0, "", "",
                null, null, null, null, null, moss);
        assertNull(legacyFull.materialShop());
        assertSame(moss, legacyFull.mossDeposit());
    }

    @Test void oversizedOptionalMetadataCannotDisplaceExistingControlAndObstructionEvidence() {
        AgentObservation baseline = criticalBase("");
        int baselineBytes = SupervisorProtocolJson.encodeObservation(baseline).length;
        AgentObservation almostFull = criticalBase("x".repeat(65_536 - baselineBytes - 16));
        JsonObject original = encode(almostFull);
        MaterialShopObservation facts = MaterialShopObservation.capture("SETTLING", true, PRODUCT,
                "石".repeat(512), Optional.of(pending()));
        byte[] bytes = SupervisorProtocolJson.encodeObservation(almostFull.withMaterialShop(facts));
        assertTrue(bytes.length <= 65_536);
        assertEquals(original, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
        assertEquals(8206, original.getAsJsonObject("execution").getAsJsonObject("last_obstruction")
                .getAsJsonObject("target").get("x").getAsInt());
    }

    @Test void spacePressureCanKeepPendingFactsWhileOmittingOnlyOptionalDetailAndStamps() {
        MaterialShopObservation facts = MaterialShopObservation.capture("SETTLING", true, PRODUCT,
                "石".repeat(512), Optional.of(pending()));
        int baselineBytes = SupervisorProtocolJson.encodeObservation(base("")).length;
        AgentObservation nearlyFull = base("x".repeat(65_536 - baselineBytes - 600)).withMaterialShop(facts);
        byte[] bytes = SupervisorProtocolJson.encodeObservation(nearlyFull);
        assertTrue(bytes.length <= 65_536);
        JsonObject encoded = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("material_shop");
        assertNotNull(encoded);
        assertTrue(encoded.get("pending").getAsBoolean());
        assertEquals(128, encoded.get("quantity").getAsInt());
        assertTrue(encoded.get("truncated").getAsBoolean());
        assertEquals("", encoded.get("detail").getAsString());
        assertTrue(encoded.get("original_stamp").isJsonNull());
    }

    @Test void impossibleWholeStackCountsAreRejected() {
        for (int invalid : List.of(0, 1, 63, 65, 577)) {
            assertThrows(IllegalArgumentException.class, () -> new MaterialShopObservation(true, true,
                    "SETTLING", "Waiting", true, PRODUCT, invalid, false, stamp(EPOCH, 1), null, null, false));
        }
    }

    private static MaterialShopObservation capture(String stage, boolean active, MaterialPurchaseJournal journal) {
        return MaterialShopObservation.capture(stage, active, PRODUCT, "Waiting for receipt", Optional.of(journal));
    }

    private static MaterialPurchaseJournal pending() {
        return MaterialPurchaseJournal.pending(new MaterialPurchaseJournal.Observation(
                new MaterialPurchaseJournal.Context("sha256:" + "a".repeat(64), "minecraft:overworld", "private-plan"),
                stamp(EPOCH, 1), beforeSlots()), new MaterialPurchaseFacts.Quote(PRODUCT, 2, 112_000,
                "b".repeat(64), "Buying stacks of Birch Planks", 1));
    }

    private static MaterialPurchaseJournal.Observation after(String epoch, long generation) {
        var main = new ArrayList<>(beforeSlots().main());
        main.set(0, new MaterialPurchaseFacts.StackFacts("c".repeat(64), PRODUCT.itemId(), 64, 64, false, true));
        main.set(1, new MaterialPurchaseFacts.StackFacts("c".repeat(64), PRODUCT.itemId(), 64, 64, false, true));
        return new MaterialPurchaseJournal.Observation(pending().before().context(), stamp(epoch, generation),
                new MaterialPurchaseFacts.Snapshot(main, empty(), protectedItem(), List.of(empty(), empty(), empty(), empty())));
    }

    private static MaterialPurchaseFacts.Snapshot beforeSlots() {
        var main = new ArrayList<>(java.util.Collections.nCopies(36, empty()));
        main.set(10, protectedItem());
        return new MaterialPurchaseFacts.Snapshot(main, empty(), protectedItem(), List.of(empty(), empty(), empty(), empty()));
    }
    private static MaterialPurchaseFacts.StackFacts empty() {
        return new MaterialPurchaseFacts.StackFacts("d".repeat(64), "minecraft:air", 0, 0, true, false);
    }
    private static MaterialPurchaseFacts.StackFacts protectedItem() {
        return new MaterialPurchaseFacts.StackFacts("e".repeat(64), "minecraft:tripwire_hook", 3, 64, false, false);
    }
    private static ServerInventorySnapshotStamp stamp(String epoch, long generation) {
        return new ServerInventorySnapshotStamp(epoch, 1, generation, generation, 7, 0);
    }
    private static AgentObservation base(String navigationDetail) {
        return new AgentObservation("run", "PAUSED", NOW, true, true, true, List.of("Receipt pending"), List.of("STOP"),
                "plan", null, null, "", "Ready", navigationDetail, 7, "PAUSE", "request-7");
    }
    private static AgentObservation criticalBase(String detail) {
        ExecutionObstruction obstruction = new ExecutionObstruction(NOW, "FLIGHT_ORDINARY_SELECTING",
                "An entity occupies the target", new ExecutionObservation.Target(new BlockPosition(8206, -63, -26976),
                "minecraft:dirt", "minecraft:moss_block", true), true, true, false, false, 1,
                List.of(new ExecutionObstruction.EntitySample("minecraft:item", false, false, true, true,
                        "minecraft:moss_block", 1)), false, "");
        return base(detail).withExecution(new ExecutionObservation(true, "FAILED", "Rejected", null,
                null, null, null, false, false, "", "", obstruction));
    }
    private static JsonObject encode(AgentObservation observation) {
        return JsonParser.parseString(new String(SupervisorProtocolJson.encodeObservation(observation),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
