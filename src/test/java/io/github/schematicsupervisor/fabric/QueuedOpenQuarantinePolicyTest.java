package io.github.schematicsupervisor.fabric;

import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.FAIL_CONTEXT;
import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.FAIL_IDENTITY;
import static io.github.schematicsupervisor.fabric.CancellationOpenPolicy.Decision.FAIL_TIMEOUT;
import static io.github.schematicsupervisor.fabric.QueuedOpenQuarantinePolicy.Decision.CLOSE_MATCHING;
import static io.github.schematicsupervisor.fabric.QueuedOpenQuarantinePolicy.Decision.RELEASE_MANUALLY_CLOSED;
import static io.github.schematicsupervisor.fabric.QueuedOpenQuarantinePolicy.Decision.RETAIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueuedOpenQuarantinePolicyTest {
    private static final CancellationOpenPolicy OPEN_REQUEST =
            new CancellationOpenPolicy(4, 6, 60);

    @Test
    void timeoutContextAndWrongIdentityFailuresRetainTheExactOpenRequest() {
        List<CancellationOpenPolicy.Decision> failures = List.of(
                OPEN_REQUEST.evaluate(
                        true,
                        61,
                        CancellationOpenPolicy.ScreenObservation.playerInventory()
                ),
                OPEN_REQUEST.evaluate(
                        false,
                        30,
                        CancellationOpenPolicy.ScreenObservation.playerInventory()
                ),
                OPEN_REQUEST.evaluate(
                        true,
                        30,
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 3)
                )
        );
        assertEquals(List.of(FAIL_TIMEOUT, FAIL_CONTEXT, FAIL_IDENTITY), failures);

        for (CancellationOpenPolicy.Decision failure : failures) {
            QueuedOpenQuarantinePolicy quarantine =
                    QueuedOpenQuarantinePolicy.fromFailure(
                            OPEN_REQUEST,
                            failure,
                            "queued screen response is uncertain"
                    );

            assertSame(OPEN_REQUEST, quarantine.openRequest());
            assertEquals(4, quarantine.openRequest().openingSyncId());
            assertEquals(6, quarantine.openRequest().expectedRows());
            assertFalse(quarantine.matchedResponse());
            assertEquals(
                    RETAIN,
                    quarantine.observe(
                            CancellationOpenPolicy.ScreenObservation.playerInventory(),
                            true
                    ).decision()
            );
            assertEquals(
                    RETAIN,
                    quarantine.observe(
                            CancellationOpenPolicy.ScreenObservation.genericContainer(5, 3),
                            true
                    ).decision()
            );
        }
    }

    @Test
    void matchingEmptyResponseCanBeClosedAndReleased() {
        QueuedOpenQuarantinePolicy quarantine =
                QueuedOpenQuarantinePolicy.fromFailure(
                        OPEN_REQUEST,
                        FAIL_TIMEOUT,
                        "queued screen response timed out"
                );

        assertEquals(
                CLOSE_MATCHING,
                quarantine.observe(
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 6),
                        true
                ).decision()
        );
    }

    @Test
    void matchingNonemptyResponseIsTrackedUntilEmptyOrManuallyClosed() {
        QueuedOpenQuarantinePolicy quarantine =
                QueuedOpenQuarantinePolicy.start(
                        OPEN_REQUEST,
                        "matching response cursor was not empty"
                );

        QueuedOpenQuarantinePolicy.Observation first =
                quarantine.observe(
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 6),
                        false
                );
        assertEquals(RETAIN, first.decision());
        QueuedOpenQuarantinePolicy tracked = first.state();
        assertTrue(tracked.matchedResponse());
        assertEquals(
                RETAIN,
                tracked.observe(
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 6),
                        false
                ).decision()
        );
        assertEquals(
                CLOSE_MATCHING,
                tracked.observe(
                        CancellationOpenPolicy.ScreenObservation.genericContainer(5, 6),
                        true
                ).decision()
        );
        assertEquals(
                RELEASE_MANUALLY_CLOSED,
                tracked.observe(
                        CancellationOpenPolicy.ScreenObservation.playerInventory(),
                        false
                ).decision()
        );
        QueuedOpenQuarantinePolicy.Observation replaced =
                tracked.observe(
                        CancellationOpenPolicy.ScreenObservation.genericContainer(6, 6),
                        true
                );
        assertEquals(
                RETAIN,
                replaced.decision()
        );
        assertEquals(
                RETAIN,
                replaced.state().observe(
                        CancellationOpenPolicy.ScreenObservation.playerInventory(),
                        false
                ).decision()
        );
    }

    @Test
    void quarantineRejectsNonfailureEntryAndIdentityReplacement() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QueuedOpenQuarantinePolicy.fromFailure(
                        OPEN_REQUEST,
                        CancellationOpenPolicy.Decision.WAIT,
                        "not a failure"
                )
        );
        QueuedOpenQuarantinePolicy tracked =
                QueuedOpenQuarantinePolicy.start(
                        OPEN_REQUEST,
                        "matching response was retained"
                ).withMatchedResponse(5, 6);
        assertThrows(
                IllegalStateException.class,
                () -> tracked.withMatchedResponse(6, 6)
        );
    }
}
