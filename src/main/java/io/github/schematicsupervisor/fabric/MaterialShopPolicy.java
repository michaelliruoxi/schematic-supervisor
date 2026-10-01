package io.github.schematicsupervisor.fabric;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure navigation for the non-Dirt purchase routes, through a {@link ShopLayout}; purchases still require
 * an independent receipt. Without a layout, routes use the captured one.
 */
public final class MaterialShopPolicy {
    public static final int MAXIMUM_STACKS = 9;
    /** The captured layout's navigation bound; a route's own bound comes from its layout. */
    public static final int MAXIMUM_NAVIGATION_CLICKS = 6;
    private static final BigDecimal MAXIMUM_PRICE = new BigDecimal("999999999999.99");
    private static final Pattern BUY_STACKS = Pattern.compile("buy ([1-9]) (stack|stacks)");
    private static final Pattern MONEY = Pattern.compile("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?");
    private static final Pattern UNSAFE_ACTION = Pattern.compile("\\b(?:sell|selling|more|fill|all|max|maximum)\\b");

    public enum Product {
        BIRCH_PLANKS("minecraft:birch_planks", "Birch Planks", 2, 20),
        GLOWSTONE("minecraft:glowstone", "Glowstone", 4, 10);

        private final String itemId;
        private final String displayName;
        private final int blocksPage;
        private final int productSlot;

        Product(String itemId, String displayName, int blocksPage, int productSlot) {
            this.itemId = itemId;
            this.displayName = displayName;
            this.blocksPage = blocksPage;
            this.productSlot = productSlot;
        }
        public String itemId() { return itemId; }
        public String displayName() { return displayName; }
        public int blocksPage() { return blocksPage; }
        public int productSlot() { return productSlot; }
        public String buyingTitle() { return "Buying " + displayName; }
        public String stacksTitle() { return "Buying stacks of " + displayName; }
    }

    public enum Step { SHOP, BLOCKS, PRODUCT, STACKS, AWAIT_RECEIPT }
    public enum Status { WAIT, BLOCKED, NAVIGATE, PURCHASE }

    public record Entry(int slot, String itemId, String label, List<String> lore, int stackCount) {
        public Entry {
            bounded(itemId, 128, "itemId");
            bounded(label, 256, "label");
            lore = List.copyOf(lore);
            if (slot < 0 || slot >= 54 || stackCount < 1 || stackCount > 99 || lore.size() > 8
                    || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("invalid bounded shop entry");
            }
            for (String line : lore) { bounded(line, 256, "lore"); }
        }
    }

    public record Menu(long identity, int syncId, String title, List<Entry> entries, boolean cursorEmpty) {
        public Menu {
            new MenuKey(identity, syncId);
            bounded(title, 128, "title");
            entries = List.copyOf(entries);
            if (entries.size() > 54 || entries.stream().map(Entry::slot).distinct().count() != entries.size()) {
                throw new IllegalArgumentException("menu entries must be bounded with unique container slots");
            }
        }
        public MenuKey key() { return new MenuKey(identity, syncId); }
    }

    public record MenuKey(long identity, int syncId) {
        public MenuKey {
            if (identity < 1 || syncId < 1) { throw new IllegalArgumentException("menu identity must be positive"); }
        }
    }

    public record Route(Product product, Step step, int expectedPage, int navigationClicks,
                        MenuKey previousMenu, BigDecimal unitPrice, ShopLayout layout) {
        public Route {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(layout, "layout");
            if (!layout.sells(product)) {
                throw new IllegalArgumentException("the shop layout has no place for " + product.itemId());
            }
            int productPage = layout.place(product).page();
            boolean initial = step == Step.SHOP;
            boolean quoted = step == Step.STACKS || step == Step.AWAIT_RECEIPT;
            int expectedClicks = switch (step) {
                case SHOP -> 0;
                case BLOCKS -> expectedPage;
                case PRODUCT -> productPage + 1;
                case STACKS, AWAIT_RECEIPT -> productPage + 2;
            };
            if (expectedPage < 1 || expectedPage > productPage
                    || initial && expectedPage != 1
                    || step != Step.SHOP && step != Step.BLOCKS && expectedPage != productPage
                    || navigationClicks != expectedClicks || navigationClicks > layout.maximumNavigationClicks()
                    || initial != (previousMenu == null) || quoted != (unitPrice != null)) {
                throw new IllegalArgumentException("route cursor does not match the bounded captured navigation");
            }
            if (unitPrice != null) { validPrice(unitPrice); }
        }
        /** A route through the captured layout. */
        public Route(Product product, Step step, int expectedPage, int navigationClicks,
                     MenuKey previousMenu, BigDecimal unitPrice) {
            this(product, step, expectedPage, navigationClicks, previousMenu, unitPrice, ShopLayout.CAPTURED);
        }
        public static Route start(Product product) { return start(product, ShopLayout.CAPTURED); }
        public static Route start(Product product, ShopLayout layout) {
            return new Route(product, Step.SHOP, 1, 0, null, null, layout);
        }
    }

    public record Decision(Status status, int slot, int stacks, BigDecimal quotedPrice, Route nextRoute, String detail) {
        public Decision {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(nextRoute, "nextRoute");
            bounded(detail, 512, "detail");
            boolean click = status == Status.NAVIGATE || status == Status.PURCHASE;
            boolean purchase = status == Status.PURCHASE;
            if (click ? slot < 0 || slot >= 54 : slot != -1) {
                throw new IllegalArgumentException("decision slot does not match its action");
            }
            if (purchase ? stacks < 1 || stacks > MAXIMUM_STACKS || slot != stacks - 1
                    || quotedPrice == null || nextRoute.step() != Step.AWAIT_RECEIPT
                    : stacks != 0 || quotedPrice != null) {
                throw new IllegalArgumentException("purchase decision must have one bounded exact quote");
            }
            if (quotedPrice != null) { validPrice(quotedPrice); }
        }
    }

    private MaterialShopPolicy() { }

    public static Optional<Product> forItemId(String itemId) {
        return java.util.Arrays.stream(Product.values()).filter(product -> product.itemId().equals(itemId)).findFirst();
    }

    /** The caller owns finite waiting/dwell, live pre-click revalidation, approval, and durable receipt state. */
    public static Decision decide(Route route, Menu menu, int maximumStacks) {
        Objects.requireNonNull(route, "route");
        if (maximumStacks < 0 || maximumStacks > 36) { throw new IllegalArgumentException("invalid stack budget"); }
        if (route.step() == Step.AWAIT_RECEIPT) {
            return waiting(route, "Purchase selection is terminal; wait for independent receipt settlement.");
        }
        if (menu == null || route.previousMenu() != null && route.previousMenu().equals(menu.key())) {
            return waiting(route, "Waiting for a fresh expected menu; no previous click may be repeated.");
        }
        if (!menu.cursorEmpty()) { return blocked(route, "Cursor is occupied; no shop action is eligible."); }
        if (!canonicalText(menu.title()).equals(canonicalText(expectedTitle(route)))) {
            return blocked(route, "Menu title or page differs from the captured product route.");
        }
        if (menu.entries().isEmpty()) { return waiting(route, "Waiting for the expected menu contents."); }
        try {
            ShopLayout layout = route.layout();
            return switch (route.step()) {
                case SHOP -> navigate(route, menu, layout.category(), Step.BLOCKS, 1, null);
                case BLOCKS -> route.expectedPage() < layout.place(route.product()).page()
                        ? navigate(route, menu, layout.nextPage(), Step.BLOCKS, route.expectedPage() + 1, null)
                        : openProduct(route, menu);
                case PRODUCT -> openStacks(route, menu);
                case STACKS -> selectPurchase(route, menu, Math.min(MAXIMUM_STACKS, maximumStacks));
                case AWAIT_RECEIPT -> throw new IllegalStateException("terminal route was already handled");
            };
        } catch (IllegalArgumentException changed) {
            return blocked(route, changed.getMessage());
        }
    }

    public static boolean isStacksTitle(Product product, String title) {
        return isStacksTitle(product, title, ShopLayout.CAPTURED);
    }

    public static boolean isStacksTitle(Product product, String title, ShopLayout layout) {
        return product != null && title != null && layout.sells(product)
                && canonicalText(title).equals(canonicalText(layout.stacksTitle(product)));
    }

    static boolean isPostPurchaseReturn(Menu menu, MaterialPurchaseJournal pending,
                                        MaterialPurchaseJournal.Observation received) {
        return isPostPurchaseReturn(menu, pending, received, ShopLayout.CAPTURED);
    }

    /**
     * A server return to the first category page grants cleanup ownership only, never purchase credit. The
     * page is recognized by a priced product the layout places on it and its main-menu and next buttons.
     */
    static boolean isPostPurchaseReturn(Menu menu, MaterialPurchaseJournal pending,
                                        MaterialPurchaseJournal.Observation received, ShopLayout layout) {
        if (menu == null || pending == null || received == null || !menu.cursorEmpty()
                || pending.stage() != MaterialPurchaseJournal.Stage.PENDING
                || pending.reconciliationBarrier() != null
                || forItemId(pending.quote().product().itemId()).isEmpty()
                || menu.syncId() != received.stamp().syncId()
                || received.stamp().openGeneration() - pending.before().stamp().openGeneration() != 1
                || !canonicalText(menu.title()).equals(canonicalText(layout.pageTitle(1)))
                || menu.entries().size() != 54 || pending.receiptProblem(received).isPresent()) {
            return false;
        }
        Optional<Map.Entry<String, ShopLayout.Place>> landmark = layout.products().entrySet().stream()
                .filter(entry -> entry.getValue().page() == 1)
                .min(Map.Entry.comparingByKey());
        if (landmark.isEmpty()) { return false; }
        try {
            ShopLayout.Place place = landmark.get().getValue();
            Entry firstProduct = unique(menu, place.name(), landmark.get().getKey(), place.slot());
            Entry back = unique(menu, layout.mainMenu());
            Entry next = unique(menu, layout.nextPage());
            if (firstProduct == null || back == null || next == null || !next.lore().isEmpty()) { return false; }
            price(firstProduct, "buy price", true);
            return true;
        } catch (IllegalArgumentException changed) { return false; }
    }

    /** Identifies only a fresh expected empty-cursor menu, including one whose contents are still arriving. */
    public static boolean isExpectedMenu(Route route, Menu menu) {
        Objects.requireNonNull(route, "route");
        return menu != null && route.step() != Step.AWAIT_RECEIPT && menu.cursorEmpty()
                && !menu.key().equals(route.previousMenu())
                && canonicalText(menu.title()).equals(canonicalText(expectedTitle(route)));
    }

    public static String canonicalText(String text) {
        return Normalizer.normalize(Objects.requireNonNull(text).replaceAll("(?i)\\u00a7[0-9a-fk-orx]", ""),
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Decision openProduct(Route route, Menu menu) {
        ShopLayout.Place place = route.layout().place(route.product());
        Entry product = unique(menu, place.name(), route.product().itemId(), place.slot());
        if (product == null) { return waiting(route, "Waiting for the exact captured product entry."); }
        price(product, "buy price", true);
        return navigation(route, menu, product.slot(), Step.PRODUCT, route.expectedPage(), null);
    }

    private static Decision openStacks(Route route, Menu menu) {
        ShopLayout layout = route.layout();
        Entry product = unique(menu, layout.place(route.product()).name(), route.product().itemId(),
                layout.buyingProductSlot());
        if (product == null) { return waiting(route, "Waiting for the exact product identity in the buying menu."); }
        BigDecimal unitPrice = price(product, "buy price", false);
        return navigate(route, menu, layout.buyStacks(), Step.STACKS, route.expectedPage(), unitPrice);
    }

    private static Decision navigate(Route route, Menu menu, ShopLayout.Button button,
                                     Step next, int page, BigDecimal unitPrice) {
        Entry entry = unique(menu, button);
        if (entry == null) { return waiting(route, "Waiting for the exact captured navigation button."); }
        if (!entry.lore().isEmpty()) { throw new IllegalArgumentException("Navigation button lore differs from the captured route."); }
        return navigation(route, menu, button.slot(), next, page, unitPrice);
    }

    private static Decision navigation(Route route, Menu menu, int slot, Step next, int page, BigDecimal unitPrice) {
        if (route.navigationClicks() >= route.layout().maximumNavigationClicks()) {
            return blocked(route, "Captured navigation click budget is exhausted.");
        }
        return new Decision(Status.NAVIGATE, slot, 0, null,
                new Route(route.product(), next, page, route.navigationClicks() + 1, menu.key(), unitPrice,
                        route.layout()),
                "Captured navigation entry matched; revalidate the live menu before dispatch.");
    }

    private static Decision selectPurchase(Route route, Menu menu, int maximumStacks) {
        if (maximumStacks == 0) { return blocked(route, "No whole-stack purchase budget is available."); }
        Entry selected = null;
        int selectedStacks = 0;
        BigDecimal selectedPrice = null;
        boolean[] seen = new boolean[MAXIMUM_STACKS + 1];
        for (Entry entry : menu.entries()) {
            String label = canonicalText(entry.label());
            if (UNSAFE_ACTION.matcher(label).find()) { continue; }
            var amount = BUY_STACKS.matcher(label);
            if (!amount.matches()) {
                if (label.startsWith("buy ") && label.contains("stack")) {
                    throw new IllegalArgumentException("An uncaptured or malformed stack quantity is present.");
                }
                continue;
            }
            int stacks = Integer.parseInt(amount.group(1));
            if (!amount.group(2).equals(stacks == 1 ? "stack" : "stacks")
                    || !entry.itemId().equals(route.product().itemId()) || seen[stacks]
                    || entry.slot() != stacks - 1) {
                throw new IllegalArgumentException("Stack option identity, quantity, slot, or uniqueness changed.");
            }
            seen[stacks] = true;
            BigDecimal quoted = price(entry, "price", false);
            BigDecimal expected = route.unitPrice().multiply(BigDecimal.valueOf(64L * stacks));
            if (quoted.compareTo(expected) != 0) {
                throw new IllegalArgumentException("Stack price does not match the current product quote and exact quantity.");
            }
            if (stacks <= maximumStacks && stacks > selectedStacks) {
                selected = entry;
                selectedStacks = stacks;
                selectedPrice = quoted;
            }
        }
        if (selected == null) { return waiting(route, "Waiting for a captured stack option within the explicit budget."); }
        return new Decision(Status.PURCHASE, selected.slot(), selectedStacks, selectedPrice,
                new Route(route.product(), Step.AWAIT_RECEIPT, route.expectedPage(), route.navigationClicks(), menu.key(),
                        route.unitPrice(), route.layout()),
                "One exact purchase quote selected; persist the intent and revalidate before its single click.");
    }

    private static Entry unique(Menu menu, ShopLayout.Button button) {
        return unique(menu, button.label(), button.item(), button.slot());
    }

    private static Entry unique(Menu menu, String label, String itemId, int slot) {
        List<Entry> matches = menu.entries().stream().filter(entry -> canonicalText(entry.label()).equals(canonicalText(label))).toList();
        if (matches.isEmpty()) { return null; }
        if (matches.size() != 1 || !matches.getFirst().itemId().equals(itemId) || matches.getFirst().slot() != slot) {
            throw new IllegalArgumentException("Captured navigation label is ambiguous or has changed item identity/slot.");
        }
        return matches.getFirst();
    }

    private static BigDecimal price(Entry entry, String prefix, boolean category) {
        BigDecimal found = null;
        for (String raw : entry.lore()) {
            String line = canonicalText(raw);
            if (category && line.startsWith("sell price: $")) { continue; }
            String start = prefix + ": $";
            if (!line.startsWith(start) || !MONEY.matcher(line.substring(start.length())).matches() || found != null) {
                throw new IllegalArgumentException("Price evidence is missing, malformed, negative, or ambiguous.");
            }
            found = new BigDecimal(line.substring(start.length()).replace(",", ""));
            validPrice(found);
        }
        if (found == null) { throw new IllegalArgumentException("The expected explicit buy-price evidence is missing."); }
        return found;
    }

    private static String expectedTitle(Route route) {
        ShopLayout layout = route.layout();
        return switch (route.step()) {
            case SHOP -> layout.mainTitle();
            case BLOCKS -> layout.pageTitle(route.expectedPage());
            case PRODUCT -> layout.buyingTitle(route.product());
            case STACKS, AWAIT_RECEIPT -> layout.stacksTitle(route.product());
        };
    }

    private static Decision waiting(Route route, String detail) { return new Decision(Status.WAIT, -1, 0, null, route, detail); }
    private static Decision blocked(Route route, String detail) { return new Decision(Status.BLOCKED, -1, 0, null, route, detail); }
    private static void validPrice(BigDecimal price) {
        if (price.signum() <= 0 || price.scale() > 2 || price.compareTo(MAXIMUM_PRICE) > 0) {
            throw new IllegalArgumentException("Price must be a positive bounded currency amount with at most two decimals.");
        }
    }
    private static void bounded(String text, int maximum, String name) {
        Objects.requireNonNull(text, name);
        if (text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be bounded and contain no control characters");
        }
    }
}
