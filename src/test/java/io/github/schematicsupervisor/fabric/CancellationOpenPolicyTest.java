package io.github.schematicsupervisor.fabric;

import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.CLOSE_MATCHING;
import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.FAIL_CONTEXT;
import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.FAIL_IDENTITY;
import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.FAIL_TIMEOUT;
import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CancellationOpenPolicyTest {
    private static final CancellationOpenPolicy POLICY =
            new CancellationOpenPolicy(4, 6, 60);

    @Test
    void waitsForTheOutstandingServerResponse() {
        assertEquals(
                WAIT,
                POLICY.evaluate(
                        true,
                        30,
                        CancellationOpenPolicy.ScreenObservation.playerInventory()
                )
        );
    }

    @Test
    void closesOnlyANewMatchingContainerIdentity() {
        assertEquals(
                CLOSE_MATCHING,
                POLICY.evaluate(
                        true,
                        30,
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 6)
                )
        );
    }

    @Test
    void rejectsTheOpeningSyncIdAndWrongShape() {
        assertEquals(
                FAIL_IDENTITY,
                POLICY.evaluate(
                        true,
                        30,
                        CancellationOpenPolicy.ScreenObservation.genericContainer(4, 6)
                )
        );
        assertEquals(
                FAIL_IDENTITY,
                POLICY.evaluate(
                        true,
                        30,
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 3)
                )
        );
        assertEquals(
                FAIL_IDENTITY,
                POLICY.evaluate(
                        true,
                        30,
                        CancellationOpenPolicy.ScreenObservation.other()
                )
        );
    }

    @Test
    void failsClosedOnContextLossOrTimeout() {
        assertEquals(
                FAIL_CONTEXT,
                POLICY.evaluate(
                        false,
                        30,
                        CancellationOpenPolicy.ScreenObservation.playerInventory()
                )
        );
        assertEquals(
                FAIL_TIMEOUT,
                POLICY.evaluate(
                        true,
                        61,
                        CancellationOpenPolicy.ScreenObservation.playerInventory()
                )
        );
    }
}
