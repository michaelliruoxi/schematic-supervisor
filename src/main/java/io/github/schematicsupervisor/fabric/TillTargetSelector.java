package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.stream.IntStream;

/** Bounded nearby-first selection with stable target indices and a serpentine fallback. */
final class TillTargetSelector {
    private final List<BlockPosition> targets;
    private final int[] sweep;
    private int cursor;
    private int fallback = -1;
    private int reachable = -1;
    private double nearest = Double.POSITIVE_INFINITY;
    private boolean complete;
    private int invalid = -1;

    TillTargetSelector(List<BlockPosition> targets) {
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        Comparator<Integer> order = Comparator
                .comparingInt((Integer index) -> this.targets.get(index).y()).reversed()
                .thenComparingInt(index -> this.targets.get(index).x())
                .thenComparing((first, second) -> {
                    BlockPosition a = this.targets.get(first);
                    BlockPosition b = this.targets.get(second);
                    return (a.x() & 1) == 0 ? Integer.compare(a.z(), b.z())
                            : Integer.compare(b.z(), a.z());
                }).thenComparingInt(Integer::intValue);
        sweep = IntStream.range(0, this.targets.size()).boxed().sorted(order)
                .mapToInt(Integer::intValue).toArray();
    }

    void begin() {
        cursor = 0;
        fallback = -1;
        reachable = -1;
        nearest = Double.POSITIVE_INFINITY;
        complete = false;
        invalid = -1;
    }

    void scan(int budget, IntPredicate unfinished, IntPredicate canReach,
              IntToDoubleFunction distanceSquared) {
        scan(budget, unfinished, index -> true, canReach, distanceSquared);
    }

    void scan(int budget, IntPredicate unfinished, IntPredicate prerequisiteValid,
              IntPredicate canReach, IntToDoubleFunction distanceSquared) {
        if (budget < 1) { throw new IllegalArgumentException("selection budget must be positive"); }
        while (!complete && cursor < sweep.length && budget-- > 0) {
            int index = sweep[cursor++];
            // Even a reachable lower floor must wait until the highest unfinished floor is done.
            if (fallback >= 0 && targets.get(index).y() != targets.get(fallback).y()) {
                complete = true;
                break;
            }
            if (!unfinished.test(index)) { continue; }
            if (!prerequisiteValid.test(index)) {
                invalid = index;
                complete = true;
                break;
            }
            if (fallback < 0) { fallback = index; }
            if (!canReach.test(index)) { continue; }
            double distance = distanceSquared.applyAsDouble(index);
            if (Double.isFinite(distance) && distance >= 0 && distance < nearest) {
                reachable = index;
                nearest = distance;
            }
        }
        complete |= cursor == sweep.length;
    }

    boolean complete() { return complete; }

    int invalidIndex() { return invalid; }

    int selectedIndex() {
        if (!complete) { throw new IllegalStateException("target selection is still scanning"); }
        return invalid >= 0 ? -1 : reachable >= 0 ? reachable : fallback;
    }
}
