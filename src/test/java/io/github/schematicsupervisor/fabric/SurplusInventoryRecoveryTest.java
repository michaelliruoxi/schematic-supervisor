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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurplusInventoryRecoveryTest {
    @TempDir Path temporary;
    private final SurplusDisposalController.Port noInput = new SurplusDisposalController.Port() {
        public boolean matchesForDispatch(SurplusDisposalJournal ignored) { fail("Recovery may not dispatch"); return false; }
        public void throwOnce(SurplusDisposalJournal ignored) { fail("An uncertain discard cannot be replayed"); }
    };

    @Test void refilledSourceAndOtherPlainPickupsReconcileWithoutClaimingAConfirmedDiscard() throws Exception {
        var pending = pending(); var store = store(pending);
        var controller = new SurplusDisposalController(store, noInput);
        var slots = replaceMain(pending.before().slots(), 2, stack("minecraft:melon_seeds", 12, true));
        slots = replaceMain(slots, 25, stack("minecraft:pumpkin_seeds", 64, true));
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcile(observed(pending, EPOCH, 2, slots)));
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileInventory(null, observed(pending, EPOCH, 2, slots)));
        assertEquals(pending, store.value.orElseThrow());
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcileInventory(null, observed(pending, EPOCH, 2, slots)));
        assertEquals(SurplusDisposalController.Result.INVENTORY_RECONCILED, controller.reconcileInventory(null, observed(pending, EPOCH, 3, slots)));
        var saved = store.value.orElseThrow();
        assertEquals(pending.before(), saved.before()); assertEquals(pending.operationId(), saved.operationId());
        assertEquals(SurplusDisposalJournal.Stage.INVENTORY_RECONCILED, saved.stage());
        assertNotEquals(SurplusDisposalJournal.Stage.CONFIRMED, saved.stage());
        assertNull(saved.inventoryRecovery().request());
    }

    @Test void restartRequiresTwoFreshReceiptsAndCannotReuseOneReceiptFromBeforeRestart() throws Exception {
        var pending = pending(); var store = store(pending);
        var controller = new SurplusDisposalController(store, noInput);
        var slots = pending.before().slots();
        assertEquals(SurplusDisposalController.Result.WAITING, controller.reconcileInventory(null, pending.before()));
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileInventory(null, observed(pending, NEXT_EPOCH, 1, slots)));
        controller = new SurplusDisposalController(store, noInput);
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileInventory(null, observed(pending, NEXT_EPOCH, 2, slots)));
        assertEquals(SurplusDisposalController.Result.INVENTORY_RECONCILED, controller.reconcileInventory(null, observed(pending, NEXT_EPOCH, 3, slots)));
        assertEquals(observed(pending, NEXT_EPOCH, 2, slots), store.value.orElseThrow().inventoryRecovery().firstReceipt());
    }

    @Test void automaticRecoveryRejectsAnyProtectedDriftAndBothPathsRejectProtectedItemLoss() throws Exception {
        var pending = pending();
        for (var changed : List.of(
                replaceMain(pending.before().slots(), 0, empty()),
                replaceMain(pending.before().slots(), 1, stack("minecraft:tripwire_hook", 33, false)),
                replaceMain(pending.before().slots(), 4, stack("minecraft:dirt", 16, false)),
                replaceMain(pending.before().slots(), 7, tool("f")),
                replaceMain(pending.before().slots(), 2, stack("minecraft:melon_seeds", 12, false)))) {
            var store = store(pending); var controller = new SurplusDisposalController(store, noInput);
            assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileInventory(null, observed(pending, EPOCH, 2, changed)));
            assertEquals(pending, store.value.orElseThrow());
        }
        for (int slot : new int[]{0, 1, 7}) {
            var changed = replaceMain(pending.before().slots(), slot, empty());
            var controller = new SurplusDisposalController(store(pending), noInput);
            assertEquals(SurplusDisposalController.Result.UNCERTAIN,
                    controller.reconcileInventory(request(pending, changed), observed(pending, EPOCH, 2, changed)));
        }
    }

    @Test void scopedResumeAcceptsOnlyReviewedMaterialAndToolChangesWithNoManualClearClaim() throws Exception {
        var pending = pending(); var store = store(pending);
        var changed = replaceMain(pending.before().slots(), 2, empty());
        changed = replaceMain(changed, 1, stack("minecraft:tripwire_hook", 33, false));
        changed = replaceMain(changed, 4, stack("minecraft:dirt", 16, false));
        changed = replaceMain(changed, 7, tool("f"));
        var request = request(pending, changed);
        var controller = new SurplusDisposalController(store, noInput);
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED,
                controller.reconcileInventory(request, observed(pending, NEXT_EPOCH, 1, changed)));
        assertEquals(SurplusDisposalController.Result.INVENTORY_RECONCILED,
                controller.reconcileInventory(request, observed(pending, NEXT_EPOCH, 2, changed)));
        assertEquals(request, store.value.orElseThrow().inventoryRecovery().request());
        assertNull(store.value.orElseThrow().operatorRecovery());
        var disk = new SurplusDisposalFileStore(temporary.resolve("reviewed-resume.json"));
        disk.replace(Optional.empty(), store.value.orElseThrow());
        assertEquals(store.value, disk.load());
    }

    @Test void changedReviewedInventoryOrFingerprintBetweenReceiptsStaysPending() throws Exception {
        var pending = pending(); var slots = pending.before().slots(); var request = request(pending, slots);
        var store = store(pending); var controller = new SurplusDisposalController(store, noInput);
        assertEquals(SurplusDisposalController.Result.REOPEN_REQUIRED, controller.reconcileInventory(request, observed(pending, EPOCH, 2, slots)));
        for (var changed : List.of(replaceMain(slots, 7, tool("f")), replaceMain(slots, 2, empty()))) {
            assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileInventory(request, observed(pending, EPOCH, 3, changed)));
        }
        assertEquals(pending, store.value.orElseThrow());
    }

    @Test void requestIsBoundToOperationPlayerPlanAndExpiry() throws Exception {
        var pending = pending(); var good = request(pending, pending.before().slots());
        for (var request : List.of(
                new SurplusInventoryRecovery.Request(good.requestId(), UUID.randomUUID().toString(), PLAYER, good.planId(), good.requestedAt(), "continue", good.expectedMain()),
                new SurplusInventoryRecovery.Request(good.requestId(), good.operationId(), UUID.randomUUID().toString(), good.planId(), good.requestedAt(), "continue", good.expectedMain()),
                new SurplusInventoryRecovery.Request(good.requestId(), good.operationId(), PLAYER, "sha256:" + "9".repeat(64), good.requestedAt(), "continue", good.expectedMain()),
                new SurplusInventoryRecovery.Request(good.requestId(), good.operationId(), PLAYER, good.planId(), Instant.now().minusSeconds(86401).toString(), "continue", good.expectedMain()))) {
            var controller = new SurplusDisposalController(store(pending), noInput);
            assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileInventory(request, observed(pending, EPOCH, 2, pending.before().slots())));
        }
        assertThrows(IllegalArgumentException.class, () -> new SurplusInventoryRecovery.Request(good.requestId(), good.operationId(), PLAYER,
                good.planId(), good.requestedAt(), "manually cleared", good.expectedMain()));
    }

    @Test void cancellationAndAmbiguousWritesNeverDispatchOrEraseTheIntent() throws Exception {
        var pending = pending();
        for (boolean committed : new boolean[]{false, true}) {
            var store = store(pending); var controller = new SurplusDisposalController(store, noInput);
            var slots = pending.before().slots();
            controller.reconcileInventory(null, observed(pending, EPOCH, 2, slots));
            store.failWrite = true; store.commitBeforeFailure = committed;
            assertThrows(IOException.class, () -> controller.reconcileInventory(null, observed(pending, EPOCH, 3, slots)));
            assertEquals(pending.before(), store.value.orElseThrow().before());
            assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileInventory(null, observed(pending, EPOCH, 4, slots)));
        }
        var controller = new SurplusDisposalController(store(pending), noInput); controller.cancel();
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileInventory(null, observed(pending, EPOCH, 2, pending.before().slots())));
    }

    @Test void foreignContextCursorAndEquipmentChangesRemainBlocked() throws Exception {
        var pending = pending(); var slots = pending.before().slots();
        var controller = new SurplusDisposalController(store(pending), noInput);
        var context = pending.before().context();
        var foreign = new MossDepositJournal.Context("sha256:" + "8".repeat(64), context.dimension(), context.planId(),
                context.depotId(), 0, 0, 0);
        assertEquals(SurplusDisposalController.Result.UNCERTAIN, controller.reconcileInventory(null,
                new MossDepositJournal.Observation(foreign, observed(pending, EPOCH, 2, slots).stamp(), slots)));
        for (var changed : List.of(
                new MossDepositFacts.Snapshot(slots.chest(), slots.main(), stack("minecraft:melon_seeds", 1, true), slots.offhand(), slots.armor()),
                new MossDepositFacts.Snapshot(slots.chest(), slots.main(), slots.cursor(), empty(), slots.armor()),
                new MossDepositFacts.Snapshot(slots.chest(), slots.main(), slots.cursor(), slots.offhand(), List.of(tool("a"), empty(), empty(), empty())))) {
            assertEquals(SurplusDisposalController.Result.UNCERTAIN,
                    controller.reconcileInventory(request(pending, slots), observed(pending, EPOCH, 2, changed)));
        }
    }

    @Test void versionFivePersistsBothReceiptsArchivesOriginalIntentAndRejectsEvidenceTampering() throws Exception {
        var pending = pending(); var path = temporary.resolve("surplus-disposal.json");
        var store = new SurplusDisposalFileStore(path); store.replace(Optional.empty(), pending);
        var first = observed(pending, NEXT_EPOCH, 1, pending.before().slots());
        var resolved = pending.reconcileInventory(null, first, observed(pending, NEXT_EPOCH, 2, first.slots()));
        store.replace(Optional.of(pending), resolved);
        assertEquals(resolved, store.load().orElseThrow()); var encoded = Files.readString(path);
        assertTrue(encoded.contains("\"version\":5"));
        for (String invalid : List.of(encoded.replace("\"version\":5", "\"version\":4"),
                encoded.replace("INVENTORY_RECONCILED", "CONFIRMED"),
                encoded.replace("\"openGeneration\":2", "\"openGeneration\":1"))) {
            Files.writeString(path, invalid); assertThrows(IOException.class, store::load);
        }
        Files.writeString(path, encoded);
        var proof = SurplusDisposalAuthorization.inPlace(observed(pending, NEXT_EPOCH, 3, first.slots()), NOW);
        var next = SurplusDisposalJournal.pendingAuthorized(proof, 2, null, PLAYER, NOW);
        store.replace(Optional.of(resolved), next);
        assertEquals(encoded, Files.readString(temporary.resolve("surplus-disposal-history").resolve(pending.operationId() + ".json")));
        assertEquals(next, store.load().orElseThrow());
    }

    private static SurplusDisposalJournal pending() {
        var template = SurplusDisposalAuthorizationTest.availableStorage();
        var c = template.context();
        var context = new MossDepositJournal.Context(c.worldIdentityHash(), c.dimension(), "sha256:" + "3".repeat(64),
                SurplusDisposalAuthorization.SHOP_RECEIPT_ID, 0, 0, 0);
        var slots = replaceMain(template.slots(), 2, stack("minecraft:melon_seeds", 64, true));
        slots = replaceMain(slots, 0, new MossDepositFacts.StackFacts("a".repeat(64), "minecraft:diamond_hoe", 1, 1, false, false));
        slots = replaceMain(slots, 25, empty());
        slots = replaceMain(slots, 1, stack("minecraft:tripwire_hook", 31, false));
        slots = replaceMain(slots, 4, stack("minecraft:dirt", 64, false));
        slots = replaceMain(slots, 7, tool("e"));
        return SurplusDisposalJournal.pendingAuthorized(SurplusDisposalAuthorization.inPlace(
                new MossDepositJournal.Observation(context, template.stamp(), slots), NOW), 2, null, PLAYER, NOW);
    }
    private static MossDepositFacts.StackFacts tool(String hash) {
        return new MossDepositFacts.StackFacts(hash.repeat(64), "minecraft:diamond_axe", 1, 1, false, false);
    }
    private static MossDepositJournal.Observation observed(SurplusDisposalJournal pending, String epoch, int opening, MossDepositFacts.Snapshot slots) {
        return new MossDepositJournal.Observation(pending.before().context(), new ServerInventorySnapshotStamp(epoch, 1, opening, opening, opening, 0), slots);
    }
    private static SurplusInventoryRecovery.Request request(SurplusDisposalJournal pending, MossDepositFacts.Snapshot slots) {
        return new SurplusInventoryRecovery.Request(UUID.randomUUID().toString(), pending.operationId(), PLAYER, pending.before().context().planId(),
                Instant.now().toString(), "continue", slots.main().stream().map(slot -> new SurplusInventoryRecovery.ExpectedStack(
                    slot.stack().itemId(), slot.stack().count(), slot.stack().maxCount(), slot.stack().plainPickup())).toList());
    }
    private static SurplusDisposalControllerTest.Store store(SurplusDisposalJournal pending) {
        var store = new SurplusDisposalControllerTest.Store(); store.value = Optional.of(pending); return store;
    }
}
