package io.github.schematicsupervisor.core;

import java.util.Objects;

/** Whether an observed block already satisfies a planned ordinary placement. */
public final class PlacementAcceptance {
    private static final String DIRT_BLOCK_ID = "minecraft:dirt";
    private static final String FARMLAND_BLOCK_ID = "minecraft:farmland";

    private PlacementAcceptance() {
    }

    /** Planned dirt that is also a till target stays satisfied after it becomes farmland. */
    public static boolean satisfied(OrdinaryPlacement placement, BlockState actual, boolean tillPrerequisite) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(actual, "actual");
        return BlockStateNormalizer.equivalent(placement.state(), actual)
                || tillPrerequisite
                && placement.material() == Material.DIRT
                && DIRT_BLOCK_ID.equals(placement.state().blockId())
                && FARMLAND_BLOCK_ID.equals(actual.blockId());
    }
}
