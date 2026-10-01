package io.github.schematicsupervisor.fabric;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Historical item facts from accepted shop menus; never authority for a later purchase. */
final class ShopMenuHistory {
    private static final int MAX_MENUS = 4;
    private static final int MAX_ENTRIES = 54;
    private static final int MAX_TEXT = 128;

    record Entry(int slot, String itemId, int stackCount) { }

    record Menu(String title, List<Entry> entries, boolean truncated, String capturedAt) {
        Menu { entries = List.copyOf(entries); }

        Menu(String title, List<Entry> entries, boolean truncated) {
            this(title, entries, truncated, Instant.now().toString());
        }
    }

    private record Key(long identity, int syncId, String title, List<Entry> entries, boolean truncated) { }

    private final ArrayDeque<Menu> menus = new ArrayDeque<>();
    private Key previous;

    void beginSession() {
        previous = null;
    }

    void clear() {
        menus.clear();
        previous = null;
    }

    void record(DirtShopPurchase.Menu source) {
        boolean truncated = source.title().length() > MAX_TEXT || source.entries().size() > MAX_ENTRIES;
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_ENTRIES, source.entries().size()); index++) {
            DirtShopPurchase.Entry entry = source.entries().get(index);
            if (entry.slot() < 0 || entry.slot() >= MAX_ENTRIES || entry.itemId().length() > MAX_TEXT
                    || entry.itemId().isBlank() || entry.stackCount() < 1) {
                truncated = true;
                continue;
            }
            entries.add(new Entry(entry.slot(), entry.itemId(), entry.stackCount()));
        }
        entries.sort(Comparator.comparingInt(Entry::slot));
        int titleEnd = Math.min(MAX_TEXT, source.title().length());
        if (titleEnd < source.title().length() && titleEnd > 0
                && Character.isHighSurrogate(source.title().charAt(titleEnd - 1))) { titleEnd--; }
        String title = source.title().substring(0, titleEnd);
        Key current = new Key(source.identity(), source.syncId(), title, List.copyOf(entries), truncated);
        if (current.equals(previous)) { return; }
        previous = current;
        if (menus.size() == MAX_MENUS) { menus.removeFirst(); }
        menus.addLast(new Menu(title, entries, truncated));
    }

    List<Menu> snapshot() {
        return List.copyOf(menus);
    }
}
