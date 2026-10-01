package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class InteractionTickDispatchTest {
    @Test
    void ownedMiningAdvancesItsBoundedBreakWhileItsReceiptWaitsButPlacementCannotRepeatInput() {
        assertEquals(InteractionTickDispatch.Next.ADVANCE, InteractionTickDispatch.next(true,
                InteractionTickDispatch.Kind.SUPPORT_MINING, false));
        assertEquals(InteractionTickDispatch.Next.ADVANCE, InteractionTickDispatch.next(true,
                InteractionTickDispatch.Kind.MOSS_MINING, false));
        assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT, InteractionTickDispatch.next(true,
                InteractionTickDispatch.Kind.CLICK, false));
    }

    @Test
    void blockingScreenKeepsEachMiningReceiptInItsOwnConfirmationPathEvenAfterEarlyAcknowledgement() {
        for (boolean pending : new boolean[]{false, true}) {
            assertEquals(InteractionTickDispatch.Next.SETTLE_SUPPORT, InteractionTickDispatch.next(pending,
                    InteractionTickDispatch.Kind.SUPPORT_MINING, true));
            assertEquals(InteractionTickDispatch.Next.SETTLE_MOSS, InteractionTickDispatch.next(pending,
                    InteractionTickDispatch.Kind.MOSS_MINING, true));
        }
        assertEquals(InteractionTickDispatch.Next.WAIT_SCREEN, InteractionTickDispatch.next(false,
                InteractionTickDispatch.Kind.CLICK, true));
    }
}
