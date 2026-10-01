package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Why a takeoff (from the command, or before Start or Resume) may not begin now, or blank. The first
 * problem in this order wins. Suppliers are read only when the order reaches them, because they read
 * saved files and Baritone's live state.
 */
final class TakeoffEnvironment {
    static final String CLOSED = "The supervisor runtime is closed.";
    static final String JOIN_WORLD = "Join the target world before takeoff.";
    static final String BUSY = "Wait for loading, shopping, and depot maintenance to finish before takeoff.";
    static final String RECONCILE = "Reconcile the checkpoint and settle depot transfers before takeoff.";
    static final String SUPERVISION_ACTIVE = "Pause or stop supervision before takeoff.";
    static final String OTHER_WORLD = "The loaded run belongs to a different server, save, or dimension.";

    /**
     * @param state the loaded plan's state, or null with no plan loaded
     * @param savedCheckpointProblem read only with no plan loaded: a problem with the saved checkpoint
     * @param baritoneProblem Baritone work or a forced input that would fight the takeoff
     */
    record Facts(
            boolean closed,
            String runtimeWorkBlocker,
            boolean connected,
            String automationBlocker,
            boolean maintenanceBusy,
            SupervisorState state,
            boolean reconciliationOrTransferPending,
            Supplier<String> savedCheckpointProblem,
            boolean loadedRunInAnotherWorld,
            Supplier<String> baritoneProblem
    ) {
        Facts {
            runtimeWorkBlocker = runtimeWorkBlocker == null ? "" : runtimeWorkBlocker;
            automationBlocker = automationBlocker == null ? "" : automationBlocker;
            Objects.requireNonNull(savedCheckpointProblem, "savedCheckpointProblem");
            Objects.requireNonNull(baritoneProblem, "baritoneProblem");
        }
    }

    private TakeoffEnvironment() {
    }

    static String problem(Facts facts) {
        if (facts.closed()) { return CLOSED; }
        if (!facts.runtimeWorkBlocker().isBlank()) { return facts.runtimeWorkBlocker(); }
        if (!facts.connected()) { return JOIN_WORLD; }
        if (!facts.automationBlocker().isBlank()) { return facts.automationBlocker(); }
        if (facts.maintenanceBusy()) { return BUSY; }
        if (facts.state() != null) {
            if (facts.reconciliationOrTransferPending()) { return RECONCILE; }
            if (facts.state() != SupervisorState.PAUSED && facts.state() != SupervisorState.STOPPED
                    && facts.state() != SupervisorState.DONE) {
                return SUPERVISION_ACTIVE;
            }
        } else {
            String saved = facts.savedCheckpointProblem().get();
            if (saved != null && !saved.isBlank()) { return saved; }
        }
        if (facts.loadedRunInAnotherWorld()) { return OTHER_WORLD; }
        String baritone = facts.baritoneProblem().get();
        return baritone == null ? "" : baritone;
    }
}
