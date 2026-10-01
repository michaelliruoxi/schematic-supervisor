package io.github.schematicsupervisor.fabric;

import java.util.function.Function;

/** Only positively identified, harmless loose items may be ignored above a clearing target. */
final class MossClearingEntityPolicy {
    record Facts(boolean exactItemClass, boolean itemType, boolean hittable, boolean collidable,
                 boolean hasVehicle, boolean hasPassengers) { }

    private MossClearingEntityPolicy() { }

    static <T> boolean hasConflict(boolean playerOverlap, Iterable<T> occupants,
                                   Function<? super T, Facts> readFacts) {
        if (playerOverlap || occupants == null || readFacts == null) { return true; }
        try {
            // The full query decides safety; bounded diagnostic samples never decide it.
            for (T occupant : occupants) {
                Facts facts = occupant == null ? null : readFacts.apply(occupant);
                if (facts == null || !facts.exactItemClass() || !facts.itemType()
                        || facts.hittable() || facts.collidable() || facts.hasVehicle()
                        || facts.hasPassengers()) { return true; }
            }
            return false;
        } catch (RuntimeException unreadable) {
            return true;
        }
    }
}
