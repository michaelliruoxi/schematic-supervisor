package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class TopDownTargetOrder {
    private static final Comparator<BlockPosition> COMPARATOR =
            Comparator.comparingInt(BlockPosition::y)
                    .reversed()
                    .thenComparingInt(BlockPosition::x)
                    .thenComparingInt(BlockPosition::z);

    private TopDownTargetOrder() {
    }

    static List<BlockPosition> copyOf(List<BlockPosition> targets) {
        Objects.requireNonNull(targets, "targets");
        ArrayList<BlockPosition> ordered = new ArrayList<>(targets.size());
        for (BlockPosition target : targets) {
            ordered.add(Objects.requireNonNull(target, "target"));
        }
        ordered.sort(COMPARATOR);
        return List.copyOf(ordered);
    }
}
