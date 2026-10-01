package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutomationReleasePolicyTest {
    @Test
    void retainsSafetySettingsUntilEveryControlLossIsResolved() {
        assertFalse(AutomationReleasePolicy.mayRestoreSafetySettings(true, false));
        assertFalse(AutomationReleasePolicy.mayRestoreSafetySettings(false, true));
        assertFalse(AutomationReleasePolicy.mayRestoreSafetySettings(true, true));
        assertTrue(AutomationReleasePolicy.mayRestoreSafetySettings(false, false));
    }

    @Test
    void freezesPlanAdaptersWhileAnyControlLossIsUncertain() {
        assertFalse(AutomationReleasePolicy.mayAdvancePlanAdapters(true, false));
        assertFalse(AutomationReleasePolicy.mayAdvancePlanAdapters(false, true));
        assertFalse(AutomationReleasePolicy.mayAdvancePlanAdapters(true, true));
        assertTrue(AutomationReleasePolicy.mayAdvancePlanAdapters(false, false));
    }

    @Test
    void depotQuarantineMayTickUnlessExecutionControlIsUncertain() {
        assertFalse(AutomationReleasePolicy.mayTickDepots(true));
        assertTrue(AutomationReleasePolicy.mayTickDepots(false));
    }
}
