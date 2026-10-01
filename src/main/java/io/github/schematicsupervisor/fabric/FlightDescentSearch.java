package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Searches a received horizontal clearance plane for a safe descent through every lower barrier. */
final class FlightDescentSearch {
    enum FailureReason { NONE, INVALID_START, NO_ROUTE, NODE_LIMIT }
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private final int targetY;
    private final int maximumNodes;
    private final Predicate<BlockPosition> withinBounds;
    private final Predicate<BlockPosition> received;
    private final Predicate<BlockPosition> clearBody;
    private final BiPredicate<BlockPosition, BlockPosition> clearSegment;
    private final Predicate<BlockPosition> untriedFrontier;
    private final ArrayDeque<BlockPosition> queue = new ArrayDeque<>();
    private final Map<BlockPosition, BlockPosition> parents = new HashMap<>();
    private List<BlockPosition> path;
    private BlockPosition frontierPosition;
    private BlockPosition frontierRequest;
    private boolean failed;
    private FailureReason failureReason = FailureReason.NONE;
    private boolean needsChunks;
    private int inspected;
    private String terminalDetail = "";

    FlightDescentSearch(BlockPosition start, int targetY, int maximumNodes,
                        Predicate<BlockPosition> withinBounds, Predicate<BlockPosition> received,
                        Predicate<BlockPosition> clearBody,
                        BiPredicate<BlockPosition, BlockPosition> clearSegment,
                        Predicate<BlockPosition> untriedFrontier) {
        if (targetY >= start.y() || maximumNodes < 1) {
            throw new IllegalArgumentException("descent search requires a lower target and positive node bound");
        }
        this.targetY = targetY;
        this.maximumNodes = maximumNodes;
        this.withinBounds = withinBounds;
        this.received = received;
        this.clearBody = clearBody;
        this.clearSegment = clearSegment;
        this.untriedFrontier = untriedFrontier;
        if (!withinBounds.test(start) || !received.test(start) || !clearBody.test(start)) {
            fail(FailureReason.INVALID_START, "The descent-search starting cell is obstructed or unreceived.");
        } else {
            parents.put(start, null);
            queue.add(start);
        }
    }

    void tick(int budget) {
        if (budget < 1) { throw new IllegalArgumentException("descent search budget must be positive"); }
        for (int count = 0; count < budget && !complete() && !failed; count++) {
            BlockPosition position = queue.pollFirst();
            if (position == null) {
                if (frontierPosition != null) {
                    path = reconstruct(frontierPosition);
                    needsChunks = true;
                    terminalDetail = "Moving to a received boundary to inspect additional descent columns.";
                } else {
                    fail(FailureReason.NO_ROUTE, "No received clear descent column or new reachable chunk boundary was found.");
                }
                return;
            }
            inspected++;
            BlockPosition exit = new BlockPosition(position.x(), targetY, position.z());
            if (withinBounds.test(exit) && received.test(exit) && clearSegment.test(position, exit)) {
                ArrayList<BlockPosition> result = new ArrayList<>(reconstruct(position));
                for (int y = position.y() - 1; y >= targetY; y--) {
                    result.add(new BlockPosition(position.x(), y, position.z()));
                }
                path = List.copyOf(result);
                terminalDetail = "Found a received clear descent column after " + inspected
                        + " horizontal cells; exit=(" + exit.x() + "," + exit.y() + "," + exit.z() + ").";
                return;
            }
            for (int[] direction : DIRECTIONS) {
                BlockPosition neighbor = new BlockPosition(position.x() + direction[0], position.y(),
                        position.z() + direction[1]);
                if (!withinBounds.test(neighbor)) { continue; }
                if (!received.test(neighbor)) {
                    if (frontierPosition == null && untriedFrontier.test(neighbor)) {
                        frontierPosition = position;
                        frontierRequest = neighbor;
                    }
                    continue;
                }
                if (parents.containsKey(neighbor) || !clearBody.test(neighbor)
                        || !clearSegment.test(position, neighbor)) { continue; }
                if (parents.size() >= maximumNodes) {
                    fail(FailureReason.NODE_LIMIT, "Descent-column search reached its bounded node limit.");
                    return;
                }
                parents.put(neighbor, position);
                queue.addLast(neighbor);
            }
        }
    }

    boolean complete() { return path != null; }
    boolean failed() { return failed; }
    FailureReason failureReason() { return failureReason; }
    boolean needsChunks() { return needsChunks; }
    int inspected() { return inspected; }
    int discoveredNodes() { return parents.size(); }
    BlockPosition requestedFrontier() { return frontierRequest; }
    List<BlockPosition> path() {
        if (!complete()) { throw new IllegalStateException("descent search has not completed"); }
        return path;
    }
    String detail() {
        return (terminalDetail.isBlank() ? "Searching the received floor plane for a clear descent column." : terminalDetail)
                + " Checked=" + inspected + ", discovered=" + parents.size() + "/" + maximumNodes + ".";
    }
    private List<BlockPosition> reconstruct(BlockPosition end) {
        ArrayList<BlockPosition> result = new ArrayList<>();
        for (BlockPosition cursor = end; cursor != null; cursor = parents.get(cursor)) { result.add(cursor); }
        Collections.reverse(result);
        return List.copyOf(result);
    }
    private void fail(FailureReason kind, String reason) {
        failed = true;
        failureReason = kind;
        terminalDetail = reason;
        queue.clear();
    }
}
