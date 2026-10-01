package io.github.schematicsupervisor.fabric;

/** A click interval elapses during navigation and acknowledgement, not after them. */
final class InteractionDispatchCadence {
    private final int interval;
    private int remaining;

    InteractionDispatchCadence(int interval) {
        if (interval < 1) { throw new IllegalArgumentException("interaction interval must be positive"); }
        this.interval = interval;
    }

    void tick() { if (remaining > 0) { remaining--; } }

    boolean ready() { return remaining == 0; }

    void dispatched() {
        if (!ready()) { throw new IllegalStateException("interaction interval has not elapsed"); }
        remaining = interval;
    }
}
