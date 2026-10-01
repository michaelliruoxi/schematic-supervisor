package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.List;

/**
 * Places a long vertical approach above or below its destination before changing altitude. The
 * staging leg is optional: when the destination's column is walled off at the current altitude (a
 * chest on a roof above stacked farm planes), the route stages nearby or plans directly instead.
 */
final class FlightAltitudeStaging {
    private static final int MINIMUM_VERTICAL_SEPARATION = 32;
    private static final int MINIMUM_HORIZONTAL_SEPARATION = 8;
    private static final int MAXIMUM_ALTITUDE_DETOUR = 8;
    private static final int NEAR_DESTINATION_RADIUS = 4;
    private static final int STAGING_BUDGET_DIVISOR = 3;

    private FlightAltitudeStaging() { }

    static boolean required(BlockPosition start, BlockPosition target) {
        return Math.abs((long) start.y() - target.y()) >= MINIMUM_VERTICAL_SEPARATION
                && FlightStreamingProgress.horizontalDistance(start, target) >= MINIMUM_HORIZONTAL_SEPARATION;
    }

    static boolean withinAltitudeBand(BlockPosition start, BlockPosition position) {
        return Math.abs((long) position.y() - start.y()) <= MAXIMUM_ALTITUDE_DETOUR;
    }

    static boolean aboveDestination(BlockPosition position, BlockPosition target) {
        return position.x() == target.x() && position.z() == target.z();
    }

    /** Horizontal nearness to the destination's column; altitude is ignored. */
    static boolean nearDestination(BlockPosition position, BlockPosition target) {
        long x = (long) position.x() - target.x();
        long z = (long) position.z() - target.z();
        return x * x + z * z <= (long) NEAR_DESTINATION_RADIUS * NEAR_DESTINATION_RADIUS;
    }

    /**
     * After a failed staging search, the searched route to stage beside the destination's column
     * instead, or an empty list when that route stayed far from it and the next leg should start here.
     */
    static List<BlockPosition> fallbackStage(List<BlockPosition> routeToNearest, BlockPosition target) {
        return routeToNearest.size() > 1 && nearDestination(routeToNearest.getLast(), target)
                ? routeToNearest : List.of();
    }

    /** The staging search's share of a route's nodes, so the later legs always keep most of them. */
    static int nodeBudget(int remainingNodes) {
        if (remainingNodes < 1) { throw new IllegalArgumentException("no route nodes remain"); }
        return Math.max(1, remainingNodes / STAGING_BUDGET_DIVISOR);
    }
}
