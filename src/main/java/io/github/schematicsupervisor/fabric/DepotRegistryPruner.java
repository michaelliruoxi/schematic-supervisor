package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.DepotId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Removes confirmed missing chest locations only after the updated registry is saved. */
final class DepotRegistryPruner {
    private static final int CHECK_INTERVAL_TICKS = 20;

    private final Map<DepotId, RegisteredDepot> depots;
    private final Deque<DepotId> scanQueue;
    private final RegistryWriter writer;
    private int ticksUntilCheck;

    DepotRegistryPruner(Map<DepotId, RegisteredDepot> depots, Deque<DepotId> scanQueue,
                        RegistryWriter writer) {
        this.depots = Objects.requireNonNull(depots, "depots");
        this.scanQueue = Objects.requireNonNull(scanQueue, "scanQueue");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    List<DepotId> tick(RunContext context, boolean settled,
                       Function<RegisteredDepot, Presence> observe) throws IOException {
        if (context == null) {
            ticksUntilCheck = 0;
            return List.of();
        }
        if (!settled) { return List.of(); }
        if (ticksUntilCheck > 0) {
            ticksUntilCheck--;
            return List.of();
        }
        ticksUntilCheck = CHECK_INTERVAL_TICKS - 1;

        List<RegisteredDepot> retained = new ArrayList<>();
        List<DepotId> removed = new ArrayList<>();
        for (RegisteredDepot depot : depots.values()) {
            if (depot.matches(context) && observe.apply(depot).confirmedMissing()) {
                removed.add(depot.id());
            } else {
                retained.add(depot);
            }
        }
        if (removed.isEmpty()) { return List.of(); }

        // Keep live state and queued work intact if the atomic registry write fails.
        writer.save(List.copyOf(retained));
        removed.forEach(depots::remove);
        scanQueue.removeIf(removed::contains);
        return List.copyOf(removed);
    }

    record Presence(boolean received, boolean predictionPending, boolean chest) {
        boolean confirmedMissing() { return received && !predictionPending && !chest; }
    }

    @FunctionalInterface
    interface RegistryWriter {
        void save(List<RegisteredDepot> depots) throws IOException;
    }
}
