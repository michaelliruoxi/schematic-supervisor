package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.UUID;

/** Identifies an applied server full-container packet, independently of local slot prediction. */
public record ServerInventorySnapshotStamp(String observerEpoch, long contextGeneration,
                                           long openGeneration, long fullSequence, int syncId, int revision) {
    public ServerInventorySnapshotStamp {
        Objects.requireNonNull(observerEpoch, "observerEpoch");
        if (!UUID.fromString(observerEpoch).toString().equals(observerEpoch)
                || contextGeneration < 1 || openGeneration < 1 || fullSequence < 1
                || syncId < 1 || revision < 0) {
            throw new IllegalArgumentException("Invalid server inventory snapshot stamp");
        }
    }

    public boolean isLaterReopenThan(ServerInventorySnapshotStamp before) {
        return before != null && observerEpoch.equals(before.observerEpoch())
                && contextGeneration == before.contextGeneration()
                && openGeneration > before.openGeneration() && fullSequence > before.fullSequence();
    }
}
