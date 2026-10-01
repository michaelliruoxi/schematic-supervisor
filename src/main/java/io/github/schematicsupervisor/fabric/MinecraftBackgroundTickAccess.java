package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;

/** Client-thread lifecycle bridge for menu pause and inactive frame limiting. */
public final class MinecraftBackgroundTickAccess {
    private static Supplier<String> state = () -> "STOPPED";
    private static BooleanSupplier ownedWork = () -> false;

    private MinecraftBackgroundTickAccess() { }

    static void bind(Supplier<String> currentState, BooleanSupplier currentOwnedWork) {
        state = Objects.requireNonNull(currentState, "currentState");
        ownedWork = Objects.requireNonNull(currentOwnedWork, "currentOwnedWork");
    }

    static void clear() {
        state = () -> "STOPPED";
        ownedWork = () -> false;
    }

    public static boolean keepsWorldTicking(MinecraftClient client) {
        if (client.world == null || client.player == null) { return false; }
        return BackgroundTickPolicy.keepsWorldTicking(state.get(), ownedWork.getAsBoolean(), true,
                MinecraftBackgroundBuildAccess.allowsWorldActions(client), client.getOverlay() != null);
    }

    public static int frameLimit(MinecraftClient client, int vanillaLimit) {
        return BackgroundTickPolicy.frameLimit(vanillaLimit, keepsWorldTicking(client));
    }

    public static boolean suppressesAutomaticFocusMenu(MinecraftClient client) {
        return BackgroundTickPolicy.suppressesAutomaticFocusMenu(state.get(), ownedWork.getAsBoolean(),
                client.world != null && client.player != null,
                MinecraftBackgroundBuildAccess.allowsWorldActions(client), client.getOverlay() != null,
                client.isWindowFocused(), client.currentScreen != null);
    }
}
