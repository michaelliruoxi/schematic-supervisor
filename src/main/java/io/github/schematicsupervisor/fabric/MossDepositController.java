package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Staged fixed-pickup maintenance; never contributes to planned-material ledgers. */
public final class MossDepositController {
    public static final int MAXIMUM_REOPEN_REQUESTS = 2;

    public interface Store {
        Optional<MossDepositJournal> load() throws IOException;

        /** Replace only the exact prior journal; return only after forcing and atomically replacing it. */
        void replace(Optional<MossDepositJournal> expected, MossDepositJournal replacement) throws IOException;
    }

    public interface Port {
        /** Read-only final eligibility check; must compare accepted handler, physical context and slot facts. */
        boolean matchesForDispatch(MossDepositJournal.Observation before, MossDepositFacts.Plan plan);

        /**
         * Check the exact original handler/context/slots again, then issue exactly one QUICK_MOVE.
         * Any exception is an unknown outcome. Neither this method nor the caller may retry the click.
         */
        void quickMoveOnce(MossDepositJournal intent) throws IOException;

        /** Request the adapter's bounded close/reopen workflow for the same approved physical chest. */
        void requestReceiptReopen(MossDepositJournal.Context context) throws IOException;
    }

    public enum Status { NO_PENDING, WAITING, REOPEN_REQUIRED, CONTEXT_MISMATCH, UNCERTAIN, CONFIRMED }

    public record Result(Status status, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (detail.length() > 512) { throw new IllegalArgumentException("deposit detail is too long"); }
        }
    }

    private final Store store;
    private final Port port;
    private Optional<MossDepositJournal> current;
    private volatile boolean cancelled;
    private int reopenRequests;

    public MossDepositController(Store store, Port port) throws IOException {
        this.store = Objects.requireNonNull(store, "store");
        this.port = Objects.requireNonNull(port, "port");
        current = Objects.requireNonNull(store.load(), "loaded journal");
    }

    public Optional<MossDepositJournal> currentJournal() { return current; }
    public boolean cancelled() { return cancelled; }
    public int reopenRequests() { return reopenRequests; }

    public MossDepositJournal begin(MossDepositJournal.Observation before, int sourceMainIndex) throws IOException {
        Objects.requireNonNull(before, "before");
        requireActive();
        if (current.filter(value -> value.stage() == MossDepositJournal.Stage.PENDING).isPresent()) {
            throw new IllegalStateException("an unresolved deposit intent must be reconciled, never replayed");
        }
        if (current.isPresent()) {
            MossDepositJournal.Observation prior = current.orElseThrow().receipt();
            if (MossDepositJournal.sameSession(prior.stamp(), before.stamp())
                    && (before.stamp().fullSequence() < prior.stamp().fullSequence()
                    || before.stamp().openGeneration() < prior.stamp().openGeneration())) {
                throw new IllegalStateException("a new deposit cannot use an observation older than its last receipt");
            }
        }
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before.slots(), sourceMainIndex)
                .orElseThrow(() -> new IllegalStateException("no observed room for that plain-pickup source quantity"));
        if (!port.matchesForDispatch(before, plan)) {
            throw new IllegalStateException("accepted depot or inventory changed before deposit intent");
        }
        MossDepositJournal intent = MossDepositJournal.pending(before, plan);
        persist(intent);
        reopenRequests = 0;
        requireActive();
        // No path below can dispatch this intent again, including exceptions and process restarts.
        port.quickMoveOnce(intent);
        return intent;
    }

    public void requestReceiptReopen() throws IOException {
        requireActive();
        MossDepositJournal pending = current.filter(value -> value.stage() == MossDepositJournal.Stage.PENDING)
                .orElseThrow(() -> new IllegalStateException("there is no pending deposit receipt"));
        if (reopenRequests >= MAXIMUM_REOPEN_REQUESTS) {
            throw new IllegalStateException("receipt reopen budget exhausted; preserve the unresolved intent");
        }
        // A failed request may already have started; count it before invoking the adapter.
        reopenRequests++;
        port.requestReceiptReopen(pending.before().context());
    }

    /** Manual pause/cancellation releases no click permission; subsequent reconciliation is read-only. */
    public void cancel() { cancelled = true; }

    public Result reconcile(MossDepositJournal.Observation observed) throws IOException {
        Objects.requireNonNull(observed, "observed");
        if (current.isEmpty()) { return new Result(Status.NO_PENDING, "No deposit intent requires a receipt."); }
        MossDepositJournal journal = current.orElseThrow();
        if (journal.stage() == MossDepositJournal.Stage.CONFIRMED) {
            return new Result(Status.CONFIRMED, "The deposit receipt is already durable.");
        }
        if (!journal.before().context().equals(observed.context())) {
            return new Result(Status.CONTEXT_MISMATCH, "Preserve the intent for its original world, plan, and physical depot.");
        }
        if (!MossDepositJournal.sameSession(journal.receiptAnchor(), observed.stamp())) {
            if (journal.reconciliationBarrier() != null
                    && MossDepositJournal.sameSession(journal.before().stamp(), observed.stamp())
                    || journal.receiptAnchor().observerEpoch().equals(observed.stamp().observerEpoch())
                    && observed.stamp().contextGeneration() < journal.receiptAnchor().contextGeneration()) {
                return new Result(Status.WAITING, "An older observation session cannot replace the receipt barrier.");
            }
            // A new process/connection must establish its own fresh barrier and reopen once more.
            persist(journal.withBarrier(observed));
            return new Result(Status.REOPEN_REQUIRED, "New observation session recorded; reopen once more for a full server receipt.");
        }
        if (!MossDepositJournal.laterReopenedSnapshot(journal.receiptAnchor(), observed.stamp())) {
            return new Result(Status.WAITING, "Waiting for a later chest opening and fresh full server inventory snapshot.");
        }
        Optional<String> problem = journal.receiptProblem(observed);
        if (problem.isPresent()) { return new Result(Status.UNCERTAIN, problem.orElseThrow()); }
        persist(journal.confirm(observed));
        return new Result(Status.CONFIRMED, "Exact player decrease and chest increase are durably confirmed.");
    }

    private void persist(MossDepositJournal replacement) throws IOException {
        store.replace(current, replacement);
        current = Optional.of(replacement);
    }

    private void requireActive() {
        if (cancelled) { throw new IllegalStateException("deposit maintenance is cancelled; observation only"); }
    }
}
