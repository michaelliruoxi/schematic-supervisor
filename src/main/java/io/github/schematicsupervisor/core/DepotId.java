package io.github.schematicsupervisor.core;

import java.util.Objects;

/**
 * An opaque registered-depot identifier. Coordinate mapping stays in the adapter.
 */
public record DepotId(String value) implements Comparable<DepotId> {
    public DepotId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("depot id must not be blank");
        }
    }

    @Override
    public int compareTo(DepotId other) {
        return value.compareTo(other.value);
    }
}
