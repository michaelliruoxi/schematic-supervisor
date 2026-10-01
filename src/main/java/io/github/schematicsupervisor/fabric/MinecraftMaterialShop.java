package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;

/** One bounded material batch; navigation is deterministic and purchase input requires a durable intent. */
final class MinecraftMaterialShop implements MaterialPurchaseController.Port {
    private static final int TIMEOUT_TICKS = 200;
    private static final int DWELL_TICKS = 10;
    private enum Stage { IDLE, NAVIGATING, SETTLING, OPENING_RECEIPT, COMPLETE, FAILED, CANCELLED }

    private final MinecraftClient client;
    private final MinecraftDirtShopPort menus;
    private final MaterialPurchaseFileStore store;
    private final Path journalPath;
    private MaterialPurchaseController controller;
    private String unavailable = "";
    private Stage stage = Stage.IDLE;
    private String detail = "Material shopping has not started.";
    private String planId;
    private RunContext context;
    private Object world;
    private Object connection;
    private MaterialShopPolicy.Route route;
    private MaterialPurchaseFacts.Product product;
    private int maximumStacks;
    private int reservedEmptySlots;
    private int ticks;
    private int deadline;
    private int settleUntil;
    private int stableSince;
    private ShopMenuCleanupPolicy.State cleanupState = ShopMenuCleanupPolicy.State.initial();
    private DirtShopPurchase.Menu stableMenu;
    private DirtShopPurchase.Menu ownedMenu;
    private DirtShopPurchase.Menu beforeReopen;
    private DirtShopPurchase.Menu dispatchMenu;
    private GenericContainerScreenHandler dispatchHandler;
    private MaterialPurchaseJournal.Observation dispatchBefore;
    private MaterialPurchaseFacts.Quote dispatchQuote;
    private ServerShopCursorStamp dispatchCursorMark;

    MinecraftMaterialShop(MinecraftClient client, Path journalPath) {
        this.client = Objects.requireNonNull(client, "client");
        menus = new MinecraftDirtShopPort(client);
        this.journalPath = Objects.requireNonNull(journalPath, "journalPath");
        store = new MaterialPurchaseFileStore(journalPath);
        reload();
    }

    void configureMossCustody(MossToolCustody custody) { menus.configureMossCustody(custody); }

    boolean active() { return stage == Stage.NAVIGATING || stage == Stage.SETTLING || stage == Stage.OPENING_RECEIPT; }
    boolean pending() {
        return controller != null && controller.currentJournal()
                .filter(value -> value.stage() == MaterialPurchaseJournal.Stage.PENDING).isPresent();
    }
    boolean unavailable() { return !unavailable.isBlank(); }
    boolean failed() { return stage == Stage.FAILED || unavailable(); }
    boolean complete() { return stage == Stage.COMPLETE && ownedMenu == null; }
    boolean durableReceiptSettled() {
        return !unavailable() && ownedMenu == null && controller.currentJournal()
                .filter(value -> value.stage() == MaterialPurchaseJournal.Stage.CONFIRMED).isPresent();
    }
    String detail() { return unavailable() ? unavailable : detail; }
    String stage() { return stage.name(); }
    MaterialShopObservation observation() {
        return unavailable() ? MaterialShopObservation.unavailable(active(), detail())
                : MaterialShopObservation.capture(stage(), active(), product, detail(), controller.currentJournal());
    }
    String stateProblem() {
        if (unavailable()) { return unavailable; }
        return pending() ? "A material purchase receipt is pending; preserve its original inventory and build checkpoint." : "";
    }

    String resumeProblem(String selectedPlan) {
        if (unavailable()) { return unavailable; }
        if (!pending()) { return ""; }
        try {
            var original = controller.currentJournal().orElseThrow().before().context();
            RunContext current = captureContext();
            return original.planId().equals(selectedPlan)
                    && original.worldIdentityHash().equals(current.worldIdentityHash())
                    && original.dimension().equals(current.dimension()) ? ""
                    : "Resume the original world and plan to reconcile the pending material purchase.";
        } catch (RuntimeException missing) { return "Join the original world before reconciling the material purchase."; }
    }

    void begin(String selectedPlan, MaterialShopPolicy.Product selected, int stackBudget, int reservedSlots) {
        if (active()) { throw new IllegalStateException("Material shopping is already active"); }
        if (stackBudget < 1 || stackBudget > 9 || reservedSlots < 0 || reservedSlots > 36) {
            throw new IllegalArgumentException("Invalid bounded material purchase budget");
        }
        prepare(selectedPlan);
        if (pending()) { throw new IllegalStateException("Reconcile the existing purchase before starting a new batch"); }
        product = MaterialPurchaseFacts.Product.valueOf(selected.name());
        maximumStacks = stackBudget;
        reservedEmptySlots = reservedSlots;
        route = MaterialShopPolicy.Route.start(selected, ShopSettings.current().layout());
        stage = Stage.NAVIGATING;
        detail = "Opening the verified material shop route.";
        menus.sendShop();
    }

    void resumePending(String selectedPlan) {
        if (active()) { return; }
        String problem = resumeProblem(selectedPlan);
        if (!problem.isBlank()) { throw new IllegalStateException(problem); }
        prepare(selectedPlan);
        if (!pending()) { throw new IllegalStateException("There is no material purchase to reconcile"); }
        product = controller.currentJournal().orElseThrow().quote().product();
        stage = Stage.SETTLING;
        settleUntil = ticks;
        detail = "Reopening the shop to observe the pending purchase; no purchase will be repeated.";
    }

    private void prepare(String selectedPlan) {
        if (unavailable()) { throw new IllegalStateException(unavailable); }
        tickCancelledCleanup();
        DirtShopPurchase.Observation initial = menus.observe();
        if (!initial.connected() || !initial.cursorEmpty() || initial.menu() != null) {
            throw new IllegalStateException("Close the current container with an empty cursor before material shopping");
        }
        try { controller = new MaterialPurchaseController(store, this); }
        catch (IOException failure) { unavailable = "Material purchase journal is unavailable; preserve it for reconciliation."; throw new IllegalStateException(unavailable, failure); }
        planId = Objects.requireNonNull(selectedPlan, "selected plan");
        context = captureContext();
        world = client.world;
        connection = client.getNetworkHandler();
        ticks = 0;
        cleanupState = ShopMenuCleanupPolicy.State.initial();
        deadline = TIMEOUT_TICKS;
        stableMenu = null;
        ownedMenu = null;
        beforeReopen = null;
        dispatchBefore = null;
        dispatchMenu = null;
        dispatchHandler = null;
        dispatchQuote = null;
        dispatchCursorMark = null;
        DirtShopPurchase.Observation observed = menus.observe();
        if (!observed.connected() || !observed.cursorEmpty() || observed.menu() != null) {
            throw new IllegalStateException("Close the current container with an empty cursor before material shopping");
        }
    }

    void tick() {
        if (!active()) { return; }
        ticks++;
        try {
            if (!contextMatches()) { fail("The world or connection changed while shopping; no purchase was retried."); return; }
            DirtShopPurchase.Observation observed = menus.observe();
            if (!observed.connected()) { fail("Disconnected while shopping; preserve any pending purchase."); return; }
            if (!observed.cursorEmpty()) {
                if (!observed.ownedClickCursor()) { fail("An unrelated item is on the cursor; material shopping stopped."); return; }
                detail = "Waiting for the server to settle the owned shop click cursor.";
            } else if (stage == Stage.NAVIGATING) { navigate(observed); }
            else if (stage == Stage.SETTLING && ticks >= settleUntil) {
                if (ownedCursorAcknowledged(observed)) { controller.requestReceiptReopen(); }
                else { detail = "Waiting for the server to acknowledge an empty purchase cursor."; }
            }
            else if (stage == Stage.OPENING_RECEIPT) { observeReceipt(observed); }
            if (active() && ticks >= deadline) { fail("Timed out waiting for material shop evidence; no purchase was retried."); }
        } catch (IOException | RuntimeException failure) {
            fail("Material shopping stopped with an unresolved operation: " + failure.getClass().getSimpleName());
        }
    }

    private void navigate(DirtShopPurchase.Observation observed) throws IOException {
        DirtShopPurchase.Menu menu = observed.menu();
        if (menu == null) {
            stableMenu = null;
            if (ownedMenu != null) { fail("The material shop was closed before its next step."); }
            return;
        }
        if (!menu.equals(stableMenu)) { stableMenu = menu; stableSince = ticks; }
        if (ticks - stableSince < DWELL_TICKS) { return; }
        GenericContainerScreenHandler handler = handler();
        int capacity = handler == null ? 0 : MinecraftMaterialPurchaseCapture.compatibleStackCapacity(
                client, handler, product, reservedEmptySlots);
        MaterialShopPolicy.Menu policyMenu = convert(menu);
        if (MaterialShopPolicy.isExpectedMenu(route, policyMenu)) { ownedMenu = menu; }
        MaterialShopPolicy.Decision decision = MaterialShopPolicy.decide(route, policyMenu,
                Math.min(maximumStacks, capacity));
        switch (decision.status()) {
            case WAIT -> detail = decision.detail();
            case BLOCKED -> fail(decision.detail());
            case NAVIGATE -> {
                ownedMenu = menu;
                route = decision.nextRoute();
                stableMenu = null;
                deadline = ticks + TIMEOUT_TICKS;
                menus.click(menu, decision.slot());
            }
            case PURCHASE -> {
                Optional<MaterialPurchaseJournal.Observation> captured = capture(handler);
                if (captured.isEmpty()) { detail = "Waiting for a complete server inventory snapshot before purchase."; return; }
                dispatchBefore = captured.orElseThrow();
                dispatchMenu = menu;
                dispatchHandler = handler;
                dispatchQuote = new MaterialPurchaseFacts.Quote(product, decision.stacks(),
                        decision.quotedPrice().movePointRight(2).longValueExact(),
                        hash(menu.toString()), menu.title(), decision.slot());
                ownedMenu = menu;
                controller.begin(dispatchBefore, dispatchQuote);
                route = decision.nextRoute();
                stage = Stage.SETTLING;
                settleUntil = ticks + DWELL_TICKS;
                deadline = ticks + TIMEOUT_TICKS;
                detail = "One purchase sent; waiting for a reopened server inventory receipt.";
            }
        }
    }

    private void observeReceipt(DirtShopPurchase.Observation observed) throws IOException {
        DirtShopPurchase.Menu menu = observed.menu();
        if (menu == null || sameMenu(menu, beforeReopen)) { return; }
        if (!MaterialShopPolicy.canonicalText(menu.title())
                .equals(MaterialShopPolicy.canonicalText(ShopSettings.current().layout().mainTitle()))) {
            fail("An unexpected menu opened during purchase reconciliation."); return;
        }
        ownedMenu = menu;
        GenericContainerScreenHandler handler = handler();
        Optional<MaterialPurchaseJournal.Observation> captured = capture(handler);
        if (captured.isEmpty()) { return; }
        if (!MinecraftMaterialPurchaseCapture.matchesLive(client, handler, captured.orElseThrow().slots(), product)) { return; }
        MaterialPurchaseController.Result result = controller.reconcile(captured.orElseThrow());
        detail = result.detail();
        switch (result.status()) {
            case CONFIRMED -> {
                menus.close(ownedMenu);
                if (menus.observe().menu() != null) { fail("Purchase receipt confirmed, but its owned shop screen did not close."); return; }
                stage = Stage.COMPLETE;
                ownedMenu = null;
            }
            case REOPEN_REQUIRED -> {
                stage = Stage.SETTLING;
                settleUntil = ticks + DWELL_TICKS;
                deadline = ticks + TIMEOUT_TICKS;
            }
            case CONTEXT_MISMATCH, UNCERTAIN -> fail(result.detail());
            default -> { }
        }
    }

    private Optional<MaterialPurchaseJournal.Observation> capture(GenericContainerScreenHandler handler) {
        if (handler == null || !contextMatches()) { return Optional.empty(); }
        return ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler)
                .flatMap(packet -> MinecraftMaterialPurchaseCapture.capture(client, handler, packet, product)
                        .map(slots -> new MaterialPurchaseJournal.Observation(
                                new MaterialPurchaseJournal.Context(context.worldIdentityHash(), context.dimension(), planId),
                                packet.stamp(), slots)));
    }

    @Override
    public boolean matchesForDispatch(MaterialPurchaseJournal.Observation before, MaterialPurchaseFacts.Quote quote) {
        if (stage != Stage.NAVIGATING || !contextMatches() || !Objects.equals(before, dispatchBefore)
                || !Objects.equals(quote, dispatchQuote) || client.player == null
                || client.player.currentScreenHandler != dispatchHandler
                || !Objects.equals(menus.observe().menu(), dispatchMenu)) { return false; }
        return MinecraftMaterialPurchaseCapture.matchesLive(client, dispatchHandler, before.slots(), product)
                && MinecraftMaterialPurchaseCapture.compatibleStackCapacity(client, dispatchHandler, product,
                        reservedEmptySlots) >= quote.stacks();
    }

    @Override
    public void purchaseOnce(MaterialPurchaseJournal intent) throws IOException {
        if (!matchesForDispatch(intent.before(), intent.quote())) {
            throw new IOException("Inventory or menu changed after purchase intent; preserve the pending operation");
        }
        dispatchCursorMark = ServerShopCursorObserver.mark(client.world, client.getNetworkHandler(), dispatchHandler)
                .orElseThrow(() -> new IOException("Server cursor observation is unavailable before purchase"));
        menus.click(dispatchMenu, intent.quote().buttonSlot());
    }

    @Override
    public void requestReceiptReopen(MaterialPurchaseJournal.Context original) throws IOException {
        if (!active() || !contextMatches() || !original.equals(controller.currentJournal().orElseThrow().before().context())) {
            throw new IOException("Purchase receipt reopening lost its original context");
        }
        DirtShopPurchase.Observation observed = menus.observe();
        if (!observed.cursorEmpty() || !ownedCursorAcknowledged(observed)) {
            throw new IOException("The server has not acknowledged an empty shop cursor");
        }
        DirtShopPurchase.Menu current = observed.menu();
        if (current != null) {
            if (!sameMenu(current, ownedMenu)) { throw new IOException("The open menu is not owned by this purchase"); }
            menus.close(ownedMenu);
        }
        beforeReopen = current;
        ownedMenu = null;
        dispatchCursorMark = null;
        menus.sendShop();
        stage = Stage.OPENING_RECEIPT;
        deadline = ticks + TIMEOUT_TICKS;
        detail = "Waiting for the reopened shop's full server inventory packet.";
    }

    void cancel(String reason) {
        if (controller != null) { controller.cancel(); }
        if (active()) {
            stage = Stage.CANCELLED;
            detail = reason;
        }
        tickCancelledCleanup();
    }

    /** Cleanup only: never navigate, reopen a shop, or replay a purchase after cancellation. */
    void tickCancelledCleanup() {
        if (active() || ownedMenu == null) { return; }
        boolean originalContext = contextMatches();
        DirtShopPurchase.Observation observed;
        try { observed = menus.observe(); }
        catch (RuntimeException missing) { return; }
        boolean clear = observed.cursorEmpty() && (!pending() || ownedCursorAcknowledged(observed));
        var decision = ShopMenuCleanupPolicy.decide(cleanupState, new ShopMenuCleanupPolicy.Observation(
                true, true, observed.connected(), originalContext, sameMenu(observed.menu(), ownedMenu),
                clear, observed.ownedClickCursor()));
        cleanupState = decision.nextState();
        if (decision.action() == ShopMenuCleanupPolicy.Action.CLOSE) { menus.close(ownedMenu); }
        else if (decision.action() == ShopMenuCleanupPolicy.Action.RELEASE_OWNERSHIP) { ownedMenu = null; }
    }

    private void fail(String reason) {
        stage = Stage.FAILED;
        detail = reason;
        // A failed forced replacement may already have committed. Reload before evaluating any future action.
        reload();
        if (controller != null) { controller.cancel(); }
        tickCancelledCleanup();
    }

    private void reload() {
        try { controller = new MaterialPurchaseController(store, this); unavailable = ""; }
        catch (IOException | RuntimeException failure) {
            unavailable = "Material purchase journal is unavailable; preserve it and the original checkpoint.";
        }
    }

    private boolean contextMatches() {
        try {
            return client.player != null && client.world == world && client.getNetworkHandler() == connection
                    && connection != null && client.getNetworkHandler().isConnectionOpen()
                    && Objects.equals(context, captureContext());
        } catch (RuntimeException unavailable) { return false; }
    }

    private GenericContainerScreenHandler handler() {
        return client.player != null && client.player.currentScreenHandler.getClass() == GenericContainerScreenHandler.class
                ? (GenericContainerScreenHandler) client.player.currentScreenHandler : null;
    }

    private RunContext captureContext() {
        if (client.player == null) { throw new IllegalStateException("Player identity is unavailable"); }
        return MaterialPurchaseContext.bind(MinecraftRunContext.capture(client), client.player.getUuid(), journalPath);
    }

    private boolean ownedCursorAcknowledged(DirtShopPurchase.Observation observed) {
        if (!observed.cursorEmpty()) { return false; }
        if (observed.menu() == null) { return true; } // No menu will be closed; reopening only requests evidence.
        GenericContainerScreenHandler handler = handler();
        if (handler == null) { return false; }
        if (!sameMenu(observed.menu(), ownedMenu)) {
            // Only the original dispatched purchase may adopt one validated server menu transition.
            // Keep the durable intent pending: the explicit receipt reopen still settles it.
            if (!contextMatches() || dispatchCursorMark == null || dispatchBefore == null
                    || !sameMenu(ownedMenu, dispatchMenu) || controller == null) { return false; }
            var pending = controller.currentJournal().orElse(null);
            if (pending == null || !pending.before().equals(dispatchBefore)
                    || !pending.quote().equals(dispatchQuote)) { return false; }
            var received = capture(handler).orElse(null);
            if (!MaterialShopPolicy.isPostPurchaseReturn(convert(observed.menu()), pending, received,
                    ShopSettings.current().layout())
                    || !MinecraftMaterialPurchaseCapture.matchesLive(client, handler, received.slots(), product)) {
                return false;
            }
            ownedMenu = observed.menu();
            dispatchCursorMark = null;
            return true;
        }
        if (dispatchCursorMark != null) {
            return ServerShopCursorObserver.latestMatching(client.world, client.getNetworkHandler(), handler)
                    .filter(snapshot -> snapshot.stamp().isLaterThan(dispatchCursorMark)
                            && snapshot.cursorStack().isEmpty()).isPresent();
        }
        return ServerInventorySnapshotObserver.latestMatching(client.world, client.getNetworkHandler(), handler)
                .filter(snapshot -> snapshot.cursorStack().isEmpty()).isPresent();
    }

    private static MaterialShopPolicy.Menu convert(DirtShopPurchase.Menu menu) {
        return new MaterialShopPolicy.Menu(menu.identity(), menu.syncId(), menu.title(), menu.entries().stream()
                .map(entry -> new MaterialShopPolicy.Entry(entry.slot(), entry.itemId(), entry.label(),
                        entry.lore(), entry.stackCount())).toList(), true);
    }

    private static boolean sameMenu(DirtShopPurchase.Menu left, DirtShopPurchase.Menu right) {
        return left != null && right != null && left.identity() == right.identity() && left.syncId() == right.syncId();
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}
