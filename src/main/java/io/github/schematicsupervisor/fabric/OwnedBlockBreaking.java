package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** Owns only suppression of vanilla's idle cancellation; it never sends input or packets. */
public final class OwnedBlockBreaking {
    private static Lease active;

    private OwnedBlockBreaking() { }

    public static synchronized boolean isOwned(Object interactionManager) {
        return active != null && active.manager == interactionManager;
    }

    static synchronized Lease acquire(Object interactionManager) {
        Objects.requireNonNull(interactionManager, "interactionManager");
        if (active != null) { throw new IllegalStateException("A block-breaking lease is already active"); }
        active = new Lease(interactionManager);
        return active;
    }

    static final class Lease implements AutoCloseable {
        private final Object manager;

        private Lease(Object manager) { this.manager = manager; }

        @Override public void close() {
            synchronized (OwnedBlockBreaking.class) {
                if (active == this) { active = null; }
            }
        }
    }
}
