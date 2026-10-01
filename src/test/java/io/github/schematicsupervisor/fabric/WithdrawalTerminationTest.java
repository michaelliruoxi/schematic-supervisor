package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.core.RestockTransferStatus;
import io.github.schematicsupervisor.fabric.DepotCancellationPolicy.Termination;
import io.github.schematicsupervisor.fabric.WithdrawalTermination.Facts;
import org.junit.jupiter.api.Test;

final class WithdrawalTerminationTest {
    @Test
    void aCleanUnreachableFailureLetsTheSupervisorPlanFromOtherChests() {
        var outcome = WithdrawalTermination.settle(failure(false, true, "", false, false, false));
        assertEquals(RestockTransferStatus.UNREACHABLE, outcome.status());
        assertEquals("route failed", outcome.detail());
    }

    @Test
    void anythingLeftBehindTurnsUnreachableAndCapacityOutcomesIntoFailures() {
        for (Facts leftBehind : new Facts[]{
                failure(false, true, "screen left open", false, false, false),
                failure(false, true, "", true, false, false),
                failure(false, true, "", false, true, false),
                failure(false, true, "", false, false, true),
                failure(true, false, "screen left open", false, false, false),
                failure(true, false, "", false, false, true)}) {
            assertEquals(RestockTransferStatus.FAILED, WithdrawalTermination.settle(leftBehind).status(),
                    leftBehind.toString());
        }
    }

    @Test
    void aCleanCapacityRejectionIsReportedAsCapacityBlockedEvenForAnUnreachableChest() {
        assertEquals(RestockTransferStatus.CAPACITY_BLOCKED,
                WithdrawalTermination.settle(failure(true, false, "", false, false, false)).status());
        assertEquals(RestockTransferStatus.CAPACITY_BLOCKED,
                WithdrawalTermination.settle(failure(true, true, "", false, false, false)).status());
    }

    @Test
    void cleanupProblemsAreAppendedToTheFailureDetail() {
        assertEquals("route failed; cursor origin is unknown",
                WithdrawalTermination.settle(failure(false, false, "cursor origin is unknown", false, false, false))
                        .detail());
    }

    @Test
    void aCancelledWithdrawalEndsIdleWithOnlyItsCleanupProblem() {
        for (Termination requested : new Termination[]{Termination.NONE, Termination.CANCEL}) {
            var outcome = WithdrawalTermination.settle(new Facts(requested, "ignored", "screen left open",
                    true, true, false, false, false));
            assertEquals(RestockTransferStatus.IDLE, outcome.status());
            assertEquals("screen left open", outcome.detail());
        }
    }

    @Test
    void onlyAMissingRouteNamesTheUnreachableDepot() {
        assertEquals("depot-016 is unreachable: no route",
                WithdrawalTermination.routeFailureDetail("depot-016", "no route", true));
        assertEquals("Manual movement input interrupted the flight",
                WithdrawalTermination.routeFailureDetail("", "Manual movement input interrupted the flight", false));
    }

    private static Facts failure(boolean capacityRejected, boolean unreachable, String cleanupProblem,
                                 boolean automationBlocked, boolean actionPending, boolean cursorHeld) {
        return new Facts(Termination.FAIL, "route failed", cleanupProblem, capacityRejected, unreachable,
                automationBlocked, actionPending, cursorHeld);
    }
}
