package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** A stale full packet may request another read, never inventory input or discard credit. */
final class StaleInventoryReceiptRefresh {
    private ServerInventorySnapshotStamp stalePacket;
    private int staleTicks;
    private int refreshes;

    boolean observe(ServerInventorySnapshotStamp packet) {
        Objects.requireNonNull(packet);
        if (!packet.equals(stalePacket)) { stalePacket = packet; staleTicks = 0; }
        if (++staleTicks < 20 || refreshes >= 2) { return false; }
        refreshes++;
        stalePacket = null; staleTicks = 0;
        return true;
    }
}
