package io.github.schematicsupervisor.fabric.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.network.PendingUpdateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The map is inspected only; vanilla remains responsible for acknowledging predictions. */
@Mixin(PendingUpdateManager.class)
public interface PendingBlockUpdatesAccessor {
    @Accessor("blockPosToPendingUpdate")
    Long2ObjectOpenHashMap<?> supervisor$getPendingBlockUpdates();
}
