package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.ExecutionStatus;

/** What the execution port is doing with its order, and the rules that depend only on that. */
enum ExecutionMode {
    IDLE,
    PREPARING,
    STEM_SWEEP_SCAN,
    STEM_SWEEP_NAVIGATION,
    STEM_SWEEP_MINING,
    STEM_SWEEP_CONFIRMATION,
    FLIGHT_CHUNK_APPROACH,
    ORDINARY_RUNNING,
    ORDINARY_COMPLETION_SCAN,
    FLIGHT_ORDINARY_SELECTING,
    FLIGHT_ORDINARY_NAVIGATING,
    FLIGHT_ORDINARY_READY,
    FLIGHT_ORDINARY_WAITING_CONFIRMATION,
    FLIGHT_CLEARING_NAVIGATION,
    FLIGHT_CLEARING_MOSS,
    FLIGHT_CLEARING_LIGHT,
    FLIGHT_CLEARING_CONFIRMATION,
    SUPPORT_PREVIEW,
    SUPPORT_NAVIGATING,
    SUPPORT_READY,
    SUPPORT_MINING,
    SUPPORT_CONFIRMATION,
    TILL_SELECTING,
    PLANT_SELECTING,
    MANUAL_NAVIGATING,
    MANUAL_READY,
    MANUAL_WAITING_CONFIRMATION,
    WAITING_MATERIALS,
    SUSPENDED,
    SUCCEEDED,
    NEEDS_PREREQUISITES,
    FAILED,
    AUTOMATION_BLOCKED;

    /** Whether a tick advances this mode; the others wait for the supervisor or are finished. */
    boolean ticksWork() {
        return switch (this) {
            case IDLE, SUSPENDED, SUCCEEDED, FAILED, NEEDS_PREREQUISITES, AUTOMATION_BLOCKED -> false;
            default -> true;
        };
    }

    /** Whether an order in this mode is still owned: started, not finished, and not blocked. */
    boolean holdsOrder() {
        return switch (this) {
            case IDLE, SUCCEEDED, FAILED, AUTOMATION_BLOCKED -> false;
            default -> true;
        };
    }

    /** An outcome the supervisor must act on; the approach deadline no longer applies. */
    boolean terminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, NEEDS_PREREQUISITES, AUTOMATION_BLOCKED -> true;
            default -> false;
        };
    }

    /**
     * Whether recovery may restart this order's route. A failed order may not: it starts again
     * from its work order instead.
     */
    boolean restartable() {
        return switch (this) {
            case IDLE, SUCCEEDED, FAILED -> false;
            default -> true;
        };
    }

    /** Modes that must confirm a block change within the hard progress deadline. */
    boolean requiresConfirmedBlockProgress(boolean flightExecution) {
        return switch (this) {
            case ORDINARY_RUNNING, SUPPORT_PREVIEW, SUPPORT_READY, SUPPORT_MINING, SUPPORT_CONFIRMATION,
                    FLIGHT_ORDINARY_SELECTING, FLIGHT_ORDINARY_READY, FLIGHT_ORDINARY_WAITING_CONFIRMATION,
                    STEM_SWEEP_MINING, STEM_SWEEP_CONFIRMATION, TILL_SELECTING, PLANT_SELECTING,
                    MANUAL_READY, MANUAL_WAITING_CONFIRMATION -> true;
            // A flight route has its own approach deadline instead.
            case MANUAL_NAVIGATING -> !flightExecution;
            default -> false;
        };
    }

    /** Modes charged to the flight approach deadline, which replanning and chunk receipt never renew. */
    boolean requiresFlightApproachProgress() {
        return switch (this) {
            case PREPARING, STEM_SWEEP_SCAN, STEM_SWEEP_NAVIGATION, FLIGHT_CHUNK_APPROACH,
                    FLIGHT_ORDINARY_SELECTING, FLIGHT_ORDINARY_NAVIGATING,
                    FLIGHT_CLEARING_NAVIGATION, SUPPORT_PREVIEW, SUPPORT_NAVIGATING,
                    TILL_SELECTING, PLANT_SELECTING, MANUAL_NAVIGATING -> true;
            default -> false;
        };
    }

    /** Modes in which moving to another block counts as progress for the supervisor's stall timer. */
    boolean countsMovementAsProgress() {
        return switch (this) {
            case ORDINARY_RUNNING, FLIGHT_CHUNK_APPROACH, FLIGHT_ORDINARY_NAVIGATING, FLIGHT_CLEARING_NAVIGATION,
                    STEM_SWEEP_NAVIGATION, MANUAL_NAVIGATING, MANUAL_READY -> true;
            default -> false;
        };
    }

    /** The status a poll reports. A blocked port has none: polling it is an error. */
    ExecutionStatus reportedStatus(boolean waitingForScreen) {
        return switch (this) {
            case IDLE -> ExecutionStatus.IDLE;
            case WAITING_MATERIALS -> ExecutionStatus.NEEDS_MATERIALS;
            case SUCCEEDED -> ExecutionStatus.SUCCEEDED;
            case NEEDS_PREREQUISITES -> ExecutionStatus.NEEDS_PREREQUISITES;
            case FAILED -> ExecutionStatus.FAILED;
            case AUTOMATION_BLOCKED -> throw new IllegalStateException("a blocked execution port has no status");
            default -> waitingForScreen ? ExecutionStatus.WAITING_FOR_SCREEN : ExecutionStatus.RUNNING;
        };
    }
}
