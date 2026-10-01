package io.github.schematicsupervisor.fabric;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates idempotent checkpoint and run-context cleanup after loaded adapters are invalidated.
 */
final class ResetTeardownCoordinator {
    private final ClearAction clearCheckpoint;
    private final ClearAction clearRunContext;

    private boolean cleanupRequired;
    private String detail = "";

    ResetTeardownCoordinator(
            ClearAction clearCheckpoint,
            ClearAction clearRunContext
    ) {
        this.clearCheckpoint = Objects.requireNonNull(
                clearCheckpoint,
                "clearCheckpoint"
        );
        this.clearRunContext = Objects.requireNonNull(
                clearRunContext,
                "clearRunContext"
        );
    }

    void begin() {
        cleanupRequired = true;
        detail = resetRequiredDetail("loaded plan teardown has started");
    }

    void recordIncomplete(String failureDetail) {
        Objects.requireNonNull(failureDetail, "failureDetail");
        if (failureDetail.isBlank()) {
            throw new IllegalArgumentException("failureDetail must not be blank");
        }
        cleanupRequired = true;
        detail = resetRequiredDetail(failureDetail);
    }

    Result clearStores() {
        if (!cleanupRequired) {
            throw new IllegalStateException("reset teardown has not started");
        }
        List<String> failures = new ArrayList<>();
        run("checkpoint", clearCheckpoint, failures);
        run("run context", clearRunContext, failures);
        if (!failures.isEmpty()) {
            recordIncomplete(String.join("; ", failures));
            return Result.pending(detail);
        }
        cleanupRequired = false;
        detail = "";
        return Result.completed();
    }

    boolean cleanupRequired() {
        return cleanupRequired;
    }

    boolean runtimeWorkAllowed() {
        return !cleanupRequired;
    }

    String detail() {
        return detail;
    }

    private static void run(
            String label,
            ClearAction action,
            List<String> failures
    ) {
        try {
            action.run();
        } catch (IOException | RuntimeException exception) {
            failures.add(label + " clear failed: " + conciseMessage(exception));
        }
    }

    private static String resetRequiredDetail(String failureDetail) {
        return "Reset teardown is incomplete: " + failureDetail
                + ". Reset is required before starting or resuming.";
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    @FunctionalInterface
    interface ClearAction {
        void run() throws IOException;
    }

    record Result(boolean complete, String detail) {
        Result {
            Objects.requireNonNull(detail, "detail");
            if (complete && !detail.isEmpty()) {
                throw new IllegalArgumentException(
                        "completed teardown must not contain a detail"
                );
            }
            if (!complete && detail.isBlank()) {
                throw new IllegalArgumentException(
                        "incomplete teardown requires a detail"
                );
            }
        }

        private static Result completed() {
            return new Result(true, "");
        }

        private static Result pending(String detail) {
            return new Result(false, detail);
        }
    }
}
