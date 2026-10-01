package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

class FlightInteractionRouteRetentionTest {
    // The farm's north edge is at z = 0; its first chunk row starts there.
    private static final BlockPosition START = new BlockPosition(56, -62, 0);
    private static final BlockPosition TARGET = new BlockPosition(56, -57, 96);
    private static final BlockPos TARGET_BLOCK = new BlockPos(TARGET.x(), TARGET.y(), TARGET.z());
    // Reproducible route geometry, not a reconstruction of every live world block.
    private static final List<Box> FLOORS = List.of(
            new Box(0, -60, 0, 112, -59, 112),
            new Box(0, -57, 0, 112, -56, 112));

    @Test void onlyActiveInteractionRoutesAreRetainedForAnUnreceivedTarget() {
        for (boolean active : List.of(false, true)) {
            for (boolean feetGoal : List.of(false, true)) {
                for (boolean chunkReceiptGoal : List.of(false, true)) {
                    boolean retained = MinecraftFlightNavigation.activeInteractionRoute(
                            active, feetGoal, chunkReceiptGoal);
                    assertEquals(active && !feetGoal && !chunkReceiptGoal, retained);
                    assertEquals(retained ? StemClearingSweep.ApproachAction.FOLLOW_PLANNED_ROUTE
                                    : StemClearingSweep.ApproachAction.RECEIVE_TARGET_CHUNK,
                            StemClearingSweep.approachAction(false, retained));
                    assertEquals(StemClearingSweep.ApproachAction.INSPECT_TARGET,
                            StemClearingSweep.approachAction(true, retained));
                }
            }
        }
    }

    @Test void northEdgeAscentRetainsSearchAndMovementAcrossDistantTargetReceiptChanges() {
        var attempt = new FlightRouteAttempt(60_000, 3_600, 4);
        boolean[] targetReceived = {true};
        var planner = planner(attempt, () -> targetReceived[0], box -> true);
        attempt.accountNodes(planner.discoveredNodes());
        int unreceivedSearchTicks = 0;
        for (int tick = 0; tick < 100 && !planner.complete() && !planner.failed(); tick++) {
            targetReceived[0] = tick != 1 && tick != 2;
            if (!targetReceived[0]) {
                unreceivedSearchTicks++;
                assertEquals(StemClearingSweep.ApproachAction.FOLLOW_PLANNED_ROUTE,
                        StemClearingSweep.approachAction(false,
                                MinecraftFlightNavigation.activeInteractionRoute(true, false, false)));
            }
            assertTrue(attempt.tick());
            int before = planner.discoveredNodes();
            planner.tick(16);
            attempt.accountNodes(planner.discoveredNodes() - before);
        }
        assertTrue(planner.complete(), planner.detail());
        assertEquals(2, unreceivedSearchTicks, "The same search survives temporary target unreceipt");
        assertEquals(planner.discoveredNodes(), attempt.nodesUsed(), "One original planner is charged");
        int plannedNodes = attempt.nodesUsed();
        int plannedTicks = attempt.activeTicks();
        int outsideSteps = 0;
        boolean returnedInside = false;
        var route = planner.path();
        for (int waypoint = 0; waypoint < route.size(); waypoint++) {
            BlockPosition point = route.get(waypoint);
            Vec3d feet = MinecraftFlightNavigation.center(point);
            boolean received = ChunkWindow.around(TARGET, 6).received(point);
            var action = StemClearingSweep.approachAction(received,
                    MinecraftFlightNavigation.activeInteractionRoute(true, false, false));
            if (!received) {
                outsideSteps++;
                assertEquals(-1, point.z());
                assertEquals(StemClearingSweep.ApproachAction.FOLLOW_PLANNED_ROUTE, action);
                assertFalse(arrival(feet, false));
            } else {
                assertEquals(StemClearingSweep.ApproachAction.INSPECT_TARGET, action);
                if (outsideSteps > 0) { returnedInside = true; }
            }
            assertTrue(attempt.tick());
            assertEquals(plannedNodes, attempt.nodesUsed(), "Receipt changes cannot mint a search allowance");
            assertTrue(clearBody(MinecraftFlightNavigation.body(feet)));
            if (waypoint > 0) {
                BlockPosition previous = route.get(waypoint - 1);
                Box segment = MinecraftFlightNavigation.sweptBody(
                        MinecraftFlightNavigation.center(previous), feet);
                assertTrue(clearBody(segment), "The ascent cannot intersect either solid floor plane");
                assertTrue(receivedNear(segment, previous),
                        "Every movement segment stays received even while the distant target is not");
            }
        }
        assertTrue(outsideSteps >= 4, "The route must ascend outside the north edge");
        assertTrue(returnedInside, "The original route returns to the target's received window");
        Vec3d arrival = MinecraftFlightNavigation.center(route.getLast());
        assertTrue(arrival(arrival, true), "Arrival still requires the exact target ray");
        assertFalse(MinecraftFlightNavigation.interactionArrival(arrival, arrival.add(0, 1.62, 0),
                TARGET_BLOCK, 3.95, TARGET_BLOCK, false,
                box -> { throw new AssertionError("Unreceived target cannot inspect arrival space"); },
                () -> { throw new AssertionError("Unreceived target cannot request an interaction ray"); }));
        assertEquals(plannedTicks + route.size(), attempt.activeTicks());
        while (attempt.durationAvailable()) { assertTrue(attempt.tick()); }
        assertFalse(attempt.tick(), "Retaining the route must retain its original finite duration");
    }

    @Test void retainingAnInteractionRouteDoesNotPermitAnUnreceivedNorthEdgeSegment() {
        var attempt = new FlightRouteAttempt(60_000, 3_600, 4);
        var planner = planner(attempt, () -> true, box -> box.minZ >= 0);
        attempt.accountNodes(planner.discoveredNodes());
        for (int tick = 0; tick < 100 && !planner.complete() && !planner.failed(); tick++) {
            assertTrue(attempt.tick());
            int before = planner.discoveredNodes();
            planner.tick(16);
            attempt.accountNodes(planner.discoveredNodes() - before);
        }
        assertTrue(planner.failed(), "Unreceived escape space cannot become a flight route");
        assertFalse(planner.complete());
    }

    private static FlightRoutePlanner planner(FlightRouteAttempt attempt, BooleanSupplier targetReceived,
                                               Predicate<Box> received) {
        return new FlightRoutePlanner(START, TARGET, 4.15 + 1.62, 384, attempt.remainingNodes(),
                point -> point.x() == 56 && point.z() >= -1 && point.z() <= 96
                        && point.y() >= -62 && point.y() <= -56
                        && clearBody(MinecraftFlightNavigation.body(MinecraftFlightNavigation.center(point)))
                        && received.test(MinecraftFlightNavigation.body(MinecraftFlightNavigation.center(point))),
                point -> arrival(MinecraftFlightNavigation.center(point), targetReceived.getAsBoolean()),
                (from, to) -> {
                    Box segment = MinecraftFlightNavigation.sweptBody(
                            MinecraftFlightNavigation.center(from), MinecraftFlightNavigation.center(to));
                    return clearBody(segment) && received.test(segment);
                });
    }

    private static boolean clearBody(Box body) {
        return FLOORS.stream().noneMatch(body::intersects);
    }

    private static boolean receivedNear(Box box, BlockPosition player) {
        int minChunk = Math.floorDiv((int) Math.floor(box.minZ), 16);
        int maxChunk = Math.floorDiv((int) Math.floor(box.maxZ - 1.0e-7), 16);
        int playerChunk = Math.floorDiv(player.z(), 16);
        return minChunk >= playerChunk - 6 && maxChunk <= playerChunk + 6;
    }

    private static boolean arrival(Vec3d feet, boolean targetReceived) {
        Vec3d eye = feet.add(0, 1.62, 0);
        return MinecraftFlightNavigation.interactionArrival(feet, eye, TARGET_BLOCK, 3.95, TARGET_BLOCK,
                targetReceived, FlightInteractionRouteRetentionTest::clearBody,
                () -> ExactInteractionRay.trace(eye, TARGET_BLOCK, null, 3.95,
                        (x, z) -> targetReceived, FlightInteractionRouteRetentionTest::floorRay).accepted());
    }

    private static BlockHitResult floorRay(Vec3d from, Vec3d to) {
        BlockHitResult nearest = null;
        for (Box floor : FLOORS) {
            BlockHitResult hit = VoxelShapes.cuboid(floor).raycast(from, to, BlockPos.ORIGIN);
            if (hit != null && (nearest == null
                    || from.squaredDistanceTo(hit.getPos()) < from.squaredDistanceTo(nearest.getPos()))) {
                Vec3d inside = hit.getPos().add(to.subtract(from).normalize().multiply(1.0e-6));
                nearest = new BlockHitResult(hit.getPos(), hit.getSide(), BlockPos.ofFloored(inside), false);
            }
        }
        return nearest == null ? BlockHitResult.createMissed(to, Direction.UP, TARGET_BLOCK) : nearest;
    }
}
