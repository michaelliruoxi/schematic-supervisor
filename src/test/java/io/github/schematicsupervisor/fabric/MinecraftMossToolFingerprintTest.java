package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;

final class MinecraftMossToolFingerprintTest {
    @Test
    void suppliedNormalizedDamageIsStableAndCaptureDoesNotMutateValues() {
        var first = new MinecraftMossToolFingerprint.NamedComponent("minecraft:damage", 30);
        var second = new MinecraftMossToolFingerprint.NamedComponent("minecraft:damage", 31);
        assertNotEquals(capture(List.of(first)).sha256(), capture(List.of(second)).sha256());
        var normalized = new MinecraftMossToolFingerprint.NamedComponent("minecraft:damage", 0);
        var tool = new MinecraftMossToolFingerprint.NamedComponent("minecraft:tool",
                new ToolComponent(List.of(), 1.0F, 1, true));
        List<MinecraftMossToolFingerprint.NamedComponent> values = List.of(normalized, tool);
        var result = capture(values);
        assertTrue(result.complete());
        assertEquals(result, capture(List.of(tool, normalized)));
        assertNotNull(component(result, "minecraft:tool"));
        assertEquals(30, first.value());
        assertEquals(31, second.value());
        assertEquals(0, normalized.value());
    }

    @Test
    void completeIdentityBindsItemCountAndEverySupportedComponent() {
        var first = scalarComponents();
        var original = capture(first);
        assertTrue(original.complete());
        assertTrue(original.sha256().matches("[0-9a-f]{64}"));
        assertEquals(original, capture(new ArrayList<>(first)));
        var damaged = new ArrayList<>(first);
        damaged.set(0, new MinecraftMossToolFingerprint.NamedComponent("minecraft:damage", 1));
        assertNotEquals(original.sha256(), capture(damaged).sha256());
        assertNotEquals(original.sha256(), MinecraftMossToolFingerprint.capture("minecraft:diamond_hoe", 2, first).sha256());
        assertNotEquals(original.sha256(), MinecraftMossToolFingerprint.capture("minecraft:iron_hoe", 1, first).sha256());
    }

    @Test
    void customDataUsesCanonicalKeyOrderAndChangesWithoutExposingPrivateValues() {
        NbtCompound firstData = new NbtCompound();
        firstData.putString("private-key", "private-content-42");
        firstData.putInt("level", 4);
        NbtCompound sameData = new NbtCompound();
        sameData.putInt("level", 4);
        sameData.putString("private-key", "private-content-42");
        var first = dataFingerprint(firstData);
        assertTrue(first.complete());
        assertEquals(first, dataFingerprint(sameData));
        sameData.putInt("level", 5);
        var changed = dataFingerprint(sameData);
        assertNotEquals(first.sha256(), changed.sha256());
        assertNotEquals(component(first, "minecraft:custom_data"), component(changed, "minecraft:custom_data"));
        assertFalse(first.toString().contains("private-key"));
        assertFalse(first.toString().contains("private-content"));
    }

    @Test
    void typedNbtAndEveryUtf16UnitHaveDistinctHashes() {
        NbtCompound integer = new NbtCompound();
        integer.putInt("value", 1);
        NbtCompound longValue = new NbtCompound();
        longValue.putLong("value", 1);
        assertNotEquals(dataFingerprint(integer).sha256(), dataFingerprint(longValue).sha256());
        NbtCompound surrogate = new NbtCompound();
        surrogate.putString("value", "\ud800");
        NbtCompound replacement = new NbtCompound();
        replacement.putString("value", "?");
        assertNotEquals(dataFingerprint(surrogate).sha256(), dataFingerprint(replacement).sha256());
    }

    @Test
    void unsupportedValuesAreUnavailableWithoutCallingTheirRenderingOrHashMethods() {
        var values = new ArrayList<>(scalarComponents());
        values.add(new MinecraftMossToolFingerprint.NamedComponent("minecraft:custom_name", new NeverRender()));
        values.add(new MinecraftMossToolFingerprint.NamedComponent("minecraft:lore", new NeverRender()));
        var result = capture(values);

        assertFalse(result.complete());
        assertNull(result.sha256());
        assertFalse(result.truncated());
        assertTrue(result.components().stream().anyMatch(value -> value.id().equals("minecraft:custom_name")));
        assertNull(component(result, "minecraft:custom_name"));
        assertNull(component(result, "minecraft:lore"));
        assertNotNull(component(result, "minecraft:damage"));
        assertFalse(result.toString().contains("private-name"));
        assertFalse(result.toString().contains("private-lore"));
    }

    @Test
    void oversizedStringArrayCollectionAndDepthProduceOnlyUnavailableHashes() {
        NbtCompound text = new NbtCompound();
        text.putString("value", "private-large".repeat(100));
        assertTruncated(dataFingerprint(text));
        NbtCompound array = new NbtCompound();
        array.putByteArray("value", new byte[65]);
        assertTruncated(dataFingerprint(array));
        NbtCompound collection = new NbtCompound();
        for (int index = 0; index < 65; index++) { collection.putInt("key" + index, index); }
        assertTruncated(dataFingerprint(collection));
        NbtCompound deep = new NbtCompound();
        NbtCompound cursor = deep;
        for (int index = 0; index < 9; index++) {
            NbtCompound child = new NbtCompound();
            cursor.put("child", child);
            cursor = child;
        }
        assertTruncated(dataFingerprint(deep));
    }

    @Test
    void sharedNodeAndByteBudgetsStopOtherwiseIndividuallySmallValues() {
        NbtCompound nodes = new NbtCompound();
        for (int outer = 0; outer < 5; outer++) {
            NbtList children = new NbtList();
            for (int inner = 0; inner < 60; inner++) { children.add(NbtString.of("bounded")); }
            nodes.put("list" + outer, children);
        }
        assertTruncated(dataFingerprint(nodes));
        NbtCompound bytes = new NbtCompound();
        for (int index = 0; index < 20; index++) { bytes.putString("key" + index, "x".repeat(1_024)); }
        assertTruncated(dataFingerprint(bytes));
    }

    @Test
    void componentCountHasAnExplicitCapAndCannotYieldACompleteSubsetIdentity() {
        var values = new ArrayList<MinecraftMossToolFingerprint.NamedComponent>();
        for (int index = 0; index < 30; index++) {
            values.add(new MinecraftMossToolFingerprint.NamedComponent("test:component_" + index, index));
        }
        var result = capture(values);
        assertFalse(result.complete());
        assertNull(result.sha256());
        assertTrue(result.truncated());
        assertEquals(MinecraftMossToolFingerprint.MAX_COMPONENTS, result.components().size());
    }

    @Test
    void invalidCoreInputsAndNullStackAreExplicitlyUnavailable() {
        assertEquals(new MinecraftMossToolFingerprint.Fingerprint(null, false, List.of(), false),
                MinecraftMossToolFingerprint.capture(null));
        assertFalse(MinecraftMossToolFingerprint.capture("private item text", 1, scalarComponents()).complete());
        assertFalse(MinecraftMossToolFingerprint.capture("minecraft:diamond_hoe", 0, scalarComponents()).complete());
        assertFalse(MinecraftMossToolFingerprint.capture("minecraft:diamond_hoe", 1, null).complete());
    }

    @Test
    void immutableRecordsEnforceBoundsAndNeverAcceptPayloadsAsHashesOrIdentifiers() {
        String hash = "a".repeat(64);
        var valid = new MinecraftMossToolFingerprint.ComponentHash("minecraft:damage", hash);
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftMossToolFingerprint.ComponentHash("private name", hash));
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftMossToolFingerprint.ComponentHash("minecraft:damage", "private-value"));
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftMossToolFingerprint.Fingerprint("private-value", true, List.of(valid), false));
        ArrayList<MinecraftMossToolFingerprint.ComponentHash> many = new ArrayList<>();
        for (int index = 0; index < 30; index++) { many.add(valid); }
        var bounded = new MinecraftMossToolFingerprint.Fingerprint(hash, true, many, false);
        assertEquals(MinecraftMossToolFingerprint.MAX_COMPONENTS, bounded.components().size());
        assertTrue(bounded.truncated());
        assertFalse(bounded.complete());
        assertNull(bounded.sha256());
        var unavailable = new MinecraftMossToolFingerprint.ComponentHash("minecraft:custom_data", null);
        assertFalse(new MinecraftMossToolFingerprint.Fingerprint(hash, true, List.of(unavailable), false).complete());
        assertNull(new MinecraftMossToolFingerprint.Fingerprint(hash, false, List.of(valid), false).sha256());
        assertFalse(new MinecraftMossToolFingerprint.Fingerprint(null, true, List.of(valid), false).complete());
        assertThrows(UnsupportedOperationException.class, () -> bounded.components().add(valid));
    }

    private static List<MinecraftMossToolFingerprint.NamedComponent> scalarComponents() {
        return List.of(new MinecraftMossToolFingerprint.NamedComponent("minecraft:damage", 0),
                new MinecraftMossToolFingerprint.NamedComponent("minecraft:max_damage", 1_561),
                new MinecraftMossToolFingerprint.NamedComponent("minecraft:max_stack_size", 1));
    }

    private static MinecraftMossToolFingerprint.Fingerprint dataFingerprint(NbtCompound data) {
        var components = new ArrayList<>(scalarComponents());
        components.add(new MinecraftMossToolFingerprint.NamedComponent("minecraft:custom_data", NbtComponent.of(data)));
        return capture(components);
    }

    private static MinecraftMossToolFingerprint.Fingerprint capture(
            List<MinecraftMossToolFingerprint.NamedComponent> components) {
        return MinecraftMossToolFingerprint.capture("minecraft:diamond_hoe", 1, components);
    }

    private static String component(MinecraftMossToolFingerprint.Fingerprint value, String id) {
        return value.components().stream().filter(component -> component.id().equals(id))
                .findFirst().orElseThrow().sha256();
    }

    private static void assertTruncated(MinecraftMossToolFingerprint.Fingerprint value) {
        assertFalse(value.complete());
        assertNull(value.sha256());
        assertTrue(value.truncated());
        assertNull(component(value, "minecraft:custom_data"));
    }

    private static final class NeverRender {
        @Override public String toString() { throw new AssertionError("Raw component value was rendered"); }
        @Override public int hashCode() { throw new AssertionError("Raw component value hashCode was called"); }
    }
}
