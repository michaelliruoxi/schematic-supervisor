package io.github.schematicsupervisor.fabric;

import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import org.lwjgl.glfw.GLFW;

/** Client-thread screen facts shared by construction, movement, and vanilla input hooks. */
public final class MinecraftBackgroundBuildAccess {
    // The idle page most recently left for owned container work, reopened once that work settles.
    private static LeftPage leftPage;

    private record LeftPage(Screen screen, boolean inventory, Object world, Object player, Object connection) { }

    private MinecraftBackgroundBuildAccess() { }

    public static boolean allowsWorldActions(MinecraftClient client) {
        return BackgroundBuildPolicy.allowsWorldActions(screen(client.currentScreen),
                hasPlayerHandler(client), hasEmptyCursor(client));
    }

    static boolean allowsHotbarTransfer(MinecraftClient client) {
        return BackgroundBuildPolicy.allowsHotbarTransfer(screen(client.currentScreen),
                hasPlayerHandler(client), hasEmptyCursor(client));
    }

    static OptionalInt hotbarDestination(MinecraftClient client) {
        if (client.player == null) { return OptionalInt.empty(); }
        var inventory = client.player.getInventory();
        return BackgroundBuildPolicy.hotbarDestination(screen(client.currentScreen),
                hasPlayerHandler(client), hasEmptyCursor(client), inventory.getSelectedSlot(),
                net.minecraft.entity.player.PlayerInventory.getHotbarSize(),
                index -> inventory.getStack(index).isEmpty(),
                index -> PlainInteractionItems.plainBuildOrPickup(inventory.getStack(index)));
    }

    static OptionalInt hotbarRecoveryDestination(MinecraftClient client, boolean repairPending,
                                                boolean interactionPending) {
        if (client.player == null) { return OptionalInt.empty(); }
        var inventory = client.player.getInventory();
        return BackgroundBuildPolicy.hotbarRecoveryDestination(screen(client.currentScreen),
                hasPlayerHandler(client), hasEmptyCursor(client), repairPending, interactionPending,
                inventory.getSelectedSlot(), net.minecraft.entity.player.PlayerInventory.getHotbarSize(),
                index -> inventory.getStack(index).isEmpty());
    }

    static boolean allowsNewInventoryTransaction(MinecraftClient client) {
        return BackgroundBuildPolicy.allowsNewInventoryTransaction(screen(client.currentScreen),
                hasPlayerHandler(client), hasEmptyCursor(client));
    }

    /** Also leaves a page before registered-chest access and shop commands; see {@link #returnLeftPage}. */
    static BackgroundBuildPolicy.RestockTransition leavePassiveScreenForRestock(
            MinecraftClient client, BooleanSupplier restockReady) {
        if (!client.isOnThread() || client.player == null || client.world == null
                || client.interactionManager == null || client.getNetworkHandler() == null) {
            return BackgroundBuildPolicy.RestockTransition.UNCHANGED;
        }
        var world = client.world;
        var player = client.player;
        var handler = player.playerScreenHandler;
        var connection = client.getNetworkHandler();
        var interactions = client.interactionManager;
        return BackgroundBuildPolicy.leavePassiveScreenForRestock(() -> restockScreen(client,
                client.isOnThread() && client.world == world && client.player == player
                        && client.getNetworkHandler() == connection && client.interactionManager == interactions
                        && player.playerScreenHandler == handler && client.getOverlay() == null),
                restockReady, () -> {
                    Screen page = client.currentScreen;
                    client.setScreen(null);
                    leftPage = new LeftPage(page, page instanceof InventoryScreen, world, player, connection);
                });
    }

    /** Why the open page can't be left for container work right now; blank when only other work holds it. */
    static String pageLeaveBlocker(MinecraftClient client) {
        if (client.player == null || client.world == null) { return ""; }
        return restockScreen(client, client.getOverlay() == null).blocker();
    }

    private static BackgroundBuildPolicy.RestockScreen restockScreen(MinecraftClient client, boolean contextMatches) {
        return new BackgroundBuildPolicy.RestockScreen(screen(client.currentScreen), client.currentScreen,
                contextMatches, hasPlayerHandler(client), hasEmptyCursor(client), hasEmptyCrafting(client),
                !mouseButtonHeld(client));
    }

    /** Reopens the page left for container work once that work settles, unless the player took over. */
    static void returnLeftPage(MinecraftClient client, boolean workSettled) {
        LeftPage left = leftPage;
        if (left == null || !client.isOnThread()) { return; }
        var decision = BackgroundBuildPolicy.returnLeftPage(client.world == left.world()
                        && client.player == left.player() && client.getNetworkHandler() == left.connection(),
                screen(client.currentScreen), workSettled, manualInput(client),
                hasPlayerHandler(client), hasEmptyCursor(client), client.getOverlay() != null);
        if (decision == BackgroundBuildPolicy.PageReturn.KEEP) { return; }
        leftPage = null;
        if (decision == BackgroundBuildPolicy.PageReturn.RESTORE) {
            // Inventory opens fresh, as the inventory key does; settings pages return like a parent screen.
            client.setScreen(left.inventory() ? new InventoryScreen(client.player) : left.screen());
        }
    }

    static void forgetLeftPage() {
        leftPage = null;
    }

    private static boolean manualInput(MinecraftClient client) {
        var options = client.options;
        return options.forwardKey.isPressed() || options.backKey.isPressed() || options.leftKey.isPressed()
                || options.rightKey.isPressed() || options.jumpKey.isPressed() || options.sneakKey.isPressed()
                || options.attackKey.isPressed() || options.useKey.isPressed() || mouseButtonHeld(client);
    }

    /**
     * Reads the physical left, right, and middle buttons. Vanilla's click flags update only while no
     * screen is open, so a click that opens a chest keeps its flag set until the next gameplay click.
     */
    static boolean mouseButtonHeld(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
    }

    private static boolean hasEmptyCrafting(MinecraftClient client) {
        if (!hasPlayerHandler(client) || client.player.playerScreenHandler.slots.size() < 5) { return false; }
        // Leaving Inventory calls its handler's onClosed; preserve every crafting input and result.
        for (int slot = 0; slot <= 4; slot++) {
            if (!client.player.playerScreenHandler.getSlot(slot).getStack().isEmpty()) { return false; }
        }
        return true;
    }

    private static boolean hasPlayerHandler(MinecraftClient client) {
        return client.world != null && client.player != null
                && client.player.currentScreenHandler == client.player.playerScreenHandler;
    }

    private static boolean hasEmptyCursor(MinecraftClient client) {
        return client.player != null && client.player.currentScreenHandler != null
                && client.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private static BackgroundBuildPolicy.Screen screen(Screen screen) {
        if (screen == null) { return BackgroundBuildPolicy.Screen.GAMEPLAY; }
        if (screen instanceof InventoryScreen) { return BackgroundBuildPolicy.Screen.INVENTORY; }
        if (screen instanceof GameMenuScreen || screen instanceof OptionsScreen
                || screen instanceof GameOptionsScreen) { return BackgroundBuildPolicy.Screen.SETTINGS; }
        if (screen instanceof ChatScreen) { return BackgroundBuildPolicy.Screen.CHAT; }
        return BackgroundBuildPolicy.Screen.OTHER;
    }
}
