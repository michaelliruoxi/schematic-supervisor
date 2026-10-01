package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DepotContainerIdentityTest {
    @Test
    void acceptsOnlyTheBoundSyncIdAndExpectedShape() {
        DepotContainerIdentity identity = DepotContainerIdentity.accepted(17, 6);

        assertTrue(identity.matches(17, 6));
        assertFalse(identity.matches(18, 6));
        assertFalse(identity.matches(17, 3));
    }

    @Test
    void resetRejectsEveryScreenIdentity() {
        DepotContainerIdentity reset = DepotContainerIdentity.accepted(17, 3).reset();

        assertFalse(reset.active());
        assertFalse(reset.matches(17, 3));
    }

    @Test
    void derivesAndValidatesTheOnlySupportedChestShapes() {
        assertEquals(3, DepotContainerIdentity.expectedRows(false));
        assertEquals(6, DepotContainerIdentity.expectedRows(true));
        assertThrows(
                IllegalArgumentException.class,
                () -> DepotContainerIdentity.accepted(17, 4)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> DepotContainerIdentity.accepted(-1, 3)
        );
    }
}
