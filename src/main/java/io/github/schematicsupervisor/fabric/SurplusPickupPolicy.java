package io.github.schematicsupervisor.fabric;

import java.util.List;

/** Only these default-component clearing drops may enter reversible registered-chest storage. */
final class SurplusPickupPolicy {
    static final List<String> ITEM_IDS = List.of("minecraft:moss_block", "minecraft:pumpkin_seeds",
            "minecraft:melon_seeds", "minecraft:jack_o_lantern");
    static final int MAXIMUM_STACKS = 8;

    private SurplusPickupPolicy() { }

    static boolean allowed(String itemId) { return ITEM_IDS.contains(itemId); }

    enum Next { TRANSFER, NEXT_CHEST, FINISH }

    static Next next(int confirmedStacks, boolean pickupRemains, boolean quantityFits) {
        if (confirmedStacks < 0 || confirmedStacks > MAXIMUM_STACKS) {
            throw new IllegalArgumentException("Invalid bounded storage batch");
        }
        if (confirmedStacks == MAXIMUM_STACKS || !pickupRemains) { return Next.FINISH; }
        return quantityFits ? Next.TRANSFER : Next.NEXT_CHEST;
    }
}
