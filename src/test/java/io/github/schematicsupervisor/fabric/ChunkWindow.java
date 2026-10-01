package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;

/** Test model of the chunks a client has received: a square of chunks within the view distance of a center. */
record ChunkWindow(int centerChunkX, int centerChunkZ, int viewDistance) {
    ChunkWindow {
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("Minecraft view distance is 2 to 32 chunks");
        }
    }

    static ChunkWindow around(BlockPosition center, int viewDistance) {
        return new ChunkWindow(Math.floorDiv(center.x(), 16), Math.floorDiv(center.z(), 16), viewDistance);
    }

    boolean received(BlockPosition point) {
        return Math.abs(Math.floorDiv(point.x(), 16) - centerChunkX) <= viewDistance
                && Math.abs(Math.floorDiv(point.z(), 16) - centerChunkZ) <= viewDistance;
    }
}
