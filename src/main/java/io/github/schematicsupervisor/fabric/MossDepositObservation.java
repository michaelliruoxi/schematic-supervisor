package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.Optional;

/** Bounded maintenance metadata; saved inventory contents and component fingerprints are never retained. */
record MossDepositObservation(boolean available, boolean active, String stage, String detail,
                              Boolean pending, Destination destination, Integer quantity,
                              int confirmedSessionItems, ServerInventorySnapshotStamp originalStamp,
                              ServerInventorySnapshotStamp receiptStamp,
                              ServerInventorySnapshotStamp reconciliationBarrier, boolean truncated,
                              String itemId, int confirmedSessionPickupItems) {
    record Destination(String id, int x, int y, int z) {
        Destination {
            Objects.requireNonNull(id, "id");
            if (id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Deposit destination id must be bounded");
            }
        }
    }

    MossDepositObservation {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(detail, "detail");
        String shortStage = bounded(stage, 32);
        String shortDetail = bounded(detail, 512);
        truncated |= !shortStage.equals(stage) || !shortDetail.equals(detail);
        stage = shortStage;
        detail = shortDetail;
        if (confirmedSessionItems < 0 || confirmedSessionPickupItems < confirmedSessionItems
                || itemId != null && !SurplusPickupPolicy.allowed(itemId)
                || quantity != null && (quantity < 1 || quantity > 64)) {
            throw new IllegalArgumentException("Invalid deposit observation counts");
        }
    }

    static MossDepositObservation capture(boolean active, boolean failed, String detail, int confirmedItems,
                                           Optional<MossDepositJournal> journal) {
        return capture(active, failed, detail, confirmedItems, confirmedItems, journal);
    }

    static MossDepositObservation capture(boolean active, boolean failed, String detail, int confirmedItems,
                                           int confirmedPickupItems, Optional<MossDepositJournal> journal) {
        Objects.requireNonNull(journal, "journal");
        MossDepositJournal saved = journal.orElse(null);
        boolean pending = saved != null && saved.stage() == MossDepositJournal.Stage.PENDING;
        String stage = failed ? "FAILED" : pending ? "PENDING" : active ? "ACTIVE"
                : saved == null ? "IDLE" : "CONFIRMED";
        // An old completed journal cannot identify the next unjournaled chest opening.
        if (active && !pending) { saved = null; }
        MossDepositJournal.Context destination = saved == null ? null : saved.before().context();
        return new MossDepositObservation(true, active, stage, detail, pending,
                destination == null ? null : new Destination(destination.depotId(), destination.depotX(),
                        destination.depotY(), destination.depotZ()),
                saved == null ? null : saved.plan().quantity(), confirmedItems,
                saved == null ? null : saved.before().stamp(),
                saved == null || saved.receipt() == null ? null : saved.receipt().stamp(),
                saved == null ? null : saved.reconciliationBarrier(), false,
                saved == null ? null : saved.before().slots().main().get(saved.plan().sourceMainIndex()).stack().itemId(),
                confirmedPickupItems);
    }

    static MossDepositObservation unavailable(boolean active, String detail, int confirmedItems) {
        return new MossDepositObservation(false, active, "UNAVAILABLE", detail, null, null, null,
                confirmedItems, null, null, null, false, null, confirmedItems);
    }

    private static String bounded(String text, int maximum) {
        String printable = text.replaceAll("\\p{Cntrl}", " ");
        int end = Math.min(maximum, printable.length());
        if (end < printable.length() && end > 0 && Character.isHighSurrogate(printable.charAt(end - 1))) { end--; }
        return printable.substring(0, end);
    }
}
