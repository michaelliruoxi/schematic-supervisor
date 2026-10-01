package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialQuantitiesTest {
    @Test
    void arithmeticIsExactAndNeverProducesNegativeCounts() {
        MaterialQuantities first = MaterialQuantities.of(Map.of(
                Material.DIRT, 10L,
                Material.WHEAT_SEEDS, 3L
        ));
        MaterialQuantities second = MaterialQuantities.of(Map.of(
                Material.DIRT, 4L,
                Material.GLOWSTONE, 2L
        ));

        assertEquals(
                MaterialQuantities.of(Map.of(
                        Material.DIRT, 14L,
                        Material.WHEAT_SEEDS, 3L,
                        Material.GLOWSTONE, 2L
                )),
                first.plus(second)
        );
        assertEquals(
                MaterialQuantities.of(Map.of(
                        Material.DIRT, 6L,
                        Material.WHEAT_SEEDS, 3L
                )),
                first.minusFloorZero(second)
        );
        assertEquals(
                MaterialQuantities.of(Material.GLOWSTONE, 2),
                second.shortageFrom(first)
        );
    }

    @Test
    void jsonMapUsesStableLowercaseNamesAndRoundTrips() {
        MaterialQuantities quantities = MaterialQuantities.of(Map.of(
                Material.FOOD, 2L,
                Material.DIRT, 5L
        ));

        assertEquals(Map.of("dirt", 5L, "food", 2L), quantities.asJsonMap());
        assertEquals(quantities, MaterialQuantities.fromJsonMap(quantities.asJsonMap()));
    }

    @Test
    void rejectsNegativeCountsAndOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MaterialQuantities.of(Material.DIRT, -1)
        );
        assertThrows(
                ArithmeticException.class,
                () -> MaterialQuantities.of(Material.DIRT, Long.MAX_VALUE)
                        .plus(MaterialQuantities.of(Material.DIRT, 1))
        );
        assertTrue(MaterialQuantities.empty().isEmpty());
    }
}
