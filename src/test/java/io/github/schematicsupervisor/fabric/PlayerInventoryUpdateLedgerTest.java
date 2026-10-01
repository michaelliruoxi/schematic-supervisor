package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class PlayerInventoryUpdateLedgerTest {
    @Test void localMutationOrUnappliedPacketCannotBecomeAReceipt() {
        var ledger = new PlayerInventoryUpdateLedger<String>(value -> value, String::equals);
        Object world = new Object(), player = new Object(), connection = new Object();
        assertEquals(0, ledger.mark(world, player, connection).sequence());
        ledger.applied(world, player, connection, 0, "damage=0", "damage=1500");
        assertTrue(ledger.latest(world, player, connection, 0).isEmpty());
        assertEquals(0, ledger.mark(world, player, connection).sequence());
    }

    @Test void exactAppliedMainSlotPacketAdvancesItsSequence() {
        var ledger = new PlayerInventoryUpdateLedger<String>(value -> value, String::equals);
        Object world = new Object(), player = new Object(), connection = new Object();
        var before = ledger.mark(world, player, connection);
        ledger.applied(world, player, connection, 0, "damage=0", "damage=0");
        var after = ledger.latest(world, player, connection, 0).orElseThrow();
        assertEquals(before.epoch(), after.stamp().epoch());
        assertTrue(after.stamp().sequence() > before.sequence());
        assertTrue(ledger.latest(world, player, connection, 1).isEmpty());
    }

    @Test void eachLiveContextIdentityChangeDropsPriorReceipts() {
        Object world = new Object(), player = new Object(), connection = new Object();
        var ledger = new PlayerInventoryUpdateLedger<String>(value -> value, String::equals);
        ledger.applied(world, player, connection, 0, "fixed", "fixed");
        assertTrue(ledger.latest(new Object(), player, connection, 0).isEmpty());
        ledger.applied(world, player, connection, 0, "fixed", "fixed");
        assertTrue(ledger.latest(world, new Object(), connection, 0).isEmpty());
        ledger.applied(world, player, connection, 0, "fixed", "fixed");
        assertTrue(ledger.latest(world, player, new Object(), 0).isEmpty());
        assertTrue(ledger.mark(world, player, connection).sequence() >= 3);
    }

    @Test void capturesAndReturnedReceiptsAreDefensiveCopies() {
        var ledger = new PlayerInventoryUpdateLedger<int[]>(int[]::clone, Arrays::equals);
        Object world = new Object(), player = new Object(), connection = new Object();
        int[] packet = {0};
        ledger.applied(world, player, connection, 0, packet, new int[]{0});
        packet[0] = 42;
        var first = ledger.latest(world, player, connection, 0).orElseThrow();
        assertEquals(0, first.stack()[0]);
        first.stack()[0] = 99;
        assertEquals(0, ledger.latest(world, player, connection, 0).orElseThrow().stack()[0]);
    }

    @Test void missingContextsAndNonMainSlotsAreRejected() {
        var ledger = new PlayerInventoryUpdateLedger<String>(value -> value, String::equals);
        Object world = new Object(), player = new Object(), connection = new Object();
        ledger.applied(null, player, connection, 0, "fixed", "fixed");
        ledger.applied(world, player, connection, -1, "fixed", "fixed");
        ledger.applied(world, player, connection, 36, "fixed", "fixed");
        assertEquals(0, ledger.mark(world, player, connection).sequence());
    }
}
