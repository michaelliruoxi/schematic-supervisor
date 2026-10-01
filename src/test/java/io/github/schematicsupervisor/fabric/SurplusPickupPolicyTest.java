package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurplusPickupPolicyTest {
    @Test void allowlistExcludesBuildSuppliesWheatSeedsToolsAndKeys() {
        assertEquals(List.of("minecraft:moss_block", "minecraft:pumpkin_seeds", "minecraft:melon_seeds",
                "minecraft:jack_o_lantern"), SurplusPickupPolicy.ITEM_IDS);
        for (String protectedItem : List.of("minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks",
                "minecraft:wheat_seeds", "minecraft:diamond_hoe", "minecraft:diamond_axe", "minecraft:tripwire_hook")) {
            assertFalse(SurplusPickupPolicy.allowed(protectedItem));
        }
    }

    @Test void fullChestRotatesEvenAfterConfirmedTransferButEighthTransferEndsBatch() {
        assertEquals(SurplusPickupPolicy.Next.NEXT_CHEST, SurplusPickupPolicy.next(0, true, false));
        assertEquals(SurplusPickupPolicy.Next.NEXT_CHEST, SurplusPickupPolicy.next(3, true, false));
        assertEquals(SurplusPickupPolicy.Next.TRANSFER, SurplusPickupPolicy.next(7, true, true));
        assertEquals(SurplusPickupPolicy.Next.FINISH, SurplusPickupPolicy.next(8, true, true));
        assertEquals(SurplusPickupPolicy.Next.FINISH, SurplusPickupPolicy.next(8, true, false));
        assertEquals(SurplusPickupPolicy.Next.FINISH, SurplusPickupPolicy.next(3, false, false));
    }
}
