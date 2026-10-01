package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MossMiningToolGuardTest {
    private static final String TOOL = "diamond_hoe:original_components";
    private static final RunContext CONTEXT = new RunContext("sha256:" + "a".repeat(64), "minecraft:overworld");
    private static final String EPOCH = UUID.randomUUID().toString();

    @Test
    void diagnosticCannotAdmitIdentitiesOrClampAndRefillWearAllowance() {
        var guard = guard();
        for (int index = 0; index < 100; index++) {
            assertEquals("UNTRACKED_AVAILABLE", guard.diagnostic("unknown-" + index, wear(0)).reason());
        }
        assertEquals(0, guard.trackedIdentityCount());
        var tracked = guard.track(TOOL, wear(100)).orElseThrow();
        var attempt = guard.begin(0, tracked, TOOL, wear(100)).orElseThrow();
        assertTrue(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        int allowance = tracked.allowance();
        for (int index = 0; index < 100; index++) {
            var damaged = guard.diagnostic(TOOL, wear(1500));
            assertEquals("DURABILITY_RESERVE", damaged.reason());
            assertEquals(61, damaged.effectiveAllowance());
            assertEquals(allowance, damaged.allowance());
            assertEquals(allowance, guard.diagnostic(TOOL, wear(0)).effectiveAllowance());
        }
        assertEquals(allowance, tracked.allowance());
        assertEquals(1, guard.trackedIdentityCount());
    }

    @Test
    void diagnosticDistinguishesIdentityLimitFromDelayedWearWithoutConsumingEither() {
        var guard = guard();
        var original = guard.track(TOOL, wear(1496)).orElseThrow();
        var attempt = guard.begin(0, original, TOOL, wear(1496)).orElseThrow();
        assertTrue(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertEquals("ALLOWANCE_RESERVE", guard.diagnostic(TOOL, wear(0)).reason());
        for (int index = 1; index < guard.identityCap(); index++) {
            guard.track("distinct-" + index, wear(0)).orElseThrow();
        }
        for (int index = 0; index < 100; index++) {
            var denied = guard.diagnostic("another", wear(0));
            assertEquals("IDENTITY_LIMIT", denied.reason());
            assertFalse(denied.identityTracked());
            assertEquals(36, denied.trackedIdentities());
            assertEquals("ALLOWANCE_RESERVE", guard.diagnostic(TOOL, wear(0)).reason());
        }
        assertEquals(64, original.allowance());
        assertEquals(36, guard.trackedIdentityCount());
        assertTrue(guard.track("another", wear(0)).isEmpty());
    }

    @Test
    void diagnosticCannotSettleOrResetPendingRepair() {
        var guard = guard();
        var original = guard.track(TOOL, wear(1500)).orElseThrow();
        original.awaitRepair("current-repair");
        for (int index = 0; index < 100; index++) {
            assertEquals("WAIT_REPAIR", guard.diagnostic(TOOL, wear(0)).reason());
        }
        assertEquals(61, original.allowance());
        assertEquals(MossMiningToolGuard.Preparation.WAIT_REPAIR, original.prepare(wear(0), true));
        assertFalse(original.acceptRepair("different-repair", wear(0)));
    }

    @Test
    void lastSafeStartDebitsReserveEvenWhenServerWearHasNotArrived() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1496)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(1496)).orElseThrow();
        assertEquals(64, tool.allowance());
        assertTrue(guard.owns(attempt, 0, TOOL, wear(1496)), "Unbreaking or a delayed packet may leave damage unchanged");
        assertTrue(guard.owns(attempt, 0, TOOL, wear(1497)));
        assertTrue(guard.begin(0, tool, TOOL, wear(1496)).isEmpty(), "Stale damage cannot fund another break");
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tool.prepare(wear(1496), true));
        assertTrue(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertEquals(MossMiningToolGuard.Preparation.REPAIR, tool.prepare(wear(1497), true));
    }

    @Test
    void repairDisabledFallsBackBeforeReserveWithoutUsingUpTheTool() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1497)).orElseThrow();
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tool.prepare(wear(1497), false));
        assertTrue(guard.begin(0, tool, TOOL, wear(1497)).isEmpty());
        assertTrue(guard.begin(0, tool, TOOL, wear(1560)).isEmpty());
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tool.prepare(wear(1561), true));
    }

    @Test
    void reequippingOrHotbarMovesCannotResetAllowanceForTheSameIdentity() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1496)).orElseThrow();
        guard.begin(0, tool, TOOL, wear(1496)).orElseThrow();
        var afterMove = guard.track(new String(TOOL), wear(0)).orElseThrow();
        assertSame(tool, afterMove);
        assertEquals(64, afterMove.allowance());
        assertTrue(guard.begin(8, afterMove, TOOL, wear(0)).isEmpty());
        assertFalse(tool.acceptRepair("old-startup-confirmation", wear(0)));
        assertEquals(64, tool.allowance());
    }

    @Test
    void frozenSlotAndComponentsPreventSwitchingToolsDuringOwnedMining() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(10)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(10)).orElseThrow();
        assertTrue(guard.owns(attempt, 0, TOOL, wear(10)));
        assertTrue(guard.owns(attempt, 0, TOOL, wear(11)));
        assertFalse(guard.owns(attempt, 1, TOOL, wear(10)));
        assertFalse(guard.owns(attempt, 0, "diamond_hoe:changed_enchantment", wear(10)));
        assertFalse(guard.owns(attempt, 0, "plain_dirt", wear(10)));
        assertFalse(guard.owns(attempt, 0, TOOL, wear(9)));
        assertFalse(guard.owns(attempt, 0, TOOL, wear(12)));
        assertFalse(guard.owns(attempt, 0, TOOL, new MossMiningToolGuard.Durability(10, 2000, false)));
        assertFalse(guard.owns(attempt, 0, TOOL, new MossMiningToolGuard.Durability(10, 1561, true)));
    }

    @Test
    void chargeSurvivesAnAbortedBreakAndUnexplainedDamageReset() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(100)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(100)).orElseThrow();
        assertEquals(1460, tool.allowance());
        assertTrue(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN));
        assertTrue(tool.refreshDisabled());
        assertTrue(tool.canStart(wear(105)));
        assertEquals(1456, tool.allowance());
        assertTrue(tool.canStart(wear(0)));
        assertEquals(1456, tool.allowance(), "Unexplained damage changes cannot add allowance");
    }

    @Test
    void repairMustBeAwaitedForThisToolAndCannotBeReplayed() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1500)).orElseThrow();
        assertFalse(tool.acceptRepair("old-operation", wear(0)));
        tool.awaitRepair("new-operation");
        assertEquals(MossMiningToolGuard.Preparation.WAIT_REPAIR, tool.prepare(wear(0), false));
        assertTrue(guard.begin(0, tool, TOOL, wear(0)).isEmpty());
        assertFalse(tool.acceptRepair("old-operation", wear(0)));
        assertFalse(tool.acceptRepair("new-operation", wear(1)));
        assertTrue(tool.acceptRepair("new-operation", wear(0)));
        assertEquals(61, tool.allowance(), "The durable repair resolves its binding without adding wear credit");
        assertTrue(guard.begin(0, tool, TOOL, wear(0)).isEmpty());
        refresh(guard, List.of(member(0, TOOL, 0)));
        guard.begin(0, tool, TOOL, wear(0)).orElseThrow();
        assertFalse(tool.acceptRepair("new-operation", wear(0)));
        assertEquals(1560, tool.allowance());
        assertThrows(IllegalArgumentException.class, () -> tool.awaitRepair("new-operation"));
    }

    @Test
    void pendingRepairCannotBeReplacedWithAnotherCommandIdentity() {
        var tool = guard().track(TOOL, wear(1500)).orElseThrow();
        tool.awaitRepair("first");
        tool.awaitRepair("first");
        assertThrows(IllegalArgumentException.class, () -> tool.awaitRepair("second"));
        assertFalse(tool.acceptRepair("second", wear(0)));
        assertEquals(MossMiningToolGuard.Preparation.WAIT_REPAIR, tool.prepare(wear(0), true));
    }

    @Test
    void pausedCancelledRepairCannotFundMiningUntilItsMatchingServerReceiptSettles() throws IOException {
        var guard = guard();
        var tracked = guard.track(TOOL, wear(1500)).orElseThrow();
        var repair = startedRepair();
        String operation = repair.journal().orElseThrow().operationId();
        tracked.awaitRepair(operation);
        repair.cancel();
        assertEquals(HoeRepairSession.Status.BLOCKED, repair.observe(CONTEXT, repaired(0), null, false));
        assertTrue(guard.begin(0, tracked, TOOL, wear(0)).isEmpty());
        assertThrows(IllegalStateException.class, () -> repair.begin(CONTEXT, damaged(), EPOCH, 12));
        assertEquals(HoeRepairSession.Status.READY,
                repair.observe(CONTEXT, repaired(0), new HoeRepairSession.Receipt(EPOCH, 12, repaired(0)), false));
        assertTrue(repair.journal().orElseThrow().confirmed());
        assertTrue(tracked.acceptRepair(operation, wear(0)));
        assertTrue(guard.begin(0, tracked, TOOL, wear(0)).isEmpty());
        refresh(guard, List.of(member(0, TOOL, 0)));
        assertTrue(guard.begin(0, tracked, TOOL, wear(0)).isPresent());
    }

    @Test
    void foreignContextWrongSlotAndChangedToolCannotReleasePendingMining() throws IOException {
        var guard = guard();
        var tracked = guard.track(TOOL, wear(1500)).orElseThrow();
        var repair = startedRepair();
        tracked.awaitRepair(repair.journal().orElseThrow().operationId());
        assertEquals(HoeRepairSession.Status.WAITING,
                repair.observe(CONTEXT, repaired(0), new HoeRepairSession.Receipt(EPOCH, 11, repaired(1)), false));
        var other = new RunContext("sha256:" + "d".repeat(64), "minecraft:overworld");
        assertEquals(HoeRepairSession.Status.BLOCKED,
                repair.observe(other, repaired(0), new HoeRepairSession.Receipt(EPOCH, 12, repaired(0)), false));
        assertFalse(repair.journal().orElseThrow().confirmed());
        assertTrue(guard.begin(0, tracked, TOOL, wear(0)).isEmpty());
        assertTrue(guard.begin(0, tracked, "changed_tool_components", wear(0)).isEmpty());
    }

    @Test
    void repairSettlesBeforeAnAlreadyCorrectTargetIsSkippedAndLaterTillingWearsTheHoe() throws IOException {
        var guard = guard();
        var tracked = guard.track(TOOL, wear(1500)).orElseThrow();
        var repair = startedRepair();
        String operation = repair.journal().orElseThrow().operationId();
        tracked.awaitRepair(operation);
        assertEquals(HoeRepairSession.Status.READY,
                repair.observe(CONTEXT, repaired(0), new HoeRepairSession.Receipt(EPOCH, 11, repaired(0)), false));
        assertTrue(tracked.acceptRepair(operation, wear(0)), "Repair binding settles before target dispatch");
        // The old moss target is skipped. A later till order legitimately changes this tool's damage.
        var laterMoss = guard.track(TOOL, wear(7)).orElseThrow();
        assertSame(tracked, laterMoss);
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, laterMoss.prepare(wear(7), true));
        refresh(guard, List.of(member(0, TOOL, 7)));
        assertEquals(MossMiningToolGuard.Preparation.USE_HOE, laterMoss.prepare(wear(7), true));
        assertTrue(guard.begin(0, laterMoss, TOOL, wear(7)).isPresent());
        assertFalse(laterMoss.acceptRepair(operation, wear(0)), "The old repair never gets another chance to top up");
    }

    @Test
    void unavailableOptionalRepairEvidenceCanFallBackOnlyAfterDurableSettlementWithoutWearCredit() {
        var guard = guard();
        var tracked = guard.track(TOOL, wear(1500)).orElseThrow();
        tracked.awaitRepair("repair-operation");
        assertFalse(tracked.abandonCompletedRepair(HoeRepairSession.Status.WAITING, "repair-operation"));
        assertFalse(tracked.abandonCompletedRepair(HoeRepairSession.Status.BLOCKED, "repair-operation"));
        assertEquals(MossMiningToolGuard.Preparation.WAIT_REPAIR, tracked.prepare(wear(0), false));
        assertTrue(tracked.abandonCompletedRepair(HoeRepairSession.Status.READY, "repair-operation"));
        assertEquals(61, tracked.allowance());
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tracked.prepare(wear(7), true));
        assertFalse(tracked.acceptRepair("repair-operation", wear(0)));
        assertThrows(IllegalArgumentException.class, () -> tracked.awaitRepair("repair-operation"));
    }

    @Test
    void unbreakableToolUsesNoWearAllowanceOrRepair() {
        var guard = guard();
        var durability = new MossMiningToolGuard.Durability(1561, 1561, true);
        var tool = guard.track(TOOL, durability).orElseThrow();
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals(MossMiningToolGuard.Preparation.USE_HOE, tool.prepare(durability, false));
            var owned = guard.begin(0, tool, TOOL, durability).orElseThrow();
            assertTrue(guard.settleCharge(owned.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        }
        assertEquals(0, tool.allowance());
    }

    @Test
    void boundedIdentityTrackingFallsBackWithoutDroppingExistingWearHistory() {
        var guard = guard();
        var original = guard.track(TOOL, wear(1496)).orElseThrow();
        guard.begin(0, original, TOOL, wear(1496)).orElseThrow();
        for (int index = 1; index < 36; index++) { guard.track("tool-" + index, wear(0)).orElseThrow(); }
        assertTrue(guard.track("another-tool", wear(0)).isEmpty());
        assertSame(original, guard.track(TOOL, wear(0)).orElseThrow());
        assertEquals(64, original.allowance());
        assertTrue(guard.begin(9, original, TOOL, wear(0)).isEmpty());
    }

    @Test
    void cleanUnbreakingAttemptsKeepTheirDebitsUntilACoveredFullReceipt() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(372)).orElseThrow();
        long previousSequence = 0;
        while (tool.allowance() > 64) {
            int before = tool.allowance();
            var attempt = guard.begin(0, tool, TOOL, wear(372)).orElseThrow();
            assertTrue(attempt.charge().sequence() > previousSequence);
            previousSequence = attempt.charge().sequence();
            assertEquals(before - 1, tool.allowance());
            assertTrue(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
            assertEquals(before - 1, tool.allowance(), "Block confirmation does not refund a charge");
            assertFalse(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN));
        }
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tool.prepare(wear(372), true));
        var cohort = List.of(member(0, TOOL, 372), member(3, TOOL, 157));
        var ticket = guard.beforeOwnedOpen(cohort).orElseThrow();
        assertEquals(64, tool.allowance(), "Opening a depot is not an inventory receipt");
        assertTrue(guard.refreshFromReceipt(ticket, cohort));
        assertEquals(1189, tool.allowance());
        assertTrue(guard.begin(3, tool, TOOL, wear(157)).isPresent());
        assertEquals(1188, tool.allowance(), "The less worn spare shares the cohort minimum");
    }

    @Test
    void bothIdenticalToolsAreCoveredEvenWhenTheMoreWornSpareIsOutsideHotbar() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1496)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(1496)).orElseThrow();
        guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        var before = List.of(member(0, TOOL, 100), member(27, TOOL, 900));
        var ticket = guard.beforeOwnedOpen(before).orElseThrow();
        assertTrue(guard.refreshFromReceipt(ticket, List.of(member(27, TOOL, 901), member(0, TOOL, 101))));
        assertEquals(660, tool.allowance());
        assertFalse(guard.refreshFromReceipt(ticket, before), "A successful receipt ticket cannot replay");
        assertEquals(660, tool.allowance());
    }

    @Test
    void pendingChargeSurvivesLostOwnershipAndPreventsAnotherStartRepairOrRefresh() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(10)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(10)).orElseThrow();
        var charge = attempt.charge();
        assertFalse(guard.owns(attempt, 3, TOOL, wear(10)), "Losing a slot-bound lease is not charge settlement");
        assertEquals("WAIT_CHARGE", guard.diagnostic(TOOL, wear(10)).reason());
        assertFalse(tool.canStart(wear(10)));
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tool.prepare(wear(10), true));
        assertTrue(guard.begin(3, tool, TOOL, wear(10)).isEmpty());
        assertTrue(guard.beforeOwnedOpen(List.of(member(3, TOOL, 10))).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tool.awaitRepair("cannot-replace-charge"));
        assertFalse(guard().settleCharge(charge, MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertTrue(guard.settleCharge(charge, MossMiningToolGuard.ChargeOutcome.UNKNOWN));
        assertFalse(guard.settleCharge(charge, MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertTrue(tool.refreshDisabled());
        assertEquals(1550, tool.allowance());
    }

    @Test
    void unresolvedChargeOrRepairOnAnotherToolBlocksTheWholeInventoryRefresh() {
        var guard = guard();
        var clean = guard.track(TOOL, wear(100)).orElseThrow();
        var other = guard.track("other", wear(1496)).orElseThrow();
        var cohort = List.of(member(0, TOOL, 100), member(3, "other", 1496));
        var attempt = guard.begin(3, other, "other", wear(1496)).orElseThrow();
        assertTrue(guard.beforeOwnedOpen(cohort).isEmpty());
        guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        other.awaitRepair("other-repair");
        assertTrue(guard.beforeOwnedOpen(cohort).isEmpty());
        assertEquals(1461, clean.allowance());
        assertTrue(other.acceptRepair("other-repair", wear(0)));
        assertEquals(64, other.allowance());
        refresh(guard, List.of(member(0, TOOL, 100), member(3, "other", 0)));
        assertEquals(1561, other.allowance());
    }

    @Test
    void anInterveningStartInvalidatesTheOpenEvenAfterItsChargeIsConfirmed() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(100)).orElseThrow();
        var cohort = List.of(member(0, TOOL, 100));
        var ticket = guard.beforeOwnedOpen(cohort).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(100)).orElseThrow();
        assertFalse(guard.refreshFromReceipt(ticket, cohort));
        guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        assertFalse(guard.refreshFromReceipt(ticket, cohort));
        assertEquals(1460, tool.allowance());
        refresh(guard, cohort);
        assertEquals(1461, tool.allowance());
    }

    @Test
    void ticketsCannotBeSupersededReplayedOrTransferredBetweenGuards() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(100)).orElseThrow();
        var cohort = new ArrayList<>(List.of(member(0, TOOL, 100)));
        var old = guard.beforeOwnedOpen(cohort).orElseThrow();
        var current = guard.beforeOwnedOpen(cohort).orElseThrow();
        cohort.clear();
        assertFalse(guard.refreshFromReceipt(old, List.of(member(0, TOOL, 0))));
        var foreign = guard();
        foreign.track(TOOL, wear(100)).orElseThrow();
        var foreignTicket = foreign.beforeOwnedOpen(List.of(member(0, TOOL, 100))).orElseThrow();
        assertFalse(guard.refreshFromReceipt(foreignTicket, List.of(member(0, TOOL, 100))));
        assertTrue(guard.refreshFromReceipt(current, List.of(member(0, TOOL, 101))));
        assertEquals(1460, tool.allowance(), "Ticket retains a copy of the cohort list");
        assertFalse(guard.refreshFromReceipt(current, List.of(member(0, TOOL, 0))));
        assertEquals(1460, tool.allowance());
    }

    @Test
    void missingReplacedMovedOrIncompatibleCohortConsumesTicketWithoutChangingAllowance() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(100)).orElseThrow();
        var original = List.of(member(0, TOOL, 100), member(3, TOOL, 200));
        List<List<MossMiningToolGuard.CohortMember<String>>> invalid = List.of(
                List.of(member(0, TOOL, 0)),
                List.of(member(0, TOOL, 0), member(3, "changed-components", 0)),
                List.of(member(0, TOOL, 0), member(4, TOOL, 0)),
                List.of(member(0, TOOL, 0), member(0, TOOL, 0)),
                List.of(member(0, TOOL, 0), new MossMiningToolGuard.CohortMember<>(3, TOOL,
                        new MossMiningToolGuard.Durability(0, 2000, false))),
                List.of(member(0, TOOL, 0), new MossMiningToolGuard.CohortMember<>(3, TOOL,
                        new MossMiningToolGuard.Durability(0, 1561, true))));
        for (var wrong : invalid) {
            var ticket = guard.beforeOwnedOpen(original).orElseThrow();
            assertFalse(guard.refreshFromReceipt(ticket, wrong));
            assertEquals(1461, tool.allowance());
            assertFalse(guard.refreshFromReceipt(ticket, original), "A rejected matching ticket is consumed too");
        }
    }

    @Test
    void cohortRefreshNeverAdmitsIdentitiesAndRejectsUnboundedOrMalformedInput() {
        var guard = guard();
        assertTrue(guard.beforeOwnedOpen(List.of(member(0, TOOL, 0))).isEmpty());
        guard.track(TOOL, wear(100)).orElseThrow();
        var cohort = new ArrayList<MossMiningToolGuard.CohortMember<String>>();
        cohort.add(member(0, TOOL, 100));
        for (int slot = 1; slot < 36; slot++) { cohort.add(member(slot, "untracked-" + slot, 0)); }
        refresh(guard, cohort);
        assertEquals(1, guard.trackedIdentityCount());
        cohort.add(member(0, TOOL, 100));
        assertTrue(guard.beforeOwnedOpen(cohort).isEmpty());
        assertTrue(guard.beforeOwnedOpen(null).isEmpty());
        assertTrue(guard.beforeOwnedOpen(List.of()).isEmpty());
        assertTrue(guard.beforeOwnedOpen(List.of(member(0, TOOL, 0), member(0, TOOL, 0))).isEmpty());
        assertTrue(guard.beforeOwnedOpen(java.util.Arrays.asList(member(0, TOOL, 0), null)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> member(-1, TOOL, 0));
        assertThrows(IllegalArgumentException.class, () -> member(36, TOOL, 0));
    }

    @Test
    void unknownAttemptCannotBeRefundedByLaterSuccessRepairOrCleanCohort() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1495)).orElseThrow();
        var unknown = guard.begin(0, tool, TOOL, wear(1495)).orElseThrow();
        guard.settleCharge(unknown.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN);
        var confirmed = guard.begin(0, tool, TOOL, wear(1495)).orElseThrow();
        guard.settleCharge(confirmed.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        assertEquals(64, tool.allowance());
        tool.awaitRepair("repair-after-unknown");
        assertTrue(tool.acceptRepair("repair-after-unknown", wear(0)));
        assertTrue(tool.refreshDisabled());
        assertTrue(guard.beforeOwnedOpen(List.of(member(0, TOOL, 0))).isEmpty());
        var clean = guard.track("clean", wear(1496)).orElseThrow();
        var cleanAttempt = guard.begin(3, clean, "clean", wear(1496)).orElseThrow();
        guard.settleCharge(cleanAttempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        refresh(guard, List.of(member(0, TOOL, 0), member(3, "clean", 100)));
        assertEquals(1461, clean.allowance());
        assertEquals(64, tool.allowance(), "Refreshing another clean identity must not erase unknown debt");
        assertTrue(guard.begin(0, tool, TOOL, wear(0)).isEmpty());
    }

    @Test
    void repairLifecycleAndNewIdentityAdmissionInvalidateExistingOpenTickets() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1500)).orElseThrow();
        var cohort = List.of(member(0, TOOL, 1500));
        var beforeRepair = guard.beforeOwnedOpen(cohort).orElseThrow();
        tool.awaitRepair("repair-after-open");
        assertTrue(tool.acceptRepair("repair-after-open", wear(0)));
        assertFalse(guard.refreshFromReceipt(beforeRepair, List.of(member(0, TOOL, 0))));
        assertEquals(61, tool.allowance());
        var beforeAdmission = guard.beforeOwnedOpen(List.of(member(0, TOOL, 0))).orElseThrow();
        guard.track("new-components", wear(0)).orElseThrow();
        assertFalse(guard.refreshFromReceipt(beforeAdmission, List.of(member(0, TOOL, 0))));
        assertEquals(61, tool.allowance());
    }

    @Test
    void custodyInvalidationPermanentlyDisablesAllOptionalStartsIncludingRaisedAllowances() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(1496)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(1496)).orElseThrow();
        guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED);
        refresh(guard, List.of(member(0, TOOL, 100)));
        var ticket = guard.beforeOwnedOpen(List.of(member(0, TOOL, 100))).orElseThrow();
        guard.invalidateCustody();
        guard.invalidateCustody();
        assertTrue(guard.custodyInvalid());
        assertFalse(tool.canStart(wear(100)));
        assertEquals(MossMiningToolGuard.Preparation.USE_PLAIN_HAND, tool.prepare(wear(100), true));
        assertTrue(guard.begin(0, tool, TOOL, wear(100)).isEmpty());
        assertTrue(guard.track("replacement", wear(0)).isEmpty());
        assertSame(tool, guard.track(TOOL, wear(0)).orElseThrow());
        assertFalse(guard.refreshFromReceipt(ticket, List.of(member(0, TOOL, 0))));
        assertTrue(guard.beforeOwnedOpen(List.of(member(0, TOOL, 0))).isEmpty());
        tool.awaitRepair("durable-repair-still-resolves");
        assertTrue(tool.acceptRepair("durable-repair-still-resolves", wear(0)));
        assertEquals(1461, tool.allowance());
        assertFalse(tool.canStart(wear(0)));
        assertEquals("CUSTODY_INVALIDATED", guard.diagnostic(TOOL, wear(0)).reason());
        assertEquals("CUSTODY_INVALIDATED", guard.diagnostic("new", wear(0)).reason());
    }

    @Test
    void custodyLossRejectsTheActiveLeaseButStillAllowsItsChargeToSettleExactlyOnce() {
        var guard = guard();
        var tool = guard.track(TOOL, wear(100)).orElseThrow();
        var attempt = guard.begin(0, tool, TOOL, wear(100)).orElseThrow();
        guard.invalidateCustody();
        assertFalse(guard.owns(attempt, 0, TOOL, wear(100)));
        assertTrue(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.UNKNOWN));
        assertFalse(guard.settleCharge(attempt.charge(), MossMiningToolGuard.ChargeOutcome.CONFIRMED));
        assertTrue(tool.refreshDisabled());
        assertFalse(tool.canStart(wear(0)));
        assertEquals(1460, tool.allowance());
    }

    @Test
    void failedComparisonConsumesTicketWithoutPartlyRefreshingAnyTool() {
        boolean[] fail = {false};
        var guard = new MossMiningToolGuard<String>((left, right) -> {
            if (fail[0] && left.equals("second") && right.equals(TOOL)) {
                throw new IllegalStateException("comparison unavailable");
            }
            return left.equals(right);
        });
        var first = guard.track(TOOL, wear(1496)).orElseThrow();
        var second = guard.track("second", wear(1496)).orElseThrow();
        var cohort = List.of(member(0, TOOL, 0), member(3, "second", 0));
        var ticket = guard.beforeOwnedOpen(cohort).orElseThrow();
        fail[0] = true;
        assertThrows(IllegalStateException.class, () -> guard.refreshFromReceipt(ticket, cohort));
        assertEquals(65, first.allowance());
        assertEquals(65, second.allowance());
        fail[0] = false;
        assertFalse(guard.refreshFromReceipt(ticket, cohort));
    }

    private static MossMiningToolGuard<String> guard() { return new MossMiningToolGuard<>(String::equals); }
    private static MossMiningToolGuard.CohortMember<String> member(int slot, String identity, int damage) {
        return new MossMiningToolGuard.CohortMember<>(slot, identity, wear(damage));
    }
    private static void refresh(MossMiningToolGuard<String> guard,
                                List<MossMiningToolGuard.CohortMember<String>> cohort) {
        var ticket = guard.beforeOwnedOpen(cohort).orElseThrow();
        assertTrue(guard.refreshFromReceipt(ticket, cohort));
    }
    private static MossMiningToolGuard.Durability wear(int damage) {
        return new MossMiningToolGuard.Durability(damage, 1561, false);
    }
    private static HoeRepairSession.Tool damaged() {
        return new HoeRepairSession.Tool(0, "minecraft:diamond_hoe", "b".repeat(64), 1, 1500, 1561, false);
    }
    private static HoeRepairSession.Tool repaired(int slot) {
        return new HoeRepairSession.Tool(slot, "minecraft:diamond_hoe", "b".repeat(64), 1, 0, 1561, false);
    }
    private static HoeRepairSession startedRepair() throws IOException {
        var session = new HoeRepairSession(new HoeRepairSession.Store() {
            private Optional<HoeRepairSession.Journal> saved = Optional.empty();
            @Override public Optional<HoeRepairSession.Journal> load() { return saved; }
            @Override public void replace(Optional<HoeRepairSession.Journal> expected,
                                          HoeRepairSession.Journal replacement) {
                assertEquals(saved, expected);
                saved = Optional.of(replacement);
            }
        });
        session.begin(CONTEXT, damaged(), EPOCH, 10);
        return session;
    }
}
