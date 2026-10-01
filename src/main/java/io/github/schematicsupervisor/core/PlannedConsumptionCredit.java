package io.github.schematicsupervisor.core;

import java.util.Objects;
import java.util.UUID;

/** One durable planned structural placement awaiting checkpoint acknowledgement. */
public record PlannedConsumptionCredit(String id, String planId, Material material, long quantity) {
    public PlannedConsumptionCredit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(material, "material");
        if (!UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("credit id must be a canonical UUID");
        }
        if (planId.isBlank()) { throw new IllegalArgumentException("credit plan id must not be blank"); }
        if (quantity != 1) { throw new IllegalArgumentException("a structural starter credit must contain one item"); }
        if (!material.placedAsBlock()) {
            throw new IllegalArgumentException("credit material must be a structural placement material");
        }
    }
}
