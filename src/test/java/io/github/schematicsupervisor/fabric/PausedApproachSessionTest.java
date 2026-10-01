package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PausedApproachSessionTest {
    @Test void waitsForCommandThenApproachesOnceAndReleasesAtArrival() {
        FakePort port = new FakePort();
        port.chat = true;
        var session = new PausedApproachSession(port);
        session.start();
        session.tick();
        assertTrue(port.actions.isEmpty());
        port.chat = false;
        session.tick();
        assertEquals(List.of("begin"), port.actions);
        port.arrived = true;
        session.tick();
        assertEquals(List.of("begin", "tick", "stop"), port.actions);
        assertFalse(session.active());
        assertFalse(session.failed());
        assertTrue(session.detail().contains("construction remains paused"));
        session.tick();
        assertEquals(3, port.actions.size());
    }

    @Test void pauseCancelsQueuedAndActiveRoutesWithoutLaterMovement() {
        for (boolean started : List.of(false, true)) {
            FakePort port = new FakePort();
            var session = new PausedApproachSession(port);
            session.start();
            if (started) { session.tick(); }
            session.cancel("Paused by operator");
            session.tick();
            session.cancel("Again");
            assertEquals(started ? List.of("begin", "stop") : List.of("stop"), port.actions);
            assertFalse(session.active());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"disconnect", "context", "flight", "screen", "receipt", "unpaused"})
    void changesStopBeforeAnotherNavigationTick(String change) {
        FakePort port = new FakePort();
        var session = new PausedApproachSession(port);
        session.start();
        session.tick();
        switch (change) {
            case "disconnect" -> port.context = null;
            case "context" -> port.context = new Object();
            case "flight" -> port.flying = false;
            case "screen" -> port.blocked = true;
            case "receipt" -> port.problem = "Unsettled inventory receipt";
            case "unpaused" -> port.problem = "Build is no longer paused";
        }
        session.tick();
        session.tick();
        assertEquals(List.of("begin", "stop"), port.actions);
        assertFalse(session.active());
        assertTrue(session.failed());
    }

    @Test void pendingReceiptRejectsBeforeStarting() {
        FakePort port = new FakePort();
        port.problem = "Pending discard must be reconciled";
        var session = new PausedApproachSession(port);
        session.start();
        session.tick();
        assertEquals(List.of("stop"), port.actions);
        assertTrue(session.failed());
    }

    @Test void navigationFailureIsReportedWithoutRetry() {
        FakePort port = new FakePort();
        port.failed = true;
        var session = new PausedApproachSession(port);
        session.start();
        session.tick();
        session.tick();
        assertEquals(List.of("begin", "stop"), port.actions);
        assertTrue(session.failed());
        assertEquals("Route result", session.detail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"observe", "begin", "tick"})
    void adapterExceptionsReleaseMovement(String operation) {
        FakePort port = new FakePort();
        var session = new PausedApproachSession(port);
        session.start();
        if (operation.equals("tick")) { session.tick(); }
        port.throwFrom = operation;
        session.tick();
        assertTrue(session.failed());
        assertEquals("stop", port.actions.getLast());
        int count = port.actions.size();
        session.tick();
        assertEquals(count, port.actions.size());
    }

    @Test void openCommandScreenTimesOutWithoutStarting() {
        FakePort port = new FakePort();
        port.chat = true;
        var session = new PausedApproachSession(port);
        session.start();
        for (int i = 0; i < PausedApproachSession.COMMAND_WAIT_TICKS + 1; i++) { session.tick(); }
        assertFalse(session.active());
        assertEquals(List.of("stop"), port.actions);
    }

    @Test void openingChatDuringFlightCancelsImmediately() {
        FakePort port = new FakePort();
        var session = new PausedApproachSession(port);
        session.start();
        session.tick();
        port.chat = true;
        session.tick();
        assertEquals(List.of("begin", "stop"), port.actions);
        assertFalse(session.active());
    }

    @Test void activeApproachHasAnOverallBudgetAndNoAutomaticReplay() {
        FakePort port = new FakePort();
        var session = new PausedApproachSession(port);
        session.start();
        for (int i = 0; i < PausedApproachSession.MAXIMUM_TICKS + 2; i++) { session.tick(); }
        assertTrue(session.failed());
        assertEquals(1, port.actions.stream().filter("begin"::equals).count());
        assertEquals(1, port.actions.stream().filter("stop"::equals).count());
    }

    private static final class FakePort implements PausedApproachSession.Port {
        Object context = new Object();
        boolean flying = true, chat, blocked, arrived, failed;
        String problem = "", throwFrom = "";
        final List<String> actions = new ArrayList<>();
        @Override public PausedApproachSession.Observation observe() {
            check("observe");
            return new PausedApproachSession.Observation(context, flying, chat, blocked, problem);
        }
        @Override public void begin() { actions.add("begin"); check("begin"); }
        @Override public void tick() { actions.add("tick"); check("tick"); }
        @Override public boolean arrived() { return arrived; }
        @Override public boolean failed() { return failed; }
        @Override public String detail() { return "Route result"; }
        @Override public void stop() { actions.add("stop"); }
        private void check(String operation) {
            if (operation.equals(throwFrom)) { throw new IllegalStateException("Injected adapter failure"); }
        }
    }
}
