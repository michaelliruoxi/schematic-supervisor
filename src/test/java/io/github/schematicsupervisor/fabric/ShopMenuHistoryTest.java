package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ShopMenuHistoryTest {
    @Test
    void repeatedFactsAreDeduplicatedAndOnlyExactItemFactsAreRetained() {
        ShopMenuHistory history = new ShopMenuHistory();
        DirtShopPurchase.Menu menu = menu(1, "Blocks (Page 1/5)", 7);
        history.record(menu);
        history.record(menu);
        history.record(new DirtShopPurchase.Menu(1, 1, menu.title(), List.of(
                new DirtShopPurchase.Entry(12, "different private label", List.of("private lore"),
                        false, "minecraft:glowstone", 7))));
        assertEquals(1, history.snapshot().size());
        ShopMenuHistory.Menu observed = history.snapshot().getFirst();
        assertEquals("Blocks (Page 1/5)", observed.title());
        assertEquals(new ShopMenuHistory.Entry(12, "minecraft:glowstone", 7), observed.entries().getFirst());
        assertFalse(observed.toString().contains("private"));
        assertFalse(observed.truncated());
        assertEquals(observed.capturedAt(), Instant.parse(observed.capturedAt()).toString());
        history.record(menu);
        assertEquals(observed, history.snapshot().getFirst());
    }

    @Test
    void clearingWorldEvidenceAlsoClearsDeduplication() {
        ShopMenuHistory history = new ShopMenuHistory();
        DirtShopPurchase.Menu menu = menu(1, "Blocks", 64);
        history.record(menu);
        history.clear();
        assertTrue(history.snapshot().isEmpty());
        history.record(menu);
        assertEquals(1, history.snapshot().size());
    }

    @Test
    void countChangesReopenedMenusAndNewSessionsRetainOnlyFourLatestObservations() {
        ShopMenuHistory history = new ShopMenuHistory();
        history.record(menu(1, "Blocks", 1));
        history.record(menu(1, "Blocks", 2));
        history.record(menu(2, "Blocks", 2));
        history.beginSession();
        history.record(menu(2, "Blocks", 2));
        List<ShopMenuHistory.Menu> before = history.snapshot();
        history.record(menu(3, "Buying Dirt", 8));
        assertEquals(4, history.snapshot().size());
        assertEquals(2, history.snapshot().getFirst().entries().getFirst().stackCount());
        assertEquals("Buying Dirt", history.snapshot().getLast().title());
        assertEquals("Blocks", before.getLast().title());
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        assertThrows(UnsupportedOperationException.class, () -> before.getFirst().entries().clear());
    }

    @Test
    void malformedAndOversizedEvidenceIsBoundedWithoutInventingItemIdsOrCounts() {
        ShopMenuHistory history = new ShopMenuHistory();
        List<DirtShopPurchase.Entry> entries = new ArrayList<>();
        entries.add(new DirtShopPurchase.Entry(0, "", List.of(), false, "minecraft:" + "x".repeat(128), 1));
        entries.add(new DirtShopPurchase.Entry(1, "", List.of(), false, "minecraft:glowstone", 0));
        for (int slot = 2; slot < 60; slot++) {
            entries.add(new DirtShopPurchase.Entry(slot, "", List.of(), false, "minecraft:glowstone", 64));
        }
        history.record(new DirtShopPurchase.Menu(1, 1, "x".repeat(127) + "\uD83D\uDE00suffix", entries));
        ShopMenuHistory.Menu observed = history.snapshot().getFirst();
        assertTrue(observed.truncated());
        assertEquals(127, observed.title().length());
        assertEquals(52, observed.entries().size());
        assertTrue(observed.entries().stream().allMatch(entry -> entry.itemId().equals("minecraft:glowstone")
                && entry.stackCount() == 64 && entry.slot() < 54));
    }

    private static DirtShopPurchase.Menu menu(long id, String title, int count) {
        return new DirtShopPurchase.Menu(id, (int) id, title, List.of(
                new DirtShopPurchase.Entry(12, "private label", List.of("private lore"),
                        false, "minecraft:glowstone", count)));
    }
}
