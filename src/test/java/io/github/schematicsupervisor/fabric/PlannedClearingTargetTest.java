package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlannedClearingTargetTest {
    private static final BlockPosition TARGET = new BlockPosition(8199, -61, -26974);
    private static final BlockState GLOWSTONE = new BlockState("minecraft:glowstone");
    private static final BlockState SOUTH_JACK = new BlockState("minecraft:jack_o_lantern", Map.of("facing", "south"));
    private static final OrdinaryPlacement LIGHT = new OrdinaryPlacement(TARGET, GLOWSTONE, Material.GLOWSTONE);

    @Test
    void bindsTheExactObservedSouthFacingLightAndUsesItsBoundedPolicyBudget() {
        var binding = new PlannedClearingTarget(LIGHT, SOUTH_JACK);
        assertTrue(binding.lightReplacement());
        assertTrue(binding.matches(new OrdinaryPlacement(new BlockPosition(8199, -61, -26974),
                new BlockState("minecraft:glowstone"), Material.GLOWSTONE),
                new BlockState("minecraft:jack_o_lantern", Map.of("facing", "south"))));
        assertEquals(240, binding.tickBudget());
        assertEquals(MossClearingPolicy.clearingBudgetTicks(LIGHT, SOUTH_JACK.blockId()), binding.tickBudget());
    }

    @Test
    void changedFacingTypeOrPropertiesInvalidateTheOwnedObstruction() {
        var binding = new PlannedClearingTarget(LIGHT, SOUTH_JACK);
        for (BlockState changed : List.of(
                new BlockState("minecraft:jack_o_lantern", Map.of("facing", "north")),
                new BlockState("minecraft:jack_o_lantern"),
                new BlockState("minecraft:jack_o_lantern", Map.of("facing", "south", "extra", "true")),
                new BlockState("minecraft:carved_pumpkin", Map.of("facing", "south")),
                new BlockState("minecraft:moss_block"), GLOWSTONE, BlockState.AIR)) {
            assertFalse(binding.matches(LIGHT, changed));
        }
        assertFalse(binding.matches(LIGHT, null));
    }

    @Test
    void changedTargetMaterialOrPlannedStateCannotReuseTheBinding() {
        var binding = new PlannedClearingTarget(LIGHT, SOUTH_JACK);
        for (OrdinaryPlacement changed : List.of(
                new OrdinaryPlacement(new BlockPosition(8199, -60, -26974), GLOWSTONE, Material.GLOWSTONE),
                new OrdinaryPlacement(new BlockPosition(8200, -61, -26974), GLOWSTONE, Material.GLOWSTONE),
                new OrdinaryPlacement(TARGET, GLOWSTONE, Material.DIRT),
                new OrdinaryPlacement(TARGET, new BlockState("minecraft:dirt"), Material.GLOWSTONE),
                new OrdinaryPlacement(TARGET, new BlockState("minecraft:glowstone", Map.of("extra", "true")),
                        Material.GLOWSTONE))) {
            assertFalse(binding.matches(changed, SOUTH_JACK));
        }
        assertFalse(binding.matches(null, SOUTH_JACK));
    }

    @Test
    void existingMossReplacementKeepsItsOriginalExactBindingAndBudget() {
        var dirt = new OrdinaryPlacement(TARGET, new BlockState("minecraft:dirt"), Material.DIRT);
        var moss = new BlockState("minecraft:moss_block");
        var binding = new PlannedClearingTarget(dirt, moss);
        assertFalse(binding.lightReplacement());
        assertEquals(100, binding.tickBudget());
        assertTrue(binding.matches(dirt, new BlockState("minecraft:moss_block")));
        assertFalse(binding.matches(LIGHT, moss));
        assertFalse(binding.matches(dirt, SOUTH_JACK));
        assertFalse(binding.matches(dirt, BlockState.AIR));
    }

    @Test
    void constructorRejectsUnknownOrMismatchedReplacementPairs() {
        for (BlockState actual : List.of(BlockState.AIR, GLOWSTONE,
                new BlockState("minecraft:stone"), new BlockState("minecraft:pumpkin"),
                new BlockState("minecraft:carved_pumpkin"))) {
            assertThrows(IllegalArgumentException.class, () -> new PlannedClearingTarget(LIGHT, actual));
        }
        for (OrdinaryPlacement invalid : List.of(
                new OrdinaryPlacement(TARGET, GLOWSTONE, Material.DIRT),
                new OrdinaryPlacement(TARGET, new BlockState("minecraft:dirt"), Material.GLOWSTONE),
                new OrdinaryPlacement(TARGET, new BlockState("minecraft:glowstone", Map.of("extra", "true")),
                        Material.GLOWSTONE))) {
            assertThrows(IllegalArgumentException.class, () -> new PlannedClearingTarget(invalid, SOUTH_JACK));
        }
        assertThrows(NullPointerException.class, () -> new PlannedClearingTarget(null, SOUTH_JACK));
        assertThrows(NullPointerException.class, () -> new PlannedClearingTarget(LIGHT, null));
    }

    @Test
    void capturedPropertiesRemainImmutableWhenTheInputMapChanges() {
        Map<String, String> properties = new HashMap<>(Map.of("facing", "south"));
        var binding = new PlannedClearingTarget(LIGHT, new BlockState("minecraft:jack_o_lantern", properties));
        properties.put("facing", "north");
        assertTrue(binding.matches(LIGHT, SOUTH_JACK));
        assertThrows(UnsupportedOperationException.class,
                () -> binding.observedState().properties().put("facing", "north"));
    }
}
