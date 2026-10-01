package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.HashSet;
import java.util.Set;

/** A single route keeps its work limits when a later interaction rejects an arrival cell. */
final class FlightRouteAttempt {
    private final int maximumNodes;
    private final int maximumTicks;
    private final int maximumExcludedArrivals;
    private final Set<BlockPosition> excludedArrivals = new HashSet<>();
    private int nodesUsed;
    private int activeTicks;

    FlightRouteAttempt(int maximumNodes, int maximumTicks, int maximumExcludedArrivals) {
        if (maximumNodes < 1 || maximumTicks < 1 || maximumExcludedArrivals < 1) {
            throw new IllegalArgumentException("route attempt limits must be positive");
        }
        this.maximumNodes = maximumNodes;
        this.maximumTicks = maximumTicks;
        this.maximumExcludedArrivals = maximumExcludedArrivals;
    }

    boolean tick() { return ++activeTicks <= maximumTicks; }
    boolean durationAvailable() { return activeTicks < maximumTicks; }
    int remainingNodes() { return maximumNodes - nodesUsed; }
    int nodesUsed() { return nodesUsed; }
    int activeTicks() { return activeTicks; }
    int excludedCount() { return excludedArrivals.size(); }

    void accountNodes(int discovered) {
        if (discovered < 0 || discovered > remainingNodes()) {
            throw new IllegalArgumentException("route search exceeded its shared node budget");
        }
        nodesUsed += discovered;
    }

    boolean permitsArrival(BlockPosition position) { return !excludedArrivals.contains(position); }

    boolean excludeArrival(BlockPosition position) {
        if (excludedArrivals.size() >= maximumExcludedArrivals || excludedArrivals.contains(position)) {
            return false;
        }
        excludedArrivals.add(position);
        return true;
    }
}
