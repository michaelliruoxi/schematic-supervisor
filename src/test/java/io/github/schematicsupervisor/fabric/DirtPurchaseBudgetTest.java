package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DirtPurchaseBudgetTest {
    @Test
    void finalRefillRoundsOnlyTheRemainingDemand() {
        assertEquals(1, DirtPurchaseBudget.maximumStacks(1_000, 999, 0, 1));
        assertEquals(1, DirtPurchaseBudget.maximumStacks(1_000, 935, 1, 1));
        assertEquals(2, DirtPurchaseBudget.maximumStacks(1_000, 935, 0, 1));
        assertEquals(0, DirtPurchaseBudget.maximumStacks(1_000, 999, 1, 1));
    }

    @Test
    void largeBuildsRetainEfficientInventorySizedRefillsWithoutOverflow() {
        assertEquals(36, DirtPurchaseBudget.maximumStacks(Long.MAX_VALUE, 0, 0, 1));
        assertEquals(36, DirtPurchaseBudget.maximumStacks(313_600, 2_239, 0, 1));
    }

    @Test
    void explicitTemporarySupportRequirementsRemainFunded() {
        assertEquals(1, DirtPurchaseBudget.maximumStacks(100, 100, 0, 3));
        assertEquals(0, DirtPurchaseBudget.maximumStacks(100, 105, 3, 3));
    }

    @Test
    void invalidQuantitiesCannotStartAPurchase() {
        assertThrows(IllegalArgumentException.class, () -> DirtPurchaseBudget.maximumStacks(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DirtPurchaseBudget.maximumStacks(1, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> DirtPurchaseBudget.maximumStacks(1, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> DirtPurchaseBudget.maximumStacks(1, 0, 0, -1));
    }
}
