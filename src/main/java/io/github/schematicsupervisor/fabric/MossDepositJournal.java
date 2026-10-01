package io.github.schematicsupervisor.fabric;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One fixed plain-pickup transfer, retained until its exact reopened-container receipt is durable. */
public record MossDepositJournal(
        String operationId,
        Observation before,
        MossDepositFacts.Plan plan,
        Stage stage,
        ServerInventorySnapshotStamp reconciliationBarrier,
        Observation receipt
) {
    public enum Stage { PENDING, CONFIRMED }

    /** Original receipt source and build identity. Deposits require a physical chest; in-place disposal uses an explicit shop source. */
    public record Context(String worldIdentityHash, String dimension, String planId, String depotId,
                          int depotX, int depotY, int depotZ) {
        public Context {
            Objects.requireNonNull(worldIdentityHash, "worldIdentityHash");
            if (!worldIdentityHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("world identity must be an opaque SHA-256 value");
            }
            bounded(dimension, 256, "dimension");
            bounded(planId, 256, "planId");
            bounded(depotId, 128, "depotId");
            if (Math.abs((long) depotX) > 30_000_000 || Math.abs((long) depotY) > 30_000_000
                    || Math.abs((long) depotZ) > 30_000_000) {
                throw new IllegalArgumentException("depot coordinates exceed world bounds");
            }
        }
    }

    /**
     * Chest/main/cursor facts come from the accepted depot's full server packet.
     * Armor/offhand have LOCAL_READ_ONLY_GUARD scope: exact local copies, never transfer-ack evidence.
     */
    public record Observation(Context context, ServerInventorySnapshotStamp stamp,
                              MossDepositFacts.Snapshot slots) {
        public Observation {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(slots, "slots");
        }
    }

    public MossDepositJournal {
        Objects.requireNonNull(operationId, "operationId");
        if (!UUID.fromString(operationId).toString().equals(operationId)) {
            throw new IllegalArgumentException("operation identity must be a canonical UUID");
        }
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(stage, "stage");
        if (!MossDepositPlanning.plan(before.slots(), plan.sourceMainIndex()).filter(plan::equals).isPresent()) {
            throw new IllegalArgumentException("deposit plan does not match its complete before snapshot");
        }
        if (stage == Stage.PENDING && receipt != null || stage == Stage.CONFIRMED && receipt == null) {
            throw new IllegalArgumentException("only a confirmed deposit may carry its receipt");
        }
        if (reconciliationBarrier != null && sameSession(before.stamp(), reconciliationBarrier)) {
            throw new IllegalArgumentException("a reconciliation barrier is only for a new observation session");
        }
        if (receipt != null) {
            Optional<String> problem = receiptProblem(before, plan,
                    reconciliationBarrier == null ? before.stamp() : reconciliationBarrier, receipt);
            if (problem.isPresent()) { throw new IllegalArgumentException(problem.orElseThrow()); }
        }
    }

    public static MossDepositJournal pending(Observation before, MossDepositFacts.Plan plan) {
        return new MossDepositJournal(UUID.randomUUID().toString(), before, plan, Stage.PENDING, null, null);
    }

    public ServerInventorySnapshotStamp receiptAnchor() {
        return reconciliationBarrier == null ? before.stamp() : reconciliationBarrier;
    }

    public MossDepositJournal withBarrier(Observation observed) {
        requirePending();
        if (!before.context().equals(observed.context())) {
            throw new IllegalArgumentException("reconciliation belongs to a different world, plan, or physical depot");
        }
        return new MossDepositJournal(operationId, before, plan, stage, observed.stamp(), null);
    }

    public MossDepositJournal confirm(Observation observed) {
        requirePending();
        return new MossDepositJournal(operationId, before, plan, Stage.CONFIRMED,
                reconciliationBarrier, observed);
    }

    public Optional<String> receiptProblem(Observation observed) {
        return receiptProblem(before, plan, receiptAnchor(), observed);
    }

    public static boolean sameSession(ServerInventorySnapshotStamp first, ServerInventorySnapshotStamp second) {
        return first.observerEpoch().equals(second.observerEpoch())
                && first.contextGeneration() == second.contextGeneration();
    }

    public static boolean laterReopenedSnapshot(ServerInventorySnapshotStamp anchor,
                                                 ServerInventorySnapshotStamp candidate) {
        return candidate.isLaterReopenThan(anchor);
    }

    private static Optional<String> receiptProblem(Observation before, MossDepositFacts.Plan plan,
                                                   ServerInventorySnapshotStamp anchor, Observation after) {
        if (!before.context().equals(after.context())) {
            return Optional.of("Receipt belongs to a different world, plan, or physical depot.");
        }
        if (!laterReopenedSnapshot(anchor, after.stamp())) {
            return Optional.of("Receipt requires a fresh full server snapshot from a later chest opening.");
        }
        return MossDepositPlanning.receiptProblem(before.slots(), plan, after.slots());
    }

    private void requirePending() {
        if (stage != Stage.PENDING) { throw new IllegalStateException("deposit is already confirmed"); }
    }

    private static void bounded(String value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be non-empty and bounded without control characters");
        }
    }
}
