package io.github.schematicsupervisor.core;

import java.util.Objects;

public record SupervisorStatus(
        SupervisorState state,
        BuildPhase phase,
        VerificationStage verificationStage,
        int currentChunkOrdinal,
        ChunkCoordinate currentChunk,
        RecoveryStage recoveryStage,
        int stableVerificationPasses,
        MaterialLedgerSnapshot materials,
        MaterialQuantities inventory,
        MaterialQuantities missingMaterials,
        String lastError,
        LayerProgress layerProgress,
        int chunkTotal,
        boolean plantingDeferred,
        long deferredSeedCells,
        boolean glowstoneAfterStructure
) {
    public SupervisorStatus(SupervisorState state, BuildPhase phase, VerificationStage verificationStage,
            int currentChunkOrdinal, ChunkCoordinate currentChunk, RecoveryStage recoveryStage,
            int stableVerificationPasses, MaterialLedgerSnapshot materials,
            MaterialQuantities inventory, MaterialQuantities missingMaterials, String lastError,
            LayerProgress layerProgress, int chunkTotal, boolean plantingDeferred, long deferredSeedCells) {
        this(state, phase, verificationStage, currentChunkOrdinal, currentChunk, recoveryStage,
                stableVerificationPasses, materials, inventory, missingMaterials, lastError,
                layerProgress, chunkTotal, plantingDeferred, deferredSeedCells, false);
    }
    public SupervisorStatus(SupervisorState state, BuildPhase phase, VerificationStage verificationStage,
            int currentChunkOrdinal, ChunkCoordinate currentChunk, RecoveryStage recoveryStage,
            int stableVerificationPasses, MaterialLedgerSnapshot materials,
            MaterialQuantities inventory, MaterialQuantities missingMaterials, String lastError,
            LayerProgress layerProgress, int chunkTotal) {
        this(state, phase, verificationStage, currentChunkOrdinal, currentChunk, recoveryStage,
                stableVerificationPasses, materials, inventory, missingMaterials, lastError,
                layerProgress, chunkTotal, false, 0);
    }
    public SupervisorStatus(SupervisorState state, BuildPhase phase, VerificationStage verificationStage,
            int currentChunkOrdinal, ChunkCoordinate currentChunk, RecoveryStage recoveryStage,
            int stableVerificationPasses, MaterialLedgerSnapshot materials,
            MaterialQuantities inventory, MaterialQuantities missingMaterials, String lastError,
            LayerProgress layerProgress) {
        this(state, phase, verificationStage, currentChunkOrdinal, currentChunk, recoveryStage,
                stableVerificationPasses, materials, inventory, missingMaterials, lastError,
                layerProgress, SchematicPlan.CHUNK_COUNT);
    }

    public SupervisorStatus(
            SupervisorState state, BuildPhase phase, VerificationStage verificationStage,
            int currentChunkOrdinal, ChunkCoordinate currentChunk, RecoveryStage recoveryStage,
            int stableVerificationPasses, MaterialLedgerSnapshot materials,
            MaterialQuantities inventory, MaterialQuantities missingMaterials, String lastError
    ) {
        this(state, phase, verificationStage, currentChunkOrdinal, currentChunk, recoveryStage,
                stableVerificationPasses, materials, inventory, missingMaterials, lastError, null);
    }

    public SupervisorStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(verificationStage, "verificationStage");
        Objects.requireNonNull(recoveryStage, "recoveryStage");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(missingMaterials, "missingMaterials");
        lastError = lastError == null ? "" : lastError;
        PlanLimits.requireChunkCount(chunkTotal);
        if (deferredSeedCells < 0 || (!plantingDeferred && deferredSeedCells != 0)) {
            throw new IllegalArgumentException("Deferred seed cells require deferred planting mode");
        }
        if (currentChunkOrdinal < 0 || currentChunkOrdinal > chunkTotal) {
            throw new IllegalArgumentException("current chunk ordinal must be between 0 and " + chunkTotal);
        }
        if ((currentChunkOrdinal == 0) != (currentChunk == null)) {
            throw new IllegalArgumentException("chunk coordinate and ordinal must either both be present or absent");
        }
    }
}
