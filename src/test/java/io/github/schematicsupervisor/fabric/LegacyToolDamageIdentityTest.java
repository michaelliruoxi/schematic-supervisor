package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

final class LegacyToolDamageIdentityTest {
    @Test
    void recognizedWearValuesHaveExactlyEqualNormalizedNbtWithoutChangingInputs() {
        NbtCompound expected = legacy(0);
        for (int damage : new int[]{0, 1, 30, 153, 155, 156, 159, 1_559}) {
            NbtCompound input = legacy(damage);
            NbtCompound before = input.copy();
            NbtCompound result = LegacyToolDamageIdentity.normalize(input, damage);
            assertTrue(LegacyToolDamageIdentity.canCompare(input, damage));
            assertTrue(LegacyToolDamageIdentity.canCompare(NbtComponent.of(input), damage));
            assertEquals(expected, result);
            assertEquals(before, input);
            if (damage != 0) { assertNotSame(input, result); }
            assertEquals(NbtComponent.of(expected), LegacyToolDamageIdentity.normalize(NbtComponent.of(input), damage));
        }
    }

    @Test
    void everyOtherTagIncludingEmptyContainersAndNumericTypesRemainsPartOfIdentity() {
        NbtCompound input = legacy(159);
        NbtCompound baseline = LegacyToolDamageIdentity.normalize(input, 159);
        ArrayList<NbtCompound> changed = new ArrayList<>();
        NbtCompound changedName = input.copy();
        changedName.putString("name", "different-private-tool");
        changed.add(changedName);
        NbtCompound changedNested = input.copy();
        NbtCompound nested = new NbtCompound();
        nested.putInt("level", 7);
        changedNested.put("enchantment", nested);
        changed.add(changedNested);
        NbtCompound changedNumericType = input.copy();
        changedNumericType.putLong("serial", 42L);
        changed.add(changedNumericType);
        NbtCompound removedEmptyCompound = input.copy();
        removedEmptyCompound.remove("empty_compound");
        changed.add(removedEmptyCompound);
        NbtCompound removedEmptyList = input.copy();
        removedEmptyList.remove("empty_list");
        changed.add(removedEmptyList);
        NbtCompound extraTag = input.copy();
        extraTag.putInt("other", 1);
        changed.add(extraTag);
        for (NbtCompound variation : changed) {
            NbtCompound expected = variation.copy();
            expected.putInt("Damage", 0);
            NbtCompound normalized = LegacyToolDamageIdentity.normalize(variation, 159);
            assertEquals(expected, normalized);
            assertNotEquals(baseline, normalized);
        }
    }

    @Test
    void markerMustBeExactlyByteOneWithTheRecognizedName() {
        ArrayList<NbtCompound> inputs = new ArrayList<>();
        NbtCompound missing = legacy(153);
        missing.remove("VV|Protocol1_20_3To1_20_5");
        inputs.add(missing);
        NbtCompound zero = legacy(153);
        zero.putByte("VV|Protocol1_20_3To1_20_5", (byte) 0);
        inputs.add(zero);
        NbtCompound otherByte = legacy(153);
        otherByte.putByte("VV|Protocol1_20_3To1_20_5", (byte) 2);
        inputs.add(otherByte);
        NbtCompound integer = legacy(153);
        integer.putInt("VV|Protocol1_20_3To1_20_5", 1);
        inputs.add(integer);
        NbtCompound wrongName = legacy(153);
        wrongName.remove("VV|Protocol1_20_3To1_20_5");
        wrongName.putByte("VV|Protocol1_20_3To1_20_6", (byte) 1);
        inputs.add(wrongName);
        for (NbtCompound input : inputs) {
            assertUnchanged(input, 153);
            assertTrue(LegacyToolDamageIdentity.canCompare(input, 153));
        }
    }

    @Test
    void duplicateDamageMustBeAnExactMatchingTopLevelNonnegativeInt() {
        ArrayList<NbtCompound> inputs = new ArrayList<>();
        NbtCompound missing = legacy(30);
        missing.remove("Damage");
        inputs.add(missing);
        NbtCompound nestedOnly = missing.copy();
        NbtCompound nested = new NbtCompound();
        nested.putInt("Damage", 30);
        nestedOnly.put("nested", nested);
        inputs.add(nestedOnly);
        NbtCompound byteValue = legacy(30);
        byteValue.putByte("Damage", (byte) 30);
        inputs.add(byteValue);
        NbtCompound longValue = legacy(30);
        longValue.putLong("Damage", 30L);
        inputs.add(longValue);
        NbtCompound floatValue = legacy(30);
        floatValue.putFloat("Damage", 30.0F);
        inputs.add(floatValue);
        NbtCompound mismatch = legacy(31);
        inputs.add(mismatch);
        for (NbtCompound input : inputs) { assertRejected(input, 30); }
        assertRejected(legacy(-1), -1);
        assertRejected(legacy(-1), 30);
    }

    @Test
    void explicitZeroIsAlreadyCanonicalButMissingZeroDamageIsNeverInvented() {
        NbtCompound zero = legacy(0);
        assertSame(zero, LegacyToolDamageIdentity.normalize(zero, 0));
        NbtComponent component = NbtComponent.of(zero);
        assertSame(component, LegacyToolDamageIdentity.normalize(component, 0));
        NbtCompound missing = zero.copy();
        missing.remove("Damage");
        assertRejected(missing, 0);
        assertNotEquals(zero, LegacyToolDamageIdentity.normalize(missing, 0));
    }

    @Test
    void unsupportedAndOversizedPayloadsFailClosedBeforeAnyCopy() {
        ArrayList<NbtCompound> inputs = new ArrayList<>();
        NbtCompound unsafeKey = legacy(153);
        unsafeKey.putInt("private raw key", 1);
        inputs.add(unsafeKey);
        NbtCompound largeString = legacy(153);
        largeString.putString("value", "x".repeat(1_025));
        inputs.add(largeString);
        NbtCompound largeArray = legacy(153);
        largeArray.putByteArray("array", new byte[65]);
        inputs.add(largeArray);
        NbtCompound largeList = legacy(153);
        NbtList list = new NbtList();
        for (int index = 0; index < 65; index++) { list.add(NbtInt.of(index)); }
        largeList.put("list", list);
        inputs.add(largeList);
        NbtCompound deep = legacy(153);
        NbtCompound cursor = deep;
        for (int index = 0; index < 9; index++) {
            NbtCompound child = new NbtCompound();
            cursor.put("child", child);
            cursor = child;
        }
        inputs.add(deep);
        NbtCompound nodes = legacy(153);
        for (int index = 0; index < 5; index++) { nodes.putByteArray("array" + index, new byte[64]); }
        inputs.add(nodes);
        NbtCompound leaves = legacy(153);
        for (int index = 0; index < 25; index++) { leaves.putInt("value" + index, index); }
        inputs.add(leaves);
        for (NbtCompound input : inputs) { assertRejected(input, 153); }
    }

    @Test
    void normalizedCopyIsIndependentAndCannotMutateOriginalMetadata() {
        NbtCompound input = legacy(156);
        NbtCompound before = input.copy();
        NbtCompound result = LegacyToolDamageIdentity.normalize(input, 156);
        result.putString("name", "changed-after-normalization");
        result.putInt("Damage", 10);
        assertEquals(before, input);
        NbtComponent original = NbtComponent.of(before);
        NbtComponent normalized = LegacyToolDamageIdentity.normalize(original, 156);
        assertNotSame(original, normalized);
        assertEquals(NbtComponent.of(before), original);
    }

    @Test
    void absentPayloadIsPreserved() {
        assertSame(null, LegacyToolDamageIdentity.normalize((NbtCompound) null, 1));
        assertSame(null, LegacyToolDamageIdentity.normalize((NbtComponent) null, 1));
    }

    @Test
    void ordinaryMetadataKeepsExactComparisonWhileInvalidDamageCannotBeAdmitted() {
        NbtCompound ordinary = new NbtCompound();
        ordinary.putInt("Damage", 7);
        ordinary.putString("private raw key", "ordinary-exact-data");
        assertTrue(LegacyToolDamageIdentity.canCompare(ordinary, 153));
        assertTrue(LegacyToolDamageIdentity.canCompare(NbtComponent.of(ordinary), 153));
        assertUnchanged(ordinary, 153);
        assertTrue(LegacyToolDamageIdentity.canCompare((NbtCompound) null, 153));
        assertTrue(LegacyToolDamageIdentity.canCompare((NbtComponent) null, 153));
        assertFalse(LegacyToolDamageIdentity.canCompare(ordinary, -1));
        assertFalse(LegacyToolDamageIdentity.canCompare((NbtComponent) null, -1));
    }

    @Test
    void mismatchedRecognizedDamageCannotBecomeANewRawIdentityOrRepairIdentity() {
        NbtCompound staleMetadata = legacy(153);
        assertSame(staleMetadata, LegacyToolDamageIdentity.normalize(staleMetadata, 154));
        assertFalse(LegacyToolDamageIdentity.canCompare(staleMetadata, 154));
        assertFalse(LegacyToolDamageIdentity.canCompare(NbtComponent.of(staleMetadata), 154));
        staleMetadata.putInt("Damage", 154);
        assertTrue(LegacyToolDamageIdentity.canCompare(staleMetadata, 154));
        assertEquals(legacy(0), LegacyToolDamageIdentity.normalize(staleMetadata, 154));
        NbtCompound afterRepair = legacy(0);
        assertTrue(LegacyToolDamageIdentity.canCompare(afterRepair, 0));
        assertEquals(LegacyToolDamageIdentity.normalize(staleMetadata, 154),
                LegacyToolDamageIdentity.normalize(afterRepair, 0));
    }

    @Test
    void successiveLegacyWearKeepsOneGuardIdentityWithoutReplenishingAllowance() {
        var guard = new MossMiningToolGuard<NbtCompound>(NbtCompound::equals);
        var initialWear = new MossMiningToolGuard.Durability(100, 1_561, false);
        var tracked = guard.track(LegacyToolDamageIdentity.normalize(legacy(100), 100), initialWear).orElseThrow();
        int initialAllowance = tracked.allowance();
        MossMiningToolGuard.OwnedTool<NbtCompound> lastOwned = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            int damage = 100 + attempt;
            NbtCompound currentMetadata = legacy(damage);
            assertTrue(LegacyToolDamageIdentity.canCompare(currentMetadata, damage));
            NbtCompound identity = LegacyToolDamageIdentity.normalize(currentMetadata, damage);
            var wear = new MossMiningToolGuard.Durability(damage, 1_561, false);
            assertSame(tracked, guard.track(identity, wear).orElseThrow());
            assertEquals(1, guard.trackedIdentityCount());
            assertEquals(initialAllowance - attempt, tracked.allowance());
            assertEquals(MossMiningToolGuard.Preparation.USE_HOE, tracked.prepare(wear, true));
            assertTrue(guard.matches(tracked, identity, wear));
            lastOwned = guard.begin(0, tracked, identity, wear).orElseThrow();
            assertEquals(initialAllowance - attempt - 1, tracked.allowance());
            NbtCompound wornMetadata = legacy(damage + 1);
            assertTrue(LegacyToolDamageIdentity.canCompare(wornMetadata, damage + 1));
            assertTrue(guard.owns(lastOwned, 0, LegacyToolDamageIdentity.normalize(wornMetadata, damage + 1),
                    new MossMiningToolGuard.Durability(damage + 1, 1_561, false)));
            if (attempt < 99) {
                assertTrue(guard.settleCharge(lastOwned.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
            }
        }

        int chargedAllowance = tracked.allowance();
        NbtCompound changed = legacy(200);
        changed.putInt("serial", 43);
        assertTrue(LegacyToolDamageIdentity.canCompare(changed, 200));
        NbtCompound changedIdentity = LegacyToolDamageIdentity.normalize(changed, 200);
        var afterWear = new MossMiningToolGuard.Durability(200, 1_561, false);
        assertFalse(guard.matches(tracked, changedIdentity, afterWear));
        assertTrue(guard.begin(0, tracked, changedIdentity, afterWear).isEmpty());
        assertFalse(guard.owns(lastOwned, 0, changedIdentity, afterWear));
        assertEquals(chargedAllowance, tracked.allowance());

        NbtCompound repairedMetadata = legacy(0);
        assertTrue(LegacyToolDamageIdentity.canCompare(repairedMetadata, 0));
        NbtCompound repairedIdentity = LegacyToolDamageIdentity.normalize(repairedMetadata, 0);
        var repairedWear = new MossMiningToolGuard.Durability(0, 1_561, false);
        assertSame(tracked, guard.track(repairedIdentity, repairedWear).orElseThrow());
        assertTrue(guard.matches(tracked, repairedIdentity, repairedWear));
        assertFalse(guard.owns(lastOwned, 0, repairedIdentity, repairedWear));
        assertFalse(tracked.acceptRepair("unrequested-repair", repairedWear));
        assertEquals(chargedAllowance, tracked.allowance());
        assertEquals(initialAllowance - 100, chargedAllowance);
        assertEquals(1, guard.trackedIdentityCount());
    }

    private static NbtCompound legacy(int damage) {
        NbtCompound data = new NbtCompound();
        data.putByte("VV|Protocol1_20_3To1_20_5", (byte) 1);
        data.putInt("Damage", damage);
        data.putString("name", "private-original-tool");
        data.putInt("serial", 42);
        NbtCompound enchantment = new NbtCompound();
        enchantment.putInt("level", 6);
        data.put("enchantment", enchantment);
        data.put("empty_compound", new NbtCompound());
        data.put("empty_list", new NbtList());
        return data;
    }

    private static void assertUnchanged(NbtCompound input, int damage) {
        NbtCompound before = input.copy();
        assertSame(input, LegacyToolDamageIdentity.normalize(input, damage));
        assertEquals(before, input);
        NbtComponent component = NbtComponent.of(input);
        assertSame(component, LegacyToolDamageIdentity.normalize(component, damage));
    }

    private static void assertRejected(NbtCompound input, int damage) {
        assertUnchanged(input, damage);
        assertFalse(LegacyToolDamageIdentity.canCompare(input, damage));
        assertFalse(LegacyToolDamageIdentity.canCompare(NbtComponent.of(input), damage));
    }
}
