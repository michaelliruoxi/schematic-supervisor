package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.Optional;

/** Bounded purchase status; raw inventory contents, fingerprints and durable context stay private. */
record MaterialShopObservation(boolean available, boolean active, String stage, String detail,
                               Boolean pending, MaterialPurchaseFacts.Product product, Integer quantity,
                               Boolean confirmed, ServerInventorySnapshotStamp originalStamp,
                               ServerInventorySnapshotStamp receiptStamp,
                               ServerInventorySnapshotStamp reconciliationBarrier, boolean truncated) {
    MaterialShopObservation {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(detail, "detail");
        String shortStage = bounded(stage, 32);
        String shortDetail = bounded(detail, 512);
        truncated |= !shortStage.equals(stage) || !shortDetail.equals(detail);
        stage = shortStage;
        detail = shortDetail;
        if (quantity != null && (quantity < 64 || quantity > 576 || quantity % 64 != 0)
                || Boolean.TRUE.equals(pending) && Boolean.TRUE.equals(confirmed)
                || quantity != null && product == null) {
            throw new IllegalArgumentException("Invalid bounded material purchase facts");
        }
    }

    static MaterialShopObservation capture(String stage, boolean active,
                                            MaterialPurchaseFacts.Product selectedProduct, String detail,
                                            Optional<MaterialPurchaseJournal> journal) {
        Objects.requireNonNull(journal, "journal");
        MaterialPurchaseJournal saved = journal.orElse(null);
        boolean pending = saved != null && saved.stage() == MaterialPurchaseJournal.Stage.PENDING;
        // A previous receipt does not describe a new route, a failed unjournaled attempt, or a fresh session.
        if (!pending && !"COMPLETE".equals(stage)) { saved = null; }
        boolean confirmed = saved != null && saved.stage() == MaterialPurchaseJournal.Stage.CONFIRMED;
        return new MaterialShopObservation(true, active, stage, detail, pending,
                saved == null ? selectedProduct : saved.quote().product(),
                saved == null ? null : saved.quote().quantity(), confirmed,
                saved == null ? null : saved.before().stamp(),
                saved == null || saved.receipt() == null ? null : saved.receipt().stamp(),
                saved == null ? null : saved.reconciliationBarrier(), false);
    }

    static MaterialShopObservation unavailable(boolean active, String detail) {
        return new MaterialShopObservation(false, active, "UNAVAILABLE", detail, null, null, null,
                null, null, null, null, false);
    }

    private static String bounded(String text, int maximum) {
        String printable = text.replaceAll("\\p{Cntrl}", " ");
        int end = Math.min(maximum, printable.length());
        if (end < printable.length() && end > 0 && Character.isHighSurrogate(printable.charAt(end - 1))) { end--; }
        return printable.substring(0, end);
    }
}
