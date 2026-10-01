package io.github.schematicsupervisor.fabric;

/** Keeps an active builder ticking without changing the user's saved game options. */
final class BackgroundTickPolicy {
    private BackgroundTickPolicy() { }

    /** Owned depot, takeoff, or approach work also keeps ticking outside an active build state. */
    static boolean keepsWorldTicking(String state, boolean ownedWork, boolean worldConnected,
                                    boolean screenAllowsWork, boolean overlayOpen) {
        if (!worldConnected || !screenAllowsWork || overlayOpen) { return false; }
        if (ownedWork) { return true; }
        if (state == null) { return false; }
        return switch (state) {
            case "BUILDING", "RESTOCKING", "VERIFYING", "STUCK", "LOADING", "CHECKING" -> true;
            default -> false;
        };
    }

    static int frameLimit(int vanillaLimit, boolean keepTicking) {
        // A minimized or long-idle client otherwise renders at 10 FPS, batching client ticks.
        return keepTicking ? Math.max(20, vanillaLimit) : vanillaLimit;
    }

    static boolean suppressesAutomaticFocusMenu(String state, boolean ownedWork, boolean worldConnected,
                                               boolean screenAllowsWork, boolean overlayOpen,
                                               boolean windowFocused, boolean screenOpen) {
        return !windowFocused && !screenOpen
                && keepsWorldTicking(state, ownedWork, worldConnected, screenAllowsWork, overlayOpen);
    }
}
