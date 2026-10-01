package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorState;

/** Admission and receipt checks for releasing a loaded build while preserving its stores. */
final class BuildUnloadPolicy {
    private BuildUnloadPolicy() { }

    static String activityProblem(Snapshot snapshot) {
        if (snapshot.loading()) { return "Pause placement loading before unloading."; }
        if (snapshot.state() == null) { return "No build is loaded."; }
        if (snapshot.state() != SupervisorState.PAUSED && snapshot.state() != SupervisorState.STOPPED
                && snapshot.state() != SupervisorState.DONE) {
            return "Pause or stop the loaded build before unloading it.";
        }
        if (snapshot.takeoffActive() || snapshot.shoppingActive()) {
            return "Wait for takeoff and shopping to finish before unloading.";
        }
        if (snapshot.pendingPurchases() > 0) {
            return "The last dirt purchase is unsettled; keep its loaded build until acknowledgement.";
        }
        if (snapshot.executionBlocked()) {
            return "Execution controls have not been released; resolve their failure before unloading.";
        }
        if (!snapshot.depotIdle() || snapshot.depotBlocked()) {
            return "Depot work or its cleanup is unsettled; pause it and wait before unloading.";
        }
        return "";
    }

    static String settledProblem(Snapshot snapshot) {
        String activity = activityProblem(snapshot);
        if (!activity.isBlank()) { return activity; }
        return receiptProblem(snapshot);
    }

    static String receiptProblem(Snapshot snapshot) {
        if (snapshot.pendingPurchases() > 0) {
            return "The last dirt purchase is unsettled; keep its loaded build until acknowledgement.";
        }
        if (snapshot.reconciliationRequired() || snapshot.withdrawalInFlight()) {
            return "The loaded checkpoint has unsettled interactions or requires reconciliation; "
                    + "resolve them before unloading.";
        }
        return "";
    }

    record Snapshot(SupervisorState state, boolean loading, boolean takeoffActive,
                    boolean shoppingActive, int pendingPurchases, boolean reconciliationRequired,
                    boolean withdrawalInFlight, boolean executionBlocked, boolean depotIdle,
                    boolean depotBlocked) { }
}
