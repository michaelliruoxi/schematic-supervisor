package io.github.schematicsupervisor.core;

/**
 * Inclusive placement bounds. Expected air is represented implicitly inside this volume.
 */
public record BuildVolume(
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {
    public BuildVolume {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("build volume minimums must not exceed maximums");
        }
        Math.multiplyExact(
                Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
                (long) maxZ - minZ + 1
        );
    }

    public boolean contains(BlockPosition position) {
        return position.x() >= minX && position.x() <= maxX
                && position.y() >= minY && position.y() <= maxY
                && position.z() >= minZ && position.z() <= maxZ;
    }

    public long blockCount() {
        return Math.multiplyExact(
                Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1),
                (long) maxZ - minZ + 1
        );
    }
}
