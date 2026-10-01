package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.github.schematicsupervisor.fabric.MaterialShopPolicy.*;
import static io.github.schematicsupervisor.fabric.ShopInventoryReceiptPolicy.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class ShopInventoryReceiptPolicyTest {
    private static final Map<String, Menu> MENUS = catalog();

    @Test void fourRowMainMenuNavigatesOnceToTheSixRowReceiptCategory() {
        var main = new ArrayList<Entry>();
        for (int slot = 0; slot < 36; slot++) {
            main.add(slot == 11 ? new Entry(slot, "minecraft:grass_block", "Blocks", List.of(), 1)
                    : new Entry(slot, "minecraft:white_stained_glass_pane", " ", List.of(), 1));
        }
        var opening = ShopInventoryReceiptPolicy.decide(ShopInventoryReceiptPolicy.start(),
                new Menu(1, 1, "Shop | Economy", main, true));
        assertEquals(OPEN_BLOCKS, opening.action());
        assertEquals(11, opening.slot());
        var receipt = ShopInventoryReceiptPolicy.decide(opening.route(), MENUS.get("shop/blocks/1"));
        assertEquals(ACCEPT, receipt.action());
        assertEquals(-1, receipt.slot());
        assertEquals(1, receipt.route().navigationClicks());
        assertEquals(1, receipt.route().expectedPage());
    }

    @Test void delayedOrRepeatedMenuCannotRepeatTheCategoryClick() {
        var main = MENUS.get("shop");
        var opening = ShopInventoryReceiptPolicy.decide(ShopInventoryReceiptPolicy.start(), main);
        assertEquals(OPEN_BLOCKS, opening.action());
        for (Menu delayed : new Menu[] {null, main,
                new Menu(main.identity(), main.syncId(), "Blocks (Page 1/5)", MENUS.get("shop/blocks/1").entries(), true)}) {
            var decision = ShopInventoryReceiptPolicy.decide(opening.route(), delayed);
            assertEquals(WAIT, decision.action());
            assertEquals(-1, decision.slot());
            assertSame(opening.route(), decision.route());
        }
    }

    @Test void allOtherCapturedProductPagesAreRejectedWithoutPurchaseOrNavigation() {
        var opening = ShopInventoryReceiptPolicy.decide(ShopInventoryReceiptPolicy.start(), MENUS.get("shop"));
        for (var entry : MENUS.entrySet()) {
            if (entry.getKey().equals("shop") || entry.getKey().equals("shop/blocks/1")) { continue; }
            var decision = ShopInventoryReceiptPolicy.decide(opening.route(), entry.getValue());
            assertEquals(BLOCKED, decision.action(), entry.getKey());
            assertEquals(-1, decision.slot());
        }
        var secondPageRoute = MaterialShopPolicy.decide(opening.route(), MENUS.get("shop/blocks/1"), 0).nextRoute();
        assertThrows(IllegalArgumentException.class, () -> ShopInventoryReceiptPolicy.decide(secondPageRoute, MENUS.get("shop/blocks/2")));
    }

    @Test void occupiedCursorOrChangedBlocksButtonCannotStartReceiptNavigation() {
        var main = MENUS.get("shop");
        var occupied = new Menu(main.identity(), main.syncId(), main.title(), main.entries(), false);
        assertEquals(BLOCKED, ShopInventoryReceiptPolicy.decide(ShopInventoryReceiptPolicy.start(), occupied).action());
        var changed = main.entries().stream().map(e -> e.slot() == 11
                ? new Entry(11, "minecraft:dirt", "Blocks", List.of(), 1) : e).toList();
        assertEquals(BLOCKED, ShopInventoryReceiptPolicy.decide(ShopInventoryReceiptPolicy.start(),
                new Menu(main.identity(), main.syncId(), main.title(), changed, true)).action());
    }

    private static Map<String, Menu> catalog() {
        try (var input = ShopInventoryReceiptPolicyTest.class.getResourceAsStream("/material-shop-catalog.json")) {
            assertNotNull(input);
            var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Menu> menus = new HashMap<>();
            for (var rawMenu : root.getAsJsonArray("menus")) {
                var value = rawMenu.getAsJsonObject();
                List<Entry> entries = new ArrayList<>();
                for (var rawEntry : value.getAsJsonArray("entries")) {
                    var item = rawEntry.getAsJsonObject();
                    List<String> lore = new ArrayList<>();
                    item.getAsJsonArray("lore").forEach(line -> lore.add(line.getAsString()));
                    entries.add(new Entry(item.get("slot").getAsInt(), item.get("item_id").getAsString(),
                            item.get("label").getAsString(), lore, 1));
                }
                menus.put(value.get("route").getAsString(), new Menu(value.get("menu_id").getAsLong(),
                        value.get("sync_id").getAsInt(), value.get("title").getAsString(), entries, true));
            }
            return Map.copyOf(menus);
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
