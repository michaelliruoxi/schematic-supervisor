package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannedConsumptionCreditTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    @TempDir Path directory;

    @Test
    void boundsIdentityMaterialAndQuantityToOneStructuralStarter() {
        for (Material material : List.of(Material.DIRT, Material.GLOWSTONE, Material.BIRCH_PLANKS)) {
            assertEquals(1, new PlannedConsumptionCredit(ID, "plan", material, 1).quantity());
        }
        for (long quantity : new long[] {-1, 0, 2, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlannedConsumptionCredit(ID, "plan", Material.DIRT, quantity));
        }
        for (Material material : List.of(Material.WHEAT_SEEDS, Material.HOE, Material.FOOD)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlannedConsumptionCredit(ID, "plan", material, 1));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedConsumptionCredit("1-1-1-1-1", "plan", Material.DIRT, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedConsumptionCredit(ID, " ", Material.DIRT, 1));
    }

    @Test
    void fullCreditRoundTripsAndCurrentSchemaCannotOmitOrDuplicateIt() {
        SupervisorCheckpoint checkpoint = checkpoint("plan", new PlannedConsumptionCredit(ID, "plan", Material.DIRT, 1));
        String json = CheckpointJsonCodec.toJson(checkpoint);
        assertEquals(checkpoint, CheckpointJsonCodec.fromJson(json));
        assertTrue(json.contains("\"last_applied_planned_credit\""));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(json.replace(
                "  \"last_applied_planned_credit\": ", "  \"different_field\": ")));
        String empty = CheckpointJsonCodec.toJson(checkpoint("plan", null));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(empty.replace(
                "  \"last_applied_planned_credit\": null,", "  \"last_applied_planned_credit\": null,\n"
                        + "  \"last_applied_planned_credit\": null,")));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(json.replace(
                "\"quantity\": 1", "\"quantity\": 2")));
    }

    @Test
    void legacyLayerCheckpointMigratesWithoutChangingCursorLedgerOrGuards() {
        SupervisorCheckpoint current = checkpoint("plan", null);
        String legacy = CheckpointJsonCodec.toJson(current)
                .replace("\"version\": 4", "\"version\": 3")
                .replace("  \"last_applied_planned_credit\": null,\n", "");
        SupervisorCheckpoint migrated = CheckpointJsonCodec.fromJson(legacy);
        assertEquals(current, migrated);
        assertEquals(4, migrated.version());
        assertEquals(1, migrated.consumedMaterials().get(Material.DIRT));
        assertEquals(64, migrated.withdrawnMaterials().get(Material.DIRT));
        assertNull(migrated.lastAppliedPlannedCredit());
    }

    @Test
    void creditCannotBeDowngradedOrDetachedFromItsCommittedTotalAndPlan() {
        String json = CheckpointJsonCodec.toJson(checkpoint("plan",
                new PlannedConsumptionCredit(ID, "plan", Material.DIRT, 1)));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                json.replace("\"version\": 4", "\"version\": 3")));
        assertThrows(IllegalArgumentException.class, () -> CheckpointJsonCodec.fromJson(
                json.replace("\"consumed_materials\": {\"dirt\": 1}", "\"consumed_materials\": {}")));
        assertThrows(IllegalArgumentException.class, () -> checkpoint("different-plan",
                new PlannedConsumptionCredit(ID, "plan", Material.DIRT, 1)));
    }

    @Test
    void changingPlantingModesPreservesTheDedupRecordAndLedger() {
        SchematicPlan full = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:farmland")),
                new TargetBlock(new BlockPosition(0, 1, 0), new BlockState("minecraft:wheat"))));
        PlannedConsumptionCredit credit = new PlannedConsumptionCredit(ID, full.planId(), Material.DIRT, 1);
        SupervisorCheckpoint saved = checkpoint(full.planId(), credit);
        SupervisorCheckpoint deferred = PlantingModeTransition.apply(full.withPlantingDeferred(true), saved);
        SupervisorCheckpoint restored = PlantingModeTransition.apply(full, deferred);
        assertEquals(credit, deferred.lastAppliedPlannedCredit());
        assertEquals(credit, restored.lastAppliedPlannedCredit());
        assertEquals(saved.consumedMaterials(), restored.consumedMaterials());
        assertEquals(saved.withdrawnMaterials(), restored.withdrawnMaterials());
    }

    @Test
    void unsupportedAtomicReplacementNeverFallsBackOrChangesPreviousCheckpoint() throws Exception {
        Path target = directory.resolve("checkpoint.json");
        SupervisorCheckpoint previous = checkpoint("plan", null);
        new JsonFileCheckpointStore(target).save(previous);
        String original = Files.readString(target);
        SupervisorCheckpoint credited = checkpoint("plan", new PlannedConsumptionCredit(ID, "plan", Material.DIRT, 1));
        int[] attempts = {0};
        JsonFileCheckpointStore failing = new JsonFileCheckpointStore(target, (source, destination) -> {
            attempts[0]++;
            assertEquals(directory, source.getParent());
            assertEquals(target, destination);
            assertEquals(credited, CheckpointJsonCodec.fromJson(Files.readString(source)));
            throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test filesystem");
        });
        assertThrows(UncheckedIOException.class, () -> failing.save(credited));
        assertEquals(1, attempts[0]);
        assertEquals(original, Files.readString(target));
        assertEquals(previous, failing.load().orElseThrow());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    private static SupervisorCheckpoint checkpoint(String planId, PlannedConsumptionCredit credit) {
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, planId,
                SupervisorState.PAUSED, SupervisorState.BUILDING, SupervisorState.BUILDING,
                0, BuildPhase.ORDINARY_BLOCKS, VerificationStage.CHUNK, RecoveryStage.NONE,
                0, "", MaterialQuantities.of(Material.DIRT, 1), MaterialQuantities.of(Material.DIRT, 64),
                MaterialQuantities.empty(), MaterialQuantities.empty(), "", 0,
                false, false, false, false, false, "", LayerBuildSchedule.ID, 0, -1, 49, false, credit);
    }
}
