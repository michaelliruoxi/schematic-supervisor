package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.SurplusDisposalPolicyTest.*;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SurplusDisposalControllerTest {
    @Test void durableIntentPrecedesSingleThrowAndOnlyLaterExactFullReceiptConfirms() throws Exception {
        var store = new Store(); var port = new Port(store); var controller = new SurplusDisposalController(store, port);
        var proof = proof("minecraft:pumpkin_seeds");
        controller.begin(true, proof, SITE, PLAYER, NOW);
        assertEquals(1, port.throwsSent); assertEquals(SurplusDisposalJournal.Stage.PENDING, store.value.orElseThrow().stage());
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcile(proof.finalInventory()));
        assertEquals(SurplusDisposalController.Result.CONFIRMED, controller.reconcile(receipt(proof)));
        assertEquals(SurplusDisposalJournal.Stage.CONFIRMED, store.value.orElseThrow().stage());
        assertEquals(SurplusDisposalController.Result.CONFIRMED, controller.reconcile(receipt(proof)));
        assertEquals(2, store.saves); assertEquals(1, port.throwsSent);
        assertThrows(IllegalStateException.class, () -> controller.begin(true, proof, SITE, PLAYER, NOW));
        var restarted = new SurplusDisposalController(store, port);
        assertThrows(IllegalStateException.class, () -> restarted.begin(true, proof, SITE, PLAYER, NOW));
    }

    @Test void uncertainThrowCancellationAndRestartCannotDispatchAgain() throws Exception {
        var store = new Store(); var port = new Port(store); port.failAfterThrow = true;
        var controller = new SurplusDisposalController(store, port); var proof = proof("minecraft:moss_block");
        assertThrows(IOException.class, () -> controller.begin(true, proof, SITE, PLAYER, NOW));
        controller.cancel();
        assertThrows(IllegalStateException.class, () -> controller.begin(true, proof, SITE, PLAYER, NOW));
        var restarted = new SurplusDisposalController(store, port);
        assertThrows(IllegalStateException.class, () -> restarted.begin(true, proof, SITE, PLAYER, NOW));
        assertEquals(1, port.throwsSent);
        assertEquals(SurplusDisposalController.Result.CONFIRMED, restarted.reconcile(receipt(proof)));
        assertEquals(1, port.throwsSent);
    }

    @Test void guardLossOrCancellationDuringPersistenceLeavesPendingWithoutThrow() throws Exception {
        for (boolean cancel : new boolean[] { false, true }) {
            var store = new Store(); var port = new Port(store); var controller = new SurplusDisposalController(store, port);
            store.afterSave = cancel ? controller::cancel : () -> port.eligible = false;
            assertThrows(IllegalStateException.class, () -> controller.begin(true, proof("minecraft:melon_seeds"), SITE, PLAYER, NOW));
            assertEquals(0, port.throwsSent); assertEquals(SurplusDisposalJournal.Stage.PENDING, store.value.orElseThrow().stage());
        }
    }

    @Test void failedOrAmbiguousPersistenceCannotIssueInputOrRetryFromSameController() throws Exception {
        for (boolean commitFirst : new boolean[] { false, true }) {
            var store = new Store(); var port = new Port(store);
            store.failWrite = true; store.commitBeforeFailure = commitFirst;
            var controller = new SurplusDisposalController(store, port);
            assertThrows(IOException.class, () -> controller.begin(true, proof("minecraft:melon_seeds"), SITE, PLAYER, NOW));
            assertEquals(0, port.throwsSent);
            store.failWrite = false;
            assertThrows(IllegalStateException.class, () -> controller.begin(true, proof("minecraft:melon_seeds"), SITE, PLAYER, NOW));
            assertEquals(0, port.throwsSent);
        }
    }

    @Test void absentSettingExpiredProofOrPreflightMismatchCreatesNoIntent() throws Exception {
        var store = new Store(); var port = new Port(store); var controller = new SurplusDisposalController(store, port);
        var proof = proof("minecraft:moss_block");
        assertThrows(IllegalStateException.class, () -> controller.begin(false, proof, SITE, PLAYER, NOW));
        assertThrows(IllegalStateException.class, () -> controller.begin(true, proof, SITE, PLAYER,
                SurplusDisposalPolicy.MAXIMUM_PROOF_AGE_NANOS + NOW));
        port.eligible = false;
        assertThrows(IllegalStateException.class, () -> controller.begin(true, proof, SITE, PLAYER, NOW));
        assertTrue(store.value.isEmpty()); assertEquals(0, port.throwsSent);
    }

    @Test void reconnectNeedsDurableNewSessionBarrierThenAnotherFullReopen() throws Exception {
        var store = new Store(); var port = new Port(store); var controller = new SurplusDisposalController(store, port);
        var proof = proof("minecraft:moss_block"); controller.begin(true, proof, SITE, PLAYER, NOW);
        var after = receipt(proof);
        var barrier = new MossDepositJournal.Observation(after.context(), new ServerInventorySnapshotStamp(
                MossDepositControllerTest.NEXT_EPOCH, 1, 1, 1, 1, 0), after.slots());
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcile(barrier));
        assertEquals(barrier.stamp(), store.value.orElseThrow().reconciliationBarrier());
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcile(after));
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcile(barrier));
        var fresh = new MossDepositJournal.Observation(after.context(), new ServerInventorySnapshotStamp(
                MossDepositControllerTest.NEXT_EPOCH, 1, 2, 2, 2, 0), after.slots());
        assertEquals(SurplusDisposalController.Result.CONFIRMED, controller.reconcile(fresh));
        assertEquals(1, port.throwsSent);
    }

    @Test void missingDecreaseOrProtectedMutationNeverConfirmsOrRetries() throws Exception {
        var store = new Store(); var port = new Port(store); var controller = new SurplusDisposalController(store, port);
        var proof = proof("minecraft:moss_block"); controller.begin(true, proof, SITE, PLAYER, NOW);
        var after = receipt(proof);
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcile(withSlots(after, proof.finalInventory().slots())));
        var changed = MossDepositControllerTest.replaceMain(after.slots(), 0, MossDepositControllerTest.empty());
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcile(withSlots(after, changed)));
        assertEquals(SurplusDisposalJournal.Stage.PENDING, store.value.orElseThrow().stage()); assertEquals(1, port.throwsSent);
    }

    static final class Store implements SurplusDisposalController.Store {
        // The same durable boundary is exercised by both storage-full and direct admission tests.
        Optional<SurplusDisposalJournal> value = Optional.empty(); int saves;
        boolean failWrite; boolean commitBeforeFailure; Runnable afterSave = () -> { };
        public Optional<SurplusDisposalJournal> load() { return value; }
        public void replace(Optional<SurplusDisposalJournal> expected, SurplusDisposalJournal next) throws IOException {
            assertEquals(expected, value);
            if (failWrite && !commitBeforeFailure) { throw new IOException("not written"); }
            value = Optional.of(next); saves++; afterSave.run();
            if (failWrite) { throw new IOException("uncertain write"); }
        }
    }
    @Test void directThrowIsDurableAndItsRestartOnlyConfirmsTheOriginalInput() throws Exception {
        var observed = SurplusDisposalAuthorizationTest.availableStorage();
        var direct = SurplusDisposalAuthorization.direct(java.util.List.of(observed.context()), observed, NOW);
        var store = new Store(); var port = new Port(store); port.failAfterThrow = true;
        var controller = new SurplusDisposalController(store, port);
        assertThrows(IOException.class, () -> controller.beginAuthorized(true, direct, SITE, PLAYER, NOW));
        assertEquals(1, port.throwsSent);
        var restarted = new SurplusDisposalController(store, port);
        assertThrows(IllegalStateException.class, () -> restarted.beginAuthorized(true, direct, SITE, PLAYER, NOW));
        var reference = receipt(proof("minecraft:moss_block"));
        var after = new MossDepositJournal.Observation(observed.context(), reference.stamp(),
                MossDepositControllerTest.replaceMain(observed.slots(), 2, MossDepositControllerTest.empty()));
        assertEquals(SurplusDisposalController.Result.CONFIRMED, restarted.reconcile(after));
        assertEquals(1, port.throwsSent);
        assertEquals(SurplusDisposalAuthorization.Mode.DIRECT, store.value.orElseThrow().storage().mode());
    }

    @Test void directModeCannotReuseAConfirmedInventoryOrIgnoreProtectedChanges() throws Exception {
        var observed = SurplusDisposalAuthorizationTest.availableStorage();
        var direct = SurplusDisposalAuthorization.direct(java.util.List.of(observed.context()), observed, NOW);
        var store = new Store(); var port = new Port(store); var controller = new SurplusDisposalController(store, port);
        controller.beginAuthorized(true, direct, SITE, PLAYER, NOW);
        var reference = receipt(proof("minecraft:moss_block"));
        var slots = MossDepositControllerTest.replaceMain(observed.slots(), 2, MossDepositControllerTest.empty());
        var after = new MossDepositJournal.Observation(observed.context(), reference.stamp(), slots);
        var changed = withSlots(after, MossDepositControllerTest.replaceMain(slots, 0, MossDepositControllerTest.empty()));
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcile(changed));
        assertEquals(SurplusDisposalController.Result.CONFIRMED, controller.reconcile(after));
        assertThrows(IllegalStateException.class, () -> controller.beginAuthorized(true, direct, SITE, PLAYER, NOW));
        assertEquals(1, port.throwsSent);
    }
    static final class Port implements SurplusDisposalController.Port {
        final Store store; boolean eligible = true; boolean failAfterThrow; int throwsSent;
        Port(Store store) { this.store = store; }
        public boolean matchesForDispatch(SurplusDisposalJournal intent) { return eligible; }
        public void throwOnce(SurplusDisposalJournal intent) throws IOException {
            assertEquals(intent, store.value.orElseThrow()); assertEquals(SurplusDisposalJournal.Stage.PENDING, intent.stage());
            throwsSent++; if (failAfterThrow) { throw new IOException("unknown input result"); }
        }
    }
}
