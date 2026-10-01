package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResetTeardownCoordinatorTest {
    @Test
    void teardownBlocksAdmissionUntilBothStoresClear() {
        ResetTeardownCoordinator coordinator = new ResetTeardownCoordinator(
                () -> {
                },
                () -> {
                }
        );

        coordinator.begin();

        assertTrue(coordinator.cleanupRequired());
        assertFalse(coordinator.runtimeWorkAllowed());
        assertTrue(coordinator.detail().contains("Reset is required"));
        ResetTeardownCoordinator.Result result = coordinator.clearStores();
        assertTrue(result.complete());
        assertFalse(coordinator.cleanupRequired());
        assertTrue(coordinator.runtimeWorkAllowed());
        assertEquals("", coordinator.detail());
    }

    @Test
    void independentlyAttemptsBothStoresAndSupportsIdempotentRetry() {
        AtomicBoolean failCheckpoint = new AtomicBoolean(true);
        AtomicBoolean failContext = new AtomicBoolean(true);
        AtomicInteger checkpointCalls = new AtomicInteger();
        AtomicInteger contextCalls = new AtomicInteger();
        ResetTeardownCoordinator coordinator = new ResetTeardownCoordinator(
                () -> {
                    checkpointCalls.incrementAndGet();
                    if (failCheckpoint.get()) {
                        throw new IOException("checkpoint locked");
                    }
                },
                () -> {
                    contextCalls.incrementAndGet();
                    if (failContext.get()) {
                        throw new IllegalStateException("context locked");
                    }
                }
        );
        coordinator.begin();

        ResetTeardownCoordinator.Result failed = coordinator.clearStores();

        assertFalse(failed.complete());
        assertTrue(coordinator.cleanupRequired());
        assertTrue(failed.detail().contains("checkpoint locked"));
        assertTrue(failed.detail().contains("context locked"));
        assertTrue(failed.detail().contains("Reset is required"));
        assertEquals(1, checkpointCalls.get());
        assertEquals(1, contextCalls.get());

        failCheckpoint.set(false);
        failContext.set(false);
        ResetTeardownCoordinator.Result retried = coordinator.clearStores();

        assertTrue(retried.complete());
        assertFalse(coordinator.cleanupRequired());
        assertEquals(2, checkpointCalls.get());
        assertEquals(2, contextCalls.get());
    }

    @Test
    void adapterCloseFailureKeepsTeardownBlockedUntilCleanupRetry() {
        ResetTeardownCoordinator coordinator = new ResetTeardownCoordinator(
                () -> {
                },
                () -> {
                }
        );
        coordinator.begin();

        coordinator.recordIncomplete("plan adapter close failed");

        assertTrue(coordinator.cleanupRequired());
        assertTrue(coordinator.detail().contains("plan adapter close failed"));
        assertFalse(coordinator.clearStores().detail().contains("plan adapter close failed"));
        assertFalse(coordinator.cleanupRequired());
    }

    @Test
    void rejectsCleanupBeforeTeardownBegins() {
        ResetTeardownCoordinator coordinator = new ResetTeardownCoordinator(
                () -> {
                },
                () -> {
                }
        );

        assertThrows(IllegalStateException.class, coordinator::clearStores);
    }
}
