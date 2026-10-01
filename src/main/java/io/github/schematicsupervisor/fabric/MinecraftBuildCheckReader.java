package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Objects;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Reads one client world for the start build check; it never requests or waits for chunks. */
final class MinecraftBuildCheckReader implements BuildCheckSession.WorldReader {
    private final ClientWorld world;
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final IdentityHashMap<net.minecraft.block.BlockState, BlockState> converted = new IdentityHashMap<>();

    MinecraftBuildCheckReader(ClientWorld world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    ClientWorld world() {
        return world;
    }

    @Override
    public boolean chunkReceived(int chunkX, int chunkZ) {
        return ClientChunkAvailability.isLoaded(world, chunkX, chunkZ);
    }

    @Override
    public BlockState blockState(int x, int y, int z) {
        return converted.computeIfAbsent(world.getBlockState(cursor.set(x, y, z)), MinecraftBlockStates::toCore);
    }

    @Override
    public boolean predictionsPending() {
        return !pending().isEmpty();
    }

    @Override
    public boolean predictionPending(int x, int y, int z) {
        return pending().containsKey(BlockPos.asLong(x, y, z));
    }

    private Long2ObjectOpenHashMap<?> pending() {
        var manager = ((ClientWorldPendingUpdatesAccessor) world).supervisor$getPendingUpdateManager();
        return ((PendingBlockUpdatesAccessor) manager).supervisor$getPendingBlockUpdates();
    }
}
