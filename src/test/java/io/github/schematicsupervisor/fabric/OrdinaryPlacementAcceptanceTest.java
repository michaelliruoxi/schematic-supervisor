package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OrdinaryPlacementAcceptanceTest {
    @Test
    void postTillRetryKeepsFarmlandAndRetriesOnlyTheMissingOrdinaryBlock() {
        OrdinaryPlacement dirtPrerequisite = placement(
                0,
                "minecraft:dirt",
                Material.DIRT
        );
        OrdinaryPlacement missingGlowstone = placement(
                1,
                "minecraft:glowstone",
                Material.GLOWSTONE
        );
        OrdinaryPlacement completedPlanks = placement(
                2,
                "minecraft:birch_planks",
                Material.BIRCH_PLANKS
        );
        Map<BlockPosition, BlockState> postTillWorld = Map.of(
                dirtPrerequisite.position(),
                new BlockState("minecraft:farmland", Map.of("moisture", "7")),
                missingGlowstone.position(),
                BlockState.AIR,
                completedPlanks.position(),
                new BlockState("minecraft:birch_planks")
        );

        List<OrdinaryPlacement> remaining = OrdinaryPlacementAcceptance.remaining(
                List.of(dirtPrerequisite, missingGlowstone, completedPlanks),
                dirtPrerequisite.position()::equals,
                postTillWorld::get
        );

        assertEquals(List.of(missingGlowstone), remaining);
        assertTrue(OrdinaryPlacementAcceptance.isSatisfied(
                dirtPrerequisite,
                postTillWorld.get(dirtPrerequisite.position()),
                true
        ));
        assertFalse(OrdinaryPlacementAcceptance.confirmsMaterialConsumption(
                dirtPrerequisite,
                postTillWorld.get(dirtPrerequisite.position())
        ));
        assertFalse(OrdinaryPlacementAcceptance.isSatisfied(
                dirtPrerequisite,
                postTillWorld.get(dirtPrerequisite.position()),
                false
        ));
    }

    private static OrdinaryPlacement placement(
            int x,
            String blockId,
            Material material
    ) {
        return new OrdinaryPlacement(
                new BlockPosition(x, 64, 0),
                new BlockState(blockId),
                material
        );
    }
}
