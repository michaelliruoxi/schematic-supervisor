package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

/** Passive observer: no packets, chunk requests, navigation, inputs, or persisted build writes. */
final class MinecraftSoilWatchpoints {
    private SchematicPlan boundPlan;
    private RunContext boundContext;
    private ClientWorld boundWorld;
    private SoilWatchpoints watchpoints;
    private Long lastSampleNanos;
    private SoilWatchpointObservation observation = unavailable("No selected plan", null);

    void tick(MinecraftClient client, SchematicPlan plan, RunContext context, boolean contextMatches) {
        if (!client.isOnThread()) { throw new IllegalStateException("Soil sampling requires the client thread"); }
        long nanos = System.nanoTime();
        if (lastSampleNanos != null && nanos - lastSampleNanos < SoilWatchpoints.SAMPLE_INTERVAL_NANOS) { return; }
        lastSampleNanos = nanos;
        Instant now = Instant.now();
        if (plan == null || context == null) {
            boundPlan = null;
            boundContext = null;
            boundWorld = null;
            watchpoints = null;
            observation = unavailable("No selected plan and world binding", now);
            return;
        }
        try {
            boolean sourceChanged = boundPlan != plan || !Objects.equals(boundContext, context);
            boolean connected = client.world != null && client.player != null && contextMatches;
            if (sourceChanged || connected && client.world != boundWorld) {
                boundPlan = plan;
                boundContext = context;
                boundWorld = connected ? client.world : null;
                ChunkCoordinate preferred = connected ? ChunkCoordinate.containing(new BlockPosition(
                        client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())) : null;
                watchpoints = new SoilWatchpoints(plan, context, UUID.randomUUID().toString(), preferred);
            }
            SoilWatchpoints.Source source = connected && client.world == boundWorld
                    ? new WorldSource(boundWorld) : null;
            watchpoints.tick(nanos, now, source);
            observation = watchpoints.observation();
        } catch (RuntimeException exception) {
            // Optional telemetry must not become a build control failure.
            if (watchpoints != null) {
                watchpoints.unavailable(now);
                observation = watchpoints.observation();
            } else { observation = unavailable("Soil observation unavailable", now); }
        }
    }

    SoilWatchpointObservation observation() { return observation; }

    private static SoilWatchpointObservation unavailable(String reason, Instant at) {
        return new SoilWatchpointObservation(false, reason, null, null, null, at, List.of());
    }

    private record WorldSource(ClientWorld world) implements SoilWatchpoints.Source {
        @Override public boolean received(BlockPosition position) {
            return ClientChunkAvailability.isLoaded(world, nativePosition(position));
        }

        @Override public boolean predictionPending(BlockPosition position) {
            return pending().containsKey(nativePosition(position).asLong());
        }

        @Override public BlockState state(BlockPosition position) {
            return MinecraftBlockStates.toCore(world.getBlockState(nativePosition(position)));
        }

        @Override public boolean water(BlockPosition position) {
            return world.getFluidState(nativePosition(position)).isIn(FluidTags.WATER);
        }

        @Override public Boolean rainAt(BlockPosition position) {
            // The rain predicate uses the column heightmap; any outstanding client prediction
            // could affect that column. An empty pending map avoids reporting a predicted roof.
            return pending().isEmpty() ? world.hasRain(nativePosition(position)) : null;
        }

        private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?> pending() {
            var manager = ((ClientWorldPendingUpdatesAccessor) world).supervisor$getPendingUpdateManager();
            return ((PendingBlockUpdatesAccessor) manager).supervisor$getPendingBlockUpdates();
        }

        private static BlockPos nativePosition(BlockPosition position) {
            return new BlockPos(position.x(), position.y(), position.z());
        }
    }
}
