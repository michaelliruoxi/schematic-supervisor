package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShovelItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;

/** Passive family, identity and speed facts shared by selection, custody and durable repair. */
final class MinecraftClearingTools {
    private MinecraftClearingTools() { }

    static ClearingToolSelection.Family family(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !"minecraft".equals(Registries.ITEM.getId(stack.getItem()).getNamespace())) {
            return ClearingToolSelection.Family.OTHER;
        }
        if (stack.getItem() instanceof HoeItem) { return ClearingToolSelection.Family.HOE; }
        if (stack.getItem() instanceof ShovelItem) { return ClearingToolSelection.Family.SHOVEL; }
        return stack.getItem() instanceof AxeItem ? ClearingToolSelection.Family.AXE : ClearingToolSelection.Family.OTHER;
    }

    static boolean supportedFamily(ItemStack stack) { return family(stack) != ClearingToolSelection.Family.OTHER; }

    static boolean admitted(ItemStack stack) {
        if (!supportedFamily(stack) || !stack.contains(DataComponentTypes.DAMAGE)
                || !stack.contains(DataComponentTypes.MAX_DAMAGE)) {
            return false;
        }
        var tool = stack.get(DataComponentTypes.TOOL);
        return tool != null && ClearingToolSelection.admitted(family(stack),
                LegacyToolDamageIdentity.canCompare(stack.get(DataComponentTypes.CUSTOM_DATA), stack.getDamage()),
                stack.getCount(), tool.damagePerBlock(), stack.getDamage(), stack.getMaxDamage());
    }

    static boolean usable(ItemStack stack, BlockState state) {
        if (state == null || !admitted(stack)) { return false; }
        return ClearingToolSelection.eligible(family(stack), Registries.BLOCK.getId(state.getBlock()).toString(),
                true, stack.getCount(), stack.get(DataComponentTypes.TOOL).damagePerBlock(),
                stack.getDamage(), stack.getMaxDamage(), stack.contains(DataComponentTypes.UNBREAKABLE),
                stack.getMiningSpeedMultiplier(state));
    }

    static ItemStack identity(ItemStack stack) {
        if (!admitted(stack)) { throw new IllegalArgumentException("Clearing tool identity is unavailable"); }
        ItemStack copy = stack.copy();
        var custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom != null) { copy.set(DataComponentTypes.CUSTOM_DATA, LegacyToolDamageIdentity.normalize(custom, stack.getDamage())); }
        copy.set(DataComponentTypes.DAMAGE, 0);
        return copy;
    }

    static MossMiningToolGuard.Durability durability(ItemStack stack) {
        return new MossMiningToolGuard.Durability(stack.getDamage(), stack.getMaxDamage(),
                stack.contains(DataComponentTypes.UNBREAKABLE));
    }

    /** Effective speed when all candidate attributes are known, otherwise base speed within the correct family. */
    static List<Integer> rankedSlots(ClientPlayerEntity player, BlockState state) {
        List<ClearingToolSelection.Candidate> candidates = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!usable(stack, state)) { continue; }
            double score = Double.NaN;
            try {
                score = ClearingToolSelection.score(stack.getMiningSpeedMultiplier(state),
                        equippedValue(player, stack, EntityAttributes.MINING_EFFICIENCY),
                        equippedValue(player, stack, EntityAttributes.BLOCK_BREAK_SPEED),
                        player.isSubmergedIn(FluidTags.WATER),
                        equippedValue(player, stack, EntityAttributes.SUBMERGED_MINING_SPEED));
            } catch (RuntimeException unavailable) {
                // A stale selected-hand attribute packet must not exclude an otherwise safe appropriate tool.
            }
            candidates.add(new ClearingToolSelection.Candidate(slot, family(stack), true,
                    stack.getMiningSpeedMultiplier(state), score));
        }
        return ClearingToolSelection.ranked(Registries.BLOCK.getId(state.getBlock()).toString(), candidates);
    }

    private static double equippedValue(ClientPlayerEntity player, ItemStack candidate,
                                        RegistryEntry<EntityAttribute> attribute) {
        EntityAttributeInstance current = player.getAttributeInstance(attribute);
        if (current == null) { throw new IllegalStateException("Mining attribute is unavailable"); }
        EntityAttributeInstance copy = new EntityAttributeInstance(attribute, ignored -> { });
        copy.setFrom(current);
        AtomicInteger inspected = new AtomicInteger();
        player.getMainHandStack().applyAttributeModifiers(EquipmentSlot.MAINHAND, (key, modifier) -> {
            if (inspected.incrementAndGet() > 128) { throw new IllegalStateException("Too many held tool modifiers"); }
            if (!key.equals(attribute)) { return; }
            if (!modifier.equals(copy.getModifier(modifier.id()))) {
                throw new IllegalStateException("Held tool attributes have not settled");
            }
            copy.removeModifier(modifier.id());
        });
        candidate.applyAttributeModifiers(EquipmentSlot.MAINHAND, (key, modifier) -> {
            if (inspected.incrementAndGet() > 256) { throw new IllegalStateException("Too many candidate tool modifiers"); }
            if (!key.equals(attribute)) { return; }
            if (copy.hasModifier(modifier.id())) { throw new IllegalStateException("Ambiguous candidate attribute ownership"); }
            copy.addTemporaryModifier(modifier);
        });
        return copy.getValue();
    }
}
