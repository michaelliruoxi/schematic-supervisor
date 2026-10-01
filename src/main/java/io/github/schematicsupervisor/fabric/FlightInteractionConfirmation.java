package io.github.schematicsupervisor.fabric;

/** A single accepted click cannot report success while vanilla still holds its prediction. */
final class FlightInteractionConfirmation {
    enum Result { WAITING, CONFIRMED, RETRYABLE, UNCERTAIN }

    private final long inventoryBefore;
    private final boolean consumesItem;
    private final boolean creative;
    private final int confirmationTicks;
    private final int miningTicks;
    private int miningFinishedAt = -1;
    private int ticks;
    private Result result = Result.WAITING;
    private long consumed;

    FlightInteractionConfirmation(long inventoryBefore, boolean consumesItem,
            boolean creative, int limit) {
        this(inventoryBefore, consumesItem, creative, 0, limit);
    }

    private FlightInteractionConfirmation(long inventoryBefore, boolean consumesItem,
            boolean creative, int miningTicks, int confirmationTicks) {
        if (inventoryBefore < 0 || miningTicks < 0 || confirmationTicks < 1
                || (long) miningTicks + confirmationTicks > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid interaction confirmation bounds");
        }
        this.inventoryBefore = inventoryBefore;
        this.consumesItem = consumesItem;
        this.creative = creative;
        this.miningTicks = miningTicks;
        this.confirmationTicks = confirmationTicks;
    }

    static FlightInteractionConfirmation forSupportRemoval(long inventoryBefore, boolean creative,
            int miningTicks, int confirmationTicks) {
        if (miningTicks < 1) { throw new IllegalArgumentException("support mining must be bounded"); }
        return new FlightInteractionConfirmation(inventoryBefore, false, creative, miningTicks, confirmationTicks);
    }

    boolean miningBudgetExpired() { return miningTicks > 0 && ticks >= miningTicks; }

    /** Latch once: stopping input starts a full acknowledgement window without permitting another attack. */
    void finishMining() {
        if (miningTicks > 0 && miningFinishedAt < 0) {
            miningFinishedAt = Math.min(ticks, miningTicks);
        }
    }

    Result observe(boolean predictionPending, boolean correctWorldState, long inventoryNow) {
        return observe(predictionPending, correctWorldState, inventoryNow, true);
    }

    Result observe(boolean predictionPending, boolean correctWorldState, long inventoryNow,
            boolean advanceDeadline) {
        if (result != Result.WAITING) { return result; }
        if (inventoryNow < 0) { throw new IllegalArgumentException("negative inventory"); }
        if (advanceDeadline) { ticks++; }
        long spent = Math.max(0, inventoryBefore - inventoryNow);
        if (!predictionPending && correctWorldState
                && (!consumesItem || creative || spent == 1)) {
            consumed = consumesItem && !creative ? 1 : 0;
            result = Result.CONFIRMED;
        } else if (ticks >= budgetTicks()) {
            // An unacknowledged click or unexplained inventory change must never be retried.
            result = !predictionPending && !correctWorldState && spent == 0
                    ? Result.RETRYABLE : Result.UNCERTAIN;
        }
        return result;
    }

    long consumed() { return consumed; }
    long inventoryBefore() { return inventoryBefore; }
    int ageTicks() { return ticks; }
    int budgetTicks() {
        return confirmationTicks + (miningTicks == 0 ? 0
                : miningFinishedAt < 0 ? miningTicks : miningFinishedAt);
    }
    Result result() { return result; }
}
