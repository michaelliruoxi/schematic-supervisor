package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class FlightInteractionConfirmationTest {
    @Test void airborneSupportMiningDoesNotSpendTheServerAcknowledgementWindow() {
        var receipt = FlightInteractionConfirmation.forSupportRemoval(740, false, 100, 100);
        for (int tick = 0; tick < 80; tick++) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 740));
        }
        assertFalse(receipt.miningBudgetExpired());
        receipt.finishMining();
        assertEquals(180, receipt.budgetTicks());
        // Vanilla may predict Air, receive a Dirt correction, then receive the server's completed break.
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 740));
        for (int tick = 81; tick < 110; tick++) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 740));
        }
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 741));
        assertEquals(0, receipt.consumed());
    }

    @Test void supportMiningAndSettlementBothHaveFiniteDeadlines() {
        var receipt = FlightInteractionConfirmation.forSupportRemoval(740, false, 100, 100);
        for (int tick = 0; tick < 100; tick++) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 740));
        }
        assertTrue(receipt.miningBudgetExpired());
        receipt.finishMining();
        assertEquals(200, receipt.budgetTicks());
        for (int tick = 100; tick < 199; tick++) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 740));
        }
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, false, 740));
        assertEquals(0, receipt.consumed());
    }

    @Test void repeatedCancellationAndReadOnlyPollsCannotExtendSupportSettlement() {
        var receipt = FlightInteractionConfirmation.forSupportRemoval(740, false, 100, 100);
        receipt.observe(false, false, 740);
        receipt.finishMining();
        assertEquals(101, receipt.budgetTicks());
        for (int tick = 1; tick < 101; tick++) {
            for (int read = 0; read < 3; read++) {
                receipt.finishMining();
                receipt.observe(false, false, 740, false);
                assertEquals(101, receipt.budgetTicks());
            }
            receipt.observe(false, false, 740);
        }
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.result());
        assertEquals(101, receipt.ageTicks());
    }

    @Test void unresolvedSupportPredictionStillRequiresReconciliationAtTheNewDeadline() {
        var receipt = FlightInteractionConfirmation.forSupportRemoval(740, false, 2, 3);
        receipt.observe(false, false, 740);
        receipt.observe(false, false, 740);
        receipt.finishMining();
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 740));
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 740));
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, receipt.observe(true, true, 740));
        assertEquals(0, receipt.consumed());
    }

    @Test void missingMiningCompletionSignalCannotLeaveAReceiptOpenIndefinitely() {
        var receipt = FlightInteractionConfirmation.forSupportRemoval(740, false, 2, 3);
        for (int tick = 0; tick < 4; tick++) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 740));
        }
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, false, 740));
        receipt.finishMining();
        assertEquals(5, receipt.budgetTicks());
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, true, 740));
    }

    @Test void cancellationCannotExtendAnOrdinaryPlacementReceipt() {
        var receipt = new FlightInteractionConfirmation(740, true, false, 2);
        receipt.finishMining();
        assertFalse(receipt.miningBudgetExpired());
        assertEquals(2, receipt.budgetTicks());
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 739));
        receipt.finishMining();
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, receipt.observe(true, true, 739));
    }

    @Test void supportRemovalRejectsUnboundedOrOverflowingDeadlines() {
        assertThrows(IllegalArgumentException.class,
                () -> FlightInteractionConfirmation.forSupportRemoval(740, false, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> FlightInteractionConfirmation.forSupportRemoval(740, false, 100, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FlightInteractionConfirmation.forSupportRemoval(740, false, Integer.MAX_VALUE, 1));
    }

    @Test void diagnosticReadsNeverAdvanceTheReceiptDeadlineOrChangeItsResult() {
        var receipt = new FlightInteractionConfirmation(1152, false, false, 1);
        for (int index = 0; index < 100; index++) {
            assertEquals(1152, receipt.inventoryBefore());
            assertEquals(0, receipt.ageTicks());
            assertEquals(1, receipt.budgetTicks());
            assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.result());
        }
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, false, 1152));
        assertEquals(1, receipt.ageTicks());
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.result());
    }

    @Test void clearedMossMustBeAcknowledgedAndNeverConsumesReplacementMaterial() {
        var receipt = new FlightInteractionConfirmation(64, false, false, 4);
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 64));
        assertEquals(0, receipt.consumed());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 64));
        assertEquals(0, receipt.consumed());
    }

    @Test void rejectedMossClearDoesNotAuthorizePlacement() {
        var receipt = new FlightInteractionConfirmation(64, false, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, false, 64));
        assertEquals(0, receipt.consumed());
    }

    @Test void mossCorrectionAfterLocalCompletionKeepsOriginalReceiptUnconfirmed() {
        var receipt = new FlightInteractionConfirmation(1152, false, false, 3);
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 1152));
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(false, false, 1152));
        assertEquals(2, receipt.ageTicks());
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, false, 1152));
        assertEquals(0, receipt.consumed());
    }

    @Test void localPredictionAndInventoryLossCannotPrecreditPlacement() {
        var receipt = new FlightInteractionConfirmation(64, true, false, 3);
        assertEquals(FlightInteractionConfirmation.Result.WAITING, receipt.observe(true, true, 63));
        assertEquals(0, receipt.consumed());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, true, 63));
        assertEquals(1, receipt.consumed());
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, receipt.observe(false, false, 62));
        assertEquals(1, receipt.consumed());
    }

    @Test void timedOutPredictionRequiresReconciliationEvenIfLocallyCorrect() {
        var receipt = new FlightInteractionConfirmation(2, true, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, receipt.observe(true, true, 1));
        assertEquals(0, receipt.consumed());
    }

    @Test void onlyAcknowledgedUnspentFailedClickCanBeRetried() {
        var receipt = new FlightInteractionConfirmation(2, true, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.RETRYABLE, receipt.observe(false, false, 2));
        var spent = new FlightInteractionConfirmation(2, true, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, spent.observe(false, false, 1));
    }

    @Test void externalCorrectBlockWithoutOwnConsumptionIsNotCredited() {
        var receipt = new FlightInteractionConfirmation(2, true, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, receipt.observe(false, true, 2));
        assertEquals(0, receipt.consumed());
    }

    @Test void toolAndCreativeConfirmationsDoNotCountMaterialConsumption() {
        var till = new FlightInteractionConfirmation(1, false, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, till.observe(false, true, 1));
        assertEquals(0, till.consumed());
        var creative = new FlightInteractionConfirmation(64, true, true, 1);
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED, creative.observe(false, true, 64));
        assertEquals(0, creative.consumed());
    }

    @Test void UnexplainedMultipleItemsLostCannotBeChargedToOneClick() {
        var receipt = new FlightInteractionConfirmation(64, true, false, 1);
        assertEquals(FlightInteractionConfirmation.Result.UNCERTAIN, receipt.observe(false, true, 62));
        assertEquals(0, receipt.consumed());
    }

    @Test void readOnlySettlementPollsCannotShortenTheClientTickDeadline() {
        var receipt = new FlightInteractionConfirmation(2, true, false, 1);
        for (int poll = 0; poll < 100; poll++) {
            assertEquals(FlightInteractionConfirmation.Result.WAITING,
                    receipt.observe(true, true, 1, false));
        }
        assertEquals(FlightInteractionConfirmation.Result.CONFIRMED,
                receipt.observe(false, true, 1, false));
        assertEquals(1, receipt.consumed());
    }
}
