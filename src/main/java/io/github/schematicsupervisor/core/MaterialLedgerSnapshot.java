package io.github.schematicsupervisor.core;

import java.util.Objects;

public record MaterialLedgerSnapshot(
        MaterialQuantities planned,
        MaterialQuantities consumed,
        MaterialQuantities withdrawn,
        MaterialQuantities remainingPlan,
        MaterialQuantities overage
) {
    public MaterialLedgerSnapshot {
        Objects.requireNonNull(planned, "planned");
        Objects.requireNonNull(consumed, "consumed");
        Objects.requireNonNull(withdrawn, "withdrawn");
        Objects.requireNonNull(remainingPlan, "remainingPlan");
        Objects.requireNonNull(overage, "overage");
    }

    public static MaterialLedgerSnapshot calculate(
            MaterialQuantities planned,
            MaterialQuantities consumed,
            MaterialQuantities withdrawn
    ) {
        return new MaterialLedgerSnapshot(
                planned,
                consumed,
                withdrawn,
                planned.minusFloorZero(consumed),
                consumed.minusFloorZero(planned)
        );
    }
}
