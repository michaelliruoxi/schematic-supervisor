package io.github.schematicsupervisor.fabric;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Historical zero-room evidence, not permission to discard: callers must revalidate freshness and inventory. */
record SurplusStorageExhaustion(List<MossDepositJournal.Context> registered,
                               List<MossDepositJournal.Observation> chests,
                               MossDepositJournal.Observation finalInventory,
                               long oldestObservedAtNanos, long capturedAtNanos) {
    static final int MAXIMUM_CHESTS = 128;

    SurplusStorageExhaustion {
        registered = List.copyOf(registered);
        chests = List.copyOf(chests);
        if (!valid(registered, chests, finalInventory) || capturedAtNanos - oldestObservedAtNanos < 0) {
            throw new IllegalArgumentException("Complete registered-chest zero-room evidence is required");
        }
    }

    static Optional<SurplusStorageExhaustion> capture(List<MossDepositJournal.Context> registered,
            List<MossDepositJournal.Observation> chests, MossDepositJournal.Observation finalInventory,
            long oldestObservedAtNanos, long capturedAtNanos) {
        return valid(registered, chests, finalInventory) && capturedAtNanos - oldestObservedAtNanos >= 0
                ? Optional.of(new SurplusStorageExhaustion(registered, chests, finalInventory,
                        oldestObservedAtNanos, capturedAtNanos))
                : Optional.empty();
    }

    private static boolean valid(List<MossDepositJournal.Context> registered,
            List<MossDepositJournal.Observation> chests, MossDepositJournal.Observation last) {
        if (registered == null || chests == null || last == null || registered.isEmpty()
                || registered.size() > MAXIMUM_CHESTS || registered.size() != chests.size()
                || registered.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(registered).size() != registered.size()
                || registered.stream().map(MossDepositJournal.Context::depotId).distinct().count() != registered.size()
                || !chests.contains(last)
                || !last.slots().cursor().empty()
                || last.slots().main().stream().noneMatch(slot -> slot.stack().plainPickup())) { return false; }
        var seen = new HashSet<MossDepositJournal.Context>();
        for (var chest : chests) {
            if (chest == null || !registered.contains(chest.context()) || !seen.add(chest.context())
                    || !sameBuild(chest.context(), last.context())
                    || !MossDepositJournal.sameSession(chest.stamp(), last.stamp())
                    || !chest.slots().cursor().empty()) { return false; }
            if (chest.slots().chest().stream().anyMatch(slot ->
                    !slot.insertion().keySet().containsAll(SurplusPickupPolicy.ITEM_IDS))) { return false; }
            // A single unit of observed compatible room prevents zero-room evidence.
            for (var source : last.slots().main()) {
                if (source.stack().plainPickup() && (!source.canTake()
                        || MossDepositPlanning.observedRoom(chest.slots(), source.stack().itemId()) > 0)) { return false; }
            }
        }
        return true;
    }

    private static boolean sameBuild(MossDepositJournal.Context a, MossDepositJournal.Context b) {
        return a.worldIdentityHash().equals(b.worldIdentityHash()) && a.dimension().equals(b.dimension())
                && a.planId().equals(b.planId());
    }
}
