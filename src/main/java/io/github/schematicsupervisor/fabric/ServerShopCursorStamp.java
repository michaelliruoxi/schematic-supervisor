package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.UUID;

/** Cursor packet ordering in one exact observed handler binding; a read-only mark may have sequence zero. */
public record ServerShopCursorStamp(String observerEpoch, long contextGeneration,
                                     long handlerGeneration, long sequence, int syncId) {
    public ServerShopCursorStamp {
        Objects.requireNonNull(observerEpoch, "observerEpoch");
        if (!UUID.fromString(observerEpoch).toString().equals(observerEpoch)
                || contextGeneration < 1 || handlerGeneration < 1 || sequence < 0 || syncId < 1) {
            throw new IllegalArgumentException("invalid server cursor stamp");
        }
    }

    public boolean isLaterThan(ServerShopCursorStamp baseline) {
        return baseline != null && observerEpoch.equals(baseline.observerEpoch())
                && contextGeneration == baseline.contextGeneration()
                && handlerGeneration == baseline.handlerGeneration()
                && syncId == baseline.syncId() && sequence > baseline.sequence();
    }
}
