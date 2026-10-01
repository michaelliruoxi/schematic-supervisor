package io.github.schematicsupervisor.fabric;

import baritone.api.schematic.ISchematic;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.WorkOrder;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * A sparse Baritone schematic: positions outside the deterministic work order never matter.
 */
final class SparseBaritoneSchematic implements ISchematic {
    private final SparsePlacementMask mask;

    SparseBaritoneSchematic(
            WorkOrder.OrdinaryBlocks order,
            List<OrdinaryPlacement> remainingPlacements,
            int minimumY,
            int maximumY
    ) {
        mask = new SparsePlacementMask(order, remainingPlacements, minimumY, maximumY);
    }

    int originX() {
        return mask.originX();
    }

    int originY() {
        return mask.originY();
    }

    int originZ() {
        return mask.originZ();
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        return mask.materialAt(x, y, z) != null;
    }

    @Override
    public BlockState desiredState(
            int x,
            int y,
            int z,
            BlockState current,
            List<BlockState> approximatelyPlaceable
    ) {
        Material material = mask.materialAt(x, y, z);
        return material == null ? current : materialState(material);
    }

    @Override
    public int widthX() {
        return mask.widthX();
    }

    @Override
    public int heightY() {
        return mask.heightY();
    }

    @Override
    public int lengthZ() {
        return mask.lengthZ();
    }

    private static BlockState materialState(Material material) {
        if (!material.placedAsBlock()) {
            throw new IllegalArgumentException("material is not an ordinary block: " + material);
        }
        return MinecraftMaterials.block(material).getDefaultState();
    }
}
