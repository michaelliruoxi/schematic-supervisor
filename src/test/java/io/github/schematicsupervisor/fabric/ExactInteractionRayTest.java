package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

class ExactInteractionRayTest {
    @Test void shortStemUsesItsActualOutlineInsteadOfTheEmptyBlockCenter() {
        BlockPos target = new BlockPos(0, 0, 0);
        Vec3d eye = new Vec3d(2.5, 1.62, 0.5);
        VoxelShape youngStem = VoxelShapes.cuboid(0.375, 0, 0.375, 0.625, 0.125, 0.625);
        assertFalse(ExactInteractionRay.trace(eye, target, null, 4.15, (x, z) -> true,
                (from, to) -> shapedHit(from, to, target, youngStem)).accepted());
        ExactInteractionRay.Result result = ExactInteractionRay.trace(eye, target, null, 4.15,
                (x, z) -> true, (from, to) -> shapedHit(from, to, target, youngStem),
                () -> ExactInteractionRay.stemEndpoint(target, "minecraft:pumpkin_stem", youngStem));
        assertTrue(result.accepted(), result.detail());
        assertEquals(target, result.hit().getBlockPos());
        assertTrue(result.hit().getPos().y <= 0.125);
    }

    @Test void aStemOutlineEndpointStillCannotSeeThroughAnOccludingBlock() {
        BlockPos target = new BlockPos(0, 0, 0);
        BlockPos obstruction = target.east();
        VoxelShape stem = VoxelShapes.cuboid(0.375, 0, 0.375, 0.625, 0.125, 0.625);
        var result = ExactInteractionRay.trace(new Vec3d(2.5, 0.5, 0.5), target, null, 4.15,
                (x, z) -> true, (from, to) -> {
                    BlockHitResult blocked = VoxelShapes.fullCube().raycast(from, to, obstruction);
                    return blocked == null ? shapedHit(from, to, target, stem) : blocked;
                }, () -> ExactInteractionRay.stemEndpoint(target, "minecraft:melon_stem", stem));
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("another block"));
    }

    @Test void attachedStemEndpointIsInsideAnActualPartAndOtherBlocksKeepCenter() {
        BlockPos target = new BlockPos(0, 0, 0);
        VoxelShape attached = VoxelShapes.union(
                VoxelShapes.cuboid(0.375, 0, 0.375, 0.625, 0.625, 0.625),
                VoxelShapes.cuboid(0.625, 0.375, 0.375, 1, 0.625, 0.625));
        for (String id : List.of("minecraft:attached_pumpkin_stem", "minecraft:attached_melon_stem")) {
            Vec3d endpoint = ExactInteractionRay.stemEndpoint(target, id, attached);
            assertTrue(attached.getBoundingBoxes().stream().anyMatch(box -> box.contains(endpoint)));
            var result = ExactInteractionRay.trace(new Vec3d(2.5, 1.62, 0.5), target, null, 4.15,
                    (x, z) -> true, (from, to) -> shapedHit(from, to, target, attached), () -> endpoint);
            assertTrue(result.accepted(), result.detail());
        }
        assertEquals(Vec3d.ofCenter(target), ExactInteractionRay.stemEndpoint(target, "minecraft:wheat", attached));
    }

    @Test void unreceivedStemOutlineIsNotReadBeforeTheSharedChunkGuard() {
        AtomicInteger outlines = new AtomicInteger();
        var result = ExactInteractionRay.trace(new Vec3d(0.5, 2, 0.5), BlockPos.ORIGIN, null, 4.15,
                (x, z) -> false, (from, to) -> BlockHitResult.createMissed(to, Direction.UP, BlockPos.ORIGIN),
                () -> { outlines.incrementAndGet(); return Vec3d.ofCenter(BlockPos.ORIGIN); });
        assertFalse(result.accepted());
        assertEquals(0, outlines.get());
    }

    private static BlockHitResult shapedHit(Vec3d from, Vec3d to, BlockPos target, VoxelShape shape) {
        BlockHitResult hit = shape.raycast(from, to, target);
        return hit == null ? BlockHitResult.createMissed(to, Direction.UP, target) : hit;
    }

    private static final BlockPos TARGET = new BlockPos(8206, -63, -26976);
    private static final Vec3d EYE = new Vec3d(8208.5, -60.28, -26975.6);

    @Test
    void aMissNeverGrantsArrivalEvenWhenAnyFaceIsAllowed() {
        BlockHitResult miss = BlockHitResult.createMissed(Vec3d.ofCenter(TARGET), Direction.UP, TARGET);
        ExactInteractionRay.Result result = trace(EYE, TARGET, null, 4.15, miss);
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("outline ray missed"));
        assertTrue(result.detail().contains("8206, -63, -26976"));
        assertTrue(result.detail().contains("MISS"));
        assertTrue(result.detail().length() <= 384);
    }

    @Test
    void anOccludingNeighborDoesNotAuthorizeMiningThePlannedMoss() {
        BlockPos firstDirt = new BlockPos(8207, -63, -26975);
        BlockHitResult blocked = new BlockHitResult(new Vec3d(8207.7, -62, -26974.9),
                Direction.UP, firstDirt, false);
        ExactInteractionRay.Result result = trace(EYE, TARGET, null, 4.15, blocked);
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("another block"));
        assertTrue(result.detail().contains(firstDirt.toShortString()));
    }

    @Test
    void exactRequiredFaceAndHitDistanceAreSharedByArrivalAndClickValidation() {
        BlockHitResult upper = new BlockHitResult(new Vec3d(8206.5, -62, -26975.5),
                Direction.UP, TARGET, false);
        assertSame(upper, trace(EYE, TARGET, Direction.UP, 4.15, upper).hit());
        assertSame(upper, trace(EYE, TARGET, null, 4.15, upper).hit());
        ExactInteractionRay.Result wrongFace = trace(EYE, TARGET, Direction.EAST, 4.15, upper);
        assertFalse(wrongFace.accepted());
        assertTrue(wrongFace.detail().contains("different face"));
    }

    @Test
    void reachableOutlineSurfaceIsAcceptedWithoutRequiringTheBlockCenterInRange() {
        BlockPos target = new BlockPos(0, 0, 0);
        Vec3d eye = new Vec3d(0.5, 4.9, 0.5);
        BlockHitResult upper = new BlockHitResult(new Vec3d(0.5, 1, 0.5), Direction.UP, target, false);
        assertTrue(eye.distanceTo(Vec3d.ofCenter(target)) > 4);
        assertTrue(trace(eye, target, Direction.UP, 4, upper).accepted());
    }

    @Test
    void anExactBlockHitBeyondTheActualInteractionRadiusIsRejected() {
        BlockPos target = new BlockPos(0, 0, 0);
        Vec3d eye = new Vec3d(4.9, 0.5, 0.5);
        BlockHitResult tooFar = new BlockHitResult(new Vec3d(0.5, 0.5, 0.5), Direction.EAST, target, false);
        ExactInteractionRay.Result result = trace(eye, target, Direction.EAST, 4, tooFar);
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("hit exceeds"));
    }

    @Test
    void unreceivedIntermediateChunksPreventRaycastAndUnknownAirCannotGrantArrival() {
        AtomicInteger raycasts = new AtomicInteger();
        Set<String> checked = new HashSet<>();
        BlockPos target = new BlockPos(-1, 0, -1);
        ExactInteractionRay.Result result = ExactInteractionRay.trace(new Vec3d(0.2, 2, 0.2),
                target, null, 4.15, (x, z) -> {
                    checked.add(x + "," + z);
                    return x != -1 || z != 0;
                }, (from, to) -> {
                    raycasts.incrementAndGet();
                    return new BlockHitResult(to, Direction.UP, target, false);
                });
        assertFalse(result.accepted());
        assertEquals(0, raycasts.get());
        assertTrue(checked.contains("-1,-1"));
        assertTrue(checked.contains("-1,0"));
        assertTrue(result.detail().contains("unreceived chunk (-1,0)"));
    }

    @Test
    void distantTargetIsRejectedBeforeAnyChunkReadOrRaycast() {
        AtomicInteger reads = new AtomicInteger();
        ExactInteractionRay.Result result = ExactInteractionRay.trace(new Vec3d(0, 0, 0),
                TARGET, null, 4.15, (x, z) -> {
                    reads.incrementAndGet();
                    return true;
                }, (from, to) -> {
                    reads.incrementAndGet();
                    return BlockHitResult.createMissed(to, Direction.UP, TARGET);
                });
        assertFalse(result.accepted());
        assertEquals(0, reads.get());
    }

    @Test
    void tillTopFaceRayReachesDirtAcrossTheNeighborThatBlockedItsCenterRay() {
        BlockPos target = new BlockPos(3, 0, 0);
        Vec3d feet = new Vec3d(0.5, 1.1, 0.5);
        Vec3d eye = feet.add(0, 1.62, 0);
        List<BlockPos> soil = List.of(BlockPos.ORIGIN, target.west(2), target.west(), target);
        BlockHitResult centerHit = firstOutlineHit(eye, Vec3d.ofCenter(target), target, soil);
        assertEquals(target.west(), centerHit.getBlockPos());
        assertFalse(ExactInteractionRay.trace(eye, target, Direction.UP, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, target, soil)).accepted());

        ExactInteractionRay.Result result = ExactInteractionRay.traceTillTopFace(eye, target, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, target, soil));
        assertTrue(result.accepted(), result.detail());
        assertEquals(target, result.hit().getBlockPos());
        assertEquals(Direction.UP, result.hit().getSide());
        assertEquals(1, result.hit().getPos().y, 0.000001);
        assertTrue(eye.distanceTo(result.hit().getPos()) < 4.15);
        assertTrue(MinecraftFlightNavigation.interactionArrival(feet, eye, target, 4.15, null, true,
                body -> soil.stream().noneMatch(block -> body.intersects(new Box(block))), result::accepted));
        assertFalse(MinecraftFlightNavigation.interactionArrival(feet, eye, target, 4.15, null, true,
                body -> false, result::accepted));
    }

    @Test
    void tillTopFaceAimExpandsOneHoveringPatchWithoutExtendingReach() {
        Vec3d eye = new Vec3d(0.5, 2.72, 0.5);
        List<BlockPos> soil = new ArrayList<>();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) { soil.add(new BlockPos(x, 0, z)); }
        }
        int centerReachable = 0;
        int topReachable = 0;
        for (BlockPos target : soil) {
            if (ExactInteractionRay.trace(eye, target, Direction.UP, 4.15, (x, z) -> true,
                    (from, to) -> firstOutlineHit(from, to, target, soil)).accepted()) { centerReachable++; }
            var top = ExactInteractionRay.traceTillTopFace(eye, target, 4.15, (x, z) -> true,
                    (from, to) -> firstOutlineHit(from, to, target, soil));
            if (top.accepted()) {
                topReachable++;
                assertTrue(eye.distanceTo(top.hit().getPos()) <= 4.15);
                assertEquals(target, top.hit().getBlockPos());
                assertEquals(Direction.UP, top.hit().getSide());
            }
        }
        assertEquals(25, centerReachable);
        assertEquals(45, topReachable);
    }

    @Test
    void tillTopFaceAimStillRejectsAnOverlyingObstruction() {
        BlockPos target = new BlockPos(3, 0, 0);
        BlockPos obstruction = target.up();
        List<BlockPos> blocks = List.of(target, target.west(), obstruction);
        var result = ExactInteractionRay.traceTillTopFace(new Vec3d(0.5, 2.72, 0.5), target, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, target, blocks));
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("another block"));
        assertTrue(result.detail().contains(obstruction.toShortString()));
    }

    @Test
    void tillTopFaceAimCannotAuthorizeASideHitFromBelowTheSoilSurface() {
        var result = ExactInteractionRay.traceTillTopFace(new Vec3d(-2.5, 0.5, 0.5), BlockPos.ORIGIN,
                4.15, (x, z) -> true,
                (from, to) -> firstOutlineHit(from, to, BlockPos.ORIGIN, List.of(BlockPos.ORIGIN)));
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("different face"));
    }

    @Test
    void tillTopFaceHitMustStayWithinReachEvenWhenTheNearBlockEdgeIsReachable() {
        BlockPos target = new BlockPos(4, 0, 0);
        var result = ExactInteractionRay.traceTillTopFace(new Vec3d(0.5, 2.72, 0.5), target, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, target, List.of(target)));
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("hit exceeds"));
    }

    @Test
    void tillTopFaceAimKeepsTheEarlyDistantTargetAndReceivedChunkGuards() {
        AtomicInteger reads = new AtomicInteger();
        var distant = ExactInteractionRay.traceTillTopFace(new Vec3d(0.5, 2.72, 0.5), TARGET, 4.15,
                (x, z) -> { reads.incrementAndGet(); return true; },
                (from, to) -> { reads.incrementAndGet(); return BlockHitResult.createMissed(to, Direction.UP, TARGET); });
        assertFalse(distant.accepted());
        assertEquals(0, reads.get());

        BlockPos target = new BlockPos(-1, 0, -1);
        var unreceived = ExactInteractionRay.traceTillTopFace(new Vec3d(0.2, 2, 0.2), target, 4.15,
                (x, z) -> x != -1 || z != 0,
                (from, to) -> { reads.incrementAndGet(); return firstOutlineHit(from, to, target, List.of(target)); });
        assertFalse(unreceived.accepted());
        assertEquals(0, reads.get());
        assertTrue(unreceived.detail().contains("unreceived chunk (-1,0)"));
    }

    @Test
    void legacyCenterRayReachesTheLowerFarmlandOutlineDirectlyBelow() {
        BlockPos target = BlockPos.ORIGIN;
        Vec3d eye = new Vec3d(0.5, 2.72, 0.5);
        VoxelShape farmland = VoxelShapes.cuboid(0, 0, 0, 1, 15.0 / 16.0, 1);
        var result = ExactInteractionRay.trace(eye, target, Direction.UP, 4.15,
                (x, z) -> true, (from, to) -> {
                    assertEquals(Vec3d.ofCenter(target), to);
                    return shapedHit(from, to, target, farmland);
                });
        assertTrue(result.accepted(), result.detail());
        assertEquals(15.0 / 16.0, result.hit().getPos().y, 0.000001);
        assertEquals(Direction.UP, result.hit().getSide());
    }

    @Test
    void farmlandSurfaceAimReachesMoreCellsAtTheSameExactRange() {
        Vec3d eye = new Vec3d(0.5, 2.72, 0.5);
        VoxelShape farmland = VoxelShapes.cuboid(0, 0, 0, 1, 15.0 / 16.0, 1);
        List<BlockPos> soil = new ArrayList<>();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) { soil.add(new BlockPos(x, 0, z)); }
        }
        int reachable = 0;
        for (BlockPos target : soil) {
            var result = ExactInteractionRay.trace(eye, target, Direction.UP, 4.15,
                    (x, z) -> true, (from, to) -> firstOutlineHit(from, to, target, soil, farmland),
                    () -> ExactInteractionRay.farmlandTopEndpoint(target, farmland));
            if (result.accepted()) {
                reachable++;
                assertEquals(Direction.UP, result.hit().getSide());
                assertEquals(target, result.hit().getBlockPos());
                assertTrue(eye.distanceTo(result.hit().getPos()) <= 4.15);
            }
        }
        assertEquals(45, reachable);
    }

    @Test
    void farmlandSurfaceAimCannotClickThroughAnExistingCropOrUnreceivedChunk() {
        BlockPos target = new BlockPos(3, 0, 0);
        VoxelShape farmland = VoxelShapes.cuboid(0, 0, 0, 1, 15.0 / 16.0, 1);
        Vec3d eye = new Vec3d(0.5, 2.72, 0.5);
        var blocked = ExactInteractionRay.trace(eye, target, Direction.UP, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, target,
                        List.of(target, target.up()), farmland),
                () -> ExactInteractionRay.farmlandTopEndpoint(target, farmland));
        assertFalse(blocked.accepted());
        assertTrue(blocked.detail().contains("another block"));
        var unloaded = ExactInteractionRay.trace(eye, target, Direction.UP, 4.15,
                (x, z) -> false, (from, to) -> { throw new AssertionError("unreceived geometry"); },
                () -> ExactInteractionRay.farmlandTopEndpoint(target, farmland));
        assertFalse(unloaded.accepted());
    }

    @Test
    void farmlandEndpointUsesActualOutlineHeightIncludingNegativeFloors() {
        var target = new BlockPos(-3, -63, -19);
        for (double height : new double[]{0.5, 15.0 / 16.0, 1}) {
            var outline = VoxelShapes.cuboid(0, 0, 0, 1, height, 1);
            assertEquals(-63 + height - 0.001,
                    ExactInteractionRay.farmlandTopEndpoint(target, outline).y, 0.0000001);
        }
    }

    @Test
    void actualFarmlandOutlineAllowsASideHitAcrossAnAdjacentMissingFloorCell() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        BlockPos missingFloor = anchor.east();
        Vec3d feet = new Vec3d(3.5, 1.1, 0.5);
        Vec3d eye = feet.add(0, 1.62, 0);
        VoxelShape farmland = VoxelShapes.cuboid(0, 0, 0, 1, 15.0 / 16.0, 1);
        ExactInteractionRay.Result result = ExactInteractionRay.trace(eye, anchor, Direction.EAST, 4.15,
                (x, z) -> true, (from, to) -> {
                    BlockHitResult nearest = null;
                    // The player hovers over existing farmland beyond the one-cell hole.
                    for (BlockPos occupied : List.of(anchor, anchor.east(2), anchor.east(3))) {
                        BlockHitResult hit = farmland.raycast(from, to, occupied);
                        if (hit != null && (nearest == null
                                || from.squaredDistanceTo(hit.getPos()) < from.squaredDistanceTo(nearest.getPos()))) {
                            nearest = hit;
                        }
                    }
                    return nearest == null ? BlockHitResult.createMissed(to, Direction.UP, anchor) : nearest;
                });
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(feet, missingFloor));
        assertTrue(result.accepted(), result.detail());
        assertEquals(Direction.EAST, result.hit().getSide());
        assertEquals(missingFloor, result.hit().getBlockPos().offset(result.hit().getSide()));
        assertTrue(result.hit().getPos().y < 15.0 / 16.0);
        assertEquals(1, result.hit().getPos().x, 0.000001);
    }

    @Test
    void aFarmlandTopHitFromATooSteepApproachDoesNotAuthorizeTheRequestedSide() {
        BlockPos anchor = new BlockPos(0, 0, 0);
        VoxelShape farmland = VoxelShapes.cuboid(0, 0, 0, 1, 15.0 / 16.0, 1);
        ExactInteractionRay.Result result = ExactInteractionRay.trace(new Vec3d(2.5, 2.72, 0.5),
                anchor, Direction.EAST, 4.15, (x, z) -> true, (from, to) -> {
                    BlockHitResult hit = farmland.raycast(from, to, anchor);
                    return hit == null ? BlockHitResult.createMissed(to, Direction.UP, anchor) : hit;
                });
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("different face"));
        assertTrue(result.detail().contains("/up"));
    }

    @Test
    void bottomFaceRayReachesTheAnchorUnderAnAdjacentCeilingFromAClearGridPose() {
        BlockPos anchor = new BlockPos(8199, -60, -26974);
        BlockPos replacement = anchor.down();
        Vec3d feet = new Vec3d(8200.5, -61.9, -26973.5);
        Vec3d eye = feet.add(0, 1.62, 0);
        List<BlockPos> solidBlocks = List.of(anchor, anchor.east(), anchor.west(),
                anchor.down(3), anchor.east().down(3), anchor.west().down(3));
        BlockHitResult oldCenterHit = firstOutlineHit(eye, Vec3d.ofCenter(anchor), anchor, solidBlocks);
        assertEquals(anchor.east(), oldCenterHit.getBlockPos());
        assertFalse(ExactInteractionRay.trace(eye, anchor, null, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, anchor, solidBlocks)).accepted());

        ExactInteractionRay.Result result = ExactInteractionRay.trace(eye, anchor, Direction.DOWN, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, anchor, solidBlocks));
        assertTrue(result.accepted(), result.detail());
        assertEquals(anchor, result.hit().getBlockPos());
        assertEquals(Direction.DOWN, result.hit().getSide());
        assertEquals(-60, result.hit().getPos().y, 0.000001);
        assertEquals(replacement, result.hit().getBlockPos().offset(result.hit().getSide()));
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(feet, replacement));
        assertTrue(MinecraftFlightNavigation.interactionArrival(feet, eye, anchor, 4.15,
                replacement, true,
                body -> solidBlocks.stream().noneMatch(block -> body.intersects(new Box(block))),
                result::accepted));
    }

    @Test
    void bottomFaceAimStillRejectsARealBlockBetweenThePlayerAndAnchor() {
        BlockPos anchor = new BlockPos(0, 3, 0);
        BlockPos obstruction = anchor.east().down();
        Vec3d eye = new Vec3d(2.5, 2.72, 0.5);
        List<BlockPos> solidBlocks = List.of(anchor, anchor.east(), obstruction);
        ExactInteractionRay.Result result = ExactInteractionRay.trace(eye, anchor, Direction.DOWN, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, anchor, solidBlocks));
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("another block"));
        assertTrue(result.detail().contains(obstruction.toShortString()));
    }

    @Test
    void bottomFaceRayAlsoReachesTheActualFarmlandUndersideBelowItsLowerTop() {
        BlockPos anchor = new BlockPos(8199, -60, -26974);
        Vec3d feet = new Vec3d(8200.5, -61.9, -26973.5);
        Vec3d eye = feet.add(0, 1.62, 0);
        VoxelShape farmland = VoxelShapes.cuboid(0, 0, 0, 1, 15.0 / 16.0, 1);
        List<BlockPos> ceiling = List.of(anchor, anchor.east(), anchor.west());
        assertEquals(anchor.east(), firstOutlineHit(eye, Vec3d.ofCenter(anchor),
                anchor, ceiling, farmland).getBlockPos());
        ExactInteractionRay.Result result = ExactInteractionRay.trace(eye, anchor, Direction.DOWN, 4.15,
                (x, z) -> true, (from, to) -> firstOutlineHit(from, to, anchor, ceiling, farmland));
        assertTrue(result.accepted(), result.detail());
        assertEquals(anchor, result.hit().getBlockPos());
        assertEquals(Direction.DOWN, result.hit().getSide());
        assertEquals(-60, result.hit().getPos().y, 0.000001);
        assertTrue(MinecraftFlightNavigation.interactionArrival(feet, eye, anchor, 4.15,
                anchor.down(), true,
                body -> ceiling.stream().noneMatch(block -> body.intersects(new Box(block))),
                result::accepted));
    }

    @Test
    void bottomFaceAimDoesNotAcceptTheAnchorsTopFace() {
        BlockPos anchor = new BlockPos(0, 3, 0);
        ExactInteractionRay.Result result = ExactInteractionRay.trace(new Vec3d(0.5, 5.5, 0.5),
                anchor, Direction.DOWN, 4.15, (x, z) -> true,
                (from, to) -> firstOutlineHit(from, to, anchor, List.of(anchor)));
        assertFalse(result.accepted());
        assertTrue(result.detail().contains("different face"));
        assertTrue(result.detail().contains("/up"));
    }

    @Test
    void otherFacesAndUnspecifiedFaceKeepTheirOriginalCenterAim() {
        assertCenterEndpoint(null);
        for (Direction face : Direction.values()) {
            if (face != Direction.DOWN) { assertCenterEndpoint(face); }
        }
    }

    private static void assertCenterEndpoint(Direction face) {
        ExactInteractionRay.trace(EYE, TARGET, face, 4.15, (x, z) -> true, (from, to) -> {
            assertEquals(Vec3d.ofCenter(TARGET), to);
            return BlockHitResult.createMissed(to, Direction.UP, TARGET);
        });
    }

    private static BlockHitResult firstOutlineHit(Vec3d from, Vec3d to, BlockPos target,
                                                   List<BlockPos> solidBlocks) {
        return firstOutlineHit(from, to, target, solidBlocks, VoxelShapes.fullCube());
    }

    private static BlockHitResult firstOutlineHit(Vec3d from, Vec3d to, BlockPos target,
                                                   List<BlockPos> solidBlocks, VoxelShape shape) {
        BlockHitResult nearest = null;
        for (BlockPos block : solidBlocks) {
            BlockHitResult hit = shape.raycast(from, to, block);
            if (hit != null && (nearest == null
                    || from.squaredDistanceTo(hit.getPos()) < from.squaredDistanceTo(nearest.getPos()))) {
                nearest = hit;
            }
        }
        return nearest == null ? BlockHitResult.createMissed(to, Direction.UP, target) : nearest;
    }

    private static ExactInteractionRay.Result trace(Vec3d eye, BlockPos target, Direction face,
                                                    double reach, BlockHitResult hit) {
        return ExactInteractionRay.trace(eye, target, face, reach, (x, z) -> true, (from, to) -> hit);
    }
}
