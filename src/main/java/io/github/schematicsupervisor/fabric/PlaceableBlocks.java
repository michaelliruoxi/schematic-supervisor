package io.github.schematicsupervisor.fabric;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

/**
 * Which schematic blocks the builder places the way it places dirt: a full cube with no properties to
 * orient or set, no block entity, no gravity, and an item that places exactly that block.
 */
final class PlaceableBlocks {
    private PlaceableBlocks() {
    }

    /** Why the block can't be placed like dirt, or blank when it can. */
    static String problem(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return "is not a known block";
        }
        if (SurplusPickupPolicy.allowed(blockId)) {
            // Pickup storage and disposal remove these from the inventory as clearing waste.
            return "is collected as clearing waste, so the mod would store or discard its supply";
        }
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        if (!state.getProperties().isEmpty()) {
            return "needs a direction or another block property";
        }
        if (block instanceof BlockEntityProvider) {
            return "stores block entity data";
        }
        if (block instanceof FallingBlock) {
            return "falls when placed";
        }
        if (!state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
            return "is not a full block";
        }
        Item item = block.asItem();
        if (!(item instanceof BlockItem placing) || placing.getBlock() != block) {
            return "has no item that places it";
        }
        return "";
    }
}
