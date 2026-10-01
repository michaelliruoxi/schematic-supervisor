package io.github.schematicsupervisor.fabric;

import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;

/** TAIL packet observations only; local selected-slot changes and predicted damage are never receipts. */
public final class ServerPlayerInventoryObserver {
    private static final PlayerInventoryUpdateLedger<ItemStack> LEDGER =
            new PlayerInventoryUpdateLedger<>(ItemStack::copy, ItemStack::areEqual);
    private ServerPlayerInventoryObserver() { }

    static PlayerInventoryUpdateLedger.Stamp mark(MinecraftClient client) {
        requireThread(client);
        return LEDGER.mark(client.world, client.player, client.getNetworkHandler());
    }

    static Optional<PlayerInventoryUpdateLedger.Update<ItemStack>> latest(MinecraftClient client, int slot) {
        requireThread(client);
        return LEDGER.latest(client.world, client.player, client.getNetworkHandler(), slot);
    }

    public static void afterSlot(ClientPlayNetworkHandler source, ScreenHandlerSlotUpdateS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!acceptSource(client, source) || packet.getSyncId() != 0) { return; }
        captureHandlerSlot(client, packet.getSlot(), packet.getStack());
    }

    public static void afterPlayerSlot(ClientPlayNetworkHandler source, SetPlayerInventoryS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!acceptSource(client, source) || packet.slot() < 0 || packet.slot() >= 36) { return; }
        LEDGER.applied(client.world, client.player, source, packet.slot(), packet.contents(),
                client.player.getInventory().getStack(packet.slot()));
    }

    public static void afterInventory(ClientPlayNetworkHandler source, InventoryS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!acceptSource(client, source) || packet.syncId() != 0
                || packet.contents().size() != client.player.playerScreenHandler.slots.size()) { return; }
        for (int slot = 0; slot < packet.contents().size(); slot++) {
            captureHandlerSlot(client, slot, packet.contents().get(slot));
        }
    }

    private static void captureHandlerSlot(MinecraftClient client, int handlerSlot, ItemStack packet) {
        var handler = client.player.playerScreenHandler;
        if (handlerSlot < 0 || handlerSlot >= handler.slots.size()) { return; }
        var slot = handler.getSlot(handlerSlot);
        if (slot.inventory != client.player.getInventory() || slot.getIndex() < 0 || slot.getIndex() >= 36) { return; }
        LEDGER.applied(client.world, client.player, client.getNetworkHandler(), slot.getIndex(), packet, slot.getStack());
    }

    private static boolean acceptSource(MinecraftClient client, ClientPlayNetworkHandler source) {
        return client.isOnThread() && client.world != null && client.player != null
                && source == client.getNetworkHandler() && source.isConnectionOpen();
    }

    private static void requireThread(MinecraftClient client) {
        if (!client.isOnThread()) { throw new IllegalStateException("Repair packet evidence requires the client thread"); }
    }
}
