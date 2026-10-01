package io.github.schematicsupervisor.fabric;

import java.util.List;

/** Bounded read-only shop facts; observation never opens a menu or purchases an item. */
record DirtShopObservation(
        boolean available,
        String state,
        boolean active,
        String expectedStep,
        String waitingReason,
        int purchasedStacks,
        int pendingStacks,
        Integer emptySlots,
        Integer dirtCount,
        Boolean cursorEmpty,
        String menuTitle,
        Long menuId,
        Integer syncId,
        boolean menuMatchesExpected,
        boolean dirtEvidence,
        List<Entry> entries,
        boolean truncated,
        String error,
        List<ShopMenuHistory.Menu> menuHistory,
        Integer targetStacks,
        Integer reservedEmptySlots
) {
    DirtShopObservation {
        entries = List.copyOf(entries);
        menuHistory = List.copyOf(menuHistory);
    }

    DirtShopObservation(boolean available, String state, boolean active, String expectedStep,
                        String waitingReason, int purchasedStacks, int pendingStacks, Integer emptySlots,
                        Integer dirtCount, Boolean cursorEmpty, String menuTitle, Long menuId, Integer syncId,
                        boolean menuMatchesExpected, boolean dirtEvidence, List<Entry> entries,
                        boolean truncated, String error, List<ShopMenuHistory.Menu> menuHistory) {
        this(available, state, active, expectedStep, waitingReason, purchasedStacks, pendingStacks,
                emptySlots, dirtCount, cursorEmpty, menuTitle, menuId, syncId, menuMatchesExpected,
                dirtEvidence, entries, truncated, error, menuHistory, null, null);
    }

    DirtShopObservation(boolean available, String state, boolean active, String expectedStep,
                        String waitingReason, int purchasedStacks, int pendingStacks, Integer emptySlots,
                        Integer dirtCount, Boolean cursorEmpty, String menuTitle, Long menuId, Integer syncId,
                        boolean menuMatchesExpected, boolean dirtEvidence, List<Entry> entries,
                        boolean truncated, String error) {
        this(available, state, active, expectedStep, waitingReason, purchasedStacks, pendingStacks,
                emptySlots, dirtCount, cursorEmpty, menuTitle, menuId, syncId, menuMatchesExpected,
                dirtEvidence, entries, truncated, error, List.of());
    }

    record Entry(int slot, String label, String normalizedLabel, String itemId,
                 boolean dirtItem, List<String> lore, int stackQuantity, boolean matchesCurrentStep) {
        Entry { lore = List.copyOf(lore); }
    }
}
