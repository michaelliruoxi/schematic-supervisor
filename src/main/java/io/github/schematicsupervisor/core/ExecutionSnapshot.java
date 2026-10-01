package io.github.schematicsupervisor.core;

import java.util.Objects;

/**
 * A poll result. consumedDelta is the exact delta since the preceding poll.
 */
public record ExecutionSnapshot(
        ExecutionStatus status,
        long progressMarker,
        MaterialQuantities requestedAvailable,
        MaterialQuantities consumedDelta,
        String detail
) {
    public ExecutionSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAvailable, "requestedAvailable");
        Objects.requireNonNull(consumedDelta, "consumedDelta");
        detail = detail == null ? "" : detail;
        if (progressMarker < 0) {
            throw new IllegalArgumentException("progressMarker must be non-negative");
        }
        if (status != ExecutionStatus.NEEDS_MATERIALS && !requestedAvailable.isEmpty()) {
            throw new IllegalArgumentException("only NEEDS_MATERIALS may request supplies");
        }
    }

    public static ExecutionSnapshot idle() {
        return new ExecutionSnapshot(
                ExecutionStatus.IDLE,
                0,
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                ""
        );
    }

    public static ExecutionSnapshot running(long marker) {
        return new ExecutionSnapshot(
                ExecutionStatus.RUNNING,
                marker,
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                ""
        );
    }

    public static ExecutionSnapshot succeeded(long marker, MaterialQuantities consumed) {
        return new ExecutionSnapshot(
                ExecutionStatus.SUCCEEDED,
                marker,
                MaterialQuantities.empty(),
                consumed,
                ""
        );
    }

    public static ExecutionSnapshot needsMaterials(
            long marker,
            MaterialQuantities requestedAvailable
    ) {
        return new ExecutionSnapshot(
                ExecutionStatus.NEEDS_MATERIALS,
                marker,
                requestedAvailable,
                MaterialQuantities.empty(),
                ""
        );
    }

    public static ExecutionSnapshot failed(long marker, String detail) {
        return new ExecutionSnapshot(
                ExecutionStatus.FAILED,
                marker,
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                detail
        );
    }
}
