package io.github.schematicsupervisor.core;

import java.util.List;
import java.util.Objects;

public record SchematicPlan(
        String planId,
        ChunkLayout layout,
        BuildVolume buildVolume,
        List<ChunkPlan> chunks,
        boolean plantingDeferred,
        boolean glowstoneAfterStructure
) {
    // Compatibility dimensions for previously compiled plans and saved checkpoints.
    public static final int GRID_SIZE = 7;
    public static final int CHUNK_COUNT = GRID_SIZE * GRID_SIZE;

    public SchematicPlan(String planId, ChunkLayout layout, BuildVolume buildVolume,
                         List<ChunkPlan> chunks) {
        this(planId, layout, buildVolume, chunks, false);
    }

    public SchematicPlan(String planId, ChunkLayout layout, BuildVolume buildVolume,
                         List<ChunkPlan> chunks, boolean plantingDeferred) {
        this(planId, layout, buildVolume, chunks, plantingDeferred, false);
    }

    public SchematicPlan(String planId, ChunkCoordinate originChunk, BuildVolume buildVolume,
                         List<ChunkPlan> chunks) {
        this(planId, new ChunkLayout(originChunk, GRID_SIZE, GRID_SIZE), buildVolume, chunks);
    }

    public SchematicPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(buildVolume, "buildVolume");
        Objects.requireNonNull(chunks, "chunks");
        PlanLimits.requireVolume(buildVolume);
        if (planId.isBlank()) {
            throw new IllegalArgumentException("planId must not be blank");
        }
        if (chunks.size() != layout.chunkCount()) {
            throw new IllegalArgumentException("plan chunk count must match its rectangular layout");
        }
        ChunkCoordinate minimumVolumeChunk = ChunkCoordinate.containing(
                new BlockPosition(buildVolume.minX(), buildVolume.minY(), buildVolume.minZ())
        );
        ChunkCoordinate maximumVolumeChunk = ChunkCoordinate.containing(
                new BlockPosition(buildVolume.maxX(), buildVolume.maxY(), buildVolume.maxZ())
        );
        if (!layout.contains(minimumVolumeChunk) || !layout.contains(maximumVolumeChunk)) {
            throw new IllegalArgumentException("build volume must fit inside the chunk layout");
        }

        long targetCount = 0;
        for (int index = 0; index < chunks.size(); index++) {
            ChunkPlan chunk = Objects.requireNonNull(chunks.get(index), "chunk");
            if (!layout.chunk(index).equals(chunk.chunk())) {
                throw new IllegalArgumentException("chunks must be row-major from the origin; expected "
                        + layout.chunk(index) + " at index " + index);
            }
            targetCount += chunk.expectedBlocks().size();
            PlanLimits.requireTargetCount(targetCount);
        }
        chunks = List.copyOf(chunks);
    }

    public ChunkCoordinate originChunk() { return layout.origin(); }
    public int chunkCount() { return layout.chunkCount(); }

    public SchematicPlan withPlantingDeferred(boolean deferred) {
        return deferred == plantingDeferred ? this
                : new SchematicPlan(planId, layout, buildVolume, chunks, deferred, glowstoneAfterStructure);
    }

    public SchematicPlan withGlowstoneAfterStructure(boolean enabled) {
        return enabled == glowstoneAfterStructure ? this
                : new SchematicPlan(planId, layout, buildVolume, chunks, plantingDeferred, enabled);
    }

    public long deferredSeedCells() {
        return plantingDeferred ? chunks.stream().mapToLong(chunk -> chunk.plantTargets().size()).sum() : 0;
    }

    public ChunkPlan chunk(int index) {
        return chunks.get(Objects.checkIndex(index, chunkCount()));
    }

    public MaterialQuantities plannedMaterials() {
        MaterialQuantities result = MaterialQuantities.empty();
        for (ChunkPlan chunk : chunks) {
            result = result.plus(plantingDeferred ? chunk.ordinaryMaterials() : chunk.plannedConsumables());
        }
        return result;
    }
}
