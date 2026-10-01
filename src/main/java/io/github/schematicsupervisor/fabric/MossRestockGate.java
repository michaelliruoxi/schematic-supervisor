package io.github.schematicsupervisor.fabric;

/** Gives unresolved moss receipts priority over new restock work without rearming a paused build. */
final class MossRestockGate {
    enum Decision {
        /** A pending moss receipt conflicts with active build state or another receipt; retain and halt it. */
        HOLD,
        /** No new maintenance input; preserve the existing paused/stopped/done settlement path. */
        WAIT_PAUSED,
        /** Only observe/reconcile the original pending moss transfer; never issue a new transfer. */
        RECONCILE,
        /** One bounded new moss-storage attempt is eligible before the usual Dirt refill. */
        DEPOSIT,
        /** No moss action; continue the existing receipt settlement or normal restock flow. */
        CONTINUE
    }

    private MossRestockGate() { }

    static Decision decide(boolean pending, boolean activeRestock, boolean pausedOrStoppedOrDone,
                           boolean otherReceiptUnsettled, boolean dirtShortage,
                           boolean attemptedThisRestock, boolean plainMossAvailable) {
        if (pausedOrStoppedOrDone) { return Decision.WAIT_PAUSED; }
        if (pending) {
            return activeRestock && !otherReceiptUnsettled ? Decision.RECONCILE : Decision.HOLD;
        }
        if (activeRestock && !otherReceiptUnsettled && dirtShortage
                && !attemptedThisRestock && plainMossAvailable) {
            return Decision.DEPOSIT;
        }
        return Decision.CONTINUE;
    }
}
