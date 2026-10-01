package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PlainInteractionItemsTest {
    @Test void stemMiningAllowsOnlyEmptyOrTheSevenExactPlainItemIds() {
        for (String id : List.of("minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks",
                "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:moss_block",
                "minecraft:jack_o_lantern")) {
            assertTrue(PlainInteractionItems.safeStemHand(id, false, true));
            assertFalse(PlainInteractionItems.safeStemHand(id, false, false),
                    "Custom components or metadata must reject even an allowed item type");
        }
        assertTrue(PlainInteractionItems.safeStemHand(null, true, false));
        assertFalse(PlainInteractionItems.safeStemHand(null, false, true));
    }

    @Test void stemMiningCannotSelectToolsKeysOrOtherPlainItems() {
        for (String id : List.of("minecraft:diamond_hoe", "minecraft:diamond_axe", "minecraft:diamond_shovel",
                "minecraft:tripwire_hook", "minecraft:trial_key", "minecraft:wheat_seeds",
                "minecraft:pumpkin", "minecraft:melon", "minecraft:stone", "server:crate_key")) {
            assertFalse(PlainInteractionItems.safeStemHand(id, false, true));
        }
    }

    @Test void fullHotbarOfToolsAndCollectedItemsUsesTheCurrentSafeCollectedStack() {
        List<String> hotbar = List.of("minecraft:diamond_hoe", "minecraft:diamond_hoe", "server:crate_key",
                "minecraft:moss_block", "minecraft:pumpkin_seeds", "minecraft:jack_o_lantern",
                "minecraft:diamond_axe", "minecraft:melon_seeds", "minecraft:diamond_hoe");
        assertEquals(7, PlainInteractionItems.chooseStemSlot(9, 7,
                slot -> PlainInteractionItems.safeStemHand(hotbar.get(slot), false, true)).orElseThrow());
        assertEquals(3, PlainInteractionItems.chooseStemSlot(9, 0,
                slot -> PlainInteractionItems.safeStemHand(hotbar.get(slot), false, true)).orElseThrow());
    }

    @Test void stemSelectionSkipsCustomSelectedSeedsAndUsesTheFirstPlainAlternative() {
        assertEquals(5, PlainInteractionItems.chooseStemSlot(9, 4, slot -> switch (slot) {
            case 4 -> PlainInteractionItems.safeStemHand("minecraft:pumpkin_seeds", false, false);
            case 5 -> PlainInteractionItems.safeStemHand("minecraft:jack_o_lantern", false, true);
            case 7 -> PlainInteractionItems.safeStemHand("minecraft:melon_seeds", false, true);
            default -> false;
        }).orElseThrow());
    }

    @Test void stemSelectionRetainsSelectedEmptyHandAndOtherwiseScansHotbarOnly() {
        assertEquals(8, PlainInteractionItems.chooseStemSlot(9, 8,
                slot -> slot == 8 || slot == 1).orElseThrow());
        assertTrue(PlainInteractionItems.chooseStemSlot(9, 0, slot -> slot == 25).isEmpty());
        assertTrue(PlainInteractionItems.chooseStemSlot(0, 0,
                slot -> { throw new AssertionError("Empty hotbar must not be read"); }).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> PlainInteractionItems.chooseStemSlot(36, 0, slot -> true));
        assertThrows(IllegalArgumentException.class, () -> PlainInteractionItems.chooseStemSlot(-1, 0, slot -> true));
    }

    @Test void plainDirtTakesPriorityOverPickupVulnerableEmptyHand() {
        assertEquals(4, PlainInteractionItems.chooseSlot(9, index -> index == 3,
                List.of(index -> index == 4)).orElseThrow());
    }

    @Test void emptyHandRemainsAFallbackWithoutPlainSupplies() {
        assertEquals(3, PlainInteractionItems.chooseSlot(9, index -> index == 3,
                List.of(index -> false)).orElseThrow());
    }

    @Test void mossPickupReselectsPlainDirtOnlyOncePerAttempt() {
        var recovery = new PlainInteractionItems.MossPickupReselection();
        assertEquals(4, recovery.chooseSlot(true, 9, index -> index >= 4).orElseThrow());
        assertTrue(recovery.chooseSlot(true, 9, index -> index == 5).isEmpty());
    }

    @Test void unrelatedOrModifiedPickupCannotTriggerReselection() {
        var recovery = new PlainInteractionItems.MossPickupReselection();
        assertTrue(recovery.chooseSlot(false, 9, index -> index == 4).isEmpty());
    }

    @Test void missingPlainHotbarReplacementFailsWithoutInventorySwapOrRetry() {
        var recovery = new PlainInteractionItems.MossPickupReselection();
        assertTrue(recovery.chooseSlot(true, 9, index -> index == 12).isEmpty());
        assertTrue(recovery.chooseSlot(true, 9, index -> index == 4).isEmpty());
    }

    @Test void specialKeyAndNamedBlockCandidatesAreSkippedForPlainDirt() {
        // Slots 1 and 2 contain unrelated or component-bearing items; only slot 6 is plain dirt.
        assertEquals(6, PlainInteractionItems.chooseSlot(9, index -> false,
                List.of(index -> index == 6)).orElseThrow());
    }

    @Test void PreferredDirtWinsOverAnEarlierPlainBuildingBlock() {
        assertEquals(6, PlainInteractionItems.chooseSlot(9, index -> false,
                List.of(index -> index == 6, index -> index == 0)).orElseThrow());
    }

    @Test void unrelatedItemsNeverBecomeAFallback() {
        assertTrue(PlainInteractionItems.chooseSlot(9, index -> false,
                List.of(index -> false)).isEmpty());
    }

    @Test void chestSelectionDoesNotReachIntoMainInventory() {
        assertTrue(PlainInteractionItems.chooseSlot(9, index -> index == 12,
                List.of(index -> index == 10)).isEmpty());
    }
}
