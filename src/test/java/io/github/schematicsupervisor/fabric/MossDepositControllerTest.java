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

class MossDepositControllerTest {
    static final String EPOCH = "11111111-1111-4111-8111-111111111111";
    static final String NEXT_EPOCH = "22222222-2222-4222-8222-222222222222";
    static final MossDepositJournal.Context CONTEXT = new MossDepositJournal.Context(
            "sha256:" + "1".repeat(64), "minecraft:overworld", "test-plan", "depot-004", 12, 64, 15);

    @Test void durableIntentPrecedesTheOnlyClickAndLocalPredictionIsInsufficient() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        var intent = controller.begin(before(), 2);
        assertEquals(intent, store.value.orElseThrow());
        assertEquals(1, port.clicks);
        assertEquals(1, store.saves);
        var prediction = new MossDepositJournal.Observation(CONTEXT, before().stamp(), deposited());
        assertEquals(MossDepositController.Status.WAITING, controller.reconcile(prediction).status());
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), 2));
        assertEquals(1, port.clicks);
        assertEquals(MossDepositJournal.Stage.PENDING, store.value.orElseThrow().stage());
    }

    @Test void exactReopenedReceiptIsSavedBeforeSuccessAndRepeatedSettlementIsIdempotent() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        controller.begin(before(), 2);
        assertEquals(MossDepositController.Status.CONFIRMED, controller.reconcile(after()).status());
        assertEquals(MossDepositJournal.Stage.CONFIRMED, store.value.orElseThrow().stage());
        assertEquals(after(), store.value.orElseThrow().receipt());
        assertEquals(MossDepositController.Status.CONFIRMED, controller.reconcile(after()).status());
        assertEquals(2, store.saves);
        assertEquals(1, port.clicks);
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), 2));
        assertEquals(1, port.clicks);
    }

    @Test void capacityAndSourceEligibilityRequireActualCompleteSlotProof() throws Exception {
        var initial = before().slots();
        List<MossDepositFacts.SlotFacts> full = new ArrayList<>();
        for (var slot : initial.chest()) { full.add(withStack(slot, stack("minecraft:dirt", 64, false))); }
        var noRoom = new MossDepositFacts.Snapshot(full, initial.main(), initial.cursor(), initial.offhand(), initial.armor());
        var customSource = replaceMain(initial, 2, stack("minecraft:moss_block", 37, false));
        var heldCursor = new MossDepositFacts.Snapshot(initial.chest(), initial.main(),
                stack("minecraft:tripwire_hook", 1, false), initial.offhand(), initial.armor());
        for (var slots : List.of(noRoom, customSource, heldCursor)) {
            FakeStore store = new FakeStore();
            FakePort port = new FakePort(store);
            MossDepositController controller = new MossDepositController(store, port);
            assertThrows(IllegalStateException.class, () -> controller.begin(
                    new MossDepositJournal.Observation(CONTEXT, before().stamp(), slots), 2));
            assertTrue(store.value.isEmpty());
            assertEquals(0, port.clicks);
        }
    }

    @Test void changedFinalEligibilityCreatesNoIntentOrClick() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        port.eligible = false;
        MossDepositController controller = new MossDepositController(store, port);
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), 2));
        assertTrue(store.value.isEmpty());
        assertEquals(0, port.clicks);
    }

    @Test void adapterMustRecheckAfterTheDurableWriteAndNeverReplayARejectedDispatch() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        store.afterSave = () -> port.eligible = false;
        MossDepositController controller = new MossDepositController(store, port);
        assertThrows(IOException.class, () -> controller.begin(before(), 2));
        assertEquals(MossDepositJournal.Stage.PENDING, store.value.orElseThrow().stage());
        assertEquals(0, port.clicks);
        port.eligible = true;
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), 2));
        assertEquals(0, port.clicks);
    }

    @Test void failedIntentSavePreventsClickAndRetainsMemory() throws Exception {
        FakeStore store = new FakeStore();
        store.failure = Failure.BEFORE;
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        assertThrows(IOException.class, () -> controller.begin(before(), 2));
        assertTrue(controller.currentJournal().isEmpty());
        assertTrue(store.value.isEmpty());
        assertEquals(0, port.clicks);
    }

    @Test void cancellationDuringJournalWriteLeavesPendingIntentWithoutDispatch() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        store.afterSave = controller::cancel;
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), 2));
        assertTrue(controller.cancelled());
        assertEquals(MossDepositJournal.Stage.PENDING, store.value.orElseThrow().stage());
        assertEquals(0, port.clicks);
        assertThrows(IllegalStateException.class, controller::requestReceiptReopen);
    }

    @Test void ambiguousIntentWriteCannotLeadToAnotherClickEvenBeforeReload() throws Exception {
        FakeStore store = new FakeStore();
        store.failure = Failure.AFTER;
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        assertThrows(IOException.class, () -> controller.begin(before(), 2));
        assertTrue(controller.currentJournal().isEmpty());
        assertTrue(store.value.isPresent());
        assertThrows(IOException.class, () -> controller.begin(before(), 2));
        MossDepositController restored = new MossDepositController(store, port);
        assertThrows(IllegalStateException.class, () -> restored.begin(before(), 2));
        assertEquals(0, port.clicks);
    }

    @Test void unknownClickOutcomeAndRestoredIntentCanOnlyReconcile() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        port.failAfterClick = true;
        MossDepositController controller = new MossDepositController(store, port);
        assertThrows(IOException.class, () -> controller.begin(before(), 2));
        MossDepositController restored = new MossDepositController(store, port);
        assertThrows(IllegalStateException.class, () -> restored.begin(before(), 2));
        var unchanged = new MossDepositJournal.Observation(CONTEXT, after().stamp(), before().slots());
        assertEquals(MossDepositController.Status.UNCERTAIN, restored.reconcile(unchanged).status());
        assertEquals(MossDepositController.Status.CONFIRMED, restored.reconcile(after()).status());
        assertEquals(1, port.clicks);
    }

    @Test void processRestartRequiresItsOwnDurableBarrierAndAnotherFullReopen() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        new MossDepositController(store, port).begin(before(), 2);
        MossDepositController restored = new MossDepositController(store, port);
        var barrier = observed(NEXT_EPOCH, 1, 1, 1, deposited());
        assertEquals(MossDepositController.Status.REOPEN_REQUIRED, restored.reconcile(barrier).status());
        assertEquals(barrier.stamp(), store.value.orElseThrow().reconciliationBarrier());
        assertEquals(MossDepositController.Status.WAITING, restored.reconcile(barrier).status());
        assertEquals(MossDepositController.Status.WAITING, restored.reconcile(after()).status());
        assertEquals(MossDepositController.Status.CONFIRMED,
                restored.reconcile(observed(NEXT_EPOCH, 1, 2, 2, deposited())).status());
        assertEquals(1, port.clicks);
        assertEquals(0, port.reopens);
    }

    @Test void connectionChangeRequiresBarrierAndCannotGoBackToOldContextGeneration() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        controller.begin(before(), 2);
        assertEquals(MossDepositController.Status.REOPEN_REQUIRED,
                controller.reconcile(observed(EPOCH, 2, 3, 3, deposited())).status());
        assertEquals(MossDepositController.Status.WAITING, controller.reconcile(after()).status());
        assertEquals(MossDepositController.Status.CONFIRMED,
                controller.reconcile(observed(EPOCH, 2, 4, 4, deposited())).status());
        assertEquals(1, port.clicks);
    }

    @Test void failedBarrierOrReceiptWriteNeverReportsSuccessAndCanBeReconciledAgain() throws Exception {
        for (boolean barrier : List.of(false, true)) {
            FakeStore store = new FakeStore();
            FakePort port = new FakePort(store);
            MossDepositController controller = new MossDepositController(store, port);
            var intent = controller.begin(before(), 2);
            store.failure = Failure.BEFORE;
            var candidate = barrier ? observed(NEXT_EPOCH, 1, 1, 1, deposited()) : after();
            assertThrows(IOException.class, () -> controller.reconcile(candidate));
            assertEquals(intent, controller.currentJournal().orElseThrow());
            assertEquals(intent, store.value.orElseThrow());
            assertEquals(barrier ? MossDepositController.Status.REOPEN_REQUIRED : MossDepositController.Status.CONFIRMED,
                    controller.reconcile(candidate).status());
            assertEquals(1, port.clicks);
        }
    }

    @Test void confirmationCommittedBeforeErrorIsRecoveredWithoutAnotherTransfer() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        controller.begin(before(), 2);
        store.failure = Failure.AFTER;
        assertThrows(IOException.class, () -> controller.reconcile(after()));
        assertEquals(MossDepositJournal.Stage.PENDING, controller.currentJournal().orElseThrow().stage());
        assertEquals(MossDepositJournal.Stage.CONFIRMED, store.value.orElseThrow().stage());
        var restored = new MossDepositController(store, port);
        assertEquals(MossDepositController.Status.CONFIRMED, restored.reconcile(after()).status());
        assertEquals(1, port.clicks);
    }

    @Test void worldPlanAndPhysicalDepotChangesCannotSettleTheOriginalIntent() throws Exception {
        var original = CONTEXT;
        List<MossDepositJournal.Context> mismatches = List.of(
                new MossDepositJournal.Context("sha256:" + "2".repeat(64), original.dimension(), original.planId(), original.depotId(), 12, 64, 15),
                new MossDepositJournal.Context(original.worldIdentityHash(), "minecraft:the_nether", original.planId(), original.depotId(), 12, 64, 15),
                new MossDepositJournal.Context(original.worldIdentityHash(), original.dimension(), "other-plan", original.depotId(), 12, 64, 15),
                new MossDepositJournal.Context(original.worldIdentityHash(), original.dimension(), original.planId(), "other-depot", 12, 64, 15),
                new MossDepositJournal.Context(original.worldIdentityHash(), original.dimension(), original.planId(), original.depotId(), 13, 64, 15));
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        var originalIntent = controller.begin(before(), 2);
        for (var context : mismatches) {
            assertEquals(MossDepositController.Status.CONTEXT_MISMATCH,
                    controller.reconcile(new MossDepositJournal.Observation(context, after().stamp(), deposited())).status());
            assertEquals(originalIntent, store.value.orElseThrow());
        }
        assertEquals(1, port.clicks);
    }

    @Test void packetFreshnessRequiresBothAnotherOpenAndAnotherFullSequence() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        controller.begin(before(), 2);
        for (var candidate : List.of(observed(EPOCH, 1, 1, 2, deposited()), observed(EPOCH, 1, 2, 1, deposited()))) {
            assertEquals(MossDepositController.Status.WAITING, controller.reconcile(candidate).status());
        }
        // Revision/sync ID may reset or repeat; only the observer's open/full generations order packets.
        var reusedSync = new MossDepositJournal.Observation(CONTEXT,
                new ServerInventorySnapshotStamp(EPOCH, 1, 2, 2, 1, 0), deposited());
        assertEquals(MossDepositController.Status.CONFIRMED, controller.reconcile(reusedSync).status());
    }

    @Test void oneSidedDeltasProtectedSlotChangesAndCursorContentsRemainUncertain() throws Exception {
        var initial = before().slots();
        var success = deposited();
        var playerOnly = new MossDepositFacts.Snapshot(initial.chest(), success.main(), success.cursor(), success.offhand(), success.armor());
        var chestOnly = new MossDepositFacts.Snapshot(success.chest(), initial.main(), success.cursor(), success.offhand(), success.armor());
        var protectedChanged = replaceMain(success, 5, stack("minecraft:tripwire_hook", 5, false));
        var cursorChanged = new MossDepositFacts.Snapshot(success.chest(), success.main(),
                stack("minecraft:moss_block", 1, true), success.offhand(), success.armor());
        var equipmentChanged = new MossDepositFacts.Snapshot(success.chest(), success.main(), success.cursor(), empty(), success.armor());
        for (var invalid : List.of(playerOnly, chestOnly, protectedChanged, cursorChanged, equipmentChanged)) {
            FakeStore store = new FakeStore();
            FakePort port = new FakePort(store);
            MossDepositController controller = new MossDepositController(store, port);
            var intent = controller.begin(before(), 2);
            assertEquals(MossDepositController.Status.UNCERTAIN,
                    controller.reconcile(new MossDepositJournal.Observation(CONTEXT, after().stamp(), invalid)).status());
            assertEquals(intent, store.value.orElseThrow());
            assertEquals(1, port.clicks);
        }
    }

    @Test void cancellationBlocksAllNewActionsButAllowsReadOnlyReceiptSettlement() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        controller.begin(before(), 2);
        controller.cancel();
        assertThrows(IllegalStateException.class, controller::requestReceiptReopen);
        assertThrows(IllegalStateException.class, () -> controller.begin(before(), 2));
        assertEquals(MossDepositController.Status.CONFIRMED, controller.reconcile(after()).status());
        assertTrue(controller.cancelled());
        assertEquals(0, port.reopens);
        assertEquals(1, port.clicks);
    }

    @Test void failedReopenRequestsUseTheSameBoundWithoutRetryingClicks() throws Exception {
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        MossDepositController controller = new MossDepositController(store, port);
        controller.begin(before(), 2);
        port.failReopen = true;
        for (int attempt = 0; attempt < MossDepositController.MAXIMUM_REOPEN_REQUESTS; attempt++) {
            assertThrows(IOException.class, controller::requestReceiptReopen);
        }
        assertThrows(IllegalStateException.class, controller::requestReceiptReopen);
        assertEquals(2, port.reopens);
        assertEquals(1, port.clicks);
        assertEquals(MossDepositJournal.Stage.PENDING, store.value.orElseThrow().stage());
    }

    @Test void partialTransferHasOneDurableIntentAndCannotConfirmFromLocalPredictionOrReplay() throws Exception {
        var initial = initial();
        var chest = new ArrayList<>(initial.chest());
        chest.set(2, withStack(chest.get(2), stack("minecraft:dirt", 64, false)));
        var partialBefore = new MossDepositFacts.Snapshot(chest, initial.main(), initial.cursor(), initial.offhand(), initial.armor());
        FakeStore store = new FakeStore();
        FakePort port = new FakePort(store);
        var controller = new MossDepositController(store, port);
        var intent = controller.begin(observed(EPOCH, 1, 1, 1, partialBefore), 2);
        assertEquals(4, intent.plan().quantity());
        assertEquals(1, port.clicks);
        chest.set(1, withStack(chest.get(1), stack("minecraft:moss_block", 64, true)));
        var partialAfter = replaceMain(new MossDepositFacts.Snapshot(chest, initial.main(), initial.cursor(),
                initial.offhand(), initial.armor()), 2, stack("minecraft:moss_block", 33, true));
        assertEquals(MossDepositController.Status.WAITING,
                controller.reconcile(observed(EPOCH, 1, 1, 1, partialAfter)).status());
        var restored = new MossDepositController(store, port);
        assertThrows(IllegalStateException.class, () -> restored.begin(observed(EPOCH, 1, 2, 2, partialAfter), 2));
        assertEquals(MossDepositController.Status.CONFIRMED,
                restored.reconcile(observed(EPOCH, 1, 2, 2, partialAfter)).status());
        assertEquals(1, port.clicks);
        assertEquals(33, store.value.orElseThrow().receipt().slots().main().get(2).stack().count());
    }

    static MossDepositJournal.Observation before() { return observed(EPOCH, 1, 1, 1, initial()); }
    static MossDepositJournal.Observation after() { return observed(EPOCH, 1, 2, 2, deposited()); }
    static MossDepositJournal.Observation observed(String epoch, long context, long open, long sequence,
                                                   MossDepositFacts.Snapshot slots) {
        return new MossDepositJournal.Observation(CONTEXT,
                new ServerInventorySnapshotStamp(epoch, context, open, sequence, 1, 0), slots);
    }
    static MossDepositFacts.Snapshot initial() {
        List<MossDepositFacts.SlotFacts> chest = new ArrayList<>();
        for (int index = 0; index < 27; index++) {
            var value = index == 1 ? stack("minecraft:moss_block", 60, true)
                    : index == 2 ? empty() : stack("minecraft:dirt", 64, false);
            chest.add(new MossDepositFacts.SlotFacts(index, index, value, true, true, 64));
        }
        List<MossDepositFacts.SlotFacts> main = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            var value = index == 2 ? stack("minecraft:moss_block", 37, true) : stack("minecraft:dirt", 64, false);
            main.add(new MossDepositFacts.SlotFacts(MossDepositFacts.mainHandlerSlot(27, index), index, value, true, true, 64));
        }
        return new MossDepositFacts.Snapshot(chest, main, empty(), stack("minecraft:tripwire_hook", 1, false),
                List.of(empty(), empty(), empty(), empty()));
    }
    static MossDepositFacts.Snapshot deposited() {
        var initial = initial();
        List<MossDepositFacts.SlotFacts> chest = new ArrayList<>(initial.chest());
        chest.set(1, withStack(chest.get(1), stack("minecraft:moss_block", 64, true)));
        chest.set(2, withStack(chest.get(2), stack("minecraft:moss_block", 33, true)));
        return replaceMain(new MossDepositFacts.Snapshot(chest, initial.main(), initial.cursor(), initial.offhand(), initial.armor()), 2, empty());
    }
    static MossDepositFacts.Snapshot replaceMain(MossDepositFacts.Snapshot snapshot, int index, MossDepositFacts.StackFacts value) {
        List<MossDepositFacts.SlotFacts> main = new ArrayList<>(snapshot.main());
        main.set(index, withStack(main.get(index), value));
        return new MossDepositFacts.Snapshot(snapshot.chest(), main, snapshot.cursor(), snapshot.offhand(), snapshot.armor());
    }
    static MossDepositFacts.SlotFacts withStack(MossDepositFacts.SlotFacts slot, MossDepositFacts.StackFacts stack) {
        return new MossDepositFacts.SlotFacts(slot.handlerSlot(), slot.inventoryIndex(), stack, slot.canTake(), slot.insertion());
    }
    static MossDepositFacts.StackFacts empty() { return new MossDepositFacts.StackFacts("0".repeat(64), "minecraft:air", 0, 0, true, false); }
    static MossDepositFacts.StackFacts stack(String item, int count, boolean plain) {
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((item + ":" + count + ":" + plain).getBytes(StandardCharsets.UTF_8)));
            return new MossDepositFacts.StackFacts(fingerprint, item, count, 64, false, plain);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    enum Failure { NONE, BEFORE, AFTER }
    static class FakeStore implements MossDepositController.Store {
        Optional<MossDepositJournal> value = Optional.empty();
        Failure failure = Failure.NONE;
        int saves;
        Runnable afterSave = () -> { };
        public Optional<MossDepositJournal> load() { return value; }
        public void replace(Optional<MossDepositJournal> expected, MossDepositJournal replacement) throws IOException {
            if (!value.equals(expected)) { throw new IOException("changed journal"); }
            Failure currentFailure = failure;
            failure = Failure.NONE;
            if (currentFailure == Failure.BEFORE) { throw new IOException("write failed before commit"); }
            value = Optional.of(replacement);
            saves++;
            afterSave.run();
            if (currentFailure == Failure.AFTER) { throw new IOException("write outcome was uncertain"); }
        }
    }
    static class FakePort implements MossDepositController.Port {
        final FakeStore store;
        boolean eligible = true;
        boolean failAfterClick;
        boolean failReopen;
        int clicks;
        int reopens;
        FakePort(FakeStore store) { this.store = store; }
        public boolean matchesForDispatch(MossDepositJournal.Observation before, MossDepositFacts.Plan plan) { return eligible; }
        public void quickMoveOnce(MossDepositJournal intent) throws IOException {
            assertEquals(intent, store.value.orElseThrow());
            assertEquals(MossDepositJournal.Stage.PENDING, intent.stage());
            if (!eligible) { throw new IOException("final context guard changed"); }
            clicks++;
            if (failAfterClick) { throw new IOException("unknown transfer outcome"); }
        }
        public void requestReceiptReopen(MossDepositJournal.Context context) throws IOException {
            assertEquals(CONTEXT, context);
            reopens++;
            if (failReopen) { throw new IOException("unknown reopen outcome"); }
        }
    }
}
