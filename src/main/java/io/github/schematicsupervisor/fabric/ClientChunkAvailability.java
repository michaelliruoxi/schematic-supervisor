package io.github.schematicsupervisor.fabric;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.WorldChunk;

/** Checks received client data without accepting the world's empty-chunk fallback. */
final class ClientChunkAvailability {
    private ClientChunkAvailability() { }

    static boolean isLoaded(ClientWorld world, int chunkX, int chunkZ) {
        if (world == null) { return false; }
        // ClientWorld.isChunkLoaded always returns true in this game version.
        WorldChunk chunk = world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return chunk != null && !(chunk instanceof EmptyChunk)
                && chunk.getPos().x == chunkX && chunk.getPos().z == chunkZ;
    }

    static boolean isLoaded(ClientWorld world, BlockPos position) {
        return isLoaded(world, position.getX() >> 4, position.getZ() >> 4);
    }
}
