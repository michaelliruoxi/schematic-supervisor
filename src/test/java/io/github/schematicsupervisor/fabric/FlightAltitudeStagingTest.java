package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class FlightAltitudeStagingTest {
    @Test
    void crossesAboveTheBroadFarmBeforeDescendingOutsideItsNorthEdge() {
        BlockPosition start = at(8244, 201, -26922);
        BlockPosition depotApproach = at(8241, -61, -26981);
        Predicate<BlockPosition> receivedAir = point -> point.x() >= 8168 && point.x() <= 8318
                && point.y() >= -64 && point.y() <= 215 && point.z() >= -27020 && point.z() <= -26840
                // Head and feet cannot cross the farm's solid floor at Y=40.
                && !(point.y() >= 39 && point.y() <= 40 && point.x() >= 8185 && point.x() <= 8297
                && point.z() >= -26976 && point.z() <= -26864);
        assertTrue(FlightAltitudeStaging.required(start, depotApproach));
        FlightRoutePlanner horizontal = horizontalRoute(start, depotApproach, receivedAir);
        complete(horizontal);
        List<BlockPosition> firstLeg = horizontal.path();
        assertTrue(FlightAltitudeStaging.aboveDestination(firstLeg.getLast(), depotApproach));
        assertTrue(firstLeg.stream().allMatch(point -> FlightAltitudeStaging.withinAltitudeBand(start, point)));

        FlightRoutePlanner descent = new FlightRoutePlanner(firstLeg.getLast(), depotApproach,
                0, 384, 60_000, receivedAir, depotApproach::equals);
        complete(descent);
        List<BlockPosition> combined = new ArrayList<>(firstLeg);
        combined.addAll(descent.path().subList(1, descent.path().size()));
        assertTrue(combined.stream().allMatch(receivedAir));
        for (int index = 1; index < combined.size(); index++) {
            BlockPosition from = combined.get(index - 1);
            BlockPosition to = combined.get(index);
            assertTrue(Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y())
                    + Math.abs(from.z() - to.z()) == 1);
        }
        assertTrue(horizontal.discoveredNodes() + descent.discoveredNodes() < 10_000);
    }

    @Test
    void permitsASmallAltitudeDetourButCannotEscapeTheBoundedBand() {
        BlockPosition start = at(0, 201, 0);
        BlockPosition target = at(10, -63, 0);
        Predicate<BlockPosition> loaded = point -> point.x() >= 0 && point.x() <= 12
                && Math.abs(point.z()) <= 2;
        Predicate<BlockPosition> smallWall = point -> loaded.test(point)
                && (point.x() != 5 || point.y() > 205);
        FlightRoutePlanner detour = horizontalRoute(start, target, smallWall);
        complete(detour);
        assertTrue(detour.path().stream().allMatch(smallWall));
        assertTrue(detour.path().stream().anyMatch(point -> point.y() > 205));
        assertTrue(detour.path().stream().allMatch(point -> FlightAltitudeStaging.withinAltitudeBand(start, point)));

        FlightRoutePlanner sealed = horizontalRoute(start, target,
                point -> loaded.test(point) && point.x() != 5);
        complete(sealed);
        assertTrue(sealed.failed());
        assertFalse(sealed.complete());
        assertTrue(sealed.discoveredNodes() <= 60_000);
    }

    @Test
    void climbsOutBesideAWalledOffChestColumnInsteadOfSpendingEveryNode() {
        // The farm: planes every three blocks from Y=-63, a roof at Y=12, glowstone under each plane
        // including below the chest, and the depot wall on the roof two blocks inside its edge.
        WheatFarmModel farm = new WheatFarmModel(0, 0);
        BlockPosition start = farm.at(25, -35, 72);
        BlockPosition chest = farm.at(50, 14, 2);
        Predicate<BlockPosition> clear = farm::clearForBody;
        assertTrue(FlightAltitudeStaging.required(start, chest));

        FlightRoutePlanner exactOnly = horizontalRoute(start, chest, clear, 60_000);
        complete(exactOnly);
        assertTrue(exactOnly.failed(), "the column under the chest is closed at this altitude");
        assertTrue(exactOnly.detail().startsWith("Flight route search reached its bounded node limit."));

        FlightRoutePlanner staging = horizontalRoute(start, chest, clear, FlightAltitudeStaging.nodeBudget(60_000));
        complete(staging);
        assertTrue(staging.failed());
        List<BlockPosition> stage = FlightAltitudeStaging.fallbackStage(staging.pathToClosest(), chest);
        assertFalse(stage.isEmpty());
        assertTrue(FlightAltitudeStaging.nearDestination(stage.getLast(), chest));
        assertTrue(stage.stream().allMatch(clear));

        FlightRoutePlanner climb = new FlightRoutePlanner(stage.getLast(), chest, 3.0 + 1.62, 384,
                60_000 - staging.discoveredNodes(), clear,
                point -> point.y() >= 13 && withinReach(point, chest));
        complete(climb);
        assertTrue(climb.complete(), climb.detail());
        assertTrue(climb.path().stream().allMatch(clear));
        assertTrue(climb.path().stream().anyMatch(point -> point.z() < farm.at(0, 0, 0).z()),
                "it leaves by the farm's side");
    }

    @Test
    void fallbackStagesOnlyBesideTheDestinationColumn() {
        BlockPosition target = at(10, 50, 10);
        List<BlockPosition> near = List.of(at(0, 0, 10), at(7, 3, 10));
        List<BlockPosition> far = List.of(at(0, 0, 10), at(5, 0, 10));
        assertEquals(near, FlightAltitudeStaging.fallbackStage(near, target));
        assertTrue(FlightAltitudeStaging.fallbackStage(far, target).isEmpty());
        assertTrue(FlightAltitudeStaging.fallbackStage(List.of(at(10, 0, 10)), target).isEmpty(),
                "a search that never left its start cell has nothing to follow");
        assertTrue(FlightAltitudeStaging.fallbackStage(List.of(), target).isEmpty());
        assertTrue(FlightAltitudeStaging.nearDestination(at(14, -60, 10), target));
        assertFalse(FlightAltitudeStaging.nearDestination(at(13, 50, 13), target));
    }

    @Test
    void stagingKeepsTwoThirdsOfTheRouteNodesForLaterLegs() {
        assertEquals(20_000, FlightAltitudeStaging.nodeBudget(60_000));
        assertEquals(1, FlightAltitudeStaging.nodeBudget(2));
        assertThrows(IllegalArgumentException.class, () -> FlightAltitudeStaging.nodeBudget(0));
    }

    @Test
    void avoidsUnnecessaryStagingForNearbyOrNearlyVerticalRoutes() {
        assertFalse(FlightAltitudeStaging.required(at(0, 20, 0), at(30, 0, 0)));
        assertFalse(FlightAltitudeStaging.required(at(0, 201, 0), at(3, -63, 0)));
        assertTrue(FlightAltitudeStaging.required(at(0, 32, 0), at(8, 0, 0)));
    }

    private static FlightRoutePlanner horizontalRoute(BlockPosition start, BlockPosition target,
                                                       Predicate<BlockPosition> clear) {
        return horizontalRoute(start, target, clear, 60_000);
    }

    private static FlightRoutePlanner horizontalRoute(BlockPosition start, BlockPosition target,
                                                       Predicate<BlockPosition> clear, int nodes) {
        BlockPosition horizontalTarget = at(target.x(), start.y(), target.z());
        return new FlightRoutePlanner(start, horizontalTarget, 8, 384, nodes,
                point -> FlightAltitudeStaging.withinAltitudeBand(start, point) && clear.test(point),
                point -> FlightAltitudeStaging.aboveDestination(point, target));
    }

    private static boolean withinReach(BlockPosition feet, BlockPosition block) {
        double eyeX = feet.x() + 0.5;
        double eyeY = feet.y() + 0.1 + 1.62;
        double eyeZ = feet.z() + 0.5;
        double x = Math.clamp(eyeX, block.x(), block.x() + 1.0) - eyeX;
        double y = Math.clamp(eyeY, block.y(), block.y() + 1.0) - eyeY;
        double z = Math.clamp(eyeZ, block.z(), block.z() + 1.0) - eyeZ;
        return x * x + y * y + z * z <= 3.0 * 3.0;
    }

    private static void complete(FlightRoutePlanner planner) {
        for (int tick = 0; tick < 1000 && !planner.complete() && !planner.failed(); tick++) { planner.tick(128); }
        assertTrue(planner.complete() || planner.failed(), planner.detail());
    }

    private static BlockPosition at(int x, int y, int z) { return new BlockPosition(x, y, z); }
}
