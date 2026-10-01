package io.github.schematicsupervisor.fabric;

import java.util.Objects;

record RunContext(String worldIdentityHash, String dimension) {
    RunContext {
        Objects.requireNonNull(worldIdentityHash, "worldIdentityHash");
        Objects.requireNonNull(dimension, "dimension");
        if (!worldIdentityHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("world identity must be an opaque SHA-256 value");
        }
        if (dimension.isBlank() || dimension.length() > 256) {
            throw new IllegalArgumentException("dimension must be non-empty and bounded");
        }
    }
}
