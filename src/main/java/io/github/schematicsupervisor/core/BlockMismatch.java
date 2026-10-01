package io.github.schematicsupervisor.core;

import java.util.Objects;

public record BlockMismatch(
        BlockPosition position,
        BlockState expected,
        BlockState actual,
        MismatchType type
) {
    public BlockMismatch {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(type, "type");
        if (type == MismatchType.MISSING && !actual.isAir()) {
            throw new IllegalArgumentException("a missing block must have air as the actual state");
        }
        if (type == MismatchType.EXTRANEOUS && (!expected.isAir() || actual.isAir())) {
            throw new IllegalArgumentException("an extraneous mismatch expects air and observes non-air");
        }
    }
}
