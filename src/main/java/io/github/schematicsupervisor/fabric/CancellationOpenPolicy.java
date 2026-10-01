package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/**
 * Pure policy for settling cancellation while a server screen-open response is outstanding.
 */
record CancellationOpenPolicy(int openingSyncId, int expectedRows, int timeoutTicks) {
    CancellationOpenPolicy {
        if (openingSyncId < 0) {
            throw new IllegalArgumentException("opening sync id must be non-negative");
        }
        if (expectedRows != 3 && expectedRows != 6) {
            throw new IllegalArgumentException("expected rows must describe a chest");
        }
        if (timeoutTicks < 1) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    Decision evaluate(boolean contextValid, int waitedTicks, ScreenObservation screen) {
        Objects.requireNonNull(screen, "screen");
        if (!contextValid) {
            return Decision.FAIL_CONTEXT;
        }
        if (waitedTicks > timeoutTicks) {
            return Decision.FAIL_TIMEOUT;
        }
        return switch (screen.kind()) {
            case PLAYER_INVENTORY -> Decision.WAIT;
            case OTHER -> Decision.FAIL_IDENTITY;
            case GENERIC_CONTAINER -> matchesResponse(screen.syncId(), screen.rows())
                    ? Decision.CLOSE_MATCHING
                    : Decision.FAIL_IDENTITY;
        };
    }

    boolean matchesResponse(int syncId, int rows) {
        return syncId != openingSyncId && rows == expectedRows;
    }

    enum Decision {
        WAIT,
        CLOSE_MATCHING,
        FAIL_IDENTITY,
        FAIL_CONTEXT,
        FAIL_TIMEOUT
    }

    enum ScreenKind {
        PLAYER_INVENTORY,
        GENERIC_CONTAINER,
        OTHER
    }

    record ScreenObservation(ScreenKind kind, int syncId, int rows) {
        ScreenObservation {
            Objects.requireNonNull(kind, "kind");
            if (kind == ScreenKind.GENERIC_CONTAINER) {
                if (syncId < 0 || rows < 1) {
                    throw new IllegalArgumentException(
                            "generic container observation must include identity and shape"
                    );
                }
            } else if (syncId != -1 || rows != -1) {
                throw new IllegalArgumentException(
                        "non-container observation cannot expose container identity"
                );
            }
        }

        static ScreenObservation playerInventory() {
            return new ScreenObservation(ScreenKind.PLAYER_INVENTORY, -1, -1);
        }

        static ScreenObservation genericContainer(int syncId, int rows) {
            return new ScreenObservation(ScreenKind.GENERIC_CONTAINER, syncId, rows);
        }

        static ScreenObservation other() {
            return new ScreenObservation(ScreenKind.OTHER, -1, -1);
        }
    }
}
