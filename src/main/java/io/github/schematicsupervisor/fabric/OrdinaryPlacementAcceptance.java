package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BlockStateNormalizer;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.PlacementAcceptance;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

final class OrdinaryPlacementAcceptance {
    private OrdinaryPlacementAcceptance() {
    }

    static boolean isSatisfied(
            OrdinaryPlacement placement,
            BlockState actual,
            boolean tillPrerequisite
    ) {
        // The start build check uses the same rule, so it skips exactly what this executor accepts.
        return PlacementAcceptance.satisfied(placement, actual, tillPrerequisite);
    }

    static boolean confirmsMaterialConsumption(
            OrdinaryPlacement placement,
            BlockState actual
    ) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(actual, "actual");
        return BlockStateNormalizer.equivalent(placement.state(), actual);
    }

    static List<OrdinaryPlacement> remaining(
            List<OrdinaryPlacement> placements,
            BitSet satisfied
    ) {
        Objects.requireNonNull(placements, "placements");
        Objects.requireNonNull(satisfied, "satisfied");
        ArrayList<OrdinaryPlacement> remaining = new ArrayList<>();
        for (int index = 0; index < placements.size(); index++) {
            OrdinaryPlacement placement = Objects.requireNonNull(
                    placements.get(index),
                    "placement"
            );
            if (!satisfied.get(index)) {
                remaining.add(placement);
            }
        }
        return List.copyOf(remaining);
    }

    static List<OrdinaryPlacement> remaining(
            List<OrdinaryPlacement> placements,
            Predicate<BlockPosition> tillPrerequisite,
            Function<BlockPosition, BlockState> actualState
    ) {
        Objects.requireNonNull(placements, "placements");
        Objects.requireNonNull(tillPrerequisite, "tillPrerequisite");
        Objects.requireNonNull(actualState, "actualState");
        BitSet satisfied = new BitSet(placements.size());
        for (int index = 0; index < placements.size(); index++) {
            OrdinaryPlacement placement = Objects.requireNonNull(
                    placements.get(index),
                    "placement"
            );
            BlockState actual = Objects.requireNonNull(
                    actualState.apply(placement.position()),
                    "actual state"
            );
            if (isSatisfied(
                    placement,
                    actual,
                    tillPrerequisite.test(placement.position())
            )) {
                satisfied.set(index);
            }
        }
        return remaining(placements, satisfied);
    }
}
