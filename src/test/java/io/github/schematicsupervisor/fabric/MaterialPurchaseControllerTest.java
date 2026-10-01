package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MaterialPurchaseControllerTest {
    static final String EPOCH = "11111111-1111-4111-8111-111111111111";
    static final String NEXT_EPOCH = "22222222-2222-4222-8222-222222222222";
    static final MaterialPurchaseJournal.Context CONTEXT = new MaterialPurchaseJournal.Context(
            "sha256:" + "1".repeat(64), "minecraft:overworld", "test-plan");

    @Test void forcePrecedesTheOnlyClickAndPredictedLocalGainCannotSettle() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        var intent = controller.begin(before(), quote());
        assertEquals(intent, store.value.orElseThrow());
        assertEquals(1, port.clicks);
        assertEquals(2, port.eligibilityChecks);
        assertEquals(MaterialPurchaseController.Status.WAITING,
                controller.reconcile(observed(EPOCH, 1, 1, 1, purchased())).status());
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
        assertEquals(1, port.clicks);
    }

    @Test void exactReceiptIsDurableBeforeSuccessAndDuplicateSettlementIsIdempotent() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        controller.begin(before(), quote());
        assertEquals(MaterialPurchaseController.Status.CONFIRMED, controller.reconcile(after()).status());
        assertEquals(after(), store.value.orElseThrow().receipt());
        assertEquals(MaterialPurchaseController.Status.CONFIRMED, controller.reconcile(after()).status());
        assertEquals(2, store.saves);
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
        assertEquals(1, port.clicks);
    }

    @Test void changedEligibilityBeforeOrDuringForceNeverClicks() throws Exception {
        for (boolean duringForce : List.of(false, true)) {
            FakeStore store = new FakeStore();
            FakePort port = new FakePort(store);
            if (duringForce) { store.afterSave = () -> port.eligible = false; }
            else { port.eligible = false; }
            var controller = new MaterialPurchaseController(store, port);
            assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
            assertEquals(duringForce, store.value.isPresent());
            assertEquals(0, port.clicks);
            if (duringForce) {
                port.eligible = true;
                assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
            }
        }
    }

    @Test void cancellationDuringPersistenceLeavesPendingWithoutDispatch() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        store.afterSave = controller::cancel;
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
        assertEquals(MaterialPurchaseJournal.Stage.PENDING, store.value.orElseThrow().stage());
        assertEquals(0, port.clicks);
        assertThrows(IllegalStateException.class, controller::requestReceiptReopen);
    }

    @Test void failedAndAmbiguousIntentWritesRequireReloadAndNeverClick() throws Exception {
        for (Failure failure : List.of(Failure.BEFORE, Failure.AFTER)) {
            FakeStore store = new FakeStore();
            store.failure = failure;
            FakePort port = new FakePort(store);
            var controller = new MaterialPurchaseController(store, port);
            assertThrows(IOException.class, () -> controller.begin(before(), quote()));
            assertTrue(controller.cancelled());
            assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
            assertEquals(0, port.clicks);
            if (failure == Failure.AFTER) {
                var restored = new MaterialPurchaseController(store, port);
                assertThrows(IllegalStateException.class, () -> restored.begin(before(), quote()));
            }
        }
    }

    @Test void unknownDispatchAndZeroGainNeverPermitRepurchase() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        port.failAfterClick = true;
        assertThrows(IOException.class, () -> new MaterialPurchaseController(store, port).begin(before(), quote()));
        var restored = new MaterialPurchaseController(store, port);
        assertThrows(IllegalStateException.class, () -> restored.begin(before(), quote()));
        assertEquals(MaterialPurchaseController.Status.UNCERTAIN,
                restored.reconcile(observed(EPOCH, 1, 2, 2, initial())).status());
        assertThrows(IllegalStateException.class, () -> restored.begin(after(), quote()));
        assertEquals(MaterialPurchaseController.Status.CONFIRMED, restored.reconcile(after()).status());
        assertEquals(1, port.clicks);
    }

    @Test void newProcessNeedsDurableBarrierAndAnotherReopenedFullPacket() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        new MaterialPurchaseController(store, port).begin(before(), quote());
        var restored = new MaterialPurchaseController(store, port);
        var barrier = observed(NEXT_EPOCH, 1, 1, 1, purchased());
        assertEquals(MaterialPurchaseController.Status.REOPEN_REQUIRED, restored.reconcile(barrier).status());
        assertEquals(barrier.stamp(), store.value.orElseThrow().reconciliationBarrier());
        assertEquals(MaterialPurchaseController.Status.WAITING, restored.reconcile(barrier).status());
        assertEquals(MaterialPurchaseController.Status.WAITING, restored.reconcile(after()).status());
        assertEquals(MaterialPurchaseController.Status.CONFIRMED,
                restored.reconcile(observed(NEXT_EPOCH, 1, 2, 2, purchased())).status());
        assertEquals(1, port.clicks);
    }

    @Test void newConnectionCannotSettleFromAnOldConnectionPacket() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        controller.begin(before(), quote());
        assertEquals(MaterialPurchaseController.Status.REOPEN_REQUIRED,
                controller.reconcile(observed(EPOCH, 2, 3, 3, purchased())).status());
        assertEquals(MaterialPurchaseController.Status.WAITING, controller.reconcile(after()).status());
        assertEquals(MaterialPurchaseController.Status.CONFIRMED,
                controller.reconcile(observed(EPOCH, 2, 4, 4, purchased())).status());
        assertEquals(1, port.clicks);
    }

    @Test void freshReceiptNeedsBothLaterOpenAndFullSequenceRegardlessOfRevision() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        controller.begin(before(), quote());
        for (var candidate : List.of(observed(EPOCH, 1, 1, 2, purchased()), observed(EPOCH, 1, 2, 1, purchased()))) {
            assertEquals(MaterialPurchaseController.Status.WAITING, controller.reconcile(candidate).status());
        }
        assertEquals(MaterialPurchaseController.Status.CONFIRMED, controller.reconcile(after()).status());
    }

    @Test void durableWorldDimensionAndPlanAreNeverReboundForSettlement() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        var intent = controller.begin(before(), quote());
        for (var context : List.of(new MaterialPurchaseJournal.Context("sha256:" + "2".repeat(64), CONTEXT.dimension(), CONTEXT.planId()),
                new MaterialPurchaseJournal.Context(CONTEXT.worldIdentityHash(), "minecraft:the_nether", CONTEXT.planId()),
                new MaterialPurchaseJournal.Context(CONTEXT.worldIdentityHash(), CONTEXT.dimension(), "other-plan"))) {
            assertEquals(MaterialPurchaseController.Status.CONTEXT_MISMATCH, controller.reconcile(
                    new MaterialPurchaseJournal.Observation(context, after().stamp(), purchased())).status());
            assertEquals(intent, store.value.orElseThrow());
        }
    }

    @Test void failedReceiptCommitDoesNotReportSuccessAndRestoreFindsActualCommit() throws Exception {
        for (Failure failure : List.of(Failure.BEFORE, Failure.AFTER)) {
            FakeStore store = new FakeStore();
            FakePort port = new FakePort(store);
            var controller = new MaterialPurchaseController(store, port);
            controller.begin(before(), quote());
            store.failure = failure;
            assertThrows(IOException.class, () -> controller.reconcile(after()));
            assertTrue(controller.cancelled());
            var restored = new MaterialPurchaseController(store, port);
            assertEquals(MaterialPurchaseController.Status.CONFIRMED, restored.reconcile(after()).status());
            assertEquals(1, port.clicks);
        }
    }

    @Test void failedNewSessionBarrierCannotClaimReceiptAndRestoresWithoutAnotherClick() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        controller.begin(before(), quote());
        store.failure = Failure.AFTER;
        var barrier = observed(NEXT_EPOCH, 1, 1, 1, purchased());
        assertThrows(IOException.class, () -> controller.reconcile(barrier));
        var restored = new MaterialPurchaseController(store, port);
        assertEquals(MaterialPurchaseController.Status.WAITING, restored.reconcile(barrier).status());
        assertEquals(MaterialPurchaseController.Status.CONFIRMED,
                restored.reconcile(observed(NEXT_EPOCH, 1, 2, 2, purchased())).status());
        assertEquals(1, port.clicks);
    }

    @Test void cancellationAllowsSettlementButNeverReopenOrNewPurchase() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        controller.begin(before(), quote());
        controller.cancel();
        assertThrows(IllegalStateException.class, controller::requestReceiptReopen);
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), quote()));
        assertEquals(MaterialPurchaseController.Status.CONFIRMED, controller.reconcile(after()).status());
        assertEquals(1, port.clicks);
        assertEquals(0, port.reopens);
    }

    @Test void failedReopenCallsConsumeTheBoundWithoutRetryingPurchases() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MaterialPurchaseController(store, port);
        controller.begin(before(), quote());
        port.failReopen = true;
        assertThrows(IOException.class, controller::requestReceiptReopen);
        assertThrows(IOException.class, controller::requestReceiptReopen);
        assertThrows(IllegalStateException.class, controller::requestReceiptReopen);
        assertEquals(2, controller.reopenRequests());
        assertEquals(2, port.reopens);
        assertEquals(1, port.clicks);
    }

    static MaterialPurchaseFacts.Quote quote() {
        return new MaterialPurchaseFacts.Quote(MaterialPurchaseFacts.Product.GLOWSTONE, 1, 3_200_000,
                "a".repeat(64), "Buying stacks of Glowstone", 0);
    }
    static MaterialPurchaseJournal.Observation before() { return observed(EPOCH, 1, 1, 1, initial()); }
    static MaterialPurchaseJournal.Observation after() { return observed(EPOCH, 1, 2, 2, purchased()); }
    static MaterialPurchaseJournal.Observation observed(String epoch, long context, long open, long sequence,
                                                         MaterialPurchaseFacts.Snapshot slots) {
        return new MaterialPurchaseJournal.Observation(CONTEXT,
                new ServerInventorySnapshotStamp(epoch, context, open, sequence, 1, 0), slots);
    }
    static MaterialPurchaseFacts.Snapshot initial() {
        List<MaterialPurchaseFacts.StackFacts> main = new ArrayList<>();
        for (int index = 0; index < 36; index++) { main.add(empty()); }
        main.set(0, stack("minecraft:glowstone", 32, true));
        main.set(35, stack("minecraft:tripwire_hook", 1, false));
        return new MaterialPurchaseFacts.Snapshot(main, empty(), stack("minecraft:tripwire_hook", 1, false),
                List.of(empty(), empty(), empty(), empty()));
    }
    static MaterialPurchaseFacts.Snapshot purchased() {
        return replaceMain(replaceMain(initial(), 0, stack("minecraft:glowstone", 64, true)),
                1, stack("minecraft:glowstone", 32, true));
    }
    static MaterialPurchaseFacts.Snapshot replaceMain(MaterialPurchaseFacts.Snapshot source, int index,
                                                        MaterialPurchaseFacts.StackFacts stack) {
        var main = new ArrayList<>(source.main());
        main.set(index, stack);
        return new MaterialPurchaseFacts.Snapshot(main, source.cursor(), source.offhand(), source.armor());
    }
    static MaterialPurchaseFacts.StackFacts empty() {
        return new MaterialPurchaseFacts.StackFacts("0".repeat(64), "minecraft:air", 0, 0, true, false);
    }
    static MaterialPurchaseFacts.StackFacts stack(String item, int count, boolean plain) {
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((item + ":" + count + ":" + plain).getBytes(StandardCharsets.UTF_8)));
            return new MaterialPurchaseFacts.StackFacts(fingerprint, item, count, 64, false, plain);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    enum Failure { NONE, BEFORE, AFTER }
    static class FakeStore implements MaterialPurchaseController.Store {
        Optional<MaterialPurchaseJournal> value = Optional.empty();
        Failure failure = Failure.NONE;
        int saves;
        Runnable afterSave = () -> { };
        public Optional<MaterialPurchaseJournal> load() { return value; }
        public void replace(Optional<MaterialPurchaseJournal> expected, MaterialPurchaseJournal replacement) throws IOException {
            if (!value.equals(expected)) { throw new IOException("changed journal"); }
            Failure pending = failure;
            failure = Failure.NONE;
            if (pending == Failure.BEFORE) { throw new IOException("write failed before commit"); }
            value = Optional.of(replacement);
            saves++;
            afterSave.run();
            if (pending == Failure.AFTER) { throw new IOException("write outcome uncertain"); }
        }
    }
    static class FakePort implements MaterialPurchaseController.Port {
        final FakeStore store;
        boolean eligible = true;
        boolean failAfterClick;
        boolean failReopen;
        int clicks;
        int reopens;
        int eligibilityChecks;
        FakePort(FakeStore store) { this.store = store; }
        public boolean matchesForDispatch(MaterialPurchaseJournal.Observation before, MaterialPurchaseFacts.Quote quote) {
            eligibilityChecks++;
            return eligible;
        }
        public void purchaseOnce(MaterialPurchaseJournal intent) throws IOException {
            assertEquals(intent, store.value.orElseThrow());
            assertEquals(MaterialPurchaseJournal.Stage.PENDING, intent.stage());
            if (!eligible) { throw new IOException("final eligibility changed"); }
            clicks++;
            if (failAfterClick) { throw new IOException("unknown purchase outcome"); }
        }
        public void requestReceiptReopen(MaterialPurchaseJournal.Context context) throws IOException {
            assertEquals(CONTEXT, context);
            reopens++;
            if (failReopen) { throw new IOException("unknown reopen outcome"); }
        }
    }
}
