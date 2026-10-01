package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.Optional;

/**
 * A Start or Resume that asked for a takeoff first. It runs once the takeoff completes; a failed or
 * cancelled takeoff drops it, and so does any Pause, Stop, or other cancellation of the takeoff.
 */
final class ActionAfterTakeoff {
    record Held(ControlHttpServer.ControlAction action, String requestId, boolean run) { }

    private ControlHttpServer.ControlAction action;
    private String requestId;

    void hold(ControlHttpServer.ControlAction action, String requestId) {
        Objects.requireNonNull(action, "action");
        if (action != ControlHttpServer.ControlAction.START && action != ControlHttpServer.ControlAction.RESUME) {
            throw new IllegalArgumentException("only Start and Resume wait for a takeoff");
        }
        this.action = action;
        this.requestId = requestId;
    }

    boolean pending() {
        return action != null;
    }

    void clear() {
        action = null;
        requestId = null;
    }

    /**
     * Called once the takeoff is no longer active. Returns the held action with whether to run it: only a
     * takeoff that ended in {@code COMPLETE} runs it.
     */
    Optional<Held> takeAfter(String takeoffState) {
        if (action == null) {
            return Optional.empty();
        }
        Held held = new Held(action, requestId, "COMPLETE".equals(takeoffState));
        clear();
        return Optional.of(held);
    }
}
