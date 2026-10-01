package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.Material;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.WorkOrder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TemporarySupportControllerTest {
    private static final RunContext CONTEXT = new RunContext("sha256:" + "cd".repeat(32), "minecraft:overworld");
    private static final BlockPosition ANCHOR = new BlockPosition(0, 0, 0);
    private static final BlockPosition BOTTOM = new BlockPosition(0, 1, 0);
    private static final BlockPosition TOP = new BlockPosition(0, 2, 0);
    private static final BlockPosition SEED = new BlockPosition(0, 3, 0);
    private static final BlockState DIRT = TemporarySupportJournal.DIRT;
    @TempDir Path directory;

    @Test
    void persistsFiveOperationsInOrderAndCreditsOnlyThePlannedSeedOnce() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        List<TemporarySupportController.Kind> order = List.of(
                TemporarySupportController.Kind.PLACE_BOTTOM, TemporarySupportController.Kind.PLACE_TOP,
                TemporarySupportController.Kind.PLACE_SEED, TemporarySupportController.Kind.REMOVE_TOP,
                TemporarySupportController.Kind.REMOVE_BOTTOM);
        long consumed = 0;
        for (int index = 0; index < order.size(); index++) {
            TemporarySupportController.Operation operation = action(controller, fixture);
            assertEquals(order.get(index), operation.kind());
            assertEquals(2L * index + 1, operation.intentRevision());
            assertEquals(controller.journal(), fixture.store.load().orElseThrow());
            assertTrue(controller.journal().requiresReconciliation());
            assertNull(controller.preview(fixture.snapshot()).candidate());
            assertFalse(controller.journal().complete());
            fixture.apply(operation);
            TemporarySupportController.Settlement receipt = controller.acknowledge(operation, fixture.snapshot());
            assertEquals(TemporarySupportController.Outcome.CONFIRMED, receipt.outcome());
            if (operation.kind() == TemporarySupportController.Kind.PLACE_SEED) {
                var credit = controller.pendingPlannedCredit().orElseThrow();
                consumed += credit.quantity();
                assertEquals(fixture.plan.planId(), credit.planId());
                assertEquals(credit, fixture.store.pendingPlannedCredit().orElseThrow());
            }
            assertThrows(IllegalArgumentException.class, () -> controller.acknowledge(operation, fixture.snapshot()));
        }
        assertEquals(1, consumed);
        assertFalse(controller.complete());
        controller.acknowledgePlannedCredit(controller.pendingPlannedCredit().orElseThrow().id());
        assertEquals(61, fixture.dirt);
        assertEquals(DIRT, fixture.world.states.get(SEED));
        assertTrue(controller.journal().complete());
        assertEquals(TemporarySupportController.Status.COMPLETE, controller.preview(fixture.snapshot()).status());
        assertEquals(TemporarySupportController.Status.COMPLETE, fixture.restore().preview(fixture.snapshot()).status());
    }

    @Test
    void upperSupportDropAllowsOwnedBottomCleanupWithoutClaimingPickup() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        // Place the two supports and starter, then confirm removing the upper support.
        for (int step = 0; step < 4; step++) { confirmNext(controller, fixture); }
        assertEquals(BlockState.AIR, fixture.world.states.get(TOP));
        assertEquals(TemporarySupportController.Kind.REMOVE_BOTTOM,
                controller.preview(fixture.snapshot()).candidate().kind());

        Box lowerGuard = new Box(0, 1, 0, 1, 2, 1).stretch(0, 2, 0);
        Box retainedDrop = new Box(0.375, 2, 0.375, 0.625, 2.25, 0.625);
        assertTrue(lowerGuard.intersects(retainedDrop));
        var looseItem = new MossClearingEntityPolicy.Facts(true, true, false, false, false, false);
        assertFalse(MossClearingEntityPolicy.hasConflict(false, List.of(looseItem), Function.identity()));
        assertTrue(MossClearingEntityPolicy.hasConflict(true, List.of(looseItem), Function.identity()));
        var collidingEntity = new MossClearingEntityPolicy.Facts(false, false, true, true, false, false);
        assertTrue(MossClearingEntityPolicy.hasConflict(false,
                List.of(looseItem, collidingEntity), Function.identity()));

        // Ignoring the drop does not relax the journal's exact owned-block check.
        fixture.world.states.put(BOTTOM, new BlockState("minecraft:stone"));
        assertEquals(TemporarySupportController.Status.BLOCKED, controller.preview(fixture.snapshot()).status());
        fixture.world.states.put(BOTTOM, DIRT);
        confirmNext(controller, fixture);
        controller.acknowledgePlannedCredit(controller.pendingPlannedCredit().orElseThrow().id());
        assertTrue(controller.journal().complete());
        assertEquals(BlockState.AIR, fixture.world.states.get(BOTTOM));
        assertEquals(DIRT, fixture.world.states.get(SEED));
        assertEquals(61, fixture.dirt, "Cleanup must not invent pickup of either dropped support");
    }

    @Test
    void failedIntentPersistenceProducesNoOperationOrInMemoryAdvance() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        TemporarySupportJournal before = controller.journal();
        Files.move(fixture.path, directory.resolve("preserved-journal.json"));
        Files.createDirectory(fixture.path);
        var candidate = controller.preview(fixture.snapshot()).candidate();
        assertThrows(IOException.class, () -> controller.issue(candidate, fixture.snapshot()));
        assertEquals(before, controller.journal());
        assertTrue(controller.journal().initial());
        Files.delete(fixture.path);
        Files.move(directory.resolve("preserved-journal.json"), fixture.path);
        assertEquals(TemporarySupportController.Kind.PLACE_BOTTOM, action(controller, fixture).kind());
    }

    @Test
    void failedConfirmationPersistenceRetainsTheOriginalLiveReceipt() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        TemporarySupportController.Operation operation = action(controller, fixture);
        fixture.apply(operation);
        Files.move(fixture.path, directory.resolve("preserved-intent.json"));
        assertThrows(IOException.class, () -> controller.acknowledge(operation, fixture.snapshot()));
        assertEquals(TemporarySupportJournal.CellStage.PLACE_INTENT, controller.journal().cells().get(0).stage());
        assertNull(controller.preview(fixture.snapshot()).candidate());
        Files.move(directory.resolve("preserved-intent.json"), fixture.path);
        assertEquals(TemporarySupportController.Outcome.CONFIRMED,
                controller.acknowledge(operation, fixture.snapshot()).outcome());
    }

    @Test
    void predictionOrAmbiguousConsumptionCannotEstablishOwnershipOrPermitRetry() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        TemporarySupportController.Operation operation = action(controller, fixture);
        fixture.apply(operation);
        fixture.prediction = BOTTOM;
        assertEquals(TemporarySupportController.Outcome.WAITING,
                controller.acknowledge(operation, fixture.snapshot()).outcome());
        assertEquals(TemporarySupportJournal.CellStage.PLACE_INTENT, controller.journal().cells().get(0).stage());
        fixture.prediction = null;
        fixture.dirt--;
        assertEquals(TemporarySupportController.Outcome.UNCERTAIN,
                controller.acknowledge(operation, fixture.snapshot()).outcome());
        assertEquals(TemporarySupportController.Status.UNCERTAIN, controller.preview(fixture.snapshot()).status());
        fixture.world.states.put(BOTTOM, BlockState.AIR);
        fixture.dirt = 64;
        assertEquals(TemporarySupportController.Outcome.UNCERTAIN,
                controller.reject(operation, fixture.snapshot()).outcome());
        fixture.world.states.put(BOTTOM, DIRT);
        fixture.dirt = 63;
        assertEquals(TemporarySupportController.Outcome.UNCERTAIN,
                controller.acknowledge(operation, fixture.snapshot()).outcome());
        assertNull(controller.preview(fixture.snapshot()).candidate());
        assertEquals(TemporarySupportJournal.CellStage.PLACE_INTENT, fixture.store.load().orElseThrow().cells().get(0).stage());
    }

    @Test
    void explicitUncertaintySurvivesRestartAsUnsettledIntent() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        TemporarySupportController.Operation operation = action(controller, fixture);
        controller.uncertain(operation, "Receipt deadline ended without an acknowledgement");
        controller.pause();
        controller.resume();
        assertEquals(TemporarySupportController.Status.UNCERTAIN, controller.preview(fixture.snapshot()).status());
        assertEquals(TemporarySupportController.Status.UNCERTAIN, fixture.restore().preview(fixture.snapshot()).status());
    }

    @Test
    void restartedPlacementIntentNeverOwnsObservedDirtOrAirAndCannotAcceptOldReceipt() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController original = fixture.begin();
        TemporarySupportController.Operation old = action(original, fixture);
        TemporarySupportController restored = fixture.restore();
        for (BlockState state : List.of(BlockState.AIR, DIRT)) {
            fixture.world.states.put(BOTTOM, state);
            assertEquals(TemporarySupportController.Status.UNCERTAIN, restored.preview(fixture.snapshot()).status());
            assertNull(restored.preview(fixture.snapshot()).candidate());
            assertThrows(IllegalArgumentException.class, () -> restored.acknowledge(old, fixture.snapshot()));
        }
        assertEquals(TemporarySupportJournal.CellStage.PLACE_INTENT, fixture.store.load().orElseThrow().cells().get(0).stage());
        assertThrows(IOException.class, fixture::begin);
    }

    @Test
    void seedIntentCannotBeReplayedAfterRestartEvenWhenSeedAndSupportsArePresent() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        confirmNext(controller, fixture);
        confirmNext(controller, fixture);
        TemporarySupportController.Operation seed = action(controller, fixture);
        fixture.apply(seed);
        TemporarySupportController restored = fixture.restore();
        assertEquals(TemporarySupportController.Status.UNCERTAIN, restored.preview(fixture.snapshot()).status());
        assertFalse(restored.journal().cleanupRequested());
        assertEquals(TemporarySupportJournal.SeedStage.PLACE_INTENT, restored.journal().seedStage());
    }

    @Test
    void pauseAllowsCurrentAcknowledgementButCleanupWaitsForResume() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        TemporarySupportController.Operation bottom = action(controller, fixture);
        controller.pause();
        controller.requestCleanup();
        assertEquals(TemporarySupportController.Status.PAUSED, controller.preview(fixture.snapshot()).status());
        fixture.apply(bottom);
        assertEquals(TemporarySupportController.Outcome.CONFIRMED,
                controller.acknowledge(bottom, fixture.snapshot()).outcome());
        assertNull(controller.preview(fixture.snapshot()).candidate());
        controller.resume();
        TemporarySupportController.Operation cleanup = action(controller, fixture);
        assertEquals(TemporarySupportController.Kind.REMOVE_BOTTOM, cleanup.kind());
        fixture.apply(cleanup);
        assertEquals(TemporarySupportController.Outcome.CONFIRMED,
                controller.acknowledge(cleanup, fixture.snapshot()).outcome());
        assertTrue(controller.pendingPlannedCredit().isEmpty());
        assertTrue(controller.journal().complete());
        assertEquals(TemporarySupportJournal.SeedStage.PLANNED, controller.journal().seedStage());
    }

    @Test
    void confirmedSupportsResumeOnlyCleanupAndAlreadyAbsentOwnedCellsNeedNoInteraction() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController original = fixture.begin();
        confirmNext(original, fixture);
        confirmNext(original, fixture);
        fixture.world.states.put(TOP, BlockState.AIR);
        TemporarySupportController restored = fixture.restore();
        TemporarySupportController.Operation bottom = action(restored, fixture);
        assertEquals(TemporarySupportController.Kind.REMOVE_BOTTOM, bottom.kind());
        assertEquals(TemporarySupportJournal.CellStage.REMOVED, restored.journal().cells().get(1).stage());
        assertEquals(62, fixture.dirt);
        fixture.apply(bottom);
        assertEquals(TemporarySupportController.Outcome.CONFIRMED,
                restored.acknowledge(bottom, fixture.snapshot()).outcome());
        assertTrue(restored.pendingPlannedCredit().isEmpty());
        assertTrue(restored.journal().complete());
        assertEquals(TemporarySupportJournal.SeedStage.PLANNED, restored.journal().seedStage());
    }

    @Test
    void restartedRemovalMayRetryOnlyConfirmedDirtOrSettleReceivedAir() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController original = fixture.begin();
        confirmNext(original, fixture);
        original.requestCleanup();
        TemporarySupportController.Operation firstRemoval = action(original, fixture);
        TemporarySupportController restored = fixture.restore();
        TemporarySupportController.Operation retry = action(restored, fixture);
        assertEquals(TemporarySupportController.Kind.REMOVE_BOTTOM, retry.kind());
        assertTrue(retry.intentRevision() > firstRemoval.intentRevision());
        assertThrows(IllegalArgumentException.class, () -> restored.acknowledge(firstRemoval, fixture.snapshot()));
        fixture.apply(retry);
        TemporarySupportController afterRemoval = fixture.restore();
        assertEquals(TemporarySupportController.Status.MAINTENANCE, afterRemoval.preview(fixture.snapshot()).status());
        afterRemoval.settleObservedState(fixture.snapshot());
        assertEquals(TemporarySupportController.Status.COMPLETE, afterRemoval.preview(fixture.snapshot()).status());
        assertEquals(63, fixture.dirt);
    }

    @Test
    void cleanupRefusesChangedUnreceivedPredictedOrUnownedCells() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController original = fixture.begin();
        confirmNext(original, fixture);
        TemporarySupportController restored = fixture.restore();
        fixture.world.received = false;
        assertEquals(TemporarySupportController.Status.MAINTENANCE, restored.preview(fixture.snapshot()).status());
        restored.settleObservedState(fixture.snapshot());
        assertEquals(TemporarySupportController.Status.WAITING, restored.preview(fixture.snapshot()).status());
        fixture.world.received = true;
        fixture.prediction = BOTTOM;
        assertEquals(TemporarySupportController.Status.WAITING, restored.preview(fixture.snapshot()).status());
        fixture.prediction = null;
        fixture.world.states.put(BOTTOM, new BlockState("minecraft:stone"));
        assertEquals(TemporarySupportController.Status.BLOCKED, restored.preview(fixture.snapshot()).status());
        fixture.world.states.put(BOTTOM, DIRT);
        fixture.world.states.put(TOP, DIRT);
        assertEquals(TemporarySupportController.Status.BLOCKED, restored.preview(fixture.snapshot()).status());
        assertEquals(TemporarySupportJournal.CellStage.PLANNED, restored.journal().cells().get(1).stage());
    }

    @Test
    void rejectsWrongContextAndChangedFullSliceBeforeAnyOperation() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        RunContext other = new RunContext(CONTEXT.worldIdentityHash(), "minecraft:the_nether");
        TemporarySupportController.Snapshot snapshot = fixture.snapshot();
        TemporarySupportController.Snapshot wrong = new TemporarySupportController.Snapshot(other,
                snapshot.cells(), snapshot.inventory(), false);
        assertThrows(IllegalArgumentException.class, () -> controller.preview(wrong));
        assertTrue(controller.journal().initial());
        assertThrows(IOException.class, () -> TemporarySupportController.restore(fixture.plan, fixture.order, other, fixture.store));
        WorkOrder.OrdinaryBlocks otherSlice = new WorkOrder.OrdinaryBlocks(0, fixture.order.chunk(),
                fixture.plan.chunk(0).ordinaryPlacements());
        assertThrows(IOException.class, () -> TemporarySupportController.restore(fixture.plan, otherSlice, CONTEXT, fixture.store));
        TemporarySupportController.Operation operation = action(controller, fixture);
        assertThrows(IllegalArgumentException.class, () -> controller.acknowledge(operation, wrong));
        assertEquals(TemporarySupportJournal.CellStage.PLACE_INTENT, controller.journal().cells().get(0).stage());
    }

    @Test
    void reservesTheWholeColumnAndWaitsForMissingOrPredictedWorldFacts() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        fixture.dirt = 2;
        assertEquals(TemporarySupportController.Status.WAITING, controller.preview(fixture.snapshot()).status());
        assertTrue(controller.journal().initial());
        fixture.dirt = 3;
        fixture.prediction = ANCHOR;
        assertEquals(TemporarySupportController.Status.WAITING, controller.preview(fixture.snapshot()).status());
        fixture.prediction = null;
        fixture.world.received = false;
        assertEquals(TemporarySupportController.Status.WAITING, controller.preview(fixture.snapshot()).status());
        fixture.world.received = true;
        TemporarySupportController.Snapshot unavailable = new TemporarySupportController.Snapshot(CONTEXT,
                Map.of(), Map.of(Material.DIRT, 3L), false);
        assertEquals(TemporarySupportController.Status.WAITING, controller.preview(unavailable).status());
        assertEquals(TemporarySupportController.Kind.PLACE_BOTTOM, action(controller, fixture).kind());
    }

    @Test
    void conclusivelyRejectedReceiptCanRetryWithANewPersistedIntent() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        TemporarySupportController.Operation first = action(controller, fixture);
        assertEquals(TemporarySupportController.Outcome.REJECTED, controller.reject(first, fixture.snapshot()).outcome());
        TemporarySupportController.Operation second = action(controller, fixture);
        assertTrue(second.intentRevision() > first.intentRevision());
        assertThrows(IllegalArgumentException.class, () -> controller.acknowledge(first, fixture.snapshot()));
        fixture.apply(second);
        assertEquals(TemporarySupportController.Outcome.CONFIRMED, controller.acknowledge(second, fixture.snapshot()).outcome());
    }

    @Test
    void previewNeverWritesAndInventoryBaselineIsCapturedOnlyAtIssue() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        String saved = Files.readString(fixture.path);
        TemporarySupportController.Candidate candidate = controller.preview(fixture.snapshot()).candidate();
        fixture.dirt = 8;
        assertEquals(candidate, controller.preview(fixture.snapshot()).candidate());
        assertEquals(saved, Files.readString(fixture.path));
        assertTrue(controller.journal().initial());
        TemporarySupportController.Operation operation = controller.issue(candidate, fixture.snapshot());
        assertEquals(8, operation.inventoryBefore());
        assertEquals(TemporarySupportJournal.CellStage.PLACE_INTENT, fixture.store.load().orElseThrow().cells().get(0).stage());
        assertThrows(IllegalStateException.class, () -> controller.issue(candidate, fixture.snapshot()));
    }

    @Test
    void changedCandidatePrerequisitesCannotPersistAnIntent() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        var candidate = controller.preview(fixture.snapshot()).candidate();
        String saved = Files.readString(fixture.path);
        fixture.world.states.put(BOTTOM, DIRT);
        assertThrows(IllegalStateException.class, () -> controller.issue(candidate, fixture.snapshot()));
        assertEquals(saved, Files.readString(fixture.path));
        fixture.world.states.remove(BOTTOM);
        fixture.dirt = 2;
        assertThrows(IllegalStateException.class, () -> controller.issue(candidate, fixture.snapshot()));
        assertEquals(saved, Files.readString(fixture.path));
    }

    @Test
    void previewOfRestoredCleanupIsReadOnlyUntilExplicitObservedSettlement() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController original = fixture.begin();
        confirmNext(original, fixture);
        fixture.world.states.put(BOTTOM, BlockState.AIR);
        TemporarySupportController restored = fixture.restore();
        String saved = Files.readString(fixture.path);
        assertEquals(TemporarySupportController.Status.MAINTENANCE, restored.preview(fixture.snapshot()).status());
        assertEquals(saved, Files.readString(fixture.path));
        assertEquals(List.of(BOTTOM), restored.outstandingSupports());
        restored.settleObservedState(fixture.snapshot());
        assertTrue(restored.complete());
        assertTrue(restored.pendingPlannedCredit().isEmpty());
        assertEquals(63, fixture.dirt);
    }

    @Test
    void starterCreditSurvivesRestartAndRequiresAcknowledgementBeforeColumnReplacement() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        confirmNext(controller, fixture);
        confirmNext(controller, fixture);
        confirmNext(controller, fixture);
        var credit = controller.pendingPlannedCredit().orElseThrow();
        TemporarySupportController restored = fixture.restore();
        assertEquals(credit, restored.pendingPlannedCredit().orElseThrow());
        confirmNext(restored, fixture);
        confirmNext(restored, fixture);
        assertTrue(restored.journal().cleanupComplete());
        assertFalse(restored.complete());
        assertEquals(TemporarySupportController.Status.WAITING, restored.preview(fixture.snapshot()).status());
        assertThrows(IOException.class, () -> fixture.store.create(restored.journal().column(), CONTEXT));
        restored.acknowledgePlannedCredit(credit.id());
        assertTrue(restored.complete());
        String acknowledged = Files.readString(fixture.path);
        restored.acknowledgePlannedCredit(credit.id());
        assertEquals(acknowledged, Files.readString(fixture.path));
        assertTrue(fixture.restore().pendingPlannedCredit().isEmpty());
        var next = fixture.store.create(restored.journal().column(), CONTEXT);
        assertFalse(next.columnId().equals(credit.id()));
    }

    @Test
    void failedStarterConfirmationLeavesNoCreditAndFailedAcknowledgementKeepsTheOutbox() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        confirmNext(controller, fixture);
        confirmNext(controller, fixture);
        var starter = action(controller, fixture);
        fixture.apply(starter);
        Path preserved = directory.resolve("preserved-credit-journal.json");
        Files.move(fixture.path, preserved);
        assertThrows(IOException.class, () -> controller.acknowledge(starter, fixture.snapshot()));
        assertTrue(controller.pendingPlannedCredit().isEmpty());
        assertEquals(TemporarySupportJournal.SeedStage.PLACE_INTENT, controller.journal().seedStage());
        Files.move(preserved, fixture.path);
        controller.acknowledge(starter, fixture.snapshot());
        var credit = controller.pendingPlannedCredit().orElseThrow();
        Files.move(fixture.path, preserved);
        assertThrows(IOException.class, () -> controller.acknowledgePlannedCredit(credit.id()));
        assertEquals(credit, controller.pendingPlannedCredit().orElseThrow());
        Files.move(preserved, fixture.path);
        assertThrows(IllegalStateException.class, () -> controller.acknowledgePlannedCredit("another-credit"));
        controller.acknowledgePlannedCredit(credit.id());
        assertFalse(controller.complete());
        assertThrows(IOException.class, () -> fixture.store.create(controller.journal().column(), CONTEXT));
        confirmNext(controller, fixture);
        confirmNext(controller, fixture);
        assertTrue(controller.complete());
    }

    @Test
    void creativeStarterNeedsNoConsumptionCreditButStillRequiresPhysicalCleanup() throws IOException {
        Fixture fixture = fixture();
        fixture.creative = true;
        TemporarySupportController controller = fixture.begin();
        for (int step = 0; step < 3; step++) { confirmNext(controller, fixture); }
        assertTrue(controller.pendingPlannedCredit().isEmpty());
        assertFalse(controller.complete());
        assertEquals(Boolean.TRUE, fixture.store.load().orElseThrow().starterCreative());
        confirmNext(controller, fixture);
        confirmNext(controller, fixture);
        assertTrue(controller.complete());
        assertEquals(64, fixture.dirt);
    }

    @Test
    void creditAcknowledgementInvalidatesAnOlderCleanupCandidateWithoutSendingIt() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportController controller = fixture.begin();
        for (int step = 0; step < 3; step++) { confirmNext(controller, fixture); }
        var candidate = controller.preview(fixture.snapshot()).candidate();
        controller.acknowledgePlannedCredit(controller.pendingPlannedCredit().orElseThrow().id());
        assertThrows(IllegalStateException.class, () -> controller.issue(candidate, fixture.snapshot()));
        assertEquals(TemporarySupportController.Kind.REMOVE_TOP,
                controller.issue(controller.preview(fixture.snapshot()).candidate(), fixture.snapshot()).kind());
    }

    private static TemporarySupportController.Operation action(TemporarySupportController controller, Fixture fixture)
            throws IOException {
        TemporarySupportController.Decision decision = controller.preview(fixture.snapshot());
        if (decision.status() == TemporarySupportController.Status.MAINTENANCE) {
            controller.settleObservedState(fixture.snapshot());
            decision = controller.preview(fixture.snapshot());
        }
        assertEquals(TemporarySupportController.Status.ACTION, decision.status(), decision.detail());
        return controller.issue(decision.candidate(), fixture.snapshot());
    }

    private static void confirmNext(TemporarySupportController controller, Fixture fixture) throws IOException {
        TemporarySupportController.Operation operation = action(controller, fixture);
        fixture.apply(operation);
        assertEquals(TemporarySupportController.Outcome.CONFIRMED,
                controller.acknowledge(operation, fixture.snapshot()).outcome());
    }

    private Fixture fixture() {
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), List.of(
                new TargetBlock(ANCHOR, new BlockState("minecraft:farmland")),
                new TargetBlock(SEED, new BlockState("minecraft:farmland"))));
        WorkOrder.OrdinaryBlocks order = new WorkOrder.OrdinaryBlocks(0, plan.chunk(0).chunk(),
                plan.chunk(0).ordinaryPlacements().stream().filter(placement -> placement.position().equals(SEED)).toList());
        return new Fixture(plan, order, directory.resolve("supports.json"));
    }

    private static final class Fixture {
        final SchematicPlan plan;
        final WorkOrder.OrdinaryBlocks order;
        final Path path;
        final TemporarySupportStore store;
        final MutableWorld world = new MutableWorld();
        long dirt = 64;
        boolean creative;
        BlockPosition prediction;

        Fixture(SchematicPlan plan, WorkOrder.OrdinaryBlocks order, Path path) {
            this.plan = plan;
            this.order = order;
            this.path = path;
            store = new TemporarySupportStore(path);
            world.states.put(ANCHOR, DIRT);
        }

        TemporarySupportController begin() throws IOException {
            return TemporarySupportController.begin(plan, order, CONTEXT, world, store).orElseThrow();
        }

        TemporarySupportController restore() throws IOException {
            return TemporarySupportController.restore(plan, order, CONTEXT, store).orElseThrow();
        }

        TemporarySupportController.Snapshot snapshot() {
            Map<BlockPosition, TemporarySupportController.CellRead> cells = new HashMap<>();
            for (BlockPosition position : List.of(ANCHOR, BOTTOM, TOP, SEED)) {
                cells.put(position, new TemporarySupportController.CellRead(world.received,
                        position.equals(prediction), world.states.getOrDefault(position, BlockState.AIR)));
            }
            return new TemporarySupportController.Snapshot(CONTEXT, cells, Map.of(Material.DIRT, dirt), creative);
        }

        void apply(TemporarySupportController.Operation operation) {
            world.states.put(operation.target(), operation.expected());
            if (!operation.kind().removes() && !creative) { dirt--; }
        }
    }

    private static final class MutableWorld implements BlockObservation {
        final Map<BlockPosition, BlockState> states = new HashMap<>();
        boolean received = true;
        @Override public boolean isChunkLoaded(ChunkCoordinate ignored) { return received; }
        @Override public BlockState blockState(BlockPosition position) {
            return states.getOrDefault(position, BlockState.AIR);
        }
    }
}
