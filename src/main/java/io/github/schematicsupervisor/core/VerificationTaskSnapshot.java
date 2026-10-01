package io.github.schematicsupervisor.core;

import java.util.Objects;
import java.util.Optional;

public record VerificationTaskSnapshot(
        VerificationTaskStatus status,
        Optional<VerificationResult> result,
        String detail
) {
    public VerificationTaskSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(result, "result");
        detail = detail == null ? "" : detail;
        if ((status == VerificationTaskStatus.SUCCEEDED) != result.isPresent()) {
            throw new IllegalArgumentException("SUCCEEDED must contain a result and other states must not");
        }
    }

    public static VerificationTaskSnapshot running() {
        return new VerificationTaskSnapshot(VerificationTaskStatus.RUNNING, Optional.empty(), "");
    }

    public static VerificationTaskSnapshot succeeded(VerificationResult result) {
        return new VerificationTaskSnapshot(
                VerificationTaskStatus.SUCCEEDED,
                Optional.of(result),
                ""
        );
    }

    public static VerificationTaskSnapshot failed(String detail) {
        return new VerificationTaskSnapshot(VerificationTaskStatus.FAILED, Optional.empty(), detail);
    }
}
