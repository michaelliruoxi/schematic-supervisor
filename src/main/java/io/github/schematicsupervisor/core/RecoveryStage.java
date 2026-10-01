package io.github.schematicsupervisor.core;

public enum RecoveryStage {
    NONE,
    STOP_MOVEMENT,
    WAIT_FOR_LAG,
    CHECK_MATERIALS,
    REPATH,
    RETURN_TO_SAFE_POSITION,
    WAIT_FOR_SAFE_POSITION,
    ASK_ADVISOR,
    WAIT_FOR_ADVISOR,
    ADVISOR_WAIT,
    RESTORE_FLIGHT,
    WAIT_FOR_FLIGHT,
    RETRY_WAIT;

    /**
     * The stage written to a checkpoint. Stages added after 0.1.0 are saved as an earlier stage, so an
     * older mod can still read the checkpoint after a rollback. Restoring a recovering checkpoint pauses
     * it and clears its stage, so the saved stage never resumes either way.
     */
    public RecoveryStage persisted() {
        return switch (this) {
            case RESTORE_FLIGHT, WAIT_FOR_FLIGHT -> STOP_MOVEMENT;
            case RETRY_WAIT -> ADVISOR_WAIT;
            default -> this;
        };
    }
}
