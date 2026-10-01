package io.github.schematicsupervisor.fabric;

import net.minecraft.util.math.Direction;

/** A known noninteractive block and a supported face are both required before routing a click. */
final class PlacementAnchorPolicy {
    record Facts(boolean plainBlock, boolean mossBlock, boolean exactFarmland,
                 boolean fullCube, boolean blockEntity, boolean replaceable, boolean fluid) { }

    private PlacementAnchorPolicy() { }

    static boolean permits(Facts facts, Direction face) {
        if (facts == null || face == null || facts.blockEntity() || facts.replaceable() || facts.fluid()) {
            return false;
        }
        if ((facts.plainBlock() || facts.mossBlock()) && facts.fullCube()) { return true; }
        // Farmland has a 15/16-height outline. The exact ray still has to reach the requested face.
        // Placing a solid block above can invalidate farmland; placement beneath does not change that condition.
        return facts.exactFarmland() && (face.getAxis().isHorizontal() || face == Direction.DOWN);
    }
}
