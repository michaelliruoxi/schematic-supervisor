package io.github.schematicsupervisor.fabric;

import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.SetCursorItemS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;

/** Passive applied cursor packets for exact generic handlers; no clicks, commands or inventory writes. */
public final class ServerShopCursorObserver {
    private static final ServerShopCursorLedger<ItemStack> LEDGER = new ServerShopCursorLedger<>(ItemStack::copy);
    private ServerShopCursorObserver() { }

    public static final class Snapshot {
        private final ServerShopCursorStamp stamp;
        private final ItemStack cursor;
        private Snapshot(ServerShopCursorLedger.Captured<ItemStack> captured) {
            stamp = captured.stamp();
            cursor = captured.cursorStack().copy();
        }
        public ServerShopCursorStamp stamp() { return stamp; }
        public ItemStack cursorStack() { return cursor.copy(); }
    }

    /** Read immediately before an authorized quantity click; this never invents a packet acknowledgement. */
    public static Optional<ServerShopCursorStamp> mark(ClientWorld world, ClientPlayNetworkHandler connection,
                                                       GenericContainerScreenHandler expectedHandler) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        try {
            GenericContainerScreenHandler current = currentGeneric(client);
            if (world != client.world || connection != client.getNetworkHandler() || current == null
                    || current != expectedHandler) {
                observeCurrentBinding(client, current);
                return Optional.empty();
            }
            return LEDGER.mark(world, connection, current, current.syncId, current.slots.size());
        } catch (RuntimeException unavailable) { LEDGER.invalidate(); return Optional.empty(); }
    }

    public static Optional<Snapshot> latestMatching(ClientWorld world, ClientPlayNetworkHandler connection,
                                                     GenericContainerScreenHandler expectedHandler) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        try {
            GenericContainerScreenHandler current = currentGeneric(client);
            if (world != client.world || connection != client.getNetworkHandler() || current == null
                    || current != expectedHandler) {
                observeCurrentBinding(client, current);
                return Optional.empty();
            }
            return LEDGER.latestMatching(world, connection, current, current.syncId, current.slots.size()).map(Snapshot::new);
        } catch (RuntimeException unavailable) { LEDGER.invalidate(); return Optional.empty(); }
    }

    /** TAIL only: vanilla applied this full packet to the matching current generic handler. */
    public static void afterInventory(ClientPlayNetworkHandler source, InventoryS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || source != client.getNetworkHandler()) { return; }
        try {
            GenericContainerScreenHandler handler = currentGeneric(client);
            if (handler == null) { observeCurrentBinding(client, null); return; }
            if (packet.syncId() != handler.syncId || packet.contents() == null) { return; }
            LEDGER.acceptFull(client.world, source, handler, handler.syncId, handler.slots.size(),
                    packet.syncId(), packet.contents().size(), packet.cursorStack(),
                    ItemStack.areEqual(handler.getCursorStack(), packet.cursorStack()));
        } catch (RuntimeException unavailable) { LEDGER.invalidate(); }
    }

    /** In 1.21.8 the dedicated cursor packet has no sync ID and vanilla skips creative inventory screens. */
    public static void afterCursor(ClientPlayNetworkHandler source, SetCursorItemS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || source != client.getNetworkHandler()) { return; }
        try {
            GenericContainerScreenHandler handler = currentGeneric(client);
            if (handler == null) { observeCurrentBinding(client, null); return; }
            if (client.currentScreen instanceof CreativeInventoryScreen) { return; }
            LEDGER.acceptCursor(client.world, source, handler, handler.syncId, handler.slots.size(),
                    packet.contents(), ItemStack.areEqual(handler.getCursorStack(), packet.contents()));
        } catch (RuntimeException unavailable) { LEDGER.invalidate(); }
    }

    public static void invalidate() {
        requireClientThread(MinecraftClient.getInstance());
        LEDGER.invalidate();
    }

    private static void observeCurrentBinding(MinecraftClient client, GenericContainerScreenHandler current) {
        LEDGER.mark(client.world, client.getNetworkHandler(), current,
                current == null ? 0 : current.syncId, current == null ? 0 : current.slots.size());
    }
    private static GenericContainerScreenHandler currentGeneric(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null
                || client.player.currentScreenHandler.getClass() != GenericContainerScreenHandler.class) { return null; }
        GenericContainerScreenHandler handler = (GenericContainerScreenHandler) client.player.currentScreenHandler;
        int rows = handler.getRows();
        return handler.syncId > 0 && rows >= 1 && rows <= 6 && handler.slots.size() == rows * 9 + 36 ? handler : null;
    }
    private static void requireClientThread(MinecraftClient client) {
        if (!client.isOnThread()) { throw new IllegalStateException("Server cursor evidence requires the client thread"); }
    }
}
