package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactTransferLedgerTest {
    @Test
    void tracksMultipleObservedGainsWithoutExceedingAllocation() {
        ExactTransferLedger ledger = new ExactTransferLedger(MaterialQuantities.of(Map.of(
                Material.DIRT, 70L,
                Material.WHEAT_SEEDS, 2L
        )));

        ledger.recordGain(Material.DIRT, 64);
        assertEquals(6, ledger.remaining(Material.DIRT));
        assertFalse(ledger.complete());

        ledger.recordGain(Material.DIRT, 6);
        ledger.recordGain(Material.WHEAT_SEEDS, 1);
        ledger.recordGain(Material.WHEAT_SEEDS, 1);

        assertTrue(ledger.complete());
        assertEquals(
                MaterialQuantities.of(Map.of(
                        Material.DIRT, 70L,
                        Material.WHEAT_SEEDS, 2L
                )),
                ledger.moved()
        );
    }

    @Test
    void rejectsAnyGainBeyondTheApprovedAllocation() {
        ExactTransferLedger ledger = new ExactTransferLedger(
                MaterialQuantities.of(Material.GLOWSTONE, 3)
        );
        ledger.recordGain(Material.GLOWSTONE, 2);

        assertThrows(
                IllegalStateException.class,
                () -> ledger.recordGain(Material.GLOWSTONE, 2)
        );
        assertThrows(
                IllegalStateException.class,
                () -> ledger.recordGain(Material.BIRCH_PLANKS, 1)
        );
        assertEquals(1, ledger.remaining(Material.GLOWSTONE));
    }

    @Test
    void rejectsEmptyAllocationsAndNonPositiveObservations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExactTransferLedger(MaterialQuantities.empty())
        );
        ExactTransferLedger ledger = new ExactTransferLedger(
                MaterialQuantities.of(Material.FOOD, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ledger.recordGain(Material.FOOD, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ledger.recordGain(Material.FOOD, -1)
        );
    }
}
