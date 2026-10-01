package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrdinaryPlacementCadenceTest {
    @Test
    void nextPlacementWaitsOnlyForTheIntervalFromTheLastDispatch() {
        var cadence = new InteractionDispatchCadence(4);
        var receipt = new FlightInteractionConfirmation(64, true, false, 100);
        cadence.dispatched();
        for (int tick = 1; tick <= 2; tick++) {
            cadence.tick();
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 63));
        }
        cadence.tick();
        // The acknowledged block and its one-item decrease settle on the third tick.
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 63));
        assertEquals(1, receipt.consumed());
        assertFalse(cadence.ready(), "A quick acknowledgement cannot shorten the placement interval");
        assertThrows(IllegalStateException.class, cadence::dispatched);
        cadence.tick();
        assertTrue(cadence.ready(), "No cooldown restarts after the acknowledgement or a reachable selection");
        cadence.dispatched();
        assertFalse(cadence.ready());
    }

    @Test
    void slowAcknowledgementLeavesNoExtraWaitAfterConfirmation() {
        var cadence = new InteractionDispatchCadence(4);
        var receipt = new FlightInteractionConfirmation(10, true, false, 100);
        cadence.dispatched();
        for (int tick = 1; tick <= 5; tick++) {
            cadence.tick();
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, false, 9));
        }
        assertTrue(cadence.ready());
        cadence.tick();
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 9));
        assertEquals(InteractionTickDispatch.Next.ADVANCE,
                InteractionTickDispatch.next(false, InteractionTickDispatch.Kind.CLICK, false));
        assertTrue(cadence.ready(), "The interval already elapsed while waiting for the server");
    }

    @Test
    void outstandingPlacementStillBlocksTheNextClickAfterTheInterval() {
        var cadence = new InteractionDispatchCadence(4);
        var receipt = new FlightInteractionConfirmation(10, true, false, 100);
        cadence.dispatched();
        for (int tick = 1; tick <= 8; tick++) {
            cadence.tick();
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 9));
            assertEquals(InteractionTickDispatch.Next.WAIT_RECEIPT,
                    InteractionTickDispatch.next(true, InteractionTickDispatch.Kind.CLICK, false));
        }
        assertTrue(cadence.ready());
    }
}
