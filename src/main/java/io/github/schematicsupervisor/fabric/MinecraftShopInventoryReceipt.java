package io.github.schematicsupervisor.fabric;

import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;

/** Opens the known Blocks category solely for a full inventory receipt; never selects a purchase. */
final class MinecraftShopInventoryReceipt {
    private final MinecraftClient client;
    private final MinecraftDirtShopPort menus;
    private Object world, player, connection;
    private DirtShopPurchase.Menu owned;
    private MaterialShopPolicy.Route route;
    private int ticks;
    private int receiptTicks;
    private MaterialShopPolicy.MenuKey stableMain;
    private int stableMainTicks;
    private boolean opened;
    private boolean receiptMenuAccepted;
    private StaleInventoryReceiptRefresh staleRefresh;
    private String waitingFor = "shop menu";

    static final class ReadTimeout extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        ReadTimeout(String detail) { super("Timed out waiting for the shop inventory receipt: " + detail); }
    }

    MinecraftShopInventoryReceipt(MinecraftClient client) {
        this.client = client;
        menus = new MinecraftDirtShopPort(client);
    }

    void configureMossCustody(MossToolCustody custody) { menus.configureMossCustody(custody); }

    void open() {
        if (opened || owned != null) { throw new IllegalStateException("The previous receipt menu is not settled"); }
        world = client.world; player = client.player; connection = client.getNetworkHandler();
        ticks = 0; receiptTicks = 0; receiptMenuAccepted = false;
        stableMain = null; stableMainTicks = 0;
        staleRefresh = new StaleInventoryReceiptRefresh();
        waitingFor = "shop menu";
        route = ShopInventoryReceiptPolicy.start();
        menus.sendShop();
        opened = true;
    }

    Optional<MossDepositJournal.Observation> tick(MossDepositJournal.Context context) {
        if (!opened || world != client.world || player != client.player || connection != client.getNetworkHandler()) {
            throw new IllegalStateException("The in-place inventory receipt context changed");
        }
        if (++ticks > 200) { throw new ReadTimeout(waitingFor); }
        var observed = menus.observe();
        if (!observed.connected() || !observed.cursorEmpty() && !observed.ownedClickCursor()) {
            throw new IllegalStateException("The inventory receipt lost its connected empty cursor");
        }
        if (!observed.cursorEmpty()) { waitingFor = "server to clear the owned category-click cursor"; return Optional.empty(); }
        var menu = observed.menu();
        if (menu == null) { waitingFor = "shop menu"; return Optional.empty(); }
        var converted = new MaterialShopPolicy.Menu(menu.identity(), menu.syncId(), menu.title(), menu.entries().stream()
                .map(entry -> new MaterialShopPolicy.Entry(entry.slot(), entry.itemId(), entry.label(),
                        entry.lore(), entry.stackCount())).toList(), true);
        var decision = ShopInventoryReceiptPolicy.decide(route, converted);
        if (decision.action() == ShopInventoryReceiptPolicy.Action.WAIT) { waitingFor = decision.detail(); return Optional.empty(); }
        if (decision.action() == ShopInventoryReceiptPolicy.Action.BLOCKED) {
            throw new IllegalStateException("The expected inventory receipt menu changed");
        }
        if (decision.action() == ShopInventoryReceiptPolicy.Action.OPEN_BLOCKS) {
            owned = menu;
            waitingFor = "stable main menu before the category click";
            if (!converted.key().equals(stableMain)) { stableMain = converted.key(); stableMainTicks = 0; }
            if (++stableMainTicks < 10) { return Optional.empty(); }
            route = decision.route(); // Commit the route before input; this opening never repeats the category click.
            menus.click(menu, decision.slot());
            return Optional.empty();
        }
        if (receiptMenuAccepted && (owned.identity() != menu.identity() || owned.syncId() != menu.syncId())) {
            throw new IllegalStateException("The owned inventory receipt menu changed");
        }
        owned = menu; receiptMenuAccepted = true;
        waitingFor = "stable six-row Blocks inventory";
        if (++receiptTicks < 20 || !(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)
                || handler.getRows() != 6) {
            return Optional.empty();
        }
        var packet = ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler);
        if (packet.isEmpty()) { waitingFor = "a full server packet for the owned Blocks menu"; return Optional.empty(); }
        var facts = MinecraftMossDepositCapture.capture(client, handler, packet.orElseThrow());
        if (facts.isEmpty()) {
            waitingFor = "complete bounded inventory facts";
            return Optional.empty();
        }
        if (!MinecraftMossDepositCapture.matchesLiveBaseline(client, handler, facts.orElseThrow())) {
            waitingFor = "current inventory to match the full server packet";
            if (staleRefresh.observe(packet.orElseThrow().stamp())) {
                // A later slot update can stale this packet permanently. Reopen only this owned
                // read-only menu; retain the overall timeout, discard intent and refresh budget.
                close();
                route = ShopInventoryReceiptPolicy.start();
                receiptTicks = 0; receiptMenuAccepted = false;
                stableMain = null; stableMainTicks = 0;
                menus.sendShop();
                opened = true;
                waitingFor = "fresh shop receipt after inventory changed";
            }
            return Optional.empty();
        }
        return Optional.of(new MossDepositJournal.Observation(context, packet.orElseThrow().stamp(), facts.orElseThrow()));
    }

    void close() {
        if (world == client.world && player == client.player && connection == client.getNetworkHandler()
                && owned != null) { menus.close(owned); }
        if (client.player == null || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("The owned shop receipt could not be safely closed");
        }
        opened = false; owned = null;
    }

    void cancel() {
        try { close(); } catch (RuntimeException ignored) { /* Foreign menus remain untouched. */ }
    }
}
