package io.github.schematicsupervisor.fabric;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable transfer facts; chest/main/cursor are packet evidence, equipment is a local read-only guard. */
final class MossDepositFacts {
    static final String MOSS = "minecraft:moss_block";
    private MossDepositFacts() { }

    record StackFacts(String fingerprint, String itemId, int count, int maxCount, boolean empty,
                      boolean plainPickup) {
        StackFacts {
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(itemId, "itemId");
            if (!fingerprint.matches("[0-9a-f]{64}") || itemId.length() > 128
                    || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || count < 0 || maxCount < 0 || maxCount > 99) {
                throw new IllegalArgumentException("invalid bounded stack facts");
            }
            if (empty ? count != 0 || maxCount != 0 || plainPickup || !itemId.equals("minecraft:air")
                    : count < 1 || maxCount < 1 || count > maxCount || itemId.equals("minecraft:air")) {
                throw new IllegalArgumentException("inconsistent empty or occupied stack facts");
            }
            if (plainPickup && (!SurplusPickupPolicy.allowed(itemId) || maxCount != 64)) {
                throw new IllegalArgumentException("plain pickup must have an allowed item and default stack limit");
            }
        }

        /** Eligibility can grow between journal versions; protected physical stack evidence cannot change. */
        boolean samePhysicalStack(StackFacts other) {
            return other != null && fingerprint.equals(other.fingerprint) && itemId.equals(other.itemId)
                    && count == other.count && maxCount == other.maxCount && empty == other.empty;
        }
    }

    record Insertion(boolean allowed, int limit) {
        Insertion {
            if (limit < 0 || limit > 99) { throw new IllegalArgumentException("Invalid insertion limit"); }
        }
    }

    record SlotFacts(int handlerSlot, int inventoryIndex, StackFacts stack, boolean canTake,
                     Map<String, Insertion> insertion) {
        SlotFacts {
            Objects.requireNonNull(stack, "stack");
            insertion = Map.copyOf(insertion);
            if (handlerSlot < 0 || handlerSlot > 89 || inventoryIndex < 0 || inventoryIndex > 53
                    || insertion.size() > SurplusPickupPolicy.ITEM_IDS.size()
                    || insertion.keySet().stream().anyMatch(id -> !SurplusPickupPolicy.allowed(id))) {
                throw new IllegalArgumentException("invalid bounded slot facts");
            }
        }

        /** Version-one snapshots prove insertion only for Moss, never for newly admitted pickup types. */
        SlotFacts(int handlerSlot, int inventoryIndex, StackFacts stack, boolean canTake,
                  boolean canInsertMoss, int mossSlotLimit) {
            this(handlerSlot, inventoryIndex, stack, canTake,
                    Map.of(MOSS, new Insertion(canInsertMoss, mossSlotLimit)));
        }
    }

    record Snapshot(List<SlotFacts> chest, List<SlotFacts> main, StackFacts cursor, StackFacts offhand,
                    List<StackFacts> armor) {
        Snapshot {
            chest = List.copyOf(chest);
            main = List.copyOf(main);
            armor = List.copyOf(armor);
            Objects.requireNonNull(cursor, "cursor");
            Objects.requireNonNull(offhand, "offhand");
            if ((chest.size() != 27 && chest.size() != 54) || main.size() != 36 || armor.size() != 4) {
                throw new IllegalArgumentException("physical chest, main36, and four local armor guards are required");
            }
            for (int index = 0; index < chest.size(); index++) {
                SlotFacts slot = chest.get(index);
                if (slot.handlerSlot() != index || slot.inventoryIndex() != index) {
                    throw new IllegalArgumentException("chest mapping must be complete and ordered");
                }
            }
            for (int index = 0; index < main.size(); index++) {
                SlotFacts slot = main.get(index);
                if (slot.inventoryIndex() != index || slot.handlerSlot() != mainHandlerSlot(chest.size(), index)) {
                    throw new IllegalArgumentException("main inventory mapping must be complete and exact");
                }
            }
        }
    }

    /** Amount is observed available room, not an assumed vanilla quick-move allocation. */
    record Destination(int handlerSlot, int amount) {
        Destination {
            if (handlerSlot < 0 || handlerSlot > 53 || amount < 1 || amount > 64) {
                throw new IllegalArgumentException("invalid chest capacity evidence");
            }
        }
    }

    record Plan(int sourceMainIndex, int sourceHandlerSlot, int quantity, List<Destination> destinations) {
        Plan {
            destinations = List.copyOf(destinations);
            if (sourceMainIndex < 0 || sourceMainIndex > 35 || sourceHandlerSlot < 27 || sourceHandlerSlot > 89
                    || quantity < 1 || quantity > 64 || destinations.isEmpty() || destinations.size() > 54) {
                throw new IllegalArgumentException("invalid bounded pickup plan");
            }
            int previous = -1;
            int room = 0;
            for (Destination destination : destinations) {
                if (destination.handlerSlot() <= previous) {
                    throw new IllegalArgumentException("capacity slots must be unique and ordered");
                }
                previous = destination.handlerSlot();
                room = Math.addExact(room, destination.amount());
            }
            if (room < quantity) { throw new IllegalArgumentException("insufficient observed capacity"); }
        }
    }

    static int mainHandlerSlot(int chestSlots, int mainIndex) {
        if ((chestSlots != 27 && chestSlots != 54) || mainIndex < 0 || mainIndex > 35) {
            throw new IllegalArgumentException("unsupported inventory mapping");
        }
        return chestSlots + (mainIndex < 9 ? 27 + mainIndex : mainIndex - 9);
    }
}
