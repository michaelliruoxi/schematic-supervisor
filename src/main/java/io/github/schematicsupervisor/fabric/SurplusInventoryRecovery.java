package io.github.schematicsupervisor.fabric;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Establishes present inventory custody without claiming that an uncertain discard succeeded. */
final class SurplusInventoryRecovery {
    private SurplusInventoryRecovery() { }

    record ExpectedStack(String itemId, int count, int maxCount, boolean plainPickup) {
        ExpectedStack {
            if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || itemId.length() > 128 || count < 0 || maxCount < 0 || maxCount > 99 || count > maxCount
                    || (count == 0) != itemId.equals("minecraft:air") || count == 0 && maxCount != 0
                    || plainPickup && (!SurplusPickupPolicy.allowed(itemId) || maxCount != 64)) {
                throw new IllegalArgumentException("Invalid expected inventory stack");
            }
        }
        boolean matches(MossDepositFacts.StackFacts stack) {
            return itemId.equals(stack.itemId()) && count == stack.count() && maxCount == stack.maxCount()
                    && plainPickup == stack.plainPickup();
        }
    }

    /** A one-operation resume instruction and its reviewed current inventory, never a manual-clear claim. */
    record Request(String requestId, String operationId, String playerUuid, String planId,
                   String requestedAt, String instruction, List<ExpectedStack> expectedMain) {
        Request {
            for (String value : List.of(requestId, operationId, playerUuid)) {
                if (!UUID.fromString(value).toString().equals(value)) { throw new IllegalArgumentException("Invalid recovery identity"); }
            }
            if (planId == null || !planId.matches("sha256:[0-9a-f]{64}") || !"continue".equals(instruction)) {
                throw new IllegalArgumentException("Recovery must be scoped to the current resume instruction");
            }
            Instant.parse(requestedAt);
            expectedMain = List.copyOf(expectedMain);
            if (expectedMain.size() != 36) { throw new IllegalArgumentException("All main inventory slots must be specified"); }
        }
        boolean matches(SurplusDisposalJournal journal) {
            return operationId.equals(journal.operationId()) && playerUuid.equals(journal.playerUuid())
                    && planId.equals(journal.before().context().planId());
        }
        boolean fresh(Instant now) {
            var age = Duration.between(Instant.parse(requestedAt), now);
            return !age.isNegative() && age.compareTo(Duration.ofHours(24)) <= 0;
        }
    }

    record Evidence(Request request, MossDepositJournal.Observation firstReceipt) {
        Evidence { Objects.requireNonNull(firstReceipt); }
    }

    static Optional<String> receiptProblem(SurplusDisposalJournal journal, Request request,
                                          MossDepositJournal.Observation after) {
        return receiptProblem(journal.before(), request, after);
    }

    static Optional<String> receiptProblem(MossDepositJournal.Observation before, Request request,
                                          MossDepositJournal.Observation after) {
        if (!before.context().equals(after.context()) || !before.slots().cursor().empty() || !after.slots().cursor().empty()) {
            return Optional.of("Inventory recovery requires the original context and an empty cursor.");
        }
        for (int index = 0; index < 36; index++) {
            var prior = before.slots().main().get(index).stack();
            var next = after.slots().main().get(index).stack();
            if (request != null && !request.expectedMain().get(index).matches(next)) {
                return Optional.of("Current inventory differs from the reviewed resume inventory.");
            }
            if (prior.samePhysicalStack(next)) { continue; }
            if ((prior.empty() || prior.plainPickup()) && (next.empty() || next.plainPickup())) { continue; }
            if (request != null) {
                // Only explicitly reviewed build-material changes may reduce protected quantities.
                boolean material = List.of("minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks").contains(prior.itemId());
                if (material && (next.empty() || next.itemId().equals(prior.itemId()) && next.maxCount() == prior.maxCount())) { continue; }
                if (!prior.empty() && prior.itemId().equals(next.itemId())
                        && next.maxCount() == prior.maxCount() && next.count() >= prior.count()) { continue; }
            }
            return Optional.of("Protected inventory changed outside the scoped resume recovery.");
        }
        if (!before.slots().offhand().samePhysicalStack(after.slots().offhand())) {
            return Optional.of("Recovery must preserve protected offhand equipment.");
        }
        for (int index = 0; index < 4; index++) {
            if (!before.slots().armor().get(index).samePhysicalStack(after.slots().armor().get(index))) {
                return Optional.of("Recovery must preserve protected armor.");
            }
        }
        return Optional.empty();
    }
}
