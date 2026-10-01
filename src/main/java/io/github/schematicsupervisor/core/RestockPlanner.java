package io.github.schematicsupervisor.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.List;
import java.util.Objects;

/**
 * Allocates exact shortages across registered depots in stable depot-id order.
 */
public final class RestockPlanner {
    private RestockPlanner() {
    }

    public static RestockDecision plan(
            MaterialQuantities requiredAvailable,
            MaterialQuantities inventory,
            List<DepotStock> depotStocks
    ) {
        Objects.requireNonNull(requiredAvailable, "requiredAvailable");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(depotStocks, "depotStocks");

        MaterialQuantities shortage = requiredAvailable.shortageFrom(inventory);
        MaterialQuantities remaining = shortage;
        List<DepotWithdrawal> withdrawals = new ArrayList<>();
        List<DepotStock> ordered = depotStocks.stream()
                .sorted(Comparator.comparing(DepotStock::depot))
                .toList();

        java.util.HashSet<DepotId> seen = new java.util.HashSet<>();
        for (DepotStock stock : ordered) {
            if (!seen.add(stock.depot())) {
                throw new IllegalArgumentException("duplicate depot snapshot " + stock.depot());
            }
            TreeMap<Material, Long> fromDepot = new TreeMap<>();
            for (Material material : remaining.asMap().keySet()) {
                long amount = Math.min(remaining.get(material), stock.available().get(material));
                if (amount > 0) {
                    fromDepot.put(material, amount);
                }
            }
            MaterialQuantities allocation = MaterialQuantities.of(fromDepot);
            if (!allocation.isEmpty()) {
                withdrawals.add(new DepotWithdrawal(stock.depot(), allocation));
                remaining = remaining.minusFloorZero(allocation);
            }
        }

        MaterialQuantities total = MaterialQuantities.empty();
        for (DepotWithdrawal withdrawal : withdrawals) {
            total = total.plus(withdrawal.quantities());
        }
        return new RestockDecision(requiredAvailable, shortage, withdrawals, total, remaining);
    }
}
