package io.github.schematicsupervisor.core;

import java.util.Objects;

public record TargetBlock(BlockPosition position, BlockState state) {
    public TargetBlock {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(state, "state");
        if (state.isAir()) {
            throw new IllegalArgumentException("air is not a build target");
        }
    }
}
