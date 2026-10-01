package io.github.schematicsupervisor.core;

import java.util.Objects;
import java.util.Optional;

public record AdviceSnapshot(
        AdviceStatus status,
        Optional<RecoveryAdvice> advice,
        String detail
) {
    public AdviceSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(advice, "advice");
        detail = detail == null ? "" : detail;
        if ((status == AdviceStatus.SUCCEEDED) != advice.isPresent()) {
            throw new IllegalArgumentException("SUCCEEDED must contain advice and other states must not");
        }
    }

    public static AdviceSnapshot pending() {
        return new AdviceSnapshot(AdviceStatus.PENDING, Optional.empty(), "");
    }

    public static AdviceSnapshot succeeded(RecoveryAdvice advice) {
        return new AdviceSnapshot(AdviceStatus.SUCCEEDED, Optional.of(advice), "");
    }

    public static AdviceSnapshot unavailable(String detail) {
        return new AdviceSnapshot(AdviceStatus.UNAVAILABLE, Optional.empty(), detail);
    }

    public static AdviceSnapshot failed(String detail) {
        return new AdviceSnapshot(AdviceStatus.FAILED, Optional.empty(), detail);
    }
}
