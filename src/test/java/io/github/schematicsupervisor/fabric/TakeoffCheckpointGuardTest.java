package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BuildPhase;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.MaterialQuantities;
import io.github.schematicsupervisor.core.RecoveryStage;
import io.github.schematicsupervisor.core.SupervisorCheckpoint;
import io.github.schematicsupervisor.core.SupervisorState;
import io.github.schematicsupervisor.core.VerificationStage;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TakeoffCheckpointGuardTest {
    private static final RunContext CURRENT = new RunContext("sha256:" + "a".repeat(64), "minecraft:overworld");

    @Test
    void noSavedCheckpointDoesNotRequireAPersistedContext() {
        assertEquals("", TakeoffCheckpointGuard.inspect(Optional::empty,
                () -> { throw new IOException("No context exists."); }, () -> CURRENT));
    }

    @Test
    void matchingFoodShortageCheckpointMayTakeOffWithoutClearingTheShortage() {
        SupervisorCheckpoint saved = checkpoint(false, false);
        assertEquals("", TakeoffCheckpointGuard.inspect(() -> Optional.of(saved),
                () -> Optional.of(CURRENT), () -> CURRENT));
        assertEquals(1, saved.missingMaterials().get(Material.FOOD));
        assertEquals("Missing materials: food=1", saved.lastError());
    }

    @Test
    void unloadedUnsettledTransferAndReconciliationMarkersBothBlockTakeoff() {
        for (SupervisorCheckpoint checkpoint : new SupervisorCheckpoint[] {
                checkpoint(true, false), checkpoint(false, true)
        }) {
            String result = TakeoffCheckpointGuard.inspect(() -> Optional.of(checkpoint),
                    () -> Optional.of(CURRENT), () -> CURRENT);
            assertTrue(result.contains("reconciliation"));
        }
    }

    @Test
    void absentAndDifferentWorldContextsBlockTakeoff() {
        assertFalse(TakeoffCheckpointGuard.inspect(() -> Optional.of(checkpoint(false, false)),
                Optional::empty, () -> CURRENT).isBlank());
        RunContext different = new RunContext("sha256:" + "b".repeat(64), "minecraft:overworld");
        assertTrue(TakeoffCheckpointGuard.inspect(() -> Optional.of(checkpoint(false, false)),
                () -> Optional.of(different), () -> CURRENT).contains("different server"));
    }

    @Test
    void checkpointDecodeFailureAndContextReadFailureBlockWithoutMutation() {
        assertTrue(TakeoffCheckpointGuard.inspect(
                () -> { throw new IllegalArgumentException("Unsupported checkpoint version."); },
                () -> Optional.of(CURRENT), () -> CURRENT).contains("could not be read safely"));
        assertTrue(TakeoffCheckpointGuard.inspect(() -> Optional.of(checkpoint(false, false)),
                () -> { throw new IOException("Context file unreadable."); },
                () -> CURRENT).contains("could not be read safely"));
    }

    private static SupervisorCheckpoint checkpoint(boolean transfer, boolean reconciliation) {
        MaterialQuantities empty = MaterialQuantities.empty();
        MaterialQuantities food = MaterialQuantities.of(Material.FOOD, 1);
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, "farm", SupervisorState.PAUSED,
                SupervisorState.RESTOCKING, SupervisorState.BUILDING, 0, BuildPhase.ORDINARY_BLOCKS,
                VerificationStage.CHUNK, RecoveryStage.NONE, 0, "", empty, empty, food, food,
                "Missing materials: food=1", 0, false, false, false, transfer, reconciliation,
                reconciliation ? "Unsettled depot transfer." : "", LayerBuildSchedule.ID, 0, -1);
    }
}
