package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.core.SafeReturnStatus;
import org.junit.jupiter.api.Test;

class ExecutionResetPolicyTest {
    @Test
    void retainsAnActiveOrderAsSuspendedIntent() {
        ExecutionResetPolicy.Normalization normalization =
                ExecutionResetPolicy.afterSuccessfulRelease(true);

        assertEquals(
                ExecutionResetPolicy.OrderIntent.SUSPENDED,
                normalization.orderIntent()
        );
    }

    @Test
    void normalizesAnAbsentOrderToIdleIntent() {
        ExecutionResetPolicy.Normalization normalization =
                ExecutionResetPolicy.afterSuccessfulRelease(false);

        assertEquals(
                ExecutionResetPolicy.OrderIntent.IDLE,
                normalization.orderIntent()
        );
    }

    @Test
    void alwaysClearsSafeReturnStateAndGrace() {
        ExecutionResetPolicy.Normalization normalization =
                ExecutionResetPolicy.afterSuccessfulRelease(true);

        assertEquals(SafeReturnStatus.IDLE, normalization.safeReturnStatus());
        assertEquals(0, normalization.safeReturnGraceTicks());
    }
}
