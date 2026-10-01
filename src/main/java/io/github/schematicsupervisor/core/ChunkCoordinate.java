package io.github.schematicsupervisor.core;

public record ChunkCoordinate(int x, int z) implements Comparable<ChunkCoordinate> {
    public static ChunkCoordinate containing(BlockPosition position) {
        return new ChunkCoordinate(Math.floorDiv(position.x(), 16), Math.floorDiv(position.z(), 16));
    }

    public boolean contains(BlockPosition position) {
        return equals(containing(position));
    }

    @Override
    public int compareTo(ChunkCoordinate other) {
        int byZ = Integer.compare(z, other.z);
        return byZ != 0 ? byZ : Integer.compare(x, other.x);
    }
}
