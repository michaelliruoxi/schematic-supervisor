package io.github.schematicsupervisor.fabric;

import static io.github.schematicsupervisor.fabric.ClearingToolSelection.Family.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises production ranking and the shared receipt ledger without initializing game registries. */
final class ClearingToolSelectionTest {
    @Test void choosesTheAppropriateFamilyWithoutAuthorizingOtherTargets() {
        assertEquals(HOE, ClearingToolSelection.appropriateFamily("minecraft:moss_block"));
        for (String block : List.of("jack_o_lantern", "pumpkin", "carved_pumpkin", "melon")) {
            assertEquals(AXE, ClearingToolSelection.appropriateFamily("minecraft:" + block));
        }
        assertEquals(SHOVEL, ClearingToolSelection.appropriateFamily("minecraft:dirt"));
        assertEquals(SHOVEL, ClearingToolSelection.appropriateFamily("minecraft:grass_block"));
        assertEquals(OTHER, ClearingToolSelection.appropriateFamily("minecraft:diamond_block"));
        assertEquals(OTHER, ClearingToolSelection.appropriateFamily(null));
        assertEquals(List.of(), ClearingToolSelection.ranked("minecraft:diamond_block", List.of(candidate(7, AXE, 100))));
    }

    @Test void ranksActualAppropriateSpeedAcrossHotbarAndMainInventory() {
        var candidates = List.of(candidate(0, HOE, 100), candidate(7, AXE, 8),
                candidate(24, AXE, 9), candidate(4, OTHER, 1));
        assertEquals(List.of(24, 7), ClearingToolSelection.ranked("minecraft:jack_o_lantern", candidates));
        assertEquals(List.of(0), ClearingToolSelection.ranked("minecraft:moss_block", candidates));
        assertEquals(List.of(12), ClearingToolSelection.ranked("minecraft:dirt",
                List.of(candidate(0, HOE, 100), candidate(7, AXE, 8), candidate(12, SHOVEL, 6))));
    }

    @Test void stableTiesKeepLowerSlotAndUnsafeCandidatesDoNotHideUsableFallback() {
        var candidates = List.of(candidate(8, AXE, 8), candidate(7, AXE, 8),
                new ClearingToolSelection.Candidate(3, AXE, false, 100), candidate(1, AXE, Double.NaN),
                candidate(2, AXE, Double.POSITIVE_INFINITY), candidate(4, AXE, 0));
        assertEquals(List.of(7, 8), ClearingToolSelection.ranked("minecraft:jack_o_lantern", candidates));
    }

    @Test void candidateEfficiencyAndAttributeFactorsCanReverseBaseToolOrder() {
        double enchantedDiamond = ClearingToolSelection.score(8, 26, 1, false, .2);
        double plainNetherite = ClearingToolSelection.score(9, 0, 1, false, .2);
        assertTrue(enchantedDiamond > plainNetherite);
        assertEquals(List.of(7, 8), ClearingToolSelection.ranked("minecraft:jack_o_lantern",
                List.of(candidate(8, AXE, plainNetherite), candidate(7, AXE, enchantedDiamond))));
        assertEquals(68, ClearingToolSelection.score(8, 26, 2, false, .2));
        assertEquals(13.6, ClearingToolSelection.score(8, 26, 2, true, .2), 0.000001);
    }

    @Test void unavailableAttributesFallBackForEveryAppropriateCandidateWithoutMixingUnits() {
        var candidates = List.of(new ClearingToolSelection.Candidate(7, AXE, true, 8, 34),
                new ClearingToolSelection.Candidate(24, AXE, true, 9, Double.NaN),
                new ClearingToolSelection.Candidate(0, HOE, true, 10, 100));
        assertEquals(List.of(24, 7), ClearingToolSelection.ranked("minecraft:jack_o_lantern", candidates));
        assertEquals(List.of(7), ClearingToolSelection.ranked("minecraft:jack_o_lantern",
                List.of(new ClearingToolSelection.Candidate(7, AXE, true, 8, Double.NaN))));
    }

    @Test void completeEffectiveScoresRankNormallyAndWrongFamilyFactsCannotForceFallback() {
        assertEquals(List.of(7, 24), ClearingToolSelection.ranked("minecraft:jack_o_lantern", List.of(
                new ClearingToolSelection.Candidate(7, AXE, true, 8, 34),
                new ClearingToolSelection.Candidate(24, AXE, true, 9, 9),
                new ClearingToolSelection.Candidate(0, HOE, true, 10, Double.NaN))));
    }

    @Test void invalidSpeedFactsCannotEstablishFastestTool() {
        assertTrue(Double.isNaN(ClearingToolSelection.score(1, 26, 1, false, 1)));
        assertTrue(Double.isNaN(ClearingToolSelection.score(Double.NaN, 0, 1, false, 1)));
        assertTrue(Double.isNaN(ClearingToolSelection.score(8, Double.POSITIVE_INFINITY, 1, false, 1)));
        assertTrue(Double.isNaN(ClearingToolSelection.score(8, -1, 1, false, 1)));
        assertTrue(Double.isNaN(ClearingToolSelection.score(8, 0, 0, false, 1)));
        assertTrue(Double.isNaN(ClearingToolSelection.score(8, 0, 1, true, 0)));
        assertTrue(Double.isNaN(ClearingToolSelection.score(8, 0, Double.MAX_VALUE, false, 1)));
    }

    @Test void admissionRequiresComparableSingleUseToolsWithinDurabilityBounds() {
        for (var family : List.of(HOE, AXE, SHOVEL)) {
            assertTrue(ClearingToolSelection.admitted(family, true, 1, 1, 39, 1561));
            assertFalse(ClearingToolSelection.admitted(family, false, 1, 1, 39, 1561));
            assertFalse(ClearingToolSelection.admitted(family, true, 2, 1, 39, 1561));
            assertFalse(ClearingToolSelection.admitted(family, true, 1, 2, 39, 1561));
            assertFalse(ClearingToolSelection.admitted(family, true, 1, 0, 39, 1561));
            assertFalse(ClearingToolSelection.admitted(family, true, 1, 1, -1, 1561));
            assertFalse(ClearingToolSelection.admitted(family, true, 1, 1, 1562, 1561));
            assertFalse(ClearingToolSelection.admitted(family, true, 1, 1, 0, 0));
        }
        assertFalse(ClearingToolSelection.admitted(OTHER, true, 1, 1, 39, 1561));
        assertFalse(ClearingToolSelection.admitted(null, true, 1, 1, 39, 1561));
    }

    @Test void usableToolsMustMatchTargetAndBeFasterThanPlainHandWithoutBeingBroken() {
        assertTrue(eligible(AXE, "minecraft:jack_o_lantern", 39, false, 8));
        assertTrue(eligible(HOE, "minecraft:moss_block", 39, false, 8));
        assertTrue(eligible(SHOVEL, "minecraft:dirt", 39, false, 8));
        assertFalse(eligible(HOE, "minecraft:jack_o_lantern", 39, false, 8));
        assertFalse(eligible(AXE, "minecraft:moss_block", 39, false, 8));
        assertFalse(eligible(AXE, "minecraft:jack_o_lantern", 39, false, 1));
        assertFalse(eligible(AXE, "minecraft:jack_o_lantern", 1561, false, 8));
        assertTrue(eligible(AXE, "minecraft:jack_o_lantern", 1561, true, 8));
    }

    @Test void candidateInventoryIsBoundedAndCannotRepeatSlots() {
        assertThrows(IllegalArgumentException.class, () -> ClearingToolSelection.ranked("minecraft:moss_block", null));
        assertThrows(IllegalArgumentException.class, () -> ClearingToolSelection.ranked("minecraft:moss_block",
                List.of(candidate(0, HOE, 8), candidate(0, HOE, 8))));
        assertThrows(IllegalArgumentException.class, () -> ClearingToolSelection.ranked("minecraft:moss_block",
                List.of(candidate(36, HOE, 8))));
        assertThrows(IllegalArgumentException.class, () -> ClearingToolSelection.ranked("minecraft:moss_block",
                java.util.Collections.nCopies(37, candidate(0, HOE, 8))));
    }

    @Test void selectedAxeKeepsExactSlotAndIdentityForTheWholeOwnedBreak() {
        var guard = new MossMiningToolGuard<String>(String::equals);
        String axe = "minecraft:diamond_axe;components-a";
        var tracked = guard.track(axe, wear(39)).orElseThrow();
        var owned = guard.begin(7, tracked, axe, wear(39)).orElseThrow();
        assertEquals(1521, tracked.allowance());
        assertTrue(guard.owns(owned, 7, axe, wear(39)));
        assertTrue(guard.owns(owned, 7, axe, wear(40)));
        assertFalse(guard.owns(owned, 3, axe, wear(39)));
        assertFalse(guard.owns(owned, 7, "minecraft:diamond_hoe;components-a", wear(39)));
        assertFalse(guard.owns(owned, 7, "minecraft:diamond_axe;components-b", wear(39)));
        assertFalse(guard.owns(owned, 7, axe, wear(41)));
        assertTrue(guard.begin(7, tracked, axe, wear(39)).isEmpty());
        assertTrue(guard.settleCharge(owned.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertFalse(guard.settleCharge(owned.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertEquals(1521, tracked.allowance(), "A successful block receipt does not refund wear");
    }

    @Test void mixedToolCohortKeepsUnknownAxeDebtWhenCleanHoeBaselineRefreshes() {
        var guard = new MossMiningToolGuard<String>(String::equals);
        var axe = guard.track("axe", wear(1496)).orElseThrow();
        var hoe = guard.track("hoe", wear(39)).orElseThrow();
        var owned = guard.begin(7, axe, "axe", wear(1496)).orElseThrow();
        guard.settleCharge(owned.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN);
        axe.awaitRepair("axe-fix");
        assertTrue(axe.acceptRepair("axe-fix", wear(0)));
        var cohort = List.of(new MossMiningToolGuard.CohortMember<>(0, "hoe", wear(39)),
                new MossMiningToolGuard.CohortMember<>(7, "axe", wear(0)));
        var ticket = guard.beforeOwnedOpen(cohort).orElseThrow();
        assertTrue(guard.refreshFromReceipt(ticket, cohort));
        assertEquals(64, axe.allowance());
        assertTrue(axe.refreshDisabled());
        assertFalse(axe.canStart(wear(0)));
        assertEquals(1522, hoe.allowance());
    }

    @Test void durableAxeRepairNeedsItsLaterExactSlotPacketAndNeverCreditsWearByItself() throws IOException {
        var context = new RunContext("sha256:" + "a".repeat(64), "minecraft:overworld");
        String epoch = UUID.randomUUID().toString();
        var before = tool("minecraft:diamond_axe", 7, 1500);
        var repaired = tool("minecraft:diamond_axe", 7, 0);
        var session = new HoeRepairSession(new MemoryStore());
        session.begin(context, before, epoch, 10);
        var guard = new MossMiningToolGuard<String>(String::equals);
        var tracked = guard.track("axe", wear(1500)).orElseThrow();
        String operation = session.journal().orElseThrow().operationId();
        tracked.awaitRepair(operation);
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(context, repaired, null, true));
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(context, repaired,
                new HoeRepairSession.Receipt(epoch, 10, repaired), true));
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(context, repaired,
                new HoeRepairSession.Receipt(epoch, 11, tool("minecraft:diamond_hoe", 7, 0)), true));
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(context, repaired,
                new HoeRepairSession.Receipt(epoch, 12, tool("minecraft:diamond_axe", 3, 0)), true));
        assertTrue(session.pending());
        assertThrows(IllegalStateException.class, () -> session.begin(context, before, epoch, 13));
        assertEquals(HoeRepairSession.Status.READY, session.observe(context, repaired,
                new HoeRepairSession.Receipt(epoch, 13, repaired), true));
        assertTrue(tracked.acceptRepair(operation, wear(0)));
        assertEquals(61, tracked.allowance());
        assertFalse(tracked.canStart(wear(0)), "Only a later covered owned-depot receipt may replenish clean allowance");
    }

    private static ClearingToolSelection.Candidate candidate(int slot, ClearingToolSelection.Family family, double speed) {
        return new ClearingToolSelection.Candidate(slot, family, true, speed);
    }
    private static boolean eligible(ClearingToolSelection.Family family, String target, int damage,
                                     boolean unbreakable, double speed) {
        return ClearingToolSelection.eligible(family, target, true, 1, 1, damage, 1561, unbreakable, speed);
    }
    private static MossMiningToolGuard.Durability wear(int damage) {
        return new MossMiningToolGuard.Durability(damage, 1561, false);
    }
    private static HoeRepairSession.Tool tool(String item, int slot, int damage) {
        return new HoeRepairSession.Tool(slot, item, "b".repeat(64), 1, damage, 1561, false);
    }
    private static final class MemoryStore implements HoeRepairSession.Store {
        private Optional<HoeRepairSession.Journal> journal = Optional.empty();
        @Override public Optional<HoeRepairSession.Journal> load() { return journal; }
        @Override public void replace(Optional<HoeRepairSession.Journal> expected, HoeRepairSession.Journal next) {
            assertEquals(expected, journal);
            journal = Optional.of(next);
        }
    }
}
