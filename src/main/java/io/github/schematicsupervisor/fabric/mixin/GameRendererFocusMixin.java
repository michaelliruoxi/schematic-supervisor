package io.github.schematicsupervisor.fabric.mixin;

import io.github.schematicsupervisor.fabric.MinecraftBackgroundTickAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps loss of focus from inserting an unsolicited menu into an active build's restock route. */
@Mixin(GameRenderer.class)
abstract class GameRendererFocusMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/MinecraftClient;openGameMenu(Z)V"))
    private void supervisor$preserveUnfocusedBuild(MinecraftClient client, boolean pauseOnly) {
        // This call site is the renderer's automatic lost-focus path, not keyboard Escape.
        if (!pauseOnly && MinecraftBackgroundTickAccess.suppressesAutomaticFocusMenu(client)) { return; }
        client.openGameMenu(pauseOnly);
    }
}
