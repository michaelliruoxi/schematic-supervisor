package io.github.schematicsupervisor.core;

import java.util.List;
import java.util.Objects;

public record RestockDecision(
        MaterialQuantities requiredAvailable,
        MaterialQuantities inventoryShortage,
        List<DepotWithdrawal> withdrawals,
        MaterialQuantities totalWithdrawal,
        MaterialQuantities missing
) {
    public RestockDecision {
        Objects.requireNonNull(requiredAvailable, "requiredAvailable");
        Objects.requireNonNull(inventoryShortage, "inventoryShortage");
        withdrawals = List.copyOf(Objects.requireNonNull(withdrawals, "withdrawals"));
        Objects.requireNonNull(totalWithdrawal, "totalWithdrawal");
        Objects.requireNonNull(missing, "missing");
    }

    public boolean ready() {
        return inventoryShortage.isEmpty();
    }

    public boolean canWithdraw() {
        return !withdrawals.isEmpty();
    }
}
