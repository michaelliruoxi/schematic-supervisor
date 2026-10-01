package io.github.schematicsupervisor.fabric.mixin;

import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the vanilla prediction manager for interaction confirmation. */
@Mixin(ClientWorld.class)
public interface ClientWorldPendingUpdatesAccessor {
    @Accessor("pendingUpdateManager")
    PendingUpdateManager supervisor$getPendingUpdateManager();
}
