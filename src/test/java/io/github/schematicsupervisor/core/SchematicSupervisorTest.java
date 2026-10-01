package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import io.github.schematicsupervisor.core.SupervisorFakes.*;
import org.junit.jupiter.api.Test;

class SchematicSupervisorTest {
    @Test void inventoryMaintenancePreservesScheduleAndLedgersThenRestartsTheSameSlice() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 64));
        harness.execution.autoComplete = false;
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start(); supervisor.tick();
        var before = supervisor.checkpoint();
        assertTrue(supervisor.requestInventoryMaintenance());
        var held = supervisor.checkpoint();
        assertEquals(SupervisorState.RESTOCKING, held.state());
        assertEquals(SupervisorState.BUILDING, held.restockResumeState());
        assertTrue(held.restockRequirement().isEmpty());
        assertEquals(before.scheduleCursor(), held.scheduleCursor());
        assertEquals(before.consumedMaterials(), held.consumedMaterials());
        assertEquals(before.withdrawnMaterials(), held.withdrawnMaterials());
        assertEquals(before.repathAttempted(), held.repathAttempted());
        assertEquals(before.safeReturnAttempted(), held.safeReturnAttempted());
        assertEquals(before.advisorAttempted(), held.advisorAttempted());
        assertEquals(held, harness.checkpoints.saved);
        supervisor.tick(); supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(2, harness.execution.started.size());
        assertEquals(harness.execution.started.get(0), harness.execution.started.get(1));
        assertEquals(0, harness.depots.beginCount);
    }

    @Test void inventoryMaintenanceDoesNotOverrideOperatorPauseOrStop() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        assertFalse(supervisor.requestInventoryMaintenance());
        supervisor.start(); supervisor.pause();
        var saved = supervisor.checkpoint();
        assertFalse(supervisor.requestInventoryMaintenance());
        assertEquals(saved, supervisor.checkpoint());
    }

    @Test void inventoryMaintenanceCancellationFailurePersistsAHoldWithoutNewWork() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        harness.execution.stopFailure = new IllegalStateException("movement cancellation failed");
        assertFalse(supervisor.requestInventoryMaintenance());
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertTrue(supervisor.requiresReconciliation());
        assertEquals(supervisor.checkpoint(), harness.checkpoints.saved);
        assertEquals(0, harness.depots.beginCount);
    }

    @Test void checkedStartBeginsAtTheFirstUnfinishedPieceAndSkipsCheckedPieces() {
        SchematicPlan plan = layeredPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 2));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        BuildCheck.Result check = layeredCheck(plan, new BlockPosition(0, 0, 0), new BlockPosition(0, 3, 0));
        assertEquals(CompletedPieces.of(bits(0, 2)), check.completePieces());

        supervisor.start(check);
        assertEquals(1, supervisor.checkpoint().scheduleCursor());
        assertEquals(check.completePieces(), supervisor.checkpoint().checkedPieces());
        assertEquals(harness.checkpoints.saved, supervisor.checkpoint());
        assertEquals(2, supervisor.progress().doneActions());
        assertEquals("DC" + ".".repeat(47), supervisor.progress().chunkStatuses(0));
        assertEquals("D-" + ".".repeat(47), supervisor.progress().chunkStatuses(1));
        assertEquals(check.completePieces(), supervisor.progressKey().checked());

        tickUntil(supervisor, SupervisorState.VERIFYING, 20);
        assertEquals(List.of(schedule.entry(1).order(), schedule.entry(3).order()), harness.execution.started);
        assertEquals(CompletedPieces.none(), supervisor.checkpoint().checkedPieces());
        assertEquals(4, supervisor.progress().doneActions());
    }

    @Test void checkedPiecesSurvivePauseAndRestoreButAPlainStartRechecksEverything() {
        SchematicPlan plan = layeredPlan();
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 2));
        harness.execution.autoComplete = false;
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start(layeredCheck(plan, new BlockPosition(0, 0, 0), new BlockPosition(0, 3, 0)));
        supervisor.tick();
        supervisor.pause();

        Harness restarted = new Harness();
        restarted.inventory.set(MaterialQuantities.of(Material.DIRT, 2));
        restarted.checkpoints.saved = harness.checkpoints.saved;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, restarted.ports());
        assertEquals(CompletedPieces.of(bits(0, 2)), restored.checkpoint().checkedPieces());
        assertEquals(2, restored.progress().doneActions());
        restored.resume();
        tickUntil(restored, SupervisorState.VERIFYING, 20);
        assertEquals(List.of(schedule.entry(1).order(), schedule.entry(3).order()), restarted.execution.started);

        restored.stop();
        restored.start();
        assertEquals(0, restored.checkpoint().scheduleCursor());
        assertEquals(CompletedPieces.none(), restored.checkpoint().checkedPieces());
        assertEquals(0, restored.progress().doneActions());
    }

    @Test void checkedStartOfAFinishedBuildGoesStraightToVerification() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start(layeredCheck(plan, new BlockPosition(0, 0, 0), new BlockPosition(16, 0, 0),
                new BlockPosition(0, 3, 0), new BlockPosition(16, 3, 0)));
        assertEquals(SupervisorState.VERIFYING, supervisor.status().state());
        assertEquals(BuildPhase.VERIFY, supervisor.status().phase());
        assertEquals(4, supervisor.checkpoint().scheduleCursor());
        assertEquals(CompletedPieces.none(), supervisor.checkpoint().checkedPieces());
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test void checkedStartRejectsAnotherPlanAndKeepsStopped() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        BuildCheck.Result foreign = layeredCheck(dirtPlan());
        assertThrows(IllegalArgumentException.class, () -> supervisor.start(foreign));
        assertEquals(SupervisorState.STOPPED, supervisor.status().state());
        assertNull(harness.checkpoints.saved);
    }

    @Test void restoreRejectsCheckedPiecesBeyondTheSchedule() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        SupervisorCheckpoint saved = checkpointFor(plan.planId());
        harness.checkpoints.saved = CheckpointJsonCodecTest.withCheckedPieces(new SupervisorCheckpoint(
                saved.version(), saved.planId(), SupervisorState.PAUSED, SupervisorState.BUILDING,
                SupervisorState.BUILDING, 0, BuildPhase.ORDINARY_BLOCKS, VerificationStage.CHUNK, RecoveryStage.NONE,
                0, "", MaterialQuantities.empty(), MaterialQuantities.empty(), MaterialQuantities.empty(),
                MaterialQuantities.empty(), "", 0, false, false, false, false, false, "",
                LayerBuildSchedule.ID, 0, -1, plan.chunkCount()), CompletedPieces.of(bits(1, 4)));
        assertThrows(IllegalArgumentException.class,
                () -> SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports()));
    }

    private static BuildCheck.Result layeredCheck(SchematicPlan plan, BlockPosition... dirt) {
        BuildCheck.Snapshot world = BuildCheckTest.received(plan);
        for (BlockPosition position : dirt) {
            world.put(position.x(), position.y(), position.z(), new BlockState("minecraft:dirt"));
        }
        return BuildCheck.evaluate(plan, new LayerBuildSchedule(plan), world, BuildCheck.Clearing.NONE,
                BuildCheck.Supports.NONE);
    }

    private static java.util.BitSet bits(int... values) {
        java.util.BitSet bits = new java.util.BitSet();
        for (int value : values) { bits.set(value); }
        return bits;
    }

    private static final SupervisorConfig NO_RESERVES = new SupervisorConfig(
            Duration.ofSeconds(15),
            Duration.ofSeconds(10),
            Duration.ofSeconds(15),
            0,
            0
    );
    // Advisor tests pause on the advisor's outcome; transient causes would otherwise retry first.
    private static final SupervisorConfig NO_RETRIES = NO_RESERVES.withTransientRetryDelays(List.of());

    @Test
    void lastConfirmedProgressUsesWallClockAndSurvivesPauseAndResume() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 64));
        harness.execution.autoComplete = false;
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        assertNull(supervisor.lastConfirmedProgressAt());
        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        assertNull(supervisor.lastConfirmedProgressAt(), "an unchanged marker is not progress");
        harness.clock.advance(Duration.ofSeconds(3));
        harness.execution.scripted.add(ExecutionSnapshot.running(1));
        supervisor.tick();
        Instant firstProgress = harness.clock.instant();
        assertEquals(firstProgress, supervisor.lastConfirmedProgressAt());
        harness.clock.advance(Duration.ofSeconds(4));
        supervisor.pause();
        supervisor.resume();
        assertEquals(firstProgress, supervisor.lastConfirmedProgressAt());
        assertEquals(new ScheduleProgress.Key(supervisor.checkpoint().planId(), LayerBuildSchedule.ID, 0, -1),
                supervisor.progressKey());
        assertEquals(1, supervisor.progress().totalActions());
    }

    @Test
    void recoveryWithARunningCommandDoesNotCountItsOldMarkerAsProgress() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        stall(supervisor, harness.clock);
        harness.execution.marker = 57;
        for (int tick = 0; tick < 10 && supervisor.status().state() != SupervisorState.BUILDING; tick++) {
            supervisor.tick();
        }
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        supervisor.tick();
        assertNull(supervisor.lastConfirmedProgressAt(), "the resumed command's old marker is only re-read");
        harness.execution.scripted.add(ExecutionSnapshot.running(58));
        supervisor.tick();
        assertEquals(harness.clock.instant(), supervisor.lastConfirmedProgressAt());
    }

    @Test void restoredLightingRequestRefreshPreservesLedgerCursorAndExhaustedRecoveryFlags() {
        SchematicPlan plan = refreshLightingPlan();
        Harness harness = new Harness();
        harness.checkpoints.saved = refreshCheckpoint(plan, true);
        harness.depots.put(new DepotId("lights"), MaterialQuantities.of(Material.GLOWSTONE, 1914));
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        SupervisorCheckpoint before = supervisor.checkpoint();
        assertTrue(supervisor.refreshGlowstoneRestockBatch(before, 64));
        SupervisorCheckpoint after = supervisor.checkpoint();
        assertEquals(64, after.restockRequirement().get(Material.GLOWSTONE));
        assertEquals(64, after.missingMaterials().get(Material.GLOWSTONE));
        assertEquals(2, after.restockRequirement().get(Material.DIRT));
        assertEquals(before.consumedMaterials(), after.consumedMaterials());
        assertEquals(before.withdrawnMaterials(), after.withdrawnMaterials());
        assertEquals(before.lastAppliedPlannedCredit(), after.lastAppliedPlannedCredit());
        assertEquals(before.scheduleCursor(), after.scheduleCursor());
        assertEquals(before.currentChunkIndex(), after.currentChunkIndex());
        assertEquals(before.phase(), after.phase());
        assertEquals(before.state(), after.state());
        assertEquals(before.resumeState(), after.resumeState());
        assertEquals(before.restockResumeState(), after.restockResumeState());
        assertEquals(before.lastError(), after.lastError());
        assertTrue(after.repathAttempted() && after.safeReturnAttempted() && after.advisorAttempted());
        assertEquals(after, harness.checkpoints.saved);
        assertEquals(0, harness.depots.beginCount);
        assertTrue(harness.execution.started.isEmpty());
        assertFalse(supervisor.refreshGlowstoneRestockBatch(before, 64), "The original checkpoint is now stale");
        assertFalse(supervisor.refreshGlowstoneRestockBatch(after, 64), "An equal request is a no-op");
    }

    @Test void freshLightingBatchIsPersistedBeforeTheFirstRealWithdrawalAllocation() {
        SchematicPlan plan = refreshLightingPlan();
        Harness harness = new Harness();
        harness.checkpoints.saved = refreshCheckpoint(plan, false);
        harness.depots.put(new DepotId("lights"), MaterialQuantities.of(Material.GLOWSTONE, 1914));
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        SupervisorCheckpoint paused = supervisor.checkpoint();
        supervisor.resume();
        assertFalse(supervisor.refreshGlowstoneRestockBatch(paused, 64), "Resume changed the checkpoint binding");
        assertTrue(supervisor.refreshGlowstoneRestockBatch(supervisor.checkpoint(), 64));
        harness.depots.onBegin = () -> {
            assertEquals(64, harness.checkpoints.saved.restockRequirement().get(Material.GLOWSTONE));
            assertTrue(harness.checkpoints.saved.withdrawalInFlight());
        };
        supervisor.tick();
        assertEquals(1, harness.depots.beginCount);
        assertEquals(64, harness.depots.active.getFirst().quantities().get(Material.GLOWSTONE));
        assertEquals(11, supervisor.checkpoint().withdrawnMaterials().get(Material.GLOWSTONE));
        assertFalse(supervisor.refreshGlowstoneRestockBatch(supervisor.checkpoint(), 65));
        supervisor.tick();
        assertEquals(75, supervisor.checkpoint().withdrawnMaterials().get(Material.GLOWSTONE));
        assertEquals(7, supervisor.checkpoint().consumedMaterials().get(Material.GLOWSTONE));
    }

    @Test void refreshCannotInventStockExceedItsScopeOrRefillANonemptyGlowstoneStack() {
        SchematicPlan plan = refreshLightingPlan();
        Harness harness = new Harness();
        harness.checkpoints.saved = refreshCheckpoint(plan, false);
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        SupervisorCheckpoint before = supervisor.checkpoint();
        assertFalse(supervisor.refreshGlowstoneRestockBatch(before, 64));
        harness.depots.put(new DepotId("lights"), MaterialQuantities.of(Material.GLOWSTONE, 20));
        assertFalse(supervisor.refreshGlowstoneRestockBatch(before, 21));
        assertFalse(supervisor.refreshGlowstoneRestockBatch(before, 65));
        assertFalse(supervisor.refreshGlowstoneRestockBatch(before, 0));
        harness.inventory.set(MaterialQuantities.of(Material.GLOWSTONE, 1));
        assertFalse(supervisor.refreshGlowstoneRestockBatch(before, 20));
        harness.inventory.set(MaterialQuantities.empty());
        assertTrue(supervisor.refreshGlowstoneRestockBatch(before, 20));
    }

    @Test void refreshRejectsUnsettledExecutionAndReconciliationWithoutChangingTheRequest() {
        SchematicPlan plan = refreshLightingPlan();
        Harness harness = new Harness();
        harness.checkpoints.saved = refreshCheckpoint(plan, false);
        harness.depots.put(new DepotId("lights"), MaterialQuantities.of(Material.GLOWSTONE, 1914));
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(MaterialQuantities.empty(), true, ""));
        supervisor.pause();
        assertTrue(supervisor.requiresReconciliation());
        assertFalse(supervisor.refreshGlowstoneRestockBatch(supervisor.checkpoint(), 64));
        assertEquals(1, supervisor.checkpoint().restockRequirement().get(Material.GLOWSTONE));
        assertEquals(0, harness.depots.beginCount);
    }

    @Test void failedRefreshPersistenceRollsBackTheRequestAndDoesNotStartAnyTransfer() {
        SchematicPlan plan = refreshLightingPlan();
        Harness harness = new Harness();
        harness.checkpoints.saved = refreshCheckpoint(plan, false);
        harness.depots.put(new DepotId("lights"), MaterialQuantities.of(Material.GLOWSTONE, 1914));
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        SupervisorCheckpoint before = supervisor.checkpoint();
        harness.checkpoints.saveFailure = new IllegalStateException("storage unavailable");
        assertThrows(IllegalStateException.class, () -> supervisor.refreshGlowstoneRestockBatch(before, 64));
        assertEquals(before, supervisor.checkpoint());
        assertEquals(0, harness.depots.beginCount);
    }

    @Test void nearLayerEndRefreshCannotCompoundLookaheadOnRepeatedCallsOrRestart() {
        SchematicPlan plan = refreshLightingPlan();
        Harness harness = new Harness();
        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(refreshCheckpoint(plan, false))
                .replace("\"schedule_cursor\": 0", "\"schedule_cursor\": 4")
                .replace("\"current_chunk_index\": 0", "\"current_chunk_index\": 4"));
        harness.depots.put(new DepotId("lights"), MaterialQuantities.of(Material.GLOWSTONE, 1914));
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        assertTrue(supervisor.refreshGlowstoneRestockBatch(supervisor.checkpoint(), 17));
        SupervisorCheckpoint refreshed = supervisor.checkpoint();
        assertFalse(supervisor.refreshGlowstoneRestockBatch(refreshed, 33));
        assertEquals(refreshed, supervisor.checkpoint());
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        assertFalse(restored.refreshGlowstoneRestockBatch(restored.checkpoint(), 33));
        assertEquals(17, restored.checkpoint().restockRequirement().get(Material.GLOWSTONE));
        assertEquals(0, harness.depots.beginCount);
    }

    private static SchematicPlan refreshLightingPlan() {
        List<TargetBlock> targets = new ArrayList<>();
        for (int x = 0; x < 96; x++) {
            targets.add(new TargetBlock(new BlockPosition(x, -61, 0), new BlockState("minecraft:glowstone")));
        }
        return SchematicCompiler.compile(new BuildVolume(0, -61, 0, 95, -61, 0), targets).withPlantingDeferred(true);
    }

    private static SupervisorCheckpoint refreshCheckpoint(SchematicPlan plan, boolean mixedRequirement) {
        LayerBuildSchedule schedule = new LayerBuildSchedule(plan);
        MaterialQuantities requirement = MaterialQuantities.of(mixedRequirement
                ? Map.of(Material.GLOWSTONE, 1L, Material.DIRT, 2L) : Map.of(Material.GLOWSTONE, 1L));
        return new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION, plan.planId(),
                SupervisorState.PAUSED, SupervisorState.RESTOCKING, SupervisorState.BUILDING,
                schedule.entry(0).order().chunkIndex(), BuildPhase.ORDINARY_BLOCKS, VerificationStage.CHUNK,
                RecoveryStage.NONE, 0, "", MaterialQuantities.of(Material.GLOWSTONE, 7),
                MaterialQuantities.of(Material.GLOWSTONE, 11), requirement, requirement, "Previous resolved incident",
                1, true, true, true, false, false, "", schedule.id(), 0, -1, plan.chunkCount(), true, null);
    }

    @Test
    void executesAllPhasesChecksAll49ChunksAndRequiresTwoStableFinalPasses() {
        SchematicPlan plan = representativePlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Map.of(
                Material.DIRT, 1L,
                Material.WHEAT_SEEDS, 1L,
                Material.GLOWSTONE, 1L,
                Material.BIRCH_PLANKS, 1L,
                Material.HOE, 1L,
                Material.FOOD, 1L
        )));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                plan,
                SupervisorConfig.defaults(),
                harness.ports()
        );

        supervisor.start();
        tickUntil(supervisor, SupervisorState.DONE, 200);

        assertEquals(
                List.of(BuildPhase.ORDINARY_BLOCKS, BuildPhase.ORDINARY_BLOCKS,
                        BuildPhase.TILL, BuildPhase.PLANT),
                harness.execution.started.stream().map(WorkOrder::phase).toList()
        );
        assertInstanceOf(WorkOrder.OrdinaryBlocks.class, harness.execution.started.getFirst());
        assertEquals(51, harness.verification.scopes.size());
        assertEquals(
                49,
                harness.verification.scopes.stream()
                        .filter(scope -> scope.kind() == VerificationScope.Kind.CHUNK)
                        .count()
        );
        assertEquals(
                2,
                harness.verification.scopes.stream()
                        .filter(scope -> scope.kind() == VerificationScope.Kind.FULL_PLAN)
                        .count()
        );
        assertEquals(plan.plannedMaterials(), supervisor.status().materials().consumed());
        assertEquals(2, supervisor.status().stableVerificationPasses());
        assertEquals(SupervisorState.DONE, harness.checkpoints.saved.state());
    }

    @Test
    void requiresTwoConsecutiveIdenticalCleanFinalFingerprints() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of());
        Harness harness = new Harness();
        Queue<String> fullFingerprints = new ArrayDeque<>(List.of("first", "second", "second"));
        harness.verification.result = scope -> scope.kind() == VerificationScope.Kind.CHUNK
                ? VerificationResult.clean("chunk-" + scope.chunkIndex())
                : VerificationResult.clean(fullFingerprints.remove());
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());

        supervisor.start();
        tickUntil(supervisor, SupervisorState.DONE, 150);

        long finalPasses = harness.verification.scopes.stream()
                .filter(scope -> scope.kind() == VerificationScope.Kind.FULL_PLAN)
                .count();
        assertEquals(3, finalPasses);
        assertEquals(2, supervisor.status().stableVerificationPasses());
    }

    @Test
    void finalFailurePausesAndNeverMarksDone() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of());
        Harness harness = new Harness();
        harness.verification.result = scope -> scope.kind() == VerificationScope.Kind.CHUNK
                ? VerificationResult.clean("chunk-" + scope.chunkIndex())
                : new VerificationResult(false, List.of(), List.of(), "unloaded");
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());

        supervisor.start();
        tickUntil(supervisor, SupervisorState.PAUSED, 150);

        assertTrue(supervisor.status().lastError().contains("Final verification failed"));
        assertFalse(harness.notifications.messages.isEmpty());
        assertEquals(0, supervisor.status().stableVerificationPasses());
    }

    @Test
    void dirtyFinalPassRequiresTwoFreshCleanPassesAfterResume() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = afterOneCleanFinalPass(harness);
        harness.verification.result = scope -> new VerificationResult(
                false, List.of(), List.of(), "unloaded"
        );

        supervisor.tick();
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(0, supervisor.status().stableVerificationPasses());
        assertEquals("", harness.checkpoints.saved.lastVerificationFingerprint());
        harness.verification.result = scope -> VerificationResult.clean("stable-full-plan");
        assertTwoFreshFinalPassesRequired(supervisor);
    }

    @Test
    void failedFinalTaskRequiresTwoFreshCleanPassesAfterResume() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = afterOneCleanFinalPass(harness);
        harness.verification.scripted.add(VerificationTaskSnapshot.failed("world unavailable"));

        supervisor.tick();
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(0, supervisor.status().stableVerificationPasses());
        assertTwoFreshFinalPassesRequired(supervisor);
    }

    @Test
    void manualPauseInvalidatesPreviousFinalPass() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = afterOneCleanFinalPass(harness);

        supervisor.pause();

        assertEquals(0, supervisor.status().stableVerificationPasses());
        assertEquals("", harness.checkpoints.saved.lastVerificationFingerprint());
        assertTwoFreshFinalPassesRequired(supervisor);
    }

    @Test
    void restoringInterruptedFinalVerificationRequiresTwoFreshPasses() {
        Harness original = new Harness();
        afterOneCleanFinalPass(original);
        Harness restarted = new Harness();
        restarted.checkpoints.saved = original.checkpoints.saved;

        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of()),
                NO_RESERVES,
                restarted.ports()
        );

        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(0, restored.status().stableVerificationPasses());
        assertTwoFreshFinalPassesRequired(restored);

        SchematicSupervisor completed = SchematicSupervisor.loadOrCreate(
                SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of()),
                NO_RESERVES,
                restarted.ports()
        );
        assertEquals(SupervisorState.DONE, completed.status().state());
        assertEquals(2, completed.status().stableVerificationPasses());
    }

    @Test
    void verificationPortCanRemainPendingWithoutStartingDuplicateScans() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of());
        Harness harness = new Harness();
        harness.verification.scripted.add(VerificationTaskSnapshot.running());
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());

        supervisor.start();
        supervisor.tick();
        assertEquals(1, harness.verification.scopes.size());
        supervisor.tick();
        assertEquals(1, harness.verification.scopes.size());
        assertEquals(SupervisorState.VERIFYING, supervisor.status().state());
        supervisor.tick();

        assertEquals(2, supervisor.status().currentChunkOrdinal());
        assertEquals(1, harness.verification.scopes.size());
    }

    @Test
    void restocksExactExecutorShortageFromRegisteredDepot() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.empty());
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                0,
                MaterialQuantities.of(Material.DIRT, 1)
        ));
        harness.execution.scripted.add(ExecutionSnapshot.succeeded(
                1,
                MaterialQuantities.of(Material.DIRT, 1)
        ));
        harness.depots.put(
                new DepotId("depot-a"),
                MaterialQuantities.of(Material.DIRT, 1)
        );
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());

        supervisor.start();
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, supervisor.status().state());

        supervisor.tick();
        assertTrue(supervisor.status().materials().withdrawn().isEmpty());
        supervisor.tick();
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 1),
                supervisor.status().materials().withdrawn()
        );
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.inventory.snapshot());

        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        supervisor.tick();
        assertEquals(1, harness.execution.started.size());
    }

    @Test
    void checkpointsWithdrawalBeforeCallingDepotAdapter() {
        Harness harness = harnessAwaitingWithdrawal();
        harness.depots.onBegin = () -> {
            assertTrue(harness.checkpoints.saved.withdrawalInFlight());
            Harness restarted = new Harness();
            restarted.checkpoints.saved = harness.checkpoints.saved;
            SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                    dirtPlan(), NO_RESERVES, restarted.ports()
            );
            assertTrue(restored.requiresReconciliation());
            assertThrows(IllegalStateException.class, restored::resume);
        };

        harness.supervisor.tick();

        assertEquals(1, harness.depots.beginCount);
        assertTrue(harness.supervisor.checkpoint().withdrawalInFlight());
    }

    @Test
    void doesNotStartWithdrawalWhenCheckpointCannotBeSaved() {
        Harness harness = harnessAwaitingWithdrawal();
        harness.checkpoints.saveFailure = new IllegalStateException("storage unavailable");

        assertThrows(IllegalStateException.class, harness.supervisor::tick);

        assertEquals(0, harness.depots.beginCount);
        assertFalse(harness.supervisor.checkpoint().withdrawalInFlight());
        assertFalse(harness.checkpoints.saved.withdrawalInFlight());
    }

    @Test
    void pausesWithExactMissingListWhenDepotsCannotSatisfyRequest() {
        Harness harness = new Harness();
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                0,
                MaterialQuantities.of(Material.DIRT, 1)
        ));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 1),
                supervisor.status().missingMaterials()
        );
        assertTrue(supervisor.status().lastError().contains("exact material shortage"));
    }

    @Test
    void capacityLimitedDirtBatchRestartsSameSliceAndPreservesFutureDemandAndLedger() {
        Harness harness = harnessAwaitingDirtBatch(MaterialQuantities.of(Material.DIRT, 128));
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 64));
        MaterialLedgerSnapshot before = harness.supervisor.status().materials();
        int cursor = harness.supervisor.checkpoint().scheduleCursor();

        assertTrue(harness.supervisor.acceptAvailableDirtBatch());
        assertEquals(MaterialQuantities.of(Material.DIRT, 64), harness.checkpoints.saved.restockRequirement());
        assertEquals(before, harness.supervisor.status().materials());
        assertEquals(cursor, harness.supervisor.checkpoint().scheduleCursor());
        harness.supervisor.tick();
        assertEquals(SupervisorState.BUILDING, harness.supervisor.status().state());
        harness.supervisor.tick();
        assertEquals(2, harness.execution.started.size());
        assertEquals(harness.execution.started.getFirst(), harness.execution.started.getLast());
        assertEquals(0, harness.depots.beginCount);

        harness.inventory.set(MaterialQuantities.empty());
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(1, MaterialQuantities.of(Material.DIRT, 64)));
        harness.supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, harness.supervisor.status().state());
        assertEquals(MaterialQuantities.of(Material.DIRT, 64), harness.supervisor.checkpoint().restockRequirement());
        assertEquals(before, harness.supervisor.status().materials());
        assertFalse(harness.supervisor.acceptAvailableDirtBatch());
    }

    @Test
    void capacityLimitedDirtBatchCannotBypassAnActiveWithdrawal() {
        Harness harness = harnessAwaitingDirtBatch(MaterialQuantities.of(Material.DIRT, 128));
        harness.depots.put(new DepotId("depot-a"), MaterialQuantities.of(Material.DIRT, 128));
        harness.supervisor.tick();
        assertTrue(harness.supervisor.checkpoint().withdrawalInFlight());
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 64));

        assertFalse(harness.supervisor.acceptAvailableDirtBatch());
        assertTrue(harness.supervisor.checkpoint().withdrawalInFlight());
        assertEquals(MaterialQuantities.of(Material.DIRT, 128), harness.supervisor.checkpoint().restockRequirement());
        assertEquals(1, harness.depots.beginCount);
    }

    @Test
    void capacityLimitedDirtBatchKeepsManualPauseAndAllOtherMaterialRequirements() {
        Harness harness = harnessAwaitingDirtBatch(MaterialQuantities.of(Map.of(
                Material.DIRT, 128L, Material.GLOWSTONE, 2L)));
        harness.supervisor.pause();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 64));

        assertTrue(harness.supervisor.acceptAvailableDirtBatch());
        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
        assertEquals(SupervisorState.RESTOCKING, harness.supervisor.checkpoint().resumeState());
        assertEquals(MaterialQuantities.of(Map.of(Material.DIRT, 64L, Material.GLOWSTONE, 2L)),
                harness.checkpoints.saved.restockRequirement());
        assertEquals(MaterialQuantities.of(Material.GLOWSTONE, 2), harness.supervisor.status().missingMaterials());
    }

    @Test
    void capacityLimitedDirtBatchCannotBypassAnUnsettledExecutionReceipt() {
        Harness harness = harnessAwaitingDirtBatch(MaterialQuantities.of(Material.DIRT, 128));
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(MaterialQuantities.empty(), true, ""));
        harness.supervisor.pause();
        assertTrue(harness.supervisor.requiresReconciliation());
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 64));

        assertFalse(harness.supervisor.acceptAvailableDirtBatch());
        assertEquals(MaterialQuantities.of(Material.DIRT, 128), harness.checkpoints.saved.restockRequirement());
        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
    }

    @Test
    void safePausePersistsReconciliationWhenMovementCancellationIsUncertain() {
        Harness harness = new Harness();
        harness.execution.stopFailure = new IllegalStateException(
                "execution control could not be released"
        );
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                0,
                MaterialQuantities.of(Material.DIRT, 1)
        ));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertTrue(supervisor.requiresReconciliation());
        assertTrue(harness.checkpoints.saved.reconciliationRequired());
        assertTrue(
                supervisor.status().lastError().contains(
                        "Registered depots cannot satisfy the exact material shortage"
                )
        );
        assertTrue(
                supervisor.status().lastError().contains(
                        "while pausing after supervision failure"
                )
        );
        assertTrue(
                supervisor.status().lastError().contains(
                        "execution control could not be released"
                )
        );
        assertTrue(supervisor.status().lastError().contains("Reset is required"));
        assertThrows(IllegalStateException.class, supervisor::resume);
    }

    @Test
    void pauseAndStopPersistUnreportedConsumptionExactlyOnce() {
        for (boolean stopping : List.of(false, true)) {
            Harness harness = new Harness();
            SchematicSupervisor supervisor = startedForSettlement(harness);
            harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                    MaterialQuantities.of(Material.DIRT, 1), false, ""));

            if (stopping) { supervisor.stop(); } else { supervisor.pause(); }
            assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                    harness.checkpoints.saved.consumedMaterials());
            supervisor.pause();
            supervisor.stop();
            supervisor.markClientStopping();

            assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                    harness.checkpoints.saved.consumedMaterials());
            assertFalse(supervisor.requiresReconciliation());
            assertEquals(1, harness.execution.started.size());
        }
    }

    @Test
    void pendingExecutionBlocksActivationAndSettlesWithoutStartingMoreWork() {
        for (boolean stopping : List.of(false, true)) {
            Harness harness = new Harness();
            SchematicSupervisor supervisor = startedForSettlement(harness);
            harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                    MaterialQuantities.empty(), true, ""));
            harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                    MaterialQuantities.empty(), true, ""));
            harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                    MaterialQuantities.of(Material.DIRT, 1), false, ""));

            if (stopping) { supervisor.stop(); } else { supervisor.pause(); }
            assertTrue(supervisor.requiresReconciliation());
            assertTrue(harness.checkpoints.saved.reconciliationRequired());
            assertTrue(harness.checkpoints.saved.reconciliationDetail().contains("still settling"));
            assertThrows(IllegalStateException.class,
                    () -> { if (stopping) { supervisor.start(); } else { supervisor.resume(); } });
            supervisor.tick();
            assertTrue(supervisor.requiresReconciliation());
            supervisor.tick();

            assertFalse(supervisor.requiresReconciliation());
            assertFalse(harness.checkpoints.saved.reconciliationRequired());
            assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                    harness.checkpoints.saved.consumedMaterials());
            assertEquals(1, harness.execution.started.size());
            assertEquals(stopping ? SupervisorState.STOPPED : SupervisorState.PAUSED,
                    supervisor.status().state());
        }
    }

    @Test
    void quittingWithAnUnsettledInteractionPersistsResetRequirementAcrossReload() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = startedForSettlement(harness);
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.empty(), true, ""));
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.empty(), true, ""));
        supervisor.pause();
        supervisor.markClientStopping();

        assertTrue(harness.checkpoints.saved.reconciliationRequired());
        assertTrue(harness.checkpoints.saved.reconciliationDetail().contains("client stopped"));
        assertTrue(harness.checkpoints.saved.consumedMaterials().isEmpty());
        supervisor.tick();
        assertTrue(supervisor.requiresReconciliation());
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        assertThrows(IllegalStateException.class, restored::resume);
    }

    @Test
    void pendingInteractionCheckpointFailsClosedEvenWithoutShutdownCallback() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = startedForSettlement(harness);
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.empty(), true, ""));
        supervisor.pause();

        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        assertTrue(restored.requiresReconciliation());
        assertThrows(IllegalStateException.class, restored::resume);
    }

    @Test
    void finalShutdownPollSettlesAnAlreadyPausedInteractionWithoutFalseReset() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = startedForSettlement(harness);
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.empty(), true, ""));
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.of(Material.DIRT, 1), false, ""));
        supervisor.pause();
        supervisor.markClientStopping();

        assertFalse(harness.checkpoints.saved.reconciliationRequired());
        assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                harness.checkpoints.saved.consumedMaterials());
        supervisor.markClientStopping();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                harness.checkpoints.saved.consumedMaterials());
    }

    @Test
    void movementReleaseFailureStillDrainsConsumptionAndPreservesReconciliation() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = startedForSettlement(harness);
        harness.execution.stopFailure = new IllegalStateException("movement cancellation failed");
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.of(Material.DIRT, 1), false, ""));
        supervisor.pause();

        assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                harness.checkpoints.saved.consumedMaterials());
        assertTrue(supervisor.requiresReconciliation());
        assertTrue(supervisor.status().lastError().contains("movement cancellation failed"));
    }

    @Test
    void executionSettlementFailureOrExceptionRemainsFailClosed() {
        for (boolean throwing : List.of(false, true)) {
            Harness harness = new Harness();
            SchematicSupervisor supervisor = startedForSettlement(harness);
            if (throwing) {
                harness.execution.settlementFailure = new IllegalStateException("lost acknowledgement");
            } else {
                harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                        MaterialQuantities.empty(), false, "lost acknowledgement"));
            }
            supervisor.pause();
            harness.execution.settlementFailure = null;
            supervisor.pause();
            supervisor.tick();

            assertTrue(supervisor.requiresReconciliation());
            assertTrue(harness.checkpoints.saved.reconciliationRequired());
            assertTrue(supervisor.status().lastError().contains("lost acknowledgement"));
            assertThrows(IllegalStateException.class, supervisor::resume);
            assertTrue(harness.checkpoints.saved.consumedMaterials().isEmpty());
        }
    }

    @Test
    void depotCancellationCannotClearExecutionSettlementFailure() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1, MaterialQuantities.of(Material.DIRT, 64)));
        SettlingDepots settling = new SettlingDepots(
                harness.inventory,
                RestockTransferSnapshot.running(MaterialQuantities.empty()),
                new RestockTransferSnapshot(RestockTransferStatus.IDLE,
                        MaterialQuantities.empty(), ""));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(), NO_RESERVES, portsWithDepots(harness, settling));
        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.empty(), false, "lost build acknowledgement"));
        supervisor.pause();
        supervisor.tick();

        assertTrue(supervisor.requiresReconciliation());
        assertFalse(harness.checkpoints.saved.withdrawalInFlight());
        assertTrue(harness.checkpoints.saved.reconciliationRequired());
        assertTrue(supervisor.status().lastError().contains("lost build acknowledgement"));
        assertThrows(IllegalStateException.class, supervisor::resume);
    }

    private static SchematicSupervisor startedForSettlement(Harness harness) {
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.autoComplete = false;
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        return supervisor;
    }

    @Test
    void honorsExactMidOrderMaterialRequestAndThenContinuesSameCommand() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.depots.put(new DepotId("bulk"), MaterialQuantities.of(Material.DIRT, 63));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        harness.execution.scripted.add(ExecutionSnapshot.succeeded(
                2,
                MaterialQuantities.of(Material.DIRT, 1)
        ));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, supervisor.status().state());

        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        supervisor.tick();

        assertEquals(1, harness.execution.started.size());
        assertEquals(MaterialQuantities.of(Material.DIRT, 63), supervisor.status().materials().withdrawn());
        assertEquals(BuildPhase.VERIFY, supervisor.status().phase());
    }

    @Test
    void accumulatesExactPartialWithdrawalDeltasAcrossAsyncPolls() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        harness.execution.scripted.add(ExecutionSnapshot.succeeded(
                2,
                MaterialQuantities.of(Material.DIRT, 1)
        ));
        PartialDepots partialDepots = new PartialDepots(harness.inventory);
        SupervisorPorts ports = new SupervisorPorts(
                harness.execution,
                harness.inventory,
                partialDepots,
                harness.verification,
                harness.health,
                harness.advisor,
                harness.checkpoints,
                harness.notifications,
                harness.clock
        );
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, ports);

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 20),
                supervisor.status().materials().withdrawn()
        );
        assertEquals(SupervisorState.RESTOCKING, supervisor.status().state());
        supervisor.tick();
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 63),
                supervisor.status().materials().withdrawn()
        );
        supervisor.tick();
        supervisor.tick();

        assertEquals(BuildPhase.VERIFY, supervisor.status().phase());
        assertEquals(1, partialDepots.beginCount);
        assertEquals(2, partialDepots.pollCount);
    }

    @Test
    void pausePollsCancelledWithdrawalToTerminalAndPreservesLateDeltas() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        SettlingDepots settling = new SettlingDepots(
                harness.inventory,
                RestockTransferSnapshot.running(MaterialQuantities.of(Material.DIRT, 20)),
                RestockTransferSnapshot.running(MaterialQuantities.of(Material.DIRT, 30)),
                new RestockTransferSnapshot(
                        RestockTransferStatus.IDLE,
                        MaterialQuantities.of(Material.DIRT, 13),
                        ""
                )
        );
        SchematicSupervisor supervisor = new SchematicSupervisor(
                plan,
                NO_RESERVES,
                portsWithDepots(harness, settling)
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        assertEquals(1, settling.beginCount);

        supervisor.pause();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertTrue(supervisor.requiresReconciliation());
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 20),
                supervisor.status().materials().withdrawn()
        );
        assertThrows(IllegalStateException.class, supervisor::resume);
        assertEquals(1, settling.beginCount);

        supervisor.tick();
        assertTrue(supervisor.requiresReconciliation());
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 50),
                supervisor.status().materials().withdrawn()
        );
        assertEquals(1, settling.beginCount);

        supervisor.tick();
        assertFalse(supervisor.requiresReconciliation());
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 63),
                supervisor.status().materials().withdrawn()
        );
        assertEquals(3, settling.pollCount);

        supervisor.resume();
        assertEquals(SupervisorState.RESTOCKING, supervisor.status().state());
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(1, settling.beginCount);
    }

    @Test
    void clientStoppingDuringCancelledWithdrawalPersistsResetOnlyMarker() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        SettlingDepots settling = new SettlingDepots(
                harness.inventory,
                RestockTransferSnapshot.running(MaterialQuantities.of(Material.DIRT, 20)),
                RestockTransferSnapshot.running(MaterialQuantities.of(Material.DIRT, 43))
        );
        SchematicSupervisor supervisor = new SchematicSupervisor(
                plan,
                NO_RESERVES,
                portsWithDepots(harness, settling)
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        supervisor.stop();
        assertTrue(supervisor.requiresReconciliation());

        supervisor.markClientStopping();
        SupervisorCheckpoint shutdownCheckpoint = harness.checkpoints.saved;
        assertTrue(shutdownCheckpoint.reconciliationRequired());
        assertTrue(shutdownCheckpoint.reconciliationDetail().contains("Reset is required"));
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 20),
                shutdownCheckpoint.withdrawnMaterials()
        );

        Harness restarted = new Harness();
        restarted.inventory.set(harness.inventory.snapshot());
        restarted.checkpoints.saved = shutdownCheckpoint;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                plan,
                NO_RESERVES,
                restarted.ports()
        );

        assertEquals(SupervisorState.STOPPED, restored.status().state());
        assertTrue(restored.requiresReconciliation());
        assertTrue(restored.status().lastError().contains("Reset is required"));
        assertThrows(IllegalStateException.class, restored::start);
        restored.tick();
        assertEquals(0, restarted.depots.beginCount);

        restarted.checkpoints.clear();
        SchematicSupervisor afterReset = SchematicSupervisor.loadOrCreate(
                plan,
                NO_RESERVES,
                restarted.ports()
        );
        assertFalse(afterReset.requiresReconciliation());
        afterReset.start();
        assertEquals(SupervisorState.BUILDING, afterReset.status().state());
    }

    @Test
    void restoreOfActiveWithdrawalFailsClosedAfterAbnormalTermination() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.depots.put(
                new DepotId("crash-stock"),
                MaterialQuantities.of(Material.DIRT, 63)
        );
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                plan,
                NO_RESERVES,
                harness.ports()
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        SupervisorCheckpoint inFlight = harness.checkpoints.saved;
        assertEquals(SupervisorState.RESTOCKING, inFlight.state());
        assertTrue(inFlight.withdrawalInFlight());

        Harness restarted = new Harness();
        restarted.inventory.set(harness.inventory.snapshot());
        restarted.checkpoints.saved = inFlight;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                plan,
                NO_RESERVES,
                restarted.ports()
        );

        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertTrue(restored.requiresReconciliation());
        assertTrue(restored.status().lastError().contains("in flight"));
        assertTrue(restored.status().lastError().contains("Reset is required"));
        assertThrows(IllegalStateException.class, restored::resume);
        restored.tick();
        assertEquals(0, restarted.depots.beginCount);
    }

    @Test
    void cancelledWithdrawalOverageIsRecordedAndRemainsFailClosedAfterTerminal() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        SettlingDepots settling = new SettlingDepots(
                harness.inventory,
                RestockTransferSnapshot.running(MaterialQuantities.of(Material.DIRT, 64)),
                new RestockTransferSnapshot(
                        RestockTransferStatus.IDLE,
                        MaterialQuantities.empty(),
                        ""
                )
        );
        SchematicSupervisor supervisor = new SchematicSupervisor(
                plan,
                NO_RESERVES,
                portsWithDepots(harness, settling)
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        supervisor.pause();
        supervisor.tick();

        assertEquals(
                MaterialQuantities.of(Material.DIRT, 64),
                supervisor.status().materials().withdrawn()
        );
        assertTrue(supervisor.requiresReconciliation());
        assertTrue(supervisor.status().lastError().contains("moved more"));
        assertThrows(IllegalStateException.class, supervisor::resume);
    }

    @Test
    void activeWithdrawalOverageRemainsResetRequiredAfterCleanCancellation() {
        SchematicPlan plan = dirtPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                1,
                MaterialQuantities.of(Material.DIRT, 64)
        ));
        OverTransferDepots overTransfer = new OverTransferDepots(harness.inventory);
        SchematicSupervisor supervisor = new SchematicSupervisor(
                plan,
                NO_RESERVES,
                portsWithDepots(harness, overTransfer)
        );

        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(
                MaterialQuantities.of(Material.DIRT, 64),
                supervisor.status().materials().withdrawn()
        );
        assertTrue(supervisor.requiresReconciliation());
        assertTrue(supervisor.status().lastError().contains("active withdrawal allowed"));
        assertEquals(1, overTransfer.cancelCount);
        assertThrows(IllegalStateException.class, supervisor::resume);
    }

    @Test
    void confirmedPlacementRenewsRecoveryForTheNextTarget() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = restoredWithExhaustedAttempts(harness);
        supervisor.resume();
        supervisor.tick();
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.RUNNING, 1,
                MaterialQuantities.empty(), MaterialQuantities.of(Material.DIRT, 1), ""));
        supervisor.tick();
        assertRecoveryAttempts(supervisor, false);
        assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                harness.checkpoints.saved.consumedMaterials());
        harness.execution.scripted.add(ExecutionSnapshot.failed(1, "next target is occluded"));
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        assertEquals(RecoveryStage.REPATH, supervisor.status().recoveryStage());
        assertTrue(harness.advisor.incidents.isEmpty());
        assertEquals(0, supervisor.checkpoint().scheduleCursor());
        assertEquals(0, supervisor.checkpoint().currentChunkIndex());
    }

    @Test
    void confirmationAndNextFailureInOnePollRenewBeforeRecoveryDispatch() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = restoredWithExhaustedAttempts(harness);
        supervisor.resume();
        supervisor.tick();
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.FAILED, 1,
                MaterialQuantities.empty(), MaterialQuantities.of(Material.DIRT, 1),
                "next target is occluded"));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        assertEquals(RecoveryStage.STOP_MOVEMENT, supervisor.status().recoveryStage());
        assertRecoveryAttempts(supervisor, false);
        assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                harness.checkpoints.saved.consumedMaterials());
        supervisor.tick();
        supervisor.tick();
        assertEquals(RecoveryStage.REPATH, supervisor.status().recoveryStage());
        assertTrue(harness.checkpoints.saved.withdrawnMaterials().isEmpty());
    }

    @Test
    void lateConfirmedConsumptionRenewsAttemptsWithoutResumingOrClearingSettlement() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = restoredWithExhaustedAttempts(harness);
        SupervisorCheckpoint before = supervisor.checkpoint();
        harness.execution.settlements.add(new ExecutionSettlementSnapshot(
                MaterialQuantities.of(Material.DIRT, 1), true, ""));
        supervisor.pause();
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertRecoveryAttempts(supervisor, false);
        assertTrue(supervisor.requiresReconciliation());
        assertEquals(before.restockRequirement(), supervisor.checkpoint().restockRequirement());
        assertEquals(before.missingMaterials(), supervisor.checkpoint().missingMaterials());
        assertEquals(before.recoveryStage(), supervisor.checkpoint().recoveryStage());
        assertEquals(before.planId(), supervisor.checkpoint().planId());
        assertEquals(before.scheduleCursor(), supervisor.checkpoint().scheduleCursor());
        assertTrue(harness.execution.started.isEmpty());
        assertThrows(IllegalStateException.class, supervisor::resume);
        supervisor.tick();
        assertFalse(supervisor.requiresReconciliation());
        assertEquals(MaterialQuantities.of(Material.DIRT, 1),
                harness.checkpoints.saved.consumedMaterials());
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
    }

    @Test
    void resumeMovementAndInventoryGainsDoNotRenewRecoveryAttempts() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = restoredWithExhaustedAttempts(harness);
        for (int attempt = 0; attempt < 2; attempt++) {
            supervisor.resume();
            supervisor.tick();
            harness.execution.scripted.add(ExecutionSnapshot.running(100 + attempt));
            harness.inventory.add(MaterialQuantities.of(Material.DIRT, 64));
            supervisor.tick();
            supervisor.pause();
            assertRecoveryAttempts(supervisor, true);
            assertTrue(supervisor.checkpoint().consumedMaterials().isEmpty());
        }
    }

    private static SchematicSupervisor restoredWithExhaustedAttempts(Harness harness) {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt")),
                new TargetBlock(new BlockPosition(1, 0, 0), new BlockState("minecraft:dirt"))));
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 8));
        harness.execution.autoComplete = false;
        harness.checkpoints.saved = new SupervisorCheckpoint(SupervisorCheckpoint.CURRENT_VERSION,
                plan.planId(), SupervisorState.PAUSED, SupervisorState.BUILDING, SupervisorState.BUILDING,
                0, BuildPhase.ORDINARY_BLOCKS, VerificationStage.CHUNK, RecoveryStage.NONE, 0, "",
                MaterialQuantities.empty(), MaterialQuantities.empty(), MaterialQuantities.empty(),
                MaterialQuantities.empty(), "previous target exhausted recovery", 0,
                true, true, true, false, false, "", LayerBuildSchedule.ID, 0, -1);
        return SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
    }

    private static void assertRecoveryAttempts(SchematicSupervisor supervisor, boolean attempted) {
        assertEquals(attempted, supervisor.checkpoint().repathAttempted());
        assertEquals(attempted, supervisor.checkpoint().safeReturnAttempted());
        assertEquals(attempted, supervisor.checkpoint().advisorAttempted());
    }

    @Test
    void screenWaitPreservesRemainingStallBudgetAcrossRepeatedOpenAndClose() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        for (int cycle = 0; cycle < 2; cycle++) {
            harness.clock.advance(Duration.ofSeconds(7));
            supervisor.tick();
            harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
            supervisor.tick();
            harness.clock.advance(Duration.ofMinutes(10));
            harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
            supervisor.tick();
            assertEquals(SupervisorState.BUILDING, supervisor.status().state());
            assertEquals(RecoveryStage.NONE, supervisor.status().recoveryStage());
            assertRecoveryAttempts(supervisor, false);
            supervisor.tick();
            assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        }
        assertEquals(1, harness.execution.started.size());
        assertTrue(supervisor.checkpoint().consumedMaterials().isEmpty());
        assertTrue(harness.advisor.incidents.isEmpty());
        harness.clock.advance(Duration.ofSeconds(1));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        assertEquals(RecoveryStage.STOP_MOVEMENT, supervisor.status().recoveryStage());
    }

    @Test
    void screenWaitDoesNotRenewPreviouslyExhaustedRecoveryAttempts() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = restoredWithExhaustedAttempts(harness);
        supervisor.resume();
        supervisor.tick();
        harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.clock.advance(Duration.ofHours(2));
        harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertRecoveryAttempts(supervisor, true);
        assertTrue(supervisor.checkpoint().consumedMaterials().isEmpty());
        assertEquals(1, harness.execution.started.size());
    }

    @Test
    void receiptSettledDuringScreenWaitIsPersistedExactlyOnce() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        harness.execution.scripted.add(screenWait(MaterialQuantities.of(Material.DIRT, 1)));
        supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.checkpoints.saved.consumedMaterials());
        harness.clock.advance(Duration.ofMinutes(10));
        harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.execution.scripted.add(ExecutionSnapshot.succeeded(1, MaterialQuantities.empty()));
        supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.checkpoints.saved.consumedMaterials());
        assertEquals(SupervisorState.VERIFYING, supervisor.status().state());
    }

    @Test
    void screenWaitDoesNotSuppressARealExecutionFailure() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.clock.advance(Duration.ofMinutes(10));
        harness.execution.scripted.add(ExecutionSnapshot.failed(0, "Receipt rejected by server"));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        assertEquals("Receipt rejected by server", supervisor.status().lastError());
    }

    @Test
    void explicitPauseAndStopRemainEffectiveWhileWaitingForScreen() {
        for (boolean pause : new boolean[]{true, false}) {
            Harness harness = runningHarness();
            SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
            supervisor.start();
            supervisor.tick();
            harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
            supervisor.tick();
            if (pause) { supervisor.pause(); } else { supervisor.stop(); }
            harness.clock.advance(Duration.ofHours(1));
            supervisor.tick();
            assertEquals(pause ? SupervisorState.PAUSED : SupervisorState.STOPPED, supervisor.status().state());
            assertTrue(harness.execution.stopCount > 0);
            assertEquals(1, harness.execution.started.size());
            assertTrue(harness.advisor.incidents.isEmpty());
            if (pause) {
                supervisor.resume();
                supervisor.tick();
                stall(supervisor, harness.clock);
            }
        }
    }

    private static ExecutionSnapshot screenWait(MaterialQuantities consumed) {
        return new ExecutionSnapshot(ExecutionStatus.WAITING_FOR_SCREEN, 0,
                MaterialQuantities.empty(), consumed, "Waiting for user inventory interaction");
    }

    @Test
    void maintenanceWaitPreservesActiveStallBudgetWhenReturningToRunning() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        for (int cycle = 0; cycle < 2; cycle++) {
            harness.clock.advance(Duration.ofSeconds(7));
            supervisor.tick();
            harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
            supervisor.tick();
            harness.clock.advance(Duration.ofMinutes(10));
            harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
            supervisor.tick();
            assertEquals(SupervisorState.BUILDING, supervisor.status().state());
            assertRecoveryAttempts(supervisor, false);
            // A terminal maintenance receipt returns the same work order to ordinary execution.
            supervisor.tick();
            assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        }
        assertEquals(1, harness.execution.started.size());
        assertTrue(supervisor.checkpoint().consumedMaterials().isEmpty());
        assertTrue(harness.advisor.incidents.isEmpty());
        harness.clock.advance(Duration.ofSeconds(1));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        assertEquals(RecoveryStage.STOP_MOVEMENT, supervisor.status().recoveryStage());
    }

    @Test
    void switchingBetweenScreenAndMaintenanceWaitDoesNotConsumeOrRenewStallBudget() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        harness.clock.advance(Duration.ofSeconds(14));
        supervisor.tick();
        harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.clock.advance(Duration.ofHours(1));
        harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.clock.advance(Duration.ofHours(1));
        harness.execution.scripted.add(screenWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.clock.advance(Duration.ofHours(1));
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        harness.clock.advance(Duration.ofSeconds(1));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
    }

    @Test
    void maintenanceWaitDoesNotRenewExhaustedRecoveryAndStillReportsReceiptFailure() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = restoredWithExhaustedAttempts(harness);
        supervisor.resume();
        supervisor.tick();
        harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.clock.advance(Duration.ofMinutes(10));
        harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertRecoveryAttempts(supervisor, true);
        assertTrue(supervisor.checkpoint().consumedMaterials().isEmpty());
        harness.execution.scripted.add(ExecutionSnapshot.failed(0, "Repair receipt not confirmed"));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        assertEquals("Repair receipt not confirmed", supervisor.status().lastError());
    }

    @Test
    void receiptSettledDuringMaintenanceWaitIsPersistedExactlyOnceBeforeCompletion() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        harness.execution.scripted.add(maintenanceWait(MaterialQuantities.of(Material.DIRT, 1)));
        supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.checkpoints.saved.consumedMaterials());
        harness.clock.advance(Duration.ofMinutes(10));
        harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
        supervisor.tick();
        harness.execution.scripted.add(ExecutionSnapshot.succeeded(1, MaterialQuantities.empty()));
        supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.checkpoints.saved.consumedMaterials());
        assertEquals(SupervisorState.VERIFYING, supervisor.status().state());
    }

    @Test
    void explicitPauseAndStopCancelConstructionDuringMaintenanceWait() {
        for (boolean pause : new boolean[]{true, false}) {
            Harness harness = runningHarness();
            SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
            supervisor.start();
            supervisor.tick();
            harness.execution.scripted.add(maintenanceWait(MaterialQuantities.empty()));
            supervisor.tick();
            if (pause) { supervisor.pause(); } else { supervisor.stop(); }
            harness.clock.advance(Duration.ofHours(1));
            supervisor.tick();
            assertEquals(pause ? SupervisorState.PAUSED : SupervisorState.STOPPED, supervisor.status().state());
            assertTrue(harness.execution.stopCount > 0);
            assertEquals(1, harness.execution.started.size());
            assertTrue(harness.advisor.incidents.isEmpty());
            if (pause) {
                supervisor.resume();
                supervisor.tick();
                stall(supervisor, harness.clock);
            }
        }
    }

    private static ExecutionSnapshot maintenanceWait(MaterialQuantities consumed) {
        return new ExecutionSnapshot(ExecutionStatus.WAITING_FOR_MAINTENANCE, 0,
                MaterialQuantities.empty(), consumed, "Waiting for server repair receipt");
    }

    @Test
    void followsStallRecoveryOrderBeforeConsultingAdvisor() {
        Harness harness = runningHarness();
        harness.advisor.responses.add(AdviceSnapshot.succeeded(RecoveryAdvice.PAUSE_AND_ALERT));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();

        stall(supervisor, harness.clock);
        supervisor.tick();
        assertEquals(RecoveryStage.CHECK_MATERIALS, supervisor.status().recoveryStage());
        supervisor.tick();
        assertEquals(RecoveryStage.REPATH, supervisor.status().recoveryStage());
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(1, harness.execution.cancelCount);
        assertTrue(supervisor.checkpoint().repathAttempted());

        stall(supervisor, harness.clock);
        supervisor.tick();
        supervisor.tick();
        assertEquals(RecoveryStage.RETURN_TO_SAFE_POSITION, supervisor.status().recoveryStage());
        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_SAFE_POSITION, supervisor.status().recoveryStage());
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(1, harness.execution.safeReturnCount);

        stall(supervisor, harness.clock);
        supervisor.tick();
        supervisor.tick();
        assertEquals(RecoveryStage.ASK_ADVISOR, supervisor.status().recoveryStage());
        assertTrue(harness.advisor.incidents.isEmpty());
        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_ADVISOR, supervisor.status().recoveryStage());
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(1, harness.advisor.incidents.size());
        RecoveryIncident incident = harness.advisor.incidents.getFirst();
        assertEquals(1, incident.chunkOrdinal());
        assertEquals(BuildPhase.ORDINARY_BLOCKS, incident.phase());
        assertTrue(incident.repathAttempted());
        assertTrue(incident.safeReturnAttempted());
        assertEquals(1, harness.execution.cancelCount);
    }

    @Test
    void safeReturnCanRemainPendingAndRestartsOnlyAfterConfirmedArrival() {
        Harness harness = runningHarness();
        harness.execution.restartResults.add(false);
        harness.execution.restartResults.add(true);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.running());
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.succeeded());
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();
        stall(supervisor, harness.clock);
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        assertEquals(RecoveryStage.RETURN_TO_SAFE_POSITION, supervisor.status().recoveryStage());

        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_SAFE_POSITION, supervisor.status().recoveryStage());
        assertEquals(1, harness.execution.restartCallCount);
        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_SAFE_POSITION, supervisor.status().recoveryStage());
        assertEquals(1, harness.execution.restartCallCount);
        supervisor.tick();

        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(2, harness.execution.restartCallCount);
    }

    @Test
    void safeReturnScreenWaitPreservesRemainingBudgetAcrossRepeatedOpenAndClose() {
        Harness harness = harnessWaitingForSafeReturn();
        SchematicSupervisor supervisor = harness.supervisor;
        for (int cycle = 0; cycle < 2; cycle++) {
            harness.clock.advance(Duration.ofSeconds(7));
            harness.execution.safeReturnResults.add(SafeReturnSnapshot.running());
            supervisor.tick();
            harness.execution.safeReturnResults.add(new SafeReturnSnapshot(SafeReturnStatus.WAITING_FOR_SCREEN, ""));
            supervisor.tick();
            harness.clock.advance(Duration.ofMinutes(10));
            harness.execution.safeReturnResults.add(new SafeReturnSnapshot(SafeReturnStatus.WAITING_FOR_SCREEN, ""));
            supervisor.tick();
            assertEquals(RecoveryStage.WAIT_FOR_SAFE_POSITION, supervisor.status().recoveryStage());
            harness.execution.safeReturnResults.add(SafeReturnSnapshot.running());
            supervisor.tick();
            assertEquals(RecoveryStage.WAIT_FOR_SAFE_POSITION, supervisor.status().recoveryStage());
        }
        assertEquals(1, harness.execution.safeReturnCount);
        assertEquals(0, harness.execution.cancelSafeReturnCount);
        assertTrue(harness.advisor.incidents.isEmpty());
        harness.clock.advance(Duration.ofSeconds(1));
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.running());
        supervisor.tick();
        assertEquals(RecoveryStage.ASK_ADVISOR, supervisor.status().recoveryStage());
        assertEquals(1, harness.execution.cancelSafeReturnCount);
        assertTrue(supervisor.status().lastError().contains("safe position timed out"));
    }

    @Test
    void safeReturnCanCompleteAfterLongScreenWaitWithoutRestartingItsRoute() {
        Harness harness = harnessWaitingForSafeReturn();
        SchematicSupervisor supervisor = harness.supervisor;
        harness.execution.safeReturnResults.add(new SafeReturnSnapshot(SafeReturnStatus.WAITING_FOR_SCREEN, ""));
        supervisor.tick();
        harness.clock.advance(Duration.ofHours(1));
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.succeeded());
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(1, harness.execution.safeReturnCount);
        assertEquals(2, harness.execution.restartCallCount);
        assertEquals(0, harness.execution.cancelSafeReturnCount);
    }

    @Test
    void explicitPauseAndStopCancelSafeReturnWhileWaitingForScreen() {
        for (boolean pause : new boolean[]{true, false}) {
            Harness harness = harnessWaitingForSafeReturn();
            SchematicSupervisor supervisor = harness.supervisor;
            harness.execution.safeReturnResults.add(new SafeReturnSnapshot(SafeReturnStatus.WAITING_FOR_SCREEN, ""));
            supervisor.tick();
            if (pause) { supervisor.pause(); } else { supervisor.stop(); }
            harness.clock.advance(Duration.ofHours(1));
            supervisor.tick();
            assertEquals(pause ? SupervisorState.PAUSED : SupervisorState.STOPPED, supervisor.status().state());
            assertEquals(1, harness.execution.cancelSafeReturnCount);
            assertEquals(1, harness.execution.safeReturnCount);
            assertEquals(1, harness.execution.restartCallCount);
        }
    }

    private static Harness harnessWaitingForSafeReturn() {
        Harness harness = runningHarness();
        harness.execution.restartResults.add(false);
        harness.supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        harness.supervisor.start();
        harness.supervisor.tick();
        stall(harness.supervisor, harness.clock);
        harness.supervisor.tick();
        harness.supervisor.tick();
        harness.supervisor.tick();
        harness.supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_SAFE_POSITION, harness.supervisor.status().recoveryStage());
        return harness;
    }

    @Test
    void failedSafeReturnAdvancesToAdvisorWithoutOverwritingTheGoal() {
        Harness harness = runningHarness();
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("blocked"));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();
        stall(supervisor, harness.clock);
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();

        assertEquals(RecoveryStage.ASK_ADVISOR, supervisor.status().recoveryStage());
        assertTrue(supervisor.status().lastError().contains("blocked"));
        assertEquals(1, harness.execution.restartCallCount);
    }

    @Test
    void advisorPollingIsNonBlockingAndHandlesPendingThenSuccess() {
        Harness harness = harnessAtAdvisor();
        harness.advisor.responses.add(AdviceSnapshot.pending());
        harness.advisor.responses.add(AdviceSnapshot.succeeded(RecoveryAdvice.PAUSE_AND_ALERT));
        SchematicSupervisor supervisor = harness.supervisor;

        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_ADVISOR, supervisor.status().recoveryStage());
        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_ADVISOR, supervisor.status().recoveryStage());
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        supervisor.tick();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(1, harness.advisor.beginCount);
        assertEquals(2, harness.advisor.pollCount);
    }

    @Test
    void advisorUnavailableAndInvalidResultsPauseSafely() {
        Harness unavailable = harnessAtAdvisor();
        unavailable.advisor.responses.add(AdviceSnapshot.unavailable("companion offline"));
        unavailable.supervisor.tick();
        unavailable.supervisor.tick();
        assertEquals(SupervisorState.PAUSED, unavailable.supervisor.status().state());
        assertTrue(unavailable.supervisor.status().lastError().contains("companion offline"));

        Harness invalid = harnessAtAdvisor();
        invalid.advisor.returnNull = true;
        invalid.supervisor.tick();
        invalid.supervisor.tick();
        assertEquals(SupervisorState.PAUSED, invalid.supervisor.status().state());
        assertTrue(invalid.supervisor.status().lastError().contains("invalid result"));
    }

    @Test
    void rectangularRunExecutesIndexSixtyNineVerifiesEveryChunkAndRestoresItsCompletedCheckpoint() {
        SchematicPlan plan = SchematicCompiler.compile(new BuildVolume(0, 0, 0, 159, 0, 111),
                List.of(new TargetBlock(new BlockPosition(144, 0, 96), new BlockState("minecraft:dirt"))));
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        assertEquals(70, supervisor.status().chunkTotal());
        supervisor.start();
        tickUntil(supervisor, SupervisorState.DONE, 300);
        assertEquals(List.of(69), harness.execution.started.stream().map(WorkOrder::chunkIndex).toList());
        assertEquals(java.util.stream.IntStream.range(0, 70).boxed().toList(),
                harness.verification.scopes.stream().filter(scope -> scope.kind() == VerificationScope.Kind.CHUNK)
                        .map(VerificationScope::chunkIndex).toList());
        assertEquals(72, harness.verification.scopes.size());
        SupervisorCheckpoint completed = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(supervisor.checkpoint()));
        assertEquals(70, completed.chunkCount());
        assertEquals(70, completed.currentChunkIndex());
        harness.checkpoints.saved = completed;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.DONE, restored.status().state());
        assertEquals(70, restored.status().chunkTotal());
        assertEquals(2, restored.status().stableVerificationPasses());
        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(completed)
                .replace("\"chunk_count\": 70", "\"chunk_count\": 71"));
        assertThrows(IllegalArgumentException.class,
                () -> SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports()));
    }

    @Test
    void legacyCheckpointWithoutChunkCountKeepsItsPausedLayerCursor() {
        SchematicPlan plan = representativePlan();
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.pause();
        SupervisorCheckpoint saved = supervisor.checkpoint();
        String legacy = CheckpointJsonCodec.toJson(saved).replace("  \"chunk_count\": 49,\n", "");
        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(legacy);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(saved.planId(), restored.checkpoint().planId());
        assertEquals(saved.scheduleCursor(), restored.checkpoint().scheduleCursor());
        assertEquals(saved.currentChunkIndex(), restored.checkpoint().currentChunkIndex());
        assertEquals(saved.phase(), restored.checkpoint().phase());
        assertEquals(saved.consumedMaterials(), restored.checkpoint().consumedMaterials());
    }

    @Test
    void unavailableAdvisorPreservesExecutionAndSafeReturnFailuresAcrossCheckpointReload() {
        Harness harness = runningHarness();
        String executionError = "No reachable support face for dirt placement";
        harness.execution.scripted.add(ExecutionSnapshot.failed(0, executionError));
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("no flight route"));
        harness.advisor.responses.add(AdviceSnapshot.unavailable("Companion request unavailable"));
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        tickUntil(supervisor, SupervisorState.PAUSED, 12);

        String error = supervisor.status().lastError();
        assertTrue(error.startsWith(executionError), error);
        assertTrue(error.contains("Return to safe position failed: no flight route"), error);
        assertTrue(error.contains("Advisor unavailable after deterministic recovery: Companion request unavailable"), error);
        assertTrue(harness.advisor.incidents.getFirst().lastError().startsWith(executionError));
        assertTrue(harness.advisor.incidents.getFirst().lastError().contains("no flight route"));
        assertEquals(error, harness.checkpoints.saved.lastError());
        assertTrue(harness.notifications.messages.getLast().startsWith(error));
        assertEquals(error, SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports())
                .status().lastError());
    }

    @Test
    void advisorTimeoutCancelsPendingRequestAndPauses() {
        Harness harness = harnessAtAdvisor();
        harness.advisor.responses.add(AdviceSnapshot.pending());
        harness.supervisor.tick();
        harness.supervisor.tick();
        harness.clock.advance(Duration.ofSeconds(15));
        harness.supervisor.tick();

        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
        assertTrue(harness.supervisor.status().lastError().contains("timed out"));
        assertTrue(harness.supervisor.status().lastError().contains("No build progress for 15 seconds"));
        assertTrue(harness.supervisor.status().lastError().contains("no route"));
        assertTrue(harness.advisor.cancelCount >= 1);
    }

    @Test
    void waitsTenSecondsForLagBeforeContinuingRecovery() {
        Harness harness = runningHarness();
        harness.health.laggy = true;
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();
        stall(supervisor, harness.clock);

        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_LAG, supervisor.status().recoveryStage());
        harness.clock.advance(Duration.ofSeconds(9));
        supervisor.tick();
        assertEquals(RecoveryStage.WAIT_FOR_LAG, supervisor.status().recoveryStage());
        harness.clock.advance(Duration.ofSeconds(1));
        supervisor.tick();
        assertEquals(RecoveryStage.CHECK_MATERIALS, supervisor.status().recoveryStage());
    }

    @Test
    void restoresActiveCheckpointAsSafePauseAtSameChunkAndPhase() {
        SchematicPlan plan = dirtPlan();
        Harness original = new Harness();
        original.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        SchematicSupervisor first = new SchematicSupervisor(plan, NO_RESERVES, original.ports());
        first.start();
        first.tick();
        first.tick();
        assertEquals(BuildPhase.VERIFY, first.status().phase());

        Harness restoredHarness = new Harness();
        restoredHarness.checkpoints.saved = original.checkpoints.saved;
        restoredHarness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                plan,
                NO_RESERVES,
                restoredHarness.ports()
        );

        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(1, restored.status().currentChunkOrdinal());
        assertEquals(BuildPhase.VERIFY, restored.status().phase());
        restored.resume();
        assertEquals(SupervisorState.VERIFYING, restored.status().state());
        restored.tick();
        assertEquals(1, restored.status().currentChunkOrdinal());
        restored.tick();
        assertEquals(2, restored.status().currentChunkOrdinal());
    }

    @Test
    void rejectsCheckpointForDifferentPlan() {
        Harness harness = new Harness();
        harness.checkpoints.saved = checkpointFor("different-plan");

        assertThrows(
                IllegalArgumentException.class,
                () -> SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports())
        );
    }

    @Test
    void manualPauseAndStopAreInertAndStopMovement() {
        Harness harness = runningHarness();
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();
        supervisor.pause();

        int startsBeforePausedTick = harness.execution.started.size();
        supervisor.tick();
        assertEquals(startsBeforePausedTick, harness.execution.started.size());
        assertEquals(1, harness.execution.stopCount);

        supervisor.resume();
        supervisor.tick();
        assertEquals(startsBeforePausedTick + 1, harness.execution.started.size());
        supervisor.stop();
        supervisor.tick();
        assertEquals(SupervisorState.STOPPED, supervisor.status().state());
        assertEquals(2, harness.execution.stopCount);
    }

    @Test
    void pausePersistsFailClosedStateWhenStoppingMovementThrows() {
        Harness harness = runningHarness();
        harness.execution.stopFailure = new IllegalStateException("pathing adapter unavailable");
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();

        supervisor.pause();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(SupervisorState.PAUSED, harness.checkpoints.saved.state());
        assertTrue(supervisor.requiresReconciliation());
        assertTrue(supervisor.status().lastError().contains("while pausing"));
        assertTrue(supervisor.status().lastError().contains("pathing adapter unavailable"));
        assertTrue(supervisor.status().lastError().contains("Reset is required"));
        assertThrows(IllegalStateException.class, supervisor::resume);
    }

    @Test
    void stopPersistsFailClosedStateWhenStoppingMovementThrows() {
        Harness harness = runningHarness();
        harness.execution.stopFailure = new IllegalStateException("pathing adapter unavailable");
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan(),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        supervisor.tick();

        supervisor.stop();

        assertEquals(SupervisorState.STOPPED, supervisor.status().state());
        assertEquals(SupervisorState.STOPPED, harness.checkpoints.saved.state());
        assertTrue(supervisor.requiresReconciliation());
        assertTrue(supervisor.status().lastError().contains("while stopping"));
        assertTrue(supervisor.status().lastError().contains("pathing adapter unavailable"));
        assertTrue(supervisor.status().lastError().contains("Reset is required"));
        assertThrows(IllegalStateException.class, supervisor::start);
    }

    @Test
    void disablingFoodReserveResumesFoodOnlyCheckpointAtTheSameLayerSlice() {
        SupervisorCheckpoint saved = pausedFoodCheckpoint(false);
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.checkpoints.saved = saved;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertTrue(restored.checkpoint().restockRequirement().isEmpty());
        assertTrue(restored.status().missingMaterials().isEmpty());
        assertEquals("", restored.status().lastError());
        assertEquals(saved.scheduleCursor(), restored.checkpoint().scheduleCursor());
        assertEquals(saved.planId(), restored.checkpoint().planId());
        restored.resume();
        restored.tick();
        assertEquals(SupervisorState.BUILDING, restored.status().state());
        restored.tick();
        assertEquals(1, harness.execution.started.size());
        assertEquals(0, harness.execution.started.getFirst().chunkIndex());
        assertEquals(0, harness.depots.beginCount);
    }

    @Test
    void disablingFoodReservePreservesMixedConstructionShortagesAndLedger() {
        SupervisorCheckpoint saved = pausedFoodCheckpoint(true);
        Harness harness = new Harness();
        harness.checkpoints.saved = saved;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        MaterialQuantities dirtRequirement = MaterialQuantities.of(Material.DIRT, 4);
        assertEquals(dirtRequirement, restored.checkpoint().restockRequirement());
        assertEquals(dirtRequirement, restored.status().missingMaterials());
        assertEquals(saved.consumedMaterials(), restored.checkpoint().consumedMaterials());
        assertEquals(saved.withdrawnMaterials(), restored.checkpoint().withdrawnMaterials());
        assertEquals(saved.scheduleCursor(), restored.checkpoint().scheduleCursor());
        restored.resume();
        restored.tick();
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(dirtRequirement, restored.status().missingMaterials());
        assertFalse(restored.status().lastError().contains("food"));
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test
    void enabledFoodReserveStillBlocksTheSavedFoodShortage() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(false);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), SupervisorConfig.defaults(), harness.ports());
        assertEquals(MaterialQuantities.of(Material.FOOD, 1),
                restored.checkpoint().restockRequirement());
        restored.resume();
        restored.tick();
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(MaterialQuantities.of(Material.FOOD, 1), restored.status().missingMaterials());
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test
    void deferredPlantingExecutesConstructionAndTillingWithoutSeedsAndStillVerifiesEveryChunkTwice() {
        SchematicPlan plan = representativePlan().withPlantingDeferred(true);
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Map.of(Material.DIRT, 1L,
                Material.GLOWSTONE, 1L, Material.BIRCH_PLANKS, 1L, Material.HOE, 1L)));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        tickUntil(supervisor, SupervisorState.DONE, 200);
        assertEquals(List.of(BuildPhase.ORDINARY_BLOCKS, BuildPhase.ORDINARY_BLOCKS, BuildPhase.TILL),
                harness.execution.started.stream().map(WorkOrder::phase).toList());
        assertEquals(0, supervisor.status().materials().planned().get(Material.WHEAT_SEEDS));
        assertEquals(0, supervisor.status().materials().consumed().get(Material.WHEAT_SEEDS));
        assertEquals(51, harness.verification.scopes.size());
        assertEquals(2, supervisor.status().stableVerificationPasses());
        assertTrue(supervisor.status().plantingDeferred());
        assertEquals(1, supervisor.status().deferredSeedCells());
        assertTrue(harness.checkpoints.saved.plantingDeferred());
    }

    @Test
    void missingTillSoilRepairsOnlyItsPlannedDirtAndRetainsCursorLedgerAndVerification() {
        SchematicPlan plan = representativePlan().withPlantingDeferred(true);
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Map.of(Material.DIRT, 2L,
                Material.GLOWSTONE, 1L, Material.BIRCH_PLANKS, 1L, Material.HOE, 1L)));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        for (int tick = 0; tick < 30 && !(harness.execution.current instanceof WorkOrder.Till); tick++) {
            supervisor.tick();
        }
        assertTrue(harness.execution.current instanceof WorkOrder.Till);
        WorkOrder.Till till = (WorkOrder.Till) harness.execution.current;
        int cursor = supervisor.checkpoint().scheduleCursor();
        MaterialQuantities consumed = supervisor.status().materials().consumed();
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.NEEDS_PREREQUISITES,
                1, MaterialQuantities.empty(), MaterialQuantities.empty(), "Received air at planned soil"));
        supervisor.tick();
        assertEquals(cursor, supervisor.checkpoint().scheduleCursor());
        assertEquals(consumed, supervisor.status().materials().consumed());
        assertTrue(harness.verification.scopes.isEmpty());
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(plan, NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(cursor, restored.checkpoint().scheduleCursor());
        assertEquals(consumed, restored.status().materials().consumed());
        restored.resume();
        restored.tick();
        assertEquals(till, harness.execution.current);
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.NEEDS_PREREQUISITES,
                1, MaterialQuantities.empty(), MaterialQuantities.empty(), "Still missing soil after restore"));
        restored.tick();
        supervisor = restored;
        supervisor.tick();
        assertTrue(harness.execution.current instanceof WorkOrder.OrdinaryBlocks);
        var repair = (WorkOrder.OrdinaryBlocks) harness.execution.current;
        assertEquals(till.chunkIndex(), repair.chunkIndex());
        assertEquals(till.targets(), repair.placements().stream().map(OrdinaryPlacement::position).toList());
        assertTrue(repair.placements().stream().allMatch(placement -> placement.material() == Material.DIRT));
        supervisor.tick();
        assertEquals(cursor, supervisor.checkpoint().scheduleCursor());
        supervisor.tick();
        assertEquals(till, harness.execution.current);
        tickUntil(supervisor, SupervisorState.DONE, 200);
        assertEquals(2, supervisor.status().materials().consumed().get(Material.DIRT));
        assertEquals(51, harness.verification.scopes.size());
        assertEquals(2, supervisor.status().stableVerificationPasses());
    }

    @Test
    void deferredRestorationDropsOnlySeedReservesAndPreservesConstructionLedger() {
        SupervisorCheckpoint saved = pausedSeedCheckpoint(true, true);
        Harness harness = new Harness();
        harness.checkpoints.saved = saved;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan().withPlantingDeferred(true), SupervisorConfig.defaults(), harness.ports());
        assertEquals(MaterialQuantities.of(Material.DIRT, 4), restored.checkpoint().restockRequirement());
        assertEquals(MaterialQuantities.of(Material.DIRT, 4), restored.status().missingMaterials());
        assertEquals(saved.consumedMaterials(), restored.checkpoint().consumedMaterials());
        assertEquals(saved.withdrawnMaterials(), restored.checkpoint().withdrawnMaterials());
        assertEquals(saved.scheduleCursor(), restored.checkpoint().scheduleCursor());
        assertFalse(restored.status().lastError().contains("wheat_seeds"));
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(0, harness.depots.beginCount);
    }

    @Test
    void deferredSeedReserveFilteringDoesNotEraseUncertainTransfer() {
        SupervisorCheckpoint saved = pausedSeedCheckpoint(false, true);
        Harness harness = new Harness();
        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved)
                .replace("\"withdrawal_in_flight\": false", "\"withdrawal_in_flight\": true"));
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan().withPlantingDeferred(true), NO_RESERVES, harness.ports());
        assertTrue(restored.checkpoint().restockRequirement().isEmpty());
        assertTrue(restored.requiresReconciliation());
        assertThrows(IllegalStateException.class, restored::resume);
        assertEquals(0, harness.depots.beginCount);
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test
    void fullPlantingModeRetainsSavedSeedReserve() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedSeedCheckpoint(false, false);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        assertEquals(MaterialQuantities.of(Material.WHEAT_SEEDS, 1),
                restored.checkpoint().restockRequirement());
        assertEquals(MaterialQuantities.of(Material.WHEAT_SEEDS, 1), restored.status().missingMaterials());
    }

    @Test
    void deferredExecutorSeedRequestCannotStartSeedWithdrawal() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(1,
                MaterialQuantities.of(Material.WHEAT_SEEDS, 1)));
        SchematicSupervisor supervisor = new SchematicSupervisor(
                dirtPlan().withPlantingDeferred(true), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, supervisor.status().state());
        assertTrue(supervisor.checkpoint().restockRequirement().isEmpty());
        supervisor.tick();
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(0, harness.depots.beginCount);
        assertTrue(supervisor.status().missingMaterials().isEmpty());
    }

    private static SupervisorCheckpoint pausedSeedCheckpoint(boolean withDirt, boolean deferred) {
        String saved = CheckpointJsonCodec.toJson(pausedFoodCheckpoint(withDirt))
                .replace("food", "wheat_seeds");
        if (deferred) {
            saved = saved.replace("\"planting_deferred\": false", "\"planting_deferred\": true")
                    .replace("\"schedule_id\": \"" + LayerBuildSchedule.ID + "\"",
                            "\"schedule_id\": \"" + LayerBuildSchedule.DEFERRED_PLANTING_ID + "\"");
        }
        return CheckpointJsonCodec.fromJson(saved);
    }

    @Test
    void modeConversionPersistsBeforeResumeAndKeepsConfirmedDirtAtTheSameConstructionCursor() {
        Harness harness = new Harness();
        SupervisorCheckpoint saved = pausedFoodCheckpoint(true);
        harness.checkpoints.saved = saved;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan().withPlantingDeferred(true), NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(saved.consumedMaterials(), restored.status().materials().consumed());
        assertEquals(1, restored.status().materials().consumed().get(Material.DIRT));
        assertEquals(saved.withdrawnMaterials(), restored.status().materials().withdrawn());
        assertEquals(saved.scheduleCursor(), restored.checkpoint().scheduleCursor());
        assertTrue(harness.checkpoints.saved.plantingDeferred());
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test
    void rowMajorCheckpointIsMappedToTheChunkTourAndSavedBeforeResume() {
        Harness harness = new Harness();
        SupervisorCheckpoint current = pausedFoodCheckpoint(true);
        SupervisorCheckpoint legacy = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(current).replace(
                "\"schedule_id\": \"" + LayerBuildSchedule.ID + "\"",
                "\"schedule_id\": \"" + LayerBuildSchedule.ROW_MAJOR_ID + "\""));
        assertEquals(LayerBuildSchedule.ROW_MAJOR_ID, legacy.scheduleId());
        harness.checkpoints.saved = legacy;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        assertEquals(LayerBuildSchedule.ID, harness.checkpoints.saved.scheduleId());
        assertEquals(current.scheduleCursor(), harness.checkpoints.saved.scheduleCursor());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(current.consumedMaterials(), restored.status().materials().consumed());
        // The same first piece keeps its restock need; only the disabled food reserve is dropped on restore.
        assertEquals(current.missingMaterials().get(Material.DIRT),
                harness.checkpoints.saved.missingMaterials().get(Material.DIRT));
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test
    void plannedCreditCommitsTotalAndIdentityBeforeAckWithoutUnpausing() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(true);
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        PlannedConsumptionCredit credit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.execution.plannedCredit = credit;
        harness.execution.beforeCreditAck = () -> {
            assertEquals(credit, harness.checkpoints.saved.lastAppliedPlannedCredit());
            assertEquals(2, harness.checkpoints.saved.consumedMaterials().get(Material.DIRT));
            assertEquals(SupervisorState.PAUSED, harness.checkpoints.saved.state());
        };
        supervisor.tick();
        supervisor.tick();
        assertEquals(1, harness.execution.creditAcknowledgements);
        assertEquals(2, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertTrue(harness.execution.started.isEmpty());
        assertEquals(0, harness.execution.stopCount);
    }

    @Test
    void legacyFileRestoreAndDeferredModeConversionPersistVersionFourWithOneDirt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        Harness harness = new Harness();
        SupervisorCheckpoint previous = pausedFoodCheckpoint(true);
        String legacy = CheckpointJsonCodec.toJson(previous)
                .replace("\"version\": 4", "\"version\": 3")
                .replace("  \"last_applied_planned_credit\": null,\n", "");
        java.nio.file.Path path = directory.resolve("checkpoint.json");
        java.nio.file.Files.writeString(path, legacy);
        JsonFileCheckpointStore store = new JsonFileCheckpointStore(path);
        SupervisorPorts ports = new SupervisorPorts(harness.execution, harness.inventory, harness.depots,
                harness.verification, harness.health, harness.advisor, store, harness.notifications, harness.clock);
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(
                dirtPlan().withPlantingDeferred(true), NO_RESERVES, ports);
        SupervisorCheckpoint persisted = store.load().orElseThrow();
        assertEquals(4, persisted.version());
        assertTrue(java.nio.file.Files.readString(path).contains("\"version\": 4"));
        assertTrue(persisted.plantingDeferred());
        assertEquals(previous.consumedMaterials(), persisted.consumedMaterials());
        assertEquals(1, persisted.consumedMaterials().get(Material.DIRT));
        assertEquals(previous.withdrawnMaterials(), persisted.withdrawnMaterials());
        assertEquals(previous.scheduleCursor(), persisted.scheduleCursor());
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertTrue(harness.execution.started.isEmpty());
    }

    @Test
    void failedCreditSaveRollsBackLedgerIdentityAndRecoveryBeforeRetry() {
        Harness harness = new Harness();
        harness.checkpoints.saved = allRecoveryAttemptsUsed(pausedFoodCheckpoint(true));
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        SupervisorCheckpoint before = supervisor.checkpoint();
        harness.execution.plannedCredit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.checkpoints.saveFailure = new IllegalStateException("Disk could not commit");
        assertThrows(IllegalStateException.class, supervisor::tick);
        assertEquals(before, supervisor.checkpoint());
        assertEquals(0, harness.execution.creditAcknowledgements);
        assertNotNull(harness.execution.plannedCredit);
        harness.checkpoints.saveFailure = null;
        supervisor.tick();
        assertEquals(2, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        assertFalse(supervisor.checkpoint().repathAttempted());
        assertFalse(supervisor.checkpoint().safeReturnAttempted());
        assertFalse(supervisor.checkpoint().advisorAttempted());
        assertEquals(1, harness.execution.creditAcknowledgements);
    }

    @Test
    void restartBeforeCheckpointCommitReplaysOutboxExactlyOnce() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(true);
        SchematicSupervisor first = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        harness.execution.plannedCredit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.checkpoints.saveFailure = new IllegalStateException("Crash before checkpoint replacement");
        assertThrows(IllegalStateException.class, first::tick);
        harness.checkpoints.saveFailure = null;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        restored.tick();
        assertEquals(2, restored.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(1, harness.execution.creditAcknowledgements);
    }

    @Test
    void restartAfterCheckpointCommitRetriesAckWithoutCreditOrRecoveryRenewal() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(true);
        SchematicSupervisor first = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        PlannedConsumptionCredit credit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.execution.plannedCredit = credit;
        harness.execution.creditAckFailure = new IllegalStateException("Crash before journal ack");
        assertThrows(IllegalStateException.class, first::tick);
        assertEquals(2, first.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(credit, harness.checkpoints.saved.lastAppliedPlannedCredit());
        assertFalse(first.requiresReconciliation());
        harness.checkpoints.saved = allRecoveryAttemptsUsed(harness.checkpoints.saved);
        harness.execution.creditAckFailure = null;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        restored.tick();
        assertEquals(2, restored.checkpoint().consumedMaterials().get(Material.DIRT));
        assertTrue(restored.checkpoint().repathAttempted());
        assertTrue(restored.checkpoint().safeReturnAttempted());
        assertTrue(restored.checkpoint().advisorAttempted());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(2, harness.execution.creditAcknowledgements);
        SchematicSupervisor afterAck = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        afterAck.tick();
        assertEquals(2, afterAck.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(2, harness.execution.creditAcknowledgements);
    }

    @Test
    void plannedCreditRejectsWrongPlanAndIdentityPayloadMismatchWithoutAck() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(true);
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        harness.execution.plannedCredit = structuralCredit("426614174000", "another-plan", Material.DIRT);
        assertThrows(IllegalStateException.class, supervisor::tick);
        assertEquals(1, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(0, harness.execution.creditAcknowledgements);
        harness.execution.plannedCredit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        supervisor.tick();
        SupervisorCheckpoint committed = supervisor.checkpoint();
        harness.execution.plannedCredit = structuralCredit("426614174000", dirtPlan().planId(), Material.GLOWSTONE);
        assertThrows(IllegalStateException.class, supervisor::tick);
        assertEquals(committed, supervisor.checkpoint());
        assertEquals(1, harness.execution.creditAcknowledgements);
    }

    @Test
    void creditCreatedByExecutionPollIsSavedBeforeAdvancingAndIsNotDoubleCounted() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        harness.execution.creditOnPoll = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.execution.scripted.add(ExecutionSnapshot.succeeded(1, MaterialQuantities.empty()));
        harness.execution.beforeCreditAck = () -> {
            assertEquals(1, harness.checkpoints.saved.consumedMaterials().get(Material.DIRT));
            assertEquals(0, harness.checkpoints.saved.scheduleCursor());
        };
        supervisor.tick();
        assertEquals(1, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(1, harness.execution.creditAcknowledgements);
        assertEquals(BuildPhase.VERIFY, supervisor.checkpoint().phase());
    }

    @Test
    void mixedOrdinaryDeltaRemainsInMemoryWhenNewCreditCheckpointSaveFails() {
        assertMixedPollCreditFailureRetainsOrdinaryDelta(true);
    }

    @Test
    void mixedOrdinaryDeltaRemainsCommittedWhenNewCreditAcknowledgementFails() {
        assertMixedPollCreditFailureRetainsOrdinaryDelta(false);
    }

    private static void assertMixedPollCreditFailureRetainsOrdinaryDelta(boolean failSave) {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 10));
        harness.execution.autoComplete = false;
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        supervisor.start();
        supervisor.tick();
        PlannedConsumptionCredit credit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.execution.creditOnPoll = credit;
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.RUNNING, 1,
                MaterialQuantities.empty(), MaterialQuantities.of(Material.DIRT, 1), "Mixed confirmed receipts"));
        RuntimeException failure = new IllegalStateException("Credit commit boundary failed");
        if (failSave) { harness.checkpoints.saveFailure = failure; }
        else { harness.execution.creditAckFailure = failure; }

        assertSame(failure, assertThrows(IllegalStateException.class, supervisor::tick));
        assertEquals(failSave ? 1 : 2, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        // Ordinary deltas retain their existing durability boundary: only a successful
        // checkpoint write commits them. A failed write still preserves them in memory.
        assertEquals(failSave ? 0 : 2, harness.checkpoints.saved.consumedMaterials().get(Material.DIRT));
        assertEquals(failSave ? null : credit, supervisor.checkpoint().lastAppliedPlannedCredit());
        assertEquals(credit, harness.execution.plannedCredit);
        assertEquals(failSave ? 0 : 1, harness.execution.creditAcknowledgements);
        assertTrue(harness.execution.scripted.isEmpty(), "The ordinary delta was drained exactly once");

        harness.checkpoints.saveFailure = null;
        harness.execution.creditAckFailure = null;
        supervisor.tick();
        supervisor.tick();
        assertEquals(2, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        assertEquals(2, harness.checkpoints.saved.consumedMaterials().get(Material.DIRT));
        assertEquals(credit, harness.checkpoints.saved.lastAppliedPlannedCredit());
        assertEquals(failSave ? 1 : 2, harness.execution.creditAcknowledgements);
        assertEquals(1, harness.execution.started.size(), "Recovery must not replay the work order");
    }

    @Test
    void pauseSettlementRetriesCreditAckWithoutMarkingAnUnknownReceipt() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(true);
        SchematicSupervisor supervisor = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        harness.execution.plannedCredit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        harness.execution.creditAckFailure = new IllegalStateException("Transient journal write failure");
        assertThrows(IllegalStateException.class, supervisor::pause);
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertFalse(supervisor.requiresReconciliation());
        harness.execution.creditAckFailure = null;
        supervisor.pause();
        assertEquals(2, supervisor.checkpoint().consumedMaterials().get(Material.DIRT));
        assertFalse(supervisor.requiresReconciliation());
    }

    @Test
    void startCannotEraseAnUnacknowledgedPlannedCredit() {
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        harness.execution.plannedCredit = structuralCredit("426614174000", dirtPlan().planId(), Material.DIRT);
        assertThrows(IllegalStateException.class, supervisor::start);
        assertEquals(0, harness.execution.creditAcknowledgements);
        assertEquals(SupervisorState.STOPPED, supervisor.status().state());
    }

    private static PlannedConsumptionCredit structuralCredit(String suffix, String planId, Material material) {
        return new PlannedConsumptionCredit("123e4567-e89b-12d3-a456-" + suffix, planId, material, 1);
    }

    private static SupervisorCheckpoint allRecoveryAttemptsUsed(SupervisorCheckpoint saved) {
        return CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved)
                .replace("\"repath_attempted\": false", "\"repath_attempted\": true")
                .replace("\"safe_return_attempted\": false", "\"safe_return_attempted\": true")
                .replace("\"advisor_attempted\": false", "\"advisor_attempted\": true"));
    }

    @Test
    void resumingRestockRechecksExternalInventorySupplyBeforePublishingItsShortage() {
        Harness harness = new Harness();
        harness.checkpoints.saved = pausedFoodCheckpoint(true);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(dirtPlan(), NO_RESERVES, harness.ports());
        assertEquals(MaterialQuantities.of(Material.DIRT, 4), restored.status().missingMaterials());
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 576));
        restored.resume();
        assertEquals(SupervisorState.RESTOCKING, restored.status().state());
        assertTrue(restored.status().missingMaterials().isEmpty());
        assertTrue(harness.checkpoints.saved.missingMaterials().isEmpty());
        restored.tick();
        assertEquals(SupervisorState.BUILDING, restored.status().state());
        assertEquals(0, harness.depots.beginCount);
        restored.tick();
        assertEquals(1, harness.execution.started.size());
        assertEquals(0, harness.depots.beginCount);
    }

    @Test
    void disablingFoodReserveDoesNotClearAnUnsettledTransferGuard() {
        String checkpointJson = CheckpointJsonCodec.toJson(pausedFoodCheckpoint(false))
                .replace("\"withdrawal_in_flight\": false", "\"withdrawal_in_flight\": true");
        Harness harness = new Harness();
        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(checkpointJson);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        assertTrue(restored.checkpoint().restockRequirement().isEmpty());
        assertTrue(restored.requiresReconciliation());
        assertTrue(restored.status().lastError().contains("depot transfer in flight"));
        assertThrows(IllegalStateException.class, restored::resume);
        restored.tick();
        assertTrue(harness.execution.started.isEmpty());
        assertEquals(0, harness.depots.beginCount);
    }

    @Test
    void disablingFoodReservePreservesAnExistingReconciliationDetail() {
        String detail = "A cancelled transfer requires manual reconciliation.";
        String checkpointJson = CheckpointJsonCodec.toJson(pausedFoodCheckpoint(true))
                .replace("\"reconciliation_required\": false", "\"reconciliation_required\": true")
                .replace("\"reconciliation_detail\": \"\"",
                        "\"reconciliation_detail\": \"" + detail + "\"");
        Harness harness = new Harness();
        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(checkpointJson);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                dirtPlan(), NO_RESERVES, harness.ports());
        assertTrue(restored.requiresReconciliation());
        assertEquals(detail, restored.checkpoint().reconciliationDetail());
        assertEquals(harness.checkpoints.saved.consumedMaterials(),
                restored.checkpoint().consumedMaterials());
        assertThrows(IllegalStateException.class, restored::resume);
    }

    @Test
    void disabledFoodReserveFiltersNewMixedRestockRequests() {
        Harness harness = new Harness();
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(1,
                MaterialQuantities.of(Map.of(Material.FOOD, 1L, Material.DIRT, 4L))));
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES,
                harness.ports());
        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 4),
                supervisor.checkpoint().restockRequirement());
        assertEquals(MaterialQuantities.of(Material.DIRT, 4), supervisor.status().missingMaterials());
    }

    private static SupervisorCheckpoint pausedFoodCheckpoint(boolean withDirt) {
        Harness harness = new Harness();
        if (withDirt) {
            harness.inventory.set(MaterialQuantities.of(Map.of(Material.FOOD, 1L, Material.DIRT, 1L)));
            harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.RUNNING, 1,
                    MaterialQuantities.empty(), MaterialQuantities.of(Material.DIRT, 1), ""));
            harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(2,
                    MaterialQuantities.of(Map.of(Material.FOOD, 1L, Material.DIRT, 4L))));
        }
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(),
                SupervisorConfig.defaults(), harness.ports());
        supervisor.start();
        supervisor.tick();
        if (withDirt) {
            supervisor.tick();
            supervisor.tick();
            harness.inventory.set(MaterialQuantities.empty());
        }
        supervisor.tick();
        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertEquals(1, supervisor.status().missingMaterials().get(Material.FOOD));
        return CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(supervisor.checkpoint()));
    }

    @Test
    void statusReusesCachedImmutablePlanTotalsInsteadOfRescanningTargetsEveryTick() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        MaterialQuantities cached = supervisor.status().materials().planned();
        assertEquals(plan.plannedMaterials(), cached);
        for (int count = 0; count < 100; count++) {
            assertSame(cached, supervisor.status().materials().planned());
        }
    }

    @Test
    void structureFirstAutomaticallyPlacesLightsThenTillsAndVerifiesTwiceWithoutSeeds() {
        SchematicPlan plan = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true).withGlowstoneAfterStructure(true);
        Harness harness = new Harness();
        harness.inventory.set(plan.plannedMaterials().plus(MaterialQuantities.of(Material.HOE, 1)));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        tickUntil(supervisor, SupervisorState.DONE, 2000);
        assertEquals(new LayerBuildSchedule(plan).entries().stream().map(LayerBuildSchedule.Entry::order).toList(),
                harness.execution.started);
        assertEquals(plan.plannedMaterials(), supervisor.status().materials().consumed());
        assertEquals(0, supervisor.status().materials().consumed().get(Material.WHEAT_SEEDS));
        assertEquals(51, harness.verification.scopes.size());
        assertEquals(2, supervisor.status().stableVerificationPasses());
        assertTrue(supervisor.status().glowstoneAfterStructure());
        assertEquals(LayerBuildSchedule.STRUCTURE_FIRST_DEFERRED_PLANTING_ID, harness.checkpoints.saved.scheduleId());
    }

    @Test
    void structureFirstMigrationPersistsBeforeResumeAndRestartKeepsItsNewCursor() {
        SchematicPlan original = LayerBuildScheduleTest.farmPlan().withPlantingDeferred(true);
        Harness harness = new Harness();
        harness.inventory.set(original.plannedMaterials().plus(MaterialQuantities.of(Material.HOE, 1)));
        SchematicSupervisor first = new SchematicSupervisor(original, NO_RESERVES, harness.ports());
        first.start();
        while (first.checkpoint().scheduleCursor() < 2 * 49 + 7) { first.tick(); }
        first.pause();
        SupervisorCheckpoint previous = first.checkpoint();
        SchematicPlan target = original.withGlowstoneAfterStructure(true);
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(target, NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(2 * 49, restored.checkpoint().scheduleCursor());
        assertEquals("STRUCTURE", restored.status().layerProgress().stage());
        assertEquals(previous.consumedMaterials(), restored.checkpoint().consumedMaterials());
        assertEquals(restored.checkpoint(), harness.checkpoints.saved);
        SchematicSupervisor second = SchematicSupervisor.loadOrCreate(target, NO_RESERVES, harness.ports());
        assertEquals(restored.checkpoint(), second.checkpoint());
    }

    @Test
    void executesWholeLayersBeforeAdvancingHeightAndDelaysAllVerification() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 4));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        for (int slice = 0; slice < 4; slice++) {
            supervisor.tick();
            assertTrue(harness.verification.scopes.isEmpty());
            supervisor.tick();
        }
        assertEquals(List.of(0, 1, 0, 1),
                harness.execution.started.stream().map(WorkOrder::chunkIndex).toList());
        assertEquals(List.of(0, 0, 3, 3), harness.execution.started.stream()
                .map(order -> ((WorkOrder.OrdinaryBlocks) order).placements().getFirst().position().y())
                .toList());
        assertEquals(BuildPhase.VERIFY, supervisor.status().phase());
        supervisor.tick();
        assertEquals(1, harness.verification.scopes.size());
    }

    @Test
    void pausedCheckpointRestoresTheSameLayerSliceWithoutReplayingCompletedLayers() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 4));
        SchematicSupervisor first = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        first.start();
        for (int count = 0; count < 4; count++) { first.tick(); }
        first.tick();
        first.pause();
        assertEquals(2, first.checkpoint().scheduleCursor());
        SupervisorCheckpoint saved = CheckpointJsonCodec.fromJson(
                CheckpointJsonCodec.toJson(first.checkpoint()));
        Harness restoredHarness = new Harness();
        restoredHarness.inventory.set(MaterialQuantities.of(Material.DIRT, 2));
        restoredHarness.checkpoints.saved = saved;
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                plan, NO_RESERVES, restoredHarness.ports());
        assertEquals(first.status().layerProgress(), restored.status().layerProgress());
        restored.resume();
        restored.tick();
        WorkOrder.OrdinaryBlocks order = (WorkOrder.OrdinaryBlocks)
                restoredHarness.execution.started.getFirst();
        assertEquals(0, order.chunkIndex());
        assertEquals(3, order.placements().getFirst().position().y());
        assertEquals(2, restored.checkpoint().scheduleCursor());
    }

    @Test
    void stoppedUnstartedSparsePlanCheckpointRestoresItsFirstRealSlice() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0),
                List.of(new TargetBlock(new BlockPosition(32, 4, 0),
                        new BlockState("minecraft:dirt"))));
        Harness harness = new Harness();
        SchematicSupervisor original = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        original.stop();
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(
                plan, NO_RESERVES, harness.ports());
        assertEquals(SupervisorState.STOPPED, restored.status().state());
        assertEquals(3, restored.status().currentChunkOrdinal());
        assertEquals(4, restored.status().layerProgress().y());
    }

    @Test
    void dirtyChunkReplaysOnlyItsLayerSlicesThenContinuesFinalVerification() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 6));
        int[] scans = {0};
        harness.verification.result = scope -> {
            if (scope.kind() == VerificationScope.Kind.CHUNK
                    && scope.chunkIndex() == 0 && scans[0]++ == 0) {
                return new VerificationResult(false, List.of(), List.of(), "dirty-first-chunk");
            }
            return VerificationResult.clean("stable");
        };
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        tickUntil(supervisor, SupervisorState.DONE, 200);
        assertEquals(List.of(0, 1, 0, 1, 0, 0),
                harness.execution.started.stream().map(WorkOrder::chunkIndex).toList());
        assertEquals(List.of(0, 0, 1), harness.verification.scopes.subList(0, 3).stream()
                .map(VerificationScope::chunkIndex).toList());
        assertEquals(-1, supervisor.checkpoint().repairChunkIndex());
    }

    @Test
    void advisorRetryRetainsTheCurrentLayerSlice() {
        SchematicPlan plan = layeredPlan();
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 4));
        SchematicSupervisor supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        supervisor.start();
        for (int count = 0; count < 4; count++) { supervisor.tick(); }
        harness.execution.autoComplete = false;
        supervisor.tick();
        // Observe the fake executor's existing marker before measuring a full stall interval.
        supervisor.tick();
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("blocked"));
        harness.advisor.responses.add(AdviceSnapshot.succeeded(RecoveryAdvice.RETRY_CHUNK));
        stall(supervisor, harness.clock);
        for (int count = 0; count < 20 && supervisor.status().state() == SupervisorState.STUCK;
                count++) { supervisor.tick(); }
        assertEquals(SupervisorState.BUILDING, supervisor.status().state());
        assertEquals(2, supervisor.checkpoint().scheduleCursor());
        supervisor.tick();
        assertEquals(harness.execution.started.get(2), harness.execution.started.get(3));
        assertEquals(3, supervisor.status().layerProgress().y());
    }

    private static SchematicPlan layeredPlan() {
        List<TargetBlock> targets = new ArrayList<>();
        for (int y : new int[] {0, 3}) {
            for (int x : new int[] {0, 16}) {
                targets.add(new TargetBlock(new BlockPosition(x, y, 0),
                        new BlockState("minecraft:dirt")));
            }
        }
        return SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets);
    }

    private static void stall(SchematicSupervisor supervisor, MutableClock clock) {
        clock.advance(Duration.ofSeconds(15));
        supervisor.tick();
        assertEquals(SupervisorState.STUCK, supervisor.status().state());
        assertEquals(RecoveryStage.STOP_MOVEMENT, supervisor.status().recoveryStage());
    }

    private static Harness runningHarness() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.autoComplete = false;
        return harness;
    }

    private static SchematicSupervisor afterOneCleanFinalPass(Harness harness) {
        SchematicSupervisor supervisor = new SchematicSupervisor(
                SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of()),
                NO_RESERVES,
                harness.ports()
        );
        supervisor.start();
        for (int tick = 0; tick < 110 && supervisor.status().stableVerificationPasses() == 0; tick++) {
            supervisor.tick();
        }
        assertEquals(SupervisorState.VERIFYING, supervisor.status().state());
        assertEquals(VerificationStage.FINAL, supervisor.status().verificationStage());
        assertEquals(1, supervisor.status().stableVerificationPasses());
        return supervisor;
    }

    private static void assertTwoFreshFinalPassesRequired(SchematicSupervisor supervisor) {
        supervisor.resume();
        supervisor.tick();
        supervisor.tick();
        assertEquals(SupervisorState.VERIFYING, supervisor.status().state());
        assertEquals(1, supervisor.status().stableVerificationPasses());
        supervisor.tick();
        supervisor.tick();
        assertEquals(SupervisorState.DONE, supervisor.status().state());
        assertEquals(2, supervisor.status().stableVerificationPasses());
    }

    @Test
    void settledCapacityRejectionPersistsReleaseWithoutPausingOrChangingLedgers() {
        Harness harness = harnessAwaitingWithdrawal();
        harness.supervisor.tick();
        var before = harness.supervisor.status().materials();
        harness.depots.terminalOverride = new RestockTransferSnapshot(
                RestockTransferStatus.CAPACITY_BLOCKED, MaterialQuantities.empty(), "capacity changed");
        harness.supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, harness.supervisor.status().state());
        assertFalse(harness.checkpoints.saved.withdrawalInFlight());
        assertFalse(harness.supervisor.requiresReconciliation());
        assertEquals(before, harness.supervisor.status().materials());
        assertEquals(1, harness.depots.beginCount);
    }

    @Test
    void settledCapacityRejectionAccountsConfirmedMovementExactlyOnce() {
        Harness harness = harnessAwaitingWithdrawal();
        harness.supervisor.tick();
        harness.depots.terminalOverride = new RestockTransferSnapshot(
                RestockTransferStatus.CAPACITY_BLOCKED, MaterialQuantities.of(Material.DIRT, 1), "capacity changed");
        harness.supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.supervisor.status().materials().withdrawn());
        assertFalse(harness.checkpoints.saved.withdrawalInFlight());
        harness.supervisor.tick();
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.supervisor.status().materials().withdrawn());
        assertEquals(1, harness.depots.beginCount);
    }

    @Test
    void genericFailureWithCapacityTextStillPauses() {
        Harness harness = harnessAwaitingWithdrawal();
        harness.supervisor.tick();
        harness.depots.terminalOverride = RestockTransferSnapshot.failed(
                MaterialQuantities.empty(), "capacity changed; no click sent");
        harness.supervisor.tick();
        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
    }

    @Test
    void unreachableDepotIsSkippedAndTheShortageIsPlannedFromAnotherDepot() {
        Harness harness = harnessAwaitingWithdrawal();
        harness.depots.put(new DepotId("depot-b"), MaterialQuantities.of(Material.DIRT, 1));
        harness.supervisor.tick();
        DepotId unreachable = harness.depots.active.getFirst().depot();
        reportUnreachable(harness, unreachable);

        assertEquals(SupervisorState.RESTOCKING, harness.supervisor.status().state());
        assertFalse(harness.checkpoints.saved.withdrawalInFlight());
        assertTrue(harness.notifications.messages.isEmpty(), "a skipped depot must not raise a pause alert");
        assertTrue(harness.supervisor.status().lastError().startsWith("Skipped an unreachable registered depot: "));

        harness.supervisor.tick();
        assertEquals(2, harness.depots.beginCount);
        assertNotEquals(unreachable, harness.depots.active.getFirst().depot());
        harness.supervisor.tick();
        harness.supervisor.tick();
        assertEquals(SupervisorState.BUILDING, harness.supervisor.status().state());
        assertEquals(MaterialQuantities.of(Material.DIRT, 1), harness.supervisor.status().materials().withdrawn());
    }

    @Test
    void unreachableDepotsPauseOnceTheSkipLimitIsSpent() {
        Harness harness = harnessAwaitingWithdrawal();
        for (int depot = 0; depot < SchematicSupervisor.MAXIMUM_UNREACHABLE_DEPOT_SKIPS; depot++) {
            harness.depots.put(new DepotId("depot-spare-" + depot), MaterialQuantities.of(Material.DIRT, 1));
        }
        for (int skip = 0; skip < SchematicSupervisor.MAXIMUM_UNREACHABLE_DEPOT_SKIPS; skip++) {
            harness.supervisor.tick();
            reportUnreachable(harness, harness.depots.active.getFirst().depot());
            assertEquals(SupervisorState.RESTOCKING, harness.supervisor.status().state());
        }
        harness.supervisor.tick();
        reportUnreachable(harness, harness.depots.active.getFirst().depot());

        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
        assertTrue(harness.supervisor.status().lastError().startsWith("Depot withdrawal failed: "));
        assertEquals(SchematicSupervisor.MAXIMUM_UNREACHABLE_DEPOT_SKIPS + 1, harness.depots.beginCount);
        assertFalse(harness.supervisor.requiresReconciliation());
    }

    @Test
    void pauseSettlesAnUnreachableDepotReportWithoutRequiringReset() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(1, MaterialQuantities.of(Material.DIRT, 64)));
        SettlingDepots settling = new SettlingDepots(harness.inventory,
                RestockTransferSnapshot.unreachable(MaterialQuantities.empty(), "settling is unreachable: no route"));
        SchematicSupervisor supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES,
                portsWithDepots(harness, settling));
        supervisor.start();
        supervisor.tick();
        supervisor.tick();
        supervisor.tick();
        assertEquals(1, settling.beginCount);

        supervisor.pause();

        assertEquals(SupervisorState.PAUSED, supervisor.status().state());
        assertFalse(supervisor.requiresReconciliation());
        supervisor.resume();
        assertEquals(SupervisorState.RESTOCKING, supervisor.status().state());
    }

    private static void reportUnreachable(Harness harness, DepotId depot) {
        // The adapter stops counting a depot's stock once its route is attempted, until it is rescanned.
        harness.depots.stock.remove(depot);
        harness.depots.terminalOverride = RestockTransferSnapshot.unreachable(
                MaterialQuantities.empty(), depot.value() + " is unreachable: no route");
        harness.supervisor.tick();
    }

    private static Harness harnessAwaitingWithdrawal() {
        Harness harness = new Harness();
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(
                0, MaterialQuantities.of(Material.DIRT, 1)
        ));
        harness.depots.put(new DepotId("depot-a"), MaterialQuantities.of(Material.DIRT, 1));
        harness.supervisor = new SchematicSupervisor(dirtPlan(), NO_RESERVES, harness.ports());
        harness.supervisor.start();
        harness.supervisor.tick();
        harness.supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, harness.supervisor.status().state());
        assertFalse(harness.checkpoints.saved.withdrawalInFlight());
        return harness;
    }

    private static Harness harnessAwaitingDirtBatch(MaterialQuantities requirement) {
        Harness harness = new Harness();
        harness.execution.autoComplete = false;
        harness.execution.scripted.add(ExecutionSnapshot.needsMaterials(0, requirement));
        List<TargetBlock> targets = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 8; z++) {
                targets.add(new TargetBlock(new BlockPosition(x, 0, z), new BlockState("minecraft:dirt")));
            }
        }
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets);
        harness.supervisor = new SchematicSupervisor(plan, NO_RESERVES, harness.ports());
        harness.supervisor.start();
        harness.supervisor.tick();
        harness.supervisor.tick();
        assertEquals(SupervisorState.RESTOCKING, harness.supervisor.status().state());
        return harness;
    }

    private static Harness harnessAtAdvisor() {
        Harness harness = runningHarness();
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("no route"));
        harness.supervisor = new SchematicSupervisor(dirtPlan(), NO_RETRIES, harness.ports());
        harness.supervisor.start();
        harness.supervisor.tick();
        stall(harness.supervisor, harness.clock);
        harness.supervisor.tick();
        harness.supervisor.tick();
        harness.supervisor.tick();
        harness.supervisor.tick();
        harness.supervisor.tick();
        assertEquals(RecoveryStage.ASK_ADVISOR, harness.supervisor.status().recoveryStage());
        return harness;
    }

    private static void tickUntil(
            SchematicSupervisor supervisor,
            SupervisorState target,
            int maximumTicks
    ) {
        for (int count = 0; count < maximumTicks && supervisor.status().state() != target; count++) {
            supervisor.tick();
        }
        assertEquals(target, supervisor.status().state(), "target state was not reached");
    }

    private static SchematicPlan dirtPlan() {
        return SchematicCompiler.compile7x7(
                new ChunkCoordinate(0, 0),
                List.of(new TargetBlock(
                        new BlockPosition(0, 0, 0),
                        new BlockState("minecraft:dirt")
                ))
        );
    }

    private static SchematicPlan representativePlan() {
        return SchematicCompiler.compile7x7(
                new ChunkCoordinate(0, 0),
                List.of(
                        new TargetBlock(
                                new BlockPosition(0, 0, 0),
                                new BlockState("minecraft:farmland", Map.of("moisture", "7"))
                        ),
                        new TargetBlock(
                                new BlockPosition(0, 1, 0),
                                new BlockState("minecraft:wheat", Map.of("age", "0"))
                        ),
                        new TargetBlock(
                                new BlockPosition(1, 0, 0),
                                new BlockState("minecraft:glowstone")
                        ),
                        new TargetBlock(
                                new BlockPosition(2, 0, 0),
                                new BlockState("minecraft:birch_planks")
                        )
                )
        );
    }

    private static SupervisorPorts portsWithDepots(
            Harness harness,
            SupervisorPorts.Depots depots
    ) {
        return new SupervisorPorts(
                harness.execution,
                harness.inventory,
                depots,
                harness.verification,
                harness.health,
                harness.advisor,
                harness.checkpoints,
                harness.notifications,
                harness.clock
        );
    }

    private static SupervisorCheckpoint checkpointFor(String planId) {
        return new SupervisorCheckpoint(
                SupervisorCheckpoint.CURRENT_VERSION,
                planId,
                SupervisorState.BUILDING,
                SupervisorState.BUILDING,
                SupervisorState.BUILDING,
                0,
                BuildPhase.ORDINARY_BLOCKS,
                VerificationStage.CHUNK,
                RecoveryStage.NONE,
                0,
                "",
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                MaterialQuantities.empty(),
                "",
                0,
                false,
                false,
                false,
                false,
                false,
                "",
                LayerBuildSchedule.ID,
                0,
                -1
        );
    }
}
