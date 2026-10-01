package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client-thread input adapter; never grants flight permission or moves the player directly. */
final class MinecraftFlightTakeoffPort implements FlightTakeoffSession.Port {
    private final MinecraftClient client;
    private final Supplier<String> environmentProblem;
    private record Context(Object world, Object player, Object connection) { }

    MinecraftFlightTakeoffPort(MinecraftClient client, Supplier<String> environmentProblem) {
        this.client = Objects.requireNonNull(client, "client");
        this.environmentProblem = Objects.requireNonNull(environmentProblem, "environmentProblem");
    }

    @Override public FlightTakeoffSession.Observation observe() {
        requireClientThread();
        boolean connected = client.world != null && client.player != null && client.getNetworkHandler() != null
                && client.getNetworkHandler().isConnectionOpen();
        Object context = connected ? new Context(client.world, client.player, client.getNetworkHandler()) : null;
        return new FlightTakeoffSession.Observation(connected, context,
                connected && client.player.getAbilities().allowFlying,
                connected && client.player.getAbilities().flying,
                connected && client.player.isOnGround(),
                !MinecraftBackgroundBuildAccess.allowsWorldActions(client), environmentProblem.get());
    }

    @Override public FlightTakeoffSession.JumpLease holdJump() {
        requireReady(false);
        KeyBinding binding = client.options.jumpKey;
        InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
        if (binding.isUnbound() || key.getCategory() == InputUtil.Type.SCANCODE) {
            throw new IllegalStateException("Bind Jump to a keyboard key or mouse button before takeoff.");
        }
        return new FlightTakeoffSession.JumpInputLease(binding.isPressed(), () -> physicallyPressed(binding),
                binding::setPressed);
    }

    @Override public void activateExistingFlight() {
        requireReady(true);
        if (!client.player.getAbilities().flying) {
            client.player.getAbilities().flying = true;
            client.player.sendAbilitiesUpdate();
        }
    }

    private boolean physicallyPressed(KeyBinding binding) {
        if (!client.isWindowFocused() || client.currentScreen != null || binding.isUnbound()) { return false; }
        InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
        long window = client.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return key.getCategory() == InputUtil.Type.KEYSYM && InputUtil.isKeyPressed(window, key.getCode());
    }

    private void requireReady(boolean airborne) {
        FlightTakeoffSession.Observation observation = observe();
        if (!observation.connected() || !observation.flightAllowed() || observation.screenBlocked()
                || (airborne && observation.onGround()) || !observation.blocker().isBlank()) {
            throw new IllegalStateException("Takeoff prerequisites changed before input was sent.");
        }
    }

    private void requireClientThread() {
        if (!client.isOnThread()) { throw new IllegalStateException("Takeoff must run on the client thread."); }
    }
}
