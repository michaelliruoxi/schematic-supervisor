package io.github.schematicsupervisor.core;

import java.util.Objects;

public record OrdinaryPlacement(BlockPosition position, BlockState state, Material material) {
    public OrdinaryPlacement {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(material, "material");
        if (state.isAir()) {
            throw new IllegalArgumentException("ordinary placements cannot place air");
        }
        if (!material.placedAsBlock()) {
            throw new IllegalArgumentException("ordinary placement requires a block material");
        }
    }
}
