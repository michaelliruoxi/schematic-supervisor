package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class PlacementAnchorPolicyTest {
    private static final PlacementAnchorPolicy.Facts FARMLAND =
            new PlacementAnchorPolicy.Facts(false, false, true, false, false, false, false);

    @Test
    void farmlandSidesAndUndersidePermitPlacementButTheTopRemainsExcluded() {
        for (Direction face : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
                Direction.DOWN)) {
            assertTrue(PlacementAnchorPolicy.permits(FARMLAND, face), face.toString());
        }
        assertFalse(PlacementAnchorPolicy.permits(FARMLAND, Direction.UP));
    }

    @Test
    void existingPlainFullCubeAndMossFacesRemainAvailableIncludingSupportColumnTopClicks() {
        for (PlacementAnchorPolicy.Facts anchor : List.of(
                new PlacementAnchorPolicy.Facts(true, false, false, true, false, false, false),
                new PlacementAnchorPolicy.Facts(false, true, false, true, false, false, false))) {
            for (Direction face : Direction.values()) {
                assertTrue(PlacementAnchorPolicy.permits(anchor, face), anchor + "/" + face);
            }
        }
    }

    @Test
    void farmlandDoesNotExemptBlockEntitiesReplaceableBlocksOrFluids() {
        for (PlacementAnchorPolicy.Facts unsafe : List.of(
                new PlacementAnchorPolicy.Facts(false, false, true, false, true, false, false),
                new PlacementAnchorPolicy.Facts(false, false, true, false, false, true, false),
                new PlacementAnchorPolicy.Facts(false, false, true, false, false, false, true),
                new PlacementAnchorPolicy.Facts(true, false, false, true, true, false, false),
                new PlacementAnchorPolicy.Facts(false, true, false, true, false, true, false),
                new PlacementAnchorPolicy.Facts(true, false, false, true, false, false, true))) {
            for (Direction face : Direction.values()) {
                assertFalse(PlacementAnchorPolicy.permits(unsafe, face), unsafe + "/" + face);
            }
        }
    }

    @Test
    void undersidePermissionRequiresExactFarmlandRatherThanAnyPartialHeightAnchor() {
        assertTrue(PlacementAnchorPolicy.permits(FARMLAND, Direction.DOWN));
        for (PlacementAnchorPolicy.Facts partial : List.of(
                new PlacementAnchorPolicy.Facts(false, false, false, false, false, false, false),
                new PlacementAnchorPolicy.Facts(true, false, false, false, false, false, false),
                new PlacementAnchorPolicy.Facts(false, true, false, false, false, false, false))) {
            assertFalse(PlacementAnchorPolicy.permits(partial, Direction.DOWN));
        }
        assertFalse(PlacementAnchorPolicy.permits(FARMLAND, Direction.UP));
    }

    @Test
    void unknownInteractiveOrNonFullCubeBlocksAreNotGeneralizedIntoSafeAnchors() {
        for (PlacementAnchorPolicy.Facts unknown : List.of(
                new PlacementAnchorPolicy.Facts(false, false, false, true, false, false, false),
                new PlacementAnchorPolicy.Facts(false, false, false, false, false, false, false),
                new PlacementAnchorPolicy.Facts(true, false, false, false, false, false, false),
                new PlacementAnchorPolicy.Facts(false, true, false, false, false, false, false))) {
            for (Direction face : Direction.values()) {
                assertFalse(PlacementAnchorPolicy.permits(unknown, face), unknown + "/" + face);
            }
        }
        assertFalse(PlacementAnchorPolicy.permits(null, Direction.EAST));
        assertFalse(PlacementAnchorPolicy.permits(FARMLAND, null));
    }
}
