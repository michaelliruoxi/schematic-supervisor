package io.github.schematicsupervisor.core;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SupervisorPorts(
        Execution execution,
        Inventory inventory,
        Depots depots,
        Verification verification,
        ServerHealth serverHealth,
        Advisor advisor,
        Checkpoints checkpoints,
        Notifications notifications,
        Clock clock
) {
    public SupervisorPorts {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(depots, "depots");
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(serverHealth, "serverHealth");
        Objects.requireNonNull(advisor, "advisor");
        Objects.requireNonNull(checkpoints, "checkpoints");
        Objects.requireNonNull(notifications, "notifications");
        Objects.requireNonNull(clock, "clock");
    }

    public interface Execution {
        /**
         * Accepts a deterministic order and returns immediately. Progress is observed via poll().
         */
        void start(WorkOrder order);

        /**
         * Must not block the client tick. Material fields are deltas/requests as documented.
         */
        ExecutionSnapshot poll();

        /**
         * Observes outstanding interactions and drains confirmed consumption without starting
         * movement, retries, or work. A nonblank detail means settlement could not be confirmed.
         */
        default ExecutionSettlementSnapshot pollSettlement() {
            return ExecutionSettlementSnapshot.settled();
        }

        /** The one durable planned credit; reading it must never drain or replace it. */
        default Optional<PlannedConsumptionCredit> pendingPlannedCredit() {
            return Optional.empty();
        }

        /** Persist acknowledgement only after the core has durably saved the full credit and total. */
        default void acknowledgePlannedCredit(String id) {
            throw new IllegalStateException("The execution adapter does not support planned credit acknowledgement");
        }

        void stopMovement();

        void cancelCurrentPath();

        /**
         * Returns whether a path restart request was accepted, not whether the route completed.
         */
        boolean restartCurrentPath();

        /**
         * Begins a safe-position route without starting any replacement build path.
         */
        void beginReturnToLastSafePosition();

        SafeReturnSnapshot pollSafeReturn();

        void cancelSafeReturn();

        /**
         * Whether flight this executor relies on is off while the server still allows it, so a bounded
         * takeoff could turn it back on. It never grants flight.
         */
        default boolean flightRestorable() {
            return false;
        }

        /** Starts a bounded takeoff that only re-activates flight the server already allows. */
        default void beginFlightRestore() {
            throw new IllegalStateException("The execution adapter cannot restore flight");
        }

        default FlightRestoreSnapshot pollFlightRestore() {
            return FlightRestoreSnapshot.failed("The execution adapter cannot restore flight");
        }

        default void cancelFlightRestore() {
        }
    }

    public interface Inventory {
        MaterialQuantities snapshot();
    }

    public interface Depots {
        /**
         * Returns the adapter's current registered-depot stock snapshot.
         */
        List<DepotStock> snapshot();

        /**
         * Starts navigation and chest interaction for only the supplied deterministic allocations.
         */
        void beginWithdrawal(List<DepotWithdrawal> withdrawals);

        /**
         * Returns immediately; movedDelta is exact since the preceding poll.
         */
        RestockTransferSnapshot pollWithdrawal();

        void cancelWithdrawal();
    }

    public interface Verification {
        /**
         * Starts a bounded verification task and returns immediately.
         */
        void beginVerification(SchematicPlan plan, VerificationScope scope);

        /**
         * Returns immediately so a full-volume pass can be divided across client ticks.
         */
        VerificationTaskSnapshot pollVerification();

        void cancelVerification();
    }

    public interface ServerHealth {
        boolean appearsLaggy();
    }

    public interface Advisor {
        /**
         * Dispatches an incident asynchronously. The advisor receives no coordinates or work orders.
         */
        void beginAdvice(RecoveryIncident incident);

        /**
         * Returns immediately. Network and model work must happen outside the client tick.
         */
        AdviceSnapshot pollAdvice();

        void cancelAdvice();
    }

    public interface Checkpoints {
        Optional<SupervisorCheckpoint> load();

        void save(SupervisorCheckpoint checkpoint);

        void clear();
    }

    public interface Notifications {
        void alert(String message, MaterialQuantities missingMaterials);
    }
}
