package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Which controls the observation offers and what blocks the others. The monitor's buttons and the
 * runner's decisions follow this, so it is computed from plain facts here and tested on its own.
 */
final class ControlAvailability {
    static final String CLOSED = "The supervisor runtime is closed.";
    static final String JOIN_WORLD = "Join the target world before starting or resuming.";
    static final String OTHER_WORLD = "The loaded run belongs to a different server, save, or dimension.";
    static final String RECONCILE = "The checkpoint requires reconciliation; use an explicit reset before restarting.";
    static final String NO_TOKEN = "A local protocol token is required to activate automation.";
    static final String SCANNING = "Wait for registered-depot scans to finish.";
    static final String CANCEL_SUFFIX = " Use Pause or Stop to cancel.";

    /**
     * Blank strings mean "no such condition". Suppliers are read only when the original order of the
     * checks reaches them, because some read live inventory and selection state.
     *
     * @param state the loaded plan's state, or null with no plan loaded
     * @param preparing a placement is loading or the start build check is running
     */
    record Facts(
            boolean closed,
            boolean pausable,
            boolean connected,
            boolean contextMatches,
            String runtimeWorkBlocker,
            String automationBlocker,
            boolean reconciliationRequired,
            boolean tokenConfigured,
            String takeoffActivity,
            String approachActivity,
            String shoppingActivity,
            boolean depotMaintenanceIdle,
            BooleanSupplier depotScansAllowed,
            Supplier<String> capacityProblem,
            SupervisorState state,
            boolean preparing,
            Supplier<BuildAccessPreflight.Decision> flight,
            Supplier<String> selectionProblem,
            String lastRuntimeError,
            String statusError
    ) {
        Facts {
            runtimeWorkBlocker = blankIfNull(runtimeWorkBlocker);
            automationBlocker = blankIfNull(automationBlocker);
            takeoffActivity = blankIfNull(takeoffActivity);
            approachActivity = blankIfNull(approachActivity);
            shoppingActivity = blankIfNull(shoppingActivity);
            lastRuntimeError = blankIfNull(lastRuntimeError);
            statusError = blankIfNull(statusError);
            Objects.requireNonNull(depotScansAllowed, "depotScansAllowed");
            Objects.requireNonNull(capacityProblem, "capacityProblem");
            Objects.requireNonNull(flight, "flight");
            Objects.requireNonNull(selectionProblem, "selectionProblem");
        }
    }

    record Result(List<String> blockers, List<String> actions, String error) {
        Result {
            blockers = List.copyOf(blockers);
            actions = List.copyOf(actions);
        }

        boolean ready() {
            return blockers.isEmpty() && (actions.contains("START") || actions.contains("RESUME"));
        }
    }

    private ControlAvailability() {
    }

    static Result evaluate(Facts facts) {
        List<String> blockers = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (facts.closed()) {
            blockers.add(CLOSED);
        } else {
            actions.add("STOP");
            if (facts.pausable()) {
                actions.add("PAUSE");
            }
        }
        if (!facts.connected()) {
            blockers.add(JOIN_WORLD);
        } else if (!facts.contextMatches()) {
            blockers.add(OTHER_WORLD);
        }
        addIfPresent(blockers, facts.runtimeWorkBlocker());
        addIfPresent(blockers, facts.automationBlocker());
        if (facts.reconciliationRequired()) {
            blockers.add(RECONCILE);
        }
        if (!facts.tokenConfigured()) {
            blockers.add(NO_TOKEN);
        }
        if (!facts.takeoffActivity().isBlank()) {
            blockers.add(facts.takeoffActivity() + CANCEL_SUFFIX);
        }
        if (!facts.approachActivity().isBlank()) {
            blockers.add(facts.approachActivity() + CANCEL_SUFFIX);
        }
        if (!facts.shoppingActivity().isBlank()) {
            blockers.add(facts.shoppingActivity() + CANCEL_SUFFIX);
        } else if (!facts.depotMaintenanceIdle()) {
            blockers.add(SCANNING);
        }
        boolean baseReady = blockers.isEmpty();
        if (baseReady && facts.depotScansAllowed().getAsBoolean()) {
            actions.add("SCAN_DEPOTS");
        }
        String capacityProblem = "";
        if (baseReady) {
            capacityProblem = blankIfNull(facts.capacityProblem().get());
            if (!capacityProblem.isBlank()) {
                blockers.add(capacityProblem);
                baseReady = false;
            }
        }
        boolean mayStart = !facts.preparing() && (facts.state() == null || facts.state() == SupervisorState.STOPPED);
        boolean mayResume = facts.state() == SupervisorState.PAUSED;
        if (baseReady && (mayStart || mayResume)) {
            // A standing player with server flight may still start or resume; the mod takes off first.
            BuildAccessPreflight.Decision flight = facts.flight().get();
            if (!flight.offerable()) {
                blockers.add(flight.detail());
            }
            if (facts.state() == null) {
                addIfPresent(blockers, facts.selectionProblem().get());
            }
            if (blockers.isEmpty()) {
                actions.add(mayResume ? "RESUME" : "START");
            }
        }
        String error = !capacityProblem.isBlank() ? capacityProblem
                : !facts.lastRuntimeError().isBlank() ? facts.lastRuntimeError()
                : facts.statusError();
        return new Result(blockers, actions, error);
    }

    private static void addIfPresent(List<String> blockers, String blocker) {
        if (blocker != null && !blocker.isBlank()) {
            blockers.add(blocker);
        }
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
