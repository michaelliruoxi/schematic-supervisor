package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

class TillTargetSelectorTest {
    @Test
    void missingPrerequisiteCannotBecomeAFallbackOrBeHiddenByReachableSoil() {
        var selector = new TillTargetSelector(List.of(pos(0, 0), pos(1, 0)));
        selector.begin();
        selector.scan(256, index -> true, index -> index != 1, index -> true, index -> 1);
        assertTrue(selector.complete());
        assertEquals(1, selector.invalidIndex());
        assertEquals(-1, selector.selectedIndex());
        selector.begin();
        selector.scan(256, index -> true, index -> false, index -> {
            throw new AssertionError("missing soil must not trigger route geometry");
        }, index -> 1);
        assertEquals(0, selector.invalidIndex());
        assertEquals(-1, selector.selectedIndex());
        assertEquals(0, select(selector, new BitSet(), index -> false, index -> 1));
        assertEquals(-1, selector.invalidIndex());
    }

    @Test
    void aNearbyOccludedTopFaceLosesToVisibleSoilUsingTheActualRayGuard() {
        var targets = List.of(pos(0, 1), pos(2, 0));
        var selector = new TillTargetSelector(targets);
        var eye = new Vec3d(0.5, 2.72, 0.5);
        var blocks = List.of(new BlockPos(0, 0, 1), new BlockPos(0, 1, 1), new BlockPos(2, 0, 0));
        IntPredicate reachable = index -> {
            BlockPosition target = targets.get(index);
            return ExactInteractionRay.trace(eye, new BlockPos(target.x(), target.y(), target.z()),
                    Direction.UP, 4.15, (x, z) -> true, (from, to) -> {
                        BlockHitResult nearest = null;
                        for (BlockPos occupied : blocks) {
                            BlockHitResult hit = VoxelShapes.fullCube().raycast(from, to, occupied);
                            if (hit != null && (nearest == null || from.squaredDistanceTo(hit.getPos())
                                    < from.squaredDistanceTo(nearest.getPos()))) { nearest = hit; }
                        }
                        return nearest == null
                                ? BlockHitResult.createMissed(to, Direction.UP, BlockPos.ofFloored(to)) : nearest;
                    }).accepted();
        };
        assertFalse(reachable.test(0));
        assertTrue(reachable.test(1));
        assertEquals(1, select(selector, new BitSet(), reachable, index -> index + 1));
    }

    @Test
    void reachableSoilWinsOverTheFirstSweepTargetEvenWhenFartherAway() {
        var selector = new TillTargetSelector(List.of(pos(0, 0), pos(1, 0), pos(2, 0)));
        assertEquals(2, select(selector, new BitSet(), index -> index == 2, index -> index + 1));
    }

    @Test
    void nearestReachableTargetWinsWithoutReindexingTheCompletionBits() {
        var targets = new ArrayList<>(List.of(pos(2, 2), pos(0, 0), pos(1, 1)));
        var selector = new TillTargetSelector(targets);
        targets.clear();
        var completed = new BitSet();
        var sequence = new ArrayList<Integer>();
        for (int count = 0; count < 3; count++) {
            int selected = select(selector, completed, index -> true, index -> index + 1);
            assertFalse(completed.get(selected));
            sequence.add(selected);
            completed.set(selected);
        }
        assertEquals(List.of(0, 1, 2), sequence);
        assertEquals(-1, select(selector, completed, index -> true, index -> 0));
    }

    @Test
    void completingTheHighestIndexFirstDoesNotSkipEarlierUnfinishedTargets() {
        var selector = new TillTargetSelector(List.of(pos(0, 0), pos(0, 1), pos(0, 2)));
        var completed = new BitSet();
        assertEquals(2, select(selector, completed, index -> index == 2, index -> 0));
        completed.set(2);
        assertEquals(0, select(selector, completed, index -> false, index -> 0));
        completed.set(0);
        assertEquals(1, select(selector, completed, index -> false, index -> 0));
    }

    @Test
    void fallbackAlternatesRowDirectionIncludingNegativeCoordinates() {
        for (int x : new int[]{0, -2}) {
            var selector = new TillTargetSelector(List.of(
                    pos(x, -8), pos(x, -7), pos(x + 1, -8), pos(x + 1, -7)));
            var completed = new BitSet();
            var sequence = new ArrayList<Integer>();
            for (int count = 0; count < 4; count++) {
                int selected = select(selector, completed, index -> false, index -> 0);
                sequence.add(selected);
                completed.set(selected);
            }
            assertEquals(List.of(0, 1, 3, 2), sequence);
        }
    }

    @Test
    void aReachableLowerFloorWaitsUntilAllHigherTargetsAreComplete() {
        var selector = new TillTargetSelector(List.of(new BlockPosition(0, 2, 0),
                new BlockPosition(0, 8, 0), new BlockPosition(1, 8, 0)));
        var completed = new BitSet();
        assertEquals(1, select(selector, completed, index -> index == 0, index -> 0));
        completed.set(1);
        assertEquals(2, select(selector, completed, index -> index == 0, index -> 0));
        completed.set(2);
        assertEquals(0, select(selector, completed, index -> true, index -> 0));
    }

    @Test
    void boundedScanDoesNotCommitAFallbackBeforeCheckingLaterReachableTargets() {
        var selector = new TillTargetSelector(List.of(pos(0, 0), pos(0, 1), pos(0, 2)));
        var reads = new AtomicInteger();
        IntPredicate unfinished = index -> { reads.incrementAndGet(); return true; };
        selector.begin();
        selector.scan(1, unfinished, index -> index == 2, index -> 1);
        assertEquals(1, reads.get());
        assertFalse(selector.complete());
        assertThrows(IllegalStateException.class, selector::selectedIndex);
        selector.scan(1, unfinished, index -> index == 2, index -> 1);
        assertEquals(2, reads.get());
        assertFalse(selector.complete());
        selector.scan(1, unfinished, index -> index == 2, index -> 1);
        assertEquals(3, reads.get());
        assertTrue(selector.complete());
        assertEquals(2, selector.selectedIndex());
    }

    @Test
    void aNewSelectionRechecksReachabilityAndAlreadyCompletedSoil() {
        var selector = new TillTargetSelector(List.of(pos(0, 0), pos(0, 1)));
        var completed = new BitSet();
        assertEquals(1, select(selector, completed, index -> index == 1, index -> 1));
        assertEquals(0, select(selector, completed, index -> index == 0, index -> 1));
        completed.set(0);
        assertEquals(1, select(selector, completed, index -> false, index -> 1));
    }

    @Test
    void equalDistancesUseTheStableSweepOrderAndDoNotCallGeometryForCompletedCells() {
        var selector = new TillTargetSelector(List.of(pos(1, 0), pos(1, 1), pos(0, 0)));
        var completed = new BitSet();
        completed.set(2);
        assertEquals(1, select(selector, completed, index -> {
            assertFalse(completed.get(index));
            return true;
        }, index -> 1));
    }

    @Test
    void completedOrEmptyOrdersHaveNoInteractionTarget() {
        var selector = new TillTargetSelector(List.of(pos(0, 0)));
        var completed = new BitSet();
        completed.set(0);
        assertEquals(-1, select(selector, completed, index -> {
            throw new AssertionError("completed soil must not require a ray test");
        }, index -> 0));
        assertEquals(-1, select(new TillTargetSelector(List.of()), new BitSet(), index -> true, index -> 0));
    }

    private static int select(TillTargetSelector selector, BitSet completed,
                              IntPredicate reachable, IntToDoubleFunction distance) {
        selector.begin();
        selector.scan(256, index -> !completed.get(index), reachable, distance);
        assertTrue(selector.complete());
        return selector.selectedIndex();
    }

    private static BlockPosition pos(int x, int z) { return new BlockPosition(x, 0, z); }
}
