package io.github.schematicsupervisor.fabric;

import java.util.Objects;

final class BuildAccessPreflight {
    static final String FLIGHT_NOT_GRANTED =
            "This floating schematic needs flight, and the server has not granted it. "
                    + "Turn on your server flight ability, then try again.";

    private BuildAccessPreflight() {
    }

    /**
     * A floating build needs active flight. When the server already allows flight, Start and Resume take
     * off first; the mod never grants flight itself.
     */
    static Decision forFloatingBuild(boolean existingFlightActive, boolean flightAllowed) {
        if (existingFlightActive) {
            return Decision.permit();
        }
        return flightAllowed ? Decision.takeOffFirst() : Decision.reject(FLIGHT_NOT_GRANTED);
    }

    record Decision(boolean allowed, boolean takeoffFirst, String detail) {
        Decision {
            Objects.requireNonNull(detail, "detail");
            if (allowed && takeoffFirst) {
                throw new IllegalArgumentException("a decision either permits or takes off first");
            }
            if ((allowed || takeoffFirst) && !detail.isEmpty()) {
                throw new IllegalArgumentException("only a rejected decision contains a detail");
            }
            if (!allowed && !takeoffFirst && detail.isBlank()) {
                throw new IllegalArgumentException("a rejected decision requires a detail");
            }
        }

        private static Decision permit() {
            return new Decision(true, false, "");
        }

        private static Decision takeOffFirst() {
            return new Decision(false, true, "");
        }

        private static Decision reject(String detail) {
            return new Decision(false, false, detail);
        }

        /** Start or Resume may be offered: either it runs now, or after a takeoff it starts itself. */
        boolean offerable() {
            return allowed || takeoffFirst;
        }
    }
}
