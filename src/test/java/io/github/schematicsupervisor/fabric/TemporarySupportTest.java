package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.WorkOrder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TemporarySupportTest {
    private static final RunContext CONTEXT = new RunContext("sha256:" + "ab".repeat(32),
            "minecraft:overworld");
    private static final BlockPosition BOTTOM = new BlockPosition(0, 1, 0);
    private static final BlockPosition TOP = new BlockPosition(0, 2, 0);

    @TempDir
    Path directory;

    @Test
    void selectsDeterministicSeedAndExactlyTwoCellsRegardlessOfInputOrder() {
        Fixture fixture = fixture(true, List.of());
        TemporarySupportPlanner.Column column = fixture.column();
        ArrayList<OrdinaryPlacement> reversed = new ArrayList<>(fixture.order().placements());
        Collections.reverse(reversed);
        WorkOrder.OrdinaryBlocks reordered = new WorkOrder.OrdinaryBlocks(0,
                new ChunkCoordinate(0, 0), reversed);

        assertEquals(new BlockPosition(0, 3, 0), column.seed().position());
        assertEquals(new BlockPosition(0, 0, 0), column.anchor());
        assertEquals(List.of(BOTTOM, TOP), column.supports());
        assertEquals(column, TemporarySupportPlanner.find(fixture.plan(), reordered, fixture.world())
                .orElseThrow());
        assertEquals(TemporarySupportPlanner.sliceId(fixture.order()),
                TemporarySupportPlanner.sliceId(reordered));
    }

    @Test
    void rejectsFinalCropOrGlowstoneCellsEvenWhenCurrentlyAir() {
        for (TargetBlock obstacle : List.of(
                new TargetBlock(BOTTOM, new BlockState("minecraft:wheat")),
                new TargetBlock(TOP, new BlockState("minecraft:glowstone")))) {
            Fixture fixture = fixture(false, List.of(obstacle));
            assertTrue(TemporarySupportPlanner.find(fixture.plan(), fixture.order(), fixture.world()).isEmpty());
        }
    }

    @Test
    void rejectsOccupiedUnknownAndPlaceholderAirCells() {
        for (BlockState state : List.of(new BlockState("minecraft:stone"),
                new BlockState("minecraft:void_air"), new BlockState("minecraft:cave_air"))) {
            Fixture fixture = fixture(false, List.of());
            fixture.world().states.put(BOTTOM, state);
            assertTrue(TemporarySupportPlanner.find(fixture.plan(), fixture.order(), fixture.world()).isEmpty());
        }
        Fixture unknown = fixture(false, List.of());
        unknown.world().states.put(TOP, null);
        assertTrue(TemporarySupportPlanner.find(unknown.plan(), unknown.order(), unknown.world()).isEmpty());
        Fixture unreceived = fixture(false, List.of());
        unreceived.world().received = false;
        assertTrue(TemporarySupportPlanner.find(unreceived.plan(), unreceived.order(), unreceived.world()).isEmpty());
        assertEquals(0, unreceived.world().reads);
    }

    @Test
    void requiresReceivedPlannedStructuralAnchorAndOneHorizontalSlice() {
        Fixture fixture = fixture(false, List.of());
        fixture.world().states.put(new BlockPosition(0, 0, 0), BlockState.AIR);
        assertTrue(TemporarySupportPlanner.find(fixture.plan(), fixture.order(), fixture.world()).isEmpty());
        WorkOrder.OrdinaryBlocks twoLayers = new WorkOrder.OrdinaryBlocks(0,
                new ChunkCoordinate(0, 0), fixture.plan().chunk(0).ordinaryPlacements());
        assertThrows(IllegalArgumentException.class,
                () -> TemporarySupportPlanner.find(fixture.plan(), twoLayers, fixture.world()));
    }

    @Test
    void roundTripsEveryIntentAndConfirmationThenRequiresImmediateCleanup() throws IOException {
        Path path = directory.resolve("supports.json");
        TemporarySupportStore store = new TemporarySupportStore(path);
        TemporarySupportJournal journal = store.create(fixture(false, List.of()).column(), CONTEXT);
        assertTrue(journal.initial());
        assertFalse(journal.complete());
        List<TemporarySupportJournal.Action> actions = List.of(
                TemporarySupportJournal.Action.PLACE_INTENT,
                TemporarySupportJournal.Action.PLACE_CONFIRMED,
                TemporarySupportJournal.Action.PLACE_INTENT,
                TemporarySupportJournal.Action.PLACE_CONFIRMED,
                TemporarySupportJournal.Action.SEED_INTENT,
                TemporarySupportJournal.Action.SEED_CONFIRMED,
                TemporarySupportJournal.Action.REMOVE_INTENT,
                TemporarySupportJournal.Action.REMOVE_CONFIRMED,
                TemporarySupportJournal.Action.REMOVE_INTENT,
                TemporarySupportJournal.Action.REMOVE_CONFIRMED);
        int[] indexes = {0, 0, 1, 1, -1, -1, 1, 1, 0, 0};
        for (int index = 0; index < actions.size(); index++) {
            journal = actions.get(index) == TemporarySupportJournal.Action.SEED_CONFIRMED
                    ? store.confirmStarter(journal, false)
                    : store.transition(journal, actions.get(index), indexes[index]);
            TemporarySupportJournal reloaded = new TemporarySupportStore(path).loadFor(
                    journal.column().planId(), CONTEXT, journal.column().sliceId()).orElseThrow();
            assertEquals(journal, reloaded);
            assertEquals(index + 1L, reloaded.revision());
            assertEquals(index % 2 == 0, reloaded.requiresReconciliation());
            if (index >= 5) { assertTrue(reloaded.cleanupRequested()); }
        }
        assertFalse(journal.complete());
        assertTrue(journal.cleanupComplete());
        TemporarySupportJournal awaitingCredit = journal;
        assertThrows(IOException.class, () -> store.create(awaitingCredit.column(), CONTEXT));
        journal = store.acknowledgePlannedCredit(journal, journal.pendingPlannedCredit().orElseThrow().id());
        assertTrue(journal.complete());
        assertTrue(journal.outstandingSupports().isEmpty());
        assertEquals(journal, store.load().orElseThrow());
        assertTrue(store.create(fixture(false, List.of()).column(), CONTEXT).initial());
        try (var files = Files.list(directory)) {
            assertEquals(List.of(path), files.toList());
        }
    }

    @Test
    void refusesUnrecordedOrOutOfOrderChangesAndCanAbortAfterOnlyLowerSupport() {
        TemporarySupportJournal initial = TemporarySupportJournal.begin(fixture(false, List.of()).column(), CONTEXT);
        assertThrows(IllegalStateException.class,
                () -> initial.transition(TemporarySupportJournal.Action.PLACE_CONFIRMED, 0));
        assertThrows(IllegalStateException.class,
                () -> initial.transition(TemporarySupportJournal.Action.PLACE_INTENT, 1));
        TemporarySupportJournal intent = initial.transition(TemporarySupportJournal.Action.PLACE_INTENT, 0);
        assertThrows(IllegalStateException.class,
                () -> intent.transition(TemporarySupportJournal.Action.BEGIN_CLEANUP, -1));
        TemporarySupportJournal placed = intent.transition(TemporarySupportJournal.Action.PLACE_CONFIRMED, 0);
        TemporarySupportJournal cleanup = placed.transition(TemporarySupportJournal.Action.BEGIN_CLEANUP, -1);
        assertThrows(IllegalStateException.class,
                () -> cleanup.transition(TemporarySupportJournal.Action.SEED_INTENT, -1));
        assertThrows(IllegalStateException.class,
                () -> cleanup.transition(TemporarySupportJournal.Action.REMOVE_INTENT, 1));
        TemporarySupportJournal complete = cleanup.transition(TemporarySupportJournal.Action.REMOVE_INTENT, 0)
                .transition(TemporarySupportJournal.Action.REMOVE_CONFIRMED, 0);
        assertTrue(complete.complete());
    }

    @Test
    void staleStateOrMismatchedBindingCannotReplaceUnfinishedOwnership() throws IOException {
        TemporarySupportStore store = new TemporarySupportStore(directory.resolve("supports.json"));
        TemporarySupportJournal initial = store.create(fixture(false, List.of()).column(), CONTEXT);
        TemporarySupportJournal intent = store.transition(initial, TemporarySupportJournal.Action.PLACE_INTENT, 0);
        assertThrows(IOException.class, () -> store.transition(initial,
                TemporarySupportJournal.Action.PLACE_INTENT, 0));
        assertThrows(IOException.class, () -> store.create(initial.column(), CONTEXT));
        assertThrows(IOException.class, () -> store.loadFor("another-plan", CONTEXT, initial.column().sliceId()));
        assertThrows(IOException.class, () -> store.loadFor(initial.column().planId(),
                new RunContext(CONTEXT.worldIdentityHash(), "minecraft:the_nether"), initial.column().sliceId()));
        assertThrows(IOException.class, () -> store.loadFor(initial.column().planId(), CONTEXT,
                "sha256:" + "00".repeat(32)));
        assertEquals(intent, store.load().orElseThrow());
    }

    @Test
    void corruptOrAlteredOwnershipFailsClosedAndPreservesFile() throws IOException {
        Path path = directory.resolve("supports.json");
        TemporarySupportStore store = new TemporarySupportStore(path);
        store.create(fixture(false, List.of()).column(), CONTEXT);
        String altered = Files.readString(path).replace("minecraft:air", "minecraft:stone");
        Files.writeString(path, altered);
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.create(fixture(false, List.of()).column(), CONTEXT));
        assertEquals(altered, Files.readString(path));
        Files.writeString(path, "{broken");
        assertThrows(IOException.class, store::load);
    }

    @Test
    void legacyJournalWithoutCreditEvidenceIsPreservedAndCannotBeReplaced() throws IOException {
        Path path = directory.resolve("supports.json");
        TemporarySupportStore store = new TemporarySupportStore(path);
        var created = store.create(fixture(false, List.of()).column(), CONTEXT);
        String legacy = Files.readString(path).replace("\"version\": 2", "\"version\": 1");
        Files.writeString(path, legacy);
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.create(created.column(), CONTEXT));
        assertEquals(legacy, Files.readString(path));
    }

    private static Fixture fixture(boolean twoSeeds, List<TargetBlock> extra) {
        ArrayList<TargetBlock> targets = new ArrayList<>(extra);
        MutableWorld world = new MutableWorld();
        for (int x = 0; x < (twoSeeds ? 2 : 1); x++) {
            targets.add(new TargetBlock(new BlockPosition(x, 0, 0), new BlockState("minecraft:farmland")));
            targets.add(new TargetBlock(new BlockPosition(x, 3, 0), new BlockState("minecraft:farmland")));
            world.states.put(new BlockPosition(x, 0, 0), TemporarySupportJournal.DIRT);
        }
        SchematicPlan plan = SchematicCompiler.compile7x7(new ChunkCoordinate(0, 0), targets);
        WorkOrder.OrdinaryBlocks order = new WorkOrder.OrdinaryBlocks(0, plan.chunk(0).chunk(),
                plan.chunk(0).ordinaryPlacements().stream().filter(placement -> placement.position().y() == 3).toList());
        return new Fixture(plan, order, world);
    }

    private record Fixture(SchematicPlan plan, WorkOrder.OrdinaryBlocks order, MutableWorld world) {
        TemporarySupportPlanner.Column column() {
            return TemporarySupportPlanner.find(plan, order, world).orElseThrow();
        }
    }

    private static final class MutableWorld implements BlockObservation {
        private final Map<BlockPosition, BlockState> states = new HashMap<>();
        private boolean received = true;
        private int reads;

        @Override
        public boolean isChunkLoaded(ChunkCoordinate ignored) { return received; }

        @Override
        public BlockState blockState(BlockPosition position) {
            assertTrue(received);
            reads++;
            return states.getOrDefault(position, BlockState.AIR);
        }
    }
}
