package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.core.SupervisorState;
import io.github.schematicsupervisor.fabric.TakeoffEnvironment.Facts;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class TakeoffEnvironmentTest {
    private static final Supplier<String> NONE = () -> "";
    private static final Supplier<String> NEVER_READ = () -> {
        throw new AssertionError("read out of order");
    };

    @Test
    void aSettledPausedOrStoppedBuildMayTakeOff() {
        for (SupervisorState state : new SupervisorState[]{SupervisorState.PAUSED, SupervisorState.STOPPED,
                SupervisorState.DONE}) {
            assertEquals("", TakeoffEnvironment.problem(facts(state, NEVER_READ, NONE)), state.name());
        }
    }

    @Test
    void activeSupervisionMustBePausedFirst() {
        for (SupervisorState state : new SupervisorState[]{SupervisorState.BUILDING, SupervisorState.RESTOCKING,
                SupervisorState.STUCK, SupervisorState.VERIFYING}) {
            assertEquals(TakeoffEnvironment.SUPERVISION_ACTIVE,
                    TakeoffEnvironment.problem(facts(state, NEVER_READ, NEVER_READ)), state.name());
        }
    }

    @Test
    void withNoPlanLoadedTheSavedCheckpointIsInspected() {
        assertEquals("The saved checkpoint belongs to a different server, save, or dimension; takeoff is blocked.",
                TakeoffEnvironment.problem(facts(null,
                        () -> "The saved checkpoint belongs to a different server, save, or dimension; takeoff is blocked.",
                        NEVER_READ)));
        assertEquals("", TakeoffEnvironment.problem(facts(null, NONE, NONE)));
    }

    @Test
    void theFirstProblemInOrderWins() {
        assertEquals(TakeoffEnvironment.CLOSED, TakeoffEnvironment.problem(new Facts(true, "teardown", false,
                "blocked", true, SupervisorState.BUILDING, true, NEVER_READ, true, NEVER_READ)));
        assertEquals("teardown", TakeoffEnvironment.problem(new Facts(false, "teardown", false,
                "blocked", true, SupervisorState.BUILDING, true, NEVER_READ, true, NEVER_READ)));
        assertEquals(TakeoffEnvironment.JOIN_WORLD, TakeoffEnvironment.problem(new Facts(false, "", false,
                "blocked", true, SupervisorState.BUILDING, true, NEVER_READ, true, NEVER_READ)));
        assertEquals("blocked", TakeoffEnvironment.problem(new Facts(false, "", true,
                "blocked", true, SupervisorState.BUILDING, true, NEVER_READ, true, NEVER_READ)));
        assertEquals(TakeoffEnvironment.BUSY, TakeoffEnvironment.problem(new Facts(false, "", true,
                "", true, SupervisorState.BUILDING, true, NEVER_READ, true, NEVER_READ)));
        assertEquals(TakeoffEnvironment.RECONCILE, TakeoffEnvironment.problem(new Facts(false, "", true,
                "", false, SupervisorState.PAUSED, true, NEVER_READ, true, NEVER_READ)));
        assertEquals(TakeoffEnvironment.OTHER_WORLD, TakeoffEnvironment.problem(new Facts(false, "", true,
                "", false, SupervisorState.PAUSED, false, NEVER_READ, true, NEVER_READ)));
        assertEquals("Stop current Baritone work before takeoff.", TakeoffEnvironment.problem(new Facts(false, "",
                true, "", false, SupervisorState.PAUSED, false, NEVER_READ, false,
                () -> "Stop current Baritone work before takeoff.")));
    }

    private static Facts facts(SupervisorState state, Supplier<String> saved, Supplier<String> baritone) {
        return new Facts(false, "", true, "", false, state, false, saved, false, baritone);
    }
}
