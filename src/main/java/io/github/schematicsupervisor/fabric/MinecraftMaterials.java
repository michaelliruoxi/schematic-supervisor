package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Maps items and blocks to materials. Besides the built-ins, a stack counts as a block material only
 * while the loaded plan places that block, so other blocks the player carries never become supplies.
 */
final class MinecraftMaterials {
    // Block materials of the loaded plan; read on the client thread and replaced whole on load.
    private static volatile Set<Material> planBlocks = Set.of();

    private MinecraftMaterials() {
    }

    /** The loaded plan's block materials beyond the built-ins; an empty collection when none is loaded. */
    static void trackPlanBlocks(Collection<Material> materials) {
        planBlocks = materials.stream().filter(material -> !material.builtIn() && material.placedAsBlock())
                .collect(Collectors.toUnmodifiableSet());
    }

    static Set<Material> planBlocks() {
        return planBlocks;
    }

    static Optional<Material> classify(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.isOf(Items.DIRT)) {
            return Optional.of(Material.DIRT);
        }
        if (stack.isOf(Items.WHEAT_SEEDS)) {
            return Optional.of(Material.WHEAT_SEEDS);
        }
        if (stack.isOf(Items.GLOWSTONE)) {
            return Optional.of(Material.GLOWSTONE);
        }
        if (stack.isOf(Items.BIRCH_PLANKS)) {
            return Optional.of(Material.BIRCH_PLANKS);
        }
        if (isUsableHoe(stack)) {
            return Optional.of(Material.HOE);
        }
        if (stack.contains(DataComponentTypes.FOOD)) {
            return Optional.of(Material.FOOD);
        }
        Set<Material> tracked = planBlocks;
        if (!tracked.isEmpty() && stack.getItem() instanceof BlockItem) {
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            for (Material material : tracked) {
                if (material.itemId().equals(itemId)) {
                    return Optional.of(material);
                }
            }
        }
        return Optional.empty();
    }

    static boolean isHoe(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof HoeItem;
    }

    static boolean isUsableHoe(ItemStack stack) {
        return HoeUsability.usable(isHoe(stack), stack.contains(DataComponentTypes.UNBREAKABLE),
                stack.contains(DataComponentTypes.DAMAGE) ? stack.getDamage() : null,
                stack.contains(DataComponentTypes.MAX_DAMAGE) ? stack.getMaxDamage() : null);
    }

    static boolean matches(ItemStack stack, Material material) {
        return classify(stack).filter(material::equals).isPresent();
    }

    /** Stacks that supply the material when selected; food is never selected for building. */
    static Predicate<ItemStack> stackPredicate(Material material) {
        if (material.equals(Material.HOE)) {
            return MinecraftMaterials::isUsableHoe;
        }
        if (material.equals(Material.FOOD)) {
            return stack -> false;
        }
        Item item = item(material);
        return stack -> stack.isOf(item);
    }

    /** The block a block material places. */
    static Block block(Material material) {
        if (!material.placedAsBlock()) {
            throw new IllegalArgumentException("Not an ordinary block material: " + material);
        }
        if (material.equals(Material.DIRT)) { return Blocks.DIRT; }
        if (material.equals(Material.GLOWSTONE)) { return Blocks.GLOWSTONE; }
        if (material.equals(Material.BIRCH_PLANKS)) { return Blocks.BIRCH_PLANKS; }
        Block block = Registries.BLOCK.get(Identifier.of(material.itemId()));
        if (block == Blocks.AIR) {
            throw new IllegalArgumentException("Unknown block material: " + material);
        }
        return block;
    }

    /** The item that supplies a block material or the seeds. */
    static Item item(Material material) {
        if (material.equals(Material.DIRT)) { return Items.DIRT; }
        if (material.equals(Material.WHEAT_SEEDS)) { return Items.WHEAT_SEEDS; }
        if (material.equals(Material.GLOWSTONE)) { return Items.GLOWSTONE; }
        if (material.equals(Material.BIRCH_PLANKS)) { return Items.BIRCH_PLANKS; }
        if (material.itemId().isBlank()) {
            throw new IllegalArgumentException("The " + material.jsonName() + " category has no single item");
        }
        Item item = Registries.ITEM.get(Identifier.of(material.itemId()));
        if (item == Items.AIR) {
            throw new IllegalArgumentException("Unknown material item: " + material);
        }
        return item;
    }

    static int maximumStackSize(Material material) {
        if (material.equals(Material.HOE)) { return 1; }
        if (material.equals(Material.FOOD)) { return 64; }
        return item(material).getMaxCount();
    }

    static MaterialQuantities count(Inventory inventory) {
        TreeMap<Material, Long> counts = new TreeMap<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            classify(stack).ifPresent(material ->
                    counts.merge(material, (long) stack.getCount(), Math::addExact)
            );
        }
        return MaterialQuantities.of(counts);
    }
}
