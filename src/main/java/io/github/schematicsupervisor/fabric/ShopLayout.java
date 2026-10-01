package io.github.schematicsupervisor.fabric;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A server's shop menus as the material purchase route expects to find them: the command that opens
 * the shop, the titles and buttons along the way, and where each product sits. {@link #CAPTURED} is
 * the layout of the server the routes were first captured on.
 *
 * @param products item ID to its place; besides the products the route can buy, a product on the
 *        first category page identifies that page when the shop returns to it after a purchase
 */
record ShopLayout(
        String command,
        String mainTitle,
        Button category,
        String pageTitle,
        int pages,
        Button nextPage,
        Button mainMenu,
        String buyingTitle,
        int buyingProductSlot,
        Button buyStacks,
        String stacksTitle,
        Map<String, Place> products
) {
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_.:-]{1,32}( [A-Za-z0-9_.:-]{1,32}){0,3}");

    static final ShopLayout CAPTURED = new ShopLayout(
            "shop",
            "Shop | Economy",
            new Button("Blocks", "minecraft:grass_block", 11),
            "Blocks (Page {page}/{pages})",
            5,
            new Button("Next page →", "minecraft:paper", 50),
            new Button("Main Menu", "minecraft:birch_door", 45),
            "Buying {name}",
            22,
            new Button("Buy stacks", "minecraft:yellow_stained_glass_pane", 35),
            "Buying stacks of {name}",
            Map.of(
                    "minecraft:dirt", new Place("Dirt", 1, 11),
                    "minecraft:birch_planks", new Place("Birch Planks", 2, 20),
                    "minecraft:glowstone", new Place("Glowstone", 4, 10)));

    /** A menu entry identified by its exact label, item, and container slot. */
    record Button(String label, String item, int slot) {
        Button {
            text(label, "button label");
            requireItem(item);
            requireSlot(slot);
        }
    }

    /** Where a product sits in the category: its label, page, and slot. */
    record Place(String name, int page, int slot) {
        Place {
            text(name, "product name");
            requireSlot(slot);
            if (page < 1) { throw new IllegalArgumentException("product page must be at least 1"); }
        }
    }

    ShopLayout {
        if (!COMMAND.matcher(Objects.requireNonNull(command, "command")).matches()) {
            throw new IllegalArgumentException("shop command must be a plain command name, without the slash");
        }
        text(mainTitle, "main title");
        Objects.requireNonNull(category, "category");
        text(pageTitle, "page title");
        if (!pageTitle.contains("{page}")) {
            throw new IllegalArgumentException("page title must contain {page}");
        }
        if (pages < 1 || pages > 20) { throw new IllegalArgumentException("pages must be 1 to 20"); }
        Objects.requireNonNull(nextPage, "next page button");
        Objects.requireNonNull(mainMenu, "main menu button");
        text(buyingTitle, "buying title");
        text(stacksTitle, "stacks title");
        if (!buyingTitle.contains("{name}") || !stacksTitle.contains("{name}")) {
            throw new IllegalArgumentException("buying titles must contain {name}");
        }
        requireSlot(buyingProductSlot);
        Objects.requireNonNull(buyStacks, "buy stacks button");
        products = Map.copyOf(Objects.requireNonNull(products, "products"));
        products.forEach((item, place) -> {
            requireItem(item);
            if (place.page() > pages) {
                throw new IllegalArgumentException(item + " is on page " + place.page() + " of " + pages);
            }
        });
    }

    Place place(MaterialShopPolicy.Product product) {
        Place place = products.get(product.itemId());
        if (place == null) {
            throw new IllegalArgumentException("The shop layout has no place for " + product.itemId());
        }
        return place;
    }

    boolean sells(MaterialShopPolicy.Product product) {
        return products.containsKey(product.itemId());
    }

    String pageTitle(int page) {
        return pageTitle.replace("{page}", Integer.toString(page)).replace("{pages}", Integer.toString(pages));
    }

    String buyingTitle(MaterialShopPolicy.Product product) {
        return buyingTitle.replace("{name}", place(product).name());
    }

    String stacksTitle(MaterialShopPolicy.Product product) {
        return stacksTitle.replace("{name}", place(product).name());
    }

    /** Category navigation, then one product click and one stack click; nothing on a route may exceed it. */
    int maximumNavigationClicks() {
        int lastPage = 1;
        for (MaterialShopPolicy.Product product : MaterialShopPolicy.Product.values()) {
            if (sells(product)) { lastPage = Math.max(lastPage, place(product).page()); }
        }
        return lastPage + 2;
    }

    private static void text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be 1 to 128 characters of plain text");
        }
    }

    private static void requireItem(String item) {
        if (item == null || !ITEM_ID.matcher(item.toLowerCase(Locale.ROOT)).matches() || !item.equals(item.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("item must be a lower-case namespaced ID, such as minecraft:paper");
        }
    }

    private static void requireSlot(int slot) {
        if (slot < 0 || slot >= 54) { throw new IllegalArgumentException("slot must be 0 to 53"); }
    }
}
