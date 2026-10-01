package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DepotInteractionHandPolicyTest {
    private static final DepotInteractionHandPolicy.Context CHEST =
            new DepotInteractionHandPolicy.Context(true, true, false, false, true, true);

    @Test void collectedSeedsMossAndJackCanOpenTheRegisteredChestWithDefaultComponents() {
        for (String item : List.of("minecraft:pumpkin_seeds", "minecraft:melon_seeds",
                "minecraft:moss_block", "minecraft:jack_o_lantern")) {
            assertTrue(DepotInteractionHandPolicy.allowsItem(item, false, true));
            assertEquals("", DepotInteractionHandPolicy.rejection(CHEST,
                    DepotInteractionHandPolicy.allowsItem(item, false, true)));
            assertFalse(DepotInteractionHandPolicy.rejection(CHEST,
                    DepotInteractionHandPolicy.allowsItem(item, false, false)).isEmpty());
        }
    }

    @Test void emptyAndPlainBuildSuppliesRetainTheirExistingChestUse() {
        assertEquals("", DepotInteractionHandPolicy.rejection(CHEST,
                DepotInteractionHandPolicy.allowsItem(null, true, false)));
        for (String item : List.of("minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks")) {
            assertEquals("", DepotInteractionHandPolicy.rejection(CHEST,
                    DepotInteractionHandPolicy.allowsItem(item, false, true)));
        }
    }

    @Test void itemPermissionCannotAuthorizeAnotherTargetOffhandOrBlockUseBypass() {
        for (var unsafe : List.of(
                new DepotInteractionHandPolicy.Context(false, true, false, false, true, true),
                new DepotInteractionHandPolicy.Context(true, false, false, false, true, true),
                new DepotInteractionHandPolicy.Context(true, true, true, false, true, true),
                new DepotInteractionHandPolicy.Context(true, true, false, true, true, true),
                new DepotInteractionHandPolicy.Context(true, true, false, false, false, true),
                new DepotInteractionHandPolicy.Context(true, true, false, false, true, false))) {
            assertFalse(DepotInteractionHandPolicy.rejection(unsafe, true).isEmpty());
        }
    }

    @Test void specialKeysToolsCustomSeedsAndOtherItemsAreNeverChestHands() {
        for (String item : List.of("minecraft:tripwire_hook", "minecraft:trial_key", "server:crate_key",
                "minecraft:diamond_hoe", "minecraft:diamond_axe", "minecraft:diamond_shovel",
                "minecraft:writable_book", "minecraft:pumpkin", "minecraft:stone")) {
            assertFalse(DepotInteractionHandPolicy.allowsItem(item, false, true));
        }
        assertFalse(DepotInteractionHandPolicy.allowsItem(null, false, true));
        assertFalse(DepotInteractionHandPolicy.rejection(CHEST, false).isEmpty());
    }

    @Test void aFullObservedStyleHotbarSelectsPlainPickupWithoutMovingToolsOrKeys() {
        List<String> hotbar = List.of("minecraft:diamond_hoe", "minecraft:tripwire_hook", "minecraft:moss_block",
                "minecraft:diamond_hoe", "minecraft:pumpkin_seeds", "minecraft:jack_o_lantern",
                "minecraft:jack_o_lantern", "minecraft:diamond_axe", "minecraft:melon_seeds");
        assertEquals(8, PlainInteractionItems.chooseStemSlot(9, 8,
                slot -> DepotInteractionHandPolicy.allowsItem(hotbar.get(slot), false, true)).orElseThrow());
        assertEquals(2, PlainInteractionItems.chooseStemSlot(9, 0,
                slot -> DepotInteractionHandPolicy.allowsItem(hotbar.get(slot), false, true)).orElseThrow());
        assertTrue(PlainInteractionItems.chooseStemSlot(9, 0,
                slot -> DepotInteractionHandPolicy.allowsItem(hotbar.get(slot), false, false)).isEmpty());
    }

    @Test void seedRefillCanOpenAnotherChestAfterFillingEveryUsableHotbarSlot() {
        List<String> hotbar = List.of("minecraft:tripwire_hook", "minecraft:writable_book",
                "minecraft:wheat_seeds", "minecraft:wheat_seeds", "minecraft:wheat_seeds",
                "minecraft:wheat_seeds", "minecraft:wheat_seeds", "minecraft:wheat_seeds",
                "minecraft:wheat_seeds");
        assertEquals(8, PlainInteractionItems.chooseStemSlot(9, 8,
                slot -> DepotInteractionHandPolicy.allowsItem(hotbar.get(slot), false, true)).orElseThrow());
        assertEquals(2, PlainInteractionItems.chooseStemSlot(9, 0,
                slot -> DepotInteractionHandPolicy.allowsItem(hotbar.get(slot), false, true)).orElseThrow());
        assertEquals("", DepotInteractionHandPolicy.rejection(CHEST,
                DepotInteractionHandPolicy.allowsItem("minecraft:wheat_seeds", false, true)));
        assertFalse(DepotInteractionHandPolicy.allowsItem("minecraft:wheat_seeds", false, false));
        assertFalse(PlainInteractionItems.safeStemHand("minecraft:wheat_seeds", false, true));
    }
}
