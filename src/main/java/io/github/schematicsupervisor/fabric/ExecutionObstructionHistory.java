package io.github.schematicsupervisor.fabric;

import java.lang.ref.WeakReference;
import java.util.Objects;

/** Recovery may change targets, but it must not erase the earlier clearing rejection. */
final class ExecutionObstructionHistory {
    private ExecutionObstruction last;
    private WeakReference<Object> world = new WeakReference<>(null);
    private WeakReference<Object> player = new WeakReference<>(null);

    void capture(ExecutionObstruction observation, Object sourceWorld, Object sourcePlayer) {
        last = Objects.requireNonNull(observation, "observation");
        world = new WeakReference<>(sourceWorld);
        player = new WeakReference<>(sourcePlayer);
    }

    ExecutionObstruction observation(Object currentWorld, Object currentPlayer) {
        if (last == null) { return null; }
        Boolean matches = currentWorld == null || currentPlayer == null ? null
                : currentWorld == world.get() && currentPlayer == player.get();
        return last.withCurrentWorldMatches(matches);
    }
}
