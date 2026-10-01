package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShopMenuCleanupPolicyTest {
    @Test void cancelledPredictedCursorSettlesBeforeExactlyOneCloseAndOwnershipRelease() {
        var state = ShopMenuCleanupPolicy.State.initial();
        for (int tick = 0; tick < 8; tick++) {
            var wait = ShopMenuCleanupPolicy.decide(state, observed(false, true));
            assertEquals(ShopMenuCleanupPolicy.Action.WAIT, wait.action());
            assertEquals(ShopMenuCleanupPolicy.Reason.OWNED_CURSOR_PENDING, wait.reason());
            state = wait.nextState();
        }
        var close = ShopMenuCleanupPolicy.decide(state, observed(true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.CLOSE, close.action());
        assertTrue(close.nextState().closeIssued());
        var stillOpen = ShopMenuCleanupPolicy.decide(close.nextState(), observed(true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.WAIT, stillOpen.action());
        assertEquals(ShopMenuCleanupPolicy.Reason.CLOSE_PENDING, stillOpen.reason());
        var gone = ShopMenuCleanupPolicy.decide(stillOpen.nextState(),
                new ShopMenuCleanupPolicy.Observation(true, true, true, true, false, true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.RELEASE_OWNERSHIP, gone.action());
    }

    @Test void anUnrelatedCursorOrUnavailableFactsNeverAuthorizeClosure() {
        for (var observation : List.of(observed(false, false),
                new ShopMenuCleanupPolicy.Observation(true, true, false, true, true, true, false))) {
            var decision = ShopMenuCleanupPolicy.decide(ShopMenuCleanupPolicy.State.initial(), observation);
            assertEquals(ShopMenuCleanupPolicy.Action.WAIT, decision.action());
            assertFalse(decision.nextState().closeIssued());
        }
    }

    @Test void activeNavigationCannotBeClosedByCleanupOrSpendItsBudget() {
        var state = ShopMenuCleanupPolicy.State.initial();
        var active = new ShopMenuCleanupPolicy.Observation(false, true, true, true, true, true, false);
        for (int tick = 0; tick < 500; tick++) {
            var decision = ShopMenuCleanupPolicy.decide(state, active);
            assertEquals(ShopMenuCleanupPolicy.Action.WAIT, decision.action());
            assertEquals(state, decision.nextState());
        }
    }

    @Test void changedConnectionWorldOrHandlerReleasesOnlyStaleOwnershipWithoutInput() {
        for (var observation : List.of(
                new ShopMenuCleanupPolicy.Observation(true, true, true, false, true, true, false),
                new ShopMenuCleanupPolicy.Observation(true, true, true, true, false, false, false),
                new ShopMenuCleanupPolicy.Observation(true, false, true, true, true, true, false))) {
            var decision = ShopMenuCleanupPolicy.decide(ShopMenuCleanupPolicy.State.initial(), observation);
            assertEquals(ShopMenuCleanupPolicy.Action.RELEASE_OWNERSHIP, decision.action());
            assertFalse(decision.nextState().closeIssued());
        }
    }

    @Test void thrownOrUnacknowledgedCloseCannotBeRetriedAndTimeoutPreservesOwnership() {
        var first = ShopMenuCleanupPolicy.decide(ShopMenuCleanupPolicy.State.initial(), observed(true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.CLOSE, first.action());
        // The adapter saves this nextState before a close whose outcome may be unknown.
        var state = first.nextState();
        for (int tick = 0; tick < 500; tick++) {
            var next = ShopMenuCleanupPolicy.decide(state, observed(true, false));
            assertEquals(ShopMenuCleanupPolicy.Action.WAIT, next.action());
            state = next.nextState();
        }
        assertEquals(ShopMenuCleanupPolicy.MAXIMUM_TICKS, state.elapsedTicks());
        assertEquals(ShopMenuCleanupPolicy.Reason.EXHAUSTED,
                ShopMenuCleanupPolicy.decide(state, observed(true, false)).reason());
    }

    @Test void cursorSettlementAfterTheBoundCannotTriggerALateClose() {
        var state = ShopMenuCleanupPolicy.State.initial();
        for (int tick = 0; tick < ShopMenuCleanupPolicy.MAXIMUM_TICKS; tick++) {
            state = ShopMenuCleanupPolicy.decide(state, observed(false, true)).nextState();
        }
        var late = ShopMenuCleanupPolicy.decide(state, observed(true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.WAIT, late.action());
        assertEquals(ShopMenuCleanupPolicy.Reason.EXHAUSTED, late.reason());
        assertFalse(late.nextState().closeIssued());
    }

    @Test void closingTheShopDoesNotClearTheIndependentPendingPurchaseUnloadGuard() {
        var cleanup = ShopMenuCleanupPolicy.decide(ShopMenuCleanupPolicy.State.initial(), observed(true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.CLOSE, cleanup.action());
        cleanup = ShopMenuCleanupPolicy.decide(cleanup.nextState(),
                new ShopMenuCleanupPolicy.Observation(true, true, true, true, false, true, false));
        assertEquals(ShopMenuCleanupPolicy.Action.RELEASE_OWNERSHIP, cleanup.action());
        // Even completed menu cleanup cannot stand in for the independent durable stock receipt.
        for (SupervisorState state : List.of(SupervisorState.PAUSED, SupervisorState.STOPPED, SupervisorState.DONE)) {
            var pending = new BuildUnloadPolicy.Snapshot(state, false, false, false, 1,
                    false, false, false, true, false);
            assertFalse(BuildUnloadPolicy.activityProblem(pending).isBlank());
            assertFalse(BuildUnloadPolicy.settledProblem(pending).isBlank());
            assertFalse(BuildUnloadPolicy.receiptProblem(pending).isBlank());
        }
    }

    @Test void boundedStateAndContradictoryCursorEvidenceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShopMenuCleanupPolicy.State(-1, false));
        assertThrows(IllegalArgumentException.class, () -> new ShopMenuCleanupPolicy.State(201, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ShopMenuCleanupPolicy.Observation(true, true, true, true, true, true, true));
    }

    private static ShopMenuCleanupPolicy.Observation observed(boolean cursorEmpty, boolean ownedCursor) {
        return new ShopMenuCleanupPolicy.Observation(true, true, true, true, true, cursorEmpty, ownedCursor);
    }
}
