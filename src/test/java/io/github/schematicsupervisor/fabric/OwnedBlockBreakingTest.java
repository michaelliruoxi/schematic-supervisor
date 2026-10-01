package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OwnedBlockBreakingTest {
    @Test void onlyTheLeasedManagerSkipsIdleCancellation() {
        Object manager = new Object();
        var lease = OwnedBlockBreaking.acquire(manager);
        try {
            assertTrue(OwnedBlockBreaking.isOwned(manager));
            assertFalse(OwnedBlockBreaking.isOwned(new Object()));
        } finally { lease.close(); }
        assertFalse(OwnedBlockBreaking.isOwned(manager));
    }

    @Test void cannotTakeOverAnExistingLease() {
        var lease = OwnedBlockBreaking.acquire(new Object());
        try {
            assertThrows(IllegalStateException.class, () -> OwnedBlockBreaking.acquire(new Object()));
        } finally { lease.close(); }
    }

    @Test void repeatedOldCleanupCannotReleaseANewerLease() {
        var oldLease = OwnedBlockBreaking.acquire(new Object());
        oldLease.close();
        Object manager = new Object();
        var current = OwnedBlockBreaking.acquire(manager);
        try {
            oldLease.close();
            assertTrue(OwnedBlockBreaking.isOwned(manager));
        } finally { current.close(); }
    }
}
