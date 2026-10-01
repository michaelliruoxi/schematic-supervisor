package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RestockPlannerTest {
    @Test
    void allocatesOnlyTheExactShortageInStableDepotOrder() {
        MaterialQuantities required = MaterialQuantities.of(Map.of(
                Material.DIRT, 10L,
                Material.WHEAT_SEEDS, 5L,
                Material.FOOD, 1L
        ));
        MaterialQuantities inventory = MaterialQuantities.of(Map.of(
                Material.DIRT, 3L,
                Material.WHEAT_SEEDS, 5L,
                Material.FOOD, 1L
        ));
        List<DepotStock> depots = List.of(
                new DepotStock(new DepotId("z-last"), MaterialQuantities.of(Material.DIRT, 20)),
                new DepotStock(new DepotId("a-first"), MaterialQuantities.of(Material.DIRT, 4))
        );

        RestockDecision decision = RestockPlanner.plan(required, inventory, depots);

        assertFalse(decision.ready());
        assertEquals(MaterialQuantities.of(Material.DIRT, 7), decision.inventoryShortage());
        assertEquals(2, decision.withdrawals().size());
        assertEquals(new DepotId("a-first"), decision.withdrawals().get(0).depot());
        assertEquals(MaterialQuantities.of(Material.DIRT, 4), decision.withdrawals().get(0).quantities());
        assertEquals(MaterialQuantities.of(Material.DIRT, 3), decision.withdrawals().get(1).quantities());
        assertEquals(MaterialQuantities.of(Material.DIRT, 7), decision.totalWithdrawal());
        assertTrue(decision.missing().isEmpty());
    }

    @Test
    void reportsExactMissingListWhenAllDepotsAreInsufficient() {
        RestockDecision decision = RestockPlanner.plan(
                MaterialQuantities.of(Map.of(Material.DIRT, 8L, Material.HOE, 1L)),
                MaterialQuantities.of(Material.DIRT, 1),
                List.of(new DepotStock(
                        new DepotId("only"),
                        MaterialQuantities.of(Material.DIRT, 2)
                ))
        );

        assertEquals(MaterialQuantities.of(Material.DIRT, 2), decision.totalWithdrawal());
        assertEquals(
                MaterialQuantities.of(Map.of(Material.DIRT, 5L, Material.HOE, 1L)),
                decision.missing()
        );
    }

    @Test
    void readyDecisionDoesNotTouchDepots() {
        RestockDecision decision = RestockPlanner.plan(
                MaterialQuantities.of(Material.FOOD, 2),
                MaterialQuantities.of(Material.FOOD, 2),
                List.of()
        );

        assertTrue(decision.ready());
        assertTrue(decision.withdrawals().isEmpty());
        assertTrue(decision.missing().isEmpty());
    }
}
