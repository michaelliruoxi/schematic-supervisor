package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Passive client observations, never evidence that a server random tick occurred. */
record SoilWatchpointObservation(boolean available, String reason, String planId,
                                 RunContext context, String worldEpoch, Instant sampledAt,
                                 List<Point> points) {
    static final int MAX_POINTS = 4;
    static final int MAX_HISTORY = 8;

    SoilWatchpointObservation {
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > 128 || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("Soil observation bounds exceeded");
        }
        points = List.copyOf(points);
    }

    record Sample(Instant at, String status, boolean received, Boolean predictionPending,
                  boolean aboveReceived, Boolean abovePredictionPending,
                  BlockState actual, BlockState above, Integer moisture,
                  boolean environmentComplete, Boolean nearbyWater, Boolean rainAtAbove) {
        Sample {
            Objects.requireNonNull(at, "at");
            if (!List.of("FRESH", "UNLOADED", "PREDICTION_PENDING", "CONTEXT_UNAVAILABLE",
                    "READ_FAILED").contains(status)) {
                throw new IllegalArgumentException("Unknown soil sample status");
            }
            requireBounded(actual);
            requireBounded(above);
            if (status.equals("FRESH") && (!received || !aboveReceived
                    || !Boolean.FALSE.equals(predictionPending) || !Boolean.FALSE.equals(abovePredictionPending)
                    || actual == null || above == null)) {
                throw new IllegalArgumentException("Fresh soil evidence requires received, unpredicted states");
            }
            if (!environmentComplete && (nearbyWater != null || rainAtAbove != null)) {
                throw new IllegalArgumentException("Incomplete environment cannot report absence");
            }
            if (environmentComplete && (nearbyWater == null || rainAtAbove == null)) {
                throw new IllegalArgumentException("Complete environment requires both bounded facts");
            }
        }

        boolean fresh() { return status.equals("FRESH"); }

        private static void requireBounded(BlockState state) {
            if (state == null) { return; }
            if (state.blockId().length() > 128 || state.properties().size() > 16
                    || state.properties().entrySet().stream().anyMatch(entry ->
                    entry.getKey().length() > 64 || entry.getValue().length() > 64)) {
                throw new IllegalArgumentException("Soil block state exceeds telemetry bounds");
            }
        }
    }

    record Point(BlockPosition position, Sample firstFresh, Sample lastFresh, Sample latest,
                 long freshSamples, long changes, boolean continuityUnknown,
                 List<Sample> history, boolean historyTruncated) {
        Point {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(firstFresh, "firstFresh");
            Objects.requireNonNull(lastFresh, "lastFresh");
            Objects.requireNonNull(latest, "latest");
            if (history.size() > MAX_HISTORY) { throw new IllegalArgumentException("Soil history exceeds bounds"); }
            history = List.copyOf(history);
        }
    }
}
