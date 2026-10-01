package io.github.schematicsupervisor.fabric;

import java.util.Objects;

/** Retains the loaded identity until adapters and settings finish their non-destructive teardown. */
final class BuildUnloadCoordinator {
    private boolean cleanupRequired;
    private boolean adaptersClosed;
    private boolean settingsRestored;
    private String detail = "";

    boolean attempt(Runnable closeAdapters, Runnable restoreSettings, Runnable releaseIdentity) {
        Objects.requireNonNull(closeAdapters, "closeAdapters");
        Objects.requireNonNull(restoreSettings, "restoreSettings");
        Objects.requireNonNull(releaseIdentity, "releaseIdentity");
        cleanupRequired = true;
        try {
            if (!adaptersClosed) {
                closeAdapters.run();
                adaptersClosed = true;
            }
            if (!settingsRestored) {
                restoreSettings.run();
                settingsRestored = true;
            }
            releaseIdentity.run();
            cleanupRequired = false;
            adaptersClosed = false;
            settingsRestored = false;
            detail = "";
            return true;
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            detail = "Build unload is incomplete: "
                    + (message == null || message.isBlank() ? failure.getClass().getSimpleName() : message)
                    + ". The loaded identity and saved files are retained; retry Unload before other work.";
            return false;
        }
    }

    boolean cleanupRequired() { return cleanupRequired; }

    String detail() { return detail; }
}
