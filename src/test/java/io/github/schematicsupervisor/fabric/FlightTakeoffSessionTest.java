package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class FlightTakeoffSessionTest {
    @Test
    void groundedTakeoffHoldsOrdinaryJumpThenActivatesExistingFlightOnceAirborne() {
        FakePort port = new FakePort();
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        assertEquals(List.of(), port.actions);
        session.tick();
        assertTrue(port.jumpPressed);
        assertEquals(List.of("jump:true"), port.actions);
        port.onGround = false;
        session.tick();
        assertFalse(port.jumpPressed);
        assertEquals(List.of("jump:true", "jump:false", "activate-flight"), port.actions);
        assertTrue(session.active());
        session.tick();
        assertEquals("COMPLETE", session.snapshot().state());
        assertFalse(session.active());
        session.tick();
        assertEquals(3, port.actions.size());
        assertTrue(port.flightAllowed);
    }

    @Test
    void airborneTakeoffNeverUsesJumpInput() {
        FakePort port = new FakePort();
        port.onGround = false;
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        session.tick();
        session.tick();
        assertEquals(List.of("activate-flight"), port.actions);
        assertEquals("COMPLETE", session.snapshot().state());
    }

    @Test
    void alreadyFlyingIsANoOpAndDeniedFlightCannotBeGranted() {
        FakePort flying = new FakePort();
        flying.flying = true;
        FlightTakeoffSession existing = new FlightTakeoffSession(flying);
        existing.start();
        assertEquals("COMPLETE", existing.snapshot().state());
        assertEquals(List.of(), flying.actions);
        FakePort denied = new FakePort();
        denied.flightAllowed = false;
        FlightTakeoffSession forbidden = new FlightTakeoffSession(denied);
        forbidden.start();
        forbidden.tick();
        assertEquals("FAILED", forbidden.snapshot().state());
        assertEquals(List.of(), denied.actions);
        assertFalse(denied.flightAllowed);
    }

    @Test
    void jumpIsReleasedAfterFourTicksAndBlockedTakeoffTimesOutWithoutRetry() {
        FakePort port = new FakePort();
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        session.tick();
        for (int tick = 0; tick < FlightTakeoffSession.JUMP_HOLD_TICKS; tick++) { session.tick(); }
        assertFalse(port.jumpPressed);
        assertTrue(session.active());
        for (int tick = 5; tick < FlightTakeoffSession.TIMEOUT_TICKS; tick++) { session.tick(); }
        assertFalse(session.active());
        assertEquals("FAILED", session.snapshot().state());
        assertEquals(List.of("jump:true", "jump:false"), port.actions);
    }

    @Test
    void flightActivationIsNotRetriedWhenItIsNotConfirmed() {
        FakePort port = new FakePort();
        port.onGround = false;
        port.confirmFlight = false;
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        for (int tick = 0; tick < FlightTakeoffSession.TIMEOUT_TICKS; tick++) { session.tick(); }
        assertEquals("FAILED", session.snapshot().state());
        assertEquals(List.of("activate-flight"), port.actions);
    }

    @Test
    void cancelReleasesOwnedJumpOnceAndDoesNotActivateFlightLater() {
        FakePort port = new FakePort();
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        session.tick();
        session.cancel("Operator stopped takeoff.");
        session.cancel("Repeated stop.");
        port.onGround = false;
        session.tick();
        assertEquals("CANCELLED", session.snapshot().state());
        assertEquals(List.of("jump:true", "jump:false"), port.actions);
    }

    @Test
    void disconnectContextChangePermissionLossGuiAndNewBlockersReleaseJumpBeforeFlight() {
        List<Consumer<FakePort>> changes = List.of(
                port -> port.connected = false,
                port -> port.context = "different-world",
                port -> port.flightAllowed = false,
                port -> port.screenBlocked = true,
                port -> port.blocker = "Checkpoint reconciliation is required."
        );
        for (Consumer<FakePort> change : changes) {
            FakePort port = new FakePort();
            FlightTakeoffSession session = new FlightTakeoffSession(port);
            session.start();
            session.tick();
            change.accept(port);
            port.onGround = false;
            session.tick();
            assertEquals("FAILED", session.snapshot().state());
            assertFalse(port.jumpPressed);
            assertEquals(List.of("jump:true", "jump:false"), port.actions);
        }
    }

    @Test
    void blockingScreenMayQueueTakeoffButNoInputIsSentUntilItCloses() {
        FakePort port = new FakePort();
        port.screenBlocked = true;
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        assertTrue(session.active());
        assertEquals(List.of(), port.actions);
        port.screenBlocked = false;
        session.tick();
        assertEquals(List.of("jump:true"), port.actions);
        session.cancel("Cancelled.");
        FakePort stillOpen = new FakePort();
        stillOpen.screenBlocked = true;
        FlightTakeoffSession blocked = new FlightTakeoffSession(stillOpen);
        blocked.start();
        blocked.tick();
        assertEquals("FAILED", blocked.snapshot().state());
        assertEquals(FlightTakeoffSession.BLOCKED_SCREEN, blocked.snapshot().detail());
        assertEquals(List.of(), stillOpen.actions);
    }

    @Test
    void activationFailureStillReleasesJumpAndDoesNotRetry() {
        FakePort port = new FakePort();
        port.throwActivation = true;
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        session.tick();
        port.onGround = false;
        session.tick();
        session.tick();
        assertEquals("FAILED", session.snapshot().state());
        assertFalse(port.jumpPressed);
        assertEquals(List.of("jump:true", "jump:false", "activate-flight"), port.actions);
    }

    @Test
    void leaseDoesNotClobberAHeldUserBindingOrANewPhysicalPress() {
        FakePort alreadyHeld = new FakePort();
        alreadyHeld.jumpPressed = true;
        alreadyHeld.physicalJump = true;
        FlightTakeoffSession.JumpLease untouched = alreadyHeld.holdJump();
        untouched.close();
        assertTrue(alreadyHeld.jumpPressed);
        assertEquals(List.of(), alreadyHeld.actions);
        FakePort pressedDuringTakeoff = new FakePort();
        FlightTakeoffSession.JumpLease owned = pressedDuringTakeoff.holdJump();
        pressedDuringTakeoff.physicalJump = true;
        owned.close();
        owned.close();
        assertTrue(pressedDuringTakeoff.jumpPressed);
        assertEquals(List.of("jump:true", "jump:true"), pressedDuringTakeoff.actions);
    }

    @Test
    void physicalInputReadFailureStillReleasesTheOwnedJumpBinding() {
        List<Boolean> states = new ArrayList<>();
        FlightTakeoffSession.JumpLease lease = new FlightTakeoffSession.JumpInputLease(false,
                () -> { throw new IllegalStateException("Input device unavailable."); }, states::add);
        assertThrows(IllegalStateException.class, lease::close);
        lease.close();
        assertEquals(List.of(true, false), states);
    }

    @Test
    void initialRuntimeBlockerRejectsBeforeAnyInput() {
        FakePort port = new FakePort();
        port.blocker = "Depot maintenance is active.";
        FlightTakeoffSession session = new FlightTakeoffSession(port);
        session.start();
        session.tick();
        assertEquals("FAILED", session.snapshot().state());
        assertEquals(List.of(), port.actions);
    }

    private static final class FakePort implements FlightTakeoffSession.Port {
        private boolean connected = true;
        private String context = "world";
        private boolean flightAllowed = true;
        private boolean flying;
        private boolean onGround = true;
        private boolean screenBlocked;
        private boolean jumpPressed;
        private boolean physicalJump;
        private String blocker = "";
        private boolean confirmFlight = true;
        private boolean throwActivation;
        private final List<String> actions = new ArrayList<>();

        @Override public FlightTakeoffSession.Observation observe() {
            return new FlightTakeoffSession.Observation(connected, context, flightAllowed,
                    flying, onGround, screenBlocked, blocker);
        }
        @Override public FlightTakeoffSession.JumpLease holdJump() {
            return new FlightTakeoffSession.JumpInputLease(jumpPressed, () -> physicalJump, value -> {
                jumpPressed = value;
                actions.add("jump:" + value);
            });
        }
        @Override public void activateExistingFlight() {
            actions.add("activate-flight");
            if (throwActivation) { throw new IllegalStateException("Ability update failed."); }
            if (confirmFlight) { flying = true; }
        }
    }
}
