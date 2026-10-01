package io.github.schematicsupervisor.fabric;

import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MossDepositPlanningTest {
    private static final MossDepositFacts.StackFacts EMPTY = stack("minecraft:air", 0, false, "empty");
    private static final MossDepositFacts.StackFacts DIRT = stack("minecraft:dirt", 64, false, "default");

    @Test void choosesOnlyWholePlainMossFromMainInventory() {
        Fixture fixture = new Fixture(27);
        fixture.main(0, stack(MossDepositFacts.MOSS, 64, false, "custom"));
        fixture.main(1, stack("minecraft:diamond_hoe", 1, false, "damaged"));
        fixture.main(2, stack("minecraft:tripwire_hook", 5, false, "protected"));
        fixture.main(3, moss(23));
        fixture.chest(2, EMPTY, true, 64);
        fixture.offhand = moss(64);
        fixture.armor.set(0, moss(64));
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(fixture.snapshot()).orElseThrow();
        assertEquals(3, plan.sourceMainIndex());
        assertEquals(57, plan.sourceHandlerSlot());
        assertEquals(23, plan.quantity());
        assertTrue(MossDepositPlanning.plan(fixture.snapshot(), 0).isEmpty());
        assertTrue(MossDepositPlanning.plan(fixture.snapshot(), 36).isEmpty());
    }

    @Test void refusesNonemptyCursorAndUnavailableTakePermission() {
        Fixture fixture = ready(27, 16);
        fixture.cursor = moss(1);
        assertTrue(MossDepositPlanning.plan(fixture.snapshot()).isEmpty());
        fixture.cursor = EMPTY;
        MossDepositFacts.SlotFacts previous = fixture.main.get(4);
        fixture.main.set(4, new MossDepositFacts.SlotFacts(previous.handlerSlot(), 4, previous.stack(), false, true, 64));
        assertTrue(MossDepositPlanning.plan(fixture.snapshot()).isEmpty());
    }

    @Test void usesOnlyObservedPermissionAndCompatibleRoomIncludingPartialStacks() {
        Fixture fixture = new Fixture(54);
        fixture.main(4, moss(64));
        fixture.chest(0, EMPTY, false, 64);
        fixture.chest(1, stack(MossDepositFacts.MOSS, 1, false, "custom"), true, 64);
        fixture.chest(2, moss(60), true, 64);
        fixture.chest(3, EMPTY, true, 59);
        assertEquals(63, MossDepositPlanning.plan(fixture.snapshot()).orElseThrow().quantity());
        fixture.chest(3, EMPTY, true, 60);
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(fixture.snapshot()).orElseThrow();
        assertEquals(List.of(new MossDepositFacts.Destination(2, 4), new MossDepositFacts.Destination(3, 60)),
                plan.destinations());
        assertEquals(85, plan.sourceHandlerSlot());
    }

    @Test void validatesCompletePhysicalChestAndMainMappings() {
        for (int size : List.of(27, 54)) {
            Fixture fixture = new Fixture(size);
            assertEquals(size + 27, fixture.snapshot().main().get(0).handlerSlot());
            assertEquals(size, fixture.snapshot().main().get(9).handlerSlot());
            assertEquals(size + 26, fixture.snapshot().main().get(35).handlerSlot());
            fixture.main.set(3, fixture.main.get(4));
            assertThrows(IllegalArgumentException.class, fixture::snapshot);
        }
        Fixture fixture = new Fixture(27);
        fixture.chest.removeLast();
        assertThrows(IllegalArgumentException.class, fixture::snapshot);
        assertThrows(IllegalArgumentException.class, () -> MossDepositFacts.mainHandlerSlot(18, 0));
    }

    @Test void snapshotsOwnImmutableListsAndRequireAllEquipmentGuards() {
        Fixture fixture = ready(27, 5);
        MossDepositFacts.Snapshot saved = fixture.snapshot();
        fixture.main(4, EMPTY);
        fixture.armor.set(0, DIRT);
        assertEquals(5, saved.main().get(4).stack().count());
        assertEquals(EMPTY, saved.armor().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> saved.main().clear());
        assertThrows(IllegalArgumentException.class, () -> new MossDepositFacts.Snapshot(saved.chest(), saved.main(),
                saved.cursor(), saved.offhand(), List.of(EMPTY)));
    }

    @Test void acceptsAnyExactPositiveAllocationWithinObservedDestinationCapacity() {
        Fixture fixture = ready(54, 20);
        fixture.chest(1, moss(54), true, 64);
        MossDepositFacts.Snapshot before = fixture.snapshot();
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, EMPTY);
        fixture.chest(0, moss(15), true, 64);
        fixture.chest(1, moss(59), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isEmpty());
        fixture.chest(0, moss(20), true, 64);
        fixture.chest(1, moss(54), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isEmpty());
    }

    @Test void rejectsPartialMoveAndMismatchedDestinationQuantity() {
        Fixture fixture = ready(27, 20);
        MossDepositFacts.Snapshot before = fixture.snapshot();
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, moss(1));
        fixture.chest(0, moss(19), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.main(4, EMPTY);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.chest(0, moss(21), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
    }

    @Test void rejectsProtectedMainChangesIncludingComponentOnlyChanges() {
        Fixture fixture = ready(27, 20);
        fixture.main(1, stack("minecraft:tripwire_hook", 5, false, "original"));
        MossDepositFacts.Snapshot before = fixture.snapshot();
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, EMPTY);
        fixture.chest(0, moss(20), true, 64);
        fixture.main(1, stack("minecraft:tripwire_hook", 5, false, "changed-component"));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.main(1, stack("minecraft:tripwire_hook", 4, false, "original"));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
    }

    @Test void rejectsCursorAndLocalEquipmentChanges() {
        Fixture fixture = ready(27, 20);
        MossDepositFacts.Snapshot before = fixture.snapshot();
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, EMPTY);
        fixture.chest(0, moss(20), true, 64);
        fixture.cursor = moss(1);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.cursor = EMPTY;
        fixture.offhand = DIRT;
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.offhand = EMPTY;
        for (int index = 0; index < 4; index++) {
            fixture.armor.set(index, DIRT);
            assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
            fixture.armor.set(index, EMPTY);
        }
    }

    @Test void rejectsProtectedChestChangesAndMossRearrangementDespiteMatchingTotals() {
        Fixture fixture = ready(27, 20);
        fixture.chest(1, moss(10), true, 64);
        fixture.chest(2, stack(MossDepositFacts.MOSS, 4, false, "custom"), true, 64);
        MossDepositFacts.Snapshot before = fixture.snapshot();
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, EMPTY);
        fixture.chest(0, moss(25), true, 64);
        fixture.chest(1, moss(5), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.chest(0, moss(20), true, 64);
        fixture.chest(1, moss(10), true, 64);
        fixture.chest(2, stack(MossDepositFacts.MOSS, 4, false, "other-custom"), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
    }

    @Test void rejectsChangedPlanAndTransferOutsideInitialPermissionOrLimit() {
        Fixture fixture = ready(27, 20);
        fixture.chest(0, EMPTY, true, 19);
        fixture.chest(1, EMPTY, true, 64);
        fixture.chest(2, EMPTY, false, 64);
        MossDepositFacts.Snapshot before = fixture.snapshot();
        MossDepositFacts.Plan plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, EMPTY);
        fixture.chest(0, moss(20), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.chest(0, EMPTY, true, 19);
        fixture.chest(2, moss(20), true, 64);
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        MossDepositFacts.Plan changed = new MossDepositFacts.Plan(4, plan.sourceHandlerSlot(), 19, plan.destinations());
        assertTrue(MossDepositPlanning.receiptProblem(before, changed, fixture.snapshot()).isPresent());
    }

    @Test void rejectsMalformedHashesUnboundedStacksAndFalsePlainClassification() {
        assertThrows(IllegalArgumentException.class, () -> new MossDepositFacts.StackFacts("payload", "minecraft:dirt", 1, 64, false, false));
        assertThrows(IllegalArgumentException.class, () -> new MossDepositFacts.StackFacts("a".repeat(64), "minecraft:dirt", 1, 64, false, true));
        assertThrows(IllegalArgumentException.class, () -> new MossDepositFacts.StackFacts("a".repeat(64), MossDepositFacts.MOSS, 65, 64, false, true));
    }

    @Test void eachAllowedPickupUsesOnlyItsOwnDefaultCompatibleCapacity() {
        for (String id : SurplusPickupPolicy.ITEM_IDS) {
            Fixture fixture = new Fixture(27);
            fixture.main(4, stack(id, 23, true, "default"));
            fixture.chest.set(0, new MossDepositFacts.SlotFacts(0, 0, EMPTY, true,
                    Map.of(id, new MossDepositFacts.Insertion(true, 64))));
            var before = fixture.snapshot();
            var plan = MossDepositPlanning.plan(before).orElseThrow();
            assertEquals(23, plan.quantity());
            fixture.main(4, EMPTY);
            fixture.chest.set(0, new MossDepositFacts.SlotFacts(0, 0, stack(id, 23, true, "default"), true,
                    Map.of(id, new MossDepositFacts.Insertion(true, 64))));
            assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isEmpty(), id);
            fixture.main(4, stack(id, 23, false, "custom"));
            assertTrue(MossDepositPlanning.plan(fixture.snapshot()).isEmpty(), id);
        }
    }

    @Test void partialPickupReceiptRequiresExactSourceRemainderAndSameItemChestIncrease() {
        Fixture fixture = new Fixture(27);
        String source = "minecraft:pumpkin_seeds";
        String other = "minecraft:melon_seeds";
        var permissions = Map.of(source, new MossDepositFacts.Insertion(true, 64),
                other, new MossDepositFacts.Insertion(true, 64));
        fixture.main(4, stack(source, 10, true, "default"));
        fixture.chest.set(0, new MossDepositFacts.SlotFacts(0, 0, stack(source, 60, true, "default"), true, permissions));
        fixture.chest.set(1, new MossDepositFacts.SlotFacts(1, 1, stack(other, 1, true, "default"), true, permissions));
        var before = fixture.snapshot();
        var plan = MossDepositPlanning.plan(before).orElseThrow();
        assertEquals(4, plan.quantity(), "room for a different seed cannot increase this transfer");
        fixture.main(4, stack(source, 6, true, "default"));
        fixture.chest.set(0, new MossDepositFacts.SlotFacts(0, 0, stack(source, 64, true, "default"), true, permissions));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isEmpty());
        fixture.main(4, stack(source, 6, false, "custom"));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.main(4, stack(source, 5, true, "default"));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
        fixture.main(4, stack(source, 6, true, "default"));
        fixture.chest.set(0, new MossDepositFacts.SlotFacts(0, 0, stack(other, 64, true, "default"), true, permissions));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
    }

    @Test void oldMossReceiptPreservesUnchangedNewlyEligiblePickupWithoutInventingOldEligibility() {
        Fixture fixture = ready(27, 20);
        fixture.main(5, stack("minecraft:pumpkin_seeds", 8, false, "default"));
        var before = fixture.snapshot();
        var plan = MossDepositPlanning.plan(before).orElseThrow();
        fixture.main(4, EMPTY);
        fixture.chest(0, moss(20), true, 64);
        fixture.main(5, stack("minecraft:pumpkin_seeds", 8, true, "default"));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isEmpty());
        assertFalse(before.main().get(5).stack().plainPickup());
        fixture.main(5, stack("minecraft:pumpkin_seeds", 8, false, "changed-component"));
        assertTrue(MossDepositPlanning.receiptProblem(before, plan, fixture.snapshot()).isPresent());
    }

    private static Fixture ready(int chestSize, int quantity) {
        Fixture fixture = new Fixture(chestSize);
        fixture.main(4, moss(quantity));
        fixture.chest(0, EMPTY, true, 64);
        return fixture;
    }

    private static MossDepositFacts.StackFacts moss(int count) { return stack(MossDepositFacts.MOSS, count, true, "default"); }
    private static MossDepositFacts.StackFacts stack(String id, int count, boolean plain, String component) {
        String hash = MossStackFingerprint.fingerprint(id, count, new JsonPrimitive(component), new MossStackFingerprint.Budget()).orElseThrow();
        return new MossDepositFacts.StackFacts(hash, id, count, count == 0 ? 0 : id.endsWith("hoe") ? 1 : 64, count == 0, plain);
    }

    private static final class Fixture {
        private final List<MossDepositFacts.SlotFacts> chest = new ArrayList<>();
        private final List<MossDepositFacts.SlotFacts> main = new ArrayList<>();
        private final List<MossDepositFacts.StackFacts> armor = new ArrayList<>(List.of(EMPTY, EMPTY, EMPTY, EMPTY));
        private MossDepositFacts.StackFacts cursor = EMPTY;
        private MossDepositFacts.StackFacts offhand = EMPTY;
        private Fixture(int size) {
            for (int index = 0; index < size; index++) { chest.add(new MossDepositFacts.SlotFacts(index, index, DIRT, true, true, 64)); }
            for (int index = 0; index < 36; index++) { main.add(new MossDepositFacts.SlotFacts(MossDepositFacts.mainHandlerSlot(size, index), index, DIRT, true, true, 64)); }
        }
        private void main(int index, MossDepositFacts.StackFacts stack) {
            main.set(index, new MossDepositFacts.SlotFacts(MossDepositFacts.mainHandlerSlot(chest.size(), index), index, stack, true, true, 64));
        }
        private void chest(int index, MossDepositFacts.StackFacts stack, boolean insert, int limit) {
            chest.set(index, new MossDepositFacts.SlotFacts(index, index, stack, true, insert, limit));
        }
        private MossDepositFacts.Snapshot snapshot() { return new MossDepositFacts.Snapshot(chest, main, cursor, offhand, armor); }
    }
}
