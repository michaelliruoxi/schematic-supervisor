package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Advances the server shop one acknowledged action at a time. */
final class DirtShopPurchase {
    private static final int STACK_SIZE = 64;
    private static final int DEFAULT_TIMEOUT_TICKS = 200;
    private static final int DEFAULT_MENU_DWELL_TICKS = 10;
    private static final Pattern STACK_AMOUNT = Pattern.compile("(?<![a-z0-9.,+\\-])([0-9]+)\\s*(?:x\\s*)?stacks?\\b");
    private static final Pattern UNSAFE_ACTION = Pattern.compile("\\b(?:sell|selling|more|fill|all|max|maximum)\\b");

    interface Port {
        Observation observe();

        void sendShop();

        void click(Menu menu, int slot);

        void close(Menu menu);
    }

    record Entry(int slot, String label, List<String> lore, boolean dirtItem, String itemId, int stackCount) {
        Entry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(itemId, "itemId");
            lore = List.copyOf(lore);
        }

        Entry(int slot, String label, List<String> lore, boolean dirtItem, String itemId) {
            this(slot, label, lore, dirtItem, itemId, 1);
        }

        Entry(int slot, String label, List<String> lore, boolean dirtItem) {
            this(slot, label, lore, dirtItem, dirtItem ? "minecraft:dirt" : "unspecified");
        }
    }

    record Menu(long identity, int syncId, String title, List<Entry> entries) {
        Menu {
            Objects.requireNonNull(title, "title");
            entries = List.copyOf(entries);
        }
    }

    record Observation(
            boolean connected,
            String context,
            int freeSlots,
            int dirtCount,
            Menu menu,
            boolean cursorEmpty,
            boolean ownedClickCursor
    ) {
        Observation(boolean connected, String context, int freeSlots, int dirtCount,
                    Menu menu, boolean cursorEmpty) {
            this(connected, context, freeSlots, dirtCount, menu, cursorEmpty, false);
        }
    }

    record Snapshot(String state, String detail, int purchasedStacks, int pendingStacks, boolean active) {
    }

    private enum State {
        IDLE, OPENING_SHOP, OPENING_BLOCKS, OPENING_DIRT, OPENING_STACKS, BUYING,
        SETTLING_RETURN, CLOSING_RETURN, COMPLETE, CAPACITY_BLOCKED, FAILED, CANCELLED, CANCELLING
    }

    private final Port port;
    private final ShopMenuHistory menuHistory = new ShopMenuHistory();
    private final int timeoutTicks;
    private final int menuDwellTicks;
    private State state = State.IDLE;
    private State lastActiveState = State.IDLE;
    private String detail = "Dirt purchase has not started.";
    private String context;
    private Menu previousMenu;
    private Menu ownedMenu;
    private Menu stableMenu;
    private Menu terminalReturnMenu;
    private int terminalDirtCount;
    private int confirmedDirtCount;
    private int stableMenuSince;
    private int nextClickAt;
    private int ticks;
    private int deadline;
    private int purchasedStacks;
    private int pendingStacks;
    private int dirtBeforePurchase;
    private int targetStacks;
    private int reservedEmptySlots;

    DirtShopPurchase(Port port) {
        this(port, DEFAULT_TIMEOUT_TICKS);
    }

    DirtShopPurchase(Port port, int timeoutTicks) {
        this(port, timeoutTicks, DEFAULT_MENU_DWELL_TICKS);
    }

    DirtShopPurchase(Port port, int timeoutTicks, int menuDwellTicks) {
        this.port = Objects.requireNonNull(port, "port");
        if (timeoutTicks < 1) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeoutTicks = timeoutTicks;
        if (menuDwellTicks < 0) {
            throw new IllegalArgumentException("menu dwell must not be negative");
        }
        this.menuDwellTicks = menuDwellTicks;
    }

    void start() {
        start(36, 0);
    }

    void start(int maximumStacks, int reserveEmptySlots) {
        if (maximumStacks < 0 || maximumStacks > 36 || reserveEmptySlots < 0 || reserveEmptySlots > 36) {
            throw new IllegalArgumentException("invalid Dirt purchase limits");
        }
        if (active() || pendingStacks > 0) {
            return;
        }
        ticks = 0;
        purchasedStacks = 0;
        pendingStacks = 0;
        previousMenu = null;
        ownedMenu = null;
        stableMenu = null;
        terminalReturnMenu = null;
        nextClickAt = menuDwellTicks;
        lastActiveState = State.IDLE;
        targetStacks = 0;
        reservedEmptySlots = reserveEmptySlots;
        try {
            Observation observation = port.observe();
            if (!valid(observation, false)) {
                return;
            }
            context = observation.context();
            targetStacks = Math.min(maximumStacks, usableEmptySlots(observation));
            if (maximumStacks == 0) {
                complete("The automatic Dirt purchase budget is already satisfied; no purchase was started.");
                return;
            }
            if (usableEmptySlots(observation) == 0) {
                stopForCapacity();
                return;
            }
            if (observation.menu() != null) {
                fail("Close the current container before buying dirt.");
                return;
            }
            waitFor(State.OPENING_SHOP, "Opening /shop.", null);
            port.sendShop();
            menuHistory.beginSession();
        } catch (RuntimeException failure) {
            fail("Could not start the dirt purchase: " + message(failure));
        }
    }

    void tick() {
        if (!active()) {
            settleLateAcknowledgement();
            return;
        }
        ticks++;
        try {
            Observation observation = port.observe();
            if (!valid(observation, true)) {
                return;
            }
            if (!observation.cursorEmpty()) {
                stableMenu = null;
                detail = "Waiting for the server to settle the shop click cursor; no further click was sent.";
            } else if (state == State.CLOSING_RETURN) {
                closeConfirmedReturn(observation);
            } else if (state == State.SETTLING_RETURN) {
                settleConfirmedReturn(observation);
            } else if (state == State.BUYING || state == State.CANCELLING) {
                acknowledgePurchase(observation);
            } else {
                advanceMenu(observation);
            }
            if (active() && ticks >= deadline) {
                fail((state == State.CLOSING_RETURN || state == State.SETTLING_RETURN
                        ? "The dirt purchase was confirmed, but its returned menu did not settle; no purchase was retried."
                        : (state == State.BUYING || state == State.CANCELLING)
                        ? "Timed out waiting for the purchased dirt in inventory; no purchase was retried."
                        : "Timed out waiting for the expected shop menu or labeled option.")
                        + timeoutObservation(observation));
            }
        } catch (RuntimeException failure) {
            fail("Dirt purchase stopped: " + message(failure));
        }
    }

    void cancel(String reason) {
        if (!active()) {
            return;
        }
        state = pendingStacks > 0 ? State.CANCELLING : State.CANCELLED;
        detail = reason == null || reason.isBlank() ? "Dirt purchase cancelled." : reason;
        if (pendingStacks == 0) {
            closeOwnedMenu();
        }
    }

    boolean active() {
        return switch (state) {
            case OPENING_SHOP, OPENING_BLOCKS, OPENING_DIRT, OPENING_STACKS, BUYING, CANCELLING,
                    SETTLING_RETURN, CLOSING_RETURN -> true;
            default -> false;
        };
    }

    Snapshot snapshot() {
        return new Snapshot(state.name(), detail, purchasedStacks, pendingStacks, active());
    }

    void clearMenuHistory() {
        menuHistory.clear();
    }

    DirtShopObservation observation() {
        State observedStep = state == State.FAILED ? lastActiveState : state;
        String expected = expectedStep(observedStep);
        try {
            Observation observed = port.observe();
            if (!observed.connected()) {
                return unavailableObservation(expected, "The client world is unavailable");
            }
            Menu menu = observed.menu();
            List<DirtShopObservation.Entry> entries = new ArrayList<>();
            boolean truncated = detail.length() > 256;
            int budget = Math.min(usableEmptySlots(observed), Math.max(0, targetStacks - purchasedStacks));
            if (menu != null) {
                truncated |= menu.entries().size() > 54 || menu.title().length() > 128;
                for (int index = 0; index < Math.min(54, menu.entries().size()); index++) {
                    Entry entry = menu.entries().get(index);
                    String normalized = normalize(entry.label());
                    int amount = stackAmount(entry);
                    List<String> lore = new ArrayList<>();
                    for (int line = 0; line < Math.min(2, entry.lore().size()); line++) {
                        String text = entry.lore().get(line);
                        truncated |= text.length() > 128;
                        lore.add(diagnosticText(text, 128));
                    }
                    truncated |= entry.label().length() > 96 || normalized.length() > 96
                            || entry.itemId().length() > 128 || entry.lore().size() > 2;
                    boolean matches = observedStep == State.OPENING_STACKS
                            ? amount > 0 && amount <= budget : matchesNavigation(entry, observedStep);
                    entries.add(new DirtShopObservation.Entry(entry.slot(), diagnosticText(entry.label(), 96),
                            diagnosticText(normalized, 96), diagnosticText(entry.itemId(), 128),
                            entry.dirtItem(), lore, amount, matches));
                }
            }
            return new DirtShopObservation(true, state.name(), active(), expected,
                    diagnosticText(detail, 256), purchasedStacks, pendingStacks,
                    observed.freeSlots(), observed.dirtCount(), observed.cursorEmpty(),
                    menu == null ? null : diagnosticText(menu.title(), 128),
                    menu == null ? null : menu.identity(), menu == null ? null : menu.syncId(),
                    menu != null && expectedTitle(menu.title(), observedStep),
                    menu != null && hasDirtEvidence(menu), entries, truncated, "", menuHistory.snapshot(),
                    state == State.IDLE ? null : targetStacks, state == State.IDLE ? null : reservedEmptySlots);
        } catch (RuntimeException failure) {
            return unavailableObservation(expected, "Shop observation unavailable: " + message(failure));
        }
    }

    private DirtShopObservation unavailableObservation(String expected, String error) {
        return new DirtShopObservation(false, state.name(), active(), expected,
                diagnosticText(detail, 256), purchasedStacks, pendingStacks,
                null, null, null, null, null, null, false, false, List.of(),
                detail.length() > 256 || error.length() > 256, diagnosticText(error, 256), menuHistory.snapshot(),
                state == State.IDLE ? null : targetStacks, state == State.IDLE ? null : reservedEmptySlots);
    }

    private static String expectedStep(State current) {
        return switch (current) {
            case OPENING_SHOP -> "Blocks category";
            case OPENING_BLOCKS -> "Dirt item labeled Dirt";
            case OPENING_DIRT -> "Buy Stacks option";
            case OPENING_STACKS -> "Explicit stack quantity fitting available inventory";
            case BUYING, CANCELLING -> "Exact purchased dirt inventory acknowledgement";
            case CLOSING_RETURN -> "Verified contents of the same returned Blocks menu";
            case SETTLING_RETURN -> "Stable verified menu after the confirmed Dirt purchase";
            default -> "No automatic shop step pending";
        };
    }

    private boolean valid(Observation observation, boolean requireContext) {
        if (!observation.connected() || observation.context() == null) {
            fail("Disconnected from the server; dirt purchase stopped.");
            return false;
        }
        if (requireContext && !observation.context().equals(context)) {
            fail("The server or world changed; dirt purchase stopped.");
            return false;
        }
        if (!observation.cursorEmpty() && (!requireContext || !observation.ownedClickCursor())) {
            fail("An item is on the cursor; dirt purchase stopped.");
            return false;
        }
        if (observation.freeSlots() < 0 || observation.freeSlots() > 36 || observation.dirtCount() < 0) {
            fail("The main inventory could not be read safely.");
            return false;
        }
        return true;
    }

    private void advanceMenu(Observation observation) {
        Menu menu = observation.menu();
        if (menu == null) {
            stableMenu = null;
            if (previousMenu != null) {
                fail("The shop was closed before the next step; dirt purchase stopped.");
            }
            return;
        }
        if (menu.equals(previousMenu)) {
            stableMenu = null;
            return;
        }
        if (!expectedTitle(menu.title())) {
            if (sameMenu(menu, previousMenu)) {
                // Vanilla may predict changed button contents before the server replaces this menu.
                stableMenu = null;
                return;
            }
            fail("Unexpected shop menu: " + menu.title() + ".");
            return;
        }
        ownedMenu = menu;
        if (menu.entries().isEmpty()) {
            stableMenu = null;
            return;
        }
        if ((state == State.OPENING_DIRT || state == State.OPENING_STACKS) && !hasDirtEvidence(menu)) {
            stableMenu = null;
            detail = "Waiting for the shop to identify Dirt before buying.";
            return;
        }
        if (!readyToClick(menu)) {
            return;
        }
        if (state == State.OPENING_STACKS) {
            buyStacks(observation);
            return;
        }
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : menu.entries()) {
            if (matchesNavigation(entry, state)) {
                matches.add(entry);
            }
        }
        if (matches.size() > 1) {
            fail("The shop has ambiguous duplicate options; dirt purchase stopped.");
            return;
        }
        if (matches.isEmpty()) {
            return;
        }
        menuHistory.record(menu);
        State next = switch (state) {
            case OPENING_SHOP -> State.OPENING_BLOCKS;
            case OPENING_BLOCKS -> State.OPENING_DIRT;
            case OPENING_DIRT -> State.OPENING_STACKS;
            default -> throw new IllegalStateException("Unexpected navigation state");
        };
        waitFor(next, "Waiting for " + matches.getFirst().label() + ".", menu);
        click(menu, matches.getFirst().slot());
    }

    private void settleLateAcknowledgement() {
        if (pendingStacks == 0) {
            return;
        }
        try {
            Observation observation = port.observe();
            if (observation.connected() && observation.cursorEmpty() && Objects.equals(context, observation.context())
                    && observation.dirtCount() - dirtBeforePurchase == pendingStacks * STACK_SIZE) {
                purchasedStacks += pendingStacks;
                pendingStacks = 0;
                detail += " The pending dirt purchase was later confirmed; no further purchase was made.";
            }
        } catch (RuntimeException ignored) {
            // An uncertain transaction remains unresolved until the original inventory can be observed.
        }
    }

    private static boolean hasDirtEvidence(Menu menu) {
        if (containsWord(normalize(menu.title()), "dirt")) {
            return true;
        }
        return menu.entries().stream().anyMatch(entry -> entry.dirtItem()
                && containsWord(normalize(entry.label()), "dirt"));
    }

    private boolean expectedTitle(String title) {
        return expectedTitle(title, state);
    }

    private static boolean expectedTitle(String title, State step) {
        String normalized = normalize(title);
        if (Pattern.compile("\\b(?:sell|selling)\\b").matcher(normalized).find()) {
            return false;
        }
        boolean shop = containsWord(normalized, "shop");
        return switch (step) {
            case OPENING_SHOP -> shop;
            case OPENING_BLOCKS -> shop || containsWord(normalized, "blocks");
            case OPENING_DIRT -> shop || containsWord(normalized, "dirt") || containsWord(normalized, "buy");
            case OPENING_STACKS, BUYING -> shop || containsWord(normalized, "dirt")
                    || containsWord(normalized, "stacks") || containsWord(normalized, "buy");
            default -> false;
        };
    }

    private static boolean matchesNavigation(Entry entry, State step) {
        String label = normalize(entry.label());
        return switch (step) {
            case OPENING_SHOP -> label.equals("blocks");
            case OPENING_BLOCKS -> entry.dirtItem() && label.equals("dirt");
            case OPENING_DIRT -> label.equals("buy stacks") || label.equals("buy stack");
            default -> false;
        };
    }

    private void buyStacks(Observation observation) {
        if (usableEmptySlots(observation) == 0) {
            stopForCapacity();
            return;
        }
        int remainingBudget = Math.min(usableEmptySlots(observation), targetStacks - purchasedStacks);
        Entry selected = null;
        int selectedAmount = 0;
        boolean duplicate = false;
        for (Entry entry : observation.menu().entries()) {
            int amount = stackAmount(entry);
            if (amount < 0) {
                fail("A stack option has conflicting quantities; dirt purchase stopped.");
                return;
            }
            if (amount > 0 && amount <= remainingBudget) {
                if (amount > selectedAmount) {
                    selected = entry;
                    selectedAmount = amount;
                    duplicate = false;
                } else if (amount == selectedAmount) {
                    duplicate = true;
                }
            }
        }
        if (duplicate) {
            fail("The shop has multiple options for the same stack quantity; dirt purchase stopped.");
            return;
        }
        if (selected == null) {
            // Menus may receive their slot contents after the screen opens.
            detail = "Waiting for an explicitly labeled stack amount within the " + remainingBudget + " stack budget.";
            return;
        }
        menuHistory.record(observation.menu());
        pendingStacks = selectedAmount;
        dirtBeforePurchase = observation.dirtCount();
        waitFor(State.BUYING, "Buying " + selectedAmount + " stacks of dirt.", observation.menu());
        click(observation.menu(), selected.slot());
    }

    private void acknowledgePurchase(Observation observation) {
        int received = observation.dirtCount() - dirtBeforePurchase;
        int expected = pendingStacks * STACK_SIZE;
        if (received > expected || received < 0) {
            fail("Inventory changed unexpectedly while buying dirt; no further purchase was made.");
            return;
        }
        if (received == expected) {
            purchasedStacks += pendingStacks;
            pendingStacks = 0;
            if (state == State.CANCELLING) {
                state = State.CANCELLED;
                detail = "Dirt purchase cancelled after the pending purchase was confirmed.";
                closeOwnedMenu();
                return;
            }
            Menu returnedMenu = observation.menu();
            State next = null;
            if (returnedMenu != null) {
                next = acknowledgedReturnStep(returnedMenu);
                if (next != null) {
                    ownedMenu = returnedMenu;
                }
            }
            if (usableEmptySlots(observation) == 0 || purchasedStacks >= targetStacks) {
                if (returnedMenu != null && next == null && blocksReturnTitle(returnedMenu)) {
                    terminalReturnMenu = returnedMenu;
                    terminalDirtCount = observation.dirtCount();
                    waitFor(State.CLOSING_RETURN,
                            "The dirt purchase is confirmed; waiting for the returned Blocks menu contents.", null);
                    return;
                }
                complete("Bought " + purchasedStacks + " stacks of dirt within the purchase and inventory limits.");
                return;
            }
            if (returnedMenu == null) {
                ownedMenu = null;
                waitFor(State.OPENING_SHOP, "Reopening /shop for the remaining stacks.", null);
                port.sendShop();
                return;
            }
            if (next == null) {
                fail("The shop changed after the purchase; bought " + purchasedStacks + " stacks and stopped.");
                return;
            }
            // Inventory has acknowledged this purchase. The menu must settle before another batch.
            if (next == State.OPENING_STACKS) {
                confirmedDirtCount = observation.dirtCount();
                waitFor(State.SETTLING_RETURN, "Inventory updated; waiting for the purchase return menu.", null);
                return;
            }
            waitFor(next, "Inventory updated; checking the remaining empty slots and shop route.", null);
            return;
        }
        if (state != State.CANCELLING && observation.menu() != null && !sameMenu(observation.menu(), ownedMenu)) {
            fail("The shop was closed or replaced before inventory confirmed the purchase; no purchase was retried.");
        }
    }

    private void settleConfirmedReturn(Observation observation) {
        if (observation.dirtCount() != confirmedDirtCount) {
            fail("Inventory changed after the confirmed dirt purchase; no purchase was retried.");
            return;
        }
        Menu menu = observation.menu();
        if (menu == null) {
            // A transient close may precede the server's return screen. Keep the original deadline.
            stableMenu = null;
            return;
        }
        State next = acknowledgedReturnStep(menu);
        if (next == null) {
            if (blocksReturnTitle(menu) && menu.entries().isEmpty()) {
                stableMenu = null;
                return;
            }
            fail("The shop changed after the purchase; bought " + purchasedStacks + " stacks and stopped.");
            return;
        }
        if (!readyToClick(menu)) {
            return;
        }
        ownedMenu = menu;
        // Keep classifying the return until it settles; never replay the acknowledged batch.
        state = next;
        lastActiveState = next;
        advanceMenu(observation);
    }

    private void closeConfirmedReturn(Observation observation) {
        Menu menu = observation.menu();
        if (observation.dirtCount() != terminalDirtCount) {
            fail("Inventory changed while closing the confirmed dirt purchase; no purchase was retried.");
            return;
        }
        if (menu == null) {
            ownedMenu = null;
            complete("Bought " + purchasedStacks + " stacks of dirt; the returned shop was already closed.");
            return;
        }
        if (!sameMenu(menu, terminalReturnMenu) || !menu.title().equals(terminalReturnMenu.title())) {
            fail("The confirmed purchase return menu was replaced; the new menu was left untouched.");
            return;
        }
        // The title permits waiting only. Contents and the original handler must both match before closing.
        if (acknowledgedReturnStep(menu) == null || !readyToClick(menu)) {
            return;
        }
        ownedMenu = menu;
        complete("Bought " + purchasedStacks + " stacks of dirt within the purchase and inventory limits.");
    }

    private static boolean blocksReturnTitle(Menu menu) {
        return normalize(menu.title()).matches("(?:shop )?blocks(?: page ?[1-9][0-9]* [1-9][0-9]*)?");
    }

    private State acknowledgedReturnStep(Menu menu) {
        String title = normalize(menu.title());
        if (blocksReturnTitle(menu)
                && navigationMatches(menu, State.OPENING_BLOCKS) == 1) {
            return State.OPENING_BLOCKS;
        }
        if (List.of("buying dirt", "buy dirt", "dirt", "shop dirt", "dirt shop").contains(title)
                && navigationMatches(menu, State.OPENING_DIRT) == 1
                && navigationMatches(menu, State.OPENING_BLOCKS) >= 1) {
            return State.OPENING_DIRT;
        }
        if (sameMenu(menu, ownedMenu) && expectedTitle(menu.title(), State.BUYING) && hasDirtEvidence(menu)) {
            return State.OPENING_STACKS;
        }
        return null;
    }

    private static long navigationMatches(Menu menu, State step) {
        return menu.entries().stream().filter(entry -> matchesNavigation(entry, step)).count();
    }

    private static int stackAmount(Entry entry) {
        String label = normalize(entry.label());
        if (UNSAFE_ACTION.matcher(label).find()) {
            return 0;
        }
        List<String> texts = new ArrayList<>();
        texts.add(stripFormatting(entry.label()).toLowerCase(Locale.ROOT));
        for (String line : entry.lore()) {
            String normalized = normalize(line);
            if (UNSAFE_ACTION.matcher(normalized).find()) {
                return 0;
            }
            texts.add(stripFormatting(line).toLowerCase(Locale.ROOT));
        }
        int result = 0;
        for (String text : texts) {
            Matcher matcher = STACK_AMOUNT.matcher(text);
            while (matcher.find()) {
                int amount;
                try {
                    amount = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException invalid) {
                    return -1;
                }
                if (amount < 1 || (result != 0 && result != amount)) {
                    return -1;
                }
                result = amount;
            }
        }
        return result;
    }

    private String timeoutObservation(Observation observation) {
        Menu menu = observation.menu();
        StringBuilder result = new StringBuilder(" Observed stage=").append(state)
                .append(", emptySlots=").append(observation.freeSlots())
                .append(", dirtItems=").append(observation.dirtCount())
                .append(", cursorEmpty=").append(observation.cursorEmpty())
                .append(", ownedClickCursor=").append(observation.ownedClickCursor());
        if (menu == null) { return result.append(", menu=none.").toString(); }
        result.append(", title='").append(diagnosticText(menu.title(), 80)).append('\'')
                .append(", menuId=").append(menu.identity()).append('/').append(menu.syncId())
                .append(", unchanged=").append(menu.equals(previousMenu))
                .append(", dirtEvidence=").append(hasDirtEvidence(menu)).append(", options=[");
        List<Entry> ordered = new ArrayList<>();
        for (Entry entry : menu.entries()) {
            if (diagnosticPriority(entry)) { ordered.add(entry); }
        }
        for (Entry entry : menu.entries()) {
            if (!diagnosticPriority(entry) && !normalize(entry.label()).isEmpty()) { ordered.add(entry); }
        }
        int count = Math.min(8, ordered.size());
        for (int index = 0; index < count; index++) {
            Entry entry = ordered.get(index);
            if (index > 0) { result.append("; "); }
            result.append(entry.slot()).append(':').append('\'')
                    .append(diagnosticText(entry.label(), 48)).append("' item=")
                    .append(diagnosticText(entry.itemId(), 48));
            if (diagnosticPriority(entry)) {
                result.append(" normalized='").append(diagnosticText(normalize(entry.label()), 48)).append('\'');
                for (int line = 0; line < Math.min(2, entry.lore().size()); line++) {
                    result.append(" lore='").append(diagnosticText(entry.lore().get(line), 72)).append('\'');
                }
            }
        }
        if (ordered.size() > count) { result.append("; +").append(ordered.size() - count).append(" options"); }
        return diagnosticText(result.append("].").toString(), 1800);
    }

    private static boolean diagnosticPriority(Entry entry) {
        String label = normalize(entry.label());
        return entry.dirtItem() || containsWord(label, "dirt") || containsWord(label, "blocks")
                || containsWord(label, "stacks") || stackAmount(entry) != 0;
    }

    private static String diagnosticText(String text, int maximumLength) {
        String compact = stripFormatting(text).replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (compact.length() <= maximumLength) { return compact; }
        int end = maximumLength - 3;
        if (end > 0 && Character.isHighSurrogate(compact.charAt(end - 1))) { end--; }
        return compact.substring(0, end) + "...";
    }

    private void waitFor(State next, String nextDetail, Menu previous) {
        state = next;
        lastActiveState = next;
        detail = nextDetail;
        previousMenu = previous;
        stableMenu = null;
        deadline = ticks + timeoutTicks;
    }

    private boolean readyToClick(Menu menu) {
        if (!menu.equals(stableMenu)) {
            stableMenu = menu;
            stableMenuSince = ticks;
        }
        if (ticks - stableMenuSince < menuDwellTicks || ticks < nextClickAt) {
            detail = "Waiting for the shop menu to settle before " + expectedStep(state) + ".";
            return false;
        }
        return true;
    }

    private void click(Menu menu, int slot) {
        nextClickAt = ticks + menuDwellTicks;
        port.click(menu, slot);
    }

    private void complete(String completionDetail) {
        state = State.COMPLETE;
        terminalReturnMenu = null;
        detail = completionDetail;
        closeOwnedMenu();
    }

    private void stopForCapacity() {
        if (purchasedStacks > 0) {
            complete("Available Dirt purchase space is filled. Bought " + purchasedStacks + " stacks of dirt.");
            return;
        }
        state = State.CAPACITY_BLOCKED;
        detail = DirtRestockCapacityPolicy.BLOCKER_PREFIX
                + " whole-stack dirt purchases require an unreserved empty main inventory slot; no dirt was bought.";
        closeOwnedMenu();
    }

    private int usableEmptySlots(Observation observation) {
        return Math.max(0, observation.freeSlots() - reservedEmptySlots);
    }

    private void fail(String failureDetail) {
        state = State.FAILED;
        detail = failureDetail;
        // Leave an uncertain purchase visible; never dismiss or retry it automatically.
    }

    private void closeOwnedMenu() {
        if (ownedMenu == null) {
            return;
        }
        try {
            Observation observation = port.observe();
            if (observation.connected() && Objects.equals(context, observation.context())
                    && observation.cursorEmpty() && sameMenu(observation.menu(), ownedMenu)) {
                port.close(observation.menu());
            }
        } catch (RuntimeException ignored) {
            // Completion/cancellation must remain terminal even if the connection vanishes.
        }
    }

    private static boolean sameMenu(Menu left, Menu right) {
        return left != null && right != null && left.identity() == right.identity() && left.syncId() == right.syncId();
    }

    private static String normalize(String text) {
        return Normalizer.normalize(stripFormatting(text), Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String stripFormatting(String text) {
        return text.replaceAll("(?i)\\u00a7[0-9a-fk-orx]", "");
    }

    private static boolean containsWord(String text, String word) {
        return (" " + text + " ").contains(" " + word + " ");
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
