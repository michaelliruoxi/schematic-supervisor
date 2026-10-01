package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Tries a finite sequence of higher clearance planes when buildings enclose the lower plane. */
final class FlightDescentEscape {
    private static final int[] HEIGHTS_ABOVE_BARRIER = {8, 16, 32, 64};
    private final int barrierClearanceY;
    private final int maximumFeetY;
    private int nextHeight;
    private boolean blocked;
    private String detail = "No higher descent-search plane has been requested.";

    FlightDescentEscape(int barrierClearanceY, int maximumFeetY) {
        this.barrierClearanceY = barrierClearanceY;
        this.maximumFeetY = maximumFeetY;
    }

    List<BlockPosition> nextPath(BlockPosition current,
                                 BiPredicate<BlockPosition, BlockPosition> clearSegment) {
        if (blocked) { return List.of(); }
        while (nextHeight < HEIGHTS_ABOVE_BARRIER.length) {
            int height = HEIGHTS_ABOVE_BARRIER[nextHeight++];
            int nextY = (int) Math.min((long) maximumFeetY, (long) barrierClearanceY + height);
            if (nextY <= current.y()) { continue; }
            BlockPosition next = new BlockPosition(current.x(), nextY, current.z());
            if (!clearSegment.test(current, next)) {
                blocked = true;
                detail = "No received collision-free upward escape to clearance Y=" + nextY + ".";
                return List.of();
            }
            ArrayList<BlockPosition> route = new ArrayList<>();
            for (int y = current.y(); y <= nextY; y++) {
                route.add(new BlockPosition(current.x(), y, current.z()));
            }
            detail = "Escaping the enclosed descent-search plane to verified clearance Y=" + nextY + ".";
            return List.copyOf(route);
        }
        detail = "All bounded higher descent-search planes were exhausted.";
        return List.of();
    }

    boolean blocked() { return blocked; }
    String detail() { return detail; }
}
