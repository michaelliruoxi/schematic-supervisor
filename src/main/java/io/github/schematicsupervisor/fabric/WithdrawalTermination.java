package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.RestockTransferStatus;
import java.util.Objects;

/**
 * How an ending depot withdrawal reports itself. Capacity and unreachable outcomes let the supervisor
 * plan again without Reset, so they are reported only when cleanup left nothing behind: no blocked
 * automation, no pending click, no item on the cursor, and no cleanup problem.
 */
final class WithdrawalTermination {
    record Facts(
            DepotCancellationPolicy.Termination requested,
            String detail,
            String cleanupProblem,
            boolean capacityRejected,
            boolean unreachable,
            boolean automationBlocked,
            boolean actionPending,
            boolean cursorHeld
    ) {
        Facts {
            Objects.requireNonNull(requested, "requested");
            detail = detail == null ? "" : detail;
            cleanupProblem = cleanupProblem == null ? "" : cleanupProblem;
        }
    }

    record Outcome(RestockTransferStatus status, String detail) { }

    private WithdrawalTermination() {
    }

    static Outcome settle(Facts facts) {
        boolean clean = facts.cleanupProblem().isEmpty();
        if (facts.requested() != DepotCancellationPolicy.Termination.FAIL) {
            return new Outcome(RestockTransferStatus.IDLE, facts.cleanupProblem());
        }
        boolean settled = clean && !facts.automationBlocked() && !facts.actionPending() && !facts.cursorHeld();
        boolean capacity = WithdrawalCapacityPolicy.settledCapacityRejection(facts.capacityRejected(), clean,
                facts.automationBlocked(), facts.actionPending(), facts.cursorHeld());
        RestockTransferStatus status = capacity ? RestockTransferStatus.CAPACITY_BLOCKED
                : facts.unreachable() && settled ? RestockTransferStatus.UNREACHABLE
                : RestockTransferStatus.FAILED;
        return new Outcome(status, clean ? facts.detail() : facts.detail() + "; " + facts.cleanupProblem());
    }

    /**
     * A route that found no way to the chest opened nothing, so the depot is reported unreachable and
     * named. Interference, such as movement input or lost flight, is not a missing route.
     */
    static String routeFailureDetail(String depotId, String detail, boolean unreachable) {
        return unreachable ? depotId + " is unreachable: " + detail : detail;
    }
}
