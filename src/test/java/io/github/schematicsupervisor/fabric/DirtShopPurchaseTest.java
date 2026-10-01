package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirtShopPurchaseTest {
    @Test
    void inventoryBeforeBlocksReturnSettlesWithoutReplayingNineStackPurchase() {
        FakePort port = new FakePort(29);
        port.dirtCount = 0;
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 2);
        purchase.start(17, 12);
        port.menu = mainMenu();
        tick(purchase, 3);
        port.menu = menu(2, "Blocks", entry(11, "Dirt", true));
        tick(purchase, 3);
        port.menu = menu(3, "Buying Dirt", entry(12, "Buy stacks", false));
        tick(purchase, 3);
        port.menu = menu(4, "Buying stacks of Dirt", entry(9, "Buy 9 Stacks", true),
                entry(8, "Buy 8 Stacks", true));
        tick(purchase, 3);
        assertEquals(9, purchase.snapshot().pendingStacks());
        port.received(9, 20);
        purchase.tick();
        assertEquals("SETTLING_RETURN", purchase.snapshot().state());
        assertEquals(9, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        int actions = port.actions.size();
        purchase.tick();
        port.menu = null;
        purchase.tick();
        port.menu = menu(5, "Blocks (Page 1/5)");
        tick(purchase, 3);
        assertEquals(actions, port.actions.size());
        port.menu = menu(5, "Blocks (Page 1/5)", entry(11, "Dirt", true));
        tick(purchase, 2);
        assertEquals(actions, port.actions.size());
        purchase.tick();
        assertEquals("click:5:11", port.actions.getLast());
        port.menu = menu(6, "Buying Dirt", entry(22, "Dirt", true), entry(35, "Buy stacks", false));
        tick(purchase, 3);
        port.menu = menu(7, "Buying stacks of Dirt", entry(9, "Buy 9 Stacks", true),
                entry(8, "Buy 8 Stacks", true));
        tick(purchase, 3);
        assertEquals(8, purchase.snapshot().pendingStacks());
        port.received(8, 12);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(17, purchase.snapshot().purchasedStacks());
        assertEquals(List.of("click:4:9", "click:7:8"), port.actions.stream()
                .filter(action -> action.endsWith(":9") || action.endsWith(":8")).toList());
    }

    @Test
    void postReceiptSettlementIsBoundedAndPreservesCreditOnUnsafeChanges() {
        for (String change : List.of("timeout", "inventory", "context", "cursor", "disconnect", "unknown",
                "ambiguous", "cancel")) {
            FakePort port = new FakePort(3);
            DirtShopPurchase purchase = navigate(port);
            port.received(2, 1);
            purchase.tick();
            assertEquals("SETTLING_RETURN", purchase.snapshot().state());
            int actions = port.actions.size();
            port.menu = menu(5, "Blocks (Page 1/5)");
            switch (change) {
                case "inventory" -> port.dirtCount++;
                case "context" -> port.context = "other-world";
                case "cursor" -> port.cursorEmpty = false;
                case "disconnect" -> port.connected = false;
                case "unknown" -> port.menu = menu(5, "Personal chest", entry(11, "Dirt", true));
                case "ambiguous" -> port.menu = menu(5, "Blocks", entry(11, "Dirt", true), entry(12, "Dirt", true));
                case "cancel" -> purchase.cancel("Operator paused.");
                case "timeout" -> { }
                default -> throw new AssertionError(change);
            }
            tick(purchase, 201);
            assertEquals(change.equals("cancel") ? "CANCELLED" : "FAILED", purchase.snapshot().state(), change);
            assertEquals(actions, port.actions.size(), change);
            assertEquals(2, purchase.snapshot().purchasedStacks(), change);
            assertEquals(0, purchase.snapshot().pendingStacks(), change);
            if (change.equals("timeout")) {
                assertTrue(purchase.snapshot().detail().contains("no purchase was retried"));
            }
        }
    }

    @Test
    void terminalReceiptCompletesWithoutAdoptingAnUnrecognizedReturnMenu() {
        for (String title : List.of("Personal chest", "Unrecognized shop")) {
            for (int remainingSlots : List.of(0, 1)) {
                FakePort port = new FakePort(3);
                port.dirtCount = 0;
                DirtShopPurchase purchase = openStacks(port, 2, 0);
                purchase.tick();
                assertEquals(2, purchase.snapshot().pendingStacks());
                port.received(2, remainingSlots);
                // A returned menu without a verified route must not be adopted or closed.
                DirtShopPurchase.Menu returned = menu(6, title);
                port.menu = returned;
                List<String> actions = List.copyOf(port.actions);
                purchase.tick();
                assertEquals("COMPLETE", purchase.snapshot().state());
                assertEquals(2, purchase.snapshot().purchasedStacks());
                assertEquals(0, purchase.snapshot().pendingStacks());
                assertEquals(128, port.dirtCount);
                assertEquals(returned, port.menu);
                tick(purchase, 20);
                assertEquals(actions, port.actions);
                assertEquals(2, purchase.snapshot().purchasedStacks());
            }
        }
    }

    @Test
    void terminalPurchaseWaitsForDelayedContentsThenClosesOnlyTheSameVerifiedReturn() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = openStacks(port, 2, 0);
        purchase.tick();
        port.received(2, 1);
        port.menu = menu(6, "Blocks (Page 1/5)");
        int actions = port.actions.size();
        purchase.tick();
        assertEquals("CLOSING_RETURN", purchase.snapshot().state());
        assertTrue(purchase.active());
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        tick(purchase, 3);
        assertEquals(actions, port.actions.size());
        port.menu = menu(6, "Blocks (Page 1/5)", entry(11, "Dirt", true));
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertFalse(purchase.active());
        assertEquals("close:6", port.actions.getLast());
        assertEquals(actions + 1, port.actions.size());
        tick(purchase, 20);
        assertEquals(actions + 1, port.actions.size());
        assertEquals(2, purchase.snapshot().purchasedStacks());
    }

    @Test
    void delayedReturnCannotCloseAReplacementMenuOrIgnoreInventoryContextAndCursorChanges() {
        for (String change : List.of("handler", "title", "inventory", "context", "cursor", "disconnect")) {
            FakePort port = new FakePort(3);
            DirtShopPurchase purchase = openStacks(port, 2, 0);
            purchase.tick();
            port.received(2, 1);
            port.menu = menu(6, "Blocks (Page 1/5)");
            purchase.tick();
            int actions = port.actions.size();
            port.menu = menu(6, "Blocks (Page 1/5)", entry(11, "Dirt", true));
            switch (change) {
                case "handler" -> port.menu = menu(7, "Blocks (Page 1/5)", entry(11, "Dirt", true));
                case "title" -> port.menu = menu(6, "Personal chest", entry(11, "Dirt", true));
                case "inventory" -> port.dirtCount++;
                case "context" -> port.context = "other-world";
                case "cursor" -> port.cursorEmpty = false;
                case "disconnect" -> port.connected = false;
                default -> throw new AssertionError(change);
            }
            purchase.tick();
            assertEquals("FAILED", purchase.snapshot().state(), change);
            assertEquals(actions, port.actions.size(), change);
            assertEquals(2, purchase.snapshot().purchasedStacks(), change);
            assertEquals(0, purchase.snapshot().pendingStacks(), change);
        }
    }

    @Test
    void delayedReturnTimesOutWithoutClosingAnAmbiguousMenuOrRetryingPurchase() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = openStacks(port, 2, 0);
        purchase.tick();
        port.received(2, 1);
        port.menu = menu(6, "Blocks (Page 1/5)", entry(11, "Dirt", true), entry(12, "Dirt", true));
        int actions = port.actions.size();
        purchase.tick();
        tick(purchase, 201);
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(actions, port.actions.size());
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
    }

    @Test
    void cancelDuringReturnWaitPreservesConfirmedPurchaseAndLeavesUnownedMenuUntouched() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = openStacks(port, 2, 0);
        purchase.tick();
        port.received(2, 1);
        port.menu = menu(6, "Blocks (Page 1/5)");
        purchase.tick();
        int actions = port.actions.size();
        purchase.cancel("Operator paused.");
        port.menu = menu(6, "Blocks (Page 1/5)", entry(11, "Dirt", true));
        tick(purchase, 20);
        assertEquals("CANCELLED", purchase.snapshot().state());
        assertEquals(actions, port.actions.size());
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
    }

    @Test
    void exhaustedCapacityCompletesExactReceiptBeforeReturnRouteValidation() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = navigate(port);
        assertEquals(3, purchase.observation().targetStacks());
        port.received(2, 0);
        port.menu = menu(6, "Personal chest");
        List<String> actions = List.copyOf(port.actions);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(actions, port.actions);
    }

    @Test
    void automaticLimitStopsAfterOneStackEvenWithThirtyThreeEmptySlots() {
        FakePort port = new FakePort(33);
        DirtShopPurchase purchase = openStacks(port, 1, 0);
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        assertEquals("click:4:1", port.actions.getLast());
        port.received(1, 32);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(1, purchase.snapshot().purchasedStacks());
        assertEquals("close:4", port.actions.getLast());
        int actions = port.actions.size();
        tick(purchase, 20);
        assertEquals(actions, port.actions.size());
    }

    @Test
    void reservedSpaceIsRecheckedBeforeEveryBatch() {
        FakePort port = new FakePort(4);
        DirtShopPurchase purchase = openStacks(port, 36, 1);
        assertEquals(3, purchase.observation().targetStacks());
        assertEquals(1, purchase.observation().reservedEmptySlots());
        purchase.tick();
        assertEquals(2, purchase.snapshot().pendingStacks());
        port.received(2, 2);
        purchase.tick();
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        port.received(1, 1);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(3, purchase.snapshot().purchasedStacks());
        assertEquals(1, port.freeSlots);
    }

    @Test
    void lostUnreservedSpacePreventsTheFirstPurchase() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = openStacks(port, 36, 2);
        port.freeSlots = 2;
        purchase.tick();
        assertEquals("CAPACITY_BLOCKED", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(List.of("shop", "click:1:10", "click:2:11", "click:3:12", "close:4"), port.actions);
    }

    @Test
    void satisfiedBudgetDoesNotOpenAMenuAndOccupiedReservationsDoNotBuy() {
        FakePort port = new FakePort(2);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start(0, 0);
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertTrue(port.actions.isEmpty());
        purchase.start(3, 2);
        assertEquals("CAPACITY_BLOCKED", purchase.snapshot().state());
        assertTrue(port.actions.isEmpty());
    }

    @Test
    void changingLimitsCannotRestartAnUnacknowledgedPurchase() {
        FakePort port = new FakePort(33);
        DirtShopPurchase purchase = openStacks(port, 1, 0);
        purchase.tick();
        int actions = port.actions.size();
        purchase.start(36, 0);
        assertEquals(1, purchase.snapshot().pendingStacks());
        assertEquals(actions, port.actions.size());
        port.received(1, 32);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(1, purchase.snapshot().purchasedStacks());
    }

    @Test
    void historyCapturesOnlyAcceptedRouteMenusAndDoesNotObserveOrActByItself() {
        FakePort port = new FakePort(2);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        port.menu = menu(1, "Personal chest", entry(1, "private name", false));
        assertTrue(purchase.observation().menuHistory().isEmpty());
        assertTrue(port.actions.isEmpty());
        port.menu = null;
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Blocks", new DirtShopPurchase.Entry(11, "Dirt", List.of(), true,
                "minecraft:dirt", 1), new DirtShopPurchase.Entry(12, "Glowstone", List.of(), false,
                "minecraft:glowstone", 64));
        for (int read = 0; read < 10; read++) { purchase.observation(); }
        assertEquals(1, purchase.observation().menuHistory().size());
        purchase.tick();
        List<ShopMenuHistory.Menu> history = purchase.observation().menuHistory();
        assertEquals(2, history.size());
        assertEquals(new ShopMenuHistory.Entry(12, "minecraft:glowstone", 64),
                history.getLast().entries().getLast());
        port.menu = menu(3, "Personal chest", entry(1, "private name", false));
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(history, purchase.observation().menuHistory());
        assertEquals(List.of("shop", "click:1:10", "click:2:11"), port.actions);
    }

    @Test
    void acceptedPurchaseReturnAllowsChangedMenuItemCountsAndKeepsExactCredit() {
        FakePort port = new FakePort(2);
        DirtShopPurchase purchase = openStacks(port);
        port.menu = menu(4, "Buy Stacks | Dirt", new DirtShopPurchase.Entry(1, "Buy 1 Stack", List.of(),
                false, "minecraft:lime_stained_glass_pane", 1));
        purchase.tick();
        port.received(1, 1);
        port.menu = menu(4, "Blocks", new DirtShopPurchase.Entry(11, "Dirt", List.of(), true,
                "minecraft:dirt", 32));
        purchase.tick();
        assertEquals(1, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals("OPENING_BLOCKS", purchase.snapshot().state());
        purchase.tick();
        assertEquals("click:4:11", port.actions.getLast());
        assertEquals(32, purchase.observation().menuHistory().getLast().entries().getFirst().stackCount());
        assertEquals(4, purchase.observation().menuHistory().size());
    }

    @Test
    void structuredObservationReadsLiveMenuWithoutNavigatingOrBuying() {
        FakePort port = new FakePort(4);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = mainMenu();
        for (int read = 0; read < 20; read++) {
            DirtShopObservation observation = purchase.observation();
            assertTrue(observation.available());
            assertEquals("OPENING_SHOP", observation.state());
            assertEquals("Blocks category", observation.expectedStep());
            assertEquals("Shop", observation.menuTitle());
            assertEquals(4, observation.emptySlots());
            assertTrue(observation.entries().getFirst().matchesCurrentStep());
        }
        assertEquals(List.of("shop"), port.actions);
        purchase.tick();
        assertEquals(List.of("shop", "click:1:10"), port.actions);
    }

    @Test
    void observationDoesNotSettleAnAcknowledgementOrPurchaseAnotherBatch() {
        FakePort port = new FakePort(2);
        DirtShopPurchase purchase = navigate(port);
        port.received(2, 0);
        int previousActions = port.actions.size();
        DirtShopObservation observed = purchase.observation();
        assertEquals("BUYING", observed.state());
        assertEquals(2, observed.pendingStacks());
        assertEquals(0, observed.purchasedStacks());
        assertEquals(previousActions, port.actions.size());
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals("No automatic shop step pending", purchase.observation().expectedStep());
    }

    @Test
    void timeoutDiagnosticsIdentifyActualListingWithoutExposingConnectionContext() {
        FakePort port = new FakePort(1);
        port.context = "private-connection-token";
        DirtShopPurchase purchase = new DirtShopPurchase(port, 2, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Blocks (Page 1/5)", new DirtShopPurchase.Entry(11, "Dirt x1",
                List.of("Buy price: $880"), false, "minecraft:grass_block"));
        purchase.tick();
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        String detail = purchase.snapshot().detail();
        assertTrue(detail.contains("stage=OPENING_BLOCKS"));
        assertTrue(detail.contains("Blocks (Page 1/5)"));
        assertTrue(detail.contains("minecraft:grass_block"));
        assertTrue(detail.contains("normalized='dirt x1'"));
        assertFalse(detail.contains(port.context));
        assertEquals(List.of("shop", "click:1:10"), port.actions);
        port.menu = menu(3, "Buying Dirt", entry(22, "Dirt", true));
        DirtShopObservation live = purchase.observation();
        assertEquals("Buying Dirt", live.menuTitle());
        assertEquals("FAILED", live.state());
        assertEquals("Dirt item labeled Dirt", live.expectedStep());
        assertEquals("minecraft:dirt", live.entries().getFirst().itemId());
        assertEquals(2, port.actions.size());
    }

    @Test
    void telemetryBoundsEntriesLabelsAndLoreAndMarksClipping() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        List<DirtShopPurchase.Entry> entries = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            entries.add(new DirtShopPurchase.Entry(index, "Dirt " + "x".repeat(200),
                    List.of("y".repeat(300), "z".repeat(300), "third line"), true));
        }
        port.menu = new DirtShopPurchase.Menu(1, 1, "Blocks", entries);
        DirtShopObservation observation = purchase.observation();
        assertEquals(54, observation.entries().size());
        assertTrue(observation.truncated());
        assertTrue(observation.entries().getFirst().label().length() <= 96);
        assertEquals(2, observation.entries().getFirst().lore().size());
        assertTrue(observation.entries().getFirst().lore().getFirst().length() <= 128);
        assertEquals(List.of(), port.actions);
    }

    @Test
    void disconnectedTelemetryIsUnavailableInsteadOfPretendingInventoryIsEmpty() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        port.connected = false;
        DirtShopObservation observed = purchase.observation();
        assertFalse(observed.available());
        assertEquals(null, observed.emptySlots());
        assertEquals(null, observed.dirtCount());
        assertFalse(observed.error().isBlank());
        assertEquals("IDLE", purchase.snapshot().state());
        assertEquals(List.of(), port.actions);
    }

    @Test
    void compatibilityFormattingStillRequiresTheGenuineExactlyNamedDirtItem() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Blocks", entry(11, "\u00a7fＤｉ\u200brｔ", false));
        purchase.tick();
        assertEquals(2, port.actions.size());
        port.menu = menu(2, "Blocks", entry(11, "Dirt x1", true));
        purchase.tick();
        assertEquals(2, port.actions.size());
        port.menu = menu(2, "Blocks", entry(11, "\u00a7fＤｉ\u200brｔ", true));
        purchase.tick();
        assertEquals("click:2:11", port.actions.getLast());
    }

    @Test
    void fullInventoryDoesNotClaimPurchaseCompletionOrOpenShop() {
        FakePort port = new FakePort(0);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        assertEquals("CAPACITY_BLOCKED", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertTrue(purchase.snapshot().detail().startsWith(DirtRestockCapacityPolicy.BLOCKER_PREFIX));
        assertEquals(List.of(), port.actions);
    }

    @Test
    void capacityLostBeforeFirstPurchaseClosesOwnedMenuWithoutClaimingCompletion() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = openStacks(port);
        port.freeSlots = 0;
        purchase.tick();
        assertEquals("CAPACITY_BLOCKED", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(List.of("shop", "click:1:10", "click:2:11", "click:3:12", "close:4"), port.actions);
        purchase.tick();
        assertEquals(5, port.actions.size());
    }

    @Test
    void capacityLostAfterAnExactReceiptKeepsOnlyTheAcknowledgedPurchaseCredit() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = navigate(port);
        port.received(2, 1);
        purchase.tick();
        port.freeSlots = 0;
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals("close:4", port.actions.getLast());
        assertEquals(6, port.actions.size());
    }

    @Test
    void oneEmptySlotBuysExactlyOneStackAndWaitsForInventory() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = navigate(port);
        assertEquals(List.of("shop", "click:1:10", "click:2:11", "click:3:12", "click:4:1"), port.actions);
        assertEquals(1, purchase.snapshot().pendingStacks());
        purchase.tick();
        purchase.tick();
        assertEquals(5, port.actions.size());
        assertTrue(purchase.active());
        port.received(1, 0);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(1, purchase.snapshot().purchasedStacks());
        assertEquals("close:4", port.actions.getLast());
    }

    @Test
    void thirtyFiveEmptySlotsUseLargestOptionsInAcknowledgedBatches() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = navigate(port);
        assertEquals(32, purchase.snapshot().pendingStacks());
        port.received(32, 3);
        purchase.tick();
        assertEquals(5, port.actions.size());
        purchase.tick();
        assertEquals(2, purchase.snapshot().pendingStacks());
        port.received(2, 1);
        purchase.tick();
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        port.received(1, 0);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(35, purchase.snapshot().purchasedStacks());
        assertEquals(List.of("click:4:32", "click:4:2", "click:4:1"), port.actions.subList(4, 7));
    }

    @Test
    void capacityIsReadAtThePurchaseAndAgainAfterEveryAcknowledgement() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = openStacks(port);
        port.freeSlots = 2;
        purchase.tick();
        assertEquals(2, purchase.snapshot().pendingStacks());
        port.received(2, 8);
        purchase.tick();
        port.freeSlots = 1;
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
    }

    @Test
    void originalSlotBudgetBoundsPurchasesEvenWhenInventoryKeepsGettingCleared() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = navigate(port);
        assertEquals(2, purchase.snapshot().pendingStacks());
        port.received(2, 35);
        purchase.tick();
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        port.received(1, 35);
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
        assertEquals(3, purchase.snapshot().purchasedStacks());
    }

    @Test
    void noFurtherClickWhenInventoryFillsDuringNavigation() {
        FakePort port = new FakePort(4);
        DirtShopPurchase purchase = openStacks(port);
        port.freeSlots = 0;
        purchase.tick();
        assertEquals("CAPACITY_BLOCKED", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertEquals(4, port.actions.stream().filter(action -> !action.startsWith("close:")).count());
    }

    @Test
    void unchangedServerMenuNeverReceivesRepeatedNavigationClick() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 4, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        purchase.tick();
        purchase.tick();
        purchase.tick();
        assertEquals(List.of("shop", "click:1:10"), port.actions);
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        purchase.tick();
        assertEquals(2, port.actions.size());
    }

    @Test
    void serverCanNavigateByUpdatingTheSameHandlerContents() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(1, "Blocks", entry(11, "Dirt", true));
        purchase.tick();
        port.menu = menu(1, "Dirt", entry(12, "Buy Stacks", false));
        purchase.tick();
        port.menu = menu(1, "Buy Stacks | Dirt", entry(13, "Buy 1 Stack", false));
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        assertEquals("click:1:13", port.actions.getLast());
    }

    @Test
    void partialInventoryUpdateWaitsUntilAllPurchasedDirtArrives() {
        FakePort port = new FakePort(2);
        DirtShopPurchase purchase = navigate(port);
        port.dirtCount += 64;
        port.freeSlots = 1;
        purchase.tick();
        assertEquals("BUYING", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertEquals(5, port.actions.size());
        port.dirtCount += 64;
        port.freeSlots = 0;
        purchase.tick();
        assertEquals("COMPLETE", purchase.snapshot().state());
    }

    @Test
    void unacknowledgedPurchaseTimesOutWithoutAnotherClickOrRestart() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = navigate(port);
        for (int tick = 0; tick < 201; tick++) {
            purchase.tick();
        }
        assertEquals("FAILED", purchase.snapshot().state());
        assertTrue(purchase.snapshot().detail().contains("no purchase was retried"));
        assertEquals(1, purchase.snapshot().pendingStacks());
        purchase.start();
        assertEquals(5, port.actions.size());
    }

    @Test
    void cancellationDuringNavigationNeverClosesAnUnobservedReplacement() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = openStacks(port);
        purchase.cancel("Stopped by user.");
        purchase.tick();
        assertEquals("CANCELLED", purchase.snapshot().state());
        assertEquals(4, port.actions.size());
        assertFalse(purchase.active());
    }

    @Test
    void cancellationDuringPurchaseWaitsForAcknowledgementWithoutMoreClicks() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = navigate(port);
        purchase.cancel("Stop.");
        assertEquals("CANCELLING", purchase.snapshot().state());
        purchase.tick();
        purchase.start();
        assertEquals(5, port.actions.size());
        port.received(32, 3);
        purchase.tick();
        assertEquals("CANCELLED", purchase.snapshot().state());
        assertEquals(32, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals("close:4", port.actions.getLast());
        purchase.tick();
        assertEquals(6, port.actions.size());
    }

    @Test
    void pendingMenuClosureWaitsForPurchaseBeforeReopeningToBuyTheRemainder() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = navigate(port);
        port.menu = null;
        purchase.tick();
        assertEquals("BUYING", purchase.snapshot().state());
        assertEquals(5, port.actions.size());
        port.received(32, 3);
        purchase.tick();
        assertEquals("OPENING_SHOP", purchase.snapshot().state());
        assertEquals("shop", port.actions.getLast());
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Blocks", entry(11, "Dirt", true));
        purchase.tick();
        port.menu = menu(3, "Dirt", entry(12, "Buy Stacks", false));
        purchase.tick();
        port.menu = menu(4, "Dirt", entry(3, "Buy 3 Stacks", false));
        purchase.tick();
        assertEquals(3, purchase.snapshot().pendingStacks());
    }

    @Test
    void cancellationDoesNotReopenAClosedMenuAfterAcknowledgement() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = navigate(port);
        purchase.cancel("Stop.");
        port.menu = null;
        port.received(32, 3);
        purchase.tick();
        assertEquals("CANCELLED", purchase.snapshot().state());
        assertEquals(5, port.actions.size());
    }

    @Test
    void wrongMenuCannotTriggerAnOtherwiseMatchingButton() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = menu(1, "Storage", entry(10, "Blocks", false));
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(List.of("shop"), port.actions);
    }

    @Test
    void ignoresSellBuyMoreFillInventoryAndUnlabeledDecorations() {
        FakePort port = new FakePort(35);
        DirtShopPurchase purchase = openStacks(port);
        port.menu = menu(4, "Buy Stacks | Dirt",
                entry(20, "Sell 35 Stacks", false),
                entry(21, "Buy More: 35 Stacks", false),
                entry(22, "Fill inventory: 35 Stacks", false),
                entry(23, "Dirt", true),
                new DirtShopPurchase.Entry(24, "Buy 32 Stacks", List.of("Right click to sell 32 stacks"), false),
                entry(25, "Buy 1 Stack", false));
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        assertEquals("click:4:25", port.actions.getLast());
    }

    @Test
    void readsExplicitQuantityFromLoreAndStripsColorFormatting() {
        FakePort port = new FakePort(8);
        DirtShopPurchase purchase = openStacks(port);
        port.menu = menu(4, "Dirt Shop", new DirtShopPurchase.Entry(10, "\u00a7aPurchase",
                List.of("\u00a7eAmount: 8 stacks", "Price: $120"), false));
        purchase.tick();
        assertEquals(8, purchase.snapshot().pendingStacks());
    }

    @Test
    void conflictingQuantitiesAndDuplicateLargestOptionsFailWithoutPurchase() {
        for (List<DirtShopPurchase.Entry> entries : List.of(
                List.of(new DirtShopPurchase.Entry(10, "Buy 1 Stack", List.of("Amount: 8 stacks"), false)),
                List.of(entry(10, "Buy 1 Stack", false), entry(11, "Buy 1 Stack", false)))) {
            FakePort port = new FakePort(8);
            DirtShopPurchase purchase = openStacks(port);
            port.menu = new DirtShopPurchase.Menu(4, 4, "Dirt Shop", entries);
            purchase.tick();
            assertEquals("FAILED", purchase.snapshot().state());
            assertEquals(4, port.actions.size());
        }
    }

    @Test
    void fractionalNegativeAndRangeQuantitiesAreNotTreatedAsWholeStacks() {
        FakePort port = new FakePort(8);
        DirtShopPurchase purchase = openStacks(port);
        port.menu = menu(4, "Dirt Shop", entry(10, "Buy 1.5 stacks", false),
                entry(11, "Buy -1 stacks", false), entry(12, "Buy 1-5 stacks", false));
        purchase.tick();
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(4, port.actions.size());
    }

    @Test
    void replacementDisconnectAndContextChangeStopPendingPurchase() {
        for (int scenario = 0; scenario < 3; scenario++) {
            FakePort port = new FakePort(1);
            DirtShopPurchase purchase = navigate(port);
            switch (scenario) {
                case 0 -> port.menu = menu(5, "Dirt Shop", entry(1, "Buy 1 Stack", false));
                case 1 -> port.connected = false;
                case 2 -> port.context = "different-world";
                default -> throw new AssertionError();
            }
            purchase.tick();
            assertEquals("FAILED", purchase.snapshot().state());
            purchase.tick();
            assertEquals(5, port.actions.size());
            assertEquals(1, purchase.snapshot().pendingStacks());
        }
    }

    @Test
    void occupiedCursorStopsBeforeOpeningOrClicking() {
        FakePort port = new FakePort(1);
        port.cursorEmpty = false;
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(List.of(), port.actions);
    }

    @Test
    void moreDirtThanExpectedStopsThePurchaseInsteadOfAssumingSuccess() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = navigate(port);
        port.dirtCount += 65;
        port.freeSlots = 0;
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().purchasedStacks());
    }

    @Test
    void aDifferentProductCannotTriggerBuyStacksOrAQuantityPurchase() {
        FakePort port = new FakePort(9);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Blocks", entry(11, "Dirt", true));
        purchase.tick();
        port.menu = menu(3, "Shop - Stone", entry(12, "Buy Stacks", false));
        purchase.tick();
        assertEquals(3, port.actions.size());
        port.menu = menu(3, "Shop - Dirt", entry(12, "Buy Stacks", false));
        purchase.tick();
        port.menu = menu(4, "Shop - Stone Stacks", entry(9, "Buy 9 Stacks", false));
        purchase.tick();
        assertEquals(4, port.actions.size());
        assertEquals(0, purchase.snapshot().pendingStacks());
    }

    @Test
    void genericPurchaseTitleRequiresTheSelectedDirtIcon() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = openStacks(port);
        port.menu = menu(4, "Buy Stacks", entry(0, "Dirt", true), entry(1, "Buy 1 Stack", false));
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
    }

    @Test
    void lateAcknowledgementSettlesUncertaintyWithoutAnotherAction() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = navigate(port);
        for (int tick = 0; tick < 201; tick++) {
            purchase.tick();
        }
        port.received(1, 0);
        port.context = "different-world";
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        port.context = "server-world";
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(1, purchase.snapshot().purchasedStacks());
        assertEquals(5, port.actions.size());
    }

    @Test
    void defaultNavigationWaitsTenStableTicksBetweenMenuClicks() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port);
        purchase.start();
        port.menu = mainMenu();
        tick(purchase, 10);
        assertEquals(List.of("shop"), port.actions);
        for (int index = 0; index < 20; index++) {
            purchase.observation();
        }
        assertEquals(List.of("shop"), port.actions);
        purchase.tick();
        assertEquals("click:1:10", port.actions.getLast());
        port.menu = menu(2, "Blocks (Page1/5)", entry(11, "Dirt", true));
        tick(purchase, 10);
        assertEquals(2, port.actions.size());
        purchase.tick();
        assertEquals("click:2:11", port.actions.getLast());
    }

    @Test
    void changingMenuContentsRestartsTheStableDwell() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port);
        purchase.start();
        port.menu = mainMenu();
        tick(purchase, 6);
        port.menu = menu(1, "Shop", entry(10, "Blocks", false), entry(20, "Decoration", false));
        tick(purchase, 10);
        assertEquals(List.of("shop"), port.actions);
        purchase.tick();
        assertEquals("click:1:10", port.actions.getLast());
    }

    @Test
    void everyPurchaseBatchWaitsForAStableMenuAndFreshCapacity() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 2);
        purchase.start();
        port.menu = mainMenu();
        tick(purchase, 3);
        port.menu = menu(2, "Blocks", entry(11, "Dirt", true));
        tick(purchase, 3);
        port.menu = menu(3, "Dirt", entry(12, "Buy Stacks", false));
        tick(purchase, 3);
        port.menu = menu(4, "Buy Stacks | Dirt", entry(1, "Buy 1 Stack", false),
                entry(2, "Buy 2 Stacks", false));
        tick(purchase, 2);
        assertEquals(4, port.actions.size());
        port.freeSlots = 1;
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
        port.received(1, 2);
        purchase.tick();
        tick(purchase, 2);
        assertEquals(5, port.actions.size());
        purchase.tick();
        assertEquals(2, purchase.snapshot().pendingStacks());
        assertEquals("click:4:2", port.actions.getLast());
    }

    @Test
    void ownedPredictedNavigationCursorWaitsWithoutRepeatingOrClosing() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.cursorEmpty = false;
        port.ownedClickCursor = true;
        port.menu = menu(1, "Shop");
        tick(purchase, 5);
        assertEquals("OPENING_BLOCKS", purchase.snapshot().state());
        assertTrue(purchase.snapshot().detail().contains("settle the shop click cursor"));
        assertEquals(2, port.actions.size());
        port.cursorEmpty = true;
        port.ownedClickCursor = false;
        port.menu = menu(2, "Blocks", entry(11, "Dirt", true));
        purchase.tick();
        assertEquals("click:2:11", port.actions.getLast());
    }

    @Test
    void sameHandlerPredictionCannotFailBeforeItsMenuResponseSettles() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start();
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Blocks (Page1/5)", entry(11, "Dirt", true));
        purchase.tick();
        port.menu = menu(2, "Blocks (Page1/5)", entry(20, "Decoration", false));
        purchase.tick();
        assertEquals("OPENING_DIRT", purchase.snapshot().state());
        assertEquals(3, port.actions.size());
        port.menu = menu(3, "Buying Dirt", entry(12, "Buy stacks", false));
        purchase.tick();
        assertEquals("click:3:12", port.actions.getLast());
    }

    @Test
    void predictedPurchaseCursorMustSettleBeforeAcknowledgementOrAnotherBatch() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = navigate(port);
        port.cursorEmpty = false;
        port.ownedClickCursor = true;
        port.received(2, 1);
        tick(purchase, 4);
        assertEquals(2, purchase.snapshot().pendingStacks());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertEquals(5, port.actions.size());
        port.cursorEmpty = true;
        port.ownedClickCursor = false;
        purchase.tick();
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(5, port.actions.size());
        purchase.tick();
        assertEquals("click:4:1", port.actions.getLast());
    }

    @Test
    void unreturnedPredictedCursorTimesOutWithoutPurchaseRetryOrFalseCredit() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = navigate(port);
        port.cursorEmpty = false;
        port.ownedClickCursor = true;
        port.received(1, 0);
        tick(purchase, 201);
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(1, purchase.snapshot().pendingStacks());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertTrue(purchase.snapshot().detail().contains("ownedClickCursor=true"));
        purchase.start();
        assertEquals(5, port.actions.size());
        port.cursorEmpty = true;
        port.ownedClickCursor = false;
        purchase.tick();
        assertEquals(0, purchase.snapshot().pendingStacks());
        assertEquals(1, purchase.snapshot().purchasedStacks());
        assertEquals(5, port.actions.size());
    }

    @Test
    void foreignCursorStillFailsAndCancellationDoesNotHandlePredictedItems() {
        FakePort port = new FakePort(1);
        DirtShopPurchase purchase = navigate(port);
        port.cursorEmpty = false;
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(5, port.actions.size());

        FakePort navigation = new FakePort(1);
        DirtShopPurchase cancelled = new DirtShopPurchase(navigation, 200, 0);
        cancelled.start();
        navigation.menu = mainMenu();
        cancelled.tick();
        navigation.cursorEmpty = false;
        navigation.ownedClickCursor = true;
        cancelled.cancel("Stop.");
        cancelled.tick();
        assertEquals("CANCELLED", cancelled.snapshot().state());
        assertEquals(List.of("shop", "click:1:10"), navigation.actions);
    }

    @Test
    void acknowledgedBlocksReturnsBuyNineNineNineAndSixWithinTheOriginalThirtyThreeSlotBudget() {
        FakePort port = new FakePort(33);
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 2);
        purchase.start();
        port.menu = mainMenu();
        tick(purchase, 3);
        port.menu = menu(2, "Blocks (Page1/5)", entry(11, "Dirt", true));
        tick(purchase, 3);
        port.menu = menu(3, "Buying Dirt", entry(13, "Dirt", true), entry(12, "Buy stacks", false));
        tick(purchase, 3);
        port.menu = nineStackMenu(4);
        tick(purchase, 3);
        int remaining = 33;
        int purchased = 0;
        for (int batch = 0; batch < 4; batch++) {
            int amount = batch == 3 ? 6 : 9;
            assertEquals(amount, purchase.snapshot().pendingStacks());
            remaining -= amount;
            purchased += amount;
            port.received(amount, remaining);
            int menuId = 5 + batch * 3;
            port.menu = menu(menuId, "Blocks (Page1/5)", entry(11, "Dirt", true));
            int previousActions = port.actions.size();
            purchase.tick();
            assertEquals(purchased, purchase.snapshot().purchasedStacks());
            assertEquals(0, purchase.snapshot().pendingStacks());
            if (remaining == 0) {
                assertEquals("COMPLETE", purchase.snapshot().state());
                assertEquals("close:" + menuId, port.actions.getLast());
                break;
            }
            assertEquals("OPENING_BLOCKS", purchase.snapshot().state());
            tick(purchase, 2);
            assertEquals(previousActions, port.actions.size());
            purchase.tick();
            assertEquals("click:" + menuId + ":11", port.actions.getLast());
            port.menu = menu(menuId + 1, "Buying Dirt", entry(13, "Dirt", true), entry(12, "Buy stacks", false));
            tick(purchase, 3);
            port.menu = nineStackMenu(menuId + 2);
            tick(purchase, 3);
        }
        assertEquals(List.of("click:4:9", "click:7:9", "click:10:9", "click:13:6"),
                port.actions.stream().filter(action -> action.endsWith(":9") || action.endsWith(":6")).toList());
        assertEquals(33, purchase.snapshot().purchasedStacks());
        assertEquals(5 + 33 * 64, port.dirtCount);
    }

    @Test
    void acknowledgedDirtProductReturnCanReenterTheExplicitBuyStacksRoute() {
        FakePort port = new FakePort(3);
        DirtShopPurchase purchase = navigate(port);
        port.received(2, 1);
        port.menu = menu(5, "Buying Dirt", entry(13, "Dirt", true), entry(12, "Buy stacks", false));
        purchase.tick();
        assertEquals("OPENING_DIRT", purchase.snapshot().state());
        assertEquals(2, purchase.snapshot().purchasedStacks());
        assertEquals(5, port.actions.size());
        purchase.tick();
        assertEquals("click:5:12", port.actions.getLast());
        port.menu = menu(6, "Buy Stacks | Dirt", entry(1, "Buy 1 Stack", false));
        purchase.tick();
        assertEquals(1, purchase.snapshot().pendingStacks());
    }

    @Test
    void exactReceiptDoesNotAuthorizeUnknownOrAmbiguousReturnMenus() {
        for (DirtShopPurchase.Menu returned : List.of(
                menu(5, "Storage", entry(11, "Dirt", true)),
                menu(5, "Blocks (Page1/5)", entry(11, "Dirt", false)),
                menu(5, "Blocks (Page1/5)", entry(11, "Dirt", true), entry(12, "Dirt", true)),
                menu(5, "Buying Dirt", entry(11, "Stone", false), entry(12, "Buy stacks", false)))) {
            FakePort port = new FakePort(3);
            DirtShopPurchase purchase = navigate(port);
            port.received(2, 1);
            port.menu = returned;
            purchase.tick();
            assertEquals("FAILED", purchase.snapshot().state());
            assertEquals(2, purchase.snapshot().purchasedStacks());
            assertEquals(0, purchase.snapshot().pendingStacks());
            assertEquals(5, port.actions.size());
        }
    }

    @Test
    void recognizedBlocksReturnBeforeInventoryReceiptRemainsAnUncertainPurchase() {
        FakePort port = new FakePort(33);
        DirtShopPurchase purchase = navigate(port);
        port.menu = menu(5, "Blocks (Page1/5)", entry(11, "Dirt", true));
        purchase.tick();
        assertEquals("FAILED", purchase.snapshot().state());
        assertEquals(32, purchase.snapshot().pendingStacks());
        assertEquals(0, purchase.snapshot().purchasedStacks());
        assertEquals(5, port.actions.size());
    }

    private static DirtShopPurchase.Menu nineStackMenu(long id) {
        return menu(id, "Buy Stacks | Dirt", entry(9, "Buy 9 Stacks", false),
                entry(6, "Buy 6 Stacks", false), entry(3, "Buy 3 Stacks", false));
    }

    private static void tick(DirtShopPurchase purchase, int count) {
        for (int index = 0; index < count; index++) {
            purchase.tick();
        }
    }

    private static DirtShopPurchase navigate(FakePort port) {
        DirtShopPurchase purchase = openStacks(port);
        purchase.tick();
        return purchase;
    }

    private static DirtShopPurchase openStacks(FakePort port) {
        return openStacks(port, 36, 0);
    }

    private static DirtShopPurchase openStacks(FakePort port, int maximumStacks, int reservedSlots) {
        DirtShopPurchase purchase = new DirtShopPurchase(port, 200, 0);
        purchase.start(maximumStacks, reservedSlots);
        port.menu = mainMenu();
        purchase.tick();
        port.menu = menu(2, "Shop | Blocks", entry(11, "\u00a7fDirt", true));
        purchase.tick();
        port.menu = menu(3, "Shop | Dirt", entry(12, "Buy Stacks", false));
        purchase.tick();
        port.menu = menu(4, "Buy Stacks | Dirt", entry(1, "Buy 1 Stack", false),
                entry(2, "Buy 2 Stacks", false), entry(8, "Buy 8 Stacks", false),
                entry(32, "Buy 32 Stacks", false), entry(36, "Buy 36 Stacks", false));
        return purchase;
    }

    private static DirtShopPurchase.Menu mainMenu() {
        return menu(1, "Shop", entry(10, "\u00a7aBlocks", false));
    }

    private static DirtShopPurchase.Menu menu(long id, String title, DirtShopPurchase.Entry... entries) {
        return new DirtShopPurchase.Menu(id, (int) id, title, List.of(entries));
    }

    private static DirtShopPurchase.Entry entry(int slot, String label, boolean dirt) {
        return new DirtShopPurchase.Entry(slot, label, List.of(), dirt);
    }

    private static final class FakePort implements DirtShopPurchase.Port {
        private final List<String> actions = new ArrayList<>();
        private boolean connected = true;
        private String context = "server-world";
        private int freeSlots;
        private int dirtCount = 5;
        private DirtShopPurchase.Menu menu;
        private boolean cursorEmpty = true;
        private boolean ownedClickCursor;

        private FakePort(int freeSlots) {
            this.freeSlots = freeSlots;
        }

        private void received(int stacks, int remainingSlots) {
            dirtCount += stacks * 64;
            freeSlots = remainingSlots;
        }

        @Override
        public DirtShopPurchase.Observation observe() {
            return new DirtShopPurchase.Observation(connected, context, freeSlots, dirtCount, menu,
                    cursorEmpty, ownedClickCursor);
        }

        @Override
        public void sendShop() {
            actions.add("shop");
        }

        @Override
        public void click(DirtShopPurchase.Menu clickedMenu, int slot) {
            assertEquals(menu, clickedMenu);
            actions.add("click:" + clickedMenu.identity() + ":" + slot);
        }

        @Override
        public void close(DirtShopPurchase.Menu closedMenu) {
            assertEquals(menu, closedMenu);
            actions.add("close:" + closedMenu.identity());
            menu = null;
        }
    }
}
