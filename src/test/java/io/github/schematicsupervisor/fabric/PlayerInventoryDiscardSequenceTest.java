package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.PlayerInventoryDiscardSequence.Action.*;
import static io.github.schematicsupervisor.fabric.PlayerInventoryDiscardSequence.View.*;
import org.junit.jupiter.api.Test;

class PlayerInventoryDiscardSequenceTest {
    @Test void shopMustCloseAndPlayerInventoryMustSettleBeforeOneDiscard() {
        var sequence = new PlayerInventoryDiscardSequence();
        for (int tick = 1; tick < 10; tick++) { assertEquals(WAIT, sequence.tick(CLOSED, false)); }
        assertEquals(OPEN_INVENTORY, sequence.tick(CLOSED, false));
        for (int tick = 1; tick < 10; tick++) { assertEquals(WAIT, sequence.tick(OWNED_INVENTORY, false)); }
        assertEquals(DISCARD_ONCE, sequence.tick(OWNED_INVENTORY, false));
        for (int tick = 0; tick < 80; tick++) { assertEquals(WAIT, sequence.tick(OWNED_INVENTORY, false)); }
        sequence.dispatched();
        for (int tick = 1; tick < 40; tick++) { assertEquals(WAIT, sequence.tick(OWNED_INVENTORY, false)); }
        assertEquals(OPEN_RECEIPT, sequence.tick(OWNED_INVENTORY, false));
        assertEquals(WAIT, sequence.tick(CLOSED, false));
        assertThrows(IllegalStateException.class, sequence::dispatched);
    }

    @Test void appliedSlotReceiptStillCannotOpenShopInTheDiscardTick() {
        var sequence = ready(); sequence.dispatched();
        for (int tick = 1; tick < 4; tick++) { assertEquals(WAIT, sequence.tick(OWNED_INVENTORY, true)); }
        assertEquals(OPEN_RECEIPT, sequence.tick(OWNED_INVENTORY, true));
    }

    @Test void foreignShopOrLostPlayerInventoryBlocksBeforeAndAfterDispatch() {
        var closed = new PlayerInventoryDiscardSequence();
        assertEquals(BLOCKED, closed.tick(FOREIGN, false));
        assertEquals(BLOCKED, closed.tick(CLOSED, false));
        assertThrows(IllegalStateException.class, closed::dispatched);
        for (var wrong : new PlayerInventoryDiscardSequence.View[]{CLOSED, FOREIGN}) {
            var before = ready();
            assertEquals(BLOCKED, before.tick(wrong, false));
            assertThrows(IllegalStateException.class, before::dispatched);
            var after = ready(); after.dispatched();
            assertEquals(BLOCKED, after.tick(wrong, true));
            assertEquals(BLOCKED, after.tick(OWNED_INVENTORY, true));
        }
    }

    @Test void shopCannotSubstituteForTheRequestedInventoryScreen() {
        var sequence = new PlayerInventoryDiscardSequence();
        for (int tick = 0; tick < 10; tick++) { sequence.tick(CLOSED, false); }
        assertEquals(BLOCKED, sequence.tick(FOREIGN, false));
        assertThrows(IllegalStateException.class, sequence::dispatched);
    }

    private static PlayerInventoryDiscardSequence ready() {
        var sequence = new PlayerInventoryDiscardSequence();
        for (int tick = 0; tick < 10; tick++) { sequence.tick(CLOSED, false); }
        for (int tick = 0; tick < 9; tick++) { sequence.tick(OWNED_INVENTORY, false); }
        assertEquals(DISCARD_ONCE, sequence.tick(OWNED_INVENTORY, false));
        return sequence;
    }
}
