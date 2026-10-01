package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Pure write-before-input boundary. Cancellation and unknown outcomes never make another THROW eligible. */
final class SurplusDisposalController {
    interface Store {
        Optional<SurplusDisposalJournal> load() throws IOException;
        void replace(Optional<SurplusDisposalJournal> expected, SurplusDisposalJournal next) throws IOException;
    }
    interface Port {
        boolean matchesForDispatch(SurplusDisposalJournal intent);
        void throwOnce(SurplusDisposalJournal intent) throws IOException;
    }
    enum Result { WAITING, REOPEN_REQUIRED, UNCERTAIN, CONFIRMED, OPERATOR_RECONCILED, INVENTORY_RECONCILED }
    private final Store store;
    private final Port port;
    private Optional<SurplusDisposalJournal> current;
    private boolean cancelled;
    private SurplusDisposalRecovery.Request operatorRequest;
    private MossDepositJournal.Observation firstOperatorReceipt;
    private SurplusInventoryRecovery.Request inventoryRequest;
    private MossDepositJournal.Observation firstInventoryReceipt;

    SurplusDisposalController(Store store, Port port) throws IOException {
        this.store = Objects.requireNonNull(store); this.port = Objects.requireNonNull(port);
        current = Objects.requireNonNull(store.load());
    }
    Optional<SurplusDisposalJournal> journal() { return current; }
    void cancel() { cancelled = true; }

    void begin(boolean enabled, SurplusStorageExhaustion proof, SurplusDisposalPolicy.Site site,
               String playerUuid, long nowNanos) throws IOException {
        beginAuthorized(enabled, SurplusDisposalAuthorization.afterStorage(proof), site, playerUuid, nowNanos);
    }

    void beginAuthorized(boolean enabled, SurplusDisposalAuthorization proof, SurplusDisposalPolicy.Site site,
                         String playerUuid, long nowNanos) throws IOException {
        if (cancelled || current.filter(value -> value.stage() == SurplusDisposalJournal.Stage.PENDING).isPresent()) {
            throw new IllegalStateException("Pending or cancelled disposal cannot dispatch");
        }
        int source = SurplusDisposalPolicy.sourceForAuthorization(proof, enabled, nowNanos)
                .orElseThrow(() -> new IllegalStateException("No fresh authorized surplus disposal source"));
        if (current.isPresent()) {
            var receipt = current.orElseThrow().receipt();
            if (MossDepositJournal.sameSession(receipt.stamp(), proof.finalInventory().stamp())
                    && (proof.finalInventory().stamp().fullSequence() <= receipt.stamp().fullSequence()
                    || proof.finalInventory().stamp().openGeneration() <= receipt.stamp().openGeneration())) {
                throw new IllegalStateException("A new disposal requires a fresh inventory opening after its last receipt");
            }
        }
        SurplusDisposalJournal intent = SurplusDisposalJournal.pendingAuthorized(proof, source, site, playerUuid, nowNanos);
        if (!port.matchesForDispatch(intent)) { throw new IllegalStateException("Disposal preflight changed"); }
        persist(intent);
        if (cancelled || !port.matchesForDispatch(intent)) {
            throw new IllegalStateException("Disposal changed after durable intent; preserve it without replay");
        }
        // This is the only dispatch site. The PENDING journal is already durable and survives an exception.
        port.throwOnce(intent);
    }

    Result reconcile(MossDepositJournal.Observation observed) throws IOException {
        SurplusDisposalJournal journal = current.orElseThrow(() -> new IllegalStateException("No disposal intent"));
        if (journal.stage() == SurplusDisposalJournal.Stage.CONFIRMED) { return Result.CONFIRMED; }
        if (journal.stage() == SurplusDisposalJournal.Stage.OPERATOR_RECONCILED) { return Result.OPERATOR_RECONCILED; }
        if (journal.stage() == SurplusDisposalJournal.Stage.INVENTORY_RECONCILED) { return Result.INVENTORY_RECONCILED; }
        if (!journal.before().context().equals(observed.context())) { return Result.UNCERTAIN; }
        if (!MossDepositJournal.sameSession(journal.receiptAnchor(), observed.stamp())) {
            if (journal.reconciliationBarrier() != null && MossDepositJournal.sameSession(journal.before().stamp(), observed.stamp())
                    || journal.receiptAnchor().observerEpoch().equals(observed.stamp().observerEpoch())
                    && observed.stamp().contextGeneration() < journal.receiptAnchor().contextGeneration()) { return Result.WAITING; }
            persist(journal.withBarrier(observed));
            return Result.REOPEN_REQUIRED;
        }
        if (!observed.stamp().isLaterReopenThan(journal.receiptAnchor())) { return Result.WAITING; }
        if (journal.receiptProblem(observed).isPresent()) { return Result.UNCERTAIN; }
        persist(journal.confirm(observed));
        return Result.CONFIRMED;
    }

    /** Explicit recovery observes twice and never calls either input method on the port. */
    Result reconcileOperator(SurplusDisposalRecovery.Request request, MossDepositJournal.Observation observed) throws IOException {
        var journal = current.orElseThrow(() -> new IllegalStateException("No disposal intent"));
        if (cancelled || journal.stage() != SurplusDisposalJournal.Stage.PENDING || !request.matches(journal)
                || SurplusDisposalRecovery.receiptProblem(journal.before(), journal.sourceMainIndex(), request, observed).isPresent()) {
            return Result.UNCERTAIN;
        }
        var anchor = journal.receiptAnchor();
        if (MossDepositJournal.sameSession(anchor, observed.stamp()) && !observed.stamp().isLaterReopenThan(anchor)
                || anchor.observerEpoch().equals(observed.stamp().observerEpoch())
                    && observed.stamp().contextGeneration() < anchor.contextGeneration()) { return Result.WAITING; }
        if (firstOperatorReceipt == null) {
            operatorRequest = request;
            firstOperatorReceipt = observed;
            return Result.REOPEN_REQUIRED;
        }
        if (!request.equals(operatorRequest)
                || !SurplusDisposalRecovery.samePlayerInventory(firstOperatorReceipt.slots(), observed.slots())) { return Result.UNCERTAIN; }
        if (!observed.stamp().isLaterReopenThan(firstOperatorReceipt.stamp())) { return Result.WAITING; }
        persist(journal.reconcileOperator(request, firstOperatorReceipt, observed));
        return Result.OPERATOR_RECONCILED;
    }
    private void persist(SurplusDisposalJournal journal) throws IOException {
        try { store.replace(current, journal); current = Optional.of(journal); }
        catch (IOException | RuntimeException failure) { cancelled = true; throw failure; }
    }

    /** Observation-only custody recovery. An unknown drop remains unverified and can never be replayed. */
    Result reconcileInventory(SurplusInventoryRecovery.Request request, MossDepositJournal.Observation observed) throws IOException {
        var journal = current.orElseThrow(() -> new IllegalStateException("No disposal intent"));
        if (cancelled || journal.stage() != SurplusDisposalJournal.Stage.PENDING
                || journal.storage().mode() != SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP
                || request != null && (!request.matches(journal) || !request.fresh(java.time.Instant.now()))
                || SurplusInventoryRecovery.receiptProblem(journal, request, observed).isPresent()) { return Result.UNCERTAIN; }
        var anchor = journal.receiptAnchor();
        if (MossDepositJournal.sameSession(anchor, observed.stamp()) && !observed.stamp().isLaterReopenThan(anchor)
                || anchor.observerEpoch().equals(observed.stamp().observerEpoch())
                    && observed.stamp().contextGeneration() < anchor.contextGeneration()) { return Result.WAITING; }
        if (firstInventoryReceipt == null) {
            inventoryRequest = request;
            firstInventoryReceipt = observed;
            return Result.REOPEN_REQUIRED;
        }
        if (!Objects.equals(request, inventoryRequest)
                || !SurplusDisposalRecovery.samePlayerInventory(firstInventoryReceipt.slots(), observed.slots())) {
            return Result.UNCERTAIN;
        }
        if (!observed.stamp().isLaterReopenThan(firstInventoryReceipt.stamp())) { return Result.WAITING; }
        persist(journal.reconcileInventory(request, firstInventoryReceipt, observed));
        return Result.INVENTORY_RECONCILED;
    }
}
