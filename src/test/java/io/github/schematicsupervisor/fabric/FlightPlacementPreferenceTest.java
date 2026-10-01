package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

class FlightPlacementPreferenceTest {
    private static final Vec3d FEET = new Vec3d(0.5, 1.1, 0.5);
    private static final Vec3d EYE = FEET.add(0, 1.62, 0);
    private static final double REACH = 4.15;

    @Test
    void fartherVisibleSideWinsOverNearAnchorWhoseTopFaceRequiresMovement() {
        Candidate near = new Candidate(new BlockPos(1, 0, 0), Direction.WEST, new BlockPos(0, 0, 0));
        Candidate far = new Candidate(new BlockPos(3, 0, 0), Direction.WEST, new BlockPos(2, 0, 0));
        List<BlockPos> blocks = List.of(near.anchor(), far.anchor());
        assertTrue(distance(near) < distance(far));
        assertEquals(Direction.UP, raycast(blocks, EYE, Vec3d.ofCenter(near.anchor())).getSide());
        BlockHitResult fartherHit = raycast(blocks, EYE, Vec3d.ofCenter(far.anchor()));
        assertEquals(far.anchor(), fartherHit.getBlockPos());
        assertEquals(Direction.WEST, fartherHit.getSide());
        assertTrue(EYE.distanceTo(fartherHit.getPos()) < REACH);
        assertFalse(eligible(near, blocks));
        assertTrue(eligible(far, blocks));
        assertSame(far, select(List.of(near, far), blocks));
        assertSame(far, select(List.of(far, near), blocks));
    }

    @Test
    void eligibleCandidatesStillUseDistanceAndEqualCandidatesKeepTheirOriginalOrder() {
        Candidate farther = new Candidate(new BlockPos(3, 0, 1), Direction.WEST, new BlockPos(2, 0, 1));
        Candidate nearer = new Candidate(new BlockPos(3, 0, 0), Direction.WEST, new BlockPos(2, 0, 0));
        Candidate tied = new Candidate(nearer.anchor(), nearer.face(), nearer.reserved());
        List<BlockPos> blocks = List.of(farther.anchor(), nearer.anchor());
        assertTrue(eligible(farther, blocks));
        assertTrue(eligible(nearer, blocks));
        assertTrue(distance(nearer) < distance(farther));
        assertSame(nearer, select(List.of(farther, nearer, tied), blocks));
    }

    @Test
    void noCurrentlyUsableFaceRetainsNearestFallbackAndStableTies() {
        Candidate farther = new Candidate(new BlockPos(2, 0, 0), Direction.EAST, new BlockPos(3, 0, 0));
        Candidate nearer = new Candidate(new BlockPos(1, 0, 0), Direction.EAST, new BlockPos(2, 0, 0));
        Candidate tied = new Candidate(nearer.anchor(), nearer.face(), nearer.reserved());
        List<BlockPos> blocks = List.of(farther.anchor(), nearer.anchor());
        assertFalse(eligible(farther, blocks));
        assertFalse(eligible(nearer, blocks));
        assertSame(nearer, select(List.of(farther, nearer, tied), blocks));
    }

    @Test
    void occludingVoxelAndWrongRequiredFaceCannotGainCurrentPositionPreference() {
        Candidate candidate = new Candidate(new BlockPos(3, 0, 0), Direction.WEST, new BlockPos(2, 0, 0));
        assertTrue(eligible(candidate, List.of(candidate.anchor())));
        assertFalse(eligible(candidate, List.of(candidate.anchor(), new BlockPos(2, 1, 0))));
        assertFalse(eligible(new Candidate(candidate.anchor(), Direction.EAST, candidate.reserved()),
                List.of(candidate.anchor())));
    }

    @Test
    void unreceivedTargetBodyCollisionAndReservedCellEachPreventRayQueries() {
        BlockPos anchor = new BlockPos(3, 0, 0);
        AtomicInteger rays = new AtomicInteger();
        assertFalse(MinecraftFlightNavigation.interactionArrival(FEET, EYE, anchor, REACH,
                null, false, box -> true, () -> { rays.incrementAndGet(); return true; }));
        // A false body predicate represents either an unreceived body chunk or occupied body space.
        assertFalse(MinecraftFlightNavigation.interactionArrival(FEET, EYE, anchor, REACH,
                null, true, box -> false, () -> { rays.incrementAndGet(); return true; }));
        assertFalse(MinecraftFlightNavigation.interactionArrival(FEET, EYE, anchor, REACH,
                new BlockPos(0, 1, 0), true, box -> true,
                () -> { rays.incrementAndGet(); return true; }));
        assertEquals(0, rays.get());
        Candidate candidate = new Candidate(anchor, Direction.WEST, new BlockPos(2, 0, 0));
        assertFalse(eligible(candidate, List.of(anchor, new BlockPos(0, 1, 0))));
    }

    @Test
    void distantAndInvalidRadiusCandidatesDoNoBodyCollisionOrRayWork() {
        AtomicInteger expensiveReads = new AtomicInteger();
        Predicate<Box> body = box -> { expensiveReads.incrementAndGet(); return true; };
        assertFalse(MinecraftFlightNavigation.interactionArrival(FEET, EYE, new BlockPos(100, 0, 0),
                REACH, null, true, body, () -> { expensiveReads.incrementAndGet(); return true; }));
        for (double radius : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 6.1}) {
            assertFalse(MinecraftFlightNavigation.interactionArrival(FEET, EYE, new BlockPos(3, 0, 0),
                    radius, null, true, body, () -> { expensiveReads.incrementAndGet(); return true; }));
        }
        assertEquals(0, expensiveReads.get());
    }

    @Test
    void surfaceInsideReachRemainsEligibleEvenWhenAnchorCenterIsOutsideReach() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        Vec3d feet = new Vec3d(0.5, 3.28, 0.5);
        Vec3d eye = feet.add(0, 1.62, 0);
        assertTrue(eye.distanceTo(Vec3d.ofCenter(anchor)) > 4);
        assertTrue(MinecraftFlightNavigation.interactionArrival(feet, eye, anchor, 4, null,
                true, box -> !box.intersects(new Box(anchor)),
                () -> ExactInteractionRay.trace(eye, anchor, Direction.UP, 4, (x, z) -> true,
                        (from, to) -> raycast(List.of(anchor), from, to)).accepted()));
    }

    private static Candidate select(List<Candidate> candidates, List<BlockPos> blocks) {
        Candidate selected = null;
        boolean bestCurrentPosition = false;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            boolean currentPosition = eligible(candidate, blocks);
            double distance = distance(candidate);
            if (FlightPlacementPreference.prefer(currentPosition, distance, bestCurrentPosition, bestDistance)) {
                selected = candidate;
                bestCurrentPosition = currentPosition;
                bestDistance = distance;
            }
        }
        return selected;
    }

    private static boolean eligible(Candidate candidate, List<BlockPos> blocks) {
        return MinecraftFlightNavigation.interactionArrival(FEET, EYE, candidate.anchor(), REACH,
                candidate.reserved(), true,
                box -> blocks.stream().noneMatch(position -> box.intersects(new Box(position))),
                () -> ExactInteractionRay.trace(EYE, candidate.anchor(), candidate.face(), REACH,
                        (x, z) -> true, (from, to) -> raycast(blocks, from, to)).accepted());
    }

    private static double distance(Candidate candidate) {
        return EYE.squaredDistanceTo(Vec3d.ofCenter(candidate.anchor()));
    }

    private static BlockHitResult raycast(List<BlockPos> blocks, Vec3d from, Vec3d to) {
        BlockHitResult nearest = null;
        for (BlockPos occupied : blocks) {
            BlockHitResult hit = VoxelShapes.fullCube().raycast(from, to, occupied);
            if (hit != null && (nearest == null
                    || from.squaredDistanceTo(hit.getPos()) < from.squaredDistanceTo(nearest.getPos()))) {
                nearest = hit;
            }
        }
        return nearest == null ? BlockHitResult.createMissed(to, Direction.UP, BlockPos.ofFloored(to)) : nearest;
    }

    private record Candidate(BlockPos anchor, Direction face, BlockPos reserved) { }
}
