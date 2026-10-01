package io.github.schematicsupervisor.core;

/**
 * A Minecraft block coordinate used only by deterministic planning and execution.
 */
public record BlockPosition(int x, int y, int z) implements Comparable<BlockPosition> {
    /**
     * Mix coordinates before hashing dense planes. The record's linear default hash clusters
     * hundreds of thousands of farm coordinates in immutable sets that use linear probing.
     */
    @Override
    public int hashCode() {
        int hash = mixCoordinate(x);
        hash = Integer.rotateLeft(hash, 13) * 5 + 0xe6546b64;
        hash ^= mixCoordinate(y);
        hash = Integer.rotateLeft(hash, 13) * 5 + 0xe6546b64;
        hash ^= mixCoordinate(z);
        hash ^= 12;
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        return hash ^ (hash >>> 16);
    }

    private static int mixCoordinate(int coordinate) {
        return Integer.rotateLeft(coordinate * 0xcc9e2d51, 15) * 0x1b873593;
    }

    @Override
    public int compareTo(BlockPosition other) {
        int byY = Integer.compare(y, other.y);
        if (byY != 0) {
            return byY;
        }
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }
}
