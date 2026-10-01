package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SparsePlacementMaskTest {
    @Test
    void singleLayerBoundsPreserveNegativeWorldOriginAndExcludeAdjacentPlanes() {
        OrdinaryPlacement dirt = placement(-31, -60, -15, Material.DIRT);
        OrdinaryPlacement planks = placement(-17, -60, -1, Material.BIRCH_PLANKS);
        WorkOrder.OrdinaryBlocks order = order(new ChunkCoordinate(-2, -1), dirt, planks);

        SparsePlacementMask mask = new SparsePlacementMask(order, order.placements(), -64, 319);

        assertEquals(-32, mask.originX());
        assertEquals(-60, mask.originY());
        assertEquals(-16, mask.originZ());
        assertEquals(16, mask.widthX());
        assertEquals(1, mask.heightY());
        assertEquals(16, mask.lengthZ());
        assertEquals(Material.DIRT, mask.materialAt(1, 0, 1));
        assertEquals(Material.BIRCH_PLANKS, mask.materialAt(15, 0, 15));
        assertNull(mask.materialAt(1, -1, 1));
        assertNull(mask.materialAt(1, 1, 1));
        assertNull(mask.materialAt(2, 0, 1));
    }

    @Test
    void retryMaskExcludesConfirmedFarmlandAndNarrowsToRemainingStructure() {
        OrdinaryPlacement farmlandDirt = placement(0, 64, 0, Material.DIRT);
        OrdinaryPlacement glowstone = placement(0, 66, 0, Material.GLOWSTONE);
        OrdinaryPlacement upperDirt = placement(0, 67, 0, Material.DIRT);
        WorkOrder.OrdinaryBlocks order = order(
                new ChunkCoordinate(0, 0), farmlandDirt, glowstone, upperDirt
        );

        SparsePlacementMask mask = new SparsePlacementMask(order, List.of(glowstone), 64, 139);

        assertEquals(66, mask.originY());
        assertEquals(1, mask.heightY());
        assertEquals(Material.GLOWSTONE, mask.materialAt(0, 0, 0));
        assertNull(mask.materialAt(0, -2, 0));
        assertNull(mask.materialAt(0, 1, 0));
    }

    @Test
    void sparseMultiLayerWorkDoesNotClaimAirOrOtherChunks() {
        OrdinaryPlacement lower = placement(0, 64, 0, Material.DIRT);
        OrdinaryPlacement upper = placement(15, 67, 15, Material.DIRT);
        WorkOrder.OrdinaryBlocks order = order(new ChunkCoordinate(0, 0), lower, upper);
        SparsePlacementMask mask = new SparsePlacementMask(order, order.placements(), 64, 139);

        assertEquals(4, mask.heightY());
        assertEquals(Material.DIRT, mask.materialAt(0, 0, 0));
        assertEquals(Material.DIRT, mask.materialAt(15, 3, 15));
        assertNull(mask.materialAt(0, 1, 0));
        assertNull(mask.materialAt(0, 2, 0));
        assertNull(mask.materialAt(-1, 0, 0));
        assertNull(mask.materialAt(16, 0, 0));
        assertNull(mask.materialAt(0, 0, -1));
        assertNull(mask.materialAt(0, 0, 16));
        assertNull(mask.materialAt(65_536, 0, 0));
        assertNull(mask.materialAt(0, 65_536, 0));
        assertNull(mask.materialAt(0, 0, 65_536));
    }

    @Test
    void rejectsRemainingPlacementOutsideTheWorkOrderOrWithChangedMaterial() {
        OrdinaryPlacement dirt = placement(0, 64, 0, Material.DIRT);
        WorkOrder.OrdinaryBlocks order = order(new ChunkCoordinate(0, 0), dirt);

        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                order, List.of(placement(0, 65, 0, Material.DIRT)), 64, 139
        ));
        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                order, List.of(placement(0, 64, 0, Material.GLOWSTONE)), 64, 139
        ));
    }

    @Test
    void rejectsInvalidChunkHeightEmptyOrDuplicateTargets() {
        OrdinaryPlacement dirt = placement(0, 64, 0, Material.DIRT);
        WorkOrder.OrdinaryBlocks order = order(new ChunkCoordinate(0, 0), dirt);
        WorkOrder.OrdinaryBlocks wrongChunk = order(new ChunkCoordinate(1, 0), dirt);

        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                wrongChunk, wrongChunk.placements(), 64, 139
        ));
        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                order, order.placements(), 65, 139
        ));
        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                order, order.placements(), 64, 63
        ));
        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                order, List.of(), 64, 139
        ));
        assertThrows(IllegalArgumentException.class, () -> new SparsePlacementMask(
                order, List.of(dirt, dirt), 64, 139
        ));
    }

    @Test
    void manualOrderingAndMaterialBatchingStayInsideSmallLayerSlices() {
        List<BlockPosition> targets = List.of(
                new BlockPosition(15, 67, 15),
                new BlockPosition(0, 67, 0)
        );

        assertEquals(List.of(targets.get(1), targets.get(0)), TopDownTargetOrder.copyOf(targets));
        assertEquals(2, RestockBatchPolicy.targetAvailable(targets.size(), 10_000, 2_304, 64, 0));
        assertEquals(1, RestockBatchPolicy.targetAvailable(1, 10_000, 2_304, 64, 0));
    }

    private static WorkOrder.OrdinaryBlocks order(
            ChunkCoordinate chunk,
            OrdinaryPlacement... placements
    ) {
        return new WorkOrder.OrdinaryBlocks(0, chunk, List.of(placements));
    }

    private static OrdinaryPlacement placement(int x, int y, int z, Material material) {
        if (!material.placedAsBlock()) {
            throw new IllegalArgumentException("test expects an ordinary material");
        }
        String blockId = material.itemId();
        return new OrdinaryPlacement(new BlockPosition(x, y, z), new BlockState(blockId), material);
    }
}
