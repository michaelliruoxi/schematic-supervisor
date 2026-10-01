package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.*;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurplusDisposalPolicyTest {
    static final long NOW = 10_000L;
    static final String PLAYER = "30fcd6d5-b7dc-4f00-83d1-76d82bd8737b";
    static final SurplusDisposalPolicy.Site SITE = new SurplusDisposalPolicy.Site(new BlockPosition(50, -48, 50), -64);

    @Test void onlyExplicitFreshZeroRoomProofSelectsAnApprovedSource() {
        for (String id : SurplusPickupPolicy.ITEM_IDS) {
            var proof = proof(id);
            assertEquals(2, SurplusDisposalPolicy.source(proof, true, NOW).orElseThrow());
            assertTrue(SurplusDisposalPolicy.source(proof, false, NOW).isEmpty());
            assertTrue(SurplusDisposalPolicy.source(proof, true, 0).isEmpty());
            assertTrue(SurplusDisposalPolicy.source(proof, true,
                    NOW + SurplusDisposalPolicy.MAXIMUM_PROOF_AGE_NANOS + 1).isEmpty());
        }
        assertTrue(SurplusDisposalPolicy.source(null, true, NOW).isEmpty());
        var noRoom = proof("minecraft:moss_block");
        var custom = replaceMain(noRoom.finalInventory().slots(), 2, stack("minecraft:moss_block", 37, false));
        var customObservation = withSlots(noRoom.finalInventory(), custom);
        assertTrue(SurplusStorageExhaustion.capture(List.of(customObservation.context()), List.of(customObservation),
                customObservation, 1, 2).isEmpty());
        assertFalse(SurplusPickupPolicy.allowed("minecraft:glowstone"));
        assertFalse(SurplusPickupPolicy.allowed("minecraft:dirt"));
        assertFalse(SurplusPickupPolicy.allowed("minecraft:diamond_hoe"));
        assertFalse(SurplusPickupPolicy.allowed("minecraft:tripwire_hook"));
    }

    @Test void anyPositiveCompatibleSpaceOrUnknownChestPreventsDisposalProof() {
        var proof = proof("minecraft:melon_seeds");
        var before = proof.finalInventory();
        var chest = new ArrayList<>(before.slots().chest());
        chest.set(0, withStack(chest.get(0), stack("minecraft:melon_seeds", 63, true)));
        var slots = new MossDepositFacts.Snapshot(chest, before.slots().main(), before.slots().cursor(),
                before.slots().offhand(), before.slots().armor());
        var room = withSlots(before, slots);
        assertTrue(SurplusStorageExhaustion.capture(List.of(before.context()), List.of(room), room, 1, 2).isEmpty());
        assertTrue(SurplusStorageExhaustion.capture(List.of(before.context()), List.of(), before, 1, 2).isEmpty());
    }

    @Test void exactWholeSourceDecreaseProtectsEveryOtherMainCursorAndEquipmentSlot() {
        var before = proof("minecraft:jack_o_lantern").finalInventory().slots();
        var after = replaceMain(before, 2, empty());
        assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2, after).isEmpty());
        assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2, before).isPresent());
        assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2,
                replaceMain(before, 2, stack("minecraft:jack_o_lantern", 1, true))).isPresent());
        for (int index = 0; index < 36; index++) {
            if (index != 2) { assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2, replaceMain(after, index, empty())).isPresent()); }
        }
        assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2, new MossDepositFacts.Snapshot(after.chest(), after.main(),
                stack("minecraft:melon_seeds", 1, true), after.offhand(), after.armor())).isPresent());
        assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2, new MossDepositFacts.Snapshot(after.chest(), after.main(),
                after.cursor(), empty(), after.armor())).isPresent());
        for (int index = 0; index < 4; index++) {
            var armor = new ArrayList<>(after.armor()); armor.set(index, stack("minecraft:diamond_helmet", 1, false));
            assertTrue(SurplusDisposalPolicy.receiptProblem(before, 2, new MossDepositFacts.Snapshot(after.chest(), after.main(),
                    after.cursor(), after.offhand(), armor)).isPresent());
        }
    }

    @Test void geometryChecksEveryBoundedCellDownToWorldBottomAndRejectsOneMissingCell() {
        var visited = new java.util.HashSet<BlockPosition>();
        assertTrue(SITE.clear(visited::add));
        assertEquals(SITE.cells(), visited.size());
        assertTrue(visited.contains(new BlockPosition(32, -64, 32)));
        assertTrue(visited.contains(new BlockPosition(68, -42, 68)));
        assertFalse(SITE.clear(pos -> !pos.equals(new BlockPosition(50, -64, 50))));
        assertThrows(IndexOutOfBoundsException.class, () -> SITE.cell(SITE.cells()));
        assertThrows(IllegalArgumentException.class, () -> new SurplusDisposalPolicy.Site(new BlockPosition(50, -60, 50), -64));
        assertThrows(IllegalArgumentException.class, () -> new SurplusDisposalPolicy.Site(new BlockPosition(50, 27, 50), -64));
        assertTrue(SITE.cells() <= 37 * 37 * 97);
    }

    @Test void candidateSetIsBoundedAndSeparatedFromEntireBuildAndEveryDepot() {
        var build = new BuildVolume(0, -64, 0, 100, 80, 100);
        var context = proof("minecraft:moss_block").finalInventory().context();
        var depots = List.of(new MossDepositJournal.Context(context.worldIdentityHash(), context.dimension(), context.planId(),
                "depot-away", -40, -60, 50));
        var candidates = SurplusDisposalPolicy.candidates(build, depots, new BlockPosition(0, -48, 50), -64, 319);
        assertFalse(candidates.isEmpty()); assertTrue(candidates.size() <= 8);
        assertTrue(candidates.stream().allMatch(site -> SurplusDisposalPolicy.separated(site, build, depots)));
        assertFalse(SurplusDisposalPolicy.separated(new SurplusDisposalPolicy.Site(new BlockPosition(-40, -48, 50), -64), build, depots));
        assertFalse(SurplusDisposalPolicy.separated(SITE, build, List.of()));
        assertTrue(SurplusDisposalPolicy.candidates(build, depots, new BlockPosition(0, 100, 50), -64, 319).isEmpty());
    }

    @Test void pinnedNormalDropEnvelopeFitsInsidePrismBeforePickupDelayExpires() {
        // LivingEntity.createItemEntity: speed <= .3+.02, vertical <= .5, pickup delay40.
        // ItemEntity: gravity .04, horizontal .98f and vertical .98 drag; no ground or fluid in the prism.
        double horizontal = 0, vertical = 0, horizontalSpeed = 0.320001, verticalSpeed = 0.500001;
        double highest = 0;
        for (int tick = 0; tick < 300; tick++) {
            verticalSpeed -= 0.04;
            horizontal += horizontalSpeed; vertical += verticalSpeed;
            horizontalSpeed *= (double) 0.98f; verticalSpeed *= 0.98;
            highest = Math.max(highest, vertical);
            if (tick == 39) { assertTrue(vertical < -6, "the item is below pickup reach before its delay ends"); }
        }
        assertTrue(horizontal < 16.01);
        assertTrue(highest + 2.0 < 6.0, "spawn eye offset plus upward motion fits the top margin");
        assertTrue(horizontal + 0.3 + 0.25 < SurplusDisposalPolicy.PRISM_RADIUS);
    }

    @Test void pendingTravelRejectsDownwardDetoursAndLaterColumnReentryIncludingSweptBoxes() {
        assertTrue(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(49.7, -48, 49.7, 50.3, -46.2, 50.3)));
        assertTrue(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(49.7, -48.3, 49.7, 50.3, -46.2, 50.3)));
        assertFalse(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(49.7, -48.301, 49.7, 50.3, -46.2, 50.3)));
        assertTrue(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(80, -60, 49.7, 80.6, -58.2, 50.3)));
        assertFalse(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(50, -60, 49.7, 80.6, -58.2, 50.3)));
        assertFalse(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(31.9, -50, 32, 32.1, -48.2, 33)));
        assertTrue(SurplusDisposalPolicy.permitsTravel(SITE, new net.minecraft.util.math.Box(31, -60, 32, 32, -58.2, 33)));
    }

    @Test void productionPlannerCanLeaveColumnBeforeDescendingToTheReceiptDepot() {
        BlockPosition start = SITE.feet();
        BlockPosition target = new BlockPosition(80, -60, 50);
        var planner = new FlightRoutePlanner(start, target, 0, 64, 4000,
                pos -> pos.x() >= 50 && pos.x() <= 80 && pos.y() >= -60 && pos.y() <= -48 && pos.z() == 50
                        && SurplusDisposalPolicy.permitsTravel(SITE, travelBox(pos, pos)),
                target::equals, (from, to) -> SurplusDisposalPolicy.permitsTravel(SITE, travelBox(from, to)));
        for (int tick = 0; tick < 40 && !planner.complete() && !planner.failed(); tick++) { planner.tick(128); }
        assertTrue(planner.complete(), planner.detail());
        var path = planner.path();
        assertTrue(path.stream().anyMatch(pos -> pos.y() < -48));
        for (int index = 1; index < path.size(); index++) {
            assertTrue(SurplusDisposalPolicy.permitsTravel(SITE, travelBox(path.get(index - 1), path.get(index))));
        }
    }

    private static net.minecraft.util.math.Box travelBox(BlockPosition from, BlockPosition to) {
        return new net.minecraft.util.math.Box(Math.min(from.x(), to.x()) + 0.2, Math.min(from.y(), to.y()) + 0.1,
                Math.min(from.z(), to.z()) + 0.2, Math.max(from.x(), to.x()) + 0.8, Math.max(from.y(), to.y()) + 1.9,
                Math.max(from.z(), to.z()) + 0.8);
    }

    static SurplusStorageExhaustion proof(String itemId) {
        var original = before();
        var insertion = new LinkedHashMap<String, MossDepositFacts.Insertion>();
        for (String id : SurplusPickupPolicy.ITEM_IDS) { insertion.put(id, new MossDepositFacts.Insertion(true, 64)); }
        var chest = new ArrayList<MossDepositFacts.SlotFacts>();
        for (int index = 0; index < 27; index++) {
            chest.add(new MossDepositFacts.SlotFacts(index, index, stack("minecraft:dirt", 64, false), true, insertion));
        }
        var originalSlots = replaceMain(original.slots(), 2, stack(itemId, 37, true));
        var slots = new MossDepositFacts.Snapshot(chest, originalSlots.main(), originalSlots.cursor(), originalSlots.offhand(), originalSlots.armor());
        var observed = withSlots(original, slots);
        return new SurplusStorageExhaustion(List.of(observed.context()), List.of(observed), observed, 1, 2);
    }
    static MossDepositJournal.Observation withSlots(MossDepositJournal.Observation observed, MossDepositFacts.Snapshot slots) {
        return new MossDepositJournal.Observation(observed.context(), observed.stamp(), slots);
    }
    static MossDepositJournal.Observation receipt(SurplusStorageExhaustion proof) {
        return new MossDepositJournal.Observation(proof.finalInventory().context(),
                new ServerInventorySnapshotStamp(EPOCH, 1, 2, 2, 2, 0),
                replaceMain(proof.finalInventory().slots(), 2, empty()));
    }
}
