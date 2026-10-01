package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** A completed allocation never permits closing a chest with an unsettled cursor. */
final class DepotTransferPolicy {
    enum Step { SETTLE_PENDING, RETURN_CURSOR, DEPOSIT_ONE, REJECT_CURSOR, COMPLETE, NEXT_SOURCE }

    private DepotTransferPolicy() { }

    static Step next(boolean pendingAction, boolean allocationComplete, boolean knownSource,
                     boolean cursorEmpty, long remainingHeldMaterial) {
        if (pendingAction) { return Step.SETTLE_PENDING; }
        if (knownSource) {
            if (cursorEmpty || remainingHeldMaterial < 0) { return Step.REJECT_CURSOR; }
            return remainingHeldMaterial == 0 ? Step.RETURN_CURSOR : Step.DEPOSIT_ONE;
        }
        if (!cursorEmpty) { return Step.REJECT_CURSOR; }
        return allocationComplete ? Step.COMPLETE : Step.NEXT_SOURCE;
    }

    record SingleDeposit(long playerCountBefore, int cursorCountExpected) { }

    /** Vanilla may mutate the same cursor stack synchronously inside the click. */
    static SingleDeposit issueSingleDeposit(long playerCountBefore, int cursorCountBefore, Runnable click) {
        Objects.requireNonNull(click, "click");
        if (playerCountBefore < 0 || cursorCountBefore < 1) {
            throw new IllegalArgumentException("Single deposit needs a nonempty cursor and known inventory count");
        }
        SingleDeposit observed = new SingleDeposit(playerCountBefore, cursorCountBefore - 1);
        click.run();
        return observed;
    }
}
