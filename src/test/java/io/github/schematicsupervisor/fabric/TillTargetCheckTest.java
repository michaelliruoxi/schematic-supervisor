package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.fabric.TillTargetCheck.Facts;
import io.github.schematicsupervisor.fabric.TillTargetCheck.Next;
import org.junit.jupiter.api.Test;

final class TillTargetCheckTest {
    private static final Facts PLAIN_DIRT = new Facts(true, false, false, false, false, true, true);

    @Test
    void plainDirtWithRoomAboveIsTilled() {
        assertEquals(Next.PROCEED, TillTargetCheck.decide(PLAIN_DIRT).next());
    }

    @Test
    void anUnreceivedTargetIsApproachedWithoutReadingItsBlocks() {
        assertEquals(Next.APPROACH_CHUNK, TillTargetCheck.decide(Facts.unreceived()).next());
    }

    @Test
    void aSettlingPredictionAtTheTargetOrAboveItWaits() {
        assertEquals(Next.WAIT_FOR_PREDICTION,
                TillTargetCheck.decide(new Facts(true, true, false, true, false, false, true)).next());
        assertEquals(Next.WAIT_FOR_PREDICTION,
                TillTargetCheck.decide(new Facts(true, false, true, false, false, true, false)).next());
    }

    @Test
    void soilAnotherPlayerAlreadyTilledIsConfirmedWithoutAClick() {
        assertEquals(Next.ALREADY_TILLED,
                TillTargetCheck.decide(new Facts(true, false, false, true, false, false, false)).next());
    }

    @Test
    void missingSoilHandsTheSliceBackForItsPlannedDirt() {
        assertEquals(Next.NEEDS_DIRT,
                TillTargetCheck.decide(new Facts(true, false, false, false, true, false, true)).next());
    }

    @Test
    void anotherBlockFailsBeforeAnOccupiedSpaceAboveIsReported() {
        TillTargetCheck.Decision stone = TillTargetCheck.decide(new Facts(true, false, false, false, false, false, false));
        assertEquals(Next.FAIL, stone.next());
        assertEquals(TillTargetCheck.NOT_DIRT, stone.failure());
        TillTargetCheck.Decision covered = TillTargetCheck.decide(new Facts(true, false, false, false, false, true, false));
        assertEquals(Next.FAIL, covered.next());
        assertEquals(TillTargetCheck.OCCUPIED_ABOVE, covered.failure());
    }
}
