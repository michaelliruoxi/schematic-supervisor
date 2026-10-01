package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class MossHoeHotbarRecoveryTest {
    private static final String HOE = "diamond_hoe:original_components";
    private static final String DIRT = "plain_dirt";

    @Test
    void repeatedDirtRefillsKeepTheMossHoeInItsHotbarSlot() {
        String[] inventory = new String[36];
        Arrays.fill(inventory, 0, 9, "other_item");
        inventory[0] = HOE;
        inventory[8] = null;
        inventory[10] = DIRT;
        inventory[11] = DIRT;

        for (int source : new int[]{10, 11}) {
            // Moss clearing selects the hoe after the preceding Dirt stack runs out.
            int destination = refill(inventory, 0).orElseThrow();
            assertEquals(8, destination);
            swap(inventory, source, destination);
            assertEquals(HOE, inventory[0]);
            assertEquals(DIRT, inventory[8]);
            assertNull(inventory[source]);
            inventory[8] = null;
        }
    }

    @Test
    void alreadyDisplacedHoeRecoversWithoutMovingDirtOrResettingWearAndSurvivesNextRefill() {
        var guard = new MossMiningToolGuard<String>(String::equals);
        var original = guard.track(HOE, wear(28)).orElseThrow();
        var preceding = guard.begin(0, original, HOE, wear(28)).orElseThrow();
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, original.prepare(wear(28), true));
        assertTrue(guard.begin(0, original, HOE, wear(28)).isEmpty(), "A pending charge forbids another START");
        assertTrue(guard.settleCharge(preceding.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertEquals(1532, original.allowance(), "Confirmation preserves the preceding debit");
        String[] inventory = new String[36];
        inventory[0] = DIRT;
        inventory[1] = "tripwire_hook";
        inventory[2] = "plain_moss";
        inventory[10] = HOE;
        inventory[11] = DIRT;

        int destination = recovery(inventory, 0, false, false).orElseThrow();
        assertEquals(3, destination);
        swap(inventory, 10, destination);
        assertEquals(DIRT, inventory[0]);
        assertEquals(HOE, inventory[3]);
        assertNull(inventory[10]);
        var recovered = guard.track(inventory[destination], wear(30)).orElseThrow();
        assertSame(original, recovered);
        assertEquals(1531, recovered.allowance());
        var owned = guard.begin(destination, recovered, inventory[destination], wear(30)).orElseThrow();
        assertEquals(1530, recovered.allowance());
        assertFalse(guard.owns(owned, 0, HOE, wear(30)));
        assertTrue(guard.settleCharge(owned.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));

        inventory[0] = null;
        int dirtDestination = refill(inventory, destination).orElseThrow();
        assertEquals(0, dirtDestination);
        swap(inventory, 11, dirtDestination);
        assertEquals(DIRT, inventory[0]);
        assertEquals(HOE, inventory[3]);
        assertSame(recovered, guard.track(inventory[3], wear(30)).orElseThrow());
        assertEquals(1530, recovered.allowance(), "Refills and stale inventory damage grant no new wear");
    }

    @Test
    void fullHotbarCannotRecoverButExistingPlainHandChoiceRemainsAvailable() {
        String[] inventory = new String[36];
        Arrays.fill(inventory, 0, 9, "plain_moss");
        inventory[4] = DIRT;
        inventory[10] = HOE;
        for (var screen : BackgroundBuildPolicy.Screen.values()) {
            assertTrue(BackgroundBuildPolicy.hotbarRecoveryDestination(screen, true, true,
                    false, false, 4, 9, index -> inventory[index] == null).isEmpty());
        }
        assertEquals(4, PlainInteractionItems.chooseSlot(9, index -> inventory[index] == null,
                List.of(index -> DIRT.equals(inventory[index]))).orElseThrow());
        assertEquals(HOE, inventory[10]);
    }

    @Test
    void pendingRepairOrMiningOrReceiptPreventsRecoveryBeforeInspectingSlots() {
        for (boolean repairPending : new boolean[]{false, true}) {
            for (boolean interactionPending : new boolean[]{false, true}) {
                if (!repairPending && !interactionPending) { continue; }
                assertTrue(BackgroundBuildPolicy.hotbarRecoveryDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                        true, true, repairPending, interactionPending, 0, 9,
                        index -> { throw new AssertionError("An owned operation cannot move its tool"); }).isEmpty());
            }
        }
    }

    @Test
    void recoveryKeepsTheExistingCursorContainerAndScreenRestrictions() {
        for (var screen : BackgroundBuildPolicy.Screen.values()) {
            assertTrue(BackgroundBuildPolicy.hotbarRecoveryDestination(screen, false, true,
                    false, false, 0, 9, index -> true).isEmpty());
            assertTrue(BackgroundBuildPolicy.hotbarRecoveryDestination(screen, true, false,
                    false, false, 0, 9, index -> true).isEmpty());
        }
        assertTrue(BackgroundBuildPolicy.hotbarRecoveryDestination(BackgroundBuildPolicy.Screen.OTHER,
                true, true, false, false, 0, 9, index -> true).isEmpty());
        for (var screen : new BackgroundBuildPolicy.Screen[]{BackgroundBuildPolicy.Screen.GAMEPLAY,
                BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT}) {
            assertEquals(7, BackgroundBuildPolicy.hotbarRecoveryDestination(screen, true, true,
                    false, false, 7, 9, index -> index == 3 || index == 7).orElseThrow());
        }
    }

    private static OptionalInt refill(String[] inventory, int selected) {
        return BackgroundBuildPolicy.hotbarDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                true, true, selected, 9, index -> inventory[index] == null);
    }

    private static OptionalInt recovery(String[] inventory, int selected, boolean repair, boolean interaction) {
        return BackgroundBuildPolicy.hotbarRecoveryDestination(BackgroundBuildPolicy.Screen.GAMEPLAY,
                true, true, repair, interaction, selected, 9, index -> inventory[index] == null);
    }

    private static void swap(String[] inventory, int source, int destination) {
        String previous = inventory[destination];
        inventory[destination] = inventory[source];
        inventory[source] = previous;
    }

    private static MossMiningToolGuard.Durability wear(int damage) {
        return new MossMiningToolGuard.Durability(damage, 1561, false);
    }
}
