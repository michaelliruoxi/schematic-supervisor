package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class HoeRepairSessionTest {
    private static final RunContext CONTEXT = new RunContext("sha256:" + "a".repeat(64), "minecraft:overworld");
    private static final String EPOCH = UUID.randomUUID().toString();
    private static final HoeRepairSession.Tool BEFORE = tool(0, 1500, "b".repeat(64));
    private static final HoeRepairSession.Tool FIXED = tool(0, 0, "b".repeat(64));

    @Test void repairsAtReserveAndBeforeFinalUseWithoutDemandingAnotherHoe() {
        assertFalse(tool(0, 1496, "b".repeat(64)).needsRepair());
        assertTrue(tool(0, 1497, "b".repeat(64)).needsRepair());
        assertTrue(tool(0, 1560, "b".repeat(64)).needsRepair());
        assertFalse(tool(0, 1561, "b".repeat(64)).needsRepair());
        assertFalse(FIXED.needsRepair());
        assertFalse(new HoeRepairSession.Tool(0, "minecraft:diamond_hoe", "b".repeat(64), 1, 1500, 1561, true).needsRepair());
    }

    @Test void shortLivedToolsDoNotRepairAfterEverySingleUse() {
        assertFalse(new HoeRepairSession.Tool(0, "minecraft:golden_hoe", "b".repeat(64), 1, 1, 32, false).needsRepair());
        assertFalse(new HoeRepairSession.Tool(0, "minecraft:golden_hoe", "b".repeat(64), 1, 28, 32, false).needsRepair());
        assertTrue(new HoeRepairSession.Tool(0, "minecraft:golden_hoe", "b".repeat(64), 1, 29, 32, false).needsRepair());
        assertTrue(new HoeRepairSession.Tool(0, "minecraft:wooden_hoe", "b".repeat(64), 1, 54, 59, false).needsRepair());
    }

    @Test void localPredictedDamageResetCannotReleaseFollowingTill() throws IOException {
        var session = started(new MemoryStore());
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, FIXED, null, true));
        assertTrue(session.pending(), "No following till is eligible while the exact repair remains pending");
    }

    @Test void staleAppliedPacketCannotReleaseFollowingTill() throws IOException {
        var session = started(new MemoryStore());
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, FIXED, receipt(10, FIXED), true));
        assertTrue(session.pending());
    }

    @Test void sameSlotLaterPacketAndMatchingCurrentToolConfirmExactlyOneRepair() throws IOException {
        var store = new MemoryStore();
        var session = started(store);
        assertThrows(IllegalStateException.class, () -> session.begin(CONTEXT, BEFORE, EPOCH, 11));
        assertEquals(1, store.writes);
        assertEquals(HoeRepairSession.Status.READY, session.observe(CONTEXT, FIXED, receipt(11, FIXED), true));
        assertFalse(session.pending());
        assertTrue(store.saved.orElseThrow().confirmed());
        assertEquals(2, store.writes);
        assertEquals(HoeRepairSession.Status.READY, session.observe(CONTEXT, FIXED, receipt(11, FIXED), true));
        assertEquals(2, store.writes, "A receipt is persisted once");
    }

    @Test void partialRepairDoesNotPretendFullFixAcknowledged() throws IOException {
        var session = started(new MemoryStore());
        var partial = tool(0, 10, "b".repeat(64));
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, partial, receipt(11, partial), true));
    }

    @Test void packetForDifferentSlotOrToolCannotConfirm() throws IOException {
        var session = started(new MemoryStore());
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, FIXED, receipt(11, tool(1, 0, "b".repeat(64))), true));
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, FIXED, receipt(12, tool(0, 0, "c".repeat(64))), true));
        assertTrue(session.pending());
    }

    @Test void changedCurrentComponentsOrAbsentToolBlockEvenWithOldValidPacket() throws IOException {
        var session = started(new MemoryStore());
        assertEquals(HoeRepairSession.Status.BLOCKED, session.observe(CONTEXT, tool(0, 0, "c".repeat(64)), receipt(11, FIXED), true));
        assertTrue(session.pending());
        assertEquals(HoeRepairSession.Status.BLOCKED, session.observe(CONTEXT, null, receipt(12, FIXED), true));
    }

    @Test void contextChangeCannotConfirmOrPermitRepeat() throws IOException {
        var session = started(new MemoryStore());
        var other = new RunContext("sha256:" + "d".repeat(64), "minecraft:overworld");
        assertEquals(HoeRepairSession.Status.BLOCKED, session.observe(other, FIXED, receipt(11, FIXED), true));
        assertThrows(IllegalStateException.class, () -> session.begin(other, BEFORE, EPOCH, 12));
    }

    @Test void deadlineBlocksOnceAndRetainsPendingAcrossReconstruction() throws IOException {
        var store = new MemoryStore();
        var session = started(store);
        for (int tick = 0; tick < 99; tick++) {
            assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, BEFORE, null, true));
        }
        assertEquals(HoeRepairSession.Status.BLOCKED, session.observe(CONTEXT, BEFORE, null, true));
        assertTrue(session.detail().contains("will not repeat"));
        var restarted = new HoeRepairSession(store);
        assertTrue(restarted.pending());
        assertThrows(IllegalStateException.class, () -> restarted.begin(CONTEXT, BEFORE, EPOCH, 20));
        assertEquals(1, store.writes);
    }

    @Test void cancellationPreventsRepeatButAllowsOnlyPassiveReceiptSettlement() throws IOException {
        var session = started(new MemoryStore());
        session.cancel();
        assertEquals(HoeRepairSession.Status.BLOCKED, session.observe(CONTEXT, BEFORE, null, false));
        assertThrows(IllegalStateException.class, () -> session.begin(CONTEXT, BEFORE, EPOCH, 12));
        assertEquals(HoeRepairSession.Status.READY, session.observe(CONTEXT, FIXED, receipt(12, FIXED), false));
        assertFalse(session.pending());
    }

    @Test void passiveChecksDoNotConsumeTheActiveWaitDeadline() throws IOException {
        var session = started(new MemoryStore());
        for (int check = 0; check < 1000; check++) {
            assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, BEFORE, null, false));
        }
        assertEquals(HoeRepairSession.Status.WAITING, session.observe(CONTEXT, BEFORE, null, true));
    }

    @Test void newProcessRequiresItsActualServerReceiptAndNeverResendsPendingIntent() throws IOException {
        var store = new MemoryStore();
        started(store);
        var restarted = new HoeRepairSession(store);
        assertEquals(HoeRepairSession.Status.WAITING, restarted.observe(CONTEXT, FIXED, null, false));
        var nextEpoch = new HoeRepairSession.Receipt(UUID.randomUUID().toString(), 1, FIXED);
        assertEquals(HoeRepairSession.Status.READY, restarted.observe(CONTEXT, FIXED, nextEpoch, false));
        assertEquals(2, store.writes);
    }

    @Test void durableWriteFailureNeverPermitsAnotherBegin() throws IOException {
        var store = new MemoryStore();
        var session = new HoeRepairSession(store);
        store.fail = true;
        assertThrows(IOException.class, () -> session.begin(CONTEXT, BEFORE, EPOCH, 10));
        store.fail = false;
        assertThrows(IllegalStateException.class, () -> session.begin(CONTEXT, BEFORE, EPOCH, 11));
    }

    @Test void receiptWriteFailureRetainsUncertainPendingOperation() throws IOException {
        var store = new MemoryStore();
        var session = started(store);
        store.fail = true;
        assertThrows(IOException.class, () -> session.observe(CONTEXT, FIXED, receipt(11, FIXED), true));
        assertTrue(session.pending());
        assertThrows(IllegalStateException.class, () -> session.begin(CONTEXT, BEFORE, EPOCH, 12));
    }

    private static HoeRepairSession started(MemoryStore store) throws IOException {
        var session = new HoeRepairSession(store);
        session.begin(CONTEXT, BEFORE, EPOCH, 10);
        return session;
    }
    private static HoeRepairSession.Receipt receipt(long sequence, HoeRepairSession.Tool tool) {
        return new HoeRepairSession.Receipt(EPOCH, sequence, tool);
    }
    private static HoeRepairSession.Tool tool(int slot, int damage, String identity) {
        return new HoeRepairSession.Tool(slot, "minecraft:diamond_hoe", identity, 1, damage, 1561, false);
    }
    private static final class MemoryStore implements HoeRepairSession.Store {
        private Optional<HoeRepairSession.Journal> saved = Optional.empty();
        private int writes;
        private boolean fail;
        @Override public Optional<HoeRepairSession.Journal> load() { return saved; }
        @Override public void replace(Optional<HoeRepairSession.Journal> expected, HoeRepairSession.Journal next) throws IOException {
            if (fail || !saved.equals(expected)) { throw new IOException("write failed"); }
            saved = Optional.of(next);
            writes++;
        }
    }
}
