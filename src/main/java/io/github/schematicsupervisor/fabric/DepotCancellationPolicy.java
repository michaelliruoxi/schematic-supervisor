package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RestockTransferStatus;
import java.util.Objects;

/**
 * Pure state policy for idempotent depot cancellation and maintenance ownership.
 */
final class DepotCancellationPolicy {
    private DepotCancellationPolicy() {
    }

    static State requestCancellation(State current, boolean withdrawalOperation) {
        Objects.requireNonNull(current, "current");
        if (current.status() != RestockTransferStatus.RUNNING) {
            return current;
        }
        if (!withdrawalOperation) {
            return new State(
                    RestockTransferStatus.FAILED,
                    Termination.FAIL,
                    "running withdrawal had no active withdrawal operation",
                    current.unpolledMovement()
            );
        }
        if (current.termination() != Termination.NONE) {
            return current;
        }
        return new State(
                current.status(),
                Termination.CANCEL,
                "",
                current.unpolledMovement()
        );
    }

    static State cancellationFailure(State current, String failureDetail) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(failureDetail, "failureDetail");
        if (failureDetail.isBlank()) {
            throw new IllegalArgumentException("cancellation failure detail must not be blank");
        }
        String combinedDetail = current.detail().isBlank()
                ? failureDetail
                : current.detail() + "; " + failureDetail;
        return new State(
                current.status(),
                Termination.FAIL,
                combinedDetail,
                current.unpolledMovement()
        );
    }

    static boolean maintenanceIdle(boolean operationIdle, boolean scanQueueEmpty) {
        return operationIdle && scanQueueEmpty;
    }

    static boolean mayFinishAfterStopFailure(
            boolean outstandingScreenResponse,
            boolean pendingInventoryAction
    ) {
        return !outstandingScreenResponse && !pendingInventoryAction;
    }

    static boolean maintenanceStopSettled(
            boolean cancellationSucceeded,
            boolean outstandingScreenResponse
    ) {
        return cancellationSucceeded && !outstandingScreenResponse;
    }

    enum Termination {
        NONE,
        CANCEL,
        FAIL
    }

    record State(
            RestockTransferStatus status,
            Termination termination,
            String detail,
            MaterialQuantities unpolledMovement
    ) {
        State {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(termination, "termination");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(unpolledMovement, "unpolledMovement");
        }
    }
}
