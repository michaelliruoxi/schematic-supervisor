package io.github.schematicsupervisor.core;

import java.util.Objects;

/**
 * Dependency-free checkpoint DTO whose fields map directly to JSON values. {@code checkedPieces} holds
 * the pieces after the cursor that the start build check found finished; the builder skips them.
 */
public record SupervisorCheckpoint(
        int version,
        String planId,
        SupervisorState state,
        SupervisorState resumeState,
        SupervisorState restockResumeState,
        int currentChunkIndex,
        BuildPhase phase,
        VerificationStage verificationStage,
        RecoveryStage recoveryStage,
        int stableVerificationPasses,
        String lastVerificationFingerprint,
        MaterialQuantities consumedMaterials,
        MaterialQuantities withdrawnMaterials,
        MaterialQuantities missingMaterials,
        MaterialQuantities restockRequirement,
        String lastError,
        int verificationRetries,
        boolean repathAttempted,
        boolean safeReturnAttempted,
        boolean advisorAttempted,
        boolean withdrawalInFlight,
        boolean reconciliationRequired,
        String reconciliationDetail,
        String scheduleId,
        int scheduleCursor,
        int repairChunkIndex,
        int chunkCount,
        boolean plantingDeferred,
        PlannedConsumptionCredit lastAppliedPlannedCredit,
        CompletedPieces checkedPieces
) {
    public static final int CURRENT_VERSION = 4;

    /** A checkpoint without pieces from a start build check. */
    public SupervisorCheckpoint(int version, String planId, SupervisorState state, SupervisorState resumeState,
            SupervisorState restockResumeState, int currentChunkIndex, BuildPhase phase,
            VerificationStage verificationStage, RecoveryStage recoveryStage, int stableVerificationPasses,
            String lastVerificationFingerprint, MaterialQuantities consumedMaterials,
            MaterialQuantities withdrawnMaterials, MaterialQuantities missingMaterials,
            MaterialQuantities restockRequirement, String lastError, int verificationRetries,
            boolean repathAttempted, boolean safeReturnAttempted, boolean advisorAttempted,
            boolean withdrawalInFlight, boolean reconciliationRequired, String reconciliationDetail,
            String scheduleId, int scheduleCursor, int repairChunkIndex, int chunkCount, boolean plantingDeferred,
            PlannedConsumptionCredit lastAppliedPlannedCredit) {
        this(version, planId, state, resumeState, restockResumeState, currentChunkIndex, phase,
                verificationStage, recoveryStage, stableVerificationPasses, lastVerificationFingerprint,
                consumedMaterials, withdrawnMaterials, missingMaterials, restockRequirement, lastError,
                verificationRetries, repathAttempted, safeReturnAttempted, advisorAttempted,
                withdrawalInFlight, reconciliationRequired, reconciliationDetail,
                scheduleId, scheduleCursor, repairChunkIndex, chunkCount, plantingDeferred,
                lastAppliedPlannedCredit, CompletedPieces.none());
    }

    public SupervisorCheckpoint(int version, String planId, SupervisorState state, SupervisorState resumeState,
            SupervisorState restockResumeState, int currentChunkIndex, BuildPhase phase,
            VerificationStage verificationStage, RecoveryStage recoveryStage, int stableVerificationPasses,
            String lastVerificationFingerprint, MaterialQuantities consumedMaterials,
            MaterialQuantities withdrawnMaterials, MaterialQuantities missingMaterials,
            MaterialQuantities restockRequirement, String lastError, int verificationRetries,
            boolean repathAttempted, boolean safeReturnAttempted, boolean advisorAttempted,
            boolean withdrawalInFlight, boolean reconciliationRequired, String reconciliationDetail,
            String scheduleId, int scheduleCursor, int repairChunkIndex, int chunkCount, boolean plantingDeferred) {
        this(version == 3 ? CURRENT_VERSION : version, planId, state, resumeState, restockResumeState,
                currentChunkIndex, phase, verificationStage, recoveryStage, stableVerificationPasses,
                lastVerificationFingerprint, consumedMaterials, withdrawnMaterials, missingMaterials,
                restockRequirement, lastError, verificationRetries, repathAttempted, safeReturnAttempted,
                advisorAttempted, withdrawalInFlight, reconciliationRequired, reconciliationDetail,
                scheduleId, scheduleCursor, repairChunkIndex, chunkCount, plantingDeferred, null);
    }

    public SupervisorCheckpoint(int version, String planId, SupervisorState state, SupervisorState resumeState,
            SupervisorState restockResumeState, int currentChunkIndex, BuildPhase phase,
            VerificationStage verificationStage, RecoveryStage recoveryStage, int stableVerificationPasses,
            String lastVerificationFingerprint, MaterialQuantities consumedMaterials,
            MaterialQuantities withdrawnMaterials, MaterialQuantities missingMaterials,
            MaterialQuantities restockRequirement, String lastError, int verificationRetries,
            boolean repathAttempted, boolean safeReturnAttempted, boolean advisorAttempted,
            boolean withdrawalInFlight, boolean reconciliationRequired, String reconciliationDetail,
            String scheduleId, int scheduleCursor, int repairChunkIndex, int chunkCount) {
        this(version, planId, state, resumeState, restockResumeState, currentChunkIndex, phase,
                verificationStage, recoveryStage, stableVerificationPasses, lastVerificationFingerprint,
                consumedMaterials, withdrawnMaterials, missingMaterials, restockRequirement, lastError,
                verificationRetries, repathAttempted, safeReturnAttempted, advisorAttempted,
                withdrawalInFlight, reconciliationRequired, reconciliationDetail,
                scheduleId, scheduleCursor, repairChunkIndex, chunkCount, false);
    }

    public SupervisorCheckpoint(int version, String planId, SupervisorState state, SupervisorState resumeState,
            SupervisorState restockResumeState, int currentChunkIndex, BuildPhase phase,
            VerificationStage verificationStage, RecoveryStage recoveryStage, int stableVerificationPasses,
            String lastVerificationFingerprint, MaterialQuantities consumedMaterials,
            MaterialQuantities withdrawnMaterials, MaterialQuantities missingMaterials,
            MaterialQuantities restockRequirement, String lastError, int verificationRetries,
            boolean repathAttempted, boolean safeReturnAttempted, boolean advisorAttempted,
            boolean withdrawalInFlight, boolean reconciliationRequired, String reconciliationDetail,
            String scheduleId, int scheduleCursor, int repairChunkIndex) {
        this(version, planId, state, resumeState, restockResumeState, currentChunkIndex, phase,
                verificationStage, recoveryStage, stableVerificationPasses, lastVerificationFingerprint,
                consumedMaterials, withdrawnMaterials, missingMaterials, restockRequirement, lastError,
                verificationRetries, repathAttempted, safeReturnAttempted, advisorAttempted,
                withdrawalInFlight, reconciliationRequired, reconciliationDetail,
                scheduleId, scheduleCursor, repairChunkIndex, SchematicPlan.CHUNK_COUNT);
    }

    public SupervisorCheckpoint {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(resumeState, "resumeState");
        Objects.requireNonNull(restockResumeState, "restockResumeState");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(verificationStage, "verificationStage");
        Objects.requireNonNull(recoveryStage, "recoveryStage");
        lastVerificationFingerprint = lastVerificationFingerprint == null ? "" : lastVerificationFingerprint;
        Objects.requireNonNull(consumedMaterials, "consumedMaterials");
        Objects.requireNonNull(withdrawnMaterials, "withdrawnMaterials");
        Objects.requireNonNull(missingMaterials, "missingMaterials");
        Objects.requireNonNull(restockRequirement, "restockRequirement");
        lastError = lastError == null ? "" : lastError;
        reconciliationDetail = reconciliationDetail == null ? "" : reconciliationDetail;
        checkedPieces = checkedPieces == null ? CompletedPieces.none() : checkedPieces;

        Objects.requireNonNull(scheduleId, "scheduleId");
        // A version-one row-major schedule is still readable; loading maps it to the chunk tour.
        if (LayerBuildSchedule.plantingDeferred(scheduleId) != plantingDeferred) {
            throw new IllegalArgumentException("checkpoint schedule is incompatible with its planting mode; "
                    + "use a compatible installed mod and preserve the saved checkpoint");
        }
        PlanLimits.requireChunkCount(chunkCount);
        if (scheduleCursor < 0 || repairChunkIndex < -1 || repairChunkIndex >= chunkCount) {
            throw new IllegalArgumentException("invalid layer schedule cursor");
        }
        if (!checkedPieces.isEmpty() && repairChunkIndex >= 0) {
            throw new IllegalArgumentException("a chunk repair cannot skip checked pieces");
        }
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported checkpoint version " + version);
        }
        if (planId.isBlank()) {
            throw new IllegalArgumentException("checkpoint plan id must not be blank");
        }
        if (lastAppliedPlannedCredit != null && (!planId.equals(lastAppliedPlannedCredit.planId())
                || consumedMaterials.get(lastAppliedPlannedCredit.material()) < lastAppliedPlannedCredit.quantity())) {
            throw new IllegalArgumentException("checkpoint credit must match its plan and already-applied material total");
        }
        if (currentChunkIndex < 0 || currentChunkIndex > chunkCount) {
            throw new IllegalArgumentException("checkpoint chunk index must be between 0 and " + chunkCount);
        }
        if (stableVerificationPasses < 0 || stableVerificationPasses > 2) {
            throw new IllegalArgumentException("stable verification passes must be between 0 and 2");
        }
        if (verificationRetries < 0) {
            throw new IllegalArgumentException("verification retries must be non-negative");
        }
        if (reconciliationRequired == reconciliationDetail.isBlank()) {
            throw new IllegalArgumentException(
                    "reconciliation detail must be present exactly when reconciliation is required"
            );
        }
    }
}
