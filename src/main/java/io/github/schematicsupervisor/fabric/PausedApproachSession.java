package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** One bounded flight approach while the construction checkpoint remains paused. */
final class PausedApproachSession {
    static final int COMMAND_WAIT_TICKS = 100;
    static final int MAXIMUM_TICKS = 4000;
    interface Port {
        Observation observe();
        void begin();
        void tick();
        boolean arrived();
        boolean failed();
        String detail();
        void stop();
    }
    record Observation(Object context, boolean flying, boolean commandScreen, boolean screenBlocked,
                       String problem) { }
    private enum State { IDLE, QUEUED, ACTIVE, COMPLETE, FAILED, CANCELLED }
    private final Port port;
    private State state = State.IDLE;
    private Object context;
    private int ticks;
    private String detail = "No paused approach requested.";

    PausedApproachSession(Port port) { this.port = Objects.requireNonNull(port); }

    void start() {
        if (active()) { return; }
        ticks = 0;
        try {
            Observation seen = port.observe();
            context = seen.context();
            if (!valid(seen)) { return; }
            state = State.QUEUED;
            detail = "Approach queued; waiting for the command screen to close.";
        } catch (RuntimeException failure) { fail(failure); }
    }

    void tick() {
        if (!active()) { return; }
        try {
            Observation seen = port.observe();
            if (!valid(seen)) { return; }
            if (++ticks > MAXIMUM_TICKS) {
                finish(State.FAILED, "Approach exceeded its bounded duration.");
                return;
            }
            if (seen.commandScreen()) {
                if (state != State.QUEUED || ticks >= COMMAND_WAIT_TICKS) {
                    finish(State.CANCELLED, "Approach cancelled by an open command screen.");
                }
                return;
            }
            if (state == State.QUEUED) {
                state = State.ACTIVE;
                port.begin();
            } else { port.tick(); }
            detail = port.detail();
            if (port.failed()) { finish(State.FAILED, detail); }
            else if (port.arrived()) {
                finish(State.COMPLETE, "Approach complete; construction remains paused. " + detail);
            }
        } catch (RuntimeException failure) { fail(failure); }
    }

    boolean active() { return state == State.QUEUED || state == State.ACTIVE; }
    boolean failed() { return state == State.FAILED; }
    String detail() { return detail; }
    void cancel(String reason) { if (active()) { finish(State.CANCELLED, reason); } }

    private boolean valid(Observation seen) {
        String problem = seen.problem();
        if (seen.context() == null || !Objects.equals(context, seen.context())) {
            problem = "The player or world changed; approach stopped.";
        } else if (!seen.flying()) {
            problem = "Approach requires flight already active and permitted by the server.";
        } else if (seen.screenBlocked()) {
            // The inventory, settings pages and an unfocused window leave the flight route running.
            problem = "Approach cancelled by a container or other screen, or an item on the cursor.";
        }
        if (problem != null && !problem.isBlank()) {
            finish(State.FAILED, problem);
            return false;
        }
        return true;
    }

    private void fail(RuntimeException failure) {
        finish(State.FAILED, "Approach stopped: " + failure.getMessage());
    }

    private void finish(State result, String reason) {
        state = result;
        detail = reason;
        try { port.stop(); }
        catch (RuntimeException failure) {
            state = State.FAILED;
            detail += " Movement release failed: " + failure.getMessage();
        }
    }
}
