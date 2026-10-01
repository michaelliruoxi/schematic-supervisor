package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/** Separates ordinary viewing screens from inventory transactions and unknown interactions. */
final class BackgroundBuildPolicy {
    enum Screen { GAMEPLAY, INVENTORY, SETTINGS, CHAT, OTHER }
    enum RestockTransition { UNCHANGED, CLEARED, INVALIDATED }
    enum PageReturn { KEEP, RESTORE, FORGET }

    private BackgroundBuildPolicy() { }

    static boolean allowsWorldActions(Screen screen, boolean playerHandler, boolean emptyCursor) {
        Objects.requireNonNull(screen, "screen");
        return playerHandler && emptyCursor && screen != Screen.OTHER;
    }

    static boolean allowsHotbarTransfer(Screen screen, boolean playerHandler, boolean emptyCursor) {
        return allowsWorldActions(screen, playerHandler, emptyCursor);
    }

    static boolean allowsNewInventoryTransaction(Screen screen, boolean playerHandler, boolean emptyCursor) {
        return allowsWorldActions(screen, playerHandler, emptyCursor) && screen == Screen.GAMEPLAY;
    }

    static boolean requiresPassiveRestockTransition(SupervisorState state, boolean shortage,
                                                    boolean operationsSettled) {
        return state == SupervisorState.RESTOCKING && shortage && operationsSettled;
    }

    /** A queued, unstarted scan owns no screen or inventory transaction. */
    static boolean depotSettledForPassiveRestock(DepotObservation depot) {
        return depot != null && depot.available() && !depot.blocked()
                && "NONE".equals(depot.operation()) && "IDLE".equals(depot.stage());
    }

    static RestockTransition leavePassiveScreenForRestock(Supplier<RestockScreen> observe,
                                                         BooleanSupplier restockReady, Runnable clearScreen) {
        RestockScreen initial = observe.get();
        if (!initial.mayLeave() || !restockReady.getAsBoolean()) { return RestockTransition.UNCHANGED; }
        RestockScreen before = observe.get();
        if (before.identity() != initial.identity() || !before.mayLeave()
                || !restockReady.getAsBoolean()) { return RestockTransition.INVALIDATED; }
        clearScreen.run();
        RestockScreen after = observe.get();
        return after.identity() == null && after.safe() && restockReady.getAsBoolean()
                && allowsNewInventoryTransaction(after.screen(), after.playerHandler(), after.emptyCursor())
                ? RestockTransition.CLEARED : RestockTransition.INVALIDATED;
    }

    /**
     * Reopens an idle Inventory or Settings page left for owned container work once that work settles.
     * A different world, or movement or click input in gameplay, means the player took over instead.
     */
    static PageReturn returnLeftPage(boolean contextMatches, Screen current, boolean workSettled,
                                     boolean manualInput, boolean playerHandler, boolean emptyCursor,
                                     boolean overlayOpen) {
        Objects.requireNonNull(current, "current");
        if (!contextMatches || current == Screen.GAMEPLAY && manualInput) { return PageReturn.FORGET; }
        if (!workSettled) { return PageReturn.KEEP; }
        // Settled work owns no container, so any screen still open belongs to the player or the game.
        if (current != Screen.GAMEPLAY) { return PageReturn.FORGET; }
        return playerHandler && emptyCursor && !overlayOpen ? PageReturn.RESTORE : PageReturn.KEEP;
    }

    record RestockScreen(Screen screen, Object identity, boolean contextMatches, boolean playerHandler,
                         boolean emptyCursor, boolean emptyCrafting, boolean mouseReleased) {
        RestockScreen { Objects.requireNonNull(screen, "screen"); }

        private boolean safe() {
            return contextMatches && playerHandler && emptyCursor && emptyCrafting && mouseReleased;
        }

        private boolean mayLeave() {
            return identity != null && safe() && (screen == Screen.INVENTORY || screen == Screen.SETTINGS);
        }

        /** What keeps an open page from being left for container work; blank when nothing does. */
        String blocker() {
            if (screen == Screen.CHAT) { return "chat is open"; }
            if (screen == Screen.OTHER || !playerHandler) { return "a container or another screen is open"; }
            if (!emptyCursor) { return "an item is on the cursor"; }
            if (!emptyCrafting) { return "the crafting grid holds items"; }
            if (!mouseReleased) { return "a mouse button is held down"; }
            return contextMatches ? "" : "the world or player changed";
        }
    }

    static OptionalInt hotbarDestination(Screen screen, boolean playerHandler, boolean emptyCursor,
                                        int selected, int hotbarSize, IntPredicate empty) {
        return hotbarDestination(screen, playerHandler, emptyCursor, selected, hotbarSize, empty, slot -> false);
    }

    static OptionalInt hotbarDestination(Screen screen, boolean playerHandler, boolean emptyCursor,
                                        int selected, int hotbarSize, IntPredicate empty, IntPredicate plainSupply) {
        return hotbarDestination(screen, playerHandler, emptyCursor, selected, hotbarSize,
                empty, plainSupply, false);
    }

    static OptionalInt hotbarRecoveryDestination(Screen screen, boolean playerHandler, boolean emptyCursor,
                                                boolean repairPending, boolean interactionPending,
                                                int selected, int hotbarSize, IntPredicate empty) {
        if (repairPending || interactionPending) { return OptionalInt.empty(); }
        return hotbarDestination(screen, playerHandler, emptyCursor, selected, hotbarSize,
                empty, slot -> false, true);
    }

    private static OptionalInt hotbarDestination(Screen screen, boolean playerHandler, boolean emptyCursor,
                                                int selected, int hotbarSize, IntPredicate empty,
                                                IntPredicate plainSupply, boolean emptyOnly) {
        Objects.requireNonNull(empty, "empty");
        Objects.requireNonNull(plainSupply, "plainSupply");
        if (hotbarSize < 1 || hotbarSize > 9 || selected < 0 || selected >= hotbarSize) {
            throw new IllegalArgumentException("Invalid selected hotbar slot");
        }
        if (!allowsHotbarTransfer(screen, playerHandler, emptyCursor)) { return OptionalInt.empty(); }
        if (empty.test(selected)) { return OptionalInt.of(selected); }
        for (int slot = 0; slot < hotbarSize; slot++) {
            if (empty.test(slot)) { return OptionalInt.of(slot); }
        }
        if (emptyOnly) { return OptionalInt.empty(); }
        if (plainSupply.test(selected)) { return OptionalInt.of(selected); }
        for (int slot = 0; slot < hotbarSize; slot++) {
            if (slot != selected && plainSupply.test(slot)) { return OptionalInt.of(slot); }
        }
        return OptionalInt.empty();
    }
}
