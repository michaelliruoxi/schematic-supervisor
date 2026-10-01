package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.fabric.ControlHttpServer.ControlAction;
import org.junit.jupiter.api.Test;

final class ActionAfterTakeoffTest {
    @Test
    void aCompletedTakeoffRunsTheHeldActionOnce() {
        ActionAfterTakeoff after = new ActionAfterTakeoff();
        after.hold(ControlAction.RESUME, "request-7");
        assertTrue(after.pending());

        ActionAfterTakeoff.Held held = after.takeAfter("COMPLETE").orElseThrow();
        assertEquals(ControlAction.RESUME, held.action());
        assertEquals("request-7", held.requestId());
        assertTrue(held.run());
        assertFalse(after.pending());
        assertTrue(after.takeAfter("COMPLETE").isEmpty());
    }

    @Test
    void aFailedOrCancelledTakeoffDropsTheHeldAction() {
        for (String state : new String[]{"FAILED", "CANCELLED", "IDLE"}) {
            ActionAfterTakeoff after = new ActionAfterTakeoff();
            after.hold(ControlAction.START, null);
            ActionAfterTakeoff.Held held = after.takeAfter(state).orElseThrow();
            assertFalse(held.run(), state);
            assertFalse(after.pending());
        }
    }

    @Test
    void clearingDropsTheHeldActionAndOnlyStartOrResumeMayWait() {
        ActionAfterTakeoff after = new ActionAfterTakeoff();
        after.hold(ControlAction.START, "a");
        after.clear();
        assertTrue(after.takeAfter("COMPLETE").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> after.hold(ControlAction.STOP, "b"));
        assertThrows(IllegalArgumentException.class, () -> after.hold(ControlAction.PAUSE, "b"));
    }
}
