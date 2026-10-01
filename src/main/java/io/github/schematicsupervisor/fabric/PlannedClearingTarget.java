package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import java.util.Objects;

/** Freezes one allowed obstruction and its exact planned replacement for an owned clearing attempt. */
record PlannedClearingTarget(OrdinaryPlacement placement, BlockState observedState) {
    PlannedClearingTarget {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(observedState, "observedState");
        if (!MossClearingPolicy.allowsReplacement(placement, observedState.blockId())) {
            throw new IllegalArgumentException("Unsupported planned clearing target");
        }
    }

    boolean matches(OrdinaryPlacement current, BlockState actual) {
        return placement.equals(current) && observedState.equals(actual);
    }

    boolean lightReplacement() {
        return "minecraft:jack_o_lantern".equals(observedState.blockId());
    }

    int tickBudget() {
        return MossClearingPolicy.clearingBudgetTicks(placement, observedState.blockId());
    }
}
