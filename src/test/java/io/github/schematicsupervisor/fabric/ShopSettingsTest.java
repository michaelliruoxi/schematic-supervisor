package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Decision;
import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Entry;
import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Menu;
import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Product;
import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Route;
import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Status;
import io.github.schematicsupervisor.fabric.MaterialShopPolicy.Step;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ShopSettingsTest {
    /** Another server's shop: other titles, buttons, slots, and pages. */
    private static final String MARKET = """
            {
              "enabled": true,
              "command": "market",
              "mainTitle": "Market",
              "category": {"label": "Building", "item": "minecraft:bricks", "slot": 13},
              "pageTitle": "Building {page}/{pages}",
              "pages": 2,
              "nextPage": {"label": "Next", "item": "minecraft:arrow", "slot": 53},
              "mainMenu": {"label": "Back", "item": "minecraft:barrier", "slot": 49},
              "buyingTitle": "Buy {name}",
              "buyingProductSlot": 4,
              "buyStacks": {"label": "Stacks", "item": "minecraft:chest", "slot": 31},
              "stacksTitle": "{name} stacks",
              "products": {
                "minecraft:stone": {"name": "Stone", "page": 1, "slot": 0},
                "minecraft:glowstone": {"name": "Glowstone", "page": 2, "slot": 7}
              }
            }
            """;

    @Test
    void withoutAFileNothingIsBought(@TempDir Path directory) throws IOException {
        ShopSettings settings = ShopSettings.load(directory.resolve(ShopSettings.FILE_NAME));
        assertSame(ShopSettings.DISABLED, settings);
        assertFalse(settings.enabled());
        assertSame(ShopLayout.CAPTURED, settings.layout());
    }

    @Test
    void enablingAloneKeepsTheCapturedLayout(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(ShopSettings.FILE_NAME);
        Files.writeString(file, "{\"enabled\": true}", StandardCharsets.UTF_8);
        ShopSettings settings = ShopSettings.load(file);
        assertTrue(settings.enabled());
        assertEquals(ShopLayout.CAPTURED, settings.layout());
        assertEquals("shop", settings.layout().command());
        assertEquals("Blocks (Page 4/5)", settings.layout().pageTitle(4));
        assertEquals("Buying stacks of Glowstone", settings.layout().stacksTitle(Product.GLOWSTONE));
        assertEquals(MaterialShopPolicy.MAXIMUM_NAVIGATION_CLICKS, settings.layout().maximumNavigationClicks());
    }

    @Test
    void invalidFilesAreRejectedWithTheReason(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(ShopSettings.FILE_NAME);
        for (String invalid : List.of(
                "[]",
                "{\"enabled\": \"yes\"}",
                "{\"enabeld\": true}",
                "{\"command\": \"/shop\"}",
                "{\"pages\": 2.5}",
                "{\"pageTitle\": \"Blocks\"}",
                "{\"category\": {\"label\": \"Blocks\", \"item\": \"Grass Block\", \"slot\": 11}}",
                "{\"category\": {\"label\": \"Blocks\", \"item\": \"minecraft:grass_block\", \"slot\": 54}}",
                "{\"products\": {\"minecraft:glowstone\": {\"name\": \"Glowstone\", \"page\": 6, \"slot\": 1}}}",
                "{\"products\": {\"minecraft:glowstone\": {\"name\": \"Glowstone\", \"page\": 1}}}")) {
            Files.writeString(file, invalid, StandardCharsets.UTF_8);
            IOException failure = assertThrows(IOException.class, () -> ShopSettings.load(file), invalid);
            assertTrue(failure.getMessage().startsWith("shop.json is invalid: "), failure.getMessage());
        }
    }

    @Test
    void anotherServersLayoutDrivesTheWholePurchaseRoute() {
        ShopLayout market = ShopSettings.parse(MARKET).layout();
        assertEquals("market", market.command());
        assertFalse(market.sells(Product.BIRCH_PLANKS));
        assertEquals(4, market.maximumNavigationClicks());

        Route route = Route.start(Product.GLOWSTONE, market);
        route = navigate(route, menu(1, "Market", button(13, "minecraft:bricks", "Building")), 13);
        route = navigate(route, menu(2, "Building 1/2", button(53, "minecraft:arrow", "Next"),
                product(0, "minecraft:stone", "Stone", "Buy price: $1")), 53);
        route = navigate(route, menu(3, "Building 2/2", product(7, "minecraft:glowstone", "Glowstone", "Buy price: $10")), 7);
        route = navigate(route, menu(4, "Buy Glowstone", product(4, "minecraft:glowstone", "Glowstone", "Buy price: $10"),
                button(31, "minecraft:chest", "Stacks")), 31);
        assertEquals(Step.STACKS, route.step());
        assertSame(market, route.layout());

        List<Entry> stacks = new ArrayList<>();
        for (int count = 1; count <= 9; count++) {
            stacks.add(new Entry(count - 1, "minecraft:glowstone", "Buy " + count + (count == 1 ? " stack" : " stacks"),
                    List.of("Price: $" + (640 * count)), 1));
        }
        Decision purchase = MaterialShopPolicy.decide(route, new Menu(5, 5, "Glowstone stacks", stacks, true), 3);
        assertEquals(Status.PURCHASE, purchase.status(), purchase.detail());
        assertEquals(3, purchase.stacks());
        assertEquals(0, purchase.quotedPrice().compareTo(new BigDecimal("1920")));
        assertTrue(MaterialShopPolicy.isStacksTitle(Product.GLOWSTONE, "Glowstone stacks", market));
        assertFalse(MaterialShopPolicy.isStacksTitle(Product.GLOWSTONE, "Glowstone stacks"));

        // The captured layout's titles no longer match this server.
        assertEquals(Status.BLOCKED, MaterialShopPolicy.decide(Route.start(Product.GLOWSTONE, market),
                menu(6, "Shop | Economy", button(13, "minecraft:bricks", "Building")), 9).status());
        assertThrows(IllegalArgumentException.class, () -> Route.start(Product.BIRCH_PLANKS, market));
    }

    @Test
    void theInstalledLayoutAlsoNamesTheStackMenuInPurchaseQuotes() {
        ShopSettings previous = ShopSettings.current();
        try {
            assertThrows(IllegalArgumentException.class, () -> quote("Glowstone stacks"));
            ShopSettings.install(ShopSettings.parse(MARKET));
            assertEquals("Glowstone stacks", quote("Glowstone stacks").menuTitle());
            assertEquals("Buying stacks of Glowstone", quote("Buying stacks of Glowstone").menuTitle());
            assertThrows(IllegalArgumentException.class, () -> quote("Birch Planks stacks"));
        } finally {
            ShopSettings.install(previous);
        }
    }

    private static MaterialPurchaseFacts.Quote quote(String title) {
        return new MaterialPurchaseFacts.Quote(MaterialPurchaseFacts.Product.GLOWSTONE, 1, 64_000, "a".repeat(64), title, 0);
    }

    private static Route navigate(Route route, Menu menu, int slot) {
        Decision decision = MaterialShopPolicy.decide(route, menu, 9);
        assertEquals(Status.NAVIGATE, decision.status(), decision.detail());
        assertEquals(slot, decision.slot());
        return decision.nextRoute();
    }

    private static Menu menu(int identity, String title, Entry... entries) {
        return new Menu(identity, identity, title, List.of(entries), true);
    }

    private static Entry button(int slot, String item, String label) {
        return new Entry(slot, item, label, List.of(), 1);
    }

    private static Entry product(int slot, String item, String label, String price) {
        return new Entry(slot, item, label, List.of(price), 1);
    }
}
