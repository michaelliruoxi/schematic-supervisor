package io.github.schematicsupervisor.fabric.mixin;

import io.github.schematicsupervisor.fabric.OwnedBlockBreaking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps exact-target mining alive without holding the global attack key. */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientBreakingMixin {
    @Redirect(method = "handleBlockBreaking", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/network/ClientPlayerInteractionManager;cancelBlockBreaking()V"))
    private void supervisor$preserveOwnedMining(ClientPlayerInteractionManager manager) {
        if (!OwnedBlockBreaking.isOwned(manager)) { manager.cancelBlockBreaking(); }
    }
}
