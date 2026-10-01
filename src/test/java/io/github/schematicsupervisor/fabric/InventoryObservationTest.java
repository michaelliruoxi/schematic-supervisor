package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InventoryObservationTest {
    @Test
    void separatesRealMainAndOffhandTotalsAndNormalDirtCapacity() {
        InventoryObservation inventory = sampleInventory();
        assertEquals(36, inventory.mainSlots().size());
        assertEquals(32, inventory.emptyMainSlots());
        assertEquals(32L * 64 + 32, inventory.dirtCapacity());
        assertEquals(48, inventory.mainMaterialTotals().get(Material.DIRT));
        assertEquals(64, inventory.mainAndOffhandMaterialTotals().get(Material.DIRT));
        assertEquals(1, inventory.mainMaterialTotals().get(Material.HOE));
        assertEquals(5, inventory.mainMaterialTotals().get(Material.FOOD));
        assertEquals(0, inventory.mainMaterialTotals().get(Material.WHEAT_SEEDS));
        assertEquals(12, inventory.menu().cursor().count());
        assertEquals(1_551, inventory.mainSlots().get(2).remainingDurability());
    }

    @Test
    void unavailableInventoryDoesNotClaimEmptyOrZeroCapacity() {
        InventoryObservation unavailable = InventoryObservation.unavailable("disconnected");
        assertFalse(unavailable.available());
        assertTrue(unavailable.mainSlots().isEmpty());
        assertEquals(null, unavailable.emptyMainSlots());
        assertEquals(null, unavailable.dirtCapacity());
        assertEquals(null, unavailable.mainMaterialTotals());
    }

    @Test
    void refusesAliasedOrMissingMainSlotsAndDoesNotInventUnbreakableDurability() {
        InventoryObservation inventory = sampleInventory();
        ArrayList<InventoryObservation.Slot> main = new ArrayList<>(inventory.mainSlots());
        main.set(35, InventoryObservation.Slot.empty(0));
        assertThrows(IllegalArgumentException.class, () -> InventoryObservation.capture(main,
                inventory.offhand(), 0, 64, inventory.menu()));
        assertThrows(IllegalArgumentException.class, () -> InventoryObservation.capture(main.subList(0, 35),
                inventory.offhand(), 0, 64, inventory.menu()));
        InventoryObservation.Slot unbreakable = new InventoryObservation.Slot(2,
                "minecraft:diamond_hoe", 1, 1, Material.HOE, false, true, 10, 1_561, true);
        assertEquals(null, unbreakable.remainingDurability());
    }

    @Test
    void missingDamageComponentsAndZeroMaximumDoNotInventFiniteDurability() {
        assertEquals(null, new InventoryObservation.Slot(0, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, null, 1_561, false).remainingDurability());
        assertEquals(null, new InventoryObservation.Slot(0, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, 0, null, false).remainingDurability());
        assertEquals(null, new InventoryObservation.Slot(0, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, null, null, false).remainingDurability());
        assertEquals(null, new InventoryObservation.Slot(0, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, 0, 0, false).remainingDurability());
        assertEquals(0, new InventoryObservation.Slot(0, "minecraft:diamond_hoe", 1, 1,
                null, false, true, 1_561, 1_561, false).remainingDurability());
        assertThrows(IllegalArgumentException.class, () -> new InventoryObservation.Slot(0,
                "minecraft:diamond_hoe", 1, 1, Material.HOE, false, true, -1, null, false));
        assertThrows(IllegalArgumentException.class, () -> new InventoryObservation.Slot(0,
                "minecraft:diamond_hoe", 1, 1, Material.HOE, false, true, null, -1, false));
    }

    @Test
    void namedKeyFactsPreserveItemIdentityWhileSanitizingUntrustedDisplayText() {
        InventoryObservation.Slot key = new InventoryObservation.Slot(5, "minecraft:tripwire_hook", 5, 64,
                null, false, false, null, null, false,
                "Rare Key\n\t" + (char) 0 + "\u202e\u2028End", false);
        assertEquals("minecraft:tripwire_hook", key.itemId());
        assertEquals(5, key.count());
        assertEquals("Rare Key     End", key.displayName());
        assertEquals(Boolean.FALSE, key.plainDefaultComponents());
    }

    @Test
    void displayNamesAreBoundedWithoutSplittingUnicodePairs() {
        InventoryObservation.Slot longName = namedSlot("x".repeat(95) + "\ud83d\ude80extra", true);
        assertEquals("x".repeat(95), longName.displayName());
        assertEquals("Key \ud83d\ude80", namedSlot("Key\ud800\ud83d\ude80", true).displayName());
        assertEquals(96, namedSlot("<".repeat(10_000), false).displayName().length());
    }

    @Test
    void legacyAndEmptySlotsDoNotInventDefaultComponentFacts() {
        InventoryObservation.Slot legacy = new InventoryObservation.Slot(0, "minecraft:dirt", 1, 64,
                Material.DIRT, true, false, null, null, false);
        assertEquals(null, legacy.displayName());
        assertEquals(null, legacy.plainDefaultComponents());
        assertEquals("", InventoryObservation.Slot.empty(0).displayName());
        assertEquals(null, InventoryObservation.Slot.empty(0).plainDefaultComponents());
        assertEquals(Boolean.TRUE, namedSlot("Tripwire Hook", true).plainDefaultComponents());
        assertThrows(IllegalArgumentException.class, () -> new InventoryObservation.Slot(0,
                "minecraft:air", 0, 0, null, false, false, null, null, false, "", true));
    }

    @Test
    void equippedAndHeldNonHoeItemsReportObservedDurabilityWithoutBecomingBuildMaterials() {
        InventoryObservation.Slot boots = new InventoryObservation.Slot(36, "minecraft:diamond_boots", 1, 1,
                null, false, false, 73, 429, false, "Diamond Boots", false);
        assertEquals(356, boots.remainingDurability());
        assertTrue(boots.hasDurabilityFacts());
        assertEquals(null, boots.material());
        InventoryObservation.Slot pickaxe = new InventoryObservation.Slot(0, "minecraft:diamond_pickaxe", 1, 1,
                null, false, false, 1_561, 1_561, false);
        assertEquals(0, pickaxe.remainingDurability());
        assertEquals(null, new InventoryObservation.Slot(0, "minecraft:elytra", 1, 1,
                null, false, false, null, 432, false).remainingDurability());
        assertEquals(null, new InventoryObservation.Slot(0, "minecraft:elytra", 1, 1,
                null, false, false, 73, 432, true).remainingDurability());
        assertThrows(IllegalArgumentException.class, () -> new InventoryObservation.Slot(36,
                "minecraft:diamond_boots", 1, 1, null, false, false, -1, 429, false));
    }

    @Test
    void additionalFactsAreImmutableAndRequireCompleteOrderedArmorAndCapacity() {
        InventoryObservation base = sampleInventory();
        ArrayList<InventoryObservation.Slot> armor = emptyArmor();
        java.util.TreeMap<Material, Long> capacity = new java.util.TreeMap<>();
        InventoryObservation.CAPACITY_MATERIALS.forEach(material -> capacity.put(material, 0L));
        InventoryObservation observed = base.withEquipmentAndCapacity(armor, capacity);
        armor.set(0, InventoryObservation.Slot.empty(40));
        capacity.put(Material.DIRT, 10L);
        assertEquals(36, observed.armorSlots().get(0).slot());
        assertEquals(0, observed.normalMaterialCapacity().get(Material.DIRT));
        assertThrows(UnsupportedOperationException.class, () -> observed.armorSlots().clear());
        assertThrows(UnsupportedOperationException.class, () -> observed.normalMaterialCapacity().clear());
        assertThrows(IllegalArgumentException.class, () -> base.withEquipmentAndCapacity(armor, capacity));
        assertThrows(IllegalArgumentException.class,
                () -> base.withEquipmentAndCapacity(emptyArmor().subList(0, 3), capacity));
        assertThrows(IllegalArgumentException.class,
                () -> base.withEquipmentAndCapacity(emptyArmor(), Map.of(Material.DIRT, 10L)));
        capacity.put(Material.DIRT, -1L);
        assertThrows(IllegalArgumentException.class, () -> base.withEquipmentAndCapacity(emptyArmor(), capacity));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryObservation.unavailable("offline").withEquipmentAndCapacity(emptyArmor(), null));
    }

    @Test
    void legacyAndUnavailableCapturesKeepNewFactsUnknownRatherThanClaimingEmptyEquipment() {
        for (InventoryObservation inventory : List.of(sampleInventory(), InventoryObservation.unavailable("offline"))) {
            assertEquals(null, inventory.armorSlots());
            assertEquals(null, inventory.normalMaterialCapacity());
        }
    }

    @Test
    void compatibleCapacityExcludesProtectedItemsBlockedSlotsAndOverfullStacksAndHonorsLimits() {
        ArrayList<InventoryObservation.CapacitySlot> slots = new ArrayList<>();
        for (int index = 0; index < 36; index++) {
            slots.add(new InventoryObservation.CapacitySlot(index, 0, 64, true, false));
        }
        slots.set(0, new InventoryObservation.CapacitySlot(0, 20, 64, true, true));
        slots.set(1, new InventoryObservation.CapacitySlot(1, 5, 64, true, false));
        slots.set(2, new InventoryObservation.CapacitySlot(2, 0, 64, false, false));
        slots.set(3, new InventoryObservation.CapacitySlot(3, 0, 16, true, false));
        slots.set(4, new InventoryObservation.CapacitySlot(4, 64, 16, true, true));
        assertEquals(31L * 64 + 44 + 16, InventoryObservation.compatibleCapacity(slots));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryObservation.compatibleCapacity(slots.subList(0, 35)));
        slots.set(35, new InventoryObservation.CapacitySlot(0, 0, 64, true, false));
        assertThrows(IllegalArgumentException.class, () -> InventoryObservation.compatibleCapacity(slots));
    }

    static ArrayList<InventoryObservation.Slot> emptyArmor() {
        ArrayList<InventoryObservation.Slot> armor = new ArrayList<>();
        for (int index = 36; index < 40; index++) { armor.add(InventoryObservation.Slot.empty(index)); }
        return armor;
    }

    private static InventoryObservation.Slot namedSlot(String name, Boolean plain) {
        return new InventoryObservation.Slot(0, "minecraft:tripwire_hook", 5, 64,
                null, false, false, null, null, false, name, plain);
    }

    static InventoryObservation sampleInventory() {
        ArrayList<InventoryObservation.Slot> main = new ArrayList<>();
        for (int index = 0; index < 36; index++) { main.add(InventoryObservation.Slot.empty(index)); }
        main.set(0, new InventoryObservation.Slot(0, "minecraft:dirt", 32, 64,
                Material.DIRT, true, false, null, null, false));
        main.set(1, new InventoryObservation.Slot(1, "minecraft:dirt", 16, 64,
                Material.DIRT, false, false, null, null, false));
        main.set(2, new InventoryObservation.Slot(2, "minecraft:diamond_hoe", 1, 1,
                Material.HOE, false, true, 10, 1_561, false));
        main.set(3, new InventoryObservation.Slot(3, "minecraft:bread", 5, 64,
                Material.FOOD, false, false, null, null, false));
        InventoryObservation.Slot offhand = new InventoryObservation.Slot(40, "minecraft:dirt", 16, 64,
                Material.DIRT, true, false, null, null, false);
        InventoryObservation.Slot cursor = new InventoryObservation.Slot(-1, "minecraft:wheat_seeds", 12, 64,
                Material.WHEAT_SEEDS, false, false, null, null, false);
        return InventoryObservation.capture(List.copyOf(main), offhand, 2, 64,
                new InventoryObservation.Menu(true, "container", "Shop", 7, cursor));
    }
}
