package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DepotTransferPolicyTest {
    @Test
    void thirtyItemAllocationReturnsTheUncreditedRemainderBeforeCompleting() {
        ExactTransferLedger ledger = new ExactTransferLedger(MaterialQuantities.of(Material.DIRT, 30));
        int playerCount = 2;
        int chestCount = 3454;
        int cursorCount = 62;
        chestCount -= cursorCount;
        for (int transfer = 0; transfer < 30; transfer++) {
            assertEquals(DepotTransferPolicy.Step.DEPOSIT_ONE,
                    DepotTransferPolicy.next(false, ledger.complete(), true, false,
                            ledger.remaining(Material.DIRT)));
            playerCount++;
            cursorCount--;
            ledger.recordGain(Material.DIRT, 1);
        }
        assertTrue(ledger.complete());
        assertEquals(32, playerCount);
        assertEquals(32, cursorCount);
        assertEquals(DepotTransferPolicy.Step.RETURN_CURSOR,
                DepotTransferPolicy.next(false, true, true, false, 0));
        // Even a locally empty cursor cannot finish before its return action settles.
        chestCount += cursorCount;
        cursorCount = 0;
        assertEquals(DepotTransferPolicy.Step.SETTLE_PENDING,
                DepotTransferPolicy.next(true, true, true, true, 0));
        assertEquals(DepotTransferPolicy.Step.COMPLETE,
                DepotTransferPolicy.next(false, true, false, cursorCount == 0, -1));
        assertEquals(3424, chestCount);
        assertEquals(30, ledger.moved().get(Material.DIRT));
        assertEquals(32, playerCount, "Closing must not add the unallocated remainder to player inventory");
    }

    @Test
    void unknownOrDisappearedCursorCannotBeHiddenByCompletedAllocation() {
        assertEquals(DepotTransferPolicy.Step.REJECT_CURSOR,
                DepotTransferPolicy.next(false, true, false, false, -1));
        assertEquals(DepotTransferPolicy.Step.REJECT_CURSOR,
                DepotTransferPolicy.next(false, true, true, true, 0));
        assertEquals(DepotTransferPolicy.Step.REJECT_CURSOR,
                DepotTransferPolicy.next(false, true, true, false, -1));
    }

    @Test
    void finishedMaterialReturnsItsCursorBeforeSelectingAnotherMaterial() {
        ExactTransferLedger ledger = new ExactTransferLedger(MaterialQuantities.of(Map.of(
                Material.DIRT, 30L, Material.GLOWSTONE, 2L)));
        ledger.recordGain(Material.DIRT, 30);
        assertEquals(DepotTransferPolicy.Step.RETURN_CURSOR,
                DepotTransferPolicy.next(false, ledger.complete(), true, false, ledger.remaining(Material.DIRT)));
        assertEquals(DepotTransferPolicy.Step.NEXT_SOURCE,
                DepotTransferPolicy.next(false, ledger.complete(), false, true, -1));
    }

    @Test
    void pendingActionSettlesBeforeAnyCursorOrCompletionDecision() {
        for (boolean completed : new boolean[]{false, true}) {
            for (boolean known : new boolean[]{false, true}) {
                for (boolean empty : new boolean[]{false, true}) {
                    assertEquals(DepotTransferPolicy.Step.SETTLE_PENDING,
                            DepotTransferPolicy.next(true, completed, known, empty, 0));
                }
            }
        }
    }

    @Test
    void singleDepositFreezesCountsBeforeVanillaMutatesTheSameCursorObject() {
        int[] playerCount = {2};
        int[] cursorCount = {62};
        DepotTransferPolicy.SingleDeposit observed = DepotTransferPolicy.issueSingleDeposit(
                playerCount[0], cursorCount[0], () -> {
                    playerCount[0]++;
                    cursorCount[0]--;
                });
        assertEquals(2, observed.playerCountBefore());
        assertEquals(61, observed.cursorCountExpected());
        assertEquals(3, playerCount[0]);
        assertEquals(cursorCount[0], observed.cursorCountExpected());
    }

    @Test
    void invalidPreClickCountsSendNoInput() {
        int[] clicks = {0};
        assertThrows(IllegalArgumentException.class,
                () -> DepotTransferPolicy.issueSingleDeposit(0, 0, () -> clicks[0]++));
        assertThrows(IllegalArgumentException.class,
                () -> DepotTransferPolicy.issueSingleDeposit(-1, 64, () -> clicks[0]++));
        assertEquals(0, clicks[0]);
    }
}
