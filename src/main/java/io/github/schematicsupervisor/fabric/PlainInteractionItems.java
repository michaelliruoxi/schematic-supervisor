package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/** Chooses an empty hand or ordinary build supplies without using unrelated items. */
final class PlainInteractionItems {
    private PlainInteractionItems() { }

    static Predicate<ItemStack> plainBlock(Material material) {
        if (!material.placedAsBlock()) {
            throw new IllegalArgumentException("Not an ordinary build block: " + material);
        }
        ItemStack expected = new ItemStack(MinecraftMaterials.item(material));
        return stack -> !stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, expected);
    }

    static OptionalInt findHotbarSlot(PlayerInventory inventory, Material... preferredMaterials) {
        List<IntPredicate> preferred = new ArrayList<>(preferredMaterials.length);
        for (Material material : preferredMaterials) {
            Predicate<ItemStack> matches = plainBlock(material);
            preferred.add(index -> matches.test(inventory.getStack(index)));
        }
        return chooseSlot(PlayerInventory.getHotbarSize(),
                index -> inventory.getStack(index).isEmpty(), preferred);
    }

    /** Stem attack predicate; does not grant block-use permission. */
    static boolean safeStemHand(ItemStack stack) {
        return stack.isEmpty() || plainBuildOrPickup(stack);
    }

    static boolean safeStemHand(String itemId, boolean empty, boolean defaultComponents) {
        if (empty) { return true; }
        if (!defaultComponents || itemId == null) { return false; }
        return switch (itemId) {
            case "minecraft:dirt", "minecraft:glowstone", "minecraft:birch_planks",
                    "minecraft:pumpkin_seeds", "minecraft:melon_seeds", "minecraft:moss_block",
                    "minecraft:jack_o_lantern" -> true;
            default -> false;
        };
    }

    static OptionalInt findStemHotbarSlot(PlayerInventory inventory) {
        return chooseStemSlot(PlayerInventory.getHotbarSize(), inventory.getSelectedSlot(),
                index -> safeStemHand(inventory.getStack(index)));
    }

    static boolean safeDepotHand(ItemStack stack) {
        return stack.isEmpty()
                || DepotInteractionHandPolicy.allowsItem(Registries.ITEM.getId(stack.getItem()).toString(),
                        false, ItemStack.areItemsAndComponentsEqual(stack, new ItemStack(stack.getItem())));
    }

    /** Nonempty item classification only; it grants neither use nor block-interaction permission. */
    static boolean plainBuildOrPickup(ItemStack stack) {
        return !stack.isEmpty()
                && safeStemHand(Registries.ITEM.getId(stack.getItem()).toString(), false, true)
                && ItemStack.areItemsAndComponentsEqual(stack, new ItemStack(stack.getItem()));
    }

    static OptionalInt findDepotHotbarSlot(PlayerInventory inventory) {
        // Same deterministic selected-first hotbar walk, with a separate chest-only item policy.
        return chooseStemSlot(PlayerInventory.getHotbarSize(), inventory.getSelectedSlot(),
                index -> safeDepotHand(inventory.getStack(index)));
    }

    static OptionalInt chooseStemSlot(int slotCount, int selectedSlot, IntPredicate safe) {
        if (slotCount < 0 || slotCount > 9) { throw new IllegalArgumentException("Invalid hotbar size"); }
        if (selectedSlot >= 0 && selectedSlot < slotCount && safe.test(selectedSlot)) {
            return OptionalInt.of(selectedSlot);
        }
        for (int index = 0; index < slotCount; index++) {
            if (index != selectedSlot && safe.test(index)) { return OptionalInt.of(index); }
        }
        return OptionalInt.empty();
    }

    static OptionalInt chooseSlot(int slotCount, IntPredicate empty, List<IntPredicate> preferredPlain) {
        if (slotCount < 0) { throw new IllegalArgumentException("Negative slot count"); }
        for (IntPredicate matches : preferredPlain) {
            for (int index = 0; index < slotCount; index++) {
                if (matches.test(index)) { return OptionalInt.of(index); }
            }
        }
        for (int index = 0; index < slotCount; index++) {
            if (empty.test(index)) { return OptionalInt.of(index); }
        }
        return OptionalInt.empty();
    }

    /** One hotbar-only recovery per moss-clearing attempt; never selects another empty slot. */
    static final class MossPickupReselection {
        private boolean attempted;

        OptionalInt chooseSlot(boolean plainMossPickup, int slotCount, IntPredicate plainReplacement) {
            if (attempted || !plainMossPickup) { return OptionalInt.empty(); }
            attempted = true;
            return PlainInteractionItems.chooseSlot(slotCount, index -> false, List.of(plainReplacement));
        }
    }
}
