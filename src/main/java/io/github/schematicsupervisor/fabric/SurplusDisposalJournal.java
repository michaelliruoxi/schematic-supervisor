package io.github.schematicsupervisor.fabric;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A single irreversible input intent. Restored intents can only acquire a receipt, never dispatch. */
record SurplusDisposalJournal(String operationId, String playerUuid, MossDepositJournal.Observation before, int sourceMainIndex,
                              SurplusDisposalPolicy.Site site, StorageProof storage, Stage stage,
                              ServerInventorySnapshotStamp reconciliationBarrier,
                              MossDepositJournal.Observation receipt,
                              SurplusDisposalRecovery.Evidence operatorRecovery,
                              SurplusInventoryRecovery.Evidence inventoryRecovery) {
    enum Stage { PENDING, CONFIRMED, OPERATOR_RECONCILED, INVENTORY_RECONCILED }
    SurplusDisposalJournal(String operationId, String playerUuid, MossDepositJournal.Observation before,
                          int sourceMainIndex, SurplusDisposalPolicy.Site site, StorageProof storage, Stage stage,
                          ServerInventorySnapshotStamp reconciliationBarrier, MossDepositJournal.Observation receipt,
                          SurplusDisposalRecovery.Evidence operatorRecovery) {
        this(operationId, playerUuid, before, sourceMainIndex, site, storage, stage, reconciliationBarrier, receipt, operatorRecovery, null);
    }
    SurplusDisposalJournal(String operationId, String playerUuid, MossDepositJournal.Observation before,
                          int sourceMainIndex, SurplusDisposalPolicy.Site site, StorageProof storage, Stage stage,
                          ServerInventorySnapshotStamp reconciliationBarrier, MossDepositJournal.Observation receipt) {
        this(operationId, playerUuid, before, sourceMainIndex, site, storage, stage, reconciliationBarrier, receipt, null);
    }
    record ChestProof(MossDepositJournal.Context context, ServerInventorySnapshotStamp stamp) {
        ChestProof { Objects.requireNonNull(context); Objects.requireNonNull(stamp); }
    }
    /** Compact durable provenance; DIRECT records one inventory receipt without claiming storage is full. */
    record StorageProof(List<ChestProof> chests, String itemId, long oldestAgeNanos,
                        SurplusDisposalAuthorization.Mode mode) {
        StorageProof(List<ChestProof> chests, String itemId, long oldestAgeNanos) {
            this(chests, itemId, oldestAgeNanos, SurplusDisposalAuthorization.Mode.STORAGE_FULL);
        }
        StorageProof {
            Objects.requireNonNull(mode);
            chests = List.copyOf(chests);
            if (chests.isEmpty() || chests.size() > SurplusStorageExhaustion.MAXIMUM_CHESTS
                    || new HashSet<>(chests.stream().map(ChestProof::context).toList()).size() != chests.size()
                    || !SurplusPickupPolicy.allowed(itemId) || oldestAgeNanos < 0
                    || oldestAgeNanos > SurplusDisposalPolicy.MAXIMUM_PROOF_AGE_NANOS
                    || mode != SurplusDisposalAuthorization.Mode.STORAGE_FULL && chests.size() != 1
                    || mode == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP
                        && chests.stream().anyMatch(chest -> !SurplusDisposalAuthorization.shopContext(chest.context()))
                    || mode != SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP
                        && chests.stream().anyMatch(chest -> SurplusDisposalAuthorization.shopContext(chest.context()))) {
                throw new IllegalArgumentException("Disposal storage proof is incomplete or expired");
            }
        }
    }

    SurplusDisposalJournal {
        if (operationId == null || !UUID.fromString(operationId).toString().equals(operationId)) {
            throw new IllegalArgumentException("Disposal operation must be a canonical UUID");
        }
        if (playerUuid == null || !UUID.fromString(playerUuid).toString().equals(playerUuid)) {
            throw new IllegalArgumentException("Disposal player must be a canonical UUID");
        }
        Objects.requireNonNull(before); Objects.requireNonNull(storage);
        if ((site == null) != (storage.mode() == SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP)) {
            throw new IllegalArgumentException("Only in-place shop receipts omit a void disposal site");
        }
        Objects.requireNonNull(stage);
        if (sourceMainIndex < 0 || sourceMainIndex >= 36) { throw new IllegalArgumentException("Invalid disposal source"); }
        var source = before.slots().main().get(sourceMainIndex);
        if (!source.canTake() || !source.stack().plainPickup() || !before.slots().cursor().empty()
                || !source.stack().itemId().equals(storage.itemId())
                || storage.chests().stream().noneMatch(chest -> chest.context().equals(before.context())
                && chest.stamp().equals(before.stamp()))) {
            throw new IllegalArgumentException("Disposal intent must match exact approved source and storage proof");
        }
        for (var chest : storage.chests()) {
            if (!chest.context().worldIdentityHash().equals(before.context().worldIdentityHash())
                    || !chest.context().dimension().equals(before.context().dimension())
                    || !chest.context().planId().equals(before.context().planId())
                    || !MossDepositJournal.sameSession(chest.stamp(), before.stamp())) {
                throw new IllegalArgumentException("Disposal storage proof belongs to another context");
            }
        }
        if (stage == Stage.PENDING && receipt != null || stage != Stage.PENDING && receipt == null
                || (stage == Stage.OPERATOR_RECONCILED) != (operatorRecovery != null)
                || (stage == Stage.INVENTORY_RECONCILED) != (inventoryRecovery != null)
                || reconciliationBarrier != null && MossDepositJournal.sameSession(before.stamp(), reconciliationBarrier)) {
            throw new IllegalArgumentException("Invalid disposal receipt stage");
        }
        if (inventoryRecovery != null) {
            var request = inventoryRecovery.request();
            var first = inventoryRecovery.firstReceipt();
            if (storage.mode() != SurplusDisposalAuthorization.Mode.IN_PLACE_SHOP
                    || request != null && (!request.operationId().equals(operationId) || !request.playerUuid().equals(playerUuid)
                        || !request.planId().equals(before.context().planId()))
                    || !(first.stamp().isLaterReopenThan(before.stamp()) || first.stamp().equals(reconciliationBarrier))
                    || !receipt.stamp().isLaterReopenThan(first.stamp())
                    || SurplusInventoryRecovery.receiptProblem(before, request, first).isPresent()
                    || SurplusInventoryRecovery.receiptProblem(before, request, receipt).isPresent()
                    || !SurplusDisposalRecovery.samePlayerInventory(first.slots(), receipt.slots())) {
                throw new IllegalArgumentException("Inventory recovery requires two stable full receipts without discard credit");
            }
        } else if (operatorRecovery != null) {
            var request = operatorRecovery.request();
            var first = operatorRecovery.firstReceipt();
            if (!request.operationId().equals(operationId) || !request.playerUuid().equals(playerUuid)
                    || !request.planId().equals(before.context().planId())
                    || !(first.stamp().isLaterReopenThan(before.stamp()) || first.stamp().equals(reconciliationBarrier))
                    || !receipt.stamp().isLaterReopenThan(first.stamp())
                    || SurplusDisposalRecovery.receiptProblem(before, sourceMainIndex, request, first).isPresent()
                    || SurplusDisposalRecovery.receiptProblem(before, sourceMainIndex, request, receipt).isPresent()
                    || !SurplusDisposalRecovery.samePlayerInventory(first.slots(), receipt.slots())) {
                throw new IllegalArgumentException("Operator recovery requires two stable full receipts and exact acknowledgement");
            }
        } else if (receipt != null) {
            Optional<String> problem = receiptProblem(before, sourceMainIndex,
                    reconciliationBarrier == null ? before.stamp() : reconciliationBarrier, receipt);
            if (problem.isPresent()) { throw new IllegalArgumentException(problem.orElseThrow()); }
        }
    }

    static SurplusDisposalJournal pending(SurplusStorageExhaustion proof, int source,
                                          SurplusDisposalPolicy.Site site, String playerUuid, long nowNanos) {
        return pendingAuthorized(SurplusDisposalAuthorization.afterStorage(proof), source, site, playerUuid, nowNanos);
    }
    static SurplusDisposalJournal pendingAuthorized(SurplusDisposalAuthorization proof, int source,
                                          SurplusDisposalPolicy.Site site, String playerUuid, long nowNanos) {
        if (!SurplusDisposalPolicy.freshAuthorization(proof, nowNanos)) { throw new IllegalArgumentException("Disposal evidence expired"); }
        var storage = new StorageProof(proof.chests().stream()
                .map(value -> new ChestProof(value.context(), value.stamp())).toList(),
                proof.finalInventory().slots().main().get(source).stack().itemId(), nowNanos - proof.oldestObservedAtNanos(), proof.mode());
        return new SurplusDisposalJournal(UUID.randomUUID().toString(), playerUuid, proof.finalInventory(), source,
                site, storage, Stage.PENDING, null, null);
    }
    ServerInventorySnapshotStamp receiptAnchor() { return reconciliationBarrier == null ? before.stamp() : reconciliationBarrier; }
    SurplusDisposalJournal withBarrier(MossDepositJournal.Observation observed) {
        requirePending();
        if (!before.context().equals(observed.context())) { throw new IllegalArgumentException("Receipt context changed"); }
        return new SurplusDisposalJournal(operationId, playerUuid, before, sourceMainIndex, site, storage, stage, observed.stamp(), null);
    }
    SurplusDisposalJournal confirm(MossDepositJournal.Observation observed) {
        requirePending();
        return new SurplusDisposalJournal(operationId, playerUuid, before, sourceMainIndex, site, storage, Stage.CONFIRMED,
                reconciliationBarrier, observed);
    }
    SurplusDisposalJournal reconcileOperator(SurplusDisposalRecovery.Request request,
            MossDepositJournal.Observation first, MossDepositJournal.Observation second) {
        requirePending();
        var barrier = MossDepositJournal.sameSession(before.stamp(), first.stamp()) ? null : first.stamp();
        return new SurplusDisposalJournal(operationId, playerUuid, before, sourceMainIndex, site, storage,
                Stage.OPERATOR_RECONCILED, barrier, second, new SurplusDisposalRecovery.Evidence(request, first));
    }
    SurplusDisposalJournal reconcileInventory(SurplusInventoryRecovery.Request request,
            MossDepositJournal.Observation first, MossDepositJournal.Observation second) {
        requirePending();
        var barrier = MossDepositJournal.sameSession(before.stamp(), first.stamp()) ? null : first.stamp();
        return new SurplusDisposalJournal(operationId, playerUuid, before, sourceMainIndex, site, storage,
                Stage.INVENTORY_RECONCILED, barrier, second, null, new SurplusInventoryRecovery.Evidence(request, first));
    }
    Optional<String> receiptProblem(MossDepositJournal.Observation observed) {
        return receiptProblem(before, sourceMainIndex, receiptAnchor(), observed);
    }
    private static Optional<String> receiptProblem(MossDepositJournal.Observation before, int source,
            ServerInventorySnapshotStamp anchor, MossDepositJournal.Observation after) {
        if (!before.context().equals(after.context())) { return Optional.of("Disposal receipt context changed."); }
        if (!after.stamp().isLaterReopenThan(anchor)) { return Optional.of("Disposal needs a later full chest receipt."); }
        return SurplusDisposalPolicy.receiptProblem(before.slots(), source, after.slots());
    }
    private void requirePending() {
        if (stage != Stage.PENDING) { throw new IllegalStateException("Disposal receipt is already confirmed"); }
    }
}
