package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether the mod may buy anything, and the shop layout it buys from, from
 * {@code config/schematic-supervisor/shop.json}. Without that file, or with {@code "enabled": false},
 * no purchase is made: every material comes from registered chests. Omitted layout fields keep the
 * {@link ShopLayout#CAPTURED} values.
 */
record ShopSettings(boolean enabled, ShopLayout layout) {
    static final String FILE_NAME = "shop.json";
    static final ShopSettings DISABLED = new ShopSettings(false, ShopLayout.CAPTURED);
    private static final int MAXIMUM_BYTES = 65_536;
    private static final Set<String> KEYS = Set.of("enabled", "command", "mainTitle", "category", "pageTitle", "pages",
            "nextPage", "mainMenu", "buyingTitle", "buyingProductSlot", "buyStacks", "stacksTitle", "products");
    // Set once at startup from shop.json; tests and the dedicated profile use the captured layout.
    private static volatile ShopSettings current = DISABLED;

    ShopSettings {
        Objects.requireNonNull(layout, "layout");
    }

    static ShopSettings current() {
        return current;
    }

    static void install(ShopSettings settings) {
        current = Objects.requireNonNull(settings, "settings");
    }

    static ShopSettings load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return DISABLED;
        }
        if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_BYTES) {
            throw new IOException(FILE_NAME + " must be a regular file of at most 64 KiB");
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | ClassCastException invalid) {
            throw new IOException(FILE_NAME + " is invalid: " + invalid.getMessage(), invalid);
        }
    }

    static ShopSettings parse(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) { throw new IllegalArgumentException("the file must contain a JSON object"); }
        JsonObject root = parsed.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!KEYS.contains(key)) { throw new IllegalArgumentException("unknown key " + key); }
        }
        ShopLayout defaults = ShopLayout.CAPTURED;
        ShopLayout layout = new ShopLayout(
                string(root, "command", defaults.command()),
                string(root, "mainTitle", defaults.mainTitle()),
                button(root, "category", defaults.category()),
                string(root, "pageTitle", defaults.pageTitle()),
                integer(root, "pages", defaults.pages()),
                button(root, "nextPage", defaults.nextPage()),
                button(root, "mainMenu", defaults.mainMenu()),
                string(root, "buyingTitle", defaults.buyingTitle()),
                integer(root, "buyingProductSlot", defaults.buyingProductSlot()),
                button(root, "buyStacks", defaults.buyStacks()),
                string(root, "stacksTitle", defaults.stacksTitle()),
                products(root, defaults.products()));
        return new ShopSettings(bool(root, "enabled", false), layout);
    }

    private static Map<String, ShopLayout.Place> products(JsonObject root, Map<String, ShopLayout.Place> defaults) {
        if (!root.has("products")) { return defaults; }
        JsonObject products = object(root.get("products"), "products");
        Map<String, ShopLayout.Place> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
            JsonObject place = object(entry.getValue(), "products." + entry.getKey());
            only(place, Set.of("name", "page", "slot"), "products." + entry.getKey());
            result.put(entry.getKey(), new ShopLayout.Place(string(place, "name", null), integer(place, "page", null),
                    integer(place, "slot", null)));
        }
        return result;
    }

    private static ShopLayout.Button button(JsonObject root, String key, ShopLayout.Button fallback) {
        if (!root.has(key)) { return fallback; }
        JsonObject button = object(root.get(key), key);
        only(button, Set.of("label", "item", "slot"), key);
        return new ShopLayout.Button(string(button, "label", null), string(button, "item", null),
                integer(button, "slot", null));
    }

    private static JsonObject object(JsonElement value, String key) {
        if (value == null || !value.isJsonObject()) { throw new IllegalArgumentException(key + " must be an object"); }
        return value.getAsJsonObject();
    }

    private static void only(JsonObject object, Set<String> keys, String name) {
        for (String key : object.keySet()) {
            if (!keys.contains(key)) { throw new IllegalArgumentException(name + " has unknown key " + key); }
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            if (fallback == null) { throw new IllegalArgumentException(key + " is required"); }
            return fallback;
        }
        if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject object, String key, Integer fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            if (fallback == null) { throw new IllegalArgumentException(key + " is required"); }
            return fallback;
        }
        if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isNumber()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException notInteger) {
            throw new IllegalArgumentException(key + " must be an integer", notInteger);
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) { return fallback; }
        if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isBoolean()) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return value.getAsBoolean();
    }
}
