package io.github.schematicsupervisor.fabric;

/**
 * Freezes automation and retains global safeguards while any Baritone control release is
 * uncertain.
 */
final class AutomationReleasePolicy {
    private AutomationReleasePolicy() {
    }

    static boolean mayRestoreSafetySettings(
            boolean depotAutomationBlocked,
            boolean executionAutomationBlocked
    ) {
        return !depotAutomationBlocked && !executionAutomationBlocked;
    }

    static boolean mayAdvancePlanAdapters(
            boolean depotAutomationBlocked,
            boolean executionAutomationBlocked
    ) {
        return !depotAutomationBlocked && !executionAutomationBlocked;
    }

    static boolean mayTickDepots(boolean executionAutomationBlocked) {
        return !executionAutomationBlocked;
    }
}
