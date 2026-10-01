package io.github.schematicsupervisor.fabric;

/** Routine movement diagnostics without account, server, chat, or authentication data. */
record PlayerObservation(double x, double y, double z, boolean flying, boolean allowFlying,
                         boolean onGround, float health, int hunger, double blockInteractionReach,
                         String screenKind, boolean containerOpen, Background background) {
    PlayerObservation(double x, double y, double z, boolean flying, boolean allowFlying,
                      boolean onGround, float health, int hunger, double blockInteractionReach,
                      String screenKind, boolean containerOpen) {
        this(x, y, z, flying, allowFlying, onGround, health, hunger, blockInteractionReach,
                screenKind, containerOpen, null);
    }

    record Background(String screenClass, boolean windowFocused, boolean windowMinimized,
                      boolean worldActionsAllowed, boolean keepsWorldTicking) { }

    PlayerObservation {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(health) || !Double.isFinite(blockInteractionReach)
                || blockInteractionReach < 0 || hunger < 0
                || !java.util.List.of("none", "inventory", "container", "chat", "other").contains(screenKind)) {
            throw new IllegalArgumentException("invalid player observation facts");
        }
    }
}
