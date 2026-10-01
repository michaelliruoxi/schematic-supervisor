package io.github.schematicsupervisor.fabric;

import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.math.BlockPos;

/** Reuses collision-checked flight; never mines, places, opens containers, or changes inventory. */
final class MinecraftPausedApproachPort implements PausedApproachSession.Port {
    private final MinecraftClient client;
    private final BlockPos target;
    private final Supplier<String> guard;
    private final MinecraftFlightNavigation navigation;
    private record Context(Object world, Object player, Object connection) { }

    MinecraftPausedApproachPort(MinecraftClient client, BlockPos target, Supplier<String> guard) {
        this.client = client;
        this.target = target.toImmutable();
        this.guard = guard;
        navigation = new MinecraftFlightNavigation(client);
    }

    @Override public PausedApproachSession.Observation observe() {
        boolean connected = client.world != null && client.player != null && client.getNetworkHandler() != null
                && client.getNetworkHandler().isConnectionOpen();
        boolean chat = client.currentScreen instanceof ChatScreen;
        return new PausedApproachSession.Observation(
                connected ? new Context(client.world, client.player, client.getNetworkHandler()) : null,
                connected && client.player.getAbilities().allowFlying && client.player.getAbilities().flying,
                chat, !MinecraftBackgroundBuildAccess.allowsWorldActions(client), guard.get());
    }

    @Override public void begin() {
        navigation.begin(target, Math.max(0.5, Math.min(5.5, client.player.getBlockInteractionRange() - 0.35)));
        faceReachedTarget();
    }
    @Override public void tick() { navigation.tick(); faceReachedTarget(); }
    @Override public boolean arrived() { return navigation.arrived(); }
    @Override public boolean failed() { return navigation.failed(); }
    @Override public String detail() { return target.toShortString() + ": " + navigation.detail(); }
    @Override public void stop() { navigation.stop(); }

    private void faceReachedTarget() {
        if (!navigation.arrived()) { return; }
        var result = ExactInteractionRay.trace(client.world, client.player, client.player.getEyePos(),
                target, null, Math.max(0.5, Math.min(5.5, client.player.getBlockInteractionRange() - 0.35)));
        if (!result.accepted()) { return; }
        var aim = result.hit().getPos().subtract(client.player.getEyePos());
        client.player.setYaw((float) (Math.toDegrees(Math.atan2(aim.z, aim.x)) - 90.0));
        client.player.setPitch((float) -Math.toDegrees(Math.atan2(aim.y, Math.sqrt(aim.x * aim.x + aim.z * aim.z))));
    }
}
