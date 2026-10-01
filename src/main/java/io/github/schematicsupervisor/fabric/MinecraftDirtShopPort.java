package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Client-thread adapter for the fixed dirt shop route. */
final class MinecraftDirtShopPort implements DirtShopPurchase.Port {
    private final MinecraftClient client;
    private MossToolCustody mossCustody;
    private Object observedWorld;
    private Object observedConnection;
    private long contextIdentity;
    private ScreenHandler observedHandler;
    private long menuIdentity;
    private ScreenHandler clickedHandler;
    private Object clickedWorld;
    private Object clickedConnection;
    private ItemStack predictedCursor = ItemStack.EMPTY;

    MinecraftDirtShopPort(MinecraftClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    void configureMossCustody(MossToolCustody custody) { mossCustody = Objects.requireNonNull(custody); }

    @Override
    public DirtShopPurchase.Observation observe() {
        requireClientThread();
        if (observedWorld != client.world || observedConnection != client.getNetworkHandler()) {
            observedWorld = client.world;
            observedConnection = client.getNetworkHandler();
            contextIdentity++;
        }
        boolean connected = client.player != null && client.world != null
                && client.getNetworkHandler() != null && client.getNetworkHandler().isConnectionOpen();
        if (!connected) {
            clearClickPrediction();
            return new DirtShopPurchase.Observation(false, Long.toString(contextIdentity),
                    0, 0, null, true);
        }
        int freeSlots = 0;
        int dirtCount = 0;
        // Only the 36 normal inventory/hotbar slots can receive full stacks.
        // Partial stacks, armor, offhand, and menu decorations are not free slots.
        for (ItemStack stack : client.player.getInventory().getMainStacks()) {
            if (stack.isEmpty()) {
                freeSlots++;
            } else if (stack.isOf(Items.DIRT)) {
                dirtCount += stack.getCount();
            }
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        DirtShopPurchase.Menu menu = readMenu(handler);
        return new DirtShopPurchase.Observation(true, Long.toString(contextIdentity),
                freeSlots, dirtCount, menu, handler.getCursorStack().isEmpty(), ownsClickCursor(handler));
    }

    @Override
    public void sendShop() {
        requireClientThread();
        if (client.currentScreen != null && !(client.currentScreen instanceof ChatScreen)) {
            // The shop menu replaces an idle Inventory or Settings page only through the guarded transition.
            MinecraftBackgroundBuildAccess.leavePassiveScreenForRestock(client, () -> true);
        }
        if (client.player == null || client.getNetworkHandler() == null
                || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty()
                || (client.currentScreen != null && !(client.currentScreen instanceof ChatScreen))) {
            throw new IllegalStateException("Close the current screen before buying dirt.");
        }
        client.getNetworkHandler().sendChatCommand(ShopSettings.current().layout().command());
    }

    @Override
    public void click(DirtShopPurchase.Menu expectedMenu, int slotId) {
        requireClientThread();
        ScreenHandler handler = requireMenu(expectedMenu);
        if (!(handler instanceof GenericContainerScreenHandler)
                || slotId < 0 || slotId >= handler.slots.size()) {
            throw new IllegalStateException("The dirt shop menu changed before its click.");
        }
        Slot slot = handler.slots.get(slotId);
        if (slot.inventory == client.player.getInventory() || slot.getStack().isEmpty()
                || !handler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("The dirt shop button is no longer available.");
        }
        if (client.interactionManager == null || client.getNetworkHandler() == null) {
            throw new IllegalStateException("Disconnected before the dirt shop click.");
        }
        ItemStack clickedStack = slot.getStack().copy();
        clearClickPrediction();
        // Use the same prediction and changed-slot hashes as a normal left click.
        Runnable click = () -> client.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.PICKUP, client.player);
        if (mossCustody == null) { click.run(); }
        else { mossCustody.clickSlot(handler, slotId, 0, SlotActionType.PICKUP, click); }
        ItemStack cursor = handler.getCursorStack();
        if (!cursor.isEmpty() && cursor.getCount() == clickedStack.getCount()
                && ItemStack.areItemsAndComponentsEqual(cursor, clickedStack)) {
            clickedHandler = handler;
            clickedWorld = client.world;
            clickedConnection = client.getNetworkHandler();
            predictedCursor = cursor.copy();
        }
    }

    @Override
    public void close(DirtShopPurchase.Menu expectedMenu) {
        requireClientThread();
        if (client.player == null || expectedMenu == null) {
            return;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        DirtShopPurchase.Menu current = readMenu(handler);
        if (current != null && current.identity() == expectedMenu.identity()
                && current.syncId() == expectedMenu.syncId() && handler.getCursorStack().isEmpty()) {
            client.player.closeHandledScreen();
        }
    }

    private ScreenHandler requireMenu(DirtShopPurchase.Menu expected) {
        if (client.player == null) {
            throw new IllegalStateException("Disconnected while buying dirt.");
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        DirtShopPurchase.Menu current = readMenu(handler);
        if (!Objects.equals(current, expected)) {
            throw new IllegalStateException("The dirt shop menu changed before its click.");
        }
        return handler;
    }

    private boolean ownsClickCursor(ScreenHandler handler) {
        ItemStack cursor = handler.getCursorStack();
        boolean owned = !predictedCursor.isEmpty() && clickedHandler == handler
                && clickedWorld == client.world && clickedConnection == client.getNetworkHandler()
                && cursor.getCount() == predictedCursor.getCount()
                && ItemStack.areItemsAndComponentsEqual(cursor, predictedCursor);
        if (!owned) {
            clearClickPrediction();
        }
        return owned;
    }

    private void clearClickPrediction() {
        clickedHandler = null;
        clickedWorld = null;
        clickedConnection = null;
        predictedCursor = ItemStack.EMPTY;
    }

    private DirtShopPurchase.Menu readMenu(ScreenHandler handler) {
        if (observedHandler != handler) {
            observedHandler = handler;
            menuIdentity++;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> screen)
                || screen.getScreenHandler() != handler
                || handler == client.player.playerScreenHandler) {
            return null;
        }
        List<DirtShopPurchase.Entry> entries = new ArrayList<>();
        if (handler instanceof GenericContainerScreenHandler) {
            for (Slot slot : handler.slots) {
                if (slot.inventory == client.player.getInventory() || !slot.hasStack()) {
                    continue;
                }
                ItemStack stack = slot.getStack();
                LoreComponent lore = stack.get(DataComponentTypes.LORE);
                entries.add(new DirtShopPurchase.Entry(slot.id, stack.getName().getString(),
                        lore == null ? List.of() : lore.lines().stream()
                                .map(line -> line.getString()).toList(), stack.isOf(Items.DIRT),
                        Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount()));
            }
        }
        return new DirtShopPurchase.Menu(menuIdentity, handler.syncId,
                screen.getTitle().getString(), entries);
    }

    private void requireClientThread() {
        if (!client.isOnThread()) {
            throw new IllegalStateException("Dirt shop actions must run on the client thread.");
        }
    }
}
