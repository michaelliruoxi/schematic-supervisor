package io.github.schematicsupervisor.fabric;

/** One clean opening may refresh wear only after its exact deposit receipt becomes durable. */
final class MossDepositWearRefresh {
    private boolean openingArmed;
    private boolean openingCaptured;
    private MossDepositJournal intent;

    void arm(boolean depositPending) {
        cancel();
        openingArmed = !depositPending;
    }

    boolean captureBeforeOpen() {
        if (!openingArmed) { return false; }
        openingArmed = false;
        openingCaptured = true;
        return true;
    }

    void submitted(MossDepositJournal submitted) {
        if (!openingCaptured || intent != null || submitted == null
                || submitted.stage() != MossDepositJournal.Stage.PENDING
                || submitted.reconciliationBarrier() != null) {
            cancel();
            return;
        }
        intent = submitted;
    }

    boolean takeConfirmed(MossDepositJournal confirmed, MossDepositJournal.Observation observed,
                          boolean liveBaselineMatches) {
        boolean accepted = openingCaptured && intent != null && liveBaselineMatches
                && confirmed != null && confirmed.stage() == MossDepositJournal.Stage.CONFIRMED
                && confirmed.reconciliationBarrier() == null
                && intent.operationId().equals(confirmed.operationId())
                && intent.before().equals(confirmed.before()) && intent.plan().equals(confirmed.plan())
                && confirmed.receipt().equals(observed);
        cancel();
        return accepted;
    }

    void cancel() {
        openingArmed = false;
        openingCaptured = false;
        intent = null;
    }
}
