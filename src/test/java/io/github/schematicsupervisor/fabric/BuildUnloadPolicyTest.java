package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BuildUnloadPolicyTest {
    @Test
    void onlyInactiveLoadedStatesCanUnload() {
        for (SupervisorState state : SupervisorState.values()) {
            String problem = BuildUnloadPolicy.settledProblem(snapshot(state, 0));
            boolean allowed = state == SupervisorState.PAUSED || state == SupervisorState.STOPPED
                    || state == SupervisorState.DONE;
            assertEquals(allowed, problem.isBlank(), state.name());
        }
        assertFalse(BuildUnloadPolicy.activityProblem(snapshot(null, 0)).isBlank());
    }

    @Test
    void loadingTakeoffShoppingAndDepotWorkAreEachRejected() {
        for (BuildUnloadPolicy.Snapshot snapshot : List.of(
                new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED, true, false, false, 0,
                        false, false, false, true, false),
                new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED, false, true, false, 0,
                        false, false, false, true, false),
                new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED, false, false, true, 0,
                        false, false, false, true, false),
                new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED, false, false, false, 0,
                        false, false, false, false, false))) {
            assertFalse(BuildUnloadPolicy.activityProblem(snapshot).isBlank());
        }
    }

    @Test
    void uncertainPurchasesAndBlockedControlOwnershipCannotBeDiscarded() {
        assertFalse(BuildUnloadPolicy.activityProblem(snapshot(SupervisorState.PAUSED, 1)).isBlank());
        assertFalse(BuildUnloadPolicy.activityProblem(new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED,
                false, false, false, 0, false, false, true, true, false)).isBlank());
        assertFalse(BuildUnloadPolicy.activityProblem(new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED,
                false, false, false, 0, false, false, false, true, true)).isBlank());
    }

    @Test
    void teardownRetryCanRecoverChangedActivityFlagsButCannotDiscardAnyReceipt() {
        BuildUnloadPolicy.Snapshot releasedPartially = new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED,
                false, false, false, 0, false, false, true, false, true);
        assertFalse(BuildUnloadPolicy.activityProblem(releasedPartially).isBlank());
        assertTrue(BuildUnloadPolicy.receiptProblem(releasedPartially).isBlank());
        assertFalse(BuildUnloadPolicy.receiptProblem(snapshot(SupervisorState.PAUSED, 1)).isBlank());
        assertFalse(BuildUnloadPolicy.receiptProblem(new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED,
                false, false, false, 0, true, false, true, false, true)).isBlank());
        assertFalse(BuildUnloadPolicy.receiptProblem(new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED,
                false, false, false, 0, false, true, true, false, true)).isBlank());
    }

    @Test
    void receiptPollingIsAllowedBeforeEitherPersistedSettlementFlagIsChecked() {
        for (int flag = 0; flag < 2; flag++) {
            BuildUnloadPolicy.Snapshot unresolved = new BuildUnloadPolicy.Snapshot(SupervisorState.PAUSED,
                    false, false, false, 0, flag == 0, flag == 1, false, true, false);
            assertTrue(BuildUnloadPolicy.activityProblem(unresolved).isBlank());
            assertFalse(BuildUnloadPolicy.settledProblem(unresolved).isBlank());
        }
        assertTrue(BuildUnloadPolicy.settledProblem(snapshot(SupervisorState.PAUSED, 0)).isBlank());
    }

    private static BuildUnloadPolicy.Snapshot snapshot(SupervisorState state, int pendingPurchase) {
        return new BuildUnloadPolicy.Snapshot(state, false, false, false, pendingPurchase,
                false, false, false, true, false);
    }
}
