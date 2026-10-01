package io.github.schematicsupervisor.fabric;

final class ConfirmedBlockProgressGuard {
    private final int maximumTicksWithoutConfirmation;
    private int ticksWithoutConfirmation;
    private boolean expired;

    ConfirmedBlockProgressGuard(int maximumTicksWithoutConfirmation) {
        if (maximumTicksWithoutConfirmation < 1) {
            throw new IllegalArgumentException(
                    "maximumTicksWithoutConfirmation must be positive"
            );
        }
        this.maximumTicksWithoutConfirmation = maximumTicksWithoutConfirmation;
    }

    void beginOrder() {
        ticksWithoutConfirmation = 0;
        expired = false;
    }

    boolean tick(boolean confirmedBlockProgress) {
        if (confirmedBlockProgress) {
            ticksWithoutConfirmation = 0;
            expired = false;
            return false;
        }
        if (expired) {
            return true;
        }
        ticksWithoutConfirmation = Math.incrementExact(ticksWithoutConfirmation);
        expired = ticksWithoutConfirmation >= maximumTicksWithoutConfirmation;
        return expired;
    }

    int ticksWithoutConfirmation() {
        return ticksWithoutConfirmation;
    }
}
