package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MossToolReceiptHistoryTest {
    private static final String EPOCH = "7b5f4a18-a9c5-4bb6-a2ee-df5091b77eea";
    private static final String OTHER_EPOCH = "c091c657-a5ef-447d-a292-a4507d2e9c76";
    private final Object world = new Object();
    private final Object player = new Object();
    private final Object connection = new Object();

    @Test
    void laterReceiptReportsPriorStampAndDamageWithCurrentComparison() {
        var history = history();
        assertNull(observe(history, update(EPOCH, 4, 0, 10, 7)));
        var transition = observe(history, update(EPOCH, 9, 0, 11, 7));
        assertEquals(EPOCH, transition.epoch());
        assertEquals(4, transition.sequence());
        assertEquals(0, transition.slot());
        assertEquals(10, transition.damage());
        assertTrue(transition.otherComponentsEqual());
        var changed = observe(history, update(EPOCH, 12, 0, 12, 8));
        assertEquals(9, changed.sequence());
        assertEquals(11, changed.damage());
        assertFalse(changed.otherComponentsEqual());
    }

    @Test
    void repeatedLatestStampReturnsCachedTransitionWithoutReevaluatingOrReplacingReceipt() {
        AtomicInteger comparisons = new AtomicInteger();
        var history = new MossToolReceiptHistory<int[]>(int[]::clone,
                (before, after) -> { comparisons.incrementAndGet(); return before[1] == after[1]; }, value -> value[0]);
        assertNull(observe(history, update(EPOCH, 1, 0, 10, 7)));
        var transition = observe(history, update(EPOCH, 2, 0, 11, 7));
        assertSame(transition, observe(history, update(EPOCH, 2, 0, 999, 999)));
        assertEquals(1, comparisons.get());
        assertEquals(11, observe(history, update(EPOCH, 3, 0, 12, 7)).damage());
    }

    @Test
    void everyContextIdentityChangeAndNullContextBreaksTheComparisonChain() {
        Object[][] contexts = {
                {new Object(), player, connection}, {world, new Object(), connection},
                {world, player, new Object()}, {null, player, connection},
                {world, null, connection}, {world, player, null}
        };
        for (Object[] changed : contexts) {
            var history = history();
            assertNull(observe(history, update(EPOCH, 1, 0, 10, 7)));
            assertNull(history.observe(changed[0], changed[1], changed[2], update(EPOCH, 2, 0, 11, 7)));
            assertNull(observe(history, update(EPOCH, 3, 0, 12, 7)));
            assertEquals(3, observe(history, update(EPOCH, 4, 0, 13, 7)).sequence());
        }
    }

    @Test
    void epochChangesInvalidateEverySlotIncludingPreviouslyCachedTransitions() {
        var history = history();
        observe(history, update(EPOCH, 1, 0, 10, 7));
        observe(history, update(EPOCH, 2, 3, 20, 7));
        assertNotNull(observe(history, update(EPOCH, 3, 0, 11, 7)));
        assertNull(observe(history, update(OTHER_EPOCH, 1, 0, 12, 7)));
        assertNull(observe(history, update(OTHER_EPOCH, 2, 3, 21, 7)));
        var transition = observe(history, update(OTHER_EPOCH, 3, 0, 13, 7));
        assertEquals(OTHER_EPOCH, transition.epoch());
        assertEquals(1, transition.sequence());
    }

    @Test
    void staleOrReversedSequenceInvalidatesOnlyThatSlotsChain() {
        var history = history();
        observe(history, update(EPOCH, 10, 0, 10, 7));
        observe(history, update(EPOCH, 11, 3, 20, 7));
        assertNull(observe(history, update(EPOCH, 9, 0, 9, 7)));
        assertNull(observe(history, update(EPOCH, 9, 0, 9, 7)));
        assertNull(observe(history, update(EPOCH, 10, 0, 10, 7)));
        assertNull(observe(history, update(EPOCH, 12, 0, 12, 7)));
        assertEquals(11, observe(history, update(EPOCH, 13, 3, 21, 7)).sequence());
        assertEquals(12, observe(history, update(EPOCH, 14, 0, 13, 7)).sequence());
    }

    @Test
    void differentSlotsCanBeObservedOutOfGlobalSequenceOrderAndRemainBoundedToHotbar() {
        AtomicInteger copies = new AtomicInteger();
        var history = new MossToolReceiptHistory<int[]>(value -> { copies.incrementAndGet(); return value.clone(); },
                (before, after) -> before[1] == after[1], value -> value[0]);
        for (int slot = 8; slot >= 0; slot--) {
            assertNull(observe(history, update(EPOCH, slot + 1, slot, 10 + slot, 7)));
        }
        assertEquals(9, copies.get());
        for (int slot = 9; slot < 200; slot++) {
            assertNull(observe(history, update(OTHER_EPOCH, 1, slot, 100, 8)));
        }
        assertNull(observe(history, update(OTHER_EPOCH, 1, -1, 100, 8)));
        assertEquals(9, copies.get());
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(slot + 1, observe(history, update(EPOCH, 100 + slot, slot, 20 + slot, 7)).sequence());
        }
    }

    @Test
    void retainedReceiptIsIndependentOfInputAndComparatorMutations() {
        var history = new MossToolReceiptHistory<int[]>(int[]::clone, (before, after) -> {
            boolean equal = before[1] == after[1];
            before[0] = 900;
            after[0] = 901;
            return equal;
        }, value -> value[0]);
        int[] first = {10, 7};
        int[] second = {11, 7};
        observe(history, new PlayerInventoryUpdateLedger.Update<>(new PlayerInventoryUpdateLedger.Stamp(EPOCH, 1), 0, first));
        first[0] = 500;
        var transition = observe(history, new PlayerInventoryUpdateLedger.Update<>(new PlayerInventoryUpdateLedger.Stamp(EPOCH, 2), 0, second));
        assertEquals(10, transition.damage());
        assertEquals(11, second[0]);
        second[0] = 600;
        assertEquals(11, observe(history, update(EPOCH, 3, 0, 12, 7)).damage());
    }

    @Test
    void malformedSlotReceiptInvalidatesItsChainAndUnknownDamageRemainsNull() {
        var history = history();
        observe(history, update(EPOCH, 1, 0, 10, 7));
        assertNull(observe(history, update(EPOCH, 0, 0, 11, 7)));
        assertNull(observe(history, update(EPOCH, 2, 0, 12, 7)));
        assertNull(observe(history, new PlayerInventoryUpdateLedger.Update<>(null, 0, new int[]{13, 7})));
        assertNull(observe(history, update(EPOCH, 3, 0, 13, 7)));
        assertNull(observe(history, new PlayerInventoryUpdateLedger.Update<>(new PlayerInventoryUpdateLedger.Stamp(EPOCH, 4), 0, null)));
        assertNull(observe(history, update(EPOCH, 5, 0, 14, 7)));
        assertNull(observe(history, null));
        var unknown = new MossToolReceiptHistory<int[]>(int[]::clone, (before, after) -> true, value -> null);
        observe(unknown, update(EPOCH, 1, 0, 10, 7));
        assertNull(observe(unknown, update(EPOCH, 2, 0, 11, 7)).damage());
    }

    @Test
    void transitionContainsOnlyBoundedEpochAndValidatedPriorReceiptFacts() {
        assertThrows(IllegalArgumentException.class,
                () -> new MossToolReceiptHistory.Transition("private raw value", 1, 0, 10, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MossToolReceiptHistory.Transition(EPOCH, 0, 0, 10, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MossToolReceiptHistory.Transition(EPOCH, 1, 9, 10, true));
        assertThrows(IllegalArgumentException.class,
                () -> new MossToolReceiptHistory.Transition(EPOCH, 1, 0, -1, true));
        var history = history();
        observe(history, update(EPOCH, 1, 0, 10, 7));
        assertNull(observe(history, update("private raw value", 2, 0, 11, 7)));
        assertNull(observe(history, update(EPOCH, 3, 0, 12, 7)));
    }

    @Test
    void optionalCallbackFailureCannotLeaveAComparisonAcrossTheFailedReceipt() {
        var history = new MossToolReceiptHistory<int[]>(int[]::clone, (before, after) -> {
            if (after[1] == 99) { throw new IllegalStateException("callback failed"); }
            return before[1] == after[1];
        }, value -> value[0]);
        observe(history, update(EPOCH, 1, 0, 10, 7));
        assertThrows(IllegalStateException.class, () -> observe(history, update(EPOCH, 2, 0, 11, 99)));
        assertNull(observe(history, update(EPOCH, 3, 0, 12, 7)));
        assertEquals(3, observe(history, update(EPOCH, 4, 0, 13, 7)).sequence());
    }

    private MossToolReceiptHistory.Transition observe(MossToolReceiptHistory<int[]> history,
                                                     PlayerInventoryUpdateLedger.Update<int[]> update) {
        return history.observe(world, player, connection, update);
    }

    private static MossToolReceiptHistory<int[]> history() {
        return new MossToolReceiptHistory<>(int[]::clone, (before, after) -> before[1] == after[1], value -> value[0]);
    }

    private static PlayerInventoryUpdateLedger.Update<int[]> update(String epoch, long sequence, int slot,
                                                                    int damage, int otherComponents) {
        return new PlayerInventoryUpdateLedger.Update<>(new PlayerInventoryUpdateLedger.Stamp(epoch, sequence),
                slot, new int[]{damage, otherComponents});
    }
}
