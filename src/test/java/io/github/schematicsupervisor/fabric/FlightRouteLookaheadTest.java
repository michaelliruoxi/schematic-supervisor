package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

class FlightRouteLookaheadTest {
    @Test void clearShortcutAvoidsReturningToTheCurrentCellCenter() {
        var current = new Vec3d(0.9, 1.1, 0.5);
        var route = List.of(new Vec3d(0.5, 1.1, 0.5), new Vec3d(1.5, 1.1, 0.5),
                new Vec3d(2.5, 1.1, 0.5));
        int selected = FlightRouteLookahead.select(0, route.size(), i -> true);
        assertEquals(2, selected);
        double previousDistance = current.distanceTo(route.getFirst()) + 2;
        assertTrue(current.distanceTo(route.get(selected)) < previousDistance);
    }

    @Test void cornerObstacleCannotBeCutEvenWhenTheDestinationIsClear() {
        var current = new Vec3d(0.5, 1.1, 0.5);
        var route = List.of(current, new Vec3d(0.5, 1.1, 2.5), new Vec3d(2.5, 1.1, 2.5));
        var obstacle = new Box(1, 1, 1, 2, 3, 2);
        assertEquals(1, FlightRouteLookahead.select(0, route.size(),
                i -> !MinecraftFlightNavigation.sweptBody(current, route.get(i)).intersects(obstacle)));
    }

    @Test void shortcutStopsBeforeAnUnreceivedChunkBoundary() {
        var current = new Vec3d(14.5, 1.1, 0.5);
        var route = List.of(current, new Vec3d(15.5, 1.1, 0.5), new Vec3d(16.5, 1.1, 0.5));
        assertEquals(1, FlightRouteLookahead.select(0, route.size(),
                i -> MinecraftFlightNavigation.sweptBody(current, route.get(i)).maxX <= 16));
    }

    @Test void routeChangesAreRecheckedWithoutCachingOldClearance() {
        assertEquals(4, FlightRouteLookahead.select(0, 5, i -> true));
        assertEquals(1, FlightRouteLookahead.select(0, 5, i -> i == 1));
        assertEquals(0, FlightRouteLookahead.select(0, 5, i -> false));
    }

    @Test void eachTickInspectsAtMostEightFutureWaypointsAndDoesNotChangeRouteBounds() {
        var checks = new AtomicInteger();
        assertEquals(3, FlightRouteLookahead.select(3, 1000, i -> {
            checks.incrementAndGet(); assertTrue(i > 3 && i <= 11); return false;
        }));
        assertEquals(8, checks.get());
        assertEquals(11, FlightRouteLookahead.select(3, 1000, i -> true));
        assertEquals(999, FlightRouteLookahead.select(998, 1000, i -> true));
    }

    @Test void finalWaypointCannotSkipTheArrivalGuard() {
        assertEquals(0, FlightRouteLookahead.select(0, 1, i -> { throw new AssertionError("end"); }));
        assertThrows(IllegalArgumentException.class, () -> FlightRouteLookahead.select(0, 0, i -> true));
        assertThrows(IllegalArgumentException.class, () -> FlightRouteLookahead.select(-1, 3, i -> true));
        assertThrows(IllegalArgumentException.class, () -> FlightRouteLookahead.select(3, 3, i -> true));
    }
}
