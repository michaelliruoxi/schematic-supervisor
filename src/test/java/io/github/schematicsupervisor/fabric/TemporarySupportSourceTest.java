package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.WorkOrder;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

final class TemporarySupportSourceTest {
    private static final BlockPosition ORIGIN = new BlockPosition(6_144, -63, -47_456);

    @Test
    void everyUnsupportedUpperFarmSliceHasAColumnWithoutUsingDeferredCropCells() throws IOException {
        LitematicSourceFixture.Source source = LitematicSourceFixture.read();
        SchematicSourceScan scan = new SchematicSourceScan(source.width(), source.height(), source.depth(),
                new SchematicSourceScan.Transform(ORIGIN, new BlockPosition(1, 0, 0),
                        new BlockPosition(0, 1, 0), new BlockPosition(0, 0, 1)),
                new BuildVolume(6_144, -63, -47_456, 6_255, 12, -47_345),
                source.expectedNonAir(), source::get);
        while (!scan.complete()) { scan.tick(4_096); }
        SchematicPlan plan = scan.result().withPlantingDeferred(true);
        assertEquals(156_800, plan.deferredSeedCells());
        Map<Integer, List<LayerBuildSchedule.Entry>> stages = new TreeMap<>();
        for (LayerBuildSchedule.Entry entry : new LayerBuildSchedule(plan).entries()) {
            stages.computeIfAbsent(entry.progress().ordinal(), ignored -> new java.util.ArrayList<>()).add(entry);
        }
        Map<BlockPosition, BlockState> completedLowerStages = new HashMap<>();
        BlockObservation received = observation(completedLowerStages);
        int unsupportedSlices = 0;
        int cropExclusionChecks = 0;
        for (List<LayerBuildSchedule.Entry> stage : stages.values()) {
            // Evaluate every chunk independently before installing its peers' same-layer side supports.
            for (LayerBuildSchedule.Entry entry : stage) {
                if (!(entry.order() instanceof WorkOrder.OrdinaryBlocks order)
                        || !entry.progress().stage().equals("STRUCTURE")
                        || entry.progress().y() == ORIGIN.y()) { continue; }
                boolean supported = order.placements().stream().anyMatch(placement ->
                        neighbors(placement.position()).stream().anyMatch(completedLowerStages::containsKey));
                if (supported) { continue; }
                unsupportedSlices++;
                var planned = TemporarySupportPlanner.find(plan, order, received);
                assertTrue(planned.isPresent(), "Missing legal column at layer " + entry.progress().y()
                        + ", chunk " + order.chunkIndex());
                var column = planned.orElseThrow();
                assertTrue(order.placements().contains(column.seed()));
                assertEquals(TemporarySupportPlanner.sliceId(order), column.sliceId());
                for (BlockPosition support : column.supports()) {
                    assertEquals(BlockState.AIR, sourceAt(source, support),
                            "Temporary support would occupy a final crop or light cell at " + support);
                }
                OrdinaryPlacement cropColumn = order.placements().stream().filter(placement ->
                        sourceAt(source, new BlockPosition(placement.position().x(),
                                placement.position().y() - 2, placement.position().z())).blockId()
                                .equals("minecraft:wheat")).findFirst().orElseThrow();
                BlockPosition cropAnchor = new BlockPosition(cropColumn.position().x(),
                        cropColumn.position().y() - 3, cropColumn.position().z());
                BlockState anchorState = completedLowerStages.get(cropAnchor);
                assertFalse(anchorState == null || anchorState.isAir());
                assertTrue(TemporarySupportPlanner.find(plan, order,
                        observation(Map.of(cropAnchor, anchorState))).isEmpty(),
                        "Deferred planting must not convert final wheat cells into scaffold permission");
                cropExclusionChecks++;
            }
            for (LayerBuildSchedule.Entry entry : stage) {
                if (entry.order() instanceof WorkOrder.OrdinaryBlocks order) {
                    order.placements().forEach(placement -> completedLowerStages.put(
                            placement.position(), placement.state()));
                }
            }
        }
        assertEquals(25 * 49, unsupportedSlices);
        assertEquals(unsupportedSlices, cropExclusionChecks);
    }

    private static BlockObservation observation(Map<BlockPosition, BlockState> placed) {
        return new BlockObservation() {
            @Override public boolean isChunkLoaded(ChunkCoordinate ignored) { return true; }
            @Override public BlockState blockState(BlockPosition position) {
                return placed.getOrDefault(position, BlockState.AIR);
            }
        };
    }

    private static BlockState sourceAt(LitematicSourceFixture.Source source, BlockPosition position) {
        return source.get(position.x() - ORIGIN.x(), position.y() - ORIGIN.y(), position.z() - ORIGIN.z());
    }

    private static List<BlockPosition> neighbors(BlockPosition position) {
        return List.of(new BlockPosition(position.x() - 1, position.y(), position.z()),
                new BlockPosition(position.x() + 1, position.y(), position.z()),
                new BlockPosition(position.x(), position.y() - 1, position.z()),
                new BlockPosition(position.x(), position.y() + 1, position.z()),
                new BlockPosition(position.x(), position.y(), position.z() - 1),
                new BlockPosition(position.x(), position.y(), position.z() + 1));
    }
}
