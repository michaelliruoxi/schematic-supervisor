package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorCheckpoint;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

/** Reads unloaded checkpoint safety markers before permitting takeoff; never changes saved state. */
final class TakeoffCheckpointGuard {
    @FunctionalInterface
    interface ContextSource { Optional<RunContext> load() throws IOException; }

    private TakeoffCheckpointGuard() { }

    static String inspect(Supplier<Optional<SupervisorCheckpoint>> checkpoints,
                          ContextSource contexts, Supplier<RunContext> currentContext) {
        try {
            Optional<SupervisorCheckpoint> saved = checkpoints.get();
            if (saved.isEmpty()) { return ""; }
            SupervisorCheckpoint checkpoint = saved.get();
            if (checkpoint.withdrawalInFlight() || checkpoint.reconciliationRequired()) {
                return "The saved checkpoint has an unsettled depot transfer or requires reconciliation; "
                        + "reconcile it before takeoff.";
            }
            Optional<RunContext> savedContext = contexts.load();
            if (savedContext.isEmpty()) {
                return "The saved checkpoint has no recorded world context; resolve it before takeoff.";
            }
            if (!savedContext.get().equals(currentContext.get())) {
                return "The saved checkpoint belongs to a different server, save, or dimension; takeoff is blocked.";
            }
            return "";
        } catch (IOException | RuntimeException failure) {
            return "The saved checkpoint or its world context could not be read safely; takeoff is blocked. "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }
}
