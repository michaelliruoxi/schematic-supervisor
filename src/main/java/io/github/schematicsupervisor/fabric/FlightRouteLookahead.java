package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.function.IntPredicate;

/** Skips grid waypoints only when the entire shortcut is freshly received and collision-free. */
final class FlightRouteLookahead {
    static final int MAXIMUM_LOOKAHEAD = 8;

    private FlightRouteLookahead() { }

    static int select(int waypoint, int count, IntPredicate clearSegment) {
        Objects.requireNonNull(clearSegment, "clearSegment");
        if (waypoint < 0 || waypoint >= count) { throw new IllegalArgumentException("invalid route cursor"); }
        int end = (int) Math.min((long) count - 1, (long) waypoint + MAXIMUM_LOOKAHEAD);
        for (int candidate = end; candidate > waypoint; candidate--) {
            if (clearSegment.test(candidate)) { return candidate; }
        }
        return waypoint;
    }
}
