package io.github.schematicsupervisor.core;

import java.util.Objects;

public record SafeReturnSnapshot(SafeReturnStatus status, String detail) {
    public SafeReturnSnapshot {
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public static SafeReturnSnapshot running() {
        return new SafeReturnSnapshot(SafeReturnStatus.RUNNING, "");
    }

    public static SafeReturnSnapshot succeeded() {
        return new SafeReturnSnapshot(SafeReturnStatus.SUCCEEDED, "");
    }

    public static SafeReturnSnapshot failed(String detail) {
        return new SafeReturnSnapshot(SafeReturnStatus.FAILED, detail);
    }
}
