package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.SurplusDisposalPolicyTest.*;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.replaceMain;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.empty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InPlaceSurplusDisposalTest {
    @TempDir Path temporary;

    @Test void shopReceiptHasDistinctAuthorizationAndNoInventedPhysicalChestOrVoidSite() {
        var proof = localProof();
        assertEquals(SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP, proof.mode());
        var intent = SurplusDisposalJournal.pendingAuthorized(proof, 2, null, PLAYER, NOW);
        assertNull(intent.site());
        assertEquals(SurplusDisposalAuthorization.SHOP_RECEIPT_ID, intent.before().context().depotId());
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalJournal.pendingAuthorized(proof, 2, SITE, PLAYER, NOW));
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalAuthorization.direct(proof.registered(), proof.finalInventory(), NOW));
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalAuthorization.inPlace(
                SurplusDisposalAuthorizationTest.availableStorage(), NOW));
    }

    @Test void exactReceiptConfirmsOnlyOneDurablyRecordedDropAndCannotReuseEvidence() throws Exception {
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port);
        var proof = localProof();
        controller.beginAuthorized(true, proof, null, PLAYER, NOW);
        assertEquals(1, port.throwsSent);
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcile(proof.finalInventory()));
        assertEquals(SurplusDisposalController.Result.CONFIRMED, controller.reconcile(after(proof)));
        assertEquals(1, port.throwsSent);
        assertThrows(IllegalStateException.class, () -> controller.beginAuthorized(true, proof, null, PLAYER, NOW));
    }

    @Test void unknownThrowAndRestartNeverAuthorizeAnotherDrop() throws Exception {
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store); port.failAfterThrow = true;
        var controller = new SurplusDisposalController(store, port); var proof = localProof();
        assertThrows(IOException.class, () -> controller.beginAuthorized(true, proof, null, PLAYER, NOW));
        var restarted = new SurplusDisposalController(store, port);
        assertThrows(IllegalStateException.class, () -> restarted.beginAuthorized(true, proof, null, PLAYER, NOW));
        assertEquals(SurplusDisposalController.Result.CONFIRMED, restarted.reconcile(after(proof)));
        assertEquals(1, port.throwsSent);
    }

    @Test void protectedSlotChangesOrRecollectedStackStayPending() throws Exception {
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port); var proof = localProof();
        controller.beginAuthorized(true, proof, null, PLAYER, NOW);
        var received = after(proof);
        assertEquals(SurplusDisposalController.Result.UNCERTAIN,
                controller.reconcile(withSlots(received, proof.finalInventory().slots())));
        assertEquals(SurplusDisposalController.Result.UNCERTAIN,
                controller.reconcile(withSlots(received, replaceMain(received.slots(), 0, empty()))));
        assertEquals(SurplusDisposalJournal.Stage.PENDING, store.value.orElseThrow().stage());
        assertEquals(1, port.throwsSent);
    }

    @Test void reconnectNeedsTwoIndependentNewSessionReceiptsWithoutAnotherDrop() throws Exception {
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port); var proof = localProof();
        controller.beginAuthorized(true, proof, null, PLAYER, NOW);
        var received = after(proof);
        var first = new MossDepositJournal.Observation(received.context(), new ServerInventorySnapshotStamp(
                MossDepositControllerTest.NEXT_EPOCH, 1, 1, 1, 1, 0), received.slots());
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcile(first));
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcile(first));
        var second = new MossDepositJournal.Observation(received.context(), new ServerInventorySnapshotStamp(
                MossDepositControllerTest.NEXT_EPOCH, 1, 2, 2, 2, 0), received.slots());
        assertEquals(SurplusDisposalController.Result.CONFIRMED, controller.reconcile(second));
        assertEquals(1, port.throwsSent);
    }

    @Test void cancellationOrUncertainJournalWritePreventsInput() throws Exception {
        for (boolean commitFirst : new boolean[] {false, true}) {
            var store = new SurplusDisposalControllerTest.Store();
            var port = new SurplusDisposalControllerTest.Port(store);
            var controller = new SurplusDisposalController(store, port);
            store.failWrite = true; store.commitBeforeFailure = commitFirst;
            assertThrows(IOException.class, () -> controller.beginAuthorized(true, localProof(), null, PLAYER, NOW));
            assertEquals(0, port.throwsSent);
            store.failWrite = false;
            assertThrows(IllegalStateException.class, () -> controller.beginAuthorized(true, localProof(), null, PLAYER, NOW));
        }
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port);
        store.afterSave = controller::cancel;
        assertThrows(IllegalStateException.class, () -> controller.beginAuthorized(true, localProof(), null, PLAYER, NOW));
        assertEquals(0, port.throwsSent);
    }

    @Test void localJournalRoundTripsInItsOwnVersionAndOlderVersionsCannotClaimLocalAdmission() throws Exception {
        Path path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path); var proof = localProof();
        var intent = SurplusDisposalJournal.pendingAuthorized(proof, 2, null, PLAYER, NOW);
        store.replace(Optional.empty(), intent);
        assertEquals(intent, store.load().orElseThrow());
        String valid = Files.readString(path);
        assertTrue(valid.contains("\"version\":4"));
        Files.writeString(path, valid.replace("\"version\":4", "\"version\":2"));
        assertThrows(IOException.class, store::load);
        Files.writeString(path, valid);
        store.replace(Optional.of(intent), intent.confirm(after(proof)));
        assertEquals(SurplusDisposalJournal.Stage.CONFIRMED, store.load().orElseThrow().stage());
    }

    @Test void localRefreshRequiresLaterReceiptAndCannotRewritePendingAuthorization() {
        var proof = localProof();
        assertTrue(proof.canRefresh(false, 0));
        assertFalse(proof.canRefresh(true, 0));
        assertFalse(proof.canRefresh(false, 2));
        assertThrows(IllegalArgumentException.class, () -> proof.refresh(proof.finalInventory(), NOW));
        var refreshed = proof.refresh(withSlots(after(proof), proof.finalInventory().slots()), NOW);
        assertEquals(SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP, refreshed.mode());
    }

    @Test void unrelatedSeedPickupsReconcileDurablyAfterRestartWithoutDispatch() throws Exception {
        var observed = localProof().finalInventory();
        var before = replaceMain(observed.slots(), 25, MossDepositControllerTest.stack("minecraft:melon_seeds", 64, true));
        before = replaceMain(before, 24, empty());
        before = replaceMain(before, 26, MossDepositControllerTest.stack("minecraft:pumpkin_seeds", 24, true));
        before = replaceMain(before, 27, MossDepositControllerTest.stack("minecraft:melon_seeds", 8, true));
        var proof = SurplusDisposalAuthorization.inPlace(withSlots(observed, before), NOW);
        var intent = SurplusDisposalJournal.pendingAuthorized(proof, 25, null, PLAYER, NOW);
        var store = new SurplusDisposalFileStore(temporary.resolve("pickup-reconciliation.json"));
        store.replace(Optional.empty(), intent);
        var noInput = new SurplusDisposalController.Port() {
            public boolean matchesForDispatch(SurplusDisposalJournal value) { fail("Restored intent cannot dispatch"); return false; }
            public void throwOnce(SurplusDisposalJournal value) { fail("Restored intent cannot replay a discard"); }
        };
        var restored = new SurplusDisposalController(store, noInput);
        var pickedUp = replaceMain(before, 25, empty());
        pickedUp = replaceMain(pickedUp, 24, MossDepositControllerTest.stack("minecraft:pumpkin_seeds", 33, true));
        pickedUp = replaceMain(pickedUp, 26, MossDepositControllerTest.stack("minecraft:pumpkin_seeds", 64, true));
        var receipt = withSlots(after(proof), pickedUp);
        assertEquals(SurplusDisposalController.Result.WAITING, restored.reconcile(withSlots(observed, pickedUp)));
        for (var unsafe : List.of(
                replaceMain(pickedUp, 25, MossDepositControllerTest.stack("minecraft:melon_seeds", 12, true)),
                replaceMain(pickedUp, 27, MossDepositControllerTest.stack("minecraft:melon_seeds", 64, true)),
                replaceMain(pickedUp, 26, MossDepositControllerTest.stack("minecraft:pumpkin_seeds", 23, true)),
                replaceMain(pickedUp, 24, MossDepositControllerTest.stack("minecraft:pumpkin_seeds", 33, false)),
                replaceMain(pickedUp, 26, MossDepositControllerTest.stack("minecraft:moss_block", 64, true)),
                replaceMain(pickedUp, 0, empty()))) {
            assertEquals(SurplusDisposalController.Result.UNCERTAIN, restored.reconcile(withSlots(receipt, unsafe)));
            assertEquals(intent, store.load().orElseThrow());
        }
        assertThrows(IllegalStateException.class, () -> restored.beginAuthorized(true, proof, null, PLAYER, NOW));
        assertEquals(SurplusDisposalController.Result.CONFIRMED, restored.reconcile(receipt));
        var saved = store.load().orElseThrow();
        assertEquals(intent.operationId(), saved.operationId());
        assertEquals(intent.before(), saved.before());
        assertEquals(receipt, saved.receipt());
        assertEquals(SurplusDisposalJournal.Stage.CONFIRMED, new SurplusDisposalController(store, noInput)
                .journal().orElseThrow().stage());
    }

    @Test void stalePostDiscardReadRefreshKeepsOriginalIntentUntilTwoStablePickupReceipts() throws Exception {
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port);
        var proof = localProof();
        controller.beginAuthorized(true, proof, null, PLAYER, NOW);
        var pending = store.value.orElseThrow();
        var after = after(proof);
        var refresh = new StaleInventoryReceiptRefresh();
        for (int tick = 0; tick < 19; tick++) { assertFalse(refresh.observe(after.stamp())); }
        assertTrue(refresh.observe(after.stamp()));
        assertEquals(pending, store.value.orElseThrow());
        assertEquals(1, port.throwsSent);
        var recollected = replaceMain(after.slots(), 2,
                MossDepositControllerTest.stack("minecraft:melon_seeds", 12, true));
        var first = withSlots(after, recollected);
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcile(first));
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileInventory(null, first));
        assertEquals(pending, store.value.orElseThrow());
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcileInventory(null, first));
        var stamp = first.stamp();
        var second = new MossDepositJournal.Observation(first.context(), new ServerInventorySnapshotStamp(
                stamp.observerEpoch(), stamp.contextGeneration(), stamp.openGeneration() + 1,
                stamp.fullSequence() + 1, stamp.syncId() + 1, stamp.revision()), first.slots());
        assertEquals(SurplusDisposalController.Result.INVENTORY_RECONCILED, controller.reconcileInventory(null, second));
        assertEquals(1, port.throwsSent);
        assertEquals(pending.before(), store.value.orElseThrow().before());
        assertEquals(pending.operationId(), store.value.orElseThrow().operationId());
        assertNotEquals(SurplusDisposalJournal.Stage.CONFIRMED, store.value.orElseThrow().stage());
    }

    private static SurplusDisposalAuthorization localProof() {
        var observed = SurplusDisposalAuthorizationTest.availableStorage();
        var context = new MossDepositJournal.Context(observed.context().worldIdentityHash(), observed.context().dimension(),
                observed.context().planId(), SurplusDisposalAuthorization.SHOP_RECEIPT_ID, 0, 0, 0);
        return SurplusDisposalAuthorization.inPlace(new MossDepositJournal.Observation(context, observed.stamp(), observed.slots()), NOW);
    }

    private static MossDepositJournal.Observation after(SurplusDisposalAuthorization proof) {
        var reference = receipt(proof("minecraft:moss_block"));
        return new MossDepositJournal.Observation(proof.finalInventory().context(), reference.stamp(),
                replaceMain(proof.finalInventory().slots(), 2, empty()));
    }
}
