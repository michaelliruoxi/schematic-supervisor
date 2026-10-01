package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PassiveMenuRestockTest {
    @Test
    void onlyActiveRestockingWithAnActualShortageCanRequestTheTransition() {
        for (SupervisorState state : SupervisorState.values()) {
            assertEquals(state == SupervisorState.RESTOCKING,
                    BackgroundBuildPolicy.requiresPassiveRestockTransition(state, true, true));
            assertFalse(BackgroundBuildPolicy.requiresPassiveRestockTransition(state, false, true));
            assertFalse(BackgroundBuildPolicy.requiresPassiveRestockTransition(state, true, false));
        }
        assertFalse(BackgroundBuildPolicy.requiresPassiveRestockTransition(null, true, true));
    }

    @Test
    void requiredRestockCanLeaveInventoryAndSettingsExactlyOnce() {
        for (var screen : List.of(BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS)) {
            var view = new AtomicReference<>(safe(screen));
            var closes = new AtomicInteger();
            assertEquals(BackgroundBuildPolicy.RestockTransition.CLEARED,
                    BackgroundBuildPolicy.leavePassiveScreenForRestock(view::get, () -> true, () -> {
                        closes.incrementAndGet();
                        view.set(safe(BackgroundBuildPolicy.Screen.GAMEPLAY));
                    }));
            assertEquals(1, closes.get());
            assertEquals(BackgroundBuildPolicy.RestockTransition.UNCHANGED,
                    BackgroundBuildPolicy.leavePassiveScreenForRestock(view::get, () -> true, closes::incrementAndGet));
            assertEquals(1, closes.get());
        }
    }

    @Test
    void chatContainersAndGameplayNeverTriggerAutomaticScreenClosure() {
        for (var screen : List.of(BackgroundBuildPolicy.Screen.CHAT,
                BackgroundBuildPolicy.Screen.OTHER, BackgroundBuildPolicy.Screen.GAMEPLAY)) {
            assertDoesNotClose(safe(screen), () -> true);
        }
    }

    @Test
    void operatorPauseStopAndIdleNeverCloseAPassiveMenu() {
        for (SupervisorState state : new SupervisorState[]{SupervisorState.PAUSED, SupervisorState.STOPPED, null}) {
            assertDoesNotClose(safe(BackgroundBuildPolicy.Screen.INVENTORY),
                    () -> BackgroundBuildPolicy.requiresPassiveRestockTransition(state, true, true));
        }
    }

    @Test
    void cursorCraftingInputsForeignHandlersAndHeldMouseButtonsRemainUntouched() {
        for (var screen : List.of(BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS)) {
            for (int unsafe = 0; unsafe < 5; unsafe++) {
                assertDoesNotClose(new BackgroundBuildPolicy.RestockScreen(screen, new Object(),
                        unsafe != 0, unsafe != 1, unsafe != 2, unsafe != 3, unsafe != 4), () -> true);
            }
        }
    }

    @Test
    void blockerNamesTheConditionHoldingAPageAndIsBlankExactlyWhenItCanBeLeft() {
        var reasons = List.of("the world or player changed", "a container or another screen is open",
                "an item is on the cursor", "the crafting grid holds items", "a mouse button is held down");
        for (var screen : List.of(BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS)) {
            assertEquals("", safe(screen).blocker());
            for (int unsafe = 0; unsafe < 5; unsafe++) {
                var held = new BackgroundBuildPolicy.RestockScreen(screen, new Object(),
                        unsafe != 0, unsafe != 1, unsafe != 2, unsafe != 3, unsafe != 4);
                assertEquals(reasons.get(unsafe), held.blocker());
                assertDoesNotClose(held, () -> true);
            }
        }
        assertEquals("chat is open", safe(BackgroundBuildPolicy.Screen.CHAT).blocker());
        assertEquals("a container or another screen is open", safe(BackgroundBuildPolicy.Screen.OTHER).blocker());
        assertEquals("", safe(BackgroundBuildPolicy.Screen.GAMEPLAY).blocker());
    }

    @Test
    void unsettledOperationsDoNotCloseAnOtherwiseSafeMenu() {
        assertDoesNotClose(safe(BackgroundBuildPolicy.Screen.SETTINGS), () -> false);
    }

    @Test
    void screenReplacedBetweenObservationsIsNotClosed() {
        var original = safe(BackgroundBuildPolicy.Screen.SETTINGS);
        var replacement = safe(BackgroundBuildPolicy.Screen.SETTINGS);
        var observations = new AtomicInteger();
        var closes = new AtomicInteger();
        assertEquals(BackgroundBuildPolicy.RestockTransition.INVALIDATED,
                BackgroundBuildPolicy.leavePassiveScreenForRestock(
                        () -> observations.getAndIncrement() == 0 ? original : replacement,
                        () -> true, closes::incrementAndGet));
        assertEquals(0, closes.get());
    }

    @Test
    void changedCursorOrContextImmediatelyBeforeCloseCancelsTheTransition() {
        for (int unsafe = 0; unsafe < 5; unsafe++) {
            var original = safe(BackgroundBuildPolicy.Screen.INVENTORY);
            var changed = new BackgroundBuildPolicy.RestockScreen(original.screen(), original.identity(),
                    unsafe != 0, unsafe != 1, unsafe != 2, unsafe != 3, unsafe != 4);
            var observations = new AtomicInteger();
            var closes = new AtomicInteger();
            assertEquals(BackgroundBuildPolicy.RestockTransition.INVALIDATED,
                    BackgroundBuildPolicy.leavePassiveScreenForRestock(
                            () -> observations.getAndIncrement() == 0 ? original : changed,
                            () -> true, closes::incrementAndGet));
            assertEquals(0, closes.get());
        }
    }

    @Test
    void pauseOrAReceiptAppearingDuringTheFinalCheckPreventsClosure() {
        var checks = new AtomicInteger();
        var closes = new AtomicInteger();
        var original = safe(BackgroundBuildPolicy.Screen.SETTINGS);
        assertEquals(BackgroundBuildPolicy.RestockTransition.INVALIDATED,
                BackgroundBuildPolicy.leavePassiveScreenForRestock(() -> original,
                        () -> checks.getAndIncrement() == 0, closes::incrementAndGet));
        assertEquals(0, closes.get());
    }

    @Test
    void reentrantScreenReplacementDuringCloseCannotStartRestocking() {
        for (var screen : List.of(BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT, BackgroundBuildPolicy.Screen.OTHER)) {
            var view = new AtomicReference<>(safe(BackgroundBuildPolicy.Screen.SETTINGS));
            assertEquals(BackgroundBuildPolicy.RestockTransition.INVALIDATED,
                    BackgroundBuildPolicy.leavePassiveScreenForRestock(view::get, () -> true,
                            () -> view.set(safe(screen))));
        }
    }

    @Test
    void gameplayAfterCloseStillRequiresTheOriginalContextAndEmptyInventoryState() {
        for (int unsafe = 0; unsafe < 5; unsafe++) {
            var view = new AtomicReference<>(safe(BackgroundBuildPolicy.Screen.INVENTORY));
            var after = new BackgroundBuildPolicy.RestockScreen(BackgroundBuildPolicy.Screen.GAMEPLAY, null,
                    unsafe != 0, unsafe != 1, unsafe != 2, unsafe != 3, unsafe != 4);
            assertEquals(BackgroundBuildPolicy.RestockTransition.INVALIDATED,
                    BackgroundBuildPolicy.leavePassiveScreenForRestock(view::get, () -> true, () -> view.set(after)));
        }
    }

    @Test
    void operatorCancellationOrNewReceiptDuringClosePreventsAnyRestockTransaction() {
        var view = new AtomicReference<>(safe(BackgroundBuildPolicy.Screen.INVENTORY));
        var ready = new AtomicBoolean(true);
        assertEquals(BackgroundBuildPolicy.RestockTransition.INVALIDATED,
                BackgroundBuildPolicy.leavePassiveScreenForRestock(view::get, ready::get, () -> {
                    view.set(safe(BackgroundBuildPolicy.Screen.GAMEPLAY));
                    ready.set(false);
                }));
    }

    @Test
    void queuedUnstartedScansDoNotDeadlockRequiredRestocking() {
        assertTrue(BackgroundBuildPolicy.depotSettledForPassiveRestock(
                depot("NONE", "IDLE", false, List.of("depot-to-scan"))));
    }

    @Test
    void activeDepotOperationsAndUnknownOrBlockedOwnershipPreventClosure() {
        assertFalse(BackgroundBuildPolicy.depotSettledForPassiveRestock(null));
        assertFalse(BackgroundBuildPolicy.depotSettledForPassiveRestock(DepotObservation.unavailable("unknown")));
        assertFalse(BackgroundBuildPolicy.depotSettledForPassiveRestock(depot("NONE", "IDLE", true, List.of())));
        for (String stage : List.of("NAVIGATING", "WAITING_FOR_SCREEN", "CANCELLING_OPEN", "CANCELLING_SCAN_OPEN",
                "TRANSFERRING", "MOSS_CHEST_READY", "CLEANING_UP")) {
            assertFalse(BackgroundBuildPolicy.depotSettledForPassiveRestock(depot("NONE", stage, false, List.of())));
            for (String operation : List.of("SCAN", "WITHDRAWAL", "MOSS_DEPOSIT")) {
                assertFalse(BackgroundBuildPolicy.depotSettledForPassiveRestock(depot(operation, stage, false, List.of())));
            }
        }
    }

    @Test
    void leftPageStaysAwayWhileOwnedContainerWorkRunsAndReturnsOnceItSettles() {
        for (var current : BackgroundBuildPolicy.Screen.values()) {
            assertEquals(BackgroundBuildPolicy.PageReturn.KEEP, pageReturn(true, current, false, false));
        }
        assertEquals(BackgroundBuildPolicy.PageReturn.RESTORE,
                pageReturn(true, BackgroundBuildPolicy.Screen.GAMEPLAY, true, false));
    }

    @Test
    void gameplayInputOrAnotherWorldMeansThePlayerTookOver() {
        for (boolean settled : new boolean[]{false, true}) {
            assertEquals(BackgroundBuildPolicy.PageReturn.FORGET,
                    pageReturn(true, BackgroundBuildPolicy.Screen.GAMEPLAY, settled, true));
            for (var current : BackgroundBuildPolicy.Screen.values()) {
                assertEquals(BackgroundBuildPolicy.PageReturn.FORGET, pageReturn(false, current, settled, false));
            }
        }
    }

    @Test
    void staleInputFlagsBehindAnOwnedContainerDoNotForgetThePage() {
        for (var current : List.of(BackgroundBuildPolicy.Screen.OTHER, BackgroundBuildPolicy.Screen.INVENTORY,
                BackgroundBuildPolicy.Screen.SETTINGS, BackgroundBuildPolicy.Screen.CHAT)) {
            assertEquals(BackgroundBuildPolicy.PageReturn.KEEP, pageReturn(true, current, false, true));
        }
    }

    @Test
    void anyScreenStillOpenAfterSettledWorkBelongsToThePlayer() {
        for (var current : List.of(BackgroundBuildPolicy.Screen.INVENTORY, BackgroundBuildPolicy.Screen.SETTINGS,
                BackgroundBuildPolicy.Screen.CHAT, BackgroundBuildPolicy.Screen.OTHER)) {
            assertEquals(BackgroundBuildPolicy.PageReturn.FORGET, pageReturn(true, current, true, false));
        }
    }

    @Test
    void returnWaitsForAForeignHandlerHeldCursorOrOverlay() {
        for (int unsafe = 0; unsafe < 3; unsafe++) {
            assertEquals(BackgroundBuildPolicy.PageReturn.KEEP, BackgroundBuildPolicy.returnLeftPage(true,
                    BackgroundBuildPolicy.Screen.GAMEPLAY, true, false, unsafe != 0, unsafe != 1, unsafe == 2));
        }
    }

    private static BackgroundBuildPolicy.PageReturn pageReturn(boolean contextMatches,
            BackgroundBuildPolicy.Screen current, boolean settled, boolean manualInput) {
        return BackgroundBuildPolicy.returnLeftPage(contextMatches, current, settled, manualInput,
                true, true, false);
    }

    private static BackgroundBuildPolicy.RestockScreen safe(BackgroundBuildPolicy.Screen screen) {
        return new BackgroundBuildPolicy.RestockScreen(screen,
                screen == BackgroundBuildPolicy.Screen.GAMEPLAY ? null : new Object(),
                true, true, true, true, true);
    }

    private static void assertDoesNotClose(BackgroundBuildPolicy.RestockScreen screen, BooleanSupplier ready) {
        var closes = new AtomicInteger();
        assertEquals(BackgroundBuildPolicy.RestockTransition.UNCHANGED,
                BackgroundBuildPolicy.leavePassiveScreenForRestock(() -> screen, ready, closes::incrementAndGet));
        assertEquals(0, closes.get());
    }

    private static DepotObservation depot(String operation, String stage, boolean blocked, List<String> queued) {
        return new DepotObservation(true, operation, stage, null, queued, false, blocked, "", 0, List.of(), false);
    }
}
