package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Best-effort Baritone control-loss orchestration with a one-way fail-closed latch.
 */
final class BaritoneCancellationGuard {
    private final Runnable prepareForCancellation;
    private final Runnable cancelEverything;
    private final Runnable forceCancel;
    private final Runnable loseBuilderControl;
    private final Runnable loseCustomGoalControl;
    private final Runnable restoreSettings;

    private boolean blocked;
    private String blockedDetail = "";

    BaritoneCancellationGuard(
            Runnable prepareForCancellation,
            Runnable cancelEverything,
            Runnable forceCancel,
            Runnable loseBuilderControl,
            Runnable loseCustomGoalControl,
            Runnable restoreSettings
    ) {
        this.prepareForCancellation = Objects.requireNonNull(
                prepareForCancellation,
                "prepareForCancellation"
        );
        this.cancelEverything = Objects.requireNonNull(
                cancelEverything,
                "cancelEverything"
        );
        this.forceCancel = Objects.requireNonNull(forceCancel, "forceCancel");
        this.loseBuilderControl = Objects.requireNonNull(
                loseBuilderControl,
                "loseBuilderControl"
        );
        this.loseCustomGoalControl = Objects.requireNonNull(
                loseCustomGoalControl,
                "loseCustomGoalControl"
        );
        this.restoreSettings = Objects.requireNonNull(restoreSettings, "restoreSettings");
    }

    boolean stop(String context) {
        requireDetail(context, "context");
        if (blocked) {
            return false;
        }
        Attempt attempt = attempt();
        if (attempt.success()) {
            return true;
        }
        latch(context + ": " + attempt.detail());
        return false;
    }

    boolean retryForReset() {
        if (!blocked) {
            return true;
        }
        Attempt attempt = attempt();
        if (!attempt.success()) {
            latch("reset retry: " + attempt.detail());
            return false;
        }
        blocked = false;
        blockedDetail = "";
        return true;
    }

    boolean blocked() {
        return blocked;
    }

    String blockedDetail() {
        return blockedDetail;
    }

    private Attempt attempt() {
        List<String> failures = new ArrayList<>();
        run("prepareForCancellation", prepareForCancellation, failures);
        run("cancelEverything", cancelEverything, failures);
        run("forceCancel", forceCancel, failures);
        run("builderOnLostControl", loseBuilderControl, failures);
        run("customGoalOnLostControl", loseCustomGoalControl, failures);
        if (failures.isEmpty()) {
            run("restoreSettings", restoreSettings, failures);
        }
        return failures.isEmpty()
                ? new Attempt(true, "")
                : new Attempt(false, String.join("; ", failures));
    }

    private void latch(String detail) {
        requireDetail(detail, "detail");
        blocked = true;
        if (blockedDetail.isBlank()) {
            blockedDetail = detail + ". Reset is required before further automation.";
        } else if (!blockedDetail.contains(detail)) {
            blockedDetail += " " + detail;
        }
    }

    private static void run(String label, Runnable action, List<String> failures) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            failures.add(label + " failed: " + conciseMessage(exception));
        }
    }

    private static void requireDetail(String detail, String name) {
        Objects.requireNonNull(detail, name);
        if (detail.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private record Attempt(boolean success, String detail) {
    }
}
