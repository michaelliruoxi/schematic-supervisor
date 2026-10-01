package io.github.schematicsupervisor.fabric;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;

/** Client-thread-only packet evidence. This class sends no input and exposes no serialized item components. */
public final class ServerInventorySnapshotObserver {
    private static final ServerInventorySnapshotLedger<ItemStack> LEDGER =
            new ServerInventorySnapshotLedger<>(ItemStack::copy);

    private ServerInventorySnapshotObserver() { }

    public static final class FullSnapshot {
        private final ServerInventorySnapshotStamp stamp;
        private final List<ItemStack> slots;
        private final ItemStack cursor;
        private final int rows;

        private FullSnapshot(ServerInventorySnapshotLedger.Captured<ItemStack> captured, int rows) {
            stamp = captured.stamp();
            slots = captured.slots().stream().map(ItemStack::copy).toList();
            cursor = captured.cursorStack().copy();
            this.rows = rows;
        }

        public ServerInventorySnapshotStamp stamp() { return stamp; }
        public int rows() { return rows; }
        public List<ItemStack> slots() { return slots.stream().map(ItemStack::copy).toList(); }
        public ItemStack cursorStack() { return cursor.copy(); }
    }

    /** Called only by the TAIL injection after vanilla applied this server packet. */
    public static void afterInventory(ClientPlayNetworkHandler source, InventoryS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { return; }
        if (source != client.getNetworkHandler()) { return; }
        try {
            GenericContainerScreenHandler handler = currentGeneric(client);
            if (handler == null) {
                LEDGER.observeBinding(client.world, client.getNetworkHandler(), null, 0, 0);
                return;
            }
            // A player-screen or stale sync packet was not applied to this open container.
            if (packet.syncId() != handler.syncId) { return; }
            LEDGER.acceptFull(client.world, source, handler, handler.syncId, handler.slots.size(),
                    packet.syncId(), packet.revision(), packet.contents(), packet.cursorStack());
        } catch (RuntimeException unavailable) {
            LEDGER.invalidate();
        }
    }

    public static Optional<FullSnapshot> latestMatching(ClientWorld world, ClientPlayNetworkHandler connection,
                                                         GenericContainerScreenHandler expectedHandler) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { throw new IllegalStateException("Inventory evidence requires the client thread"); }
        try {
            GenericContainerScreenHandler current = currentGeneric(client);
            if (world != client.world || connection != client.getNetworkHandler() || current == null
                    || current != expectedHandler) {
                LEDGER.observeBinding(client.world, client.getNetworkHandler(), current,
                        current == null ? 0 : current.syncId, current == null ? 0 : current.slots.size());
                return Optional.empty();
            }
            return LEDGER.latestMatching(world, connection, current, current.syncId, current.slots.size())
                    .map(captured -> new FullSnapshot(captured, current.getRows()));
        } catch (RuntimeException unavailable) {
            LEDGER.invalidate();
            return Optional.empty();
        }
    }

    /** Runtime may call this on disconnect or profile teardown; the sequence never resets. */
    public static void invalidate() {
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw new IllegalStateException("Inventory evidence requires the client thread");
        }
        LEDGER.invalidate();
    }

    private static GenericContainerScreenHandler currentGeneric(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null
                || client.player.currentScreenHandler.getClass() != GenericContainerScreenHandler.class) { return null; }
        GenericContainerScreenHandler handler = (GenericContainerScreenHandler) client.player.currentScreenHandler;
        int rows = handler.getRows();
        return handler.syncId > 0 && rows >= 1 && rows <= 6 && handler.slots.size() == rows * 9 + 36
                ? handler : null;
    }
}
