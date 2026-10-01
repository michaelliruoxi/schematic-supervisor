package io.github.schematicsupervisor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ControlAvailabilityTest {
    private static final BuildAccessPreflight.Decision FLYING = BuildAccessPreflight.forFloatingBuild(true, true);
    private static final BuildAccessPreflight.Decision STANDING = BuildAccessPreflight.forFloatingBuild(false, true);
    private static final BuildAccessPreflight.Decision NO_FLIGHT = BuildAccessPreflight.forFloatingBuild(false, false);

    @Test
    void anIdleReadyClientOffersStartAndDepotScans() {
        var result = evaluate(facts().build());
        assertEquals(List.of(), result.blockers());
        assertEquals(List.of("STOP", "SCAN_DEPOTS", "START"), result.actions());
        assertTrue(result.ready());
        assertEquals("", result.error());
    }

    @Test
    void aPausedBuildOffersResumeEvenToAStandingPlayerWithServerFlight() {
        var result = evaluate(facts().state(SupervisorState.PAUSED).pausable(true).flight(STANDING).build());
        assertEquals(List.of(), result.blockers());
        assertEquals(List.of("STOP", "PAUSE", "SCAN_DEPOTS", "RESUME"), result.actions());
    }

    @Test
    void withoutServerFlightStartAndResumeAreBlockedWithTheReason() {
        for (SupervisorState state : new SupervisorState[]{null, SupervisorState.STOPPED, SupervisorState.PAUSED}) {
            var result = evaluate(facts().state(state).flight(NO_FLIGHT).build());
            assertEquals(List.of(BuildAccessPreflight.FLIGHT_NOT_GRANTED), result.blockers());
            assertFalse(result.actions().contains("START"));
            assertFalse(result.actions().contains("RESUME"));
            assertTrue(result.actions().contains("SCAN_DEPOTS"), "flight does not gate depot scans");
        }
    }

    @Test
    void aRunningBuildOffersOnlyPauseAndStop() {
        for (SupervisorState state : new SupervisorState[]{SupervisorState.BUILDING, SupervisorState.RESTOCKING,
                SupervisorState.STUCK, SupervisorState.VERIFYING, SupervisorState.DONE}) {
            var result = evaluate(facts().state(state).pausable(true).scansAllowed(false).build());
            assertEquals(List.of("STOP", "PAUSE"), result.actions(), state.name());
            assertFalse(result.ready());
        }
    }

    @Test
    void loadingOrCheckingNeverOffersAnotherStart() {
        var result = evaluate(facts().preparing(true).pausable(true).build());
        assertFalse(result.actions().contains("START"));
    }

    @Test
    void aClosedRuntimeOffersNothing() {
        var result = evaluate(facts().closed(true).build());
        assertEquals(List.of(ControlAvailability.CLOSED), result.blockers());
        assertEquals(List.of(), result.actions());
    }

    @Test
    void connectionAndWorldBlockersComeFirstAndOnlyOneOfThemApplies() {
        assertEquals(List.of(ControlAvailability.JOIN_WORLD),
                evaluate(facts().connected(false).contextMatches(false).build()).blockers());
        assertEquals(List.of(ControlAvailability.OTHER_WORLD),
                evaluate(facts().contextMatches(false).build()).blockers());
    }

    @Test
    void everyActiveConditionIsListedInOrder() {
        var result = evaluate(facts().runtimeWorkBlocker("Reset is still cleaning up.")
                .automationBlocker("Baritone could not be released.").reconciliationRequired(true)
                .tokenConfigured(false).takeoffActivity("Taking off with a bounded jump.")
                .shoppingActivity("Dirt shop: Opening /shop.").build());
        assertEquals(List.of("Reset is still cleaning up.", "Baritone could not be released.",
                ControlAvailability.RECONCILE, ControlAvailability.NO_TOKEN,
                "Taking off with a bounded jump." + ControlAvailability.CANCEL_SUFFIX,
                "Dirt shop: Opening /shop." + ControlAvailability.CANCEL_SUFFIX), result.blockers());
        assertEquals(List.of("STOP"), result.actions());
    }

    @Test
    void depotScansBlockStartUnlessShoppingAlreadyExplainsTheWait() {
        var scanning = evaluate(facts().depotMaintenanceIdle(false).build());
        assertEquals(List.of(ControlAvailability.SCANNING), scanning.blockers());
        assertFalse(scanning.actions().contains("SCAN_DEPOTS"));
        var shopping = evaluate(facts().depotMaintenanceIdle(false).shoppingActivity("Material shop: buying.").build());
        assertEquals(List.of("Material shop: buying." + ControlAvailability.CANCEL_SUFFIX), shopping.blockers());
    }

    @Test
    void aCapacityProblemBlocksAndBecomesTheErrorButIsReadOnlyWhenNothingElseBlocks() {
        var result = evaluate(facts().state(SupervisorState.PAUSED).capacityProblem("Inventory capacity blocked: x")
                .lastRuntimeError("older runtime error").statusError("older status error").build());
        assertEquals(List.of("Inventory capacity blocked: x"), result.blockers());
        assertEquals("Inventory capacity blocked: x", result.error());
        assertFalse(result.actions().contains("RESUME"));

        int[] reads = {0};
        evaluate(facts().connected(false).capacitySupplier(() -> {
            reads[0]++;
            return "should not be read";
        }).build());
        assertEquals(0, reads[0]);
    }

    @Test
    void theRuntimeErrorWinsOverTheStatusError() {
        assertEquals("runtime", evaluate(facts().lastRuntimeError("runtime").statusError("status").build()).error());
        assertEquals("status", evaluate(facts().statusError("status").build()).error());
    }

    @Test
    void withNoPlanLoadedTheSelectionMustBeValidToStart() {
        var result = evaluate(facts().selectionProblem("Select exactly one enabled Litematica sub-region.").build());
        assertEquals(List.of("Select exactly one enabled Litematica sub-region."), result.blockers());
        assertFalse(result.actions().contains("START"));
        // A loaded, stopped plan does not read the selection.
        var loaded = evaluate(facts().state(SupervisorState.STOPPED).selectionProblem("ignored").build());
        assertTrue(loaded.actions().contains("START"));
    }

    private static ControlAvailability.Result evaluate(ControlAvailability.Facts facts) {
        return ControlAvailability.evaluate(facts);
    }

    private static Builder facts() {
        return new Builder();
    }

    /** A connected, idle, paired client with no plan loaded and nothing in progress. */
    private static final class Builder {
        private boolean closed;
        private boolean pausable;
        private boolean connected = true;
        private boolean contextMatches = true;
        private String runtimeWorkBlocker = "";
        private String automationBlocker = "";
        private boolean reconciliationRequired;
        private boolean tokenConfigured = true;
        private String takeoffActivity = "";
        private String shoppingActivity = "";
        private boolean depotMaintenanceIdle = true;
        private boolean scansAllowed = true;
        private java.util.function.Supplier<String> capacity = () -> "";
        private SupervisorState state;
        private boolean preparing;
        private BuildAccessPreflight.Decision flight = FLYING;
        private String selectionProblem = "";
        private String lastRuntimeError = "";
        private String statusError = "";

        Builder closed(boolean value) { closed = value; return this; }
        Builder pausable(boolean value) { pausable = value; return this; }
        Builder connected(boolean value) { connected = value; return this; }
        Builder contextMatches(boolean value) { contextMatches = value; return this; }
        Builder runtimeWorkBlocker(String value) { runtimeWorkBlocker = value; return this; }
        Builder automationBlocker(String value) { automationBlocker = value; return this; }
        Builder reconciliationRequired(boolean value) { reconciliationRequired = value; return this; }
        Builder tokenConfigured(boolean value) { tokenConfigured = value; return this; }
        Builder takeoffActivity(String value) { takeoffActivity = value; return this; }
        Builder shoppingActivity(String value) { shoppingActivity = value; return this; }
        Builder depotMaintenanceIdle(boolean value) { depotMaintenanceIdle = value; return this; }
        Builder scansAllowed(boolean value) { scansAllowed = value; return this; }
        Builder capacityProblem(String value) { capacity = () -> value; return this; }
        Builder capacitySupplier(java.util.function.Supplier<String> value) { capacity = value; return this; }
        Builder state(SupervisorState value) { state = value; return this; }
        Builder preparing(boolean value) { preparing = value; return this; }
        Builder flight(BuildAccessPreflight.Decision value) { flight = value; return this; }
        Builder selectionProblem(String value) { selectionProblem = value; return this; }
        Builder lastRuntimeError(String value) { lastRuntimeError = value; return this; }
        Builder statusError(String value) { statusError = value; return this; }

        ControlAvailability.Facts build() {
            return new ControlAvailability.Facts(closed, pausable, connected, contextMatches, runtimeWorkBlocker,
                    automationBlocker, reconciliationRequired, tokenConfigured, takeoffActivity, "",
                    shoppingActivity, depotMaintenanceIdle, () -> scansAllowed, capacity, state, preparing,
                    () -> flight, () -> selectionProblem, lastRuntimeError, statusError);
        }
    }
}
