package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class BuildUnloadCoordinatorTest {
    @Test
    void closesAndRestoresBeforeReleasingIdentityAndCanHandleTheNextBuild() {
        BuildUnloadCoordinator coordinator = new BuildUnloadCoordinator();
        List<String> actions = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            assertTrue(coordinator.attempt(() -> actions.add("close"), () -> actions.add("restore"),
                    () -> actions.add("release")));
            assertFalse(coordinator.cleanupRequired());
        }
        assertEquals(List.of("close", "restore", "release", "close", "restore", "release"), actions);
    }

    @Test
    void failedAdapterCloseRetainsIdentityAndBlocksOtherWorkUntilRetry() {
        BuildUnloadCoordinator coordinator = new BuildUnloadCoordinator();
        AtomicBoolean fail = new AtomicBoolean(true);
        List<String> actions = new ArrayList<>();
        Runnable close = () -> {
            actions.add("close");
            if (fail.get()) { throw new IllegalStateException("movement release failed"); }
        };
        assertFalse(coordinator.attempt(close, () -> actions.add("restore"), () -> actions.add("release")));
        assertEquals(List.of("close"), actions);
        assertTrue(coordinator.cleanupRequired());
        assertTrue(coordinator.detail().contains("movement release failed"));
        fail.set(false);
        assertTrue(coordinator.attempt(close, () -> actions.add("restore"), () -> actions.add("release")));
        assertEquals(List.of("close", "close", "restore", "release"), actions);
        assertFalse(coordinator.cleanupRequired());
    }

    @Test
    void failedSettingsRestoreRetainsIdentityAndDoesNotCloseAdaptersAgain() {
        BuildUnloadCoordinator coordinator = new BuildUnloadCoordinator();
        AtomicBoolean fail = new AtomicBoolean(true);
        List<String> actions = new ArrayList<>();
        Runnable restore = () -> {
            actions.add("restore");
            if (fail.get()) { throw new IllegalStateException("settings unavailable"); }
        };
        assertFalse(coordinator.attempt(() -> actions.add("close"), restore, () -> actions.add("release")));
        assertEquals(List.of("close", "restore"), actions);
        assertTrue(coordinator.cleanupRequired());
        fail.set(false);
        assertTrue(coordinator.attempt(() -> actions.add("unexpected close"), restore, () -> actions.add("release")));
        assertEquals(List.of("close", "restore", "restore", "release"), actions);
    }

    @Test
    void identityReleaseFailureKeepsTeardownBlockedAndDoesNotRepeatCompletedSteps() {
        BuildUnloadCoordinator coordinator = new BuildUnloadCoordinator();
        List<String> actions = new ArrayList<>();
        assertFalse(coordinator.attempt(() -> actions.add("close"), () -> actions.add("restore"),
                () -> { throw new IllegalStateException("identity retained"); }));
        assertTrue(coordinator.cleanupRequired());
        assertTrue(coordinator.attempt(() -> actions.add("unexpected close"),
                () -> actions.add("unexpected restore"), () -> actions.add("release")));
        assertEquals(List.of("close", "restore", "release"), actions);
    }
}
