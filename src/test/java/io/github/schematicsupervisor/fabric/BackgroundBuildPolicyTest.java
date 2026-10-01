package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class BackgroundBuildPolicyTest {
    @Test
    void ordinaryInventoryCanBuildAndRefillHotbarWithoutOpeningAShop() {
        assertTrue(BackgroundBuildPolicy.allowsWorldActions(BackgroundBuildPolicy.Screen.INVENTORY, true, true));
        assertTrue(BackgroundBuildPolicy.allowsHotbarTransfer(BackgroundBuildPolicy.Screen.INVENTORY, true, true));
        assertFalse(BackgroundBuildPolicy.allowsNewInventoryTransaction(BackgroundBuildPolicy.Screen.INVENTORY, true, true));
    }

    @Test
    void eligibleInventoryAndSettingsKeepTheExistingMiningAttemptAndReceipt() {
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.INVENTORY,
                BackgroundBuildPolicy.Screen.SETTINGS}) {
            boolean blocked = !BackgroundBuildPolicy.allowsWorldActions(screen, true, true);
            assertEquals(InteractionTickDispatch.Next.ADVANCE,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.MOSS_MINING, blocked));
            assertEquals(InteractionTickDispatch.Next.ADVANCE,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.SUPPORT_MINING, blocked));
            assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.CLICK, blocked));
        }
    }

    @Test
    void heldInventoryCursorSettlesOwnedMiningBeforeAnyFurtherInteraction() {
        boolean blocked = !BackgroundBuildPolicy.allowsWorldActions(BackgroundBuildPolicy.Screen.INVENTORY, true, false);
        assertEquals(InteractionTickDispatch.Next.SETTLE_MOSS,
                InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.MOSS_MINING, blocked));
        assertEquals(InteractionTickDispatch.Next.SETTLE_SUPPORT,
                InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.SUPPORT_MINING, blocked));
    }

    @Test
    void settingsAndChatKeepBuildingWithoutReplacingTheUsersScreenWithAShop() {
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT}) {
            assertTrue(BackgroundBuildPolicy.allowsWorldActions(screen, true, true));
            assertTrue(BackgroundBuildPolicy.allowsHotbarTransfer(screen, true, true));
            assertFalse(BackgroundBuildPolicy.allowsNewInventoryTransaction(screen, true, true));
        }
    }

    @Test
    void heldCursorOrForeignContainerAlwaysStopsWorldAndInventoryActions() {
        for (var screen : BackgroundBuildPolicy.Screen.values()) {
            assertFalse(BackgroundBuildPolicy.allowsWorldActions(screen, true, false));
            assertFalse(BackgroundBuildPolicy.allowsWorldActions(screen, false, true));
            assertFalse(BackgroundBuildPolicy.allowsHotbarTransfer(screen, true, false));
            assertFalse(BackgroundBuildPolicy.allowsHotbarTransfer(screen, false, true));
            assertFalse(BackgroundBuildPolicy.allowsNewInventoryTransaction(screen, true, false));
            assertFalse(BackgroundBuildPolicy.allowsNewInventoryTransaction(screen, false, true));
        }
    }

    @Test
    void unknownScreensAreNeverAssumedSafeEvenWithThePlayerHandler() {
        assertFalse(BackgroundBuildPolicy.allowsWorldActions(BackgroundBuildPolicy.Screen.OTHER, true, true));
        assertFalse(BackgroundBuildPolicy.allowsHotbarTransfer(BackgroundBuildPolicy.Screen.OTHER, true, true));
        assertFalse(BackgroundBuildPolicy.allowsNewInventoryTransaction(BackgroundBuildPolicy.Screen.OTHER, true, true));
    }

    @Test
    void unobstructedGameplayAllowsNormalRestocking() {
        assertTrue(BackgroundBuildPolicy.allowsWorldActions(BackgroundBuildPolicy.Screen.GAMEPLAY, true, true));
        assertTrue(BackgroundBuildPolicy.allowsHotbarTransfer(BackgroundBuildPolicy.Screen.GAMEPLAY, true, true));
        assertTrue(BackgroundBuildPolicy.allowsNewInventoryTransaction(BackgroundBuildPolicy.Screen.GAMEPLAY, true, true));
    }

    @Test
    void depletedSelectedSlotCanRefillWhileTheInventoryRemainsVisible() {
        assertEquals(4, BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, true, 4, 9, index -> index == 4).orElseThrow());
    }

    @Test
    void visibleInventoryNeverDisplacesAnUnrelatedSelectedItem() {
        assertEquals(6, BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, true, 2, 9, index -> index == 6).orElseThrow());
        assertTrue(BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, true, 2, 9, index -> false).isEmpty());
    }

    @Test
    void refillWaitsForHeldCursorAndForeignContainerEvenWhenHotbarSpaceExists() {
        assertTrue(BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, false, 4, 9, index -> true).isEmpty());
        assertTrue(BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                false, true, 4, 9, index -> true).isEmpty());
        assertTrue(BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.OTHER,
                true, true, 4, 9, index -> true).isEmpty());
    }

    @Test
    void ordinaryRefillKeepsSelectedPlainSupplyAheadOfOtherPlainSupplies() {
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.GAMEPLAY,
                BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT}) {
            assertEquals(3, BackgroundBuildPolicy.hotbarDestination(screen,
                    true, true, 3, 9, index -> false, index -> index == 1 || index == 3).orElseThrow());
        }
    }

    @Test
    void fullHotbarRefillUsesPlainPickupAndKeepsAxeHoesAndKeyAvailable() {
        record Stack(String item, int count, boolean plainSupply) { }
        Stack[] inventory = new Stack[36];
        inventory[0] = new Stack("diamond_hoe", 1, false);
        inventory[1] = new Stack("custom_key", 1, false);
        inventory[2] = new Stack("moss_block", 64, true);
        inventory[3] = new Stack("diamond_hoe", 1, false);
        inventory[4] = new Stack("jack_o_lantern", 6, true);
        inventory[5] = new Stack("diamond_axe", 1, false);
        inventory[6] = new Stack("pumpkin_seeds", 64, true);
        inventory[7] = new Stack("custom_melon_seeds", 1, false);
        inventory[8] = new Stack("melon_seeds", 8, true);
        Stack[] original = inventory.clone();
        Stack glowstone = new Stack("glowstone", 64, true);
        inventory[9] = glowstone;

        // Clearing selected the axe immediately before ordinary placement selects Glowstone.
        int destination = BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                true, true, 5, 9, slot -> inventory[slot] == null,
                slot -> inventory[slot].plainSupply()).orElseThrow();
        assertEquals(2, destination);
        inventory[9] = inventory[destination];
        inventory[destination] = glowstone;
        assertSame(glowstone, inventory[2]);
        assertSame(original[2], inventory[9], "The complete pickup stack moves into the source slot");
        for (int slot : new int[]{0, 1, 3, 4, 5, 6, 7, 8}) {
            assertSame(original[slot], inventory[slot]);
        }
        assertEquals(2, BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                true, true, 5, 9, slot -> false, slot -> inventory[slot].plainSupply()).orElseThrow(),
                "A later refill still chooses a plain supply instead of the selected axe");
    }

    @Test
    void fullHotbarWithoutPlainSupplyDoesNotMoveAnySelectedToolOrSpecialItem() {
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.GAMEPLAY,
                BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT}) {
            assertTrue(BackgroundBuildPolicy.hotbarDestination(screen,
                    true, true, 5, 9, slot -> false, slot -> false).isEmpty());
            assertTrue(BackgroundBuildPolicy.hotbarDestination(screen,
                    true, true, 5, 9, slot -> false).isEmpty(), "Unknown destination contents are not plain");
        }
    }

    @Test
    void emptySlotWinsBeforePlainDestinationIsInspected() {
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.GAMEPLAY,
                BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT}) {
            assertEquals(8, BackgroundBuildPolicy.hotbarDestination(screen,
                    true, true, 2, 9, slot -> slot == 8,
                    slot -> { throw new AssertionError("Empty space must be preferred"); }).orElseThrow());
        }
    }

    @Test
    void fullHotbarRefillWhileTheInventoryIsVisibleUsesAPlainSupplyAndNeverATool() {
        // A full hotbar with the Inventory open must not wait forever for an empty slot.
        assertEquals(3, BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, true, 5, 9, slot -> false, slot -> slot == 3 || slot == 7).orElseThrow());
        assertEquals(5, BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, true, 5, 9, slot -> false, slot -> slot == 3 || slot == 5).orElseThrow());
        assertTrue(BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.INVENTORY,
                true, true, 5, 9, slot -> false, slot -> false).isEmpty());
    }

    @Test
    void restrictedContextsNeverInspectOccupiedDestinationCandidates() {
        for (var screen : BackgroundBuildPolicy.Screen.values()) {
            for (boolean playerHandler : new boolean[]{true, false}) {
                for (boolean emptyCursor : new boolean[]{true, false}) {
                    if (playerHandler && emptyCursor && screen != BackgroundBuildPolicy.Screen.OTHER) { continue; }
                    assertTrue(BackgroundBuildPolicy.hotbarDestination(screen, playerHandler, emptyCursor,
                            5, 9, slot -> { throw new AssertionError("Unsafe context"); },
                            slot -> { throw new AssertionError("Unsafe context"); }).isEmpty());
                }
            }
        }
    }

    @Test
    void destinationSearchIsBoundedToTheHotbar() {
        assertThrows(IllegalArgumentException.class, () -> BackgroundBuildPolicy.hotbarDestination(
                BackgroundBuildPolicy.Screen.GAMEPLAY, true, true, 0, 36, slot -> false, slot -> true));
        int[] queries = {0};
        assertTrue(BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                true, true, 5, 9, slot -> false, slot -> {
                    assertTrue(slot >= 0 && slot < 9);
                    queries[0]++;
                    return false;
                }).isEmpty());
        assertEquals(9, queries[0]);
    }

    @Test
    void ordinaryRefillPrefersSpaceOverDisplacingTheSelectedToolOnEveryAllowedScreen() {
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.GAMEPLAY,
                BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT}) {
            assertEquals(8, BackgroundBuildPolicy.hotbarDestination(screen,
                    true, true, 0, 9, index -> index == 8).orElseThrow());
        }
    }

    @Test
    void selectedDepletedSupplySlotKeepsPriorityOverOtherEmptySlots() {
        assertEquals(8, BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                true, true, 8, 9, index -> index == 3 || index == 8).orElseThrow());
    }
}
