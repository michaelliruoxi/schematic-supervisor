package io.github.schematicsupervisor.fabric.mixin;

import io.github.schematicsupervisor.fabric.MinecraftBackgroundTickAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Allows ordinary menus to coexist with active construction, including in a local world. */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientBackgroundMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/screen/Screen;shouldPause()Z"))
    private boolean supervisor$keepActiveWorldTicking(Screen screen) {
        return screen.shouldPause()
                && !MinecraftBackgroundTickAccess.keepsWorldTicking((MinecraftClient) (Object) this);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/option/InactivityFpsLimiter;update()I"))
    private int supervisor$keepBackgroundTickCadence(InactivityFpsLimiter limiter) {
        return MinecraftBackgroundTickAccess.frameLimit((MinecraftClient) (Object) this, limiter.update());
    }
}
