package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.function.Predicate;

/** Bounded staging and waiting decisions for movement through received world data. */
final class FlightStreamingProgress {
    enum WaitState { WAITING, READY, TIMED_OUT }

    private final int maximumWaitTicks;
    private int waitTicks;

    FlightStreamingProgress(int maximumWaitTicks) {
        if (maximumWaitTicks < 1) { throw new IllegalArgumentException("stream wait must be positive"); }
        this.maximumWaitTicks = maximumWaitTicks;
    }

    WaitState await(boolean continuationAvailable) {
        if (continuationAvailable) {
            waitTicks = 0;
            return WaitState.READY;
        }
        return ++waitTicks > maximumWaitTicks ? WaitState.TIMED_OUT : WaitState.WAITING;
    }

    void reset() { waitTicks = 0; }
    int waitTicks() { return waitTicks; }

    static boolean reachedStage(BlockPosition start, BlockPosition position, BlockPosition target,
                                double desiredProgress, Predicate<BlockPosition> received) {
        double progress = horizontalDistance(start, target) - horizontalDistance(position, target);
        return progress >= 1.0 && (progress >= desiredProgress || atFrontier(position, target, received));
    }

    static boolean atFrontier(BlockPosition position, BlockPosition target,
                              Predicate<BlockPosition> received) {
        int xDirection = Integer.compare(target.x(), position.x());
        int zDirection = Integer.compare(target.z(), position.z());
        if (xDirection == 0 && zDirection == 0) { return false; }
        if (xDirection != 0 && received.test(new BlockPosition(
                position.x() + xDirection, position.y(), position.z()))) { return false; }
        if (zDirection != 0 && received.test(new BlockPosition(
                position.x(), position.y(), position.z() + zDirection))) { return false; }
        return true;
    }

    static double horizontalDistance(BlockPosition first, BlockPosition second) {
        return Math.hypot((double) first.x() - second.x(), (double) first.z() - second.z());
    }
}
