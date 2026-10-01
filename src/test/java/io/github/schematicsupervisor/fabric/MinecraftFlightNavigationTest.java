package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

class MinecraftFlightNavigationTest {
    @Test
    void cruiseStepReachesAThreeBlockLegInSixTicksWithoutOvershoot() {
        Vec3d current = new Vec3d(-18.5, -61.9, -7.5);
        Vec3d destination = current.add(3, 0, 0);
        for (int tick = 0; tick < 6; tick++) {
            Vec3d velocity = MinecraftFlightNavigation.nextVelocity(current, destination);
            assertTrue(velocity.length() <= MinecraftFlightNavigation.HORIZONTAL_SPEED_PER_TICK + 1.0e-9);
            assertTrue(velocity.length() <= current.distanceTo(destination) + 1.0e-9);
            Vec3d next = current.add(velocity);
            Box swept = MinecraftFlightNavigation.sweptBody(current, next);
            assertTrue(swept.minY > -62 && swept.maxY < -60);
            current = next;
        }
        assertEquals(0, current.distanceTo(destination), 1.0e-9);
        assertEquals(0, MinecraftFlightNavigation.nextVelocity(current, destination).length(), 1.0e-9);
    }

    @Test
    void cruiseStepsStayWithinVanillaHorizontalAndVerticalFlightSpeeds() {
        Vec3d current = new Vec3d(-4.5, -30.9, -18.5);
        for (Vec3d offset : new Vec3d[]{new Vec3d(3, 0, 3), new Vec3d(0, 3, 0),
                new Vec3d(-3, -3, -3), new Vec3d(0.2, -2, 0)}) {
            Vec3d velocity = MinecraftFlightNavigation.nextVelocity(current, current.add(offset));
            double horizontal = Math.hypot(velocity.x, velocity.z);
            assertTrue(horizontal <= MinecraftFlightNavigation.HORIZONTAL_SPEED_PER_TICK + 1.0e-9);
            assertTrue(Math.abs(velocity.y) <= MinecraftFlightNavigation.VERTICAL_SPEED_PER_TICK + 1.0e-9);
            // The tighter limit is reached exactly, and the step stays on the segment to the waypoint.
            assertEquals(1.0, Math.max(horizontal / MinecraftFlightNavigation.HORIZONTAL_SPEED_PER_TICK,
                    Math.abs(velocity.y) / MinecraftFlightNavigation.VERTICAL_SPEED_PER_TICK), 1.0e-9);
            assertEquals(0, velocity.normalize().subtract(offset.normalize()).length(), 1.0e-9);
        }
    }

    @Test
    void closeApproachSlowsToRemainingDistanceBeforeTheNextCollisionCheck() {
        Vec3d current = new Vec3d(0.5, 1.1, 0.5);
        Vec3d destination = current.add(0.04, 0.03, -0.02);
        Vec3d velocity = MinecraftFlightNavigation.nextVelocity(current, destination);
        assertEquals(0, current.add(velocity).distanceTo(destination), 1.0e-9);
        Box changedObstacle = new Box(0.82, 1.2, 0.45, 1.2, 2.5, 0.55);
        assertTrue(MinecraftFlightNavigation.sweptBody(current, current.add(velocity)).intersects(changedObstacle));
    }

    @Test
    void releaseRecognizesWhatVanillaFlightDragLeavesOfTheCommand() {
        Vec3d descent = new Vec3d(0, -MinecraftFlightNavigation.VERTICAL_SPEED_PER_TICK, 0);
        Vec3d afterOneTick = VanillaFlight.drag(descent);
        assertEquals(-0.225, afterOneTick.y, 1.0e-9);
        // Farther than 0.07 from the command, which the release used to require before zeroing it.
        assertTrue(afterOneTick.distanceTo(descent) > 0.07);
        assertTrue(MinecraftFlightNavigation.remainderOfCommand(afterOneTick, descent));
        assertTrue(MinecraftFlightNavigation.remainderOfCommand(VanillaFlight.drag(VanillaFlight.drag(afterOneTick)), descent));
        assertTrue(MinecraftFlightNavigation.remainderOfCommand(descent, descent));
        Vec3d diagonal = MinecraftFlightNavigation.nextVelocity(Vec3d.ZERO, new Vec3d(2, -3, 1));
        assertTrue(MinecraftFlightNavigation.remainderOfCommand(VanillaFlight.drag(diagonal), diagonal));
        // A collision stops one axis and keeps the others.
        assertTrue(MinecraftFlightNavigation.remainderOfCommand(
                new Vec3d(0.91 * diagonal.x, 0, 0.91 * diagonal.z), diagonal));
    }

    @Test
    void releaseKeepsImpulsesThatAreNotLeftFromTheCommand() {
        Vec3d descent = new Vec3d(0, -MinecraftFlightNavigation.VERTICAL_SPEED_PER_TICK, 0);
        assertFalse(MinecraftFlightNavigation.remainderOfCommand(new Vec3d(0, 0.2, 0), descent));
        assertFalse(MinecraftFlightNavigation.remainderOfCommand(new Vec3d(0.3, -0.2, 0), descent));
        assertFalse(MinecraftFlightNavigation.remainderOfCommand(new Vec3d(0, -0.5, 0), descent));
        // The allowance around the command itself still applies.
        assertTrue(MinecraftFlightNavigation.remainderOfCommand(new Vec3d(0.05, -0.4, 0), descent));
    }

    @Test
    void descentToTheHoverCellAboveAFloorNeverLandsFromAnyHeight() {
        // A blocked descent column ends 0.1 above the roof; touching the roof switches flight off.
        Vec3d hover = MinecraftFlightNavigation.center(new BlockPosition(3, 13, 2));
        double roofTop = hover.y - 0.1;
        for (int height = 1; height <= 24; height++) {
            VanillaFlight.State state = new VanillaFlight.State(hover.add(0, height, 0), Vec3d.ZERO, true);
            Vec3d owned = null;
            boolean released = false;
            for (int tick = 0; tick < 200; tick++) {
                // Vanilla moves the player and applies flight drag; the mod ticks after that.
                state = VanillaFlight.tick(state, roofTop);
                assertTrue(state.flying(), "landed on the roof from " + height + " blocks");
                assertTrue(state.feet().y >= hover.y - 1.0e-9, "sank below the hover cell from " + height + " blocks");
                if (released) { continue; }
                Vec3d velocity;
                if (state.feet().squaredDistanceTo(hover) < 0.01) {
                    velocity = MinecraftFlightNavigation.remainderOfCommand(state.velocity(), owned)
                            ? Vec3d.ZERO : state.velocity();
                    released = true;
                } else {
                    velocity = MinecraftFlightNavigation.nextVelocity(state.feet(), hover);
                    owned = velocity;
                }
                state = new VanillaFlight.State(state.feet(), velocity, state.flying());
            }
            assertTrue(released);
            assertEquals(0, state.velocity().length(), 1.0e-9);
        }
    }

    @Test
    void theFlightModelLandsAPlayerWhoseReleaseKeepsTheOldCommand() {
        // The regression the release rule fixes: keeping 0.225 of a 0.375 descent coasts onto the roof.
        Vec3d hover = MinecraftFlightNavigation.center(new BlockPosition(3, 13, 2));
        VanillaFlight.State state = new VanillaFlight.State(hover,
                new Vec3d(0, -MinecraftFlightNavigation.VERTICAL_SPEED_PER_TICK * VanillaFlight.VERTICAL_DRAG, 0), true);
        for (int tick = 0; tick < 20 && state.flying(); tick++) {
            state = VanillaFlight.tick(state, hover.y - 0.1);
        }
        assertFalse(state.flying());
    }

    @Test
    void hoverWaypointsStayAboveFloorAndFitTwoBlockClearanceAtNegativeY() {
        Vec3d feet = MinecraftFlightNavigation.center(new BlockPosition(3, -62, -5));
        Box body = MinecraftFlightNavigation.body(feet);
        assertEquals(-61.9, body.minY, 0.00001);
        assertEquals(-60.1, body.maxY, 0.00001);
        assertEquals(0.6, body.getLengthX(), 0.00001);
        assertEquals(0.6, body.getLengthZ(), 0.00001);
        assertTrue(body.minY > -62);
        assertTrue(body.maxY < -60);
    }

    @Test
    void placementGoalCannotLeavePlayersHeadInsidePendingGlowstone() {
        BlockPos glowstone = new BlockPos(0, 2, 0);
        assertFalse(MinecraftFlightNavigation.clearsReservedPlacement(
                new Vec3d(0.5, 1.1, 0.5), glowstone));
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(
                new Vec3d(1.5, 1.1, 0.5), glowstone));
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(
                new Vec3d(0.5, 3.1, 0.5), glowstone));
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(
                new Vec3d(0.5, 1.1, 0.5), null));
    }

    @Test
    void safeReturnMeasuresFeetDistanceInsteadOfInteractionReach() {
        BlockPos saved = new BlockPos(10, -62, -20);
        assertTrue(MinecraftFlightNavigation.withinFeetRadius(
                new Vec3d(10.5, -61.9, -19.5), saved, 1));
        assertTrue(MinecraftFlightNavigation.withinFeetRadius(
                new Vec3d(11.49, -61.9, -19.5), saved, 1));
        assertFalse(MinecraftFlightNavigation.withinFeetRadius(
                new Vec3d(13.5, -61.9, -19.5), saved, 1));
        // Being an eye-height below the saved position is not a successful feet return.
        assertFalse(MinecraftFlightNavigation.withinFeetRadius(
                new Vec3d(10.5, -63.52, -19.5), saved, 1));
        assertFalse(MinecraftFlightNavigation.withinFeetRadius(
                new Vec3d(10.5, -60.89, -19.5), saved, 1));
    }

    @Test
    void sweptBodyIncludesEntireSegmentBetweenWaypoints() {
        Box swept = MinecraftFlightNavigation.sweptBody(
                new Vec3d(0.5, 1.1, 0.5), new Vec3d(1.5, 2.1, 1.5));
        assertEquals(0.2, swept.minX, 0.00001);
        assertEquals(1.8, swept.maxX, 0.00001);
        assertEquals(1.1, swept.minY, 0.00001);
        assertEquals(3.9, swept.maxY, 0.00001);
        assertTrue(swept.intersects(new Box(0.85, 1.2, 0.85, 1.15, 1.3, 1.15)));
    }
}
