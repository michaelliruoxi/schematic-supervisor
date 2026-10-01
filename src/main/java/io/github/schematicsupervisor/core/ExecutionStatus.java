package io.github.schematicsupervisor.core;

public enum ExecutionStatus {
    IDLE,
    RUNNING,
    WAITING_FOR_SCREEN,
    WAITING_FOR_MAINTENANCE,
    NEEDS_MATERIALS,
    NEEDS_PREREQUISITES,
    SUCCEEDED,
    FAILED
}
