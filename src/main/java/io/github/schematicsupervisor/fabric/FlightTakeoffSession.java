package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A bounded ordinary jump followed by activation of already-authorized flight. */
final class FlightTakeoffSession {
    static final int TIMEOUT_TICKS = 20;
    static final int JUMP_HOLD_TICKS = 4;
    static final String BLOCKED_SCREEN =
            "Close containers and other screens, and empty the cursor, before takeoff.";

    interface Port {
        Observation observe();
        JumpLease holdJump();
        void activateExistingFlight();
    }

    interface JumpLease extends AutoCloseable {
        @Override void close();
    }

    /** Restores current physical input only when this operation changed the binding. */
    static final class JumpInputLease implements JumpLease {
        private final BooleanSupplier physicallyPressed;
        private final Consumer<Boolean> setPressed;
        private final boolean owned;
        private boolean closed;

        JumpInputLease(boolean alreadyPressed, BooleanSupplier physicallyPressed, Consumer<Boolean> setPressed) {
            this.physicallyPressed = Objects.requireNonNull(physicallyPressed, "physicallyPressed");
            this.setPressed = Objects.requireNonNull(setPressed, "setPressed");
            owned = !alreadyPressed;
            if (owned) { setPressed.accept(true); }
        }

        @Override public void close() {
            if (closed) { return; }
            RuntimeException readFailure = null;
            if (owned) {
                boolean pressed = false;
                try {
                    pressed = physicallyPressed.getAsBoolean();
                } catch (RuntimeException failure) {
                    readFailure = failure;
                }
                setPressed.accept(pressed);
            }
            closed = true;
            if (readFailure != null) { throw readFailure; }
        }
    }

    record Observation(boolean connected, Object context, boolean flightAllowed, boolean flying,
                       boolean onGround, boolean screenBlocked, String blocker) { }
    record Snapshot(String state, String detail, boolean active) { }
    private enum State { IDLE, QUEUED, JUMPING, WAITING_FOR_AIR, CONFIRMING, COMPLETE, FAILED, CANCELLED }

    private final Port port;
    private State state = State.IDLE;
    private String detail = "Takeoff has not been requested.";
    private Object context;
    private JumpLease jump;
    private int ticks;
    private int jumpStarted;

    FlightTakeoffSession(Port port) { this.port = Objects.requireNonNull(port, "port"); }

    void start() {
        if (active()) { return; }
        ticks = 0;
        try {
            Observation observation = port.observe();
            if (!valid(observation, false)) { return; }
            context = observation.context();
            if (observation.flying()) {
                finish(State.COMPLETE, "Flight is already active; no takeoff input was sent.");
            } else {
                state = State.QUEUED;
                detail = "Takeoff queued for the next client tick.";
            }
        } catch (RuntimeException failure) {
            fail("Takeoff could not start: " + message(failure));
        }
    }

    void tick() {
        if (!active()) { return; }
        ticks++;
        try {
            Observation observation = port.observe();
            if (!valid(observation, true)) { return; }
            if (observation.flying() && !observation.onGround()) {
                finish(State.COMPLETE, "Takeoff complete; existing flight is active.");
                return;
            }
            if (ticks >= TIMEOUT_TICKS) {
                fail("Takeoff timed out; jump input was released.");
                return;
            }
            if (state == State.CONFIRMING) { return; }
            if (!observation.onGround()) {
                releaseJump();
                state = State.CONFIRMING;
                detail = "Airborne; confirming existing flight activation.";
                port.activateExistingFlight();
                return;
            }
            if (state == State.QUEUED) {
                jump = Objects.requireNonNull(port.holdJump(), "jump input lease");
                jumpStarted = ticks;
                state = State.JUMPING;
                detail = "Taking off with a bounded jump.";
            } else if (state == State.JUMPING && ticks - jumpStarted >= JUMP_HOLD_TICKS) {
                releaseJump();
                state = State.WAITING_FOR_AIR;
                detail = "Jump input released; waiting to become airborne.";
            }
        } catch (RuntimeException failure) {
            fail("Takeoff stopped: " + message(failure));
        }
    }

    void cancel(String reason) {
        if (active()) { finish(State.CANCELLED, reason); }
    }

    boolean active() {
        return switch (state) {
            case QUEUED, JUMPING, WAITING_FOR_AIR, CONFIRMING -> true;
            default -> false;
        };
    }

    Snapshot snapshot() { return new Snapshot(state.name(), detail, active()); }

    private boolean valid(Observation observation, boolean running) {
        if (!observation.connected() || observation.context() == null) {
            fail("Takeoff stopped because the player is disconnected.");
        } else if (running && !Objects.equals(context, observation.context())) {
            fail("Takeoff stopped because the player, server, or world changed.");
        } else if (!observation.flightAllowed()) {
            fail("Takeoff requires flight permission already granted by the server.");
        } else if (observation.blocker() != null && !observation.blocker().isBlank()) {
            fail("Takeoff blocked: " + observation.blocker());
        } else if (running && observation.screenBlocked()) {
            // The inventory, settings pages, chat and an unfocused window leave takeoff input working.
            fail(BLOCKED_SCREEN);
        } else {
            return true;
        }
        return false;
    }

    private void fail(String reason) { finish(State.FAILED, reason); }

    private void finish(State result, String reason) {
        state = result;
        detail = reason == null || reason.isBlank() ? "Takeoff cancelled." : reason;
        try {
            releaseJump();
        } catch (RuntimeException failure) {
            state = State.FAILED;
            detail += " Jump input cleanup failed: " + message(failure);
        }
    }

    private void releaseJump() {
        JumpLease lease = jump;
        if (lease != null) {
            jump = null;
            lease.close();
        }
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
