package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TillDispatchCadenceTest {
    @Test
    void twoTickIntervalAllowsNextClickAfterEarlyServerConfirmation() {
        var cadence = new InteractionDispatchCadence(2);
        var receipt = new FlightInteractionConfirmation(1, false, false, 100);
        cadence.dispatched();
        cadence.tick();
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 1));
        assertFalse(cadence.ready(), "Early confirmation must not shorten the configured interval");
        assertThrows(IllegalStateException.class, cadence::dispatched);
        cadence.tick();
        assertTrue(cadence.ready());
        assertEquals(InteractionTickDispatch.Next.ADVANCE,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, false));
        cadence.dispatched();
        assertFalse(cadence.ready());
    }

    @Test
    void twoTickIntervalCannotBypassDelayedPredictionOrBlockingScreen() {
        var cadence = new InteractionDispatchCadence(2);
        var receipt = new FlightInteractionConfirmation(1, false, false, 100);
        cadence.dispatched();
        for (int tick = 0; tick < 5; tick++) {
            cadence.tick();
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 1));
            assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.CLICK, false));
            assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.CLICK, true));
        }
        assertTrue(cadence.ready());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 1));
        assertEquals(InteractionTickDispatch.Next.WAIT_SCREEN,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, true));
        assertEquals(InteractionTickDispatch.Next.ADVANCE,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, false));
        cadence.dispatched();
        assertFalse(cadence.ready());
    }

    @Test
    void shortAcknowledgementAndNavigationShareTheOriginalFourTickInterval() {
        var cadence = new InteractionDispatchCadence(4);
        var receipt = new FlightInteractionConfirmation(1, false, false, 100);
        cadence.dispatched();
        cadence.tick();
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 1));
        cadence.tick();
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 1));
        assertFalse(cadence.ready());
        cadence.tick();
        assertFalse(cadence.ready());
        cadence.tick();
        assertTrue(cadence.ready(), "No second interval starts after the acknowledgement or arrival");
        cadence.dispatched();
        assertFalse(cadence.ready());
    }

    @Test
    void anExpiredIntervalNeverAllowsAnotherClickWithAnOutstandingReceipt() {
        var cadence = new InteractionDispatchCadence(4);
        var receipt = new FlightInteractionConfirmation(1, false, false, 100);
        cadence.dispatched();
        for (int tick = 0; tick < 7; tick++) {
            cadence.tick();
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 1));
            assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.CLICK, false));
        }
        assertTrue(cadence.ready());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 1));
        assertEquals(InteractionTickDispatch.Next.ADVANCE,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, false));
        cadence.dispatched();
    }

    @Test
    void aLongMoveDoesNotAddAnotherCooldownAtArrival() {
        var cadence = new InteractionDispatchCadence(4);
        cadence.dispatched();
        for (int tick = 0; tick < 80; tick++) { cadence.tick(); }
        assertTrue(cadence.ready());
        cadence.dispatched();
        assertFalse(cadence.ready());
    }

    @Test
    void aBlockedScreenStillBlocksAfterTheDispatchIntervalExpires() {
        var cadence = new InteractionDispatchCadence(4);
        cadence.dispatched();
        for (int tick = 0; tick < 10; tick++) { cadence.tick(); }
        assertTrue(cadence.ready());
        assertEquals(InteractionTickDispatch.Next.WAIT_SCREEN,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, true));
    }

    @Test
    void evenARejectedAttemptCannotDispatchAgainInsideItsInterval() {
        var cadence = new InteractionDispatchCadence(1);
        cadence.dispatched();
        assertThrows(IllegalStateException.class, cadence::dispatched);
        cadence.tick();
        assertTrue(cadence.ready());
        cadence.dispatched();
        assertThrows(IllegalArgumentException.class, () -> new InteractionDispatchCadence(0));
    }
}
