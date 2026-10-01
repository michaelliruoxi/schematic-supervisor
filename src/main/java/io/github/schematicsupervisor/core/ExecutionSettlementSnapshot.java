package io.github.schematicsupervisor.core;

import java.util.Objects;

/** A read-only settlement poll; consumption is drained exactly once across both poll methods. */
public record ExecutionSettlementSnapshot(
        MaterialQuantities consumedDelta,
        boolean pending,
        String detail
) {
    public ExecutionSettlementSnapshot {
        Objects.requireNonNull(consumedDelta, "consumedDelta");
        detail = detail == null ? "" : detail;
    }

    public static ExecutionSettlementSnapshot settled() {
        return new ExecutionSettlementSnapshot(MaterialQuantities.empty(), false, "");
    }
}
