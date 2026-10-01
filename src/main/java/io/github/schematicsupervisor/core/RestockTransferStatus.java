package io.github.schematicsupervisor.core;

public enum RestockTransferStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    CAPACITY_BLOCKED,
    FAILED,
    /**
     * No route reached the current depot. Its chest was never opened, nothing moved there, no screen
     * remains open, and the adapter no longer counts its stock, so another plan may use other depots.
     */
    UNREACHABLE
}
