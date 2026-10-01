package io.github.schematicsupervisor.core;

import java.util.Objects;

public record DepotStock(DepotId depot, MaterialQuantities available) {
    public DepotStock {
        Objects.requireNonNull(depot, "depot");
        Objects.requireNonNull(available, "available");
    }
}
