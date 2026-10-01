package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** One durable material purchase at a time; pending intents are never replayed. */
public final class MaterialPurchaseController {
    public static final int MAXIMUM_REOPEN_REQUESTS = 2;
    public interface Store {
        Optional<MaterialPurchaseJournal> load() throws IOException;
        /** Return only after forced atomic replacement of the exact prior journal. */
        void replace(Optional<MaterialPurchaseJournal> expected, MaterialPurchaseJournal replacement) throws IOException;
    }
    public interface Port {
        /** Compare current raw menu, handler/context, main36, cursor and equipment to the accepted baseline. */
        boolean matchesForDispatch(MaterialPurchaseJournal.Observation before, MaterialPurchaseFacts.Quote quote);
        /** Recheck original ownership immediately before issuing one quantity click; never retry exceptions. */
        void purchaseOnce(MaterialPurchaseJournal intent) throws IOException;
        /** Navigation only: close only an owned clear-cursor menu and reopen the fixed shop landing page. */
        void requestReceiptReopen(MaterialPurchaseJournal.Context context) throws IOException;
    }
    public enum Status { NO_PENDING, WAITING, REOPEN_REQUIRED, CONTEXT_MISMATCH, UNCERTAIN, CONFIRMED }
    public record Result(Status status, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            MaterialPurchaseFacts.bounded(detail, 512, "detail");
        }
    }
    private final Store store;
    private final Port port;
    private Optional<MaterialPurchaseJournal> current;
    private volatile boolean cancelled;
    private int reopenRequests;

    public MaterialPurchaseController(Store store, Port port) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.port = Objects.requireNonNull(port, "port");
        current = Objects.requireNonNull(store.load(), "loaded journal");
    }
    public Optional<MaterialPurchaseJournal> currentJournal() { return current; }
    public boolean cancelled() { return cancelled; }
    public int reopenRequests() { return reopenRequests; }

    public MaterialPurchaseJournal begin(MaterialPurchaseJournal.Observation before,
                                          MaterialPurchaseFacts.Quote quote) throws IOException {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(quote, "quote");
        requireActive();
        if (current.filter(value -> value.stage() == MaterialPurchaseJournal.Stage.PENDING).isPresent()) {
            throw new IllegalStateException("an unresolved purchase must be reconciled, never replayed");
        }
        if (current.isPresent()) {
            var prior = current.orElseThrow().receipt();
            if (MaterialPurchaseJournal.sameSession(prior.stamp(), before.stamp())
                    && (before.stamp().fullSequence() < prior.stamp().fullSequence()
                    || before.stamp().openGeneration() < prior.stamp().openGeneration())) {
                throw new IllegalStateException("purchase baseline is older than its last receipt");
            }
        }
        MaterialPurchaseJournal intent = MaterialPurchaseJournal.pending(before, quote);
        if (!port.matchesForDispatch(before, quote)) {
            throw new IllegalStateException("the accepted purchase quote or inventory baseline changed");
        }
        persist(intent);
        reopenRequests = 0;
        requireActive();
        if (!port.matchesForDispatch(before, quote)) {
            throw new IllegalStateException("purchase eligibility changed after the durable intent; preserve uncertainty");
        }
        requireActive();
        port.purchaseOnce(intent);
        return intent;
    }

    public void requestReceiptReopen() throws IOException {
        requireActive();
        MaterialPurchaseJournal pending = current.filter(value -> value.stage() == MaterialPurchaseJournal.Stage.PENDING)
                .orElseThrow(() -> new IllegalStateException("there is no pending purchase receipt"));
        if (reopenRequests >= MAXIMUM_REOPEN_REQUESTS) {
            throw new IllegalStateException("purchase receipt reopen budget exhausted; preserve the intent");
        }
        reopenRequests++;
        port.requestReceiptReopen(pending.before().context());
    }
    public void cancel() { cancelled = true; }

    /** Settlement may continue after cancellation, but this method never sends input. */
    public Result reconcile(MaterialPurchaseJournal.Observation observed) throws IOException {
        Objects.requireNonNull(observed, "observed");
        if (current.isEmpty()) { return new Result(Status.NO_PENDING, "No purchase intent requires a receipt."); }
        MaterialPurchaseJournal journal = current.orElseThrow();
        if (journal.stage() == MaterialPurchaseJournal.Stage.CONFIRMED) {
            return new Result(Status.CONFIRMED, "The purchase stock receipt is already durable.");
        }
        if (!journal.before().context().equals(observed.context())) {
            return new Result(Status.CONTEXT_MISMATCH, "Preserve the purchase for its original world, profile, dimension and plan.");
        }
        if (!MaterialPurchaseJournal.sameSession(journal.receiptAnchor(), observed.stamp())) {
            if (journal.reconciliationBarrier() != null
                    && MaterialPurchaseJournal.sameSession(journal.before().stamp(), observed.stamp())
                    || journal.receiptAnchor().observerEpoch().equals(observed.stamp().observerEpoch())
                    && observed.stamp().contextGeneration() < journal.receiptAnchor().contextGeneration()) {
                return new Result(Status.WAITING, "An older observation session cannot replace the purchase receipt barrier.");
            }
            persist(journal.withBarrier(observed));
            return new Result(Status.REOPEN_REQUIRED, "New observation session recorded; reopen once more for a full server receipt.");
        }
        if (!observed.stamp().isLaterReopenThan(journal.receiptAnchor())) {
            return new Result(Status.WAITING, "Waiting for a later accepted shop opening and a full server inventory snapshot.");
        }
        Optional<String> problem = journal.receiptProblem(observed);
        if (problem.isPresent()) { return new Result(Status.UNCERTAIN, problem.orElseThrow()); }
        persist(journal.confirm(observed));
        return new Result(Status.CONFIRMED, "The exact plain-product stock increase is durably confirmed.");
    }
    private void persist(MaterialPurchaseJournal replacement) throws IOException {
        try {
            store.replace(current, replacement);
            current = Optional.of(replacement);
        } catch (IOException | RuntimeException uncertainWrite) {
            // Even a thrown replacement may have committed. Reload before permitting any further input.
            cancelled = true;
            throw uncertainWrite;
        }
    }
    private void requireActive() {
        if (cancelled) { throw new IllegalStateException("material purchasing is cancelled; observation only"); }
    }
}
