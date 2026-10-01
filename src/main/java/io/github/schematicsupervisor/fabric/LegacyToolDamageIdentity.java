package io.github.schematicsupervisor.fabric;

import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;

/** Normalizes only the recognized legacy duplicate of the current tool damage for identity comparison. */
final class LegacyToolDamageIdentity {
    private static final String MARKER = "VV|Protocol1_20_3To1_20_5";
    private static final String DAMAGE = "Damage";

    private LegacyToolDamageIdentity() { }

    /** Check before admitting a new comparison or repair identity; normalization alone is not admission. */
    static boolean canCompare(NbtComponent component, int actualDamage) {
        if (actualDamage < 0) { return false; }
        if (component == null) { return true; }
        try { return canCompare(readOnlyNbt(component), actualDamage); }
        catch (RuntimeException unavailable) { return false; }
    }

    static boolean canCompare(NbtCompound compound, int actualDamage) {
        if (actualDamage < 0) { return false; }
        if (compound == null) { return true; }
        try {
            // Ordinary/unrecognized metadata keeps the existing exact comparison behavior.
            return !recognized(compound) || applicable(compound, actualDamage);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static NbtComponent normalize(NbtComponent component, int actualDamage) {
        if (component == null || actualDamage < 0) { return component; }
        try {
            if (!applicable(readOnlyNbt(component), actualDamage) || actualDamage == 0) { return component; }
            // apply() copies the validated payload before invoking the mutation on that copy.
            return component.apply(copy -> copy.putInt(DAMAGE, 0));
        } catch (RuntimeException unavailable) {
            return component;
        }
    }

    static NbtCompound normalize(NbtCompound compound, int actualDamage) {
        if (compound == null || actualDamage < 0) { return compound; }
        try {
            if (!applicable(compound, actualDamage) || actualDamage == 0) { return compound; }
            NbtCompound normalized = compound.copy();
            normalized.putInt(DAMAGE, 0);
            return normalized;
        } catch (RuntimeException unavailable) {
            return compound;
        }
    }

    private static boolean applicable(NbtCompound compound, int actualDamage) {
        if (!recognized(compound) || !(compound.get(DAMAGE) instanceof NbtInt duplicate)
                || duplicate.intValue() != actualDamage) {
            return false;
        }
        // Traversal validates every node, including empty containers, before any full copy.
        // Its leaf list is not used as an identity; the copied NBT retains all other tags exactly.
        MossToolMetadataProbe.Snapshot bounds = MossToolMetadataProbe.capture(compound, actualDamage);
        return bounds.available() && bounds.complete() && !bounds.truncated();
    }

    private static boolean recognized(NbtCompound compound) {
        return compound.get(MARKER) instanceof NbtByte marker && marker.byteValue() == 1;
    }

    // The pinned direct getter avoids an unbounded copy before the traversal checks have run.
    @SuppressWarnings("deprecation")
    private static NbtCompound readOnlyNbt(NbtComponent component) { return component.getNbt(); }
}
