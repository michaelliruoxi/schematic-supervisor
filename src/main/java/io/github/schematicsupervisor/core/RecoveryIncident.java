package io.github.schematicsupervisor.core;

import java.util.Objects;

/**
 * Advisor-safe recovery context. It deliberately contains no block, player, or depot coordinates.
 */
public record RecoveryIncident(
        String planId,
        int chunkOrdinal,
        BuildPhase phase,
        long stalledSeconds,
        boolean serverLagging,
        MaterialQuantities missingMaterials,
        String lastError,
        boolean repathAttempted,
        boolean safeReturnAttempted,
        int chunkTotal
) {
    public RecoveryIncident(String planId, int chunkOrdinal, BuildPhase phase, long stalledSeconds,
            boolean serverLagging, MaterialQuantities missingMaterials, String lastError,
            boolean repathAttempted, boolean safeReturnAttempted) {
        this(planId, chunkOrdinal, phase, stalledSeconds, serverLagging, missingMaterials, lastError,
                repathAttempted, safeReturnAttempted, SchematicPlan.CHUNK_COUNT);
    }

    public RecoveryIncident {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(missingMaterials, "missingMaterials");
        lastError = lastError == null ? "" : lastError;
        PlanLimits.requireChunkCount(chunkTotal);
        if (chunkOrdinal < 1 || chunkOrdinal > chunkTotal) {
            throw new IllegalArgumentException("chunk ordinal must be between 1 and " + chunkTotal);
        }
        if (stalledSeconds < 0) {
            throw new IllegalArgumentException("stalled seconds must be non-negative");
        }
    }
}
