package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MossRestockGateTest {
    @Test void pendingReceiptStillReconcilesAfterDirtBecomesFulfilledAndMossSourceIsEmpty() {
        assertEquals(MossRestockGate.Decision.RECONCILE,
                MossRestockGate.decide(true, true, false, false, false, true, false));
    }

    @Test void pendingReceiptIgnoresNewDepositEligibilityAndAttemptHistory() {
        for (boolean shortage : List.of(false, true)) {
            for (boolean attempted : List.of(false, true)) {
                for (boolean moss : List.of(false, true)) {
                    assertEquals(MossRestockGate.Decision.RECONCILE,
                            MossRestockGate.decide(true, true, false, false, shortage, attempted, moss));
                }
            }
        }
    }

    @Test void pendingReceiptHoldsUnexpectedActiveStateAndCompetingUnsettledReceipt() {
        assertEquals(MossRestockGate.Decision.HOLD,
                MossRestockGate.decide(true, false, false, false, false, true, false));
        assertEquals(MossRestockGate.Decision.HOLD,
                MossRestockGate.decide(true, true, false, true, true, false, true));
    }

    @Test void pausedStopOrDoneNeverStartsMaintenanceEvenWithPendingReceiptOrContradictoryRestockFlag() {
        for (boolean pending : List.of(false, true)) {
            for (boolean restocking : List.of(false, true)) {
                assertEquals(MossRestockGate.Decision.WAIT_PAUSED,
                        MossRestockGate.decide(pending, restocking, true, false, true, false, true));
            }
        }
    }

    @Test void existingNonMossReceiptMaySettleNormallyWithoutStartingANewMossDeposit() {
        assertEquals(MossRestockGate.Decision.CONTINUE,
                MossRestockGate.decide(false, true, false, true, true, false, true));
    }

    @Test void newDepositRequiresEveryEligibilityGuardAndIsBoundedToOneRestockAttempt() {
        assertEquals(MossRestockGate.Decision.DEPOSIT,
                MossRestockGate.decide(false, true, false, false, true, false, true));
        assertEquals(MossRestockGate.Decision.CONTINUE,
                MossRestockGate.decide(false, true, false, false, true, true, true));
        assertEquals(MossRestockGate.Decision.CONTINUE,
                MossRestockGate.decide(false, true, false, false, false, false, true));
        assertEquals(MossRestockGate.Decision.CONTINUE,
                MossRestockGate.decide(false, true, false, false, true, false, false));
        assertEquals(MossRestockGate.Decision.CONTINUE,
                MossRestockGate.decide(false, false, false, false, true, false, true));
    }

    @Test void everyBooleanCombinationPreservesNoReplayPauseAndReceiptPriorityInvariants() {
        for (int flags = 0; flags < 128; flags++) {
            boolean pending = (flags & 1) != 0;
            boolean restocking = (flags & 2) != 0;
            boolean paused = (flags & 4) != 0;
            boolean otherReceipt = (flags & 8) != 0;
            boolean shortage = (flags & 16) != 0;
            boolean attempted = (flags & 32) != 0;
            boolean moss = (flags & 64) != 0;
            var decision = MossRestockGate.decide(pending, restocking, paused, otherReceipt, shortage, attempted, moss);
            String state = "input flags=" + flags;
            if (paused) { assertEquals(MossRestockGate.Decision.WAIT_PAUSED, decision, state); }
            if (pending && !paused) {
                assertTrue(decision == MossRestockGate.Decision.HOLD || decision == MossRestockGate.Decision.RECONCILE, state);
            }
            if (decision == MossRestockGate.Decision.DEPOSIT) {
                assertFalse(pending || paused || otherReceipt || attempted, state);
                assertTrue(restocking && shortage && moss, state);
            }
            if (decision == MossRestockGate.Decision.RECONCILE) {
                assertTrue(pending && restocking && !paused && !otherReceipt, state);
            }
            if (otherReceipt) { assertNotEquals(MossRestockGate.Decision.DEPOSIT, decision, state); }
        }
    }
}
