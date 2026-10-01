package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RestockTransferStatus;
import io.github.schematicsupervisor.fabric.DepotCancellationPolicy.State;
import io.github.schematicsupervisor.fabric.DepotCancellationPolicy.Termination;
import org.junit.jupiter.api.Test;

class DepotCancellationPolicyTest {
    private static final MaterialQuantities DELTA =
            MaterialQuantities.of(Material.DIRT, 7);

    @Test
    void terminalSuccessAndFailurePreserveDetailAndUnpolledDelta() {
        State success = new State(
                RestockTransferStatus.SUCCEEDED,
                Termination.NONE,
                "",
                DELTA
        );
        State failure = new State(
                RestockTransferStatus.FAILED,
                Termination.FAIL,
                "server rejected transfer",
                DELTA
        );

        assertSame(success, DepotCancellationPolicy.requestCancellation(success, false));
        assertSame(failure, DepotCancellationPolicy.requestCancellation(failure, false));
        assertEquals(DELTA, success.unpolledMovement());
        assertEquals(DELTA, failure.unpolledMovement());
        assertEquals("server rejected transfer", failure.detail());
    }

    @Test
    void cancellationDoesNotOverwriteFailureCleanup() {
        State failureCleanup = new State(
                RestockTransferStatus.RUNNING,
                Termination.FAIL,
                "pending inventory action failed",
                DELTA
        );

        State requested =
                DepotCancellationPolicy.requestCancellation(failureCleanup, true);

        assertSame(failureCleanup, requested);
        assertEquals(Termination.FAIL, requested.termination());
        assertEquals("pending inventory action failed", requested.detail());
        assertEquals(DELTA, requested.unpolledMovement());
    }

    @Test
    void firstCancellationStartsWithoutDiscardingDelta() {
        State running = new State(
                RestockTransferStatus.RUNNING,
                Termination.NONE,
                "",
                DELTA
        );

        State requested = DepotCancellationPolicy.requestCancellation(running, true);

        assertEquals(RestockTransferStatus.RUNNING, requested.status());
        assertEquals(Termination.CANCEL, requested.termination());
        assertEquals(DELTA, requested.unpolledMovement());
    }

    @Test
    void cancellationExceptionBecomesFailureWithoutDiscardingDelta() {
        State cancelling = new State(
                RestockTransferStatus.RUNNING,
                Termination.CANCEL,
                "",
                DELTA
        );

        State failed = DepotCancellationPolicy.cancellationFailure(
                cancelling,
                "could not fully cancel depot navigation: unavailable"
        );

        assertEquals(RestockTransferStatus.RUNNING, failed.status());
        assertEquals(Termination.FAIL, failed.termination());
        assertEquals(
                "could not fully cancel depot navigation: unavailable",
                failed.detail()
        );
        assertEquals(DELTA, failed.unpolledMovement());
    }

    @Test
    void cancellationExceptionAppendsExistingFailureAndRejectsBlankDetail() {
        State priorFailure = new State(
                RestockTransferStatus.RUNNING,
                Termination.FAIL,
                "pending inventory action failed",
                DELTA
        );

        State failed = DepotCancellationPolicy.cancellationFailure(
                priorFailure,
                "could not fully cancel depot navigation: unavailable"
        );

        assertEquals(Termination.FAIL, failed.termination());
        assertEquals(
                "pending inventory action failed; "
                        + "could not fully cancel depot navigation: unavailable",
                failed.detail()
        );
        assertEquals(DELTA, failed.unpolledMovement());
        assertThrows(
                IllegalArgumentException.class,
                () -> DepotCancellationPolicy.cancellationFailure(priorFailure, " ")
        );
    }

    @Test
    void withdrawalOperationIsNeverMaintenanceIdle() {
        assertFalse(DepotCancellationPolicy.maintenanceIdle(false, true));
        assertFalse(DepotCancellationPolicy.maintenanceIdle(true, false));
        assertTrue(DepotCancellationPolicy.maintenanceIdle(true, true));
    }

    @Test
    void uncertainStopRetainsOutstandingOpenAndPendingActionState() {
        assertFalse(DepotCancellationPolicy.mayFinishAfterStopFailure(true, false));
        assertFalse(DepotCancellationPolicy.mayFinishAfterStopFailure(false, true));
        assertFalse(DepotCancellationPolicy.mayFinishAfterStopFailure(true, true));
        assertTrue(DepotCancellationPolicy.mayFinishAfterStopFailure(false, false));
    }

    @Test
    void scanStopWaitsForOutstandingAcceptedScreenResponse() {
        assertFalse(DepotCancellationPolicy.maintenanceStopSettled(true, true));
        assertFalse(DepotCancellationPolicy.maintenanceStopSettled(false, false));
        assertTrue(DepotCancellationPolicy.maintenanceStopSettled(true, false));
    }
}
