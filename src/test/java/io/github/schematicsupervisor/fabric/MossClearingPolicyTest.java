package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MossClearingPolicyTest {
    @Test void fourStemsMayBeRemovedOnlyAtGuardedExactOrdinaryTargets() {
        for (String id : List.of("minecraft:pumpkin_stem", "minecraft:melon_stem",
                "minecraft:attached_pumpkin_stem", "minecraft:attached_melon_stem")) {
            var placement = placement(Material.DIRT, new BlockState("minecraft:dirt"));
            assertTrue(MossClearingPolicy.allowsReplacement(placement, id));
            assertEquals(100, MossClearingPolicy.clearingBudgetTicks(placement, id));
            assertEquals("", check(TARGET, id, true, false, false, false, true));
            assertFalse(check(TARGET, id, false, false, false, false, true).isEmpty());
            assertFalse(check(TARGET, id, true, true, false, false, true).isEmpty());
            assertFalse(check(TARGET, id, true, false, true, false, true).isEmpty());
            assertFalse(check(TARGET, id, true, false, false, true, true).isEmpty());
            assertFalse(check(TARGET, id, true, false, false, false, false).isEmpty());
            assertFalse(check(new BlockPosition(1, 0, 0), id, true, false, false, false, true).isEmpty());
        }
    }

    private static final BlockPosition TARGET = new BlockPosition(0, 0, 0);
    private static final BuildVolume VOLUME = new BuildVolume(0, 0, 0, 15, 3, 15);
    private static final WorkOrder.OrdinaryBlocks ORDER = order(TARGET);

    @Test void permitsReceivedMossOnlyWhenItsExactReplacementIsAvailable() {
        assertEquals("", check(TARGET, "minecraft:moss_block", true, false, false, false, true));
        assertTrue(check(TARGET, "minecraft:moss_block", true, false, false, false, false)
                .contains("replacement"));
    }

    @Test void neverClearsAnotherLayerOrAnUnplannedPosition() {
        assertFalse(check(new BlockPosition(0, 3, 0), "minecraft:moss_block", true,
                false, false, false, true).isEmpty());
        assertFalse(check(new BlockPosition(1, 0, 0), "minecraft:moss_block", true,
                false, false, false, true).isEmpty());
    }

    @Test void rejectsOutOfVolumeOrWrongChunkEvenIfAnOrderContainsIt() {
        BlockPosition outside = new BlockPosition(16, 0, 0);
        var observed = new MossClearingPolicy.Observation(outside, "minecraft:moss_block",
                true, false, false, false, true);
        assertFalse(MossClearingPolicy.rejection(VOLUME, order(outside), observed).isEmpty());
        BuildVolume wider = new BuildVolume(0, 0, 0, 31, 3, 15);
        assertFalse(MossClearingPolicy.rejection(wider, order(outside), observed).isEmpty());
    }

    @Test void refusesOtherBlocksAndUnreceivedPlaceholderData() {
        for (String block : List.of("minecraft:stone", "minecraft:chest", "minecraft:dirt", "minecraft:void_air")) {
            assertFalse(check(TARGET, block, true, false, false, false, true).isEmpty());
        }
        assertFalse(check(TARGET, "minecraft:moss_block", false, false, false, false, true).isEmpty());
    }

    @Test void protectsEntitiesContainersAndFluid() {
        assertFalse(check(TARGET, "minecraft:moss_block", true, true, false, false, true).isEmpty());
        assertFalse(check(TARGET, "minecraft:moss_block", true, false, true, false, true).isEmpty());
        assertFalse(check(TARGET, "minecraft:moss_block", true, false, false, true, true).isEmpty());
    }

    @Test void manualFarmingCannotRequestTerrainClearing() {
        var till = new WorkOrder.Till(0, new ChunkCoordinate(0, 0), List.of(TARGET));
        var observed = new MossClearingPolicy.Observation(TARGET, "minecraft:moss_block",
                true, false, false, false, true);
        assertFalse(MossClearingPolicy.rejection(VOLUME, till, observed).isEmpty());
    }

    @Test void jackOLanternIsAllowedOnlyAtTheExactPlannedGlowstoneReplacement() {
        OrdinaryPlacement glowstone = placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone"));
        var order = new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(glowstone));
        assertTrue(MossClearingPolicy.allowsReplacement(glowstone, "minecraft:jack_o_lantern"));
        assertEquals("", MossClearingPolicy.rejection(VOLUME, order,
                jack(TARGET, true, false, false, false, true)));
        assertEquals(240, MossClearingPolicy.clearingBudgetTicks(glowstone, "minecraft:jack_o_lantern"));
    }

    @Test void jackReplacementRequiresBothPlannedMaterialAndExactExpectedBlockState() {
        for (OrdinaryPlacement wrong : List.of(
                placement(Material.DIRT, new BlockState("minecraft:glowstone")),
                placement(Material.BIRCH_PLANKS, new BlockState("minecraft:glowstone")),
                placement(Material.GLOWSTONE, new BlockState("minecraft:dirt")),
                placement(Material.GLOWSTONE, new BlockState("minecraft:jack_o_lantern", Map.of("facing", "south"))),
                placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone", Map.of("unexpected", "property"))))) {
            assertFalse(MossClearingPolicy.allowsReplacement(wrong, "minecraft:jack_o_lantern"));
            assertThrows(IllegalArgumentException.class,
                    () -> MossClearingPolicy.clearingBudgetTicks(wrong, "minecraft:jack_o_lantern"));
            var order = new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(wrong));
            assertFalse(MossClearingPolicy.rejection(VOLUME, order,
                    jack(TARGET, true, false, false, false, true)).isEmpty());
        }
    }

    @Test void otherObstructionsNeverInheritTheLongerClearingBudget() {
        OrdinaryPlacement glowstone = placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone"));
        for (String block : List.of("minecraft:pumpkin", "minecraft:carved_pumpkin", "minecraft:glowstone",
                "minecraft:stone", "minecraft:chest", "minecraft:dirt", "minecraft:air", "unknown")) {
            assertFalse(MossClearingPolicy.allowsReplacement(glowstone, block));
            assertThrows(IllegalArgumentException.class, () -> MossClearingPolicy.clearingBudgetTicks(glowstone, block));
        }
        assertFalse(MossClearingPolicy.allowsReplacement(glowstone, null));
        assertFalse(MossClearingPolicy.allowsReplacement(null, "minecraft:jack_o_lantern"));
        assertThrows(IllegalArgumentException.class, () -> MossClearingPolicy.clearingBudgetTicks(glowstone, null));
        assertThrows(IllegalArgumentException.class,
                () -> MossClearingPolicy.clearingBudgetTicks(null, "minecraft:moss_block"));
    }

    @Test void jackReplacementRetainsEveryReceivedSafetyAndMaterialGuard() {
        var order = glowstoneOrder(TARGET);
        for (var unsafe : List.of(
                jack(TARGET, false, false, false, false, true),
                jack(TARGET, true, true, false, false, true),
                jack(TARGET, true, false, true, false, true),
                jack(TARGET, true, false, false, true, true),
                jack(TARGET, true, false, false, false, false))) {
            assertFalse(MossClearingPolicy.rejection(VOLUME, order, unsafe).isEmpty());
        }
    }

    @Test void jackReplacementCannotClearAnotherPositionLayerChunkOrVolume() {
        for (BlockPosition outsideOrder : List.of(new BlockPosition(1, 0, 0), new BlockPosition(0, 1, 0),
                new BlockPosition(16, 0, 0))) {
            assertFalse(MossClearingPolicy.rejection(VOLUME, glowstoneOrder(TARGET),
                    jack(outsideOrder, true, false, false, false, true)).isEmpty());
        }
        BlockPosition wrongChunk = new BlockPosition(16, 0, 0);
        BuildVolume wider = new BuildVolume(0, 0, 0, 31, 3, 15);
        assertFalse(MossClearingPolicy.rejection(wider, glowstoneOrder(wrongChunk),
                jack(wrongChunk, true, false, false, false, true)).isEmpty());
        BlockPosition outsideVolume = new BlockPosition(0, 4, 0);
        assertFalse(MossClearingPolicy.rejection(VOLUME, glowstoneOrder(outsideVolume),
                jack(outsideVolume, true, false, false, false, true)).isEmpty());
        WorkOrder till = new WorkOrder.Till(0, new ChunkCoordinate(0, 0), List.of(TARGET));
        assertFalse(MossClearingPolicy.rejection(VOLUME, till,
                jack(TARGET, true, false, false, false, true)).isEmpty());
    }

    @Test void conflictingPlansAtTheSamePositionCannotAuthorizeJackReplacement() {
        var order = new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(
                placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone")),
                placement(Material.DIRT, new BlockState("minecraft:dirt"))));
        assertFalse(MossClearingPolicy.rejection(VOLUME, order,
                jack(TARGET, true, false, false, false, true)).isEmpty());
    }

    @Test void allExistingMossReplacementMaterialsRetainTheirHundredTickBudget() {
        for (OrdinaryPlacement existing : List.of(
                placement(Material.DIRT, new BlockState("minecraft:dirt")),
                placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone")),
                placement(Material.BIRCH_PLANKS, new BlockState("minecraft:birch_planks")))) {
            assertTrue(MossClearingPolicy.allowsReplacement(existing, "minecraft:moss_block"));
            assertEquals(100, MossClearingPolicy.clearingBudgetTicks(existing, "minecraft:moss_block"));
            var order = new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(existing));
            assertEquals("", MossClearingPolicy.rejection(VOLUME, order,
                    new MossClearingPolicy.Observation(TARGET, "minecraft:moss_block", true,
                            false, false, false, true)));
        }
    }

    @Test void jackBudgetCoversPinnedAirbornePlainHandTimingAndStillRequiresAnAirReceipt() {
        var placement = placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone"));
        var receipt = new FlightInteractionConfirmation(8, false, false,
                MossClearingPolicy.clearingBudgetTicks(placement, "minecraft:jack_o_lantern"));
        var mossBudget = new FlightInteractionConfirmation(8, false, false,
                MossClearingPolicy.clearingBudgetTicks(placement, "minecraft:moss_block"));
        // Timing fixture from pinned 1.21.8 bytecode: jack hardness 1, airborne speed / 5, harvest divisor 30.
        // This exercises the production receipt with that timing; it does not bootstrap a live Minecraft world.
        float delta = 1.0f / 5.0f / 1.0f / 30.0f;
        float progress = 0;
        int ticks = 0;
        while (progress < 1.0f) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 8));
            mossBudget.observe(false, false, 8);
            progress += delta;
            ticks++;
        }
        assertTrue(ticks >= 150 && ticks <= 152);
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, mossBudget.result());
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 8));
        assertEquals(0, receipt.consumed());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 8));
        assertEquals(0, receipt.consumed(), "Clearing a light never credits a Glowstone placement");
    }

    @Test void jackReceiptCorrectionAndTimeoutNeverAuthorizeReplacement() {
        var placement = placement(Material.GLOWSTONE, new BlockState("minecraft:glowstone"));
        int budget = MossClearingPolicy.clearingBudgetTicks(placement, "minecraft:jack_o_lantern");
        var correction = new FlightInteractionConfirmation(8, false, false, budget);
        assertEquals(FlightInteractionConfirmation.Result.WAITING, correction.observe(true, true, 8));
        for (int tick = 1; tick < budget; tick++) { correction.observe(false, false, 8); }
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, correction.result());
        assertEquals(0, correction.consumed());
        var pending = new FlightInteractionConfirmation(8, false, false, budget);
        for (int tick = 0; tick < budget; tick++) { pending.observe(true, true, 8); }
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, pending.result());
        assertEquals(0, pending.consumed());
    }

    private static MossClearingPolicy.Observation jack(BlockPosition target, boolean received,
                                                       boolean blockEntity, boolean fluid,
                                                       boolean entities, boolean available) {
        return new MossClearingPolicy.Observation(target, "minecraft:jack_o_lantern", received,
                blockEntity, fluid, entities, available);
    }

    private static OrdinaryPlacement placement(Material material, BlockState state) {
        return new OrdinaryPlacement(TARGET, state, material);
    }

    private static WorkOrder.OrdinaryBlocks glowstoneOrder(BlockPosition target) {
        return new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(new OrdinaryPlacement(
                target, new BlockState("minecraft:glowstone"), Material.GLOWSTONE)));
    }

    private static String check(BlockPosition target, String block, boolean received,
                                boolean blockEntity, boolean fluid, boolean entities, boolean available) {
        return MossClearingPolicy.rejection(VOLUME, ORDER, new MossClearingPolicy.Observation(
                target, block, received, blockEntity, fluid, entities, available));
    }

    private static WorkOrder.OrdinaryBlocks order(BlockPosition target) {
        return new WorkOrder.OrdinaryBlocks(0, new ChunkCoordinate(0, 0), List.of(new OrdinaryPlacement(
                target, new BlockState("minecraft:dirt", Map.of()), Material.DIRT)));
    }
}
