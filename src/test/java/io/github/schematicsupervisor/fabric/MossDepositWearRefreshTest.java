package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MossDepositWearRefreshTest {
    private static final String HOE = "matching-diamond-hoes";

    @Test void confirmedStorageRestoresOnlyTheCoveredCohortMinimumWithoutRepairOrToolInput() throws Exception {
        var guard = exhausted(false);
        var tool = guard.track(HOE, wear(1070)).orElseThrow();
        var refresh = new MossDepositWearRefresh();
        refresh.arm(false);
        assertTrue(refresh.captureBeforeOpen());
        var ticket = guard.beforeOwnedOpen(cohort()).orElseThrow();
        assertEquals(64, tool.allowance(), "Opening the chest alone grants no wear");
        var store = new FakeStore();
        var port = new FakePort(store);
        var deposit = new MossDepositController(store, port);
        var intent = deposit.begin(withTools(before()), 2);
        refresh.submitted(intent);
        assertEquals(64, tool.allowance(), "A pending transfer grants no wear");
        var receipt = withTools(after());
        assertEquals(MossDepositController.Status.CONFIRMED, deposit.reconcile(receipt).status());
        assertTrue(refresh.takeConfirmed(store.value.orElseThrow(), receipt, true));
        assertTrue(guard.refreshFromReceipt(ticket, cohort()));
        assertEquals(491, tool.allowance());
        assertEquals(1, port.clicks, "Only the original pickup QUICK_MOVE was dispatched");
        assertFalse(refresh.takeConfirmed(store.value.orElseThrow(), receipt, true));
        assertFalse(guard.refreshFromReceipt(ticket, cohort()));
    }

    @Test void restoredPendingDepositCannotBorrowAnOpeningOrLaterConfirmedReceipt() {
        var intent = intent();
        var refresh = new MossDepositWearRefresh();
        refresh.arm(true);
        assertFalse(refresh.captureBeforeOpen());
        refresh.submitted(intent);
        assertFalse(refresh.takeConfirmed(intent.confirm(withTools(after())), withTools(after()), true));
    }

    @Test void initialOpenUnsubmittedTransferAndPendingJournalNeverAuthorizeRefresh() {
        var intent = intent();
        var refresh = new MossDepositWearRefresh();
        refresh.arm(false);
        assertFalse(refresh.takeConfirmed(intent.confirm(withTools(after())), withTools(after()), true));
        refresh.arm(false);
        assertTrue(refresh.captureBeforeOpen());
        assertFalse(refresh.captureBeforeOpen(), "The same opening cannot arm twice");
        assertFalse(refresh.takeConfirmed(intent.confirm(withTools(after())), withTools(after()), true));
        refresh.arm(false);
        assertTrue(refresh.captureBeforeOpen());
        refresh.submitted(intent);
        assertFalse(refresh.takeConfirmed(intent, withTools(after()), true));
    }

    @Test void exactOperationReceiptAndCurrentInventoryMustAllMatch() {
        var original = intent();
        var different = intent();
        var confirmed = original.confirm(withTools(after()));
        assertFalse(bound(original).takeConfirmed(different.confirm(withTools(after())), withTools(after()), true));
        assertFalse(bound(original).takeConfirmed(confirmed, withTools(before()), true));
        var refresh = bound(original);
        assertFalse(refresh.takeConfirmed(confirmed, withTools(after()), false));
        assertFalse(refresh.takeConfirmed(confirmed, withTools(after()), true), "A failed baseline does not leave reusable permission");
    }

    @Test void connectionReconciliationBarrierNeverRestoresOptionalWear() {
        var original = intent();
        var barrier = observed(NEXT_EPOCH, 2, 1, 1, withTools(after()).slots());
        var receipt = observed(NEXT_EPOCH, 2, 2, 2, withTools(after()).slots());
        var confirmed = original.withBarrier(barrier).confirm(receipt);
        assertFalse(bound(original).takeConfirmed(confirmed, receipt, true));
        var restarted = new MossDepositWearRefresh();
        restarted.arm(false);
        assertTrue(restarted.captureBeforeOpen());
        restarted.submitted(original.withBarrier(barrier));
        assertFalse(restarted.takeConfirmed(confirmed, receipt, true));
    }

    @Test void cancellationAndSupersedingChestCannotReuseAnOlderIntent() {
        var original = intent();
        var refresh = bound(original);
        refresh.cancel();
        assertFalse(refresh.takeConfirmed(original.confirm(withTools(after())), withTools(after()), true));
        refresh = bound(original);
        refresh.arm(false);
        assertTrue(refresh.captureBeforeOpen());
        assertFalse(refresh.takeConfirmed(original.confirm(withTools(after())), withTools(after()), true));
    }

    @Test void unknownWearDebtAndInvalidCustodyStillRejectAConfirmedStorageBoundary() {
        for (boolean unknown : List.of(false, true)) {
            var guard = exhausted(unknown);
            if (!unknown) { guard.invalidateCustody(); }
            var tool = guard.track(HOE, wear(1070)).orElseThrow();
            var original = intent();
            var refresh = bound(original);
            assertTrue(refresh.takeConfirmed(original.confirm(withTools(after())), withTools(after()), true));
            assertTrue(guard.beforeOwnedOpen(cohort()).isEmpty());
            assertFalse(guard.refreshFromReceipt(null, cohort()));
            assertEquals(64, tool.allowance());
        }
    }

    @Test void pendingWearChargeRepairAndInterveningStartCannotUseDepositAsFreshCredit() {
        var guard = new MossMiningToolGuard<String>(String::equals);
        var tool = guard.track(HOE, wear(1070)).orElseThrow();
        var ticket = guard.beforeOwnedOpen(cohort()).orElseThrow();
        var charge = guard.begin(0, tool, HOE, wear(1070)).orElseThrow().charge();
        assertTrue(guard.beforeOwnedOpen(cohort()).isEmpty());
        assertFalse(guard.refreshFromReceipt(ticket, cohort()));
        guard.settleCharge(charge, MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        assertFalse(guard.refreshFromReceipt(ticket, cohort()));
        tool.awaitRepair("original-pending-repair");
        assertTrue(guard.beforeOwnedOpen(cohort()).isEmpty());
        assertEquals(490, tool.allowance());
    }

    @Test void missingIdenticalSpareCannotTurnConfirmedPickupStorageIntoPartialToolProof() {
        var guard = exhausted(false);
        var tool = guard.track(HOE, wear(1070)).orElseThrow();
        var ticket = guard.beforeOwnedOpen(cohort()).orElseThrow();
        assertFalse(guard.refreshFromReceipt(ticket, List.of(cohort().getFirst())));
        assertEquals(64, tool.allowance());
    }

    @Test void uncertainConfirmationWriteCannotGrantWearEvenIfTheFileContainsConfirmedReceipt() throws Exception {
        var refresh = new MossDepositWearRefresh();
        refresh.arm(false);
        assertTrue(refresh.captureBeforeOpen());
        var store = new FakeStore();
        var deposit = new MossDepositController(store, new FakePort(store));
        refresh.submitted(deposit.begin(withTools(before()), 2));
        store.failure = Failure.AFTER;
        assertThrows(java.io.IOException.class, () -> deposit.reconcile(withTools(after())));
        assertEquals(MossDepositJournal.Stage.CONFIRMED, store.value.orElseThrow().stage());
        // The adapter's catch path discards the optional opening before reloading the durable journal.
        refresh.cancel();
        assertFalse(refresh.takeConfirmed(store.value.orElseThrow(), withTools(after()), true));
    }

    private static MossDepositWearRefresh bound(MossDepositJournal intent) {
        var refresh = new MossDepositWearRefresh();
        refresh.arm(false);
        assertTrue(refresh.captureBeforeOpen());
        refresh.submitted(intent);
        return refresh;
    }

    private static MossDepositJournal intent() {
        var before = withTools(before());
        return MossDepositJournal.pending(before, MossDepositPlanning.plan(before.slots(), 2).orElseThrow());
    }

    private static MossDepositJournal.Observation withTools(MossDepositJournal.Observation original) {
        var slots = replaceMain(original.slots(), 0,
                new MossDepositFacts.StackFacts("a".repeat(64), "minecraft:diamond_hoe", 1, 1, false, false));
        slots = replaceMain(slots, 3,
                new MossDepositFacts.StackFacts("b".repeat(64), "minecraft:diamond_hoe", 1, 1, false, false));
        return new MossDepositJournal.Observation(original.context(), original.stamp(), slots);
    }

    private static MossMiningToolGuard<String> exhausted(boolean unknownFirstCharge) {
        var guard = new MossMiningToolGuard<String>(String::equals);
        var tool = guard.track(HOE, wear(900)).orElseThrow();
        boolean first = true;
        while (tool.allowance() > 64) {
            var charge = guard.begin(0, tool, HOE, wear(900)).orElseThrow().charge();
            guard.settleCharge(charge, first && unknownFirstCharge
                    ? MossMiningToolGuard.ChargeOutcome.UNKNOWN : MossMiningToolGuard.ChargeOutcome.CONFIRMED);
            first = false;
        }
        return guard;
    }

    private static List<MossMiningToolGuard.CohortMember<String>> cohort() {
        return List.of(new MossMiningToolGuard.CohortMember<>(0, HOE, wear(1070)),
                new MossMiningToolGuard.CohortMember<>(3, HOE, wear(157)));
    }

    private static MossMiningToolGuard.Durability wear(int damage) {
        return new MossMiningToolGuard.Durability(damage, 1561, false);
    }
}
