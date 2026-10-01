package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only inventory facts; slot indices refer to the real player inventory, never GUI slots. */
record InventoryObservation(boolean available, List<Slot> mainSlots, Slot offhand,
                            Integer selectedHotbarSlot, Integer emptyMainSlots, Long dirtCapacity,
                            MaterialQuantities mainMaterialTotals,
                            MaterialQuantities mainAndOffhandMaterialTotals, Menu menu, String error,
                            List<Slot> armorSlots, Map<Material, Long> normalMaterialCapacity) {
    static final List<Material> CAPACITY_MATERIALS = List.of(Material.DIRT, Material.GLOWSTONE,
            Material.BIRCH_PLANKS, Material.WHEAT_SEEDS);

    InventoryObservation {
        mainSlots = List.copyOf(mainSlots);
        armorSlots = armorSlots == null ? null : List.copyOf(armorSlots);
        normalMaterialCapacity = normalMaterialCapacity == null ? null : Map.copyOf(normalMaterialCapacity);
        error = error == null ? "" : error;
        if (available) {
            if (mainSlots.size() != 36 || offhand == null || offhand.slot() != 40
                    || selectedHotbarSlot == null || selectedHotbarSlot < 0 || selectedHotbarSlot > 8
                    || emptyMainSlots == null || emptyMainSlots < 0 || emptyMainSlots > 36
                    || dirtCapacity == null || dirtCapacity < 0
                    || mainMaterialTotals == null || mainAndOffhandMaterialTotals == null || menu == null) {
                throw new IllegalArgumentException("inventory observation requires complete main-slot facts");
            }
            for (int index = 0; index < mainSlots.size(); index++) {
                if (mainSlots.get(index).slot() != index) {
                    throw new IllegalArgumentException("inventory main slots must be ordered zero through thirty-five");
                }
            }
            if (armorSlots != null) {
                if (armorSlots.size() != 4) { throw new IllegalArgumentException("armor requires four slots"); }
                for (int index = 0; index < armorSlots.size(); index++) {
                    if (armorSlots.get(index).slot() != index + 36) {
                        throw new IllegalArgumentException("armor slots must be ordered thirty-six through thirty-nine");
                    }
                }
            }
            if (normalMaterialCapacity != null
                    && (!normalMaterialCapacity.keySet().equals(java.util.Set.copyOf(CAPACITY_MATERIALS))
                    || normalMaterialCapacity.values().stream().anyMatch(value -> value < 0))) {
                throw new IllegalArgumentException("material capacity requires complete nonnegative build-item facts");
            }
        } else if (!mainSlots.isEmpty() || offhand != null || selectedHotbarSlot != null
                || emptyMainSlots != null || dirtCapacity != null || mainMaterialTotals != null
                || mainAndOffhandMaterialTotals != null || menu != null || armorSlots != null
                || normalMaterialCapacity != null) {
            throw new IllegalArgumentException("unavailable inventory must not report guessed facts");
        }
    }

    InventoryObservation(boolean available, List<Slot> mainSlots, Slot offhand,
                         Integer selectedHotbarSlot, Integer emptyMainSlots, Long dirtCapacity,
                         MaterialQuantities mainMaterialTotals,
                         MaterialQuantities mainAndOffhandMaterialTotals, Menu menu, String error) {
        this(available, mainSlots, offhand, selectedHotbarSlot, emptyMainSlots, dirtCapacity,
                mainMaterialTotals, mainAndOffhandMaterialTotals, menu, error, null, null);
    }

    InventoryObservation withEquipmentAndCapacity(List<Slot> armor, Map<Material, Long> capacity) {
        return new InventoryObservation(available, mainSlots, offhand, selectedHotbarSlot, emptyMainSlots,
                dirtCapacity, mainMaterialTotals, mainAndOffhandMaterialTotals, menu, error, armor, capacity);
    }

    static InventoryObservation unavailable(String error) {
        return new InventoryObservation(false, List.of(), null, null, null, null, null, null, null, error);
    }

    static InventoryObservation capture(List<Slot> main, Slot offhand, int selectedSlot,
                                        int normalDirtMaxCount, Menu menu) {
        if (normalDirtMaxCount < 1) { throw new IllegalArgumentException("dirt stack size must be positive"); }
        int empty = 0;
        long capacity = 0;
        for (Slot slot : main) {
            if (slot.count() == 0) {
                empty++;
                capacity = Math.addExact(capacity, normalDirtMaxCount);
            } else if (slot.normalDirtStackable()) {
                capacity = Math.addExact(capacity, Math.max(0, slot.maxCount() - slot.count()));
            }
        }
        MaterialQuantities mainTotals = totals(main);
        return new InventoryObservation(true, main, offhand, selectedSlot, empty, capacity,
                mainTotals, mainTotals.plus(totals(List.of(offhand))), menu, "");
    }

    static MaterialQuantities totals(List<Slot> slots) {
        TreeMap<Material, Long> totals = new TreeMap<>();
        for (Slot slot : slots) {
            if (slot.material() != null && slot.count() > 0) {
                totals.merge(slot.material(), slot.material() == Material.HOE ? 1L : slot.count(), Math::addExact);
            }
        }
        return MaterialQuantities.of(totals);
    }

    record Slot(int slot, String itemId, int count, int maxCount, Material material,
                boolean normalDirtStackable, boolean hoe, Integer damage, Integer maxDamage,
                boolean unbreakable, String displayName, Boolean plainDefaultComponents) {
        static final int MAX_DISPLAY_NAME_LENGTH = 96;

        Slot {
            Objects.requireNonNull(itemId, "itemId");
            displayName = boundedDisplayName(displayName);
            if (slot < -1 || slot > 40 || itemId.length() > 256
                    || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || count < 0 || maxCount < 0
                    || (count > 0 && maxCount == 0)) {
                throw new IllegalArgumentException("invalid inventory slot facts");
            }
            if (count == 0 && (!itemId.equals("minecraft:air") || material != null
                    || normalDirtStackable || hoe || damage != null || maxDamage != null || unbreakable
                    || (displayName != null && !displayName.isEmpty()) || plainDefaultComponents != null)) {
                throw new IllegalArgumentException("empty inventory slot must not describe an item");
            }
            if (normalDirtStackable && !itemId.equals("minecraft:dirt")) {
                throw new IllegalArgumentException("only normal dirt contributes dirt stack room");
            }
            if ((damage != null && damage < 0) || (maxDamage != null && maxDamage < 0)) {
                throw new IllegalArgumentException("present durability facts must be nonnegative");
            }
        }

        Slot(int slot, String itemId, int count, int maxCount, Material material,
             boolean normalDirtStackable, boolean hoe, Integer damage, Integer maxDamage,
             boolean unbreakable) {
            this(slot, itemId, count, maxCount, material, normalDirtStackable, hoe, damage, maxDamage,
                    unbreakable, null, null);
        }

        static Slot empty(int index) {
            return new Slot(index, "minecraft:air", 0, 0, null, false, false, null, null, false, "", null);
        }

        Integer remainingDurability() {
            return unbreakable || damage == null || maxDamage == null || maxDamage == 0
                    ? null : Math.max(0, maxDamage - damage);
        }

        boolean hasDurabilityFacts() {
            return hoe || damage != null || maxDamage != null || unbreakable;
        }

        /** Item labels are untrusted display data, never action instructions or item identity. */
        private static String boundedDisplayName(String value) {
            if (value == null) { return null; }
            StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_DISPLAY_NAME_LENGTH));
            for (int offset = 0; offset < value.length() && result.length() < MAX_DISPLAY_NAME_LENGTH;) {
                int codePoint = value.codePointAt(offset);
                offset += Character.charCount(codePoint);
                int type = Character.getType(codePoint);
                if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                        || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                    codePoint = ' ';
                }
                if (result.length() + Character.charCount(codePoint) > MAX_DISPLAY_NAME_LENGTH) { break; }
                result.appendCodePoint(codePoint);
            }
            return result.toString().strip();
        }
    }

    /** One observed handler slot for a single normal item; shared room is not an allocation. */
    record CapacitySlot(int slot, int count, int maximum, boolean insertable, boolean compatible) {
        CapacitySlot {
            if (slot < 0 || slot >= 36 || count < 0 || maximum < 0) {
                throw new IllegalArgumentException("invalid capacity slot facts");
            }
        }
    }

    static long compatibleCapacity(List<CapacitySlot> slots) {
        if (slots.size() != 36) { throw new IllegalArgumentException("capacity requires all main slots"); }
        long capacity = 0;
        for (int index = 0; index < slots.size(); index++) {
            CapacitySlot slot = slots.get(index);
            if (slot.slot() != index) { throw new IllegalArgumentException("capacity slots are not ordered"); }
            if (slot.insertable() && (slot.count() == 0 || slot.compatible())) {
                capacity = Math.addExact(capacity, Math.max(0, slot.maximum() - slot.count()));
            }
        }
        return capacity;
    }

    record Menu(boolean open, String kind, String title, Integer syncId, Slot cursor) {
        Menu {
            Objects.requireNonNull(kind, "kind");
            title = title == null ? "" : title;
            Objects.requireNonNull(cursor, "cursor");
            if (cursor.slot() != -1 || title.length() > 128
                    || !List.of("none", "inventory", "container").contains(kind)) {
                throw new IllegalArgumentException("invalid inventory menu facts");
            }
        }
    }
}
