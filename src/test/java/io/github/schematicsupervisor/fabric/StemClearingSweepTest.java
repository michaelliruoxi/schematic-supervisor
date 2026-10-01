package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

final class StemClearingSweepTest {
    @Test void unreceivedStemRetainsOnlyAnAlreadyPlannedInteractionRoute() {
        assertEquals(StemClearingSweep.ApproachAction.FOLLOW_PLANNED_ROUTE,
                StemClearingSweep.approachAction(false, true));
        assertEquals(StemClearingSweep.ApproachAction.RECEIVE_TARGET_CHUNK,
                StemClearingSweep.approachAction(false, false));
        for (boolean following : List.of(false, true)) {
            assertEquals(StemClearingSweep.ApproachAction.INSPECT_TARGET,
                    StemClearingSweep.approachAction(true, following));
        }
    }

    @Test void outsideEdgeDetourKeepsItsOriginalRouteWhenTheDistantStemChunkUnloads() {
        BlockPosition start = new BlockPosition(8303, -59, -26923);
        BlockPosition target = new BlockPosition(8192, -62, -26923);
        BlockPosition arrival = new BlockPosition(8194, -62, -26923);
        Box floor = new Box(8192, -60, -26923, 8304, -59, -26922);
        Predicate<BlockPosition> clearCorridor = point -> point.z() == -26923
                && point.x() >= 8192 && point.x() <= 8304
                && ((point.y() == -59 && point.x() >= 8303)
                || (point.x() == 8304 && point.y() >= -62 && point.y() <= -59)
                || point.y() == -62);
        var attempt = new FlightRouteAttempt(60_000, 3_600, 4);
        var planner = new FlightRoutePlanner(start, target, 4.15, 384, attempt.remainingNodes(),
                clearCorridor, arrival::equals,
                (from, to) -> !MinecraftFlightNavigation.sweptBody(MinecraftFlightNavigation.center(from),
                        MinecraftFlightNavigation.center(to)).intersects(floor));
        attempt.accountNodes(planner.discoveredNodes());
        for (int tick = 0; tick < 100 && !planner.complete() && !planner.failed(); tick++) {
            assertTrue(attempt.tick());
            int before = planner.discoveredNodes();
            planner.tick(16);
            attempt.accountNodes(planner.discoveredNodes() - before);
        }
        assertTrue(planner.complete(), planner.detail());
        int plannedNodes = attempt.nodesUsed();
        int ticksAfterPlanning = attempt.activeTicks();
        int unreceivedTargetSteps = 0;
        var route = planner.path();
        for (int waypoint = 0; waypoint < route.size(); waypoint++) {
            BlockPosition feet = route.get(waypoint);
            boolean targetReceived = feet.x() < 8304;
            var action = StemClearingSweep.approachAction(targetReceived, waypoint < route.size() - 1);
            if (!targetReceived) {
                unreceivedTargetSteps++;
                assertEquals(StemClearingSweep.ApproachAction.FOLLOW_PLANNED_ROUTE, action);
            } else {
                assertEquals(StemClearingSweep.ApproachAction.INSPECT_TARGET, action);
            }
            assertTrue(attempt.tick());
            assertEquals(plannedNodes, attempt.nodesUsed(), "A target-chunk change must not restart search");
            if (waypoint > 0) {
                assertTrue(clearCorridor.test(feet));
                assertFalse(MinecraftFlightNavigation.sweptBody(
                        MinecraftFlightNavigation.center(route.get(waypoint - 1)),
                        MinecraftFlightNavigation.center(feet)).intersects(floor));
            }
        }
        assertTrue(unreceivedTargetSteps > 1, "The outside descent crosses the live target-receipt boundary");
        assertEquals(arrival, route.getLast());
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(MinecraftFlightNavigation.center(arrival),
                new BlockPos(target.x(), target.y(), target.z())));
        assertEquals(ticksAfterPlanning + route.size(), attempt.activeTicks());
        while (attempt.durationAvailable()) { assertTrue(attempt.tick()); }
        assertFalse(attempt.tick(), "Retained movement still expires under its original finite allowance");
    }

    @Test void retainedStemMovementNeverAuthorizesInteractionWithAnUnreceivedTarget() {
        BlockPos target = new BlockPos(8192, -62, -26923);
        Vec3d feet = new Vec3d(8193.5, -61.9, -26922.5);
        assertFalse(MinecraftFlightNavigation.interactionArrival(feet, feet.add(0, 1.62, 0),
                target, 4.15, target, false,
                body -> { throw new AssertionError("An unreceived target cannot reach body/interaction checks"); },
                () -> { throw new AssertionError("An unreceived target cannot request an attack ray"); }));
    }

    @Test void approachDoesNotQueryOccupancyButInteractionReadsFreshFacts() {
        AtomicInteger queries = new AtomicInteger();
        assertEquals("", StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.APPROACH, () -> {
            queries.incrementAndGet();
            return true;
        }));
        assertEquals(0, queries.get());
        assertFalse(StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.INTERACTION, () -> {
            queries.incrementAndGet();
            return true;
        }).isEmpty());
        assertEquals(1, queries.get());
        assertEquals("", StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.INTERACTION, () -> false));
    }

    @Test void reportedOverlappingStartCanRouteToAClearStemAttackPose() {
        BlockPos target = new BlockPos(8259, -62, -26892);
        Vec3d startingFeet = new Vec3d(8259.5, -61.9, -26891.6000);
        boolean overlaps = MinecraftFlightNavigation.body(startingFeet).intersects(new Box(target));
        assertTrue(overlaps, "The retained incident starts with the player inside the target cell");
        assertEquals("", StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.APPROACH, () -> overlaps));
        assertFalse(StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.INTERACTION, () -> overlaps).isEmpty());
        assertFalse(MinecraftFlightNavigation.interactionArrival(startingFeet, startingFeet.add(0, 1.62, 0),
                target, 4.15, target, true, body -> true, () -> true));

        VoxelShape stem = VoxelShapes.cuboid(0.375, 0, 0.375, 0.625, 0.125, 0.625);
        var start = new BlockPosition(8259, -62, -26892);
        var planner = new FlightRoutePlanner(start, start, 4.15 + 1.62, 16, 128,
                point -> point.y() == -62 && Math.abs(point.x() - start.x()) <= 2
                        && Math.abs(point.z() - start.z()) <= 2,
                point -> {
                    Vec3d feet = MinecraftFlightNavigation.center(point);
                    Vec3d eye = feet.add(0, 1.62, 0);
                    return MinecraftFlightNavigation.interactionArrival(feet, eye, target, 4.15, target, true,
                            body -> body.minY >= -62 && body.maxY < -60,
                            () -> ExactInteractionRay.trace(eye, target, null, 4.15, (x, z) -> true,
                                    (from, to) -> {
                                        BlockHitResult hit = stem.raycast(from, to, target);
                                        return hit == null ? BlockHitResult.createMissed(to, Direction.UP, target) : hit;
                                    }, () -> ExactInteractionRay.stemEndpoint(target, "minecraft:melon_stem", stem)).accepted());
                }, (from, to) -> true);
        for (int tick = 0; tick < 16 && !planner.complete() && !planner.failed(); tick++) { planner.tick(8); }
        assertTrue(planner.complete(), planner.detail());
        assertTrue(planner.path().size() > 1);
        Vec3d arrival = MinecraftFlightNavigation.center(planner.path().getLast());
        assertTrue(MinecraftFlightNavigation.clearsReservedPlacement(arrival, target));
        assertEquals("", StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.INTERACTION,
                () -> MinecraftFlightNavigation.body(arrival).intersects(new Box(target))));
    }

    @Test void anotherEntityOrUnreadableEntityFactsStillPreventStemInteraction() {
        assertFalse(StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.INTERACTION,
                () -> MossClearingEntityPolicy.hasConflict(false, List.of("living entity"),
                        ignored -> new MossClearingEntityPolicy.Facts(false, false, true, true, false, false)))
                .isEmpty());
        assertFalse(StemClearingSweep.occupancyProblem(StemClearingSweep.Stage.INTERACTION,
                () -> MossClearingEntityPolicy.hasConflict(false, null, ignored -> null)).isEmpty());
    }

    private static final List<String> STEMS = List.of("minecraft:pumpkin_stem", "minecraft:melon_stem",
            "minecraft:attached_pumpkin_stem", "minecraft:attached_melon_stem");
    private static final BlockPosition FLOOR = new BlockPosition(0, 0, 0);
    private static final BlockPosition CROP = new BlockPosition(0, 1, 0);

    @Test void allFourStemsAreAllowedAtImplicitAirAndDeferredWheatOnly() {
        SchematicPlan plan = farm();
        var sweep = new StemClearingSweep(plan, new LayerBuildSchedule(plan).entry(0).order());
        for (String id : STEMS) {
            BlockState stem = new BlockState(id);
            assertTrue(sweep.allows(CROP, stem));
            assertTrue(sweep.allows(new BlockPosition(1, 1, 0), stem));
            assertFalse(sweep.allows(FLOOR, stem), "A planned replacement uses its ordinary guarded path");
            assertFalse(sweep.allows(new BlockPosition(1, 0, 0), stem));
        }
    }

    @Test void neitherFruitNorOtherVegetationOrBlocksCanBorrowStemAuthorization() {
        var sweep = new StemClearingSweep(farm(), till());
        for (String id : List.of("minecraft:pumpkin", "minecraft:melon", "minecraft:carved_pumpkin",
                "minecraft:jack_o_lantern", "minecraft:wheat", "minecraft:moss_block",
                "minecraft:grass_block", "minecraft:short_grass", "minecraft:chest", "minecraft:air")) {
            assertFalse(StemClearingSweep.isStem(id));
            assertFalse(sweep.allows(CROP, new BlockState(id)));
        }
        assertFalse(StemClearingSweep.isStem(null));
    }

    @Test void tillSweepIncludesCropAboveAnAlreadyCorrectFarmlandTarget() {
        var sweep = new StemClearingSweep(farm(), till());
        assertTrue(sweep.allows(CROP, new BlockState("minecraft:melon_stem", Map.of("age", "7"))));
        assertTrue(positions(sweep).contains(CROP));
        assertEquals(4, sweep.size());
        // No manual-target correctness flag is an input to the pre-order sweep.
    }

    @Test void exactAgeFacingAndStemIdentityAreFrozenForOneAttempt() {
        var sweep = new StemClearingSweep(farm(), till());
        BlockState original = new BlockState("minecraft:pumpkin_stem", Map.of("age", "3"));
        var target = new StemClearingSweep.Target(CROP, original);
        assertTrue(sweep.matches(target, original));
        assertFalse(sweep.matches(target, new BlockState("minecraft:pumpkin_stem", Map.of("age", "4"))));
        assertFalse(sweep.matches(target, new BlockState("minecraft:melon_stem", Map.of("age", "3"))));
        assertFalse(sweep.matches(target, BlockState.AIR));
        var attached = new StemClearingSweep.Target(CROP,
                new BlockState("minecraft:attached_melon_stem", Map.of("facing", "south")));
        assertFalse(sweep.matches(attached,
                new BlockState("minecraft:attached_melon_stem", Map.of("facing", "north"))));
        assertThrows(IllegalArgumentException.class, () -> new StemClearingSweep.Target(CROP, BlockState.AIR));
    }

    @Test void cursorClipsNegativeCoordinatesAndNeverCrossesChunkOrSelectedBounds() {
        var volume = new BuildVolume(-17, -3, -2, -15, 5, 1);
        var target = new TargetBlock(new BlockPosition(-16, 1, -1), new BlockState("minecraft:dirt"));
        SchematicPlan plan = SchematicCompiler.compile(volume, List.of(target));
        WorkOrder order = new LayerBuildSchedule(plan).entry(0).order();
        var sweep = new StemClearingSweep(plan, order);
        var positions = positions(sweep);
        assertEquals(24, sweep.size()); // two X * two Z * six Y (-3 through 2)
        assertEquals(sweep.size(), positions.size());
        assertEquals(new BlockPosition(-16, -3, -2), sweep.position(0));
        assertEquals(new BlockPosition(-15, 2, -2), sweep.position(sweep.size() - 1));
        assertTrue(positions.stream().allMatch(position -> volume.contains(position) && order.chunk().contains(position)));
        assertThrows(IndexOutOfBoundsException.class, () -> sweep.position(sweep.size()));
        assertThrows(IndexOutOfBoundsException.class, () -> sweep.position(-1));
        for (BlockPosition outside : List.of(new BlockPosition(-17, 1, -1), new BlockPosition(-16, 3, -1),
                new BlockPosition(-16, 1, 0), new BlockPosition(-16, -4, -1))) {
            assertFalse(sweep.allows(outside, new BlockState(STEMS.getFirst())));
        }
    }

    @Test void observedFullRowReturnBecomesAnAdjacentTurnWithoutChangingLayer() {
        var volume = new BuildVolume(8224, -57, -26960, 8239, -56, -26945);
        SchematicPlan plan = SchematicCompiler.compile(volume, List.of(
                new TargetBlock(new BlockPosition(8224, -57, -26960), new BlockState("minecraft:dirt"))));
        var sweep = new StemClearingSweep(plan, new LayerBuildSchedule(plan).entry(0).order());
        int upperPlane = 16 * 16;
        assertEquals(new BlockPosition(8228, -56, -26945), sweep.position(upperPlane + 4 * 16 + 15));
        assertEquals(new BlockPosition(8229, -56, -26945), sweep.position(upperPlane + 5 * 16));
        assertEquals(new BlockPosition(8229, -56, -26960), sweep.position(upperPlane + 5 * 16 + 15));
        assertEquals(new BlockPosition(8230, -56, -26960), sweep.position(upperPlane + 6 * 16));
    }

    @Test void serpentineCoversClippedNegativeBoundsOnceAcrossThinOddAndEvenDimensions() {
        for (int width = 1; width <= 16; width++) {
            for (int depth = 1; depth <= 16; depth++) {
                for (int height = 1; height <= 3; height++) {
                    int minX = -width;
                    int minZ = -depth;
                    int minY = -3;
                    int maxY = minY + height - 1;
                    var volume = new BuildVolume(minX, minY, minZ, 2, maxY, 2);
                    SchematicPlan plan = SchematicCompiler.compile(volume, List.of(new TargetBlock(
                            new BlockPosition(minX, Math.max(minY, maxY - 1), minZ),
                            new BlockState("minecraft:dirt"))));
                    WorkOrder order = new LayerBuildSchedule(plan).entry(0).order();
                    var sweep = new StemClearingSweep(plan, order);
                    var reconstructed = new StemClearingSweep(plan, order);
                    String dimensions = width + "x" + depth + "x" + height;
                    var expected = new HashSet<BlockPosition>();
                    for (int y = minY; y <= maxY; y++) {
                        for (int x = minX; x < 0; x++) {
                            for (int z = minZ; z < 0; z++) {
                                expected.add(new BlockPosition(x, y, z));
                            }
                        }
                    }
                    assertEquals(expected.size(), sweep.size(), dimensions);
                    var visited = new HashSet<BlockPosition>();
                    BlockPosition previous = null;
                    for (int cursor = 0; cursor < sweep.size(); cursor++) {
                        BlockPosition position = sweep.position(cursor);
                        assertTrue(visited.add(position), dimensions + ": duplicate target");
                        assertTrue(volume.contains(position) && order.chunk().contains(position), dimensions);
                        assertEquals(position, reconstructed.position(cursor), dimensions + ": reconstruction");
                        if (previous != null) {
                            assertTrue(position.y() >= previous.y(), dimensions + ": bottom-up traversal");
                            if (position.y() == previous.y()) {
                                assertTrue(position.x() >= previous.x(), dimensions + ": ascending X rows");
                                assertEquals(1, manhattan(previous, position), dimensions + ": adjacent row turn");
                            } else {
                                assertEquals(previous.y() + 1, position.y(), dimensions + ": next layer");
                            }
                        }
                        previous = position;
                    }
                    assertEquals(expected, visited, dimensions);
                    assertEquals(new BlockPosition(minX, minY, minZ), sweep.position(0), dimensions);
                }
            }
        }
    }

    @Test void serpentineShortensDenseGridTargetCenterTravelWithoutMeasuringFlight() {
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 15, 0, 15),
                List.of(new TargetBlock(FLOOR, new BlockState("minecraft:dirt"))));
        var sweep = new StemClearingSweep(plan, new LayerBuildSchedule(plan).entry(0).order());
        var raster = new ArrayList<BlockPosition>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                raster.add(new BlockPosition(x, 0, z));
            }
        }
        int rasterDistance = 0;
        int sweepDistance = 0;
        for (int cursor = 1; cursor < sweep.size(); cursor++) {
            rasterDistance += manhattan(raster.get(cursor - 1), raster.get(cursor));
            sweepDistance += manhattan(sweep.position(cursor - 1), sweep.position(cursor));
        }
        assertEquals(480, rasterDistance);
        assertEquals(255, sweepDistance);
        // Dense target-center Manhattan distance excludes attack reach, obstacles and sparse targets.
    }

    @Test void aNewSweepAfterResumeIncludesLowerCellsAgainWithoutChangingSchedule() {
        SchematicPlan plan = farm();
        LayerBuildSchedule before = new LayerBuildSchedule(plan);
        var first = new StemClearingSweep(plan, before.entry(0).order());
        var resumed = new StemClearingSweep(plan, before.entry(0).order());
        assertEquals(first.position(0), resumed.position(0));
        assertEquals(positions(first), positions(resumed));
        assertEquals(before.entries(), new LayerBuildSchedule(plan).entries());
        assertEquals(LayerBuildSchedule.DEFERRED_PLANTING_ID, before.id());
        assertTrue(before.entries().stream().noneMatch(entry -> entry.order() instanceof WorkOrder.Plant));
        assertEquals(0, plan.plannedMaterials().get(Material.WHEAT_SEEDS));
    }

    @Test void sweepStopsAtOrderPlusOneAndDoesNotClaimAnUnscheduledAirCap() {
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 0, 10, 0),
                List.of(new TargetBlock(FLOOR, new BlockState("minecraft:dirt"))));
        var sweep = new StemClearingSweep(plan, new LayerBuildSchedule(plan).entry(0).order());
        assertEquals(2, sweep.size());
        assertFalse(sweep.allows(new BlockPosition(0, 10, 0), new BlockState(STEMS.getFirst())));
    }

    @Test void invalidOrderBindingsCannotAuthorizeAnotherChunkOrOutsideVolume() {
        SchematicPlan plan = farm();
        assertThrows(IllegalArgumentException.class, () -> new StemClearingSweep(plan,
                new WorkOrder.Till(0, new ChunkCoordinate(1, 0), List.of(new BlockPosition(16, 0, 0)))));
        assertThrows(IllegalArgumentException.class, () -> new StemClearingSweep(plan,
                new WorkOrder.Till(0, new ChunkCoordinate(0, 0), List.of(new BlockPosition(2, 0, 0)))));
    }

    @Test void removalReceiptRequiresAcknowledgedAirAndNeverCreditsMaterials() {
        var receipt = new FlightInteractionConfirmation(3, false, false, StemClearingSweep.CLEARING_BUDGET_TICKS);
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 3));
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 3));
        assertEquals(0, receipt.consumed());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 3));
        assertEquals(0, receipt.consumed());
        var rejected = new FlightInteractionConfirmation(0, false, false, 2);
        rejected.observe(false, false, 0);
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, rejected.observe(false, false, 0));
        assertEquals(0, rejected.consumed());
    }

    private static HashSet<BlockPosition> positions(StemClearingSweep sweep) {
        var result = new HashSet<BlockPosition>();
        for (int index = 0; index < sweep.size(); index++) { result.add(sweep.position(index)); }
        return result;
    }

    private static int manhattan(BlockPosition first, BlockPosition second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y())
                + Math.abs(first.z() - second.z());
    }

    private static SchematicPlan farm() {
        return SchematicCompiler.compile(new BuildVolume(0, 0, 0, 1, 1, 0), List.of(
                new TargetBlock(FLOOR, new BlockState("minecraft:farmland")),
                new TargetBlock(CROP, new BlockState("minecraft:wheat")),
                new TargetBlock(new BlockPosition(1, 0, 0), new BlockState("minecraft:birch_planks"))))
                .withPlantingDeferred(true);
    }

    private static WorkOrder till() { return new WorkOrder.Till(0, new ChunkCoordinate(0, 0), List.of(FLOOR)); }
}
