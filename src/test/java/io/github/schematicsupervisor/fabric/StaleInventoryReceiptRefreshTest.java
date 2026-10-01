package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.schematicsupervisor.fabric.MossDepositControllerTest.*;
import org.junit.jupiter.api.Test;

class StaleInventoryReceiptRefreshTest {
    private static ServerInventorySnapshotStamp packet(int opening, int sequence) {
        return new ServerInventorySnapshotStamp(EPOCH, 1, opening, sequence, opening, 0);
    }

    @Test void sustainedSlotDriftAllowsTwoReadRefreshesThenStops() {
        var refresh = new StaleInventoryReceiptRefresh();
        for (int opening = 1; opening <= 2; opening++) {
            var packet = packet(opening, opening);
            for (int tick = 0; tick < 19; tick++) { assertFalse(refresh.observe(packet)); }
            assertTrue(refresh.observe(packet));
        }
        for (int tick = 0; tick < 200; tick++) { assertFalse(refresh.observe(packet(3, 3))); }
    }

    @Test void newFullPacketMustHaveItsOwnStaleDwell() {
        var refresh = new StaleInventoryReceiptRefresh();
        for (int tick = 0; tick < 19; tick++) { assertFalse(refresh.observe(packet(1, 1))); }
        for (int tick = 0; tick < 19; tick++) { assertFalse(refresh.observe(packet(1, 2))); }
        assertTrue(refresh.observe(packet(1, 2)));
    }
}
