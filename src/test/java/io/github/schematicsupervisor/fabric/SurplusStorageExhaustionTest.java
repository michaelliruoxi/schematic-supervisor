package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurplusStorageExhaustionTest {
    @Test void completeSameSessionZeroRoomPassRetainsExactFinalInventoryAndOldestTime() {
        var first = full("depot-001", 1);
        var last = full("depot-002", 2);
        var contexts = List.of(first.context(), last.context());
        var evidence = SurplusStorageExhaustion.capture(contexts, List.of(first, last), last, 100, 150).orElseThrow();
        assertEquals(last, evidence.finalInventory());
        assertEquals(100, evidence.oldestObservedAtNanos());
        assertEquals(150, evidence.capturedAtNanos());
        assertThrows(UnsupportedOperationException.class, () -> evidence.chests().clear());
    }

    @Test void singleUnitOfCompatibleRoomPreventsExhaustionEvenWhenItCannotHoldWholeStack() {
        var noRoom = full("depot-001", 1);
        var chest = new ArrayList<>(noRoom.slots().chest());
        chest.set(0, MossDepositControllerTest.withStack(chest.get(0),
                MossDepositControllerTest.stack("minecraft:moss_block", 63, true)));
        var room = withSlots(noRoom, new MossDepositFacts.Snapshot(chest, noRoom.slots().main(),
                noRoom.slots().cursor(), noRoom.slots().offhand(), noRoom.slots().armor()));
        assertEquals(1, MossDepositPlanning.plan(room.slots()).orElseThrow().quantity());
        assertTrue(SurplusStorageExhaustion.capture(List.of(room.context()), List.of(room), room, 100, 150).isEmpty());
    }

    @Test void missingFailedDuplicateForeignAndIncompletePermissionObservationsCannotAuthorizeOverflow() {
        var first = full("depot-001", 1);
        var last = full("depot-002", 2);
        var contexts = List.of(first.context(), last.context());
        assertTrue(SurplusStorageExhaustion.capture(contexts, List.of(last), last, 100, 150).isEmpty());
        assertTrue(SurplusStorageExhaustion.capture(contexts, List.of(first, first), first, 100, 150).isEmpty());
        assertTrue(SurplusStorageExhaustion.capture(List.of(), List.of(), last, 100, 150).isEmpty());
        var foreign = new MossDepositJournal.Observation(last.context(),
                new ServerInventorySnapshotStamp(MossDepositControllerTest.NEXT_EPOCH, 1, 2, 2, 1, 0), last.slots());
        assertTrue(SurplusStorageExhaustion.capture(contexts, List.of(first, foreign), foreign, 100, 150).isEmpty());
        var chest = new ArrayList<>(last.slots().chest());
        var slot = chest.getFirst();
        chest.set(0, new MossDepositFacts.SlotFacts(slot.handlerSlot(), slot.inventoryIndex(), slot.stack(), true, true, 64));
        var legacy = withSlots(last, new MossDepositFacts.Snapshot(chest, last.slots().main(), last.slots().cursor(),
                last.slots().offhand(), last.slots().armor()));
        assertTrue(SurplusStorageExhaustion.capture(contexts, List.of(first, legacy), legacy, 100, 150).isEmpty());
        assertTrue(SurplusStorageExhaustion.capture(contexts, List.of(first, last), last, 151, 150).isEmpty());
    }

    @Test void newlyCollectedItemCannotReuseEarlierChestThatHadRoomForThatItem() {
        var first = full("depot-001", 1);
        var last = full("depot-002", 2);
        var chest = new ArrayList<>(first.slots().chest());
        chest.set(0, MossDepositControllerTest.withStack(chest.get(0),
                MossDepositControllerTest.stack("minecraft:melon_seeds", 1, true)));
        first = withSlots(first, new MossDepositFacts.Snapshot(chest, first.slots().main(), first.slots().cursor(),
                first.slots().offhand(), first.slots().armor()));
        last = withSlots(last, MossDepositControllerTest.replaceMain(last.slots(), 3,
                MossDepositControllerTest.stack("minecraft:melon_seeds", 12, true)));
        assertTrue(SurplusStorageExhaustion.capture(List.of(first.context(), last.context()), List.of(first, last),
                last, 100, 150).isEmpty());
    }

    private static MossDepositJournal.Observation full(String depotId, long generation) {
        var original = MossDepositControllerTest.before();
        Map<String, MossDepositFacts.Insertion> insertion = new LinkedHashMap<>();
        for (String id : SurplusPickupPolicy.ITEM_IDS) { insertion.put(id, new MossDepositFacts.Insertion(true, 64)); }
        var chest = new ArrayList<MossDepositFacts.SlotFacts>();
        for (int index = 0; index < 27; index++) {
            chest.add(new MossDepositFacts.SlotFacts(index, index,
                    MossDepositControllerTest.stack("minecraft:dirt", 64, false), true, insertion));
        }
        var slots = new MossDepositFacts.Snapshot(chest, original.slots().main(), original.slots().cursor(),
                original.slots().offhand(), original.slots().armor());
        var c = original.context();
        var context = new MossDepositJournal.Context(c.worldIdentityHash(), c.dimension(), c.planId(), depotId,
                c.depotX() + (int) generation, c.depotY(), c.depotZ());
        return new MossDepositJournal.Observation(context,
                new ServerInventorySnapshotStamp(original.stamp().observerEpoch(), 1, generation, generation, 1, 0), slots);
    }

    private static MossDepositJournal.Observation withSlots(MossDepositJournal.Observation observation,
                                                            MossDepositFacts.Snapshot slots) {
        return new MossDepositJournal.Observation(observation.context(), observation.stamp(), slots);
    }
}
