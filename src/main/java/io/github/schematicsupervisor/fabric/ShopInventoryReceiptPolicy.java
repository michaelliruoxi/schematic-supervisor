package io.github.schematicsupervisor.fabric;

/** One category navigation supplies a chest-sized inventory receipt; no purchase action exists. */
final class ShopInventoryReceiptPolicy {
    enum Action { WAIT, OPEN_BLOCKS, ACCEPT, BLOCKED }
    record Decision(Action action, int slot, MaterialShopPolicy.Route route, String detail) { }

    private ShopInventoryReceiptPolicy() { }

    static MaterialShopPolicy.Route start() {
        return start(ShopSettings.current().layout());
    }

    /** The receipt only opens the category's first page, so any product the layout sells gives the route. */
    static MaterialShopPolicy.Route start(ShopLayout layout) {
        for (MaterialShopPolicy.Product product : MaterialShopPolicy.Product.values()) {
            if (layout.sells(product)) { return MaterialShopPolicy.Route.start(product, layout); }
        }
        throw new IllegalStateException("The shop layout places no purchasable product to reach its first page");
    }

    static Decision decide(MaterialShopPolicy.Route route, MaterialShopPolicy.Menu menu) {
        if (route == null || route.step() != MaterialShopPolicy.Step.SHOP
                && (route.step() != MaterialShopPolicy.Step.BLOCKS || route.expectedPage() != 1)) {
            throw new IllegalArgumentException("Receipt navigation ends on the first Blocks page");
        }
        var next = MaterialShopPolicy.decide(route, menu, 0);
        return switch (next.status()) {
            case WAIT -> new Decision(Action.WAIT, -1, route, next.detail());
            case BLOCKED -> new Decision(Action.BLOCKED, -1, route, next.detail());
            case PURCHASE -> throw new IllegalStateException("Receipt navigation cannot purchase");
            case NAVIGATE -> route.step() == MaterialShopPolicy.Step.SHOP
                    ? new Decision(Action.OPEN_BLOCKS, next.slot(), next.nextRoute(), "Open the receipt category once")
                    : new Decision(Action.ACCEPT, -1, route, "Read the full inventory receipt without another click");
        };
    }
}
