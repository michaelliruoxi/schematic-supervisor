package io.github.schematicsupervisor.fabric;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.minecraft.component.Component;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.EnchantableComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.RepairableComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

/** Bounded diagnostic hashes only; unsupported components never imply a complete identity. */
final class MinecraftMossToolFingerprint {
    static final int MAX_COMPONENTS = 24;
    static final int MAX_DEPTH = 8;
    static final int MAX_NODES = 256;
    static final int MAX_STRING_UNITS = 1_024;
    static final int MAX_MEMBERS = 64;
    static final int MAX_BYTES = 32_768;

    private MinecraftMossToolFingerprint() { }

    record ComponentHash(String id, String sha256) {
        ComponentHash {
            if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("Invalid component fingerprint identifier");
            }
            requireHash(sha256);
        }
    }
    record Fingerprint(String sha256, boolean complete, List<ComponentHash> components, boolean truncated) {
        Fingerprint {
            requireHash(sha256);
            if (components == null) {
                components = List.of();
                truncated = true;
            }
            if (components.size() > MAX_COMPONENTS) { truncated = true; }
            ArrayList<ComponentHash> bounded = new ArrayList<>();
            boolean allHashed = true;
            for (int index = 0; index < Math.min(components.size(), MAX_COMPONENTS); index++) {
                ComponentHash component = components.get(index);
                if (component == null) {
                    throw new IllegalArgumentException("Missing component fingerprint");
                }
                bounded.add(component);
                allHashed &= component.sha256() != null;
            }
            components = List.copyOf(bounded);
            complete = complete && sha256 != null && allHashed && !truncated;
            if (!complete) { sha256 = null; }
        }
    }

    private static void requireHash(String hash) {
        if (hash != null && (hash.length() != 64 || !hash.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Invalid component fingerprint hash");
        }
    }

    /** The caller supplies its existing damage-normalized identity; this method never changes it. */
    static Fingerprint capture(ItemStack normalizedIdentity) {
        if (normalizedIdentity == null || normalizedIdentity.isEmpty()) {
            return new Fingerprint(null, false, List.of(), false);
        }
        ArrayList<NamedComponent> components = new ArrayList<>();
        boolean truncated = false;
        try {
            var iterator = normalizedIdentity.getComponents().iterator();
            while (iterator.hasNext()) {
                if (components.size() == MAX_COMPONENTS) { truncated = true; break; }
                Component<?> component = iterator.next();
                Identifier id = Registries.DATA_COMPONENT_TYPE.getId(component.type());
                if (id == null || id.getNamespace().length() + id.getPath().length() + 1 > 128) {
                    truncated = true;
                    break;
                }
                components.add(new NamedComponent(id.toString(), component.value()));
            }
            Identifier itemId = Registries.ITEM.getId(normalizedIdentity.getItem());
            if (itemId == null || itemId.getNamespace().length() + itemId.getPath().length() + 1 > 128) {
                return new Fingerprint(null, false, List.of(), true);
            }
            Fingerprint result = capture(itemId.toString(), normalizedIdentity.getCount(), components);
            return new Fingerprint(result.sha256(), result.complete(), result.components(),
                    truncated || result.truncated());
        } catch (RuntimeException unavailable) {
            return new Fingerprint(null, false, List.of(), truncated);
        }
    }

    /** Shared bounded capture core; registry extraction stays in the client-thread adapter above. */
    static Fingerprint capture(String itemId, int count, List<NamedComponent> components) {
        if (itemId == null || itemId.length() > 128 || !itemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                || count < 1 || components == null) {
            return new Fingerprint(null, false, List.of(), false);
        }
        Budget budget = new Budget();
        ArrayList<NamedComponent> values = new ArrayList<>();
        ArrayList<ComponentHash> hashes = new ArrayList<>();
        boolean all = true;
        try {
            budget.truncated = components.size() > MAX_COMPONENTS;
            for (int index = 0; index < Math.min(components.size(), MAX_COMPONENTS); index++) {
                NamedComponent component = components.get(index);
                if (component == null || component.id() == null || component.id().length() > 128
                        || !component.id().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                    budget.truncated = true;
                    all = false;
                    break;
                }
                values.add(component);
            }
            values.sort(Comparator.comparing(NamedComponent::id));
            for (NamedComponent component : values) {
                String hash = null;
                try {
                    Writer writer = new Writer(budget);
                    writer.string("moss-tool-component-v1");
                    writer.string(component.id());
                    writer.value(component.value(), 0);
                    hash = writer.finish();
                } catch (Unavailable | RuntimeException unavailable) {
                    all = false;
                }
                hashes.add(new ComponentHash(component.id(), hash));
            }
            if (all && !budget.truncated) {
                Writer writer = new Writer(budget);
                writer.string("moss-tool-identity-v1");
                writer.value(Identifier.of(itemId), 0);
                writer.value(count, 0);
                writer.number(hashes.size());
                for (ComponentHash hash : hashes) {
                    writer.string(hash.id());
                    writer.string(hash.sha256());
                }
                return new Fingerprint(writer.finish(), true, hashes, false);
            }
        } catch (Unavailable | RuntimeException unavailable) {
            all = false;
        }
        return new Fingerprint(null, false, hashes, budget.truncated);
    }

    record NamedComponent(String id, Object value) {
        @Override public String toString() { return "NamedComponent[unrendered]"; }
    }

    // Pinned 1.21.8 exposes its backing NBT only through this deprecated direct accessor.
    // copyNbt()/apply() recursively copy before our limits run; this reference is read only.
    @SuppressWarnings("deprecation")
    private static NbtCompound readOnlyNbt(NbtComponent component) { return component.getNbt(); }

    private static final class Budget {
        private int nodes;
        private int bytes;
        private boolean truncated;

        private void node(int depth) throws Unavailable {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) { limit(); }
        }

        private void bytes(int amount) throws Unavailable {
            if (amount < 0 || amount > MAX_BYTES - bytes) { limit(); }
            bytes += amount;
        }

        private void members(int size) throws Unavailable {
            if (size < 0 || size > MAX_MEMBERS) { limit(); }
        }

        private void limit() throws Unavailable {
            truncated = true;
            throw new Unavailable();
        }
    }

    private static final class Writer {
        private final Budget budget;
        private final MessageDigest digest;

        private Writer(Budget budget) throws Unavailable {
            this.budget = budget;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException unavailable) { throw new Unavailable(); }
        }

        private String finish() { return HexFormat.of().formatHex(digest.digest()); }

        private void octet(int value) throws Unavailable {
            budget.bytes(1);
            digest.update((byte) value);
        }

        private void number(long value) throws Unavailable {
            for (int shift = 56; shift >= 0; shift -= 8) { octet((int) (value >>> shift)); }
        }

        private void string(String value) throws Unavailable {
            if (value == null) { throw new Unavailable(); }
            if (value.length() > MAX_STRING_UNITS) { budget.limit(); }
            number(value.length());
            // Preserve every UTF-16 unit, including isolated surrogates, without rendering the value.
            for (int index = 0; index < value.length(); index++) {
                char unit = value.charAt(index);
                octet(unit >>> 8);
                octet(unit);
            }
        }

        private void value(Object value, int depth) throws Unavailable {
            budget.node(depth);
            if (value == null) { throw new Unavailable(); }
            if (value instanceof String text) { octet(1); string(text); }
            else if (value instanceof Boolean flag) { octet(2); octet(flag ? 1 : 0); }
            else if (value instanceof Byte number) { octet(3); number(number); }
            else if (value instanceof Short number) { octet(4); number(number); }
            else if (value instanceof Integer number) { octet(5); number(number); }
            else if (value instanceof Long number) { octet(6); number(number); }
            else if (value instanceof Float number) { octet(7); number(Float.floatToIntBits(number)); }
            else if (value instanceof Double number) { octet(8); number(Double.doubleToLongBits(number)); }
            else if (value instanceof Identifier id) { octet(9); string(id.getNamespace()); string(id.getPath()); }
            else if (value instanceof Enum<?> constant) {
                octet(10); string(constant.getDeclaringClass().getName()); string(constant.name());
            } else if (value instanceof NbtComponent nbt) { octet(11); value(readOnlyNbt(nbt), depth + 1); }
            else if (value instanceof NbtElement nbt) { octet(12); nbt(nbt, depth); }
            else if (value instanceof RegistryKey<?> key) {
                octet(13); value(key.getRegistry(), depth + 1); value(key.getValue(), depth + 1);
            } else if (value instanceof TagKey<?> tag) {
                octet(14); value(tag.registryRef(), depth + 1); value(tag.id(), depth + 1);
            } else if (value instanceof RegistryEntry<?> entry) {
                octet(15); value(entry.getKey().orElseThrow(Unavailable::new), depth + 1);
            } else if (value instanceof RegistryEntryList<?> entries) {
                octet(16);
                Optional<? extends TagKey<?>> tag = entries.getTagKey();
                octet(tag.isPresent() ? 1 : 0);
                if (tag.isPresent()) { value(tag.get(), depth + 1); }
                else {
                    budget.members(entries.size());
                    number(entries.size());
                    for (int index = 0; index < entries.size(); index++) { value(entries.get(index), depth + 1); }
                }
            } else if (value instanceof ToolComponent tool) {
                octet(17); list(tool.rules(), depth);
                value(tool.defaultMiningSpeed(), depth + 1);
                value(tool.damagePerBlock(), depth + 1);
                value(tool.canDestroyBlocksInCreative(), depth + 1);
            } else if (value instanceof ToolComponent.Rule rule) {
                octet(18); value(rule.blocks(), depth + 1);
                optional(rule.speed(), depth); optional(rule.correctForDrops(), depth);
            } else if (value instanceof RepairableComponent repairable) {
                octet(19); value(repairable.items(), depth + 1);
            } else if (value instanceof EnchantableComponent enchantable) {
                octet(20); value(enchantable.value(), depth + 1);
            } else if (value instanceof ItemEnchantmentsComponent enchantments) {
                octet(21); budget.members(enchantments.getSize());
                ArrayList<Enchantment> sorted = new ArrayList<>();
                for (var entry : enchantments.getEnchantmentEntries()) {
                    if (sorted.size() == MAX_MEMBERS) { budget.limit(); }
                    RegistryKey<?> key = entry.getKey().getKey().orElseThrow(Unavailable::new);
                    Identifier id = key.getValue();
                    if (id.getNamespace().length() + id.getPath().length() + 1 > 128) { budget.limit(); }
                    sorted.add(new Enchantment(id.toString(), key, entry.getIntValue()));
                }
                sorted.sort(Comparator.comparing(Enchantment::id));
                number(sorted.size());
                for (Enchantment enchantment : sorted) {
                    value(enchantment.key(), depth + 1); value(enchantment.level(), depth + 1);
                }
            } else if (value instanceof CustomModelDataComponent model) {
                octet(22); list(model.floats(), depth); list(model.flags(), depth);
                list(model.strings(), depth); list(model.colors(), depth);
            } else { throw new Unavailable(); }
        }

        private record Enchantment(String id, RegistryKey<?> key, int level) { }

        private void optional(Optional<?> value, int depth) throws Unavailable {
            octet(value.isPresent() ? 1 : 0);
            if (value.isPresent()) { value(value.get(), depth + 1); }
        }

        private void list(List<?> values, int depth) throws Unavailable {
            budget.members(values.size());
            number(values.size());
            for (Object value : values) { value(value, depth + 1); }
        }

        private void nbt(NbtElement value, int depth) throws Unavailable {
            octet(value.getType());
            if (value instanceof NbtCompound compound) {
                budget.members(compound.getSize());
                ArrayList<String> keys = new ArrayList<>();
                for (String key : compound.getKeys()) {
                    if (keys.size() == MAX_MEMBERS || key.length() > MAX_STRING_UNITS) { budget.limit(); }
                    keys.add(key);
                }
                keys.sort(String::compareTo);
                number(keys.size());
                for (String key : keys) { string(key); value(compound.get(key), depth + 1); }
            } else if (value instanceof NbtList list) { list(list, depth); }
            else if (value instanceof NbtString text) { string(text.value()); }
            else if (value instanceof AbstractNbtNumber number) {
                switch (value.getType()) {
                    case NbtElement.BYTE_TYPE, NbtElement.SHORT_TYPE, NbtElement.INT_TYPE, NbtElement.LONG_TYPE ->
                            number(number.longValue());
                    case NbtElement.FLOAT_TYPE -> number(Float.floatToIntBits(number.floatValue()));
                    case NbtElement.DOUBLE_TYPE -> number(Double.doubleToLongBits(number.doubleValue()));
                    default -> throw new Unavailable();
                }
            } else if (value instanceof NbtByteArray array) {
                budget.members(array.size()); number(array.size());
                for (byte element : array.getByteArray()) { budget.node(depth + 1); octet(element); }
            } else if (value instanceof NbtIntArray array) {
                budget.members(array.size()); number(array.size());
                for (int element : array.getIntArray()) { budget.node(depth + 1); number(element); }
            } else if (value instanceof NbtLongArray array) {
                budget.members(array.size()); number(array.size());
                for (long element : array.getLongArray()) { budget.node(depth + 1); number(element); }
            } else if (value.getType() != NbtElement.END_TYPE) { throw new Unavailable(); }
        }
    }

    private static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;
        private Unavailable() { super("Tool component fingerprint unavailable", null, false, false); }
    }
}
