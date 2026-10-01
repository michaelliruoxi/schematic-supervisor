package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static io.github.schematicsupervisor.fabric.MaterialShopPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialShopPolicyTest {
    private static final Map<String, Menu> CATALOG = loadCatalog();

    @Test void capturedRoutesReachOnlyTheirExactStackButtonWithinBoundedNavigation() {
        for (Product product : Product.values()) {
            Route route = stackRoute(product);
            assertEquals(product == Product.BIRCH_PLANKS ? 4 : 6, route.navigationClicks());
            Decision buy = decide(route, stackMenu(product), 9);
            assertEquals(Status.PURCHASE, buy.status(), buy.detail());
            assertEquals(8, buy.slot());
            assertEquals(9, buy.stacks());
            assertEquals(0, buy.quotedPrice().compareTo(new BigDecimal(product == Product.BIRCH_PLANKS ? "5040" : "288000")));
            assertEquals(Step.AWAIT_RECEIPT, buy.nextRoute().step());
        }
    }

    @Test void capturedStackLabelsChooseOnlyTheExplicitCapacityBudgetAndIgnoreIconCount() {
        for (Product product : Product.values()) {
            Menu menu = stackMenu(product);
            for (int maximum = 1; maximum <= 36; maximum++) {
                Decision decision = decide(stackRoute(product), menu, maximum);
                assertEquals(Status.PURCHASE, decision.status(), decision.detail());
                assertEquals(Math.min(9, maximum), decision.stacks());
            }
            Menu misleadingIcons = withEntries(menu, menu.entries().stream()
                    .map(entry -> new Entry(entry.slot(), entry.itemId(), entry.label(), entry.lore(), 64)).toList());
            assertEquals(2, decide(stackRoute(product), misleadingIcons, 2).stacks());
            assertEquals(Status.BLOCKED, decide(stackRoute(product), menu, 0).status());
        }
    }

    @Test void zeroCurrentCapacityAllowsOnlyNavigationAndNeverSelectsAPurchase() {
        for (Product product : Product.values()) {
            Route route = Route.start(product);
            List<Menu> menus = new ArrayList<>();
            menus.add(CATALOG.get("shop"));
            for (int page = 1; page <= product.blocksPage(); page++) {
                menus.add(CATALOG.get("shop/blocks/" + page));
            }
            menus.add(CATALOG.get(productPath(product)));
            for (Menu menu : menus) {
                Decision decision = decide(route, menu, 0);
                assertEquals(Status.NAVIGATE, decision.status(), decision.detail());
                assertEquals(0, decision.stacks());
                route = decision.nextRoute();
            }
            Decision stopped = decide(route, stackMenu(product), 0);
            assertEquals(Status.BLOCKED, stopped.status());
            assertEquals(-1, stopped.slot());
            assertEquals(0, stopped.stacks());
            assertNull(stopped.quotedPrice());
            assertEquals(Step.STACKS, stopped.nextRoute().step());
        }
    }

    @Test void purchaseIsTerminalEvenWhenOriginalShopOrAnotherQuantityMenuAppears() {
        Decision purchase = decide(stackRoute(Product.GLOWSTONE), stackMenu(Product.GLOWSTONE), 3);
        for (Menu observed : CATALOG.values()) {
            Decision next = decide(purchase.nextRoute(), observed, 9);
            assertEquals(Status.WAIT, next.status());
            assertEquals(-1, next.slot());
            assertSame(purchase.nextRoute(), next.nextRoute());
        }
        assertEquals(Status.WAIT, decide(purchase.nextRoute(), null, 9).status());
    }

    @Test void onlyCapturedNewProductsAreAllowlisted() {
        assertEquals(Product.BIRCH_PLANKS, forItemId("minecraft:birch_planks").orElseThrow());
        assertEquals(Product.GLOWSTONE, forItemId("minecraft:glowstone").orElseThrow());
        for (String unsupported : List.of("minecraft:birch_log", "minecraft:dirt", "minecraft:wheat_seeds",
                "minecraft:diamond_hoe", "minecraft:glowstone_dust", "other:glowstone")) {
            assertTrue(forItemId(unsupported).isEmpty());
        }
    }

    @Test void staleMenusCannotRepeatNavigationEvenIfLocallyChangedToLookLikeNextPage() {
        Menu shop = CATALOG.get("shop");
        Route waitingForBlocks = decide(Route.start(Product.BIRCH_PLANKS), shop, 9).nextRoute();
        assertEquals(Status.WAIT, decide(waitingForBlocks, shop, 9).status());
        Menu page = CATALOG.get("shop/blocks/1");
        Menu sameHandler = new Menu(shop.identity(), shop.syncId(), page.title(), page.entries(), true);
        assertEquals(Status.WAIT, decide(waitingForBlocks, sameHandler, 9).status());
        assertEquals(Status.NAVIGATE, decide(waitingForBlocks, page, 9).status());
    }

    @Test void lateMenusWaitWithoutLosingTheRouteAndUnrelatedMenusBlock() {
        Route route = stackRoute(Product.BIRCH_PLANKS);
        Menu menu = stackMenu(Product.BIRCH_PLANKS);
        for (Menu late : new Menu[] {null, withEntries(menu, List.of())}) {
            Decision decision = decide(route, late, 9);
            assertEquals(Status.WAIT, decision.status());
            assertSame(route, decision.nextRoute());
            assertEquals(-1, decision.slot());
        }
        assertEquals(Status.BLOCKED, decide(route, stackMenu(Product.GLOWSTONE), 9).status());
        assertEquals(Status.BLOCKED, decide(route, new Menu(menu.identity(), menu.syncId(), menu.title(), menu.entries(), false), 9).status());
    }

    @Test void onlyFreshExpectedEmptyCursorMenusCanBeAdoptedForWaitCleanup() {
        Route route = stackRoute(Product.BIRCH_PLANKS);
        Menu menu = stackMenu(Product.BIRCH_PLANKS);
        assertTrue(isExpectedMenu(route, menu));
        assertTrue(isExpectedMenu(route, withEntries(menu, List.of())));
        assertTrue(isExpectedMenu(route, titled(menu, "\u00a7aBUYING  stacks of Birch Planks")));
        assertFalse(isExpectedMenu(route, null));
        assertFalse(isExpectedMenu(route, stackMenu(Product.GLOWSTONE)));
        assertFalse(isExpectedMenu(route, titled(menu, "Buying stacks: Birch Planks")));
        assertFalse(isExpectedMenu(route, new Menu(menu.identity(), menu.syncId(), menu.title(), menu.entries(), false)));
        assertFalse(isExpectedMenu(route, new Menu(route.previousMenu().identity(), route.previousMenu().syncId(),
                menu.title(), menu.entries(), true)));
        Route terminal = decide(route, menu, 1).nextRoute();
        assertFalse(isExpectedMenu(terminal, new Menu(100, 100, menu.title(), menu.entries(), true)));
    }

    @Test void blocksPageOrderAndTotalPageCountMustMatchRatherThanSearchingOtherPages() {
        Route route = decide(Route.start(Product.GLOWSTONE), CATALOG.get("shop"), 9).nextRoute();
        assertEquals(Status.BLOCKED, decide(route, CATALOG.get("shop/blocks/2"), 9).status());
        Menu first = CATALOG.get("shop/blocks/1");
        assertEquals(Status.BLOCKED, decide(route, titled(first, "Blocks (Page 1/6)"), 9).status());
        assertEquals(Status.BLOCKED, decide(route, titled(first, "Blocks Page 1/5"), 9).status());
        Menu missingNext = withEntries(first, first.entries().stream().filter(entry -> entry.slot() != 50).toList());
        assertEquals(Status.WAIT, decide(route, missingNext, 9).status());
    }

    @Test void navigationRequiresExactSlotItemArrowAndUniqueLabel() {
        Menu shop = CATALOG.get("shop");
        Route start = Route.start(Product.BIRCH_PLANKS);
        assertEquals(Status.BLOCKED, decide(start, replace(shop, 11, entry ->
                new Entry(11, "minecraft:dirt", entry.label(), entry.lore(), 1)), 9).status());
        assertEquals(Status.BLOCKED, decide(start, replace(shop, 11, entry ->
                new Entry(0, entry.itemId(), entry.label(), entry.lore(), 1)), 9).status());
        var duplicate = new ArrayList<>(shop.entries());
        duplicate.add(new Entry(0, "minecraft:grass_block", "Blocks", List.of(), 1));
        assertEquals(Status.BLOCKED, decide(start, withEntries(shop, duplicate), 9).status());
        Route blocks = decide(start, shop, 9).nextRoute();
        Menu page = CATALOG.get("shop/blocks/1");
        assertEquals(Status.WAIT, decide(blocks, replace(page, 50, entry ->
                new Entry(50, entry.itemId(), "Next page", List.of(), 1)), 9).status());
        assertEquals(Status.BLOCKED, decide(blocks, replace(page, 50, entry ->
                new Entry(50, entry.itemId(), entry.label(), List.of("Click to purchase"), 1)), 9).status());
    }

    @Test void formattingIsCosmeticButPunctuationAndProductWordsRemainSignificant() {
        Menu shop = CATALOG.get("shop");
        Menu styled = titled(replace(shop, 11, entry -> new Entry(11, entry.itemId(),
                "  \u00a7aBLOCKS  ", List.of(), 1)), "\u00a76Shop   | Economy");
        assertEquals(Status.NAVIGATE, decide(Route.start(Product.GLOWSTONE), styled, 9).status());
        assertTrue(isStacksTitle(Product.BIRCH_PLANKS, "\u00a7aBUYING  stacks of Birch Planks"));
        assertFalse(isStacksTitle(Product.BIRCH_PLANKS, "Buying stacks: Birch Planks"));
        assertFalse(isStacksTitle(Product.BIRCH_PLANKS, "Buying stacks of Birch Log"));
        assertEquals(Status.BLOCKED, decide(Route.start(Product.GLOWSTONE), titled(shop, "Shop Economy"), 9).status());
    }

    @Test void productMenuMustShowTheSelectedProductBeforeEnteringStacks() {
        Product product = Product.BIRCH_PLANKS;
        Route route = productRoute(product);
        Menu menu = CATALOG.get(productPath(product));
        assertEquals(Status.BLOCKED, decide(route, replace(menu, 22, entry ->
                new Entry(22, "minecraft:birch_log", entry.label(), entry.lore(), 1)), 9).status());
        assertEquals(Status.WAIT, decide(route, withEntries(menu, menu.entries().stream()
                .filter(entry -> entry.slot() != 22).toList()), 9).status());
        assertEquals(Status.BLOCKED, decide(route, replace(menu, 35, entry ->
                new Entry(35, "minecraft:lime_stained_glass_pane", entry.label(), entry.lore(), 1)), 9).status());
    }

    @Test void missingNegativeMalformedAndAmbiguousUnitPricesCannotOpenStackSelection() {
        Route route = productRoute(Product.BIRCH_PLANKS);
        Menu menu = CATALOG.get(productPath(Product.BIRCH_PLANKS));
        List<List<String>> invalid = List.of(List.of(), List.of("Sell price: $8.75"), List.of("Buy price: $-8.75"),
                List.of("Buy price: $0"), List.of("Buy price: $8.755"), List.of("Buy price: $8,75"),
                List.of("Buy price: $8.75", "Buy price: $8.75"), List.of("Buy price: $8.75", "Discount applied"),
                List.of("Buy price: $1e3"), List.of("Buy price: $9999999999999999999999"));
        for (List<String> lore : invalid) {
            Menu changed = replace(menu, 22, entry -> new Entry(22, entry.itemId(), entry.label(), lore, 1));
            assertEquals(Status.BLOCKED, decide(route, changed, 9).status(), lore.toString());
        }
    }

    @Test void currentExplicitQuoteCanChangeButQuantityPriceMustMatchItExactly() {
        Route productRoute = productRoute(Product.BIRCH_PLANKS);
        Menu buying = replace(CATALOG.get(productPath(Product.BIRCH_PLANKS)), 22, entry ->
                new Entry(22, entry.itemId(), entry.label(), List.of("Buy price: $9.00"), 1));
        Route currentQuote = decide(productRoute, buying, 9).nextRoute();
        Menu oldPrices = stackMenu(Product.BIRCH_PLANKS);
        assertEquals(Status.BLOCKED, decide(currentQuote, oldPrices, 9).status());
        Menu oneCurrentOption = withEntries(oldPrices, List.of(new Entry(0, Product.BIRCH_PLANKS.itemId(),
                "Buy 1 stack", List.of("Price: $576.00"), 1)));
        Decision purchase = decide(currentQuote, oneCurrentOption, 9);
        assertEquals(Status.PURCHASE, purchase.status(), purchase.detail());
        assertEquals(new BigDecimal("576.00"), purchase.quotedPrice());
    }

    @Test void changedQuantityPricesBlockEvenWhenTheChangedButtonIsOutsideBudget() {
        Route route = stackRoute(Product.GLOWSTONE);
        Menu menu = stackMenu(Product.GLOWSTONE);
        for (List<String> lore : List.of(List.of("Price: $31,999"), List.of("Price: $32,000", "Price: $32,000"),
                List.of("Sell price: $32,000"), List.of("Price: $-32,000"), List.of("Price: $3,2000"))) {
            assertEquals(Status.BLOCKED, decide(route, replace(menu, 0, entry ->
                    new Entry(0, entry.itemId(), entry.label(), lore, 1)), 1).status(), lore.toString());
        }
        Menu expensive = replace(menu, 8, entry -> new Entry(8, entry.itemId(), entry.label(), List.of("Price: $1"), 1));
        assertEquals(Status.BLOCKED, decide(route, expensive, 1).status());
    }

    @Test void conflictingQuantityIdentityAndDuplicateLabelsBlockInsteadOfFallingBack() {
        Route route = stackRoute(Product.BIRCH_PLANKS);
        Menu menu = stackMenu(Product.BIRCH_PLANKS);
        assertEquals(Status.BLOCKED, decide(route, replace(menu, 0, entry ->
                new Entry(0, "minecraft:birch_log", entry.label(), entry.lore(), 1)), 9).status());
        assertEquals(Status.BLOCKED, decide(route, replace(menu, 0, entry ->
                new Entry(0, entry.itemId(), "Buy 2 stacks", List.of("Price: $1,120"), 1)), 9).status());
        var duplicate = new ArrayList<>(menu.entries());
        duplicate.add(new Entry(20, Product.BIRCH_PLANKS.itemId(), "Buy 1 stack", List.of("Price: $560"), 1));
        assertEquals(Status.BLOCKED, decide(route, withEntries(menu, duplicate), 9).status());
    }

    @Test void uncapturedMalformedQuantitiesAreNeverInterpretedAsAnAllowedButton() {
        Route route = stackRoute(Product.BIRCH_PLANKS);
        Menu menu = stackMenu(Product.BIRCH_PLANKS);
        for (String label : List.of("Buy 0 stacks", "Buy 10 stacks", "Buy -1 stack", "Buy 1.5 stacks",
                "Buy 1 stacks", "Buy 2 stack", "Buy 9 stacks now", "Buy 1 stack for $560")) {
            Menu changed = replace(menu, 0, entry -> new Entry(0, entry.itemId(), label, entry.lore(), 1));
            assertEquals(Status.BLOCKED, decide(route, changed, 9).status(), label);
        }
    }

    @Test void sellMaxFillAllAndUncapturedConfirmControlsNeverProducePurchase() {
        Route route = stackRoute(Product.BIRCH_PLANKS);
        Menu menu = stackMenu(Product.BIRCH_PLANKS);
        for (String label : List.of("Sell 9 stacks", "Buy max", "Buy all", "Buy 9 stacks max", "Fill inventory",
                "Buy more stacks", "Confirm Transaction", "Cancel Transaction")) {
            Menu dangerousOnly = withEntries(menu, List.of(new Entry(8, Product.BIRCH_PLANKS.itemId(), label,
                    List.of("Price: $5,040"), 1)));
            Decision result = decide(route, dangerousOnly, 9);
            assertEquals(Status.WAIT, result.status(), label);
            assertEquals(-1, result.slot());
        }
    }

    @Test void observationsAreImmutableAndRejectOversizedOrDuplicateSlotEvidence() {
        var lore = new ArrayList<>(List.of("Price: $560"));
        Entry entry = new Entry(0, Product.BIRCH_PLANKS.itemId(), "Buy 1 stack", lore, 1);
        lore.clear();
        assertEquals(List.of("Price: $560"), entry.lore());
        var entries = new ArrayList<>(List.of(entry));
        Menu menu = new Menu(1, 1, Product.BIRCH_PLANKS.stacksTitle(), entries, true);
        entries.clear();
        assertEquals(1, menu.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> menu.entries().clear());
        assertThrows(IllegalArgumentException.class, () -> new Menu(1, 1, "title", List.of(entry, entry), true));
        assertThrows(IllegalArgumentException.class, () -> new Menu(1, 1, "x".repeat(129), List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new Entry(54, entry.itemId(), "label", List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, entry.itemId(), "label\n", List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new Entry(0, entry.itemId(), "label", java.util.Collections.nCopies(9, "line"), 1));
    }

    @Test void invalidRouteAndPurchaseBudgetsCannotBypassTheBoundedRoute() {
        assertThrows(IllegalArgumentException.class, () -> new Route(Product.GLOWSTONE, Step.BLOCKS, 5, 5, new MenuKey(1, 1), null));
        assertThrows(IllegalArgumentException.class, () -> new Route(Product.GLOWSTONE, Step.STACKS, 4, 7, new MenuKey(1, 1), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Route(Product.BIRCH_PLANKS, Step.STACKS, 2, 4, null, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> decide(Route.start(Product.GLOWSTONE), null, -1));
        assertThrows(IllegalArgumentException.class, () -> decide(Route.start(Product.GLOWSTONE), null, 37));
    }

    private static Route productRoute(Product product) {
        Route route = Route.start(product);
        route = navigate(route, CATALOG.get("shop"), 11);
        for (int page = 1; page <= product.blocksPage(); page++) {
            route = navigate(route, CATALOG.get("shop/blocks/" + page), page == product.blocksPage() ? product.productSlot() : 50);
        }
        return route;
    }

    private static Route stackRoute(Product product) {
        return navigate(productRoute(product), CATALOG.get(productPath(product)), 35);
    }

    private static Route navigate(Route route, Menu menu, int expectedSlot) {
        Decision decision = decide(route, menu, 9);
        assertEquals(Status.NAVIGATE, decision.status(), decision.detail());
        assertEquals(expectedSlot, decision.slot());
        return decision.nextRoute();
    }

    private static String productPath(Product product) {
        return "shop/blocks/" + product.blocksPage() + "/" + product.itemId().substring("minecraft:".length());
    }

    private static Menu stackMenu(Product product) { return CATALOG.get(productPath(product) + "/buy_stacks"); }
    private static Menu titled(Menu menu, String title) { return new Menu(menu.identity(), menu.syncId(), title, menu.entries(), menu.cursorEmpty()); }
    private static Menu withEntries(Menu menu, List<Entry> entries) { return new Menu(menu.identity(), menu.syncId(), menu.title(), entries, menu.cursorEmpty()); }
    private static Menu replace(Menu menu, int slot, UnaryOperator<Entry> replace) {
        return withEntries(menu, menu.entries().stream().map(entry -> entry.slot() == slot ? replace.apply(entry) : entry).toList());
    }

    private static Map<String, Menu> loadCatalog() {
        try (var resource = MaterialShopPolicyTest.class.getResourceAsStream("/material-shop-catalog.json")) {
            assertNotNull(resource, "captured catalog fixture");
            var root = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("live_read_only_menu_inspection", root.get("capture_kind").getAsString());
            assertEquals(0, root.get("purchases_made").getAsInt());
            Map<String, Menu> menus = new HashMap<>();
            for (var element : root.getAsJsonArray("menus")) {
                var object = element.getAsJsonObject();
                List<Entry> entries = new ArrayList<>();
                for (var raw : object.getAsJsonArray("entries")) {
                    var entry = raw.getAsJsonObject();
                    List<String> lore = new ArrayList<>();
                    for (var line : entry.getAsJsonArray("lore")) { lore.add(line.getAsString()); }
                    // The captured quantity label is evidence; an icon stack count is not a purchase quantity.
                    entries.add(new Entry(entry.get("slot").getAsInt(), entry.get("item_id").getAsString(),
                            entry.get("label").getAsString(), lore, 1));
                }
                menus.put(object.get("route").getAsString(), new Menu(object.get("menu_id").getAsLong(),
                        object.get("sync_id").getAsInt(), object.get("title").getAsString(), entries,
                        object.get("cursor_empty").getAsBoolean()));
            }
            return Map.copyOf(menus);
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
