package io.github.schematicsupervisor.fabric;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtByteArray;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLongArray;
import net.minecraft.nbt.NbtString;

/** Local custom-data diagnostics only; values are hashed and never rendered or retained. */
final class MossToolMetadataProbe {
    static final int MAX_DEPTH = 8;
    static final int MAX_NODES = 256;
    static final int MAX_LEAVES = 24;
    static final int MAX_BYTES = 32_768;
    static final int MAX_STRING_UNITS = 1_024;
    static final int MAX_MEMBERS = 64;
    static final int MAX_KEY_UNITS = 64;
    static final int MAX_PATH_UNITS = 256;
    private static final Set<String> REASONS = Set.of("ok", "absent", "invalid_item_damage",
            "unsupported_key", "unsupported_type", "limit_exceeded", "unavailable");

    private MossToolMetadataProbe() { }

    /** Bracketed index segments cannot alias compound keys, whose allowed alphabet excludes brackets. */
    record Leaf(List<String> path, int type, String sha256, Boolean equalsItemDamage) {
        Leaf {
            if (path == null || path.isEmpty() || path.size() > MAX_DEPTH) {
                throw new IllegalArgumentException("Invalid metadata leaf path");
            }
            int units = 0;
            ArrayList<String> bounded = new ArrayList<>();
            for (String segment : path) {
                if (!safeKey(segment) && !safeIndex(segment)) {
                    throw new IllegalArgumentException("Invalid metadata leaf path");
                }
                units += segment.length() + 1;
                if (units > MAX_PATH_UNITS) { throw new IllegalArgumentException("Invalid metadata leaf path"); }
                bounded.add(segment);
            }
            path = List.copyOf(bounded);
            if (!leafType(type) || sha256 == null || sha256.length() != 64 || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid metadata leaf fingerprint");
            }
            if (!integral(type)) { equalsItemDamage = null; }
        }
    }

    record Snapshot(boolean available, boolean complete, boolean truncated, String reason, List<Leaf> leaves) {
        Snapshot {
            reason = reason != null && reason.length() <= 32 && REASONS.contains(reason) ? reason : "unavailable";
            if (leaves == null) { leaves = List.of(); complete = false; reason = "unavailable"; }
            if (leaves.size() > MAX_LEAVES) { truncated = true; }
            ArrayList<Leaf> bounded = new ArrayList<>();
            for (int index = 0; index < Math.min(leaves.size(), MAX_LEAVES); index++) {
                Leaf leaf = leaves.get(index);
                if (leaf == null) { throw new IllegalArgumentException("Missing metadata leaf"); }
                bounded.add(leaf);
            }
            leaves = available ? List.copyOf(bounded) : List.of();
            if (truncated) { reason = "limit_exceeded"; }
            complete = available && complete && !truncated && reason.equals("ok");
            if (!complete && reason.equals("ok")) { reason = "unavailable"; }
        }
    }

    static Snapshot capture(NbtComponent component, int actualDamage) {
        if (actualDamage < 0) { return unavailable("invalid_item_damage"); }
        if (component == null) { return unavailable("absent"); }
        try { return capture(readOnlyNbt(component), actualDamage); }
        catch (RuntimeException unavailable) { return unavailable("unavailable"); }
    }

    static Snapshot capture(NbtCompound compound, int actualDamage) {
        if (actualDamage < 0) { return unavailable("invalid_item_damage"); }
        if (compound == null) { return unavailable("absent"); }
        Walker walker = new Walker(actualDamage);
        try { walker.visit(compound, List.of(), 0, 0); }
        catch (LimitReached limit) { walker.problem("limit_exceeded", true); }
        catch (Unavailable | RuntimeException unavailable) { walker.problem("unavailable", false); }
        return new Snapshot(true, walker.complete, walker.truncated, walker.reason, walker.leaves);
    }

    private static Snapshot unavailable(String reason) { return new Snapshot(false, false, false, reason, List.of()); }

    // Pinned 1.21.8's direct read avoids copyNbt()/apply(), which copy the whole payload before limits run.
    @SuppressWarnings("deprecation")
    private static NbtCompound readOnlyNbt(NbtComponent component) { return component.getNbt(); }

    private static boolean safeKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_UNITS
                && key.matches("[A-Za-z0-9_.:|\\-]+");
    }

    private static boolean safeIndex(String index) {
        if (index == null || index.length() < 3 || index.length() > 4 || !index.matches("\\[(0|[1-9][0-9]?)\\]")) {
            return false;
        }
        return Integer.parseInt(index.substring(1, index.length() - 1)) < MAX_MEMBERS;
    }

    private static boolean integral(int type) { return type >= NbtElement.BYTE_TYPE && type <= NbtElement.LONG_TYPE; }

    private static boolean leafType(int type) {
        return type >= NbtElement.END_TYPE && type <= NbtElement.STRING_TYPE
                || type == NbtElement.INT_ARRAY_TYPE || type == NbtElement.LONG_ARRAY_TYPE;
    }

    private static final class Walker {
        private final int actualDamage;
        private final ArrayList<Leaf> leaves = new ArrayList<>();
        private int nodes;
        private int bytes;
        private boolean complete = true;
        private boolean truncated;
        private String reason = "ok";

        private Walker(int actualDamage) { this.actualDamage = actualDamage; }

        private void problem(String detail, boolean limit) {
            complete = false;
            truncated |= limit;
            if (reason.equals("ok") || limit) { reason = detail; }
        }

        private void node(int depth) throws LimitReached {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) { throw new LimitReached(); }
        }

        private void members(int count) throws LimitReached {
            if (count < 0 || count > MAX_MEMBERS) { throw new LimitReached(); }
        }

        private void visit(NbtElement value, List<String> path, int depth, int pathUnits)
                throws LimitReached, Unavailable {
            node(depth);
            if (value == null) { throw new Unavailable(); }
            if (value instanceof NbtCompound compound) {
                members(compound.getSize());
                ArrayList<String> keys = new ArrayList<>();
                for (String key : compound.getKeys()) {
                    if (keys.size() == MAX_MEMBERS) { throw new LimitReached(); }
                    if (!safeKey(key)) {
                        problem(key != null && key.length() > MAX_KEY_UNITS ? "limit_exceeded" : "unsupported_key",
                                key != null && key.length() > MAX_KEY_UNITS);
                        continue;
                    }
                    keys.add(key);
                }
                keys.sort(String::compareTo);
                for (String key : keys) { child(compound.get(key), path, key, depth, pathUnits); }
                return;
            }
            if (value instanceof NbtList list) {
                members(list.size());
                for (int index = 0; index < list.size(); index++) {
                    child(list.get(index), path, "[" + index + "]", depth, pathUnits);
                }
                return;
            }
            if (!leafType(value.getType())) { problem("unsupported_type", false); return; }
            if (leaves.size() == MAX_LEAVES) { throw new LimitReached(); }
            Hasher hash = new Hasher();
            hash.octet(value.getType());
            Boolean equalsDamage = null;
            if (value instanceof AbstractNbtNumber number) {
                switch (value.getType()) {
                    case NbtElement.BYTE_TYPE, NbtElement.SHORT_TYPE, NbtElement.INT_TYPE, NbtElement.LONG_TYPE -> {
                        hash.number(number.longValue());
                        equalsDamage = number.longValue() == actualDamage;
                    }
                    case NbtElement.FLOAT_TYPE -> hash.number(Float.floatToIntBits(number.floatValue()));
                    case NbtElement.DOUBLE_TYPE -> hash.number(Double.doubleToLongBits(number.doubleValue()));
                    default -> throw new Unavailable();
                }
            } else if (value instanceof NbtString text) { hash.string(text.value()); }
            else if (value instanceof NbtByteArray array) {
                members(array.size()); hash.number(array.size());
                for (byte element : array.getByteArray()) { node(depth + 1); hash.octet(element); }
            } else if (value instanceof NbtIntArray array) {
                members(array.size()); hash.number(array.size());
                for (int element : array.getIntArray()) { node(depth + 1); hash.number(element); }
            } else if (value instanceof NbtLongArray array) {
                members(array.size()); hash.number(array.size());
                for (long element : array.getLongArray()) { node(depth + 1); hash.number(element); }
            } else if (value.getType() != NbtElement.END_TYPE) { throw new Unavailable(); }
            leaves.add(new Leaf(path, value.getType(), hash.finish(), equalsDamage));
        }

        private void child(NbtElement value, List<String> path, String segment, int depth, int pathUnits)
                throws LimitReached, Unavailable {
            int nextUnits = pathUnits + segment.length() + 1;
            if (depth == MAX_DEPTH || nextUnits > MAX_PATH_UNITS) {
                problem("limit_exceeded", true);
                return;
            }
            ArrayList<String> next = new ArrayList<>(path);
            next.add(segment);
            visit(value, next, depth + 1, nextUnits);
        }

        private final class Hasher {
            private final MessageDigest digest;

            private Hasher() throws Unavailable {
                try { digest = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException unavailable) { throw new Unavailable(); }
            }

            private void octet(int value) throws LimitReached {
                if (++bytes > MAX_BYTES) { throw new LimitReached(); }
                digest.update((byte) value);
            }

            private void number(long value) throws LimitReached {
                for (int shift = 56; shift >= 0; shift -= 8) { octet((int) (value >>> shift)); }
            }

            private void string(String value) throws LimitReached {
                if (value.length() > MAX_STRING_UNITS) { throw new LimitReached(); }
                number(value.length());
                for (int index = 0; index < value.length(); index++) {
                    char unit = value.charAt(index);
                    octet(unit >>> 8);
                    octet(unit);
                }
            }

            private String finish() { return HexFormat.of().formatHex(digest.digest()); }
        }
    }

    private static final class LimitReached extends Exception {
        private static final long serialVersionUID = 1L;
        private LimitReached() { super("Metadata probe limit reached", null, false, false); }
    }

    private static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;
        private Unavailable() { super("Metadata probe unavailable", null, false, false); }
    }
}
