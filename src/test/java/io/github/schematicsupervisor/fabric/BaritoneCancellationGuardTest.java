package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BaritoneCancellationGuardTest {
    @Test
    void attemptsEveryControlLossActionAndKeepsRestrictiveSettingsOnFailure() {
        List<String> calls = new ArrayList<>();
        BaritoneCancellationGuard guard = new BaritoneCancellationGuard(
                () -> calls.add("freeze"),
                () -> {
                    calls.add("cancel");
                    throw new IllegalStateException("cancel unavailable");
                },
                () -> {
                    calls.add("force");
                    throw new IllegalStateException("force unavailable");
                },
                () -> {
                    calls.add("builder");
                    throw new IllegalStateException("builder control unavailable");
                },
                () -> {
                    calls.add("goal");
                    throw new IllegalStateException("goal control unavailable");
                },
                () -> calls.add("restore")
        );

        assertFalse(guard.stop("navigation stop"));
        assertEquals(List.of("freeze", "cancel", "force", "builder", "goal"), calls);
        assertTrue(guard.blocked());
        assertTrue(guard.blockedDetail().contains("cancelEverything failed"));
        assertTrue(guard.blockedDetail().contains("forceCancel failed"));
        assertTrue(guard.blockedDetail().contains("builderOnLostControl failed"));
        assertTrue(guard.blockedDetail().contains("customGoalOnLostControl failed"));
        assertTrue(guard.blockedDetail().contains("Reset is required"));
    }

    @Test
    void preparationFailureStillAttemptsEveryDecisiveCancellationAndSkipsRestore() {
        List<String> calls = new ArrayList<>();
        BaritoneCancellationGuard guard = new BaritoneCancellationGuard(
                () -> {
                    calls.add("freeze");
                    throw new IllegalStateException("settings unavailable");
                },
                () -> calls.add("cancel"),
                () -> calls.add("force"),
                () -> calls.add("builder"),
                () -> calls.add("goal"),
                () -> calls.add("restore")
        );

        assertFalse(guard.stop("execution stop"));
        assertEquals(List.of("freeze", "cancel", "force", "builder", "goal"), calls);
        assertTrue(guard.blockedDetail().contains("prepareForCancellation failed"));
        assertTrue(guard.blockedDetail().contains("Reset is required"));
    }

    @Test
    void ordinaryStopDoesNotRetryWhileLatched() {
        List<String> calls = new ArrayList<>();
        BaritoneCancellationGuard guard = new BaritoneCancellationGuard(
                () -> calls.add("freeze"),
                () -> {
                    calls.add("cancel");
                    throw new IllegalStateException("unavailable");
                },
                () -> calls.add("force"),
                () -> calls.add("builder"),
                () -> calls.add("goal"),
                () -> calls.add("restore")
        );
        assertFalse(guard.stop("first stop"));

        assertFalse(guard.stop("second stop"));
        assertEquals(List.of("freeze", "cancel", "force", "builder", "goal"), calls);
    }

    @Test
    void explicitResetRetryClearsLatchOnlyAfterDecisiveSuccess() {
        AtomicBoolean fail = new AtomicBoolean(true);
        List<String> calls = new ArrayList<>();
        BaritoneCancellationGuard guard = new BaritoneCancellationGuard(
                () -> calls.add("freeze"),
                () -> {
                    calls.add("cancel");
                    if (fail.get()) {
                        throw new IllegalStateException("unavailable");
                    }
                },
                () -> calls.add("force"),
                () -> calls.add("builder"),
                () -> calls.add("goal"),
                () -> calls.add("restore")
        );
        assertFalse(guard.stop("navigation stop"));
        assertFalse(guard.retryForReset());
        assertTrue(guard.blocked());

        fail.set(false);
        assertTrue(guard.retryForReset());
        assertFalse(guard.blocked());
        assertEquals("", guard.blockedDetail());
        assertEquals("restore", calls.get(calls.size() - 1));
    }

    @Test
    void restorationFailureAlsoLatchesResetRequired() {
        BaritoneCancellationGuard guard = new BaritoneCancellationGuard(
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                    throw new IllegalStateException("restore unavailable");
                }
        );

        assertFalse(guard.stop("navigation stop"));
        assertTrue(guard.blockedDetail().contains("restoreSettings failed"));
    }
}
