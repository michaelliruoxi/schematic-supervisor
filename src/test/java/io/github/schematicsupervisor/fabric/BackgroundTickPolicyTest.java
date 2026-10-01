package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BackgroundTickPolicyTest {
    @Test void onlyActiveConnectedWorkMayBypassMenuPause() {
        for (String state : new String[] {"BUILDING", "RESTOCKING", "VERIFYING", "STUCK", "LOADING", "CHECKING"}) {
            assertTrue(BackgroundTickPolicy.keepsWorldTicking(state, false, true, true, false));
            assertFalse(BackgroundTickPolicy.keepsWorldTicking(state, false, false, true, false));
            assertFalse(BackgroundTickPolicy.keepsWorldTicking(state, false, true, false, false));
            assertFalse(BackgroundTickPolicy.keepsWorldTicking(state, false, true, true, true));
        }
    }

    @Test void pauseStopCompletionAndUnknownStatesRestoreVanillaPauseImmediately() {
        for (String state : new String[] {"PAUSED", "STOPPED", "DONE", "IDLE", "ERROR", "unknown", null}) {
            assertFalse(BackgroundTickPolicy.keepsWorldTicking(state, false, true, true, false));
        }
    }

    @Test void ownedDepotTakeoffOrApproachWorkKeepsTickingOutsideAnActiveBuild() {
        for (String state : new String[] {"PAUSED", "STOPPED", "DONE", "IDLE", "ERROR", "unknown", null}) {
            assertTrue(BackgroundTickPolicy.keepsWorldTicking(state, true, true, true, false));
            assertTrue(BackgroundTickPolicy.suppressesAutomaticFocusMenu(state, true, true, true, false,
                    false, false));
        }
    }

    @Test void ownedWorkStillYieldsToDisconnectionBlockingScreensAndOverlays() {
        assertFalse(BackgroundTickPolicy.keepsWorldTicking("IDLE", true, false, true, false));
        assertFalse(BackgroundTickPolicy.keepsWorldTicking("IDLE", true, true, false, false));
        assertFalse(BackgroundTickPolicy.keepsWorldTicking("IDLE", true, true, true, true));
    }

    @Test void activeBackgroundWorkHasEnoughFramesForUnbatchedTicks() {
        assertEquals(20, BackgroundTickPolicy.frameLimit(10, true));
        assertEquals(30, BackgroundTickPolicy.frameLimit(30, true));
        assertEquals(120, BackgroundTickPolicy.frameLimit(120, true));
        assertEquals(10, BackgroundTickPolicy.frameLimit(10, false));
    }

    @Test void backgroundBuildCanReachRestockingWithoutAnAutomaticMenuAppearing() {
        for (String state : new String[] {"BUILDING", "RESTOCKING", "VERIFYING", "STUCK", "LOADING", "CHECKING"}) {
            assertTrue(BackgroundTickPolicy.suppressesAutomaticFocusMenu(state, false, true, true, false,
                    false, false));
        }
    }

    @Test void explicitUserPagesAndFocusAreNeverOverriddenByTheAutomaticMenuPolicy() {
        for (boolean ownedWork : new boolean[] {false, true}) {
            assertFalse(BackgroundTickPolicy.suppressesAutomaticFocusMenu("BUILDING", ownedWork, true, true,
                    false, false, true));
            assertFalse(BackgroundTickPolicy.suppressesAutomaticFocusMenu("BUILDING", ownedWork, true, true,
                    false, true, false));
        }
    }

    @Test void pauseStopCompletionAndUnavailableWorldRestoreAutomaticFocusMenus() {
        for (String state : new String[] {"PAUSED", "STOPPED", "DONE", "IDLE", "ERROR", "unknown", null}) {
            assertFalse(BackgroundTickPolicy.suppressesAutomaticFocusMenu(state, false, true, true, false,
                    false, false));
        }
        assertFalse(BackgroundTickPolicy.suppressesAutomaticFocusMenu("BUILDING", false, false, true, false,
                false, false));
        assertFalse(BackgroundTickPolicy.suppressesAutomaticFocusMenu("BUILDING", false, true, false, false,
                false, false));
        assertFalse(BackgroundTickPolicy.suppressesAutomaticFocusMenu("BUILDING", false, true, true, true,
                false, false));
    }
}
