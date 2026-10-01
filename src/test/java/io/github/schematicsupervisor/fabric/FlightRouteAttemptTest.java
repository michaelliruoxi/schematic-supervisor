package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import org.junit.jupiter.api.Test;

class FlightRouteAttemptTest {
    @Test
    void aRejectedArrivalFindsAnotherReceivedCollisionFreeCandidate() {
        FlightRouteAttempt attempt = new FlightRouteAttempt(60_000, 3_600, 4);
        BlockPosition start = new BlockPosition(0, 1, 0);
        BlockPosition target = new BlockPosition(3, 0, 0);
        BlockPosition firstArrival = new BlockPosition(2, 1, 0);
        FlightRoutePlanner first = route(start, target, attempt);
        finish(first, attempt);
        assertTrue(first.complete());
        assertEquals(firstArrival, first.path().getLast());
        int firstCost = attempt.nodesUsed();
        assertTrue(attempt.excludeArrival(firstArrival));
        FlightRoutePlanner alternative = route(firstArrival, target, attempt);
        finish(alternative, attempt);
        assertTrue(alternative.complete());
        assertEquals(new BlockPosition(3, 1, 0), alternative.path().getLast());
        assertTrue(attempt.nodesUsed() > firstCost);
        assertTrue(alternative.path().stream().allMatch(FlightRouteAttemptTest::receivedClearCell));
    }

    @Test
    void onlyFourDistinctArrivalCellsMayBeExcludedAndDuplicatesDoNotRestartTheBudget() {
        FlightRouteAttempt attempt = new FlightRouteAttempt(100, 100, 4);
        for (int x = 0; x < 4; x++) {
            BlockPosition failed = new BlockPosition(x, 1, 0);
            assertTrue(attempt.excludeArrival(failed));
            assertFalse(attempt.permitsArrival(failed));
            assertFalse(attempt.excludeArrival(failed));
        }
        assertFalse(attempt.excludeArrival(new BlockPosition(4, 1, 0)));
        assertEquals(4, attempt.excludedCount());
    }

    @Test
    void replannedSearchesConsumeOneCumulativeNodeAllowance() {
        FlightRouteAttempt attempt = new FlightRouteAttempt(10, 100, 4);
        attempt.accountNodes(6);
        assertTrue(attempt.excludeArrival(new BlockPosition(0, 1, 0)));
        FlightRoutePlanner continuation = route(new BlockPosition(0, 1, 0),
                new BlockPosition(20, 0, 0), attempt);
        finish(continuation, attempt);
        assertTrue(continuation.failed());
        assertEquals(10, attempt.nodesUsed());
        assertEquals(0, attempt.remainingNodes());
        assertThrows(IllegalArgumentException.class, () -> attempt.accountNodes(1));
    }

    @Test
    void alternateArrivalsDoNotResetTheOriginalActiveDuration() {
        FlightRouteAttempt attempt = new FlightRouteAttempt(100, 3, 4);
        assertTrue(attempt.tick());
        assertTrue(attempt.excludeArrival(new BlockPosition(0, 1, 0)));
        assertTrue(attempt.tick());
        assertTrue(attempt.excludeArrival(new BlockPosition(1, 1, 0)));
        assertTrue(attempt.tick());
        assertFalse(attempt.durationAvailable());
        assertFalse(attempt.tick());
        assertEquals(4, attempt.activeTicks());
    }

    @Test
    void excludedCellsRemainTraversableButCanNeverEndAnInteractionRoute() {
        FlightRouteAttempt attempt = new FlightRouteAttempt(100, 100, 4);
        BlockPosition excluded = new BlockPosition(2, 1, 0);
        assertTrue(attempt.excludeArrival(excluded));
        FlightRoutePlanner planned = route(new BlockPosition(0, 1, 0),
                new BlockPosition(3, 0, 0), attempt);
        finish(planned, attempt);
        assertTrue(planned.complete());
        assertTrue(planned.path().contains(excluded));
        assertEquals(new BlockPosition(3, 1, 0), planned.path().getLast());
    }

    private static FlightRoutePlanner route(BlockPosition start, BlockPosition target,
                                            FlightRouteAttempt attempt) {
        return new FlightRoutePlanner(start, target, 2, 384, attempt.remainingNodes(),
                FlightRouteAttemptTest::receivedClearCell,
                p -> p.x() >= target.x() - 1 && attempt.permitsArrival(p),
                (from, to) -> receivedClearCell(from) && receivedClearCell(to));
    }

    private static boolean receivedClearCell(BlockPosition position) {
        return position.y() == 1 && position.z() == 0 && position.x() >= 0 && position.x() <= 8;
    }

    private static void finish(FlightRoutePlanner planner, FlightRouteAttempt attempt) {
        attempt.accountNodes(planner.discoveredNodes());
        for (int tick = 0; tick < 100 && !planner.complete() && !planner.failed(); tick++) {
            int before = planner.discoveredNodes();
            planner.tick(8);
            attempt.accountNodes(planner.discoveredNodes() - before);
        }
        assertTrue(planner.complete() || planner.failed());
    }
}
