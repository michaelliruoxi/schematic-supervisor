package io.github.schematicsupervisor.core;

import java.util.Objects;

/**
 * movedDelta is the exact inventory transfer since the preceding poll.
 */
public record RestockTransferSnapshot(
        RestockTransferStatus status,
        MaterialQuantities movedDelta,
        String detail
) {
    public RestockTransferSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(movedDelta, "movedDelta");
        detail = detail == null ? "" : detail;
    }

    public static RestockTransferSnapshot running(MaterialQuantities movedDelta) {
        return new RestockTransferSnapshot(RestockTransferStatus.RUNNING, movedDelta, "");
    }

    public static RestockTransferSnapshot succeeded(MaterialQuantities movedDelta) {
        return new RestockTransferSnapshot(RestockTransferStatus.SUCCEEDED, movedDelta, "");
    }

    public static RestockTransferSnapshot failed(MaterialQuantities movedDelta, String detail) {
        return new RestockTransferSnapshot(RestockTransferStatus.FAILED, movedDelta, detail);
    }

    public static RestockTransferSnapshot unreachable(MaterialQuantities movedDelta, String detail) {
        return new RestockTransferSnapshot(RestockTransferStatus.UNREACHABLE, movedDelta, detail);
    }
}
