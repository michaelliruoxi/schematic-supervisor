package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BuildAccessPreflightTest {
    @Test
    void floatingBuildAllowsAnAlreadyFlyingPlayer() {
        for (boolean allowed : new boolean[]{true, false}) {
            BuildAccessPreflight.Decision decision = BuildAccessPreflight.forFloatingBuild(true, allowed);

            assertTrue(decision.allowed());
            assertFalse(decision.takeoffFirst());
            assertTrue(decision.offerable());
            assertTrue(decision.detail().isEmpty());
        }
    }

    @Test
    void standingPlayerWithServerFlightTakesOffBeforeStartingOrResuming() {
        BuildAccessPreflight.Decision decision = BuildAccessPreflight.forFloatingBuild(false, true);

        assertFalse(decision.allowed());
        assertTrue(decision.takeoffFirst());
        assertTrue(decision.offerable());
        assertTrue(decision.detail().isEmpty());
    }

    @Test
    void floatingBuildRejectsStartOrResumeWhenTheServerHasNotGrantedFlight() {
        BuildAccessPreflight.Decision decision = BuildAccessPreflight.forFloatingBuild(false, false);

        assertFalse(decision.allowed());
        assertFalse(decision.takeoffFirst());
        assertFalse(decision.offerable());
        assertTrue(decision.detail().contains("server has not granted"));
    }

    @Test
    void decisionsCannotBothPermitAndTakeOff() {
        assertThrows(IllegalArgumentException.class, () -> new BuildAccessPreflight.Decision(true, true, ""));
        assertThrows(IllegalArgumentException.class, () -> new BuildAccessPreflight.Decision(false, true, "why"));
        assertThrows(IllegalArgumentException.class, () -> new BuildAccessPreflight.Decision(false, false, " "));
    }
}
