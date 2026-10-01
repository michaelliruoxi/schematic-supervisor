package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class FlightStreamingProgressTest {
    @Test
    void approachesAcrossSeveralReceivedWindowsWithoutEnteringUnknownCells() {
        BlockPosition position = at(0, 201, 0);
        BlockPosition target = at(0, 201, -60);
        int legs = 0;
        while (Math.abs(Math.floorDiv(position.z(), 16) - Math.floorDiv(target.z(), 16)) > 1) {
            int receivedCenter = Math.floorDiv(position.z(), 16);
            Predicate<BlockPosition> received = point -> Math.abs(Math.floorDiv(point.z(), 16)
                    - receivedCenter) <= 1 && Math.abs(point.x()) <= 20;
            List<BlockPosition> path = stagingPath(position, target, received, received);
            assertTrue(path.stream().allMatch(received));
            assertTrue(path.stream().allMatch(point -> point.y() == 201));
            BlockPosition next = path.getLast();
            assertTrue(FlightStreamingProgress.horizontalDistance(next, target)
                    < FlightStreamingProgress.horizontalDistance(position, target));
            position = next;
            assertTrue(++legs < 10, "received-window staging must make bounded forward progress");
        }
        assertTrue(legs >= 2);
        assertTrue(Math.abs(Math.floorDiv(position.z(), 16) - Math.floorDiv(target.z(), 16)) <= 1);
    }

    @Test
    void stopsAtTheReceivedFrontierBeforeAnUnknownNextCell() {
        BlockPosition start = at(0, 201, 0);
        BlockPosition target = at(60, 201, 0);
        Predicate<BlockPosition> received = point -> point.x() >= -8 && point.x() <= 7
                && point.y() == 201 && Math.abs(point.z()) <= 3;
        List<BlockPosition> path = stagingPath(start, target, received, received);
        assertEquals(7, path.getLast().x());
        assertTrue(path.stream().allMatch(received));
        assertTrue(FlightStreamingProgress.atFrontier(path.getLast(), target, received));
        assertFalse(received.test(at(8, 201, 0)));
    }

    @Test
    void routesAroundAReceivedWallBeforeContinuingTowardTheUnreceivedTarget() {
        BlockPosition start = at(0, 201, 0);
        BlockPosition target = at(60, 201, 0);
        Predicate<BlockPosition> received = point -> point.x() >= -3 && point.x() <= 20
                && point.y() == 201 && Math.abs(point.z()) <= 3;
        Predicate<BlockPosition> clear = point -> received.test(point)
                && (point.x() != 5 || point.z() == 2);
        List<BlockPosition> path = stagingPath(start, target, received, clear);
        assertTrue(path.contains(at(5, 201, 2)));
        assertTrue(path.stream().allMatch(clear));
    }

    @Test
    void waitsOnlyWhenEveryForwardDirectionIsUnreceived() {
        BlockPosition position = at(-16, 20, -16);
        BlockPosition target = at(-60, 20, -60);
        Predicate<BlockPosition> oneDirectionAvailable = point -> point.x() == -16;
        assertFalse(FlightStreamingProgress.atFrontier(position, target, oneDirectionAvailable));
        assertTrue(FlightStreamingProgress.atFrontier(position, target, point -> point.equals(position)));
    }

    @Test
    void chunkReceiptResumesWaitingButMissingDataHasABoundedDeadline() {
        FlightStreamingProgress wait = new FlightStreamingProgress(3);
        assertEquals(FlightStreamingProgress.WaitState.WAITING, wait.await(false));
        assertEquals(FlightStreamingProgress.WaitState.WAITING, wait.await(false));
        assertEquals(FlightStreamingProgress.WaitState.READY, wait.await(true));
        assertEquals(0, wait.waitTicks());
        for (int tick = 0; tick < 3; tick++) {
            assertEquals(FlightStreamingProgress.WaitState.WAITING, wait.await(false));
        }
        assertEquals(FlightStreamingProgress.WaitState.TIMED_OUT, wait.await(false));
        wait.reset();
        assertEquals(0, wait.waitTicks());
    }

    private static List<BlockPosition> stagingPath(BlockPosition start, BlockPosition target,
                                                   Predicate<BlockPosition> received,
                                                   Predicate<BlockPosition> clear) {
        FlightRoutePlanner planner = new FlightRoutePlanner(start, target, 0, 384, 60_000,
                clear, point -> FlightStreamingProgress.reachedStage(start, point, target, 12, received));
        for (int tick = 0; tick < 1000 && !planner.failed() && !planner.complete(); tick++) {
            planner.tick(128);
        }
        assertTrue(planner.complete(), planner.detail());
        assertTrue(planner.discoveredNodes() <= 60_000);
        return planner.path();
    }

    private static BlockPosition at(int x, int y, int z) { return new BlockPosition(x, y, z); }
}
