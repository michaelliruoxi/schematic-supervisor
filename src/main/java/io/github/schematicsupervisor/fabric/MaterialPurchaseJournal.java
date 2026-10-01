package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One authorized quantity click, retained until a later shop opening provides its exact stock receipt. */
public record MaterialPurchaseJournal(String operationId, Observation before, MaterialPurchaseFacts.Quote quote,
                                      Stage stage, ServerInventorySnapshotStamp reconciliationBarrier,
                                      Observation receipt) {
    public enum Stage { PENDING, CONFIRMED }

    /** The opaque identity must bind server, account/profile and world as well as the explicit dimension. */
    public record Context(String worldIdentityHash, String dimension, String planId) {
        public Context {
            Objects.requireNonNull(worldIdentityHash, "worldIdentityHash");
            if (!worldIdentityHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("world identity must be an opaque SHA-256 value");
            }
            MaterialPurchaseFacts.bounded(dimension, 256, "dimension");
            MaterialPurchaseFacts.bounded(planId, 256, "planId");
        }
    }

    public record Observation(Context context, ServerInventorySnapshotStamp stamp,
                              MaterialPurchaseFacts.Snapshot slots) {
        public Observation {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(slots, "slots");
        }
    }

    public MaterialPurchaseJournal {
        Objects.requireNonNull(operationId, "operationId");
        if (!UUID.fromString(operationId).toString().equals(operationId)) {
            throw new IllegalArgumentException("operation identity must be a canonical UUID");
        }
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(stage, "stage");
        MaterialPurchaseFacts.baselineProblem(before.slots(), quote).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        if (stage == Stage.PENDING && receipt != null || stage == Stage.CONFIRMED && receipt == null) {
            throw new IllegalArgumentException("only a confirmed purchase may carry its receipt");
        }
        if (reconciliationBarrier != null && sameSession(before.stamp(), reconciliationBarrier)) {
            throw new IllegalArgumentException("a reconciliation barrier is only for a new observation session");
        }
        if (receipt != null) {
            receiptProblem(before, quote, reconciliationBarrier == null ? before.stamp() : reconciliationBarrier,
                    receipt).ifPresent(problem -> { throw new IllegalArgumentException(problem); });
        }
    }

    public static MaterialPurchaseJournal pending(Observation before, MaterialPurchaseFacts.Quote quote) {
        return new MaterialPurchaseJournal(UUID.randomUUID().toString(), before, quote, Stage.PENDING, null, null);
    }
    public ServerInventorySnapshotStamp receiptAnchor() {
        return reconciliationBarrier == null ? before.stamp() : reconciliationBarrier;
    }
    public MaterialPurchaseJournal withBarrier(Observation observed) {
        requirePending();
        if (!before.context().equals(observed.context())) {
            throw new IllegalArgumentException("reconciliation belongs to a different world, profile, dimension or plan");
        }
        return new MaterialPurchaseJournal(operationId, before, quote, stage, observed.stamp(), null);
    }
    public MaterialPurchaseJournal confirm(Observation observed) {
        requirePending();
        return new MaterialPurchaseJournal(operationId, before, quote, Stage.CONFIRMED,
                reconciliationBarrier, observed);
    }
    public Optional<String> receiptProblem(Observation observed) {
        return receiptProblem(before, quote, receiptAnchor(), observed);
    }
    public static boolean sameSession(ServerInventorySnapshotStamp first, ServerInventorySnapshotStamp second) {
        return first.observerEpoch().equals(second.observerEpoch())
                && first.contextGeneration() == second.contextGeneration();
    }
    private static Optional<String> receiptProblem(Observation before, MaterialPurchaseFacts.Quote quote,
                                                   ServerInventorySnapshotStamp anchor, Observation after) {
        if (!before.context().equals(after.context())) { return Optional.of("Receipt belongs to a different durable purchase context."); }
        if (!after.stamp().isLaterReopenThan(anchor)) {
            return Optional.of("Receipt requires a fresh full server snapshot from a later accepted shop opening.");
        }
        return MaterialPurchaseFacts.receiptProblem(before.slots(), quote, after.slots());
    }
    private void requirePending() {
        if (stage != Stage.PENDING) { throw new IllegalStateException("purchase is already confirmed"); }
    }
}
