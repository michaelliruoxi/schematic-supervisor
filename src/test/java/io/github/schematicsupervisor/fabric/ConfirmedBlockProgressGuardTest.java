package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ConfirmedBlockProgressGuardTest {
    private static final int APPROACH_TICK_LIMIT = 3_600;

    @Test
    void zeroAndNegativeLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedBlockProgressGuard(0));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedBlockProgressGuard(-1));
    }

    @Test
    void unconfirmedMovementCannotExtendTheHardDeadline() {
        ConfirmedBlockProgressGuard guard = new ConfirmedBlockProgressGuard(3);
        guard.beginOrder();

        assertFalse(guard.tick(false));
        assertFalse(guard.tick(false));
        assertTrue(guard.tick(false));
        assertTrue(guard.tick(false));
        assertEquals(3, guard.ticksWithoutConfirmation());
    }

    @Test
    void onlyConfirmedBlockProgressResetsTheDeadline() {
        ConfirmedBlockProgressGuard guard = new ConfirmedBlockProgressGuard(3);
        guard.beginOrder();
        assertFalse(guard.tick(false));
        assertFalse(guard.tick(false));

        assertFalse(guard.tick(true));
        assertEquals(0, guard.ticksWithoutConfirmation());
        assertFalse(guard.tick(false));
        assertFalse(guard.tick(false));
        assertTrue(guard.tick(false));
    }

    @Test
    void beginningANewOrderClearsAnExpiredGuard() {
        ConfirmedBlockProgressGuard guard = new ConfirmedBlockProgressGuard(1);
        assertTrue(guard.tick(false));

        guard.beginOrder();

        assertEquals(0, guard.ticksWithoutConfirmation());
        assertFalse(guard.tick(true));
    }

    @Test
    void repeatedReceivedAndUnreceivedReplansCannotRenewTheAggregateApproachBudget() {
        ConfirmedBlockProgressGuard approach = new ConfirmedBlockProgressGuard(APPROACH_TICK_LIMIT);
        approach.beginOrder();
        FlightRouteAttempt route = null;
        int routesStarted = 0;

        for (int activeTick = 1; activeTick <= APPROACH_TICK_LIMIT; activeTick++) {
            // Each brief receipt cycle replaces the route with a chunk approach,
            // then rescans and starts another interaction route for the same target.
            if ((activeTick - 1) % 2 == 0) {
                route = new FlightRouteAttempt(60_000, APPROACH_TICK_LIMIT, 4);
                routesStarted++;
            }
            assertTrue(route.tick());
            route.accountNodes(8);
            assertTrue(route.durationAvailable());
            assertEquals(activeTick == APPROACH_TICK_LIMIT, approach.tick(false),
                    "Route replacement must not renew the aggregate budget at tick " + activeTick);
        }

        assertEquals(1_800, routesStarted);
        assertEquals(APPROACH_TICK_LIMIT, approach.ticksWithoutConfirmation());
        assertTrue(approach.tick(false));
        assertEquals(APPROACH_TICK_LIMIT, approach.ticksWithoutConfirmation());
    }

    @Test
    void pausedScreenAndRepairWaitsPreserveTheRemainingApproachAllowance() {
        ConfirmedBlockProgressGuard approach = new ConfirmedBlockProgressGuard(4);
        approach.beginOrder();
        assertFalse(approach.tick(false));
        assertFalse(approach.tick(false));

        // The executor withholds approach ticks during these waits. Creating or
        // retaining navigation work must neither charge nor replenish that budget.
        FlightRouteAttempt waitingRoute = new FlightRouteAttempt(60_000, APPROACH_TICK_LIMIT, 4);
        waitingRoute.accountNodes(40);
        assertEquals(2, approach.ticksWithoutConfirmation());
        assertTrue(waitingRoute.tick());
        assertFalse(approach.tick(false));
        assertTrue(waitingRoute.tick());
        assertTrue(approach.tick(false));
        assertEquals(4, approach.ticksWithoutConfirmation());
    }

    @Test
    void confirmedBlockReceiptRenewsAnAlmostExpiredApproachBudget() {
        ConfirmedBlockProgressGuard approach = new ConfirmedBlockProgressGuard(APPROACH_TICK_LIMIT);
        approach.beginOrder();
        for (int tick = 1; tick < APPROACH_TICK_LIMIT; tick++) {
            assertFalse(approach.tick(false));
        }

        assertFalse(approach.tick(true));
        assertEquals(0, approach.ticksWithoutConfirmation());
        for (int tick = 1; tick < APPROACH_TICK_LIMIT; tick++) {
            assertFalse(approach.tick(false));
        }
        assertTrue(approach.tick(false));
        assertEquals(APPROACH_TICK_LIMIT, approach.ticksWithoutConfirmation());
    }

    @Test
    void beginningANewOrderRenewsTheFullApproachBudget() {
        ConfirmedBlockProgressGuard approach = new ConfirmedBlockProgressGuard(APPROACH_TICK_LIMIT);
        for (int tick = 1; tick < APPROACH_TICK_LIMIT; tick++) {
            assertFalse(approach.tick(false));
        }
        assertTrue(approach.tick(false));

        approach.beginOrder();

        assertEquals(0, approach.ticksWithoutConfirmation());
        for (int tick = 1; tick < APPROACH_TICK_LIMIT; tick++) {
            assertFalse(approach.tick(false));
        }
        assertTrue(approach.tick(false));
    }
}
