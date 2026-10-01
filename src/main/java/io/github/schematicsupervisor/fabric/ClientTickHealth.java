package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorPorts;

final class ClientTickHealth implements SupervisorPorts.ServerHealth {
    private static final long LAG_TICK_NANOS = 500_000_000L;
    private long previousTickNanos;
    private int recentLagTicks;

    void tick() {
        long now = System.nanoTime();
        if (previousTickNanos != 0L) {
            if (now - previousTickNanos >= LAG_TICK_NANOS) {
                recentLagTicks = Math.min(10, recentLagTicks + 1);
            } else if (recentLagTicks > 0) {
                recentLagTicks--;
            }
        }
        previousTickNanos = now;
    }

    @Override
    public boolean appearsLaggy() {
        return recentLagTicks >= 2;
    }
}
