package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MossClearingEntityPolicyTest {
    private static final MossClearingEntityPolicy.Facts LOOSE_ITEM =
            new MossClearingEntityPolicy.Facts(true, true, false, false, false, false);
    private static final MossClearingEntityPolicy.Facts UNKNOWN =
            new MossClearingEntityPolicy.Facts(false, false, false, false, false, false);

    @Test
    void positivelyIdentifiedLooseItemsAloneDoNotPreventClearing() {
        assertFalse(conflict(List.of()));
        assertFalse(conflict(List.of(LOOSE_ITEM, LOOSE_ITEM, LOOSE_ITEM)));
    }

    @Test
    void mixedPlayersLivingEntitiesVehiclesAndUnknownsRemainConflicts() {
        Map<String, MossClearingEntityPolicy.Facts> blockers = Map.of(
                "player", new MossClearingEntityPolicy.Facts(false, false, true, false, false, false),
                "living entity", new MossClearingEntityPolicy.Facts(false, false, true, false, false, false),
                "vehicle", new MossClearingEntityPolicy.Facts(false, false, true, true, false, false),
                "unknown noncollidable entity", UNKNOWN);
        for (var blocker : blockers.entrySet()) {
            assertTrue(conflict(List.of(LOOSE_ITEM, blocker.getValue(), LOOSE_ITEM)), blocker.getKey());
        }
    }

    @Test
    void aNinthOccupantStillBlocksAfterEightLooseItems() {
        List<MossClearingEntityPolicy.Facts> occupants = new ArrayList<>();
        for (int index = 0; index < 8; index++) { occupants.add(LOOSE_ITEM); }
        occupants.add(UNKNOWN);
        assertTrue(conflict(occupants));
        assertEquals(9, occupants.size());
        assertEquals(UNKNOWN, occupants.getLast());
    }

    @Test
    void itemSubclassesChangedTypesCollidableItemsAndMountsAreNotExempt() {
        for (MossClearingEntityPolicy.Facts variant : List.of(
                new MossClearingEntityPolicy.Facts(false, true, false, false, false, false),
                new MossClearingEntityPolicy.Facts(true, false, false, false, false, false),
                new MossClearingEntityPolicy.Facts(true, true, true, false, false, false),
                new MossClearingEntityPolicy.Facts(true, true, false, true, false, false),
                new MossClearingEntityPolicy.Facts(true, true, false, false, true, false),
                new MossClearingEntityPolicy.Facts(true, true, false, false, false, true))) {
            assertTrue(conflict(List.of(variant)), variant.toString());
        }
    }

    @Test
    void selfOverlapRemainsBlockedWithoutReadingOtherEntities() {
        assertTrue(MossClearingEntityPolicy.hasConflict(true, List.of(LOOSE_ITEM), ignored -> {
            throw new AssertionError("Self overlap must not need another entity read");
        }));
        assertTrue(MossClearingEntityPolicy.hasConflict(true, null, Function.identity()));
    }

    @Test
    void missingAndFailedEntityReadsRemainConflicts() {
        assertTrue(conflict(null));
        assertTrue(conflict(Arrays.asList(LOOSE_ITEM, null)));
        assertTrue(MossClearingEntityPolicy.hasConflict(false, List.of(LOOSE_ITEM), ignored -> null));
        assertTrue(MossClearingEntityPolicy.hasConflict(false, List.of(LOOSE_ITEM), ignored -> {
            throw new IllegalStateException("Entity changed during observation");
        }));
        Iterable<MossClearingEntityPolicy.Facts> unreadable = () -> {
            throw new IllegalStateException("Entity query unavailable");
        };
        assertTrue(MossClearingEntityPolicy.hasConflict(false, unreadable, Function.identity()));
    }

    @Test
    void ignoredItemsDoNotBypassExistingMossReplacementGuards() {
        BlockPosition target = new BlockPosition(0, 0, 0);
        BuildVolume volume = new BuildVolume(0, 0, 0, 15, 3, 15);
        WorkOrder order = new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(
                new OrdinaryPlacement(target, new BlockState("minecraft:dirt", Map.of()), Material.DIRT)));
        boolean entities = conflict(List.of(LOOSE_ITEM));
        assertEquals("", MossClearingPolicy.rejection(volume, order, new MossClearingPolicy.Observation(
                target, "minecraft:moss_block", true, false, false, entities, true)));
        for (MossClearingPolicy.Observation unsafe : List.of(
                new MossClearingPolicy.Observation(target, "minecraft:stone", true, false, false, entities, true),
                new MossClearingPolicy.Observation(target, "minecraft:moss_block", false, false, false, entities, true),
                new MossClearingPolicy.Observation(target, "minecraft:moss_block", true, true, false, entities, true),
                new MossClearingPolicy.Observation(target, "minecraft:moss_block", true, false, true, entities, true),
                new MossClearingPolicy.Observation(target, "minecraft:moss_block", true, false, false, entities, false),
                new MossClearingPolicy.Observation(new BlockPosition(0, 1, 0), "minecraft:moss_block",
                        true, false, false, entities, true))) {
            assertFalse(MossClearingPolicy.rejection(volume, order, unsafe).isEmpty(), unsafe.toString());
        }
    }

    private static boolean conflict(List<MossClearingEntityPolicy.Facts> occupants) {
        return MossClearingEntityPolicy.hasConflict(false, occupants, Function.identity());
    }
}
