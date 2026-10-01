package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

final class PlacementSourceTransformTest {
    @Test
    void negativeSizeFarmRegionMapsTheWholeSourceToItsPlacedMinimumCorner() {
        SchematicSourceScan.Transform transform = PlacementPlanLoadSession.sourceTransform(
                new BlockPos(-32, -63, -128), new BlockPos(111, 0, 111), new BlockPos(-112, 76, -112),
                BlockMirror.NONE, BlockRotation.NONE, BlockMirror.NONE, BlockRotation.NONE);
        assertEquals(new BlockPosition(-32, -63, -128), transform.apply(0, 0, 0));
        assertEquals(new BlockPosition(79, 12, -17), transform.apply(111, 75, 111));
        assertEquals(new BuildVolume(-32, -63, -128, 79, 12, -17), transform.bounds(112, 76, 112));
    }

    @Test
    void combinedMainAndSubregionMirrorsAndRotationsKeepTheMainRegionTranslation() {
        SchematicSourceScan.Transform transform = PlacementPlanLoadSession.sourceTransform(
                new BlockPos(100, -63, 200), new BlockPos(10, 7, -2), new BlockPos(3, 2, 5),
                BlockMirror.FRONT_BACK, BlockRotation.CLOCKWISE_90,
                BlockMirror.LEFT_RIGHT, BlockRotation.COUNTERCLOCKWISE_90);
        assertEquals(new BlockPosition(102, -56, 190), transform.apply(0, 0, 0));
        assertEquals(new BlockPosition(104, -55, 194), transform.apply(2, 1, 4));
        assertEquals(new BuildVolume(102, -56, 190, 104, -55, 194), transform.bounds(3, 2, 5));
    }

    @Test
    void negativeSizesOnAllAxesWorkWithQuarterTurnAndSubregionMirror() {
        SchematicSourceScan.Transform transform = PlacementPlanLoadSession.sourceTransform(
                new BlockPos(100, -63, 200), new BlockPos(10, 7, -2), new BlockPos(-3, -2, -5),
                BlockMirror.NONE, BlockRotation.CLOCKWISE_90, BlockMirror.FRONT_BACK, BlockRotation.NONE);
        assertEquals(new BlockPosition(98, -57, 208), transform.apply(0, 0, 0));
        assertEquals(new BlockPosition(102, -56, 210), transform.apply(2, 1, 4));
        assertEquals(new BuildVolume(98, -57, 208, 102, -56, 210), transform.bounds(3, 2, 5));
    }
}
