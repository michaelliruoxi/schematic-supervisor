package io.github.schematicsupervisor.fabric;

/** Prefer a face usable without movement, retaining nearest-distance fallback and stable ties. */
final class FlightPlacementPreference {
    private FlightPlacementPreference() { }

    static boolean prefer(boolean currentPosition, double distance,
                          boolean bestCurrentPosition, double bestDistance) {
        return currentPosition != bestCurrentPosition ? currentPosition : distance < bestDistance;
    }
}
