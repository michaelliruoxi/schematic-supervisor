package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

class FlightDescentEscapeTest {
    @Test
    void escapesAnEnclosedCourtyardAboveItsWallsThenDescendsOutsideTheFloor() {
        List<Box> obstacles = List.of(
                new Box(-20, 48, -20, 21, 49, 21),
                new Box(-20, 0, -20, 21, 1, 21),
                new Box(-16, 49, -16, -15, 63, 16),
                new Box(15, 49, -16, 16, 63, 16),
                new Box(-16, 49, -16, 16, 63, -15),
                new Box(-16, 49, 15, 16, 63, 16));
        BlockPosition current = at(0, 49, 0);
        FlightDescentEscape escape = new FlightDescentEscape(current.y(), 318);
        List<BlockPosition> combined = new ArrayList<>(List.of(current));
        List<Integer> exhaustedPlanes = new ArrayList<>();
        int totalDiscovered = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            FlightDescentSearch search = new FlightDescentSearch(current, -61, 60_000 - totalDiscovered,
                    FlightDescentEscapeTest::withinRouteBounds, point -> true,
                    point -> clearBody(point, obstacles),
                    (from, to) -> clearSegment(from, to, obstacles), point -> false);
            for (int tick = 0; tick < 4_000 && !search.complete() && !search.failed(); tick++) {
                int before = search.inspected();
                search.tick(16);
                assertTrue(search.inspected() - before <= 16);
            }
            totalDiscovered += search.discoveredNodes();
            if (search.complete()) {
                assertFalse(search.needsChunks());
                combined.addAll(search.path().subList(1, search.path().size()));
                break;
            }
            assertEquals(FlightDescentSearch.FailureReason.NO_ROUTE, search.failureReason());
            exhaustedPlanes.add(current.y());
            List<BlockPosition> ascent = escape.nextPath(current,
                    (from, to) -> withinRouteBounds(to) && clearSegment(from, to, obstacles));
            assertFalse(ascent.isEmpty(), escape.detail());
            combined.addAll(ascent.subList(1, ascent.size()));
            current = ascent.getLast();
        }
        assertEquals(List.of(49, 57), exhaustedPlanes);
        assertEquals(-61, combined.getLast().y());
        assertTrue(Math.abs(combined.getLast().x()) >= 21 || Math.abs(combined.getLast().z()) >= 21);
        assertTrue(totalDiscovered < 6_000, "closed planes and final exit must share the existing 60,000-node cap");
        assertSafePath(combined, obstacles);
    }

    @Test
    void aCeilingBetweenClearEndpointsPreventsEveryHigherAttempt() {
        List<Box> ceiling = List.of(new Box(-2, 54, -2, 3, 54.1, 3));
        BlockPosition start = at(0, 49, 0);
        assertTrue(clearBody(start, ceiling));
        assertTrue(clearBody(at(0, 57, 0), ceiling));
        AtomicInteger checked = new AtomicInteger();
        FlightDescentEscape escape = new FlightDescentEscape(49, 318);
        assertTrue(escape.nextPath(start, (from, to) -> {
            checked.incrementAndGet();
            return clearSegment(from, to, ceiling);
        }).isEmpty());
        assertTrue(escape.blocked());
        assertTrue(escape.nextPath(start, (from, to) -> {
            checked.incrementAndGet();
            return true;
        }).isEmpty());
        assertEquals(1, checked.get());
    }

    @Test
    void limitsTheClimbToFourDeterministicPlanes() {
        FlightDescentEscape escape = new FlightDescentEscape(49, 318);
        BlockPosition current = at(5, 49, -9);
        for (int height : List.of(57, 65, 81, 113)) {
            List<BlockPosition> path = escape.nextPath(current, (from, to) -> true);
            assertEquals(current, path.getFirst());
            assertEquals(at(5, height, -9), path.getLast());
            assertSafePath(path, List.of());
            current = path.getLast();
        }
        assertTrue(escape.nextPath(current, (from, to) -> true).isEmpty());
        assertFalse(escape.blocked());
        assertTrue(escape.detail().contains("exhausted"));
    }

    @Test
    void capsClearanceAtTheWorldHeightWithoutRetryingTheSamePlane() {
        FlightDescentEscape escape = new FlightDescentEscape(49, 70);
        BlockPosition current = at(0, 49, 0);
        AtomicInteger checked = new AtomicInteger();
        for (int height : List.of(57, 65, 70)) {
            List<BlockPosition> path = escape.nextPath(current, (from, to) -> {
                checked.incrementAndGet();
                return to.y() <= 70;
            });
            assertEquals(height, path.getLast().y());
            current = path.getLast();
        }
        assertTrue(escape.nextPath(current, (from, to) -> {
            checked.incrementAndGet();
            return true;
        }).isEmpty());
        assertEquals(3, checked.get());
    }

    @Test
    void cannotBypassMissingTerrainOrTheOriginalRouteBounds() {
        List<Predicate<BlockPosition>> permitted = List.of(
                point -> false,
                FlightDescentEscapeTest::withinRouteBounds);
        for (Predicate<BlockPosition> precondition : permitted) {
            AtomicInteger collisionReads = new AtomicInteger();
            FlightDescentEscape escape = new FlightDescentEscape(380, 500);
            assertTrue(escape.nextPath(at(0, 380, 0), (from, to) -> {
                if (!precondition.test(to)) { return false; }
                collisionReads.incrementAndGet();
                return true;
            }).isEmpty());
            assertEquals(0, collisionReads.get());
            assertTrue(escape.blocked());
        }
    }

    private static boolean withinRouteBounds(BlockPosition point) {
        return (long) point.x() * point.x() + (long) point.y() * point.y()
                + (long) point.z() * point.z() <= 384L * 384;
    }

    private static boolean clearBody(BlockPosition point, List<Box> obstacles) {
        Box body = MinecraftFlightNavigation.body(MinecraftFlightNavigation.center(point));
        return obstacles.stream().noneMatch(body::intersects);
    }

    private static boolean clearSegment(BlockPosition from, BlockPosition to, List<Box> obstacles) {
        Box swept = MinecraftFlightNavigation.sweptBody(
                MinecraftFlightNavigation.center(from), MinecraftFlightNavigation.center(to));
        return obstacles.stream().noneMatch(swept::intersects);
    }

    private static void assertSafePath(List<BlockPosition> path, List<Box> obstacles) {
        assertTrue(path.stream().allMatch(FlightDescentEscapeTest::withinRouteBounds));
        assertTrue(path.stream().allMatch(point -> clearBody(point, obstacles)));
        for (int index = 1; index < path.size(); index++) {
            BlockPosition from = path.get(index - 1);
            BlockPosition to = path.get(index);
            assertEquals(1, Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z()));
            assertTrue(clearSegment(from, to, obstacles));
        }
    }

    private static BlockPosition at(int x, int y, int z) { return new BlockPosition(x, y, z); }
}
