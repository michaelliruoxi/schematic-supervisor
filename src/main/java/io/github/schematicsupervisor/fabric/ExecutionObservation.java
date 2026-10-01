package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.time.Instant;
import java.util.Objects;

/** Immutable execution facts; the last receipt is historical, never a fresh world observation. */
record ExecutionObservation(boolean available, String mode, String detail, Target target,
                            Receipt receipt, Receipt lastReceipt, Receipt lastFailure, Boolean ownedMining,
                            Boolean managerBreaking, String flightStatus, String error,
                            ExecutionObstruction lastObstruction, MossToolSelection lastMossToolSelection) {
    ExecutionObservation {
        mode = bounded(mode, 64);
        detail = bounded(detail, 512);
        flightStatus = bounded(flightStatus, 512);
        error = bounded(error, 256);
        if (!available) {
            target = null;
            receipt = null;
            ownedMining = null;
            managerBreaking = null;
            flightStatus = "";
        }
    }

    ExecutionObservation(boolean available, String mode, String detail, Target target,
                         Receipt receipt, Receipt lastReceipt, Receipt lastFailure, Boolean ownedMining,
                         Boolean managerBreaking, String flightStatus, String error,
                         ExecutionObstruction lastObstruction) {
        this(available, mode, detail, target, receipt, lastReceipt, lastFailure, ownedMining,
                managerBreaking, flightStatus, error, lastObstruction, null);
    }

    ExecutionObservation withMossToolSelection(MossToolSelection selection) {
        return new ExecutionObservation(available, mode, detail, target, receipt, lastReceipt, lastFailure,
                ownedMining, managerBreaking, flightStatus, error, lastObstruction, selection);
    }

    ExecutionObservation(boolean available, String mode, String detail, Target target,
                         Receipt receipt, Receipt lastReceipt, Receipt lastFailure, Boolean ownedMining,
                         Boolean managerBreaking, String flightStatus, String error) {
        this(available, mode, detail, target, receipt, lastReceipt, lastFailure, ownedMining,
                managerBreaking, flightStatus, error, null);
    }

    static ExecutionObservation unavailable(String mode, String detail, Receipt lastReceipt,
                                            Receipt lastFailure, String error) {
        return new ExecutionObservation(false, mode, detail, null, null, lastReceipt, lastFailure,
                null, null, "", error);
    }

    static ExecutionObservation unavailable(String mode, String detail, Receipt lastReceipt,
                                            Receipt lastFailure, String error,
                                            ExecutionObstruction lastObstruction) {
        return new ExecutionObservation(false, mode, detail, null, null, lastReceipt, lastFailure,
                null, null, "", error, lastObstruction);
    }

    record Target(BlockPosition position, String expectedBlock, String actualBlock, Boolean chunkReceived) {
        Target {
            Objects.requireNonNull(position, "position");
            expectedBlock = nullableBounded(expectedBlock, 128);
            actualBlock = Boolean.TRUE.equals(chunkReceived) ? nullableBounded(actualBlock, 128) : null;
        }
    }

    record Receipt(Instant capturedAt, String mode, Target target, Boolean predictionPending,
                   String material, long inventoryBefore, Long inventoryNow, String result,
                   int ageTicks, int budgetTicks, boolean worldMatches, Boolean ownedMining,
                   Boolean managerBreaking, BlockPosition breakingPosition, Float breakingProgress,
                   Float localBreakingDelta, Integer breakingCooldown, Integer selectedSlot, String selectedItem, String error) {
        Receipt {
            Objects.requireNonNull(capturedAt, "capturedAt");
            Objects.requireNonNull(target, "target");
            mode = bounded(mode, 64);
            material = bounded(material, 64);
            result = bounded(result, 32);
            selectedItem = nullableBounded(selectedItem, 128);
            error = bounded(error, 256);
            if (inventoryBefore < 0 || (inventoryNow != null && inventoryNow < 0)
                    || ageTicks < 0 || budgetTicks < 1
                    || (breakingProgress != null && (!Float.isFinite(breakingProgress) || breakingProgress < 0))
                    || (localBreakingDelta != null && (!Float.isFinite(localBreakingDelta) || localBreakingDelta < 0))
                    || (breakingCooldown != null && breakingCooldown < 0)
                    || (selectedSlot != null && (selectedSlot < 0 || selectedSlot > 8))) {
                throw new IllegalArgumentException("invalid execution receipt facts");
            }
            if (!worldMatches) {
                target = new Target(target.position(), target.expectedBlock(), null, null);
                predictionPending = null;
                inventoryNow = null;
                ownedMining = null;
                managerBreaking = null;
                breakingPosition = null;
                breakingProgress = null;
                localBreakingDelta = null;
                breakingCooldown = null;
                selectedSlot = null;
                selectedItem = null;
            } else if (!Boolean.TRUE.equals(target.chunkReceived())) {
                predictionPending = null;
                localBreakingDelta = null;
            }
        }
    }

    private static String nullableBounded(String value, int limit) {
        return value == null ? null : bounded(value, limit);
    }

    private static String bounded(String value, int limit) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return text.substring(0, Math.min(text.length(), limit));
    }
}
