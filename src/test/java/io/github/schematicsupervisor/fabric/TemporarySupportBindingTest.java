package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.SchematicCompiler;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.TargetBlock;
import io.github.schematicsupervisor.core.VerificationScope;
import io.github.schematicsupervisor.core.WorkOrder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporarySupportBindingTest {
    private static final RunContext CONTEXT = new RunContext("sha256:" + "ab".repeat(32), "minecraft:overworld");
    @TempDir Path directory;

    @Test
    void restoresTheOriginalCompleteSliceAndRejectsFilteredSlicesOrLaterFarming() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportBinding restored = new TemporarySupportBinding(fixture.plan(), CONTEXT, fixture.store());
        assertTrue(restored.outstanding());
        assertEquals(fixture.slice(), restored.slice);
        assertEquals(2, restored.slice.placements().size());
        assertDoesNotThrow(() -> restored.requireOrder(fixture.slice()));
        WorkOrder.OrdinaryBlocks filtered = new WorkOrder.OrdinaryBlocks(fixture.slice().chunkIndex(),
                fixture.slice().chunk(), List.of(fixture.slice().placements().getLast()));
        assertThrows(IllegalStateException.class, () -> restored.requireOrder(filtered));
        assertThrows(IllegalStateException.class, () -> restored.requireOrder(
                new WorkOrder.Till(0, fixture.slice().chunk(), List.of(new BlockPosition(0, 0, 0)))));
        assertEquals(fixture.journal(), fixture.store().load().orElseThrow(), "Binding never edits ownership");
    }

    @Test
    void anyUnfinishedProfileJournalBlocksForeignPlanWorldAndDimensionWithoutChangingItsBytes() throws IOException {
        Fixture fixture = fixture();
        byte[] before = Files.readAllBytes(fixture.path());
        SchematicPlan other = SchematicCompiler.compile(new BuildVolume(4, 0, 0, 5, 3, 0),
                List.of(target(4, 0), target(4, 3)));
        assertThrows(IOException.class, () -> new TemporarySupportBinding(other, CONTEXT, fixture.store()));
        assertThrows(IOException.class, () -> new TemporarySupportBinding(fixture.plan(),
                new RunContext(CONTEXT.worldIdentityHash(), "minecraft:the_nether"), fixture.store()));
        assertThrows(IOException.class, () -> new TemporarySupportBinding(fixture.plan(),
                new RunContext("sha256:" + "cd".repeat(32), CONTEXT.dimension()), fixture.store()));
        assertArrayEquals(before, Files.readAllBytes(fixture.path()));
    }

    @Test
    void pendingStarterCreditRetainsSliceOwnershipEvenAfterAllPhysicalSupportsAreGone() throws IOException {
        Fixture fixture = fixture();
        TemporarySupportJournal journal = fixture.journal();
        for (int index = 0; index < 2; index++) {
            journal = fixture.store().transition(journal, TemporarySupportJournal.Action.PLACE_INTENT, index);
            journal = fixture.store().transition(journal, TemporarySupportJournal.Action.PLACE_CONFIRMED, index);
        }
        journal = fixture.store().transition(journal, TemporarySupportJournal.Action.SEED_INTENT, -1);
        journal = fixture.store().confirmStarter(journal, false);
        for (int index = 1; index >= 0; index--) {
            journal = fixture.store().transition(journal, TemporarySupportJournal.Action.REMOVE_INTENT, index);
            journal = fixture.store().transition(journal, TemporarySupportJournal.Action.REMOVE_CONFIRMED, index);
        }
        TemporarySupportBinding restored = new TemporarySupportBinding(fixture.plan(), CONTEXT, fixture.store());
        assertTrue(restored.outstanding());
        assertTrue(restored.controller.outstandingSupports().isEmpty());
        assertTrue(restored.controller.pendingPlannedCredit().isPresent());
        restored.controller.acknowledgePlannedCredit(restored.controller.pendingPlannedCredit().orElseThrow().id());
        assertFalse(restored.outstanding());
        assertFalse(new TemporarySupportBinding(fixture.plan(), CONTEXT, fixture.store()).outstanding());
    }

    @Test
    void deferredModeNeverAdmitsPlantOrdersEvenWithoutAnyJournal() throws IOException {
        SchematicPlan plan = plan().withPlantingDeferred(true);
        TemporarySupportBinding binding = new TemporarySupportBinding(plan, CONTEXT,
                new TemporarySupportStore(directory.resolve("empty.json")));
        assertFalse(binding.outstanding());
        assertThrows(IllegalStateException.class, () -> binding.requireOrder(
                new WorkOrder.Plant(0, plan.chunk(0).chunk(), List.of(new BlockPosition(0, 1, 0)))));
        assertFalse(Files.exists(directory.resolve("empty.json")));
    }

    @Test
    void malformedOwnershipCannotBeBypassedBySelectingAnotherBuild() throws IOException {
        Path path = directory.resolve("broken.json");
        Files.writeString(path, "{broken");
        assertThrows(IOException.class, () -> new TemporarySupportBinding(plan(), CONTEXT,
                new TemporarySupportStore(path)));
        assertEquals("{broken", Files.readString(path));
    }

    private Fixture fixture() throws IOException {
        SchematicPlan plan = plan();
        WorkOrder.OrdinaryBlocks slice = (WorkOrder.OrdinaryBlocks) new LayerBuildSchedule(plan).entries().stream()
                .filter(entry -> entry.progress().y() == 3).findFirst().orElseThrow().order();
        Path path = directory.resolve("support.json");
        TemporarySupportStore store = new TemporarySupportStore(path);
        TemporarySupportController controller = TemporarySupportController.begin(plan, slice, CONTEXT,
                new BlockObservation() {
                    public boolean isChunkLoaded(ChunkCoordinate chunk) { return true; }
                    public BlockState blockState(BlockPosition position) {
                        return position.y() == 0 ? TemporarySupportJournal.DIRT : BlockState.AIR;
                    }
                    public List<BlockPosition> temporaryScaffolding(VerificationScope scope) { return List.of(); }
                }, store).orElseThrow();
        return new Fixture(plan, slice, store, controller.journal(), path);
    }

    private static SchematicPlan plan() {
        return SchematicCompiler.compile(new BuildVolume(0, 0, 0, 1, 3, 0),
                List.of(target(0, 0), target(1, 0), target(0, 3), target(1, 3)));
    }

    private static TargetBlock target(int x, int y) {
        return new TargetBlock(new BlockPosition(x, y, 0), TemporarySupportJournal.DIRT);
    }

    private record Fixture(SchematicPlan plan, WorkOrder.OrdinaryBlocks slice, TemporarySupportStore store,
                           TemporarySupportJournal journal, Path path) { }
}
