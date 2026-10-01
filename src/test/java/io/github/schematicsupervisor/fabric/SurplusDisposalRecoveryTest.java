package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.*;
import static io.github.schematicsupervisor.fabric.SurplusDisposalPolicyTest.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurplusDisposalRecoveryTest {
    @TempDir Path temporary;
    static final Instant ACKNOWLEDGED = Instant.parse("2026-09-18T18:45:00Z");

    @Test void manualCleanupAndRepairNeedTwoReceiptsAndNeverSendAnotherThrow() throws Exception {
        var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port); var request = request(pending);
        var first = receipt(pending, EPOCH, 2, cleaned(pending));
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcile(first));
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileOperator(request, first));
        assertEquals(pending, store.value.orElseThrow());
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcileOperator(request, first));
        var second = receipt(pending, EPOCH, 3, first.slots());
        assertEquals(SurplusDisposalController.Result.OPERATOR_RECONCILED, controller.reconcileOperator(request, second));
        var settled = store.value.orElseThrow();
        assertEquals(SurplusDisposalJournal.Stage.OPERATOR_RECONCILED, settled.stage());
        assertEquals(pending.before(), settled.before()); assertEquals(pending.operationId(), settled.operationId());
        assertEquals(first, settled.operatorRecovery().firstReceipt()); assertEquals(second, settled.receipt());
        assertEquals(0, port.throwsSent); assertEquals(1, store.saves);
        var restarted = new SurplusDisposalController(store, port);
        assertEquals(SurplusDisposalController.Result.OPERATOR_RECONCILED, restarted.reconcile(second));
        assertEquals(0, port.throwsSent);
    }

    @Test void reconnectUsesTwoNewOpeningsAndRestartForgetsAnIncompletePair() throws Exception {
        var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
        var request = request(pending); var first = receipt(pending, NEXT_EPOCH, 1, cleaned(pending));
        var controller = new SurplusDisposalController(store, port);
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileOperator(request, first));
        controller = new SurplusDisposalController(store, port);
        var second = receipt(pending, NEXT_EPOCH, 2, first.slots());
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileOperator(request, second));
        assertEquals(SurplusDisposalController.Result.OPERATOR_RECONCILED,
                controller.reconcileOperator(request, receipt(pending, NEXT_EPOCH, 3, first.slots())));
        assertEquals(second.stamp(), store.value.orElseThrow().reconciliationBarrier());
        assertEquals(0, port.throwsSent);
    }

    @Test void wrongAcknowledgementScopeAndStaleOpeningsCannotRetireIntent() throws Exception {
        var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port); var good = request(pending);
        var after = receipt(pending, EPOCH, 2, cleaned(pending));
        for (var request : List.of(
                new SurplusDisposalRecovery.Request(UUID.randomUUID().toString(), PLAYER, good.planId(), good.acknowledgedAt(), good.acknowledgement(), List.of(0)),
                new SurplusDisposalRecovery.Request(good.operationId(), UUID.randomUUID().toString(), good.planId(), good.acknowledgedAt(), good.acknowledgement(), List.of(0)),
                new SurplusDisposalRecovery.Request(good.operationId(), PLAYER, "sha256:" + "9".repeat(64), good.acknowledgedAt(), good.acknowledgement(), List.of(0)))) {
            assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileOperator(request, after));
        }
        assertEquals(SurplusDisposalController.Result.WAITING,
                controller.reconcileOperator(good, receipt(pending, EPOCH, 1, after.slots())));
        assertEquals(pending, store.value.orElseThrow()); assertEquals(0, port.throwsSent);
        assertTrue(good.fresh(ACKNOWLEDGED)); assertFalse(good.fresh(ACKNOWLEDGED.minusSeconds(1)));
        assertFalse(good.fresh(ACKNOWLEDGED.plusSeconds(86401)));
        assertThrows(IllegalArgumentException.class, () -> new SurplusDisposalRecovery.Request(good.operationId(), PLAYER,
                good.planId(), good.acknowledgedAt(), "assumed", List.of(0)));
    }

    @Test void sourceMustBeEmptyAndAllOtherToolsKeysBuildMaterialsAndEquipmentStayProtected() {
        var pending = pending(); var request = request(pending); var cleaned = cleaned(pending);
        for (int slot : new int[]{0, 1, 3, 4, 7}) {
            assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request,
                    receipt(pending, EPOCH, 2, replaceMain(cleaned, slot, empty()))).isPresent());
        }
        assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request,
                receipt(pending, EPOCH, 2, replaceMain(cleaned, 2, stack("minecraft:moss_block", 1, true)))).isPresent());
        assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request,
                receipt(pending, EPOCH, 2, replaceMain(cleaned, 8, stack("minecraft:moss_block", 1, true)))).isPresent());
        var renamedKey = replaceMain(cleaned, 1, stack("minecraft:tripwire_hook", 22, false));
        assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request, receipt(pending, EPOCH, 2, renamedKey)).isPresent());
        var wrongHoe = replaceMain(cleaned, 0, hoe("minecraft:iron_hoe", "c"));
        assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request, receipt(pending, EPOCH, 2, wrongHoe)).isPresent());
        var occupiedCursor = new MossDepositFacts.Snapshot(cleaned.chest(), cleaned.main(), stack("minecraft:melon_seeds", 1, true), cleaned.offhand(), cleaned.armor());
        assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request, receipt(pending, EPOCH, 2, occupiedCursor)).isPresent());
        var changedEquipment = new MossDepositFacts.Snapshot(cleaned.chest(), cleaned.main(), cleaned.cursor(), empty(), cleaned.armor());
        assertTrue(SurplusDisposalRecovery.receiptProblem(pending.before(), 2, request, receipt(pending, EPOCH, 2, changedEquipment)).isPresent());
    }

    @Test void unacknowledgedRepairAndInventoryChangesBetweenReceiptsStayPending() throws Exception {
        var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port); var request = request(pending);
        var first = receipt(pending, EPOCH, 2, cleaned(pending));
        var noRepair = new SurplusDisposalRecovery.Request(request.operationId(), PLAYER, request.planId(),
                request.acknowledgedAt(), request.acknowledgement(), List.of());
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileOperator(noRepair, first));
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileOperator(request, first));
        var changed = replaceMain(first.slots(), 0, hoe("minecraft:diamond_hoe", "d"));
        assertEquals(SurplusDisposalController.Result.UNCERTAIN,
                controller.reconcileOperator(request, receipt(pending, EPOCH, 3, changed)));
        assertEquals(pending, store.value.orElseThrow()); assertEquals(0, port.throwsSent);
    }

    @Test void cancellationAndAmbiguousPersistenceNeverReplayOrGrantBuildCredit() throws Exception {
        for (boolean committed : new boolean[]{false, true}) {
            var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
            var controller = new SurplusDisposalController(store, port); var request = request(pending);
            var first = receipt(pending, EPOCH, 2, cleaned(pending));
            assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileOperator(request, first));
            store.failWrite = true; store.commitBeforeFailure = committed;
            assertThrows(IOException.class, () -> controller.reconcileOperator(request, receipt(pending, EPOCH, 3, first.slots())));
            assertEquals(0, port.throwsSent);
            assertEquals(committed ? SurplusDisposalJournal.Stage.OPERATOR_RECONCILED : SurplusDisposalJournal.Stage.PENDING,
                    store.value.orElseThrow().stage());
        }
        var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port); controller.cancel();
        assertEquals(SurplusDisposalController.Result.UNCERTAIN,
                controller.reconcileOperator(request(pending), receipt(pending, EPOCH, 2, cleaned(pending))));
        assertEquals(pending, store.value.orElseThrow());
    }

    @Test void explicitRecoveryRoundTripsWithoutChangingOriginalIntentAndRejectsTampering() throws Exception {
        var pending = pending(); var file = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(file); store.replace(Optional.empty(), pending);
        var first = receipt(pending, NEXT_EPOCH, 1, cleaned(pending));
        var resolved = pending.reconcileOperator(request(pending), first, receipt(pending, NEXT_EPOCH, 2, first.slots()));
        store.replace(Optional.of(pending), resolved); assertEquals(resolved, store.load().orElseThrow());
        var valid = Files.readString(file); assertTrue(valid.contains("\"version\":3"));
        assertThrows(IOException.class, () -> store.replace(Optional.of(resolved), pending));
        for (String invalid : List.of(valid.replace("\"version\":3", "\"version\":2"),
                valid.replace("OPERATOR_RECONCILED", "CONFIRMED"),
                valid.replace("\"repairedMainSlots\":[0]", "\"repairedMainSlots\":[]"),
                valid.replace(SurplusDisposalRecovery.ACKNOWLEDGEMENT, "unacknowledged"))) {
            Files.writeString(file, invalid); assertThrows(IOException.class, store::load);
        }
    }

    @Test void passiveInventoryAndSettingsCanLeaveForReceiptOnlyRecoveryWithoutAnyThrow() throws Exception {
        for (var screen : List.of(BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS)) {
            var pending = pending(); var store = store(pending); var port = new SurplusDisposalControllerTest.Port(store);
            var controller = new SurplusDisposalController(store, port);
            var view = new AtomicReference<>(new BackgroundBuildPolicy.RestockScreen(screen, new Object(), true, true, true, true, true));
            assertEquals(BackgroundBuildPolicy.RestockTransition.CLEARED,
                    BackgroundBuildPolicy.leavePassiveScreenForRestock(view::get, () -> true,
                            () -> view.set(new BackgroundBuildPolicy.RestockScreen(BackgroundBuildPolicy.Screen.GAMEPLAY,
                                    null, true, true, true, true, true))));
            var first = receipt(pending, EPOCH, 2, cleaned(pending));
            assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileOperator(request(pending), first));
            assertEquals(SurplusDisposalController.Result.OPERATOR_RECONCILED,
                    controller.reconcileOperator(request(pending), receipt(pending, EPOCH, 3, first.slots())));
            assertEquals(0, port.throwsSent);
        }
    }

    private static SurplusDisposalJournal pending() {
        var template = proof("minecraft:jack_o_lantern").finalInventory();
        var original = template.context();
        var context = new MossDepositJournal.Context(original.worldIdentityHash(), original.dimension(), "sha256:" + "3".repeat(64),
                original.depotId(), original.depotX(), original.depotY(), original.depotZ());
        var slots = replaceMain(template.slots(), 0, hoe("minecraft:diamond_hoe", "a"));
        slots = replaceMain(slots, 1, stack("minecraft:tripwire_hook", 23, false));
        slots = replaceMain(slots, 3, hoe("minecraft:diamond_hoe", "b"));
        slots = replaceMain(slots, 7, hoe("minecraft:diamond_axe", "c"));
        slots = replaceMain(slots, 8, stack("minecraft:pumpkin_seeds", 64, true));
        var before = new MossDepositJournal.Observation(context, template.stamp(), slots);
        var authorization = SurplusDisposalAuthorization.direct(List.of(context), before, NOW);
        return SurplusDisposalJournal.pendingAuthorized(authorization, 2, SITE, PLAYER, NOW);
    }
    private static MossDepositFacts.StackFacts hoe(String item, String fingerprint) {
        return new MossDepositFacts.StackFacts(fingerprint.repeat(64), item, 1, 1, false, false);
    }
    private static MossDepositFacts.Snapshot cleaned(SurplusDisposalJournal pending) {
        var slots = replaceMain(pending.before().slots(), 2, empty());
        slots = replaceMain(slots, 8, empty());
        return replaceMain(slots, 0, hoe("minecraft:diamond_hoe", "e"));
    }
    private static SurplusDisposalRecovery.Request request(SurplusDisposalJournal pending) {
        return new SurplusDisposalRecovery.Request(pending.operationId(), PLAYER, pending.before().context().planId(),
                ACKNOWLEDGED.toString(), SurplusDisposalRecovery.ACKNOWLEDGEMENT, List.of(0));
    }
    private static MossDepositJournal.Observation receipt(SurplusDisposalJournal pending, String epoch, int opening,
                                                         MossDepositFacts.Snapshot slots) {
        return new MossDepositJournal.Observation(pending.before().context(),
                new ServerInventorySnapshotStamp(epoch, 1, opening, opening, opening, 0), slots);
    }
    private static SurplusDisposalControllerTest.Store store(SurplusDisposalJournal pending) {
        var store = new SurplusDisposalControllerTest.Store(); store.value = Optional.of(pending); return store;
    }
}
