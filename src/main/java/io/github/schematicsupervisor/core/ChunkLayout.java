package io.github.schematicsupervisor.core;

import java.util.Objects;

/** Inclusive rectangular chunk coverage, ordered along X before advancing Z. */
public record ChunkLayout(ChunkCoordinate origin, int columns, int rows) {
    public ChunkLayout {
        Objects.requireNonNull(origin, "origin");
        if (columns < 1 || rows < 1) {
            throw new IllegalArgumentException("chunk layout dimensions must be positive");
        }
        PlanLimits.requireChunkCount((long) columns * rows);
        Math.addExact(origin.x(), columns - 1);
        Math.addExact(origin.z(), rows - 1);
    }

    public static ChunkLayout covering(BuildVolume volume) {
        int minX = Math.floorDiv(volume.minX(), 16);
        int minZ = Math.floorDiv(volume.minZ(), 16);
        return new ChunkLayout(new ChunkCoordinate(minX, minZ),
                Math.toIntExact((long) Math.floorDiv(volume.maxX(), 16) - minX + 1),
                Math.toIntExact((long) Math.floorDiv(volume.maxZ(), 16) - minZ + 1));
    }

    public int chunkCount() { return Math.multiplyExact(columns, rows); }

    public ChunkCoordinate chunk(int index) {
        Objects.checkIndex(index, chunkCount());
        return new ChunkCoordinate(Math.addExact(origin.x(), index % columns),
                Math.addExact(origin.z(), index / columns));
    }

    public boolean contains(ChunkCoordinate chunk) {
        long x = (long) chunk.x() - origin.x();
        long z = (long) chunk.z() - origin.z();
        return x >= 0 && x < columns && z >= 0 && z < rows;
    }
}
