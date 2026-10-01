package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.TreeMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validates that observed inventory gains stay inside a deterministic withdrawal allocation.
 */
final class ExactTransferLedger {
    private final TreeMap<Material, Long> requested;
    private final TreeMap<Material, Long> moved = new TreeMap<>();

    ExactTransferLedger(MaterialQuantities quantities) {
        Objects.requireNonNull(quantities, "quantities");
        if (quantities.isEmpty()) {
            throw new IllegalArgumentException("transfer allocation must not be empty");
        }
        requested = new TreeMap<>();
        requested.putAll(quantities.asMap());
    }

    /** The allocated materials, built-ins first. */
    java.util.Set<Material> requestedMaterials() {
        return java.util.Collections.unmodifiableSet(requested.keySet());
    }

    long remaining(Material material) {
        return requested.getOrDefault(material, 0L) - moved.getOrDefault(material, 0L);
    }

    void recordGain(Material material, long amount) {
        Objects.requireNonNull(material, "material");
        if (amount <= 0) {
            throw new IllegalArgumentException("observed gain must be positive");
        }
        if (amount > remaining(material)) {
            throw new IllegalStateException(
                    "observed gain exceeds allocation for " + material.jsonName()
            );
        }
        moved.merge(material, amount, Math::addExact);
    }

    boolean complete() {
        for (Map.Entry<Material, Long> entry : requested.entrySet()) {
            if (moved.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    MaterialQuantities moved() {
        return MaterialQuantities.of(moved);
    }
}
