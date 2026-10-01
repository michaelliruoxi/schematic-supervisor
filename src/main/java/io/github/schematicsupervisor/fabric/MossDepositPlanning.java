package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Verifies packet-backed chest/main/cursor changes and additional local equipment guards. */
final class MossDepositPlanning {
    private MossDepositPlanning() { }

    static Optional<MossDepositFacts.Plan> plan(MossDepositFacts.Snapshot before) {
        Objects.requireNonNull(before, "before");
        for (int index = 0; index < 36; index++) {
            Optional<MossDepositFacts.Plan> candidate = plan(before, index);
            if (candidate.isPresent()) { return candidate; }
        }
        return Optional.empty();
    }

    static Optional<MossDepositFacts.Plan> plan(MossDepositFacts.Snapshot before, int sourceMainIndex) {
        Objects.requireNonNull(before, "before");
        if (sourceMainIndex < 0 || sourceMainIndex >= 36 || !before.cursor().empty()) { return Optional.empty(); }
        MossDepositFacts.SlotFacts source = before.main().get(sourceMainIndex);
        if (!source.canTake() || !source.stack().plainPickup()) { return Optional.empty(); }
        List<MossDepositFacts.Destination> capacity = new ArrayList<>();
        int totalRoom = 0;
        for (MossDepositFacts.SlotFacts slot : before.chest()) {
            int room = room(slot, source.stack().itemId());
            if (room > 0) {
                capacity.add(new MossDepositFacts.Destination(slot.handlerSlot(), room));
                totalRoom = Math.addExact(totalRoom, room);
            }
        }
        if (totalRoom == 0) { return Optional.empty(); }
        return Optional.of(new MossDepositFacts.Plan(sourceMainIndex, source.handlerSlot(),
                Math.min(source.stack().count(), totalRoom), capacity));
    }

    static Optional<String> receiptProblem(MossDepositFacts.Snapshot before, MossDepositFacts.Plan plan,
                                           MossDepositFacts.Snapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(after, "after");
        if (!plan(before, plan.sourceMainIndex()).filter(plan::equals).isPresent()) {
            return Optional.of("Transfer intent does not match its original exact capacity proof");
        }
        String itemId = before.main().get(plan.sourceMainIndex()).stack().itemId();
        if (before.chest().size() != after.chest().size()) {
            return Optional.of("Chest layout changed before transfer confirmation");
        }
        if (!before.cursor().samePhysicalStack(after.cursor()) || !after.cursor().empty()) {
            return Optional.of("Cursor changed or is not empty");
        }
        if (!before.offhand().samePhysicalStack(after.offhand())) { return Optional.of("Protected offhand changed"); }
        for (int index = 0; index < before.armor().size(); index++) {
            if (!before.armor().get(index).samePhysicalStack(after.armor().get(index))) {
                return Optional.of("Protected armor changed");
            }
        }
        for (int index = 0; index < 36; index++) {
            MossDepositFacts.StackFacts previous = before.main().get(index).stack();
            MossDepositFacts.StackFacts current = after.main().get(index).stack();
            if (index == plan.sourceMainIndex()) {
                int remaining = previous.count() - plan.quantity();
                if (remaining == 0 ? !current.empty()
                        : !current.plainPickup() || !current.itemId().equals(itemId) || current.count() != remaining) {
                    return Optional.of("The source stack remainder does not match the planned item and quantity");
                }
            } else if (!previous.samePhysicalStack(current)) {
                return Optional.of("An unplanned main inventory slot changed");
            }
        }
        if (plainCount(before.main(), itemId) - plainCount(after.main(), itemId) != plan.quantity()) {
            return Optional.of("Player pickup decrease does not match the planned item and quantity");
        }
        for (int index = 0; index < before.chest().size(); index++) {
            MossDepositFacts.SlotFacts slot = before.chest().get(index);
            MossDepositFacts.StackFacts previous = slot.stack();
            MossDepositFacts.StackFacts current = after.chest().get(index).stack();
            if (previous.samePhysicalStack(current)) { continue; }
            int available = room(slot, itemId);
            int previousCount = previous.empty() ? 0 : previous.count();
            if (available == 0 || !current.plainPickup() || !current.itemId().equals(itemId)
                    || current.count() <= previousCount
                    || current.count() - previousCount > available) {
                return Optional.of("A protected chest slot changed or its observed pickup capacity was exceeded");
            }
        }
        if (plainCount(after.chest(), itemId) - plainCount(before.chest(), itemId) != plan.quantity()) {
            return Optional.of("Chest pickup increase does not match the planned item and quantity");
        }
        return Optional.empty();
    }

    private static int room(MossDepositFacts.SlotFacts slot, String itemId) {
        MossDepositFacts.Insertion insertion = slot.insertion().get(itemId);
        if (insertion == null || !insertion.allowed() || (!slot.stack().empty()
                && (!slot.stack().plainPickup() || !slot.stack().itemId().equals(itemId)))) { return 0; }
        int limit = Math.min(64, insertion.limit());
        int count = slot.stack().empty() ? 0 : slot.stack().count();
        return Math.max(0, limit - count);
    }

    static int observedRoom(MossDepositFacts.Snapshot snapshot, String itemId) {
        if (!SurplusPickupPolicy.allowed(itemId)) { throw new IllegalArgumentException("Unsupported storage item"); }
        return snapshot.chest().stream().mapToInt(slot -> room(slot, itemId)).sum();
    }

    private static long plainCount(List<MossDepositFacts.SlotFacts> slots, String itemId) {
        return slots.stream().map(MossDepositFacts.SlotFacts::stack)
                .filter(stack -> stack.plainPickup() && stack.itemId().equals(itemId))
                .mapToLong(MossDepositFacts.StackFacts::count).sum();
    }
}
