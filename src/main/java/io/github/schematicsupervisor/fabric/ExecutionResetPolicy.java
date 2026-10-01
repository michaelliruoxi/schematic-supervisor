package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SafeReturnStatus;
import java.util.Objects;

/**
 * Pure post-release normalization for an execution adapter awaiting explicit Reset teardown.
 */
final class ExecutionResetPolicy {
    private ExecutionResetPolicy() {
    }

    static Normalization afterSuccessfulRelease(boolean orderPresent) {
        return new Normalization(
                orderPresent ? OrderIntent.SUSPENDED : OrderIntent.IDLE,
                SafeReturnStatus.IDLE,
                0
        );
    }

    enum OrderIntent {
        IDLE,
        SUSPENDED
    }

    record Normalization(
            OrderIntent orderIntent,
            SafeReturnStatus safeReturnStatus,
            int safeReturnGraceTicks
    ) {
        Normalization {
            Objects.requireNonNull(orderIntent, "orderIntent");
            Objects.requireNonNull(safeReturnStatus, "safeReturnStatus");
            if (safeReturnStatus != SafeReturnStatus.IDLE) {
                throw new IllegalArgumentException(
                        "explicit reset must normalize safe return to IDLE"
                );
            }
            if (safeReturnGraceTicks != 0) {
                throw new IllegalArgumentException(
                        "explicit reset must clear safe-return grace ticks"
                );
            }
        }
    }
}
