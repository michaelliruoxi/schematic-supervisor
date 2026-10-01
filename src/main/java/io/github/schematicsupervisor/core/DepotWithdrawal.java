package io.github.schematicsupervisor.core;

import java.util.Objects;

public record DepotWithdrawal(DepotId depot, MaterialQuantities quantities) {
    public DepotWithdrawal {
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(quantities, "quantities");
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("withdrawal must not be empty");
        }
    }
}
