package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/**
 * The checks every execution tick runs, in order, before any work. Their order is the contract: a
 * click awaiting its acknowledgement settles first, lost flight stops work even behind a menu, and
 * key presses only count as player input while no menu is taking them.
 */
final class ExecutionTickGate {
    enum Next {
        WAIT_RECEIPT,
        FAIL_FLIGHT_LOST,
        WAIT_SCREEN,
        FAIL_MANUAL_INTERACTION,
        FAIL_MANUAL_MOVEMENT,
        ADVANCE
    }

    static final String FLIGHT_LOST = "Active flight was lost; construction stopped before further interaction";
    static final String MANUAL_INTERACTION = "Manual interaction interrupted moss clearing";
    static final String MANUAL_MOVEMENT = "Manual movement input interrupted flight construction";

    record Facts(
            InteractionTickDispatch.Next dispatch,
            boolean screenBlocksActions,
            boolean flightExecution,
            boolean flightAvailable,
            boolean ownedMining,
            boolean attackOrUsePressed,
            boolean movementPressed,
            ExecutionMode mode
    ) {
        Facts {
            Objects.requireNonNull(dispatch, "dispatch");
            Objects.requireNonNull(mode, "mode");
        }
    }

    private ExecutionTickGate() {
    }

    static Next decide(Facts facts) {
        if (facts.dispatch() == InteractionTickDispatch.Next.WAIT_RECEIPT) {
            return Next.WAIT_RECEIPT;
        }
        if (facts.flightExecution() && !facts.flightAvailable()) {
            return Next.FAIL_FLIGHT_LOST;
        }
        if (facts.screenBlocksActions()) {
            return Next.WAIT_SCREEN;
        }
        if (facts.ownedMining() && facts.attackOrUsePressed()) {
            return Next.FAIL_MANUAL_INTERACTION;
        }
        // Waiting for materials leaves the player free to move; any other flight work stops for input.
        if (facts.flightExecution() && facts.mode() != ExecutionMode.WAITING_MATERIALS && facts.movementPressed()) {
            return Next.FAIL_MANUAL_MOVEMENT;
        }
        return Next.ADVANCE;
    }
}
