package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class HoeUsabilityTest {
    @Test
    void normalDamageAndTheLastRemainingUseStayAvailable() {
        assertTrue(HoeUsability.usable(true, false, 0, 1_561));
        assertTrue(HoeUsability.usable(true, false, 64, 1_561));
        assertTrue(HoeUsability.usable(true, false, 1_560, 1_561));
        assertEquals(1_497, HoeUsability.remaining(true, false, 64, 1_561));
        assertEquals(1, HoeUsability.remaining(true, false, 1_560, 1_561));
        assertFalse(HoeUsability.usable(true, false, 1_561, 1_561));
        assertEquals(0, HoeUsability.remaining(true, false, 1_561, 1_561));
    }

    @Test
    void anUnbreakableHoeIgnoresStoredExhaustedDamageAndHasNoFiniteBudget() {
        assertTrue(HoeUsability.usable(true, true, 1_561, 1_561));
        assertEquals(null, HoeUsability.remaining(true, true, 1_561, 1_561));
        assertFalse(HoeUsability.usable(true, false, 1_561, 1_561));
    }

    @Test
    void eitherAbsentDamageComponentLeavesAHoeUsableWithoutInventingDurability() {
        assertTrue(HoeUsability.usable(true, false, null, 1_561));
        assertEquals(null, HoeUsability.remaining(true, false, null, 1_561));
        assertTrue(HoeUsability.usable(true, false, 0, null));
        assertEquals(null, HoeUsability.remaining(true, false, 0, null));
        assertTrue(HoeUsability.usable(true, false, null, null));
        assertEquals(null, HoeUsability.remaining(true, false, null, null));
    }

    @Test
    void aPresentZeroMaximumIsNotMistakenForAnAbsentComponent() {
        assertFalse(HoeUsability.usable(true, false, 0, 0));
        assertTrue(HoeUsability.usable(true, false, null, 0));
        assertTrue(HoeUsability.usable(true, true, 0, 0));
        assertEquals(null, HoeUsability.remaining(true, false, 0, 0));
    }

    @Test
    void exhaustedToolsCannotHideAUsableReplacementInObservedSupply() {
        List<ToolFacts> supply = List.of(
                new ToolFacts(0, true, false, 1_561, 1_561),
                new ToolFacts(1, false, false, null, null),
                new ToolFacts(12, true, false, 249, 250));
        assertEquals(1, supply.stream().filter(ToolFacts::usable).count());
        assertEquals(12, supply.stream().filter(ToolFacts::usable).findFirst().orElseThrow().slot());
        List<ToolFacts> depleted = List.of(supply.getFirst(), supply.get(1),
                new ToolFacts(12, true, false, 250, 250));
        assertTrue(depleted.stream().noneMatch(ToolFacts::usable),
                "No retained exhausted stack may satisfy the one-hoe restock reserve");
    }

    @Test
    void emptyOrUnrelatedItemIdentityNeverBecomesAHoeThroughDamageFacts() {
        assertFalse(HoeUsability.usable(false, false, null, null));
        assertFalse(HoeUsability.usable(false, false, 0, 1_561));
        assertFalse(HoeUsability.usable(false, true, 1_561, 1_561));
        assertEquals(null, HoeUsability.remaining(false, false, 0, 1_561));
    }

    private record ToolFacts(int slot, boolean hoe, boolean unbreakable, Integer damage, Integer maximum) {
        boolean usable() { return HoeUsability.usable(hoe, unbreakable, damage, maximum); }
    }
}
