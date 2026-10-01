package io.github.schematicsupervisor.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.SupervisorFakes.Harness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfHealingRecoveryTest {
    private static final SupervisorConfig CONFIG = new SupervisorConfig(
            Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(15), 0, 0);
    private static final String FLIGHT_LOST = "Active flight was lost; construction stopped before further interaction";
    private static final String SERVER_TIMEOUT =
            "The server did not confirm the cleared replacement target within the bounded attempt";

    @Test
    void lostFlightIsRestoredBeforeAnyOtherRecoveryAndTheSliceStartsAgain() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        fail(harness, FLIGHT_LOST);

        tick(harness);
        assertEquals(RecoveryStage.RESTORE_FLIGHT, harness.supervisor.status().recoveryStage());
        tick(harness);
        assertEquals(RecoveryStage.WAIT_FOR_FLIGHT, harness.supervisor.status().recoveryStage());
        assertEquals(1, harness.execution.flightRestoreCount);
        tick(harness);

        assertEquals(SupervisorState.BUILDING, harness.supervisor.status().state());
        assertTrue(harness.supervisor.status().lastError().endsWith("Flight was restored automatically"),
                harness.supervisor.status().lastError());
        assertEquals(0, harness.execution.restartCallCount);
        assertEquals(0, harness.execution.safeReturnCount);
        assertTrue(harness.advisor.incidents.isEmpty());
        tick(harness);
        assertEquals(2, harness.execution.started.size(), "the same slice starts again from the hover position");
        assertEquals(harness.execution.started.get(0), harness.execution.started.get(1));
    }

    @Test
    void flightIsRestoredBeforeWaitingOutLag() {
        Harness harness = building();
        harness.health.laggy = true;
        harness.execution.flightRestorable = true;
        fail(harness, FLIGHT_LOST);

        tick(harness);
        assertEquals(RecoveryStage.RESTORE_FLIGHT, harness.supervisor.status().recoveryStage());
    }

    @Test
    void aSecondLandingBeforeAnyConfirmedWorkIsNotRestoredAgain() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        fail(harness, FLIGHT_LOST);
        tickUntilState(harness, SupervisorState.BUILDING);
        tick(harness);

        harness.execution.flightRestorable = true;
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("Active flight was lost before safe return"));
        harness.advisor.responses.add(AdviceSnapshot.unavailable("Companion request unavailable"));
        fail(harness, FLIGHT_LOST);
        tickUntilState(harness, SupervisorState.PAUSED);

        assertEquals(1, harness.execution.flightRestoreCount);
        String error = harness.supervisor.status().lastError();
        assertTrue(error.startsWith(FLIGHT_LOST), error);
        assertFalse(error.contains("retrying automatically"), "flight loss is never retried on a timer");
    }

    @Test
    void confirmedWorkRenewsTheFlightRestore() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        fail(harness, FLIGHT_LOST);
        tickUntilState(harness, SupervisorState.BUILDING);
        tick(harness);
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.RUNNING, 1,
                MaterialQuantities.empty(), MaterialQuantities.of(Material.DIRT, 1), ""));
        tick(harness);

        harness.execution.flightRestorable = true;
        fail(harness, FLIGHT_LOST);
        tickUntilState(harness, SupervisorState.BUILDING);

        assertEquals(2, harness.execution.flightRestoreCount);
    }

    @Test
    void aFailedTakeoffContinuesTheDeterministicRecovery() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        harness.execution.flightRestoreResults.add(FlightRestoreSnapshot.failed(
                "Takeoff requires flight permission already granted by the server."));
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("Active flight was lost before safe return"));
        harness.advisor.responses.add(AdviceSnapshot.unavailable("Companion request unavailable"));
        fail(harness, FLIGHT_LOST);
        tick(harness);
        tick(harness);
        tick(harness);
        assertEquals(RecoveryStage.CHECK_MATERIALS, harness.supervisor.status().recoveryStage());

        tickUntilState(harness, SupervisorState.PAUSED);
        String error = harness.supervisor.status().lastError();
        assertTrue(error.contains("Flight could not be restored: Takeoff requires flight permission"), error);
        assertTrue(error.contains("Advisor unavailable after deterministic recovery"), error);
    }

    @Test
    void aTakeoffThatNeverFinishesIsCancelledAtItsDeadline() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        for (int poll = 0; poll < 3; poll++) {
            harness.execution.flightRestoreResults.add(FlightRestoreSnapshot.running());
        }
        fail(harness, FLIGHT_LOST);
        tick(harness);
        tick(harness);
        tick(harness);
        assertEquals(RecoveryStage.WAIT_FOR_FLIGHT, harness.supervisor.status().recoveryStage());

        harness.clock.advance(SupervisorConfig.FLIGHT_RESTORE_TIMEOUT);
        tick(harness);
        assertEquals(1, harness.execution.cancelFlightRestoreCount);
        assertEquals(RecoveryStage.CHECK_MATERIALS, harness.supervisor.status().recoveryStage());
        assertTrue(harness.supervisor.status().lastError().endsWith("Flight restore timed out"));
    }

    @Test
    void pauseCancelsATakeoffInProgress() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        harness.execution.flightRestoreResults.add(FlightRestoreSnapshot.running());
        fail(harness, FLIGHT_LOST);
        tick(harness);
        tick(harness);
        assertEquals(RecoveryStage.WAIT_FOR_FLIGHT, harness.supervisor.status().recoveryStage());

        harness.supervisor.pause();
        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
        assertEquals(1, harness.execution.cancelFlightRestoreCount);
    }

    @Test
    void newStagesAreSavedAsStagesAnOlderModCanRead() {
        Harness harness = building();
        harness.execution.flightRestorable = true;
        harness.execution.flightRestoreResults.add(FlightRestoreSnapshot.running());
        fail(harness, FLIGHT_LOST);
        tick(harness);
        tick(harness);

        assertEquals(RecoveryStage.WAIT_FOR_FLIGHT, harness.supervisor.status().recoveryStage());
        SupervisorCheckpoint saved = harness.supervisor.checkpoint();
        assertEquals(RecoveryStage.STOP_MOVEMENT, saved.recoveryStage());
        assertTrue(CheckpointJsonCodec.toJson(saved).contains("\"recovery_stage\": \"STOP_MOVEMENT\""));
        assertEquals(RecoveryStage.ADVISOR_WAIT, RecoveryStage.RETRY_WAIT.persisted());
        assertEquals(RecoveryStage.STOP_MOVEMENT, RecoveryStage.RESTORE_FLIGHT.persisted());
        for (RecoveryStage stage : List.of(RecoveryStage.NONE, RecoveryStage.REPATH, RecoveryStage.ADVISOR_WAIT)) {
            assertEquals(stage, stage.persisted());
        }

        harness.checkpoints.saved = CheckpointJsonCodec.fromJson(CheckpointJsonCodec.toJson(saved));
        SchematicSupervisor restored = SchematicSupervisor.loadOrCreate(plan(), CONFIG, harness.ports());
        assertEquals(SupervisorState.PAUSED, restored.status().state());
        assertEquals(RecoveryStage.NONE, restored.status().recoveryStage());
    }

    @Test
    void aTransientFailureWaitsAndRetriesThreeTimesThenPauses() {
        Harness harness = building();
        List<Long> waits = List.of(30L, 90L, 270L);
        for (int retry = 1; retry <= waits.size(); retry++) {
            failThroughUnavailableAdvisor(harness, SERVER_TIMEOUT);
            tickUntilStage(harness, RecoveryStage.RETRY_WAIT);
            String error = harness.supervisor.status().lastError();
            assertTrue(error.startsWith(SERVER_TIMEOUT), error);
            assertTrue(error.endsWith("retrying automatically in " + waits.get(retry - 1) + " s ("
                    + retry + " of 3)"), error);
            assertEquals(SupervisorState.STUCK, harness.supervisor.status().state());

            harness.clock.advance(Duration.ofSeconds(waits.get(retry - 1) - 1));
            tick(harness);
            assertEquals(RecoveryStage.RETRY_WAIT, harness.supervisor.status().recoveryStage());
            harness.clock.advance(Duration.ofSeconds(1));
            tick(harness);
            assertEquals(SupervisorState.BUILDING, harness.supervisor.status().state());
            assertFalse(harness.supervisor.checkpoint().repathAttempted(), "each retry gets fresh attempts");
        }

        failThroughUnavailableAdvisor(harness, SERVER_TIMEOUT);
        tickUntilState(harness, SupervisorState.PAUSED);
        String error = harness.supervisor.status().lastError();
        assertTrue(error.startsWith(SERVER_TIMEOUT), error);
        assertFalse(error.contains("retrying automatically"), error);
        assertEquals(4, harness.advisor.incidents.size());
        assertEquals(1, harness.notifications.messages.size(), "retries do not alert; the final pause does");
    }

    @Test
    void confirmedWorkRenewsTheTransientRetries() {
        Harness harness = building();
        failThroughUnavailableAdvisor(harness, SERVER_TIMEOUT);
        tickUntilStage(harness, RecoveryStage.RETRY_WAIT);
        harness.clock.advance(Duration.ofSeconds(30));
        tick(harness);
        harness.execution.scripted.add(new ExecutionSnapshot(ExecutionStatus.RUNNING, 1,
                MaterialQuantities.empty(), MaterialQuantities.of(Material.DIRT, 1), ""));
        tick(harness);

        failThroughUnavailableAdvisor(harness, SERVER_TIMEOUT);
        tickUntilStage(harness, RecoveryStage.RETRY_WAIT);
        assertTrue(harness.supervisor.status().lastError().endsWith("in 30 s (1 of 3)"),
                harness.supervisor.status().lastError());
    }

    @Test
    void aFailureThatNeedsThePlayerStillPausesAtOnce() {
        Harness harness = building();
        failThroughUnavailableAdvisor(harness, "Manual movement input interrupted flight construction");
        tickUntilState(harness, SupervisorState.PAUSED);
        assertFalse(harness.supervisor.status().lastError().contains("retrying automatically"));
    }

    @Test
    void anExplicitPauseEndsTheRetryWait() {
        Harness harness = building();
        failThroughUnavailableAdvisor(harness, SERVER_TIMEOUT);
        tickUntilStage(harness, RecoveryStage.RETRY_WAIT);

        harness.supervisor.pause();
        harness.clock.advance(Duration.ofMinutes(10));
        tick(harness);
        assertEquals(SupervisorState.PAUSED, harness.supervisor.status().state());
        assertEquals(RecoveryStage.NONE, harness.supervisor.status().recoveryStage());
        harness.supervisor.resume();
        assertEquals(SupervisorState.BUILDING, harness.supervisor.status().state());
    }

    @Test
    void advisorAdviceToPauseIsNeverOverriddenByARetry() {
        Harness harness = building();
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("no route"));
        harness.advisor.responses.add(AdviceSnapshot.succeeded(RecoveryAdvice.PAUSE_AND_ALERT));
        fail(harness, SERVER_TIMEOUT);
        tickUntilState(harness, SupervisorState.PAUSED);
        assertFalse(harness.supervisor.status().lastError().contains("retrying automatically"));
    }

    @Test
    void classifierRetriesOnlyKnownTransientCauses() {
        for (String cause : List.of(
                SERVER_TIMEOUT,
                "The server did not confirm stem removal within the bounded attempt",
                "Approaching planned block replacement: A flight segment became obstructed or unloaded; route stopped",
                "Flight approach made no confirmed block-state progress for 3600 active client ticks across route planning",
                "No build progress for 15 seconds",
                "An entity occupies or stands on the clearing target",
                "Flight route search reached its bounded node limit. expanded=60000",
                "The layer's bounded chunk-receipt approach expired",
                "Interaction was not confirmed by a world-state transition")) {
            assertTrue(RecoveryClassifier.transientFailure(cause), cause);
        }
        for (String cause : List.of(
                FLIGHT_LOST,
                "Manual movement input interrupted flight construction",
                "The server moved the player outside the expected flight segment",
                "Flight placement will not overwrite an occupied target at 1, 2, 3: actual=Block{minecraft:hopper}",
                "Registered depots cannot satisfy the exact material shortage: {wheat_seeds=64}",
                "Server rejected the deterministic block interaction",
                "Manual movement input interrupted the flight; the server did not confirm the route",
                "",
                "Something nobody has classified")) {
            assertFalse(RecoveryClassifier.transientFailure(cause), cause);
        }
        assertFalse(RecoveryClassifier.transientFailure(null));
    }

    private static Harness building() {
        Harness harness = new Harness();
        harness.inventory.set(MaterialQuantities.of(Material.DIRT, 1));
        harness.execution.autoComplete = false;
        harness.supervisor = new SchematicSupervisor(plan(), CONFIG, harness.ports());
        harness.supervisor.start();
        tick(harness);
        assertEquals(1, harness.execution.started.size());
        return harness;
    }

    private static void fail(Harness harness, String cause) {
        harness.execution.scripted.add(ExecutionSnapshot.failed(0, cause));
        tick(harness);
        assertEquals(SupervisorState.STUCK, harness.supervisor.status().state());
        assertEquals(RecoveryStage.STOP_MOVEMENT, harness.supervisor.status().recoveryStage());
    }

    private static void failThroughUnavailableAdvisor(Harness harness, String cause) {
        harness.execution.restartResults.add(false);
        harness.execution.safeReturnResults.add(SafeReturnSnapshot.failed("no route"));
        harness.advisor.responses.add(AdviceSnapshot.unavailable("Companion request unavailable"));
        fail(harness, cause);
    }

    private static void tick(Harness harness) {
        harness.supervisor.tick();
    }

    private static void tickUntilState(Harness harness, SupervisorState target) {
        for (int count = 0; count < 30 && harness.supervisor.status().state() != target; count++) {
            tick(harness);
        }
        assertEquals(target, harness.supervisor.status().state(), harness.supervisor.status().lastError());
    }

    private static void tickUntilStage(Harness harness, RecoveryStage target) {
        for (int count = 0; count < 30 && harness.supervisor.status().recoveryStage() != target; count++) {
            tick(harness);
        }
        assertEquals(target, harness.supervisor.status().recoveryStage(), harness.supervisor.status().lastError());
    }

    private static SchematicPlan plan() {
        return SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0),
                List.of(new TargetBlock(new BlockPosition(0, 0, 0), new BlockState("minecraft:dirt"))));
    }
}
