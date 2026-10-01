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

class FlightDescentSearchTest {
    @Test
    void descendsToY49ThenFindsAColumnOutsideMultipleBroadFloors() {
        Predicate<BlockPosition> received = point -> Math.abs(point.x()) <= 80 && Math.abs(point.z()) <= 80
                && point.y() >= -64 && point.y() <= 319;
        Geometry geometry = new Geometry(received, List.of(
                new Box(-56, 48, -56, 57, 49, 57),
                new Box(-56, 0, -56, 57, 1, 57)));
        FlightDescentProbe probe = new FlightDescentProbe(at(0, 201, 0), -61, geometry::clearSegment);
        completeProbe(probe);
        assertTrue(probe.blocked());
        assertEquals(at(0, 49, 0), probe.path().getLast());

        FlightDescentSearch search = search(probe.path().getLast(), -61, 60_000,
                received, geometry, point -> true);
        completeSearch(search);
        assertFalse(search.needsChunks());
        assertEquals(-61, search.path().getLast().y());
        assertTrue(Math.abs(search.path().getLast().x()) >= 57 || Math.abs(search.path().getLast().z()) >= 57);
        assertTrue(search.inspected() < 8_000, "search should cover a plane, not the volume above both floors");
        assertTrue(search.discoveredNodes() < 9_000);
        List<BlockPosition> combined = new ArrayList<>(probe.path());
        combined.addAll(search.path().subList(1, search.path().size()));
        assertSafePath(combined, geometry);
    }

    @Test
    void clearColumnCanDescendDirectlyWithoutSearchingAPlane() {
        Geometry geometry = new Geometry(point -> true, List.of());
        FlightDescentProbe probe = new FlightDescentProbe(at(4, 201, -7), -61, geometry::clearSegment);
        completeProbe(probe);
        assertFalse(probe.blocked());
        assertEquals(at(4, -61, -7), probe.path().getLast());
        assertSafePath(probe.path(), geometry);
    }

    @Test
    void crossingBetweenClearEndpointsStillRequiresASweptBodyCheck() {
        Predicate<BlockPosition> received = point -> Math.abs(point.x()) <= 8 && Math.abs(point.z()) <= 8;
        Geometry geometry = new Geometry(received, List.of(new Box(-4, 0, -4, 5, 1, 5),
                new Box(0.95, 1, -4, 1.05, 3, 5)));
        assertTrue(geometry.clearBody(at(0, 1, 0)));
        assertTrue(geometry.clearBody(at(1, 1, 0)));
        assertFalse(geometry.clearSegment(at(0, 1, 0), at(1, 1, 0)));
        FlightDescentSearch search = search(at(0, 1, 0), -10, 1_000,
                received, geometry, point -> true);
        completeSearch(search);
        assertFalse(search.needsChunks());
        assertSafePath(search.path(), geometry);
    }

    @Test
    void stagesOnlyAtReceivedCellsThenFindsTheExitAfterMoreChunksArrive() {
        Predicate<BlockPosition> initialReceipt = point -> Math.abs(point.x()) <= 3 && Math.abs(point.z()) <= 3;
        List<Box> floors = List.of(new Box(-8, 0, -8, 9, 1, 9));
        Geometry initial = new Geometry(initialReceipt, floors);
        FlightDescentSearch first = search(at(0, 1, 0), -10, 1_000,
                initialReceipt, initial, point -> true);
        completeSearch(first);
        assertTrue(first.needsChunks());
        assertFalse(initialReceipt.test(first.requestedFrontier()));
        assertTrue(first.path().stream().allMatch(point -> point.y() == 1));
        assertSafePath(first.path(), initial);

        Predicate<BlockPosition> moreReceived = point -> Math.abs(point.x()) <= 12 && Math.abs(point.z()) <= 12;
        Geometry updated = new Geometry(moreReceived, floors);
        FlightDescentSearch next = search(first.path().getLast(), -10,
                1_000 - first.discoveredNodes(), moreReceived, updated, point -> true);
        completeSearch(next);
        assertFalse(next.needsChunks());
        assertSafePath(next.path(), updated);
    }

    @Test
    void doesNotReuseExcludedFrontiersOrIgnoreTheNodeLimit() {
        Predicate<BlockPosition> received = point -> Math.abs(point.x()) <= 3 && Math.abs(point.z()) <= 3;
        Geometry geometry = new Geometry(received, List.of(new Box(-8, 0, -8, 9, 1, 9)));
        FlightDescentSearch exhausted = search(at(0, 1, 0), -10, 1_000,
                received, geometry, point -> false);
        completeSearch(exhausted);
        assertTrue(exhausted.failed());
        assertEquals(FlightDescentSearch.FailureReason.NO_ROUTE, exhausted.failureReason());
        assertFalse(exhausted.complete());

        FlightDescentSearch bounded = search(at(0, 1, 0), -10, 12,
                received, geometry, point -> true);
        completeSearch(bounded);
        assertTrue(bounded.failed());
        assertEquals(FlightDescentSearch.FailureReason.NODE_LIMIT, bounded.failureReason());
        assertTrue(bounded.detail().contains("node limit"));
        assertTrue(bounded.discoveredNodes() <= 12);
    }

    @Test
    void unreceivedStartingDataIsRejectedBeforeAnyCollisionRead() {
        AtomicInteger collisionReads = new AtomicInteger();
        FlightDescentSearch search = new FlightDescentSearch(at(0, 1, 0), -10, 100,
                point -> true, point -> false,
                point -> { collisionReads.incrementAndGet(); return true; },
                (from, to) -> { collisionReads.incrementAndGet(); return true; }, point -> true);
        assertTrue(search.failed());
        assertEquals(0, collisionReads.get());
        assertEquals(FlightDescentSearch.FailureReason.INVALID_START, search.failureReason());
    }

    @Test
    void descentCandidatesCannotEscapeTheSharedRouteBounds() {
        Predicate<BlockPosition> received = point -> Math.abs(point.x()) <= 20 && Math.abs(point.z()) <= 20;
        Geometry geometry = new Geometry(received, List.of(new Box(-8, 0, -8, 9, 1, 9)));
        FlightDescentSearch search = new FlightDescentSearch(at(0, 1, 0), -10, 1_000,
                point -> Math.abs(point.x()) <= 5 && Math.abs(point.z()) <= 5,
                received, geometry::clearBody, geometry::clearSegment, point -> true);
        completeSearch(search);
        assertTrue(search.failed());
        assertFalse(search.needsChunks());
        assertTrue(search.discoveredNodes() <= 121);
    }

    private static FlightDescentSearch search(BlockPosition start, int targetY, int maximumNodes,
                                               Predicate<BlockPosition> received, Geometry geometry,
                                               Predicate<BlockPosition> untriedFrontier) {
        return new FlightDescentSearch(start, targetY, maximumNodes,
                point -> Math.abs(point.x()) <= 384 && Math.abs(point.z()) <= 384
                        && point.y() >= -64 && point.y() <= 319,
                received, geometry::clearBody, geometry::clearSegment, untriedFrontier);
    }

    private static void completeProbe(FlightDescentProbe probe) {
        for (int tick = 0; tick < 30 && !probe.complete(); tick++) {
            int before = probe.inspected();
            probe.tick(32);
            assertTrue(probe.inspected() - before <= 32);
        }
        assertTrue(probe.complete());
    }

    private static void completeSearch(FlightDescentSearch search) {
        for (int tick = 0; tick < 4_000 && !search.complete() && !search.failed(); tick++) {
            int before = search.inspected();
            search.tick(16);
            assertTrue(search.inspected() - before <= 16);
        }
        assertTrue(search.complete() || search.failed(), search.detail());
    }

    private static void assertSafePath(List<BlockPosition> path, Geometry geometry) {
        assertTrue(path.stream().allMatch(geometry::clearBody));
        for (int index = 1; index < path.size(); index++) {
            BlockPosition from = path.get(index - 1);
            BlockPosition to = path.get(index);
            assertEquals(1, Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z()));
            assertTrue(geometry.clearSegment(from, to));
        }
    }

    private record Geometry(Predicate<BlockPosition> received, List<Box> obstacles) {
        boolean clearBody(BlockPosition position) {
            assertTrue(received.test(position), "must not inspect collision data in an unreceived cell");
            Box body = MinecraftFlightNavigation.body(MinecraftFlightNavigation.center(position));
            return obstacles.stream().noneMatch(body::intersects);
        }
        boolean clearSegment(BlockPosition from, BlockPosition to) {
            assertTrue(received.test(from) && received.test(to), "swept collision reads require received cells");
            Box swept = MinecraftFlightNavigation.sweptBody(
                    MinecraftFlightNavigation.center(from), MinecraftFlightNavigation.center(to));
            return obstacles.stream().noneMatch(swept::intersects);
        }
    }

    private static BlockPosition at(int x, int y, int z) { return new BlockPosition(x, y, z); }
}
