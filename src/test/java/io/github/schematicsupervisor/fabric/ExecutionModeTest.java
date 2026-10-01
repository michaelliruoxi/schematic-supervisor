package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.ExecutionStatus;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ExecutionModeTest {
    private static final Set<ExecutionMode> WAITING_OR_DONE = EnumSet.of(ExecutionMode.IDLE, ExecutionMode.SUSPENDED,
            ExecutionMode.SUCCEEDED, ExecutionMode.FAILED, ExecutionMode.NEEDS_PREREQUISITES,
            ExecutionMode.AUTOMATION_BLOCKED);

    @Test
    void onlyWorkingModesTickAndEveryWorkingModeHoldsItsOrder() {
        for (ExecutionMode mode : ExecutionMode.values()) {
            assertEquals(!WAITING_OR_DONE.contains(mode), mode.ticksWork(), mode.name());
            if (mode.ticksWork()) {
                assertTrue(mode.holdsOrder(), mode.name());
                assertFalse(mode.terminal(), mode.name());
                assertTrue(mode.restartable(), mode.name());
            }
        }
        // A suspended or prerequisite-waiting order is still owned; a finished or blocked one is not.
        assertTrue(ExecutionMode.SUSPENDED.holdsOrder());
        assertTrue(ExecutionMode.NEEDS_PREREQUISITES.holdsOrder());
        for (ExecutionMode mode : EnumSet.of(ExecutionMode.IDLE, ExecutionMode.SUCCEEDED, ExecutionMode.FAILED,
                ExecutionMode.AUTOMATION_BLOCKED)) {
            assertFalse(mode.holdsOrder(), mode.name());
        }
    }

    @Test
    void aFailedOrderCannotRestartItsRouteAndMustStartAgain() {
        assertFalse(ExecutionMode.FAILED.restartable());
        assertFalse(ExecutionMode.SUCCEEDED.restartable());
        assertFalse(ExecutionMode.IDLE.restartable());
        assertTrue(ExecutionMode.SUSPENDED.restartable(), "a stalled order stopped for recovery restarts its route");
        assertTrue(ExecutionMode.WAITING_MATERIALS.restartable());
    }

    @Test
    void pollsReportEachModesStatus() {
        assertEquals(ExecutionStatus.IDLE, ExecutionMode.IDLE.reportedStatus(false));
        assertEquals(ExecutionStatus.NEEDS_MATERIALS, ExecutionMode.WAITING_MATERIALS.reportedStatus(true));
        assertEquals(ExecutionStatus.SUCCEEDED, ExecutionMode.SUCCEEDED.reportedStatus(true));
        assertEquals(ExecutionStatus.NEEDS_PREREQUISITES, ExecutionMode.NEEDS_PREREQUISITES.reportedStatus(false));
        assertEquals(ExecutionStatus.FAILED, ExecutionMode.FAILED.reportedStatus(true));
        assertThrows(IllegalStateException.class, () -> ExecutionMode.AUTOMATION_BLOCKED.reportedStatus(false));
        for (ExecutionMode mode : ExecutionMode.values()) {
            if (mode.ticksWork() && mode != ExecutionMode.WAITING_MATERIALS || mode == ExecutionMode.SUSPENDED) {
                assertEquals(ExecutionStatus.RUNNING, mode.reportedStatus(false), mode.name());
                assertEquals(ExecutionStatus.WAITING_FOR_SCREEN, mode.reportedStatus(true), mode.name());
            }
        }
    }

    @Test
    void flightRoutesUseTheApproachDeadlineInsteadOfTheBlockDeadline() {
        assertTrue(ExecutionMode.MANUAL_NAVIGATING.requiresConfirmedBlockProgress(false));
        assertFalse(ExecutionMode.MANUAL_NAVIGATING.requiresConfirmedBlockProgress(true));
        assertTrue(ExecutionMode.MANUAL_NAVIGATING.requiresFlightApproachProgress());
        for (ExecutionMode mode : EnumSet.of(ExecutionMode.FLIGHT_ORDINARY_NAVIGATING, ExecutionMode.FLIGHT_CHUNK_APPROACH,
                ExecutionMode.FLIGHT_CLEARING_NAVIGATION, ExecutionMode.STEM_SWEEP_NAVIGATION)) {
            assertTrue(mode.requiresFlightApproachProgress(), mode.name());
            assertFalse(mode.requiresConfirmedBlockProgress(true), mode.name());
            assertFalse(mode.requiresConfirmedBlockProgress(false), mode.name());
        }
        // Waiting for an interaction's confirmation is charged to the block deadline only.
        assertTrue(ExecutionMode.MANUAL_WAITING_CONFIRMATION.requiresConfirmedBlockProgress(true));
        assertFalse(ExecutionMode.MANUAL_WAITING_CONFIRMATION.requiresFlightApproachProgress());
    }

    @Test
    void waitingAndFinishedModesSpendNoDeadline() {
        for (ExecutionMode mode : WAITING_OR_DONE) {
            assertFalse(mode.requiresConfirmedBlockProgress(true), mode.name());
            assertFalse(mode.requiresConfirmedBlockProgress(false), mode.name());
            assertFalse(mode.requiresFlightApproachProgress(), mode.name());
            assertFalse(mode.countsMovementAsProgress(), mode.name());
        }
        assertFalse(ExecutionMode.WAITING_MATERIALS.requiresConfirmedBlockProgress(true));
        assertFalse(ExecutionMode.WAITING_MATERIALS.requiresFlightApproachProgress());
    }

    @Test
    void movementCountsAsProgressOnlyWhileTravellingToWork() {
        assertEquals(EnumSet.of(ExecutionMode.ORDINARY_RUNNING, ExecutionMode.FLIGHT_CHUNK_APPROACH,
                        ExecutionMode.FLIGHT_ORDINARY_NAVIGATING, ExecutionMode.FLIGHT_CLEARING_NAVIGATION,
                        ExecutionMode.STEM_SWEEP_NAVIGATION, ExecutionMode.MANUAL_NAVIGATING, ExecutionMode.MANUAL_READY),
                EnumSet.copyOf(java.util.Arrays.stream(ExecutionMode.values())
                        .filter(ExecutionMode::countsMovementAsProgress).toList()));
    }

    @Test
    void terminalModesAreExactlyTheOutcomesTheSupervisorHandles() {
        assertEquals(EnumSet.of(ExecutionMode.SUCCEEDED, ExecutionMode.FAILED, ExecutionMode.NEEDS_PREREQUISITES,
                        ExecutionMode.AUTOMATION_BLOCKED),
                EnumSet.copyOf(java.util.Arrays.stream(ExecutionMode.values()).filter(ExecutionMode::terminal).toList()));
    }
}
