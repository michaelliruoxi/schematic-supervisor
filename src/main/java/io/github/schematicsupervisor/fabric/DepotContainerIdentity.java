package io.github.schematicsupervisor.fabric;

/**
 * Exact identity of the container screen positively accepted for one depot operation.
 */
record DepotContainerIdentity(int acceptedSyncId, int expectedRows) {
    private static final int INACTIVE = -1;

    DepotContainerIdentity {
        boolean inactive = acceptedSyncId == INACTIVE && expectedRows == INACTIVE;
        boolean accepted = acceptedSyncId >= 0 && (expectedRows == 3 || expectedRows == 6);
        if (!inactive && !accepted) {
            throw new IllegalArgumentException(
                    "container identity must be inactive or an accepted chest screen"
            );
        }
    }

    static DepotContainerIdentity inactive() {
        return new DepotContainerIdentity(INACTIVE, INACTIVE);
    }

    static DepotContainerIdentity accepted(int syncId, int expectedRows) {
        return new DepotContainerIdentity(syncId, expectedRows);
    }

    static int expectedRows(boolean doubleChest) {
        return doubleChest ? 6 : 3;
    }

    boolean active() {
        return acceptedSyncId >= 0;
    }

    boolean matches(int candidateSyncId, int candidateRows) {
        return active()
                && acceptedSyncId == candidateSyncId
                && expectedRows == candidateRows;
    }

    DepotContainerIdentity reset() {
        return inactive();
    }
}
