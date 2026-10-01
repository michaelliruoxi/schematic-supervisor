package io.github.schematicsupervisor.fabric;

/** Bounds an automatic refill without changing the fixed, acknowledged shop route. */
final class DirtPurchaseBudget {
    private DirtPurchaseBudget() { }

    static int maximumStacks(long planned, long consumed, long inMainInventory, long requiredAvailable) {
        if (planned < 0 || consumed < 0 || inMainInventory < 0 || requiredAvailable < 0) {
            throw new IllegalArgumentException("Dirt purchase quantities must not be negative");
        }
        // The current request can include temporary supports that are not permanent plan consumption.
        long target = Math.max(Math.max(0, planned - consumed), requiredAvailable);
        long needed = Math.max(0, target - inMainInventory);
        long stacks = needed / 64 + (needed % 64 == 0 ? 0 : 1);
        return (int) Math.min(36, stacks);
    }
}
