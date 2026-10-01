package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** Bounded cancellation cleanup for one owned shop handler; never selects a menu action. */
final class ShopMenuCleanupPolicy {
    static final int MAXIMUM_TICKS = 200;
    private ShopMenuCleanupPolicy() { }

    enum Action { WAIT, CLOSE, RELEASE_OWNERSHIP }
    enum Reason { ACTIVE, NO_OWNERSHIP, UNAVAILABLE, CONTEXT_CHANGED, HANDLER_REPLACED,
        OWNED_CURSOR_PENDING, UNRELATED_CURSOR, CLOSE_READY, CLOSE_PENDING, EXHAUSTED }

    /** Persist nextState in the owning adapter before issuing CLOSE, including if that input throws. */
    record State(int elapsedTicks, boolean closeIssued) {
        State {
            if (elapsedTicks < 0 || elapsedTicks > MAXIMUM_TICKS) {
                throw new IllegalArgumentException("cleanup ticks must be bounded");
            }
        }
        static State initial() { return new State(0, false); }
    }

    /** Identity facts refer to the exact original handler, world and connection, never just its title. */
    record Observation(boolean cleanupRequested, boolean ownsHandler, boolean observationAvailable,
                       boolean originalContext, boolean sameHandler, boolean cursorEmpty,
                       boolean ownedClickCursor) {
        Observation {
            if (cursorEmpty && ownedClickCursor) {
                throw new IllegalArgumentException("an empty cursor cannot contain the owned prediction");
            }
        }
    }

    record Decision(Action action, State nextState, Reason reason) {
        Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(nextState, "nextState");
            Objects.requireNonNull(reason, "reason");
        }
    }

    static Decision decide(State state, Observation observed) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(observed, "observed");
        if (!observed.cleanupRequested()) { return result(Action.WAIT, state, Reason.ACTIVE); }
        if (!observed.ownsHandler()) { return result(Action.RELEASE_OWNERSHIP, state, Reason.NO_OWNERSHIP); }
        State elapsed = new State(Math.min(MAXIMUM_TICKS, state.elapsedTicks() + 1), state.closeIssued());
        if (!observed.observationAvailable()) { return result(Action.WAIT, elapsed, Reason.UNAVAILABLE); }
        if (!observed.originalContext()) { return result(Action.RELEASE_OWNERSHIP, elapsed, Reason.CONTEXT_CHANGED); }
        if (!observed.sameHandler()) { return result(Action.RELEASE_OWNERSHIP, elapsed, Reason.HANDLER_REPLACED); }
        if (state.elapsedTicks() >= MAXIMUM_TICKS) { return result(Action.WAIT, elapsed, Reason.EXHAUSTED); }
        if (!observed.cursorEmpty()) {
            return result(Action.WAIT, elapsed, observed.ownedClickCursor()
                    ? Reason.OWNED_CURSOR_PENDING : Reason.UNRELATED_CURSOR);
        }
        if (state.closeIssued()) { return result(Action.WAIT, elapsed, Reason.CLOSE_PENDING); }
        return result(Action.CLOSE, new State(elapsed.elapsedTicks(), true), Reason.CLOSE_READY);
    }

    private static Decision result(Action action, State state, Reason reason) {
        return new Decision(action, state, reason);
    }
}
