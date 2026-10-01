package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Finds the last clear feet cell above the first obstruction, without moving the player. */
final class FlightDescentProbe {
    private final int targetY;
    private final BiPredicate<BlockPosition, BlockPosition> clearSegment;
    private final List<BlockPosition> path = new ArrayList<>();
    private boolean complete;
    private boolean blocked;
    private int inspected;

    FlightDescentProbe(BlockPosition start, int targetY,
                       BiPredicate<BlockPosition, BlockPosition> clearSegment) {
        if (targetY >= start.y()) { throw new IllegalArgumentException("descent target must be below start"); }
        this.targetY = targetY;
        this.clearSegment = clearSegment;
        path.add(start);
    }

    void tick(int budget) {
        if (budget < 1) { throw new IllegalArgumentException("descent probe budget must be positive"); }
        for (int count = 0; count < budget && !complete; count++) {
            BlockPosition previous = path.getLast();
            BlockPosition next = new BlockPosition(previous.x(), previous.y() - 1, previous.z());
            inspected++;
            if (!clearSegment.test(previous, next)) {
                complete = true;
                blocked = true;
            } else {
                path.add(next);
                complete = next.y() <= targetY;
            }
        }
    }

    boolean complete() { return complete; }
    boolean blocked() { return blocked; }
    int inspected() { return inspected; }
    List<BlockPosition> path() {
        if (!complete) { throw new IllegalStateException("descent probe has not completed"); }
        return List.copyOf(path);
    }
    String detail() {
        BlockPosition last = path.getLast();
        return "Inspecting received descent column: checked=" + inspected + ", last clear feet=("
                + last.x() + "," + last.y() + "," + last.z() + ").";
    }
}
