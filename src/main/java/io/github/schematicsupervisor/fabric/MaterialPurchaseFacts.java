package io.github.schematicsupervisor.fabric;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Complete packet-backed main/cursor facts with separate local equipment guards. */
public final class MaterialPurchaseFacts {
    private MaterialPurchaseFacts() { }

    public enum Product {
        DIRT("minecraft:dirt", "Dirt"),
        GLOWSTONE("minecraft:glowstone", "Glowstone"),
        BIRCH_PLANKS("minecraft:birch_planks", "Birch Planks");

        private final String itemId;
        private final String displayName;
        Product(String itemId, String displayName) { this.itemId = itemId; this.displayName = displayName; }
        public String itemId() { return itemId; }
        public String displayName() { return displayName; }
        public static Product fromItemId(String itemId) {
            for (Product product : values()) { if (product.itemId.equals(itemId)) { return product; } }
            throw new IllegalArgumentException("product is outside the material purchase allowlist");
        }
    }

    /** Fingerprints include count; plainProduct means default components of the quoted product only. */
    public record StackFacts(String fingerprint, String itemId, int count, int maxCount,
                             boolean empty, boolean plainProduct) {
        public StackFacts {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(itemId, "itemId");
            if (!fingerprint.matches("[0-9a-f]{64}") || itemId.length() > 128
                    || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || count < 0 || maxCount < 0 || maxCount > 99) {
                throw new IllegalArgumentException("invalid bounded stack facts");
            }
            if (empty ? count != 0 || maxCount != 0 || plainProduct || !itemId.equals("minecraft:air")
                    : count < 1 || maxCount < 1 || count > maxCount || itemId.equals("minecraft:air")) {
                throw new IllegalArgumentException("inconsistent stack facts");
            }
            if (plainProduct) {
                Product.fromItemId(itemId);
                if (maxCount != 64) { throw new IllegalArgumentException("plain product must have stack limit 64"); }
            }
        }
    }

    /** Main slots are ordered by real player inventory index 0..35, never by container slot index. */
    public record Snapshot(List<StackFacts> main, StackFacts cursor, StackFacts offhand,
                           List<StackFacts> armor) {
        public Snapshot {
            main = List.copyOf(main);
            armor = List.copyOf(armor);
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(offhand, "offhand");
            if (main.size() != 36 || armor.size() != 4) {
                throw new IllegalArgumentException("complete main36 and four equipment guards are required");
            }
        }
    }

    /** Exact current accepted quote; routeHash includes the raw accepted menu and route facts. */
    public record Quote(Product product, int stacks, long quotedPriceMinor, String routeHash,
                        String menuTitle, int buttonSlot) {
        public Quote {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(routeHash, "routeHash");
            bounded(menuTitle, 128, "menuTitle");
            if (stacks < 1 || stacks > 9 || buttonSlot != stacks - 1 || quotedPriceMinor <= 0
                    || !routeHash.matches("[0-9a-f]{64}")
                    || !stacksTitle(product, menuTitle)) {
                throw new IllegalArgumentException("purchase requires the exact bounded product stack quote");
            }
        }
        public int quantity() { return stacks * 64; }
    }

    public static Optional<String> baselineProblem(Snapshot before, Quote quote) {
        if (!before.cursor().empty()) { return Optional.of("The authoritative baseline cursor is not empty."); }
        if (!validProductFacts(before, quote.product())) {
            return Optional.of("Plain-product facts disagree with the quoted product.");
        }
        int capacity = before.main().stream().mapToInt(stack -> stack.empty() ? 64
                : stack.plainProduct() ? 64 - stack.count() : 0).sum();
        if (capacity < quote.quantity()) {
            return Optional.of("The quoted whole stacks exceed the original plain-product main-inventory capacity.");
        }
        return Optional.empty();
    }

    public static Optional<String> receiptProblem(Snapshot before, Quote quote, Snapshot after) {
        Optional<String> baseline = baselineProblem(before, quote);
        if (baseline.isPresent()) { return baseline; }
        if (!after.cursor().empty()) { return Optional.of("The server receipt cursor is not empty."); }
        if (!before.offhand().equals(after.offhand()) || !before.armor().equals(after.armor())) {
            return Optional.of("Local equipment guards changed while the purchase was pending.");
        }
        if (!validProductFacts(after, quote.product())) {
            return Optional.of("Receipt plain-product facts disagree with the quoted product.");
        }
        int beforeCount = 0;
        int afterCount = 0;
        for (int index = 0; index < 36; index++) {
            StackFacts original = before.main().get(index);
            StackFacts received = after.main().get(index);
            if (original.empty() || original.plainProduct()) {
                if (!received.empty() && !received.plainProduct()) {
                    return Optional.of("An unexpected or modified item occupies an eligible product slot.");
                }
            } else if (!original.equals(received)) {
                return Optional.of("An unrelated protected main-inventory slot changed.");
            }
            if (original.plainProduct()) { beforeCount += original.count(); }
            if (received.plainProduct()) { afterCount += received.count(); }
        }
        return afterCount - beforeCount == quote.quantity() ? Optional.empty()
                : Optional.of("The full server receipt does not show the exact quoted plain-product increase.");
    }

    private static boolean validProductFacts(Snapshot snapshot, Product product) {
        return snapshot.main().stream().allMatch(stack -> !stack.plainProduct() || stack.itemId().equals(product.itemId()));
    }

    static void bounded(String value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be non-empty and bounded without control characters");
        }
    }

    /** The stack menu of this product, in the captured layout or the one shop.json configures. */
    private static boolean stacksTitle(Product product, String title) {
        for (ShopLayout layout : List.of(ShopLayout.CAPTURED, ShopSettings.current().layout())) {
            ShopLayout.Place place = layout.products().get(product.itemId());
            String name = place == null ? product.displayName() : place.name();
            if (canonical(title).equals(canonical(layout.stacksTitle().replace("{name}", name)))) {
                return true;
            }
        }
        return false;
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value.replaceAll("(?i)§[0-9A-FK-OR]", ""), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
