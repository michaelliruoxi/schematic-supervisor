package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.schematicsupervisor.fabric.ExecutionTickGate.Facts;
import io.github.schematicsupervisor.fabric.ExecutionTickGate.Next;
import org.junit.jupiter.api.Test;

final class ExecutionTickGateTest {
    private static final InteractionTickDispatch.Next ADVANCE = InteractionTickDispatch.Next.ADVANCE;

    @Test
    void aFlyingBuildWithNoInputAdvances() {
        assertEquals(Next.ADVANCE, decide(flying()));
    }

    @Test
    void aClickAwaitingItsAcknowledgementSettlesBeforeAnyOtherCheck() {
        Facts everythingWrong = new Facts(InteractionTickDispatch.Next.WAIT_RECEIPT, true, true, false, true, true, true,
                ExecutionMode.MANUAL_READY);
        assertEquals(Next.WAIT_RECEIPT, decide(everythingWrong));
    }

    @Test
    void lostFlightStopsWorkEvenBehindAMenu() {
        assertEquals(Next.FAIL_FLIGHT_LOST, decide(new Facts(ADVANCE, false, true, false, false, false, false,
                ExecutionMode.MANUAL_NAVIGATING)));
        assertEquals(Next.FAIL_FLIGHT_LOST, decide(new Facts(InteractionTickDispatch.Next.WAIT_SCREEN, true, true,
                false, false, false, false, ExecutionMode.MANUAL_NAVIGATING)));
        // A ground build never relied on flight.
        assertEquals(Next.ADVANCE, decide(new Facts(ADVANCE, false, false, false, false, false, false,
                ExecutionMode.ORDINARY_RUNNING)));
    }

    @Test
    void keysPressedWhileAMenuIsOpenAreTypingNotMovement() {
        assertEquals(Next.WAIT_SCREEN, decide(new Facts(InteractionTickDispatch.Next.WAIT_SCREEN, true, true, true,
                true, true, true, ExecutionMode.FLIGHT_CLEARING_MOSS)));
    }

    @Test
    void attackOrUseOnlyInterruptsOwnedMining() {
        assertEquals(Next.FAIL_MANUAL_INTERACTION, decide(new Facts(ADVANCE, false, true, true, true, true, false,
                ExecutionMode.FLIGHT_CLEARING_MOSS)));
        assertEquals(Next.ADVANCE, decide(new Facts(ADVANCE, false, true, true, false, true, false,
                ExecutionMode.MANUAL_READY)));
    }

    @Test
    void movementKeysInterruptFlightWorkExceptWhileWaitingForMaterials() {
        assertEquals(Next.FAIL_MANUAL_MOVEMENT, decide(new Facts(ADVANCE, false, true, true, false, false, true,
                ExecutionMode.MANUAL_NAVIGATING)));
        assertEquals(Next.ADVANCE, decide(new Facts(ADVANCE, false, true, true, false, false, true,
                ExecutionMode.WAITING_MATERIALS)));
        assertEquals(Next.ADVANCE, decide(new Facts(ADVANCE, false, false, false, false, false, true,
                ExecutionMode.ORDINARY_RUNNING)), "Baritone's ground builder is not watched for movement keys");
    }

    @Test
    void ownedMiningInputIsReportedBeforeMovementInput() {
        assertEquals(Next.FAIL_MANUAL_INTERACTION, decide(new Facts(ADVANCE, false, true, true, true, true, true,
                ExecutionMode.STEM_SWEEP_MINING)));
    }

    private static Facts flying() {
        return new Facts(ADVANCE, false, true, true, false, false, false, ExecutionMode.MANUAL_NAVIGATING);
    }

    private static Next decide(Facts facts) {
        return ExecutionTickGate.decide(facts);
    }
}
