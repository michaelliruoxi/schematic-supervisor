package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

final class MossToolMetadataProbeTest {
    @Test
    void compoundAndListPathsAreBoundedTypedAndDeterministic() {
        NbtCompound data = new NbtCompound();
        NbtCompound tool = new NbtCompound();
        tool.putInt("Damage", 30);
        NbtList history = new NbtList();
        history.add(NbtInt.of(29));
        history.add(NbtInt.of(30));
        tool.put("history", history);
        data.put("plugin:tool|state", tool);

        var result = MossToolMetadataProbe.capture(data, 30);

        assertTrue(result.available());
        assertTrue(result.complete());
        assertFalse(result.truncated());
        assertEquals("ok", result.reason());
        assertEquals(3, result.leaves().size());
        assertEquals(Boolean.TRUE, leaf(result, "plugin:tool|state", "Damage").equalsItemDamage());
        assertEquals(Boolean.FALSE, leaf(result, "plugin:tool|state", "history", "[0]").equalsItemDamage());
        assertEquals(Boolean.TRUE, leaf(result, "plugin:tool|state", "history", "[1]").equalsItemDamage());
        assertEquals(result, MossToolMetadataProbe.capture(NbtComponent.of(data), 30));
        assertTrue(result.leaves().stream().allMatch(value -> value.sha256().matches("[0-9a-f]{64}")));
    }

    @Test
    void changedLeafHasDistinctHashWhileOtherMetadataRemainsStable() {
        NbtCompound data = new NbtCompound();
        data.putInt("wear", 30);
        data.putString("label", "private-tool-label-921");
        var before = MossToolMetadataProbe.capture(data, 30);
        data.putInt("wear", 31);
        var after = MossToolMetadataProbe.capture(data, 31);

        assertNotEquals(leaf(before, "wear").sha256(), leaf(after, "wear").sha256());
        assertEquals(leaf(before, "label").sha256(), leaf(after, "label").sha256());
        assertEquals(Boolean.TRUE, leaf(before, "wear").equalsItemDamage());
        assertEquals(Boolean.TRUE, leaf(after, "wear").equalsItemDamage());
        assertFalse(before.toString().contains("private-tool-label-921"));
        assertNull(leaf(before, "label").equalsItemDamage());
    }

    @Test
    void numericTypeIsBoundAndOnlyIntegralScalarTypesCompareWithItemDamage() {
        NbtCompound data = new NbtCompound();
        data.putByte("byte", (byte) 4);
        data.putShort("short", (short) 4);
        data.putInt("int", 4);
        data.putLong("long", 4L);
        data.putFloat("float", 4.0F);
        data.putDouble("double", 4.0);
        data.putInt("other", 3);
        data.putLong("large", 4L + (1L << 32));
        var result = MossToolMetadataProbe.capture(data, 4);

        for (String key : List.of("byte", "short", "int", "long")) {
            assertEquals(Boolean.TRUE, leaf(result, key).equalsItemDamage());
        }
        assertEquals(Boolean.FALSE, leaf(result, "other").equalsItemDamage());
        assertEquals(Boolean.FALSE, leaf(result, "large").equalsItemDamage());
        assertNull(leaf(result, "float").equalsItemDamage());
        assertNull(leaf(result, "double").equalsItemDamage());
        assertEquals(6, result.leaves().stream().filter(value -> !value.path().contains("other")
                && !value.path().contains("large")).map(MossToolMetadataProbe.Leaf::sha256).distinct().count());
    }

    @Test
    void arrayTypesAndStringUtf16UnitsRemainDistinctWithoutValuesInOutput() {
        NbtCompound data = new NbtCompound();
        data.putByteArray("bytes", new byte[]{1, 2});
        data.putIntArray("ints", new int[]{1, 2});
        data.putLongArray("longs", new long[]{1, 2});
        data.putString("surrogate", "\ud800");
        data.putString("replacement", "?");
        var result = MossToolMetadataProbe.capture(data, 1);

        assertTrue(result.complete());
        assertEquals(5, result.leaves().stream().map(MossToolMetadataProbe.Leaf::sha256).distinct().count());
        assertTrue(result.leaves().stream().allMatch(value -> value.equalsItemDamage() == null));
    }

    @Test
    void unsafeKeysInvalidateOnlyTheirBranchesAndNeverAppearInOutput() {
        NbtCompound data = new NbtCompound();
        data.putString("private raw key\n", "private raw value");
        data.putInt("[0]", 12);
        data.putInt("safe_damage", 12);
        var result = MossToolMetadataProbe.capture(data, 12);

        assertTrue(result.available());
        assertFalse(result.complete());
        assertFalse(result.truncated());
        assertEquals("unsupported_key", result.reason());
        assertEquals(List.of("safe_damage"), result.leaves().getFirst().path());
        assertEquals(1, result.leaves().size());
        assertFalse(result.toString().contains("private raw"));
    }

    @Test
    void oversizedStringsArraysMembersAndLeafCountsAreExplicitlyTruncated() {
        NbtCompound text = new NbtCompound();
        text.putString("value", "private-large".repeat(100));
        assertTruncated(MossToolMetadataProbe.capture(text, 1));
        NbtCompound array = new NbtCompound();
        array.putIntArray("value", new int[65]);
        assertTruncated(MossToolMetadataProbe.capture(array, 1));
        NbtCompound members = new NbtCompound();
        for (int index = 0; index < 65; index++) { members.putInt("key" + index, index); }
        assertTruncated(MossToolMetadataProbe.capture(members, 1));
        NbtList longList = new NbtList();
        for (int index = 0; index < 65; index++) { longList.add(NbtInt.of(index)); }
        NbtCompound lists = new NbtCompound();
        lists.put("value", longList);
        assertTruncated(MossToolMetadataProbe.capture(lists, 1));
        NbtCompound leaves = new NbtCompound();
        for (int index = 0; index < 25; index++) { leaves.putInt("key" + index, index); }
        var result = MossToolMetadataProbe.capture(leaves, 1);
        assertTruncated(result);
        assertEquals(MossToolMetadataProbe.MAX_LEAVES, result.leaves().size());
    }

    @Test
    void depthAndPathBudgetsRejectHiddenSubtreesWithoutRenderingTheirKeys() {
        NbtCompound depth = chain(9, "child");
        assertTruncated(MossToolMetadataProbe.capture(depth, 1));
        NbtCompound path = chain(4, "k".repeat(64));
        assertTruncated(MossToolMetadataProbe.capture(path, 1));
        NbtCompound key = new NbtCompound();
        key.putInt("private_key_".repeat(8), 1);
        key.putInt("safe", 1);
        var result = MossToolMetadataProbe.capture(key, 1);
        assertTruncated(result);
        assertEquals(1, result.leaves().size());
        assertFalse(result.toString().contains("private_key_"));
    }

    @Test
    void sharedNodeAndHashedByteBudgetsCannotBeBypassedByManySmallLeaves() {
        NbtCompound nodes = new NbtCompound();
        for (int index = 0; index < 5; index++) { nodes.putByteArray("array" + index, new byte[64]); }
        assertTruncated(MossToolMetadataProbe.capture(nodes, 1));
        NbtCompound bytes = new NbtCompound();
        for (int index = 0; index < 20; index++) { bytes.putString("value" + index, "x".repeat(1_024)); }
        assertTruncated(MossToolMetadataProbe.capture(bytes, 1));
    }

    @Test
    void snapshotsAndPathsAreImmutableAndConstructorBoundsKeepOutputRedacted() {
        String hash = "a".repeat(64);
        ArrayList<String> path = new ArrayList<>(List.of("wear"));
        var leaf = new MossToolMetadataProbe.Leaf(path, NbtElement.INT_TYPE, hash, true);
        path.set(0, "changed");
        assertEquals(List.of("wear"), leaf.path());
        assertThrows(UnsupportedOperationException.class, () -> leaf.path().add("extra"));
        ArrayList<MossToolMetadataProbe.Leaf> leaves = new ArrayList<>();
        for (int index = 0; index < 30; index++) { leaves.add(leaf); }
        var result = new MossToolMetadataProbe.Snapshot(true, true, false, "private reason", leaves);
        leaves.clear();
        assertTruncated(result);
        assertEquals(24, result.leaves().size());
        assertThrows(UnsupportedOperationException.class, () -> result.leaves().clear());
        assertFalse(result.toString().contains("private reason"));
        assertThrows(IllegalArgumentException.class,
                () -> new MossToolMetadataProbe.Leaf(List.of("private raw key"), NbtElement.INT_TYPE, hash, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MossToolMetadataProbe.Leaf(List.of("wear"), NbtElement.INT_TYPE, "private value", true));
        assertNull(new MossToolMetadataProbe.Leaf(List.of("float"), NbtElement.FLOAT_TYPE, hash, true).equalsItemDamage());
    }

    @Test
    void absentAndInvalidDamageAreUnavailableWhileEmptyMetadataIsComplete() {
        assertEquals("absent", MossToolMetadataProbe.capture((NbtCompound) null, 1).reason());
        assertFalse(MossToolMetadataProbe.capture((NbtComponent) null, 1).available());
        assertEquals("invalid_item_damage", MossToolMetadataProbe.capture(new NbtCompound(), -1).reason());
        var empty = MossToolMetadataProbe.capture(new NbtCompound(), 0);
        assertTrue(empty.available());
        assertTrue(empty.complete());
        assertTrue(empty.leaves().isEmpty());
    }

    private static NbtCompound chain(int depth, String key) {
        NbtCompound root = new NbtCompound();
        NbtCompound cursor = root;
        for (int index = 0; index < depth; index++) {
            NbtCompound child = new NbtCompound();
            cursor.put(key, child);
            cursor = child;
        }
        cursor.putInt("value", 1);
        return root;
    }

    private static MossToolMetadataProbe.Leaf leaf(MossToolMetadataProbe.Snapshot snapshot, String... path) {
        return snapshot.leaves().stream().filter(value -> value.path().equals(List.of(path))).findFirst().orElseThrow();
    }

    private static void assertTruncated(MossToolMetadataProbe.Snapshot value) {
        assertTrue(value.available());
        assertFalse(value.complete());
        assertTrue(value.truncated());
        assertEquals("limit_exceeded", value.reason());
        assertTrue(value.leaves().size() <= MossToolMetadataProbe.MAX_LEAVES);
    }
}
