package io.github.schematicsupervisor.core;

/**
 * The complete allowlist of actions an external advisor may suggest.
 */
public enum RecoveryAdvice {
    WAIT,
    REPATH,
    RESTOCK,
    RETRY_CHUNK,
    RETURN_TO_SAFE_POSITION,
    PAUSE_AND_ALERT
}
