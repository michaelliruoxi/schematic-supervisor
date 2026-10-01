package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/**
 * Pure policy for retaining an uncertain queued screen-open response until it is safely settled.
 */
record QueuedOpenQuarantinePolicy(
        CancellationOpenPolicy openRequest,
        int matchedResponseSyncId,
        boolean matchedResponseWasLastObserved,
        String detail
) {
    static final int UNMATCHED_SYNC_ID = -1;

    QueuedOpenQuarantinePolicy {
        Objects.requireNonNull(openRequest, "openRequest");
        Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("quarantine detail must not be blank");
        }
        if (matchedResponseSyncId < UNMATCHED_SYNC_ID
                || matchedResponseSyncId == openRequest.openingSyncId()) {
            throw new IllegalArgumentException("matched response sync id is invalid");
        }
        if (matchedResponseSyncId == UNMATCHED_SYNC_ID
                && matchedResponseWasLastObserved) {
            throw new IllegalArgumentException(
                    "an unmatched response cannot have been observed"
            );
        }
    }

    static QueuedOpenQuarantinePolicy start(
            CancellationOpenPolicy openRequest,
            String detail
    ) {
        return new QueuedOpenQuarantinePolicy(
                openRequest,
                UNMATCHED_SYNC_ID,
                false,
                detail
        );
    }

    static QueuedOpenQuarantinePolicy fromFailure(
            CancellationOpenPolicy openRequest,
            CancellationOpenPolicy.Decision failure,
            String detail
    ) {
        Objects.requireNonNull(failure, "failure");
        if (failure != CancellationOpenPolicy.Decision.FAIL_TIMEOUT
                && failure != CancellationOpenPolicy.Decision.FAIL_CONTEXT
                && failure != CancellationOpenPolicy.Decision.FAIL_IDENTITY) {
            throw new IllegalArgumentException(
                    "only an uncertain open failure can enter quarantine"
            );
        }
        return start(openRequest, detail);
    }

    boolean matchedResponse() {
        return matchedResponseSyncId != UNMATCHED_SYNC_ID;
    }

    QueuedOpenQuarantinePolicy withMatchedResponse(int syncId, int rows) {
        if (!openRequest.matchesResponse(syncId, rows)) {
            throw new IllegalArgumentException(
                    "matched response must satisfy the retained open request"
            );
        }
        if (matchedResponse() && matchedResponseSyncId != syncId) {
            throw new IllegalStateException(
                    "a different response cannot replace the quarantined screen identity"
            );
        }
        return new QueuedOpenQuarantinePolicy(openRequest, syncId, true, detail);
    }

    Observation observe(
            CancellationOpenPolicy.ScreenObservation screen,
            boolean matchingCursorEmpty
    ) {
        Objects.requireNonNull(screen, "screen");
        if (matchedResponse()) {
            if (screen.kind() == CancellationOpenPolicy.ScreenKind.PLAYER_INVENTORY
                    && matchedResponseWasLastObserved) {
                return new Observation(this, Decision.RELEASE_MANUALLY_CLOSED);
            }
            if (screen.kind() == CancellationOpenPolicy.ScreenKind.GENERIC_CONTAINER
                    && screen.syncId() == matchedResponseSyncId
                    && screen.rows() == openRequest.expectedRows()) {
                QueuedOpenQuarantinePolicy observed =
                        new QueuedOpenQuarantinePolicy(
                                openRequest,
                                matchedResponseSyncId,
                                true,
                                detail
                        );
                return new Observation(
                        observed,
                        matchingCursorEmpty ? Decision.CLOSE_MATCHING : Decision.RETAIN
                );
            }
            return new Observation(
                    new QueuedOpenQuarantinePolicy(
                            openRequest,
                            matchedResponseSyncId,
                            false,
                            detail
                    ),
                    Decision.RETAIN
            );
        }
        if (screen.kind() != CancellationOpenPolicy.ScreenKind.GENERIC_CONTAINER
                || !openRequest.matchesResponse(screen.syncId(), screen.rows())) {
            return new Observation(this, Decision.RETAIN);
        }
        QueuedOpenQuarantinePolicy observed =
                withMatchedResponse(screen.syncId(), screen.rows());
        return new Observation(
                observed,
                matchingCursorEmpty ? Decision.CLOSE_MATCHING : Decision.RETAIN
        );
    }

    enum Decision {
        RETAIN,
        CLOSE_MATCHING,
        RELEASE_MANUALLY_CLOSED
    }

    record Observation(QueuedOpenQuarantinePolicy state, Decision decision) {
        Observation {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(decision, "decision");
        }
    }
}
