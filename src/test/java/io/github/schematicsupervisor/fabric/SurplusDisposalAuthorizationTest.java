package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.SurplusDisposalPolicyTest.*;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurplusDisposalAuthorizationTest {
    @Test void keyArrivalRequiresLaterFullReceiptAndBecomesProtectedInNewIntent() throws Exception {
        var before = availableStorage();
        var original = SurplusDisposalAuthorization.direct(List.of(before.context()), before, NOW);
        var reference = receipt(proof("minecraft:moss_block"));
        var slots = replaceMain(before.slots(), 1, stack("minecraft:tripwire_hook", 31, false));
        var fresh = new MossDepositJournal.Observation(before.context(), reference.stamp(), slots);
        var refreshed = original.refresh(fresh, NOW + 1);
        assertEquals(fresh, refreshed.finalInventory());
        assertEquals(2, SurplusDisposalPolicy.sourceForAuthorization(refreshed, true, NOW + 1).orElseThrow());
        var store = new SurplusDisposalControllerTest.Store();
        var port = new SurplusDisposalControllerTest.Port(store);
        var controller = new SurplusDisposalController(store, port);
        controller.beginAuthorized(true, refreshed, SITE, PLAYER, NOW + 1);
        assertEquals(fresh, store.value.orElseThrow().before());
        var after = replaceMain(slots, 2, empty());
        assertTrue(SurplusDisposalPolicy.receiptProblem(slots, 2, after).isEmpty());
        assertTrue(SurplusDisposalPolicy.receiptProblem(slots, 2,
                replaceMain(after, 1, stack("minecraft:tripwire_hook", 30, false))).isPresent());
        assertThrows(IllegalStateException.class,
                () -> controller.beginAuthorized(true, refreshed, SITE, PLAYER, NOW + 1));
        assertEquals(1, port.throwsSent);
    }

    @Test void refreshIsBoundedAndCannotUsePendingStorageOrOldSessionEvidence() {
        var before = availableStorage();
        var direct = SurplusDisposalAuthorization.direct(List.of(before.context()), before, NOW);
        assertTrue(direct.canRefresh(false, 0));
        assertTrue(direct.canRefresh(false, 1));
        assertFalse(direct.canRefresh(false, 2));
        assertFalse(direct.canRefresh(true, 0));
        assertFalse(SurplusDisposalAuthorization.afterStorage(proof("minecraft:moss_block")).canRefresh(false, 0));
        assertThrows(IllegalArgumentException.class, () -> direct.refresh(before, NOW + 1));
        var wrongSession = new MossDepositJournal.Observation(before.context(), new ServerInventorySnapshotStamp(
                NEXT_EPOCH, 1, 100, 100, 1, 0), before.slots());
        assertThrows(IllegalArgumentException.class, () -> direct.refresh(wrongSession, NOW + 1));
        var reference = receipt(proof("minecraft:moss_block"));
        var protectedOnly = new MossDepositJournal.Observation(before.context(), reference.stamp(),
                replaceMain(before.slots(), 2, stack("minecraft:moss_block", 37, false)));
        assertThrows(IllegalArgumentException.class, () -> direct.refresh(protectedOnly, NOW + 1));
    }

    static MossDepositJournal.Observation availableStorage() {
        var observed = proof("minecraft:moss_block").finalInventory();
        var chest = new ArrayList<>(observed.slots().chest());
        chest.set(0, withStack(chest.getFirst(), empty()));
        return withSlots(observed, new MossDepositFacts.Snapshot(chest, observed.slots().main(),
                observed.slots().cursor(), observed.slots().offhand(), observed.slots().armor()));
    }

    @Test void directModeUsesOneFreshInventoryEvenWhenStorageHasRoom() {
        var observed = availableStorage();
        assertTrue(SurplusStorageExhaustion.capture(List.of(observed.context()), List.of(observed), observed, NOW, NOW).isEmpty());
        var direct = SurplusDisposalAuthorization.direct(List.of(observed.context()), observed, NOW);
        assertEquals(SurplusDisposalAuthorization.Mode.DIRECT, direct.mode());
        assertEquals(2, SurplusDisposalPolicy.sourceForAuthorization(direct, true, NOW).orElseThrow());
        assertTrue(SurplusDisposalPolicy.sourceForAuthorization(direct, false, NOW).isEmpty());
        assertTrue(SurplusDisposalPolicy.sourceForAuthorization(direct, true, NOW - 1).isEmpty());
        assertTrue(SurplusDisposalPolicy.sourceForAuthorization(direct, true,
                NOW + SurplusDisposalPolicy.MAXIMUM_PROOF_AGE_NANOS + 1).isEmpty());
    }

    @Test void directModePrefersLargestApprovedStackAndPreservesToolsKeysAndMaterials() {
        var observed = availableStorage();
        var slots = replaceMain(observed.slots(), 9, stack("minecraft:melon_seeds", 64, true));
        slots = replaceMain(slots, 10, stack("minecraft:moss_block", 64, false));
        var direct = SurplusDisposalAuthorization.direct(List.of(observed.context()), withSlots(observed, slots), NOW);
        assertEquals(9, SurplusDisposalPolicy.sourceForAuthorization(direct, true, NOW).orElseThrow());
        for (String protectedId : List.of("minecraft:diamond_hoe", "minecraft:diamond_axe", "minecraft:tripwire_hook",
                "minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks", "minecraft:wheat_seeds")) {
            var protectedSlots = replaceMain(observed.slots(), 2, stack(protectedId, 1, false));
            assertThrows(IllegalArgumentException.class, () -> SurplusDisposalAuthorization.direct(
                    List.of(observed.context()), withSlots(observed, protectedSlots), NOW));
        }
    }

    @Test void directModeRefusesUnregisteredOrDuplicateContextAndOccupiedCursor() {
        var observed = availableStorage();
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalAuthorization.direct(List.of(), observed, NOW));
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalAuthorization.direct(
                List.of(observed.context(), observed.context()), observed, NOW));
        var occupied = new MossDepositFacts.Snapshot(observed.slots().chest(), observed.slots().main(),
                stack("minecraft:moss_block", 1, true), observed.slots().offhand(), observed.slots().armor());
        assertThrows(IllegalArgumentException.class, () -> SurplusDisposalAuthorization.direct(
                List.of(observed.context()), withSlots(observed, occupied), NOW));
    }

    @Test void directJournalRecordsItsModeWithoutClaimingStorageExhaustion() {
        var observed = availableStorage();
        var direct = SurplusDisposalAuthorization.direct(List.of(observed.context()), observed, NOW);
        var intent = SurplusDisposalJournal.pendingAuthorized(direct, 2, SITE, PLAYER, NOW);
        assertEquals(SurplusDisposalAuthorization.Mode.DIRECT, intent.storage().mode());
        assertEquals(1, intent.storage().chests().size());
        assertEquals(observed, intent.before());
        assertThrows(IllegalArgumentException.class, () -> new SurplusDisposalAuthorization(
                SurplusDisposalAuthorization.Mode.STORAGE_FULL, direct.registered(), direct.chests(), observed, NOW, NOW));
    }
}
