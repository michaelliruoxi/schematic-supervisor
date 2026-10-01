package io.github.schematicsupervisor.fabric;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit operator reconciliation, kept separate from an exact automatic disposal receipt. */
final class SurplusDisposalRecovery {
    static final String ACKNOWLEDGEMENT = "CLEARED_SURPLUS_AND_REPAIRED_LISTED_HOES";
    private SurplusDisposalRecovery() { }

    record Request(String operationId, String playerUuid, String planId, String acknowledgedAt,
                   String acknowledgement, List<Integer> repairedMainSlots) {
        Request {
            if (!UUID.fromString(operationId).toString().equals(operationId)
                    || !UUID.fromString(playerUuid).toString().equals(playerUuid)
                    || planId == null || !planId.matches("sha256:[0-9a-f]{64}")
                    || !ACKNOWLEDGEMENT.equals(acknowledgement)) {
                throw new IllegalArgumentException("An exact operator acknowledgement is required");
            }
            Instant.parse(acknowledgedAt);
            repairedMainSlots = List.copyOf(repairedMainSlots);
            if (repairedMainSlots.size() > 2 || new HashSet<>(repairedMainSlots).size() != repairedMainSlots.size()
                    || repairedMainSlots.stream().anyMatch(slot -> slot < 0 || slot >= 36)) {
                throw new IllegalArgumentException("Repair acknowledgement must name at most two exact main slots");
            }
        }
        boolean matches(SurplusDisposalJournal journal) {
            return operationId.equals(journal.operationId()) && playerUuid.equals(journal.playerUuid())
                    && planId.equals(journal.before().context().planId());
        }
        boolean fresh(Instant now) {
            Duration age = Duration.between(Instant.parse(acknowledgedAt), now);
            return !age.isNegative() && age.compareTo(Duration.ofHours(24)) <= 0;
        }
    }

    record Evidence(Request request, MossDepositJournal.Observation firstReceipt) {
        Evidence { Objects.requireNonNull(request); Objects.requireNonNull(firstReceipt); }
    }

    static Optional<String> receiptProblem(MossDepositJournal.Observation before, int source,
                                          Request request, MossDepositJournal.Observation after) {
        if (!before.context().equals(after.context()) || !request.planId().equals(before.context().planId())) {
            return Optional.of("Operator recovery receipt belongs to another build context.");
        }
        if (!before.slots().main().get(source).stack().plainPickup()
                || !after.slots().main().get(source).stack().empty()) {
            return Optional.of("The original disposal source must be empty; no discard may be replayed.");
        }
        for (int slot = 0; slot < 36; slot++) {
            var prior = before.slots().main().get(slot).stack();
            var next = after.slots().main().get(slot).stack();
            if (request.repairedMainSlots().contains(slot)) {
                if (!prior.itemId().matches("minecraft:(wooden|stone|iron|golden|diamond|netherite)_hoe")
                        || !prior.itemId().equals(next.itemId()) || prior.count() != 1 || next.count() != 1
                        || prior.maxCount() != 1 || next.maxCount() != 1) {
                    return Optional.of("An acknowledged hoe repair changed its item, count, or slot.");
                }
            } else if (!prior.samePhysicalStack(next) && !(prior.plainPickup() && next.empty())) {
                return Optional.of("Inventory changed outside acknowledged plain pickups and repaired hoes.");
            }
        }
        if (!before.slots().cursor().empty() || !after.slots().cursor().empty()
                || !sameEquipment(before.slots(), after.slots())) {
            return Optional.of("Operator recovery must preserve cursor, offhand, and armor.");
        }
        return Optional.empty();
    }

    static boolean samePlayerInventory(MossDepositFacts.Snapshot first, MossDepositFacts.Snapshot second) {
        if (!first.cursor().empty() || !second.cursor().empty() || !sameEquipment(first, second)) { return false; }
        for (int slot = 0; slot < 36; slot++) {
            if (!first.main().get(slot).stack().samePhysicalStack(second.main().get(slot).stack())) { return false; }
        }
        return true;
    }

    private static boolean sameEquipment(MossDepositFacts.Snapshot first, MossDepositFacts.Snapshot second) {
        if (!first.offhand().samePhysicalStack(second.offhand())) { return false; }
        for (int slot = 0; slot < 4; slot++) {
            if (!first.armor().get(slot).samePhysicalStack(second.armor().get(slot))) { return false; }
        }
        return true;
    }
}
