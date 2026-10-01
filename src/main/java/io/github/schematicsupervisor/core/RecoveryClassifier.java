package io.github.schematicsupervisor.core;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a recovery cause may be retried automatically after a wait. Only causes known to
 * come from lag, slow chunk delivery, or a moving entity qualify; anything else, including any cause
 * that mentions player input, flight loss, a server correction, or reconciliation, still pauses.
 */
public final class RecoveryClassifier {
    private static final List<String> NEVER_RETRIED = List.of(
            "manual movement input",
            "manual interaction",
            "server moved the player",
            "will not overwrite an occupied target",
            "reconciliation",
            "reset is required",
            "did not agree",
            "rejected",
            "flight was lost",
            "active flight is required",
            "interaction manager");
    private static final List<String> TRANSIENT = List.of(
            "did not confirm",
            "became obstructed or unloaded",
            "made no confirmed block-state progress",
            "no confirmed block-state progress",
            "no build progress for",
            "bounded node limit",
            "chunk-receipt approach expired",
            "could not receive the layer chunk",
            "occupies or stands on",
            "path ended before the interaction target was reached",
            "failed after bounded attempts",
            "was not confirmed by a world-state transition");

    private RecoveryClassifier() {
    }

    public static boolean transientFailure(String cause) {
        if (cause == null || cause.isBlank()) {
            return false;
        }
        String text = cause.toLowerCase(Locale.ROOT);
        for (String marker : NEVER_RETRIED) {
            if (text.contains(marker)) {
                return false;
            }
        }
        for (String marker : TRANSIENT) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
