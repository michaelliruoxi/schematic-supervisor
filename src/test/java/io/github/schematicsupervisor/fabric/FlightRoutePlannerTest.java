package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class FlightRoutePlannerTest {
    @Test
    void findsRouteAroundWallInsteadOfCrossingIt() {
        BlockPosition start = at(0, 1, 0);
        BlockPosition target = at(8, 1, 0);
        Predicate<BlockPosition> free = point -> within(point, -1, 9, 0, 4, -5, 5)
                && !(point.x() == 3 && Math.abs(point.z()) <= 3);
        FlightRoutePlanner planner = route(start, target, free);
        complete(planner, 7);
        assertSafePath(planner.path(), start, target, free);
        assertTrue(planner.path().stream().anyMatch(point -> Math.abs(point.z()) >= 4));
    }

    @Test
    void descendsThroughFloorGapWithEnoughSpaceForHeadAndFeet() {
        BlockPosition start = at(0, 3, 0);
        BlockPosition target = at(0, -4, 0);
        Predicate<BlockPosition> free = point -> within(point, -4, 4, -5, 5, -4, 4)
                && (point.y() != 0 && point.y() != -1 || point.x() == 2 && point.z() == 0);
        FlightRoutePlanner planner = route(start, target, free);
        complete(planner, 4);
        assertSafePath(planner.path(), start, target, free);
        assertTrue(planner.path().contains(at(2, 0, 0)));
        assertTrue(planner.path().contains(at(2, -1, 0)));
    }

    @Test
    void handlesLongDescentIntoNegativeYWithPerTickExpansionBudget() {
        BlockPosition start = at(0, 49, 0);
        BlockPosition target = at(0, -62, 0);
        Predicate<BlockPosition> free = point -> within(point, -2, 2, -64, 64, -2, 2);
        FlightRoutePlanner planner = route(start, target, free);
        complete(planner, 3);
        assertEquals(112, planner.path().size());
        assertSafePath(planner.path(), start, target, free);
    }

    @Test
    void reachesDeepDepotFromElevatedHomeWithinTheExpandedDistanceBound() {
        BlockPosition home = at(8244, 201, -26921);
        BlockPosition depotApproach = at(8241, -62, -26981);
        Predicate<BlockPosition> loadedAir = point -> within(point,
                8236, 8248, -64, 210, -26988, -26915);
        FlightRoutePlanner previousLimit = new FlightRoutePlanner(home, depotApproach, 0,
                256, 60_000, loadedAir, depotApproach::equals);
        assertTrue(previousLimit.failed());
        assertTrue(previousLimit.detail().contains("route distance"));
        FlightRoutePlanner expanded = new FlightRoutePlanner(home, depotApproach, 0,
                MinecraftFlightNavigation.MAXIMUM_ROUTE_DISTANCE, 60_000,
                loadedAir, depotApproach::equals);
        complete(expanded, 128);
        assertSafePath(expanded.path(), home, depotApproach, loadedAir);
        assertTrue(expanded.discoveredNodes() <= 60_000);
        assertEquals(manhattan(home, depotApproach) + 1, expanded.path().size());
    }

    @Test
    void descendsAroundBroadPlatformWithoutExhaustingBoundedSearch() {
        BlockPosition home = at(8244, 201, -26922);
        BlockPosition depotApproach = at(8241, -62, -26981);
        Predicate<BlockPosition> loadedAir = point -> within(point,
                8161, 8321, -64, 210, -27061, -26821)
                // A full-width platform blocks both feet and head cells over the approach.
                && !(point.y() >= 99 && point.y() <= 100
                && point.x() >= 8185 && point.x() <= 8297
                && point.z() >= -26981 && point.z() <= -26869);
        // Use the same conservative reach-plus-eye allowance as live chest navigation.
        double reachAndEyeAllowance = 3.0 + 1.62;
        FlightRoutePlanner shortest = new FlightRoutePlanner(home, depotApproach, reachAndEyeAllowance,
                384, 60_000, loadedAir, depotApproach::equals, (from, to) -> true, 1.0);
        complete(shortest, 128);
        assertTrue(shortest.failed());
        assertTrue(shortest.detail().contains("node limit"));

        FlightRoutePlanner bounded = new FlightRoutePlanner(home, depotApproach, reachAndEyeAllowance,
                384, 60_000, loadedAir, depotApproach::equals);
        complete(bounded, 128);
        assertSafePath(bounded.path(), home, depotApproach, loadedAir);
        assertTrue(bounded.path().stream().anyMatch(point -> point.z() < -26981));
        assertTrue(bounded.discoveredNodes() < 10_000);
    }

    @Test
    void weightedSearchStillRejectsASealedPlatformWithinItsNodeBudget() {
        BlockPosition home = at(0, 20, 0);
        BlockPosition target = at(0, -20, 0);
        Predicate<BlockPosition> available = point -> within(point, -10, 10, -30, 30, -10, 10)
                && point.y() != 0 && point.y() != -1;
        FlightRoutePlanner planner = new FlightRoutePlanner(home, target, 0,
                384, 2_000, available, target::equals);
        complete(planner, 128);
        assertTrue(planner.failed());
        assertFalse(planner.complete());
        assertTrue(planner.detail().contains("node limit"));
        assertTrue(planner.detail().contains("discovered=2000/2000"));
    }

    @Test
    void reportsSearchProgressWhilePlanningAndAfterFailure() {
        FlightRoutePlanner planner = new FlightRoutePlanner(at(0, 0, 0), at(8, 0, 0), 0,
                16, 10, point -> true, point -> false);
        assertTrue(planner.detail().contains("expanded=0"));
        planner.tick(1);
        assertTrue(planner.detail().contains("expanded=1"));
        assertTrue(planner.detail().contains("nearest=(0,0,0)"));
        complete(planner, 1);
        assertTrue(planner.detail().contains("node limit"));
        assertTrue(planner.detail().contains("discovered=10/10"));
    }

    @Test
    void treatsUnloadedCellsAsUnavailableAndReportsNoRoute() {
        FlightRoutePlanner planner = route(at(0, 0, 0), at(7, 0, 0),
                point -> within(point, -2, 2, -2, 2, -2, 2));
        complete(planner, 8);
        assertTrue(planner.failed());
        assertFalse(planner.complete());
        assertTrue(planner.detail().contains("No loaded collision-free"));
        assertThrows(IllegalStateException.class, planner::path);
    }

    @Test
    void respectsNodeAndDistanceLimitsWithoutUnboundedSearch() {
        FlightRoutePlanner nodes = new FlightRoutePlanner(at(0, 0, 0), at(8, 0, 0), 0,
                16, 10, point -> true, point -> false);
        complete(nodes, 2);
        assertTrue(nodes.failed());
        assertTrue(nodes.detail().contains("node limit"));
        assertTrue(nodes.discoveredNodes() <= 10);
        FlightRoutePlanner distance = new FlightRoutePlanner(at(0, 0, 0), at(300, 0, 0), 3,
                256, 100, point -> true, point -> true);
        assertTrue(distance.failed());
        assertTrue(distance.detail().contains("route distance"));
    }

    @Test
    void choosesFreeInteractionCellBesideSolidTarget() {
        BlockPosition target = at(4, 0, 0);
        FlightRoutePlanner planner = new FlightRoutePlanner(at(0, 0, 0), target, 1,
                16, 1000, point -> !point.equals(target),
                point -> manhattan(point, target) <= 1);
        complete(planner, 4);
        assertTrue(planner.complete());
        assertEquals(1, manhattan(planner.path().getLast(), target));
        assertFalse(planner.path().contains(target));
    }

    @Test
    void avoidsThinCollisionBetweenOtherwiseFreeEndpoints() {
        BlockPosition start = at(0, 0, 0);
        BlockPosition target = at(2, 0, 0);
        Predicate<BlockPosition> free = point -> within(point, 0, 2, 0, 0, 0, 2);
        FlightRoutePlanner planner = new FlightRoutePlanner(start, target, 0, 16, 1000,
                free, target::equals, (from, to) -> !(from.z() == 0 && to.z() == 0
                && Math.min(from.x(), to.x()) == 0 && Math.max(from.x(), to.x()) == 1));
        complete(planner, 2);
        assertSafePath(planner.path(), start, target, free);
        assertTrue(planner.path().stream().anyMatch(point -> point.z() == 1));
    }

    @Test
    void alreadyAtGoalNeedsNoMovementAndBlockedStartFailsClosed() {
        BlockPosition position = at(-10, -63, -20);
        FlightRoutePlanner ready = route(position, position, point -> true);
        ready.tick(1);
        assertEquals(List.of(position), ready.path());
        FlightRoutePlanner blocked = route(position, at(-9, -63, -20), point -> false);
        assertTrue(blocked.failed());
        assertTrue(blocked.startObstructed());
        assertFalse(ready.startObstructed());
        assertTrue(blocked.detail().contains("starting flight cell"));
        assertThrows(IllegalArgumentException.class, () -> ready.tick(0));
    }

    @Test
    void failedSearchStillExposesItsRouteToTheNearestReachedCell() {
        BlockPosition start = at(0, 1, 0);
        BlockPosition target = at(8, 1, 0);
        // A solid block around the target: the search fails, but it still came close to it.
        Predicate<BlockPosition> free = point -> within(point, -2, 10, 0, 3, -3, 3)
                && !(point.x() >= 7 && point.x() <= 9 && Math.abs(point.z()) <= 1);
        FlightRoutePlanner planner = route(start, target, free);
        assertTrue(planner.pathToClosest().isEmpty(), "nothing is expanded before the first tick");
        complete(planner, 16);
        assertTrue(planner.failed());
        assertFalse(planner.startObstructed(), "an exhausted search is not a blocked start");

        List<BlockPosition> nearest = planner.pathToClosest();
        assertEquals(start, nearest.getFirst());
        // (6,1,0), (10,1,0) and (8,1,+-2) are the free cells two blocks from the sealed target.
        assertEquals(2, manhattan(nearest.getLast(), target));
        assertTrue(nearest.stream().allMatch(free));
        for (int index = 1; index < nearest.size(); index++) {
            assertEquals(1, manhattan(nearest.get(index - 1), nearest.get(index)));
        }
        assertTrue(route(start, target, point -> false).pathToClosest().isEmpty());
    }

    private static FlightRoutePlanner route(BlockPosition start, BlockPosition target,
                                            Predicate<BlockPosition> free) {
        return new FlightRoutePlanner(start, target, 0, 256, 10_000, free, target::equals);
    }

    private static void complete(FlightRoutePlanner planner, int budget) {
        for (int tick = 0; tick < 10_001 && !planner.complete() && !planner.failed(); tick++) {
            int before = planner.expandedNodes();
            planner.tick(budget);
            assertTrue(planner.expandedNodes() - before <= budget);
        }
        assertTrue(planner.complete() || planner.failed(), "bounded search did not settle");
    }

    private static void assertSafePath(List<BlockPosition> path, BlockPosition start,
                                       BlockPosition target, Predicate<BlockPosition> free) {
        assertEquals(start, path.getFirst());
        assertEquals(target, path.getLast());
        assertTrue(path.stream().allMatch(free));
        for (int index = 1; index < path.size(); index++) {
            assertEquals(1, manhattan(path.get(index - 1), path.get(index)));
        }
    }

    private static int manhattan(BlockPosition left, BlockPosition right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
                + Math.abs(left.z() - right.z());
    }

    private static boolean within(BlockPosition point, int minX, int maxX, int minY, int maxY,
                                   int minZ, int maxZ) {
        return point.x() >= minX && point.x() <= maxX && point.y() >= minY && point.y() <= maxY
                && point.z() >= minZ && point.z() <= maxZ;
    }

    private static BlockPosition at(int x, int y, int z) { return new BlockPosition(x, y, z); }
}
