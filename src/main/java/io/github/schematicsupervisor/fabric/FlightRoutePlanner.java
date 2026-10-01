package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

/** Bounded weighted A* over collision-free, loaded player feet positions; shortest paths are optional. */
final class FlightRoutePlanner {
    private static final double DEFAULT_HEURISTIC_WEIGHT = 1.5;
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private final BlockPosition start;
    private final BlockPosition target;
    private final int maximumDistance;
    private final int maximumNodes;
    private final double goalAllowance;
    private final double heuristicWeight;
    private final Predicate<BlockPosition> traversable;
    private final Predicate<BlockPosition> goal;
    private final BiPredicate<BlockPosition, BlockPosition> clearSegment;
    private final PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator
            .comparingDouble(Node::estimatedTotal).thenComparingInt(Node::remaining)
            .thenComparingLong(Node::sequence));
    private final Map<BlockPosition, Integer> costs = new HashMap<>();
    private final Map<BlockPosition, BlockPosition> parents = new HashMap<>();
    private final Map<BlockPosition, Boolean> spaces = new HashMap<>();
    private List<BlockPosition> path;
    private boolean failed;
    private boolean startObstructed;
    private String detail = "Planning a loaded collision-free flight route.";
    private long sequence;
    private int expandedNodes;
    private BlockPosition closestPosition;
    private long closestDistanceSquared = Long.MAX_VALUE;

    FlightRoutePlanner(BlockPosition start, BlockPosition target, double goalRadius,
                       int maximumDistance, int maximumNodes,
                       Predicate<BlockPosition> traversable, Predicate<BlockPosition> goal) {
        this(start, target, goalRadius, maximumDistance, maximumNodes, traversable, goal,
                (from, to) -> true);
    }

    FlightRoutePlanner(BlockPosition start, BlockPosition target, double goalRadius,
                       int maximumDistance, int maximumNodes,
                       Predicate<BlockPosition> traversable, Predicate<BlockPosition> goal,
                       BiPredicate<BlockPosition, BlockPosition> clearSegment) {
        this(start, target, goalRadius, maximumDistance, maximumNodes, traversable, goal,
                clearSegment, DEFAULT_HEURISTIC_WEIGHT);
    }

    FlightRoutePlanner(BlockPosition start, BlockPosition target, double goalRadius,
                       int maximumDistance, int maximumNodes,
                       Predicate<BlockPosition> traversable, Predicate<BlockPosition> goal,
                       BiPredicate<BlockPosition, BlockPosition> clearSegment,
                       double heuristicWeight) {
        this.clearSegment = Objects.requireNonNull(clearSegment, "clearSegment");
        this.start = Objects.requireNonNull(start, "start");
        this.target = Objects.requireNonNull(target, "target");
        this.traversable = Objects.requireNonNull(traversable, "traversable");
        this.goal = Objects.requireNonNull(goal, "goal");
        if (!Double.isFinite(goalRadius) || goalRadius < 0 || maximumDistance < 1
                || maximumNodes < 1 || !Double.isFinite(heuristicWeight) || heuristicWeight < 1) {
            throw new IllegalArgumentException("flight search limits must be finite and positive");
        }
        this.maximumDistance = maximumDistance;
        this.maximumNodes = maximumNodes;
        this.heuristicWeight = heuristicWeight;
        goalAllowance = Math.ceil(goalRadius * Math.sqrt(3));
        if (distanceSquared(start, target) > Math.pow(maximumDistance + goalRadius, 2)) {
            fail("Flight target exceeds the bounded route distance.");
        } else if (!isTraversable(start)) {
            startObstructed = true;
            fail("The starting flight cell is obstructed or not loaded.");
        } else {
            costs.put(start, 0);
            offer(start, 0);
        }
    }

    void tick(int expansionBudget) {
        if (expansionBudget < 1) { throw new IllegalArgumentException("search budget must be positive"); }
        for (int visited = 0; visited < expansionBudget && !complete() && !failed; visited++) {
            Node next = frontier.poll();
            if (next == null) {
                fail("No loaded collision-free flight route reaches this target.");
                return;
            }
            if (costs.get(next.position()) != next.cost()) { continue; }
            expandedNodes++;
            long remainingSquared = distanceSquared(next.position(), target);
            if (remainingSquared < closestDistanceSquared) {
                closestDistanceSquared = remainingSquared;
                closestPosition = next.position();
            }
            if (goal.test(next.position())) {
                finish(next.position());
                return;
            }
            for (int[] direction : DIRECTIONS) {
                BlockPosition neighbor = new BlockPosition(
                        Math.addExact(next.position().x(), direction[0]),
                        Math.addExact(next.position().y(), direction[1]),
                        Math.addExact(next.position().z(), direction[2]));
                if (distanceSquared(start, neighbor) > (long) maximumDistance * maximumDistance
                        || !isTraversable(neighbor)) { continue; }
                int candidateCost = next.cost() + 1;
                Integer previous = costs.get(neighbor);
                if (previous != null && previous <= candidateCost) { continue; }
                if (!clearSegment.test(next.position(), neighbor)) { continue; }
                if (previous == null && costs.size() >= maximumNodes) {
                    fail("Flight route search reached its bounded node limit.");
                    return;
                }
                costs.put(neighbor, candidateCost);
                parents.put(neighbor, next.position());
                offer(neighbor, candidateCost);
            }
        }
    }

    boolean complete() { return path != null; }
    boolean failed() { return failed; }
    /** The player's own cell is blocked or unloaded, so no route from here can exist to any target. */
    boolean startObstructed() { return startObstructed; }
    String detail() {
        return complete() || failed ? detail : "Planning flight route: " + searchProgress();
    }
    int expandedNodes() { return expandedNodes; }
    int discoveredNodes() { return costs.size(); }

    List<BlockPosition> path() {
        if (!complete()) { throw new IllegalStateException("flight route has not completed"); }
        return path;
    }

    /** The searched route to the expanded cell nearest the target; empty when nothing was expanded. */
    List<BlockPosition> pathToClosest() {
        return closestPosition == null ? List.of() : routeTo(closestPosition);
    }

    private boolean isTraversable(BlockPosition position) {
        return spaces.computeIfAbsent(position, traversable::test);
    }

    private void offer(BlockPosition position, int cost) {
        int distance = Math.abs(position.x() - target.x())
                + Math.abs(position.y() - target.y()) + Math.abs(position.z() - target.z());
        frontier.add(new Node(position, cost,
                cost + heuristicWeight * Math.max(0, distance - goalAllowance),
                distance, sequence++));
    }

    private List<BlockPosition> routeTo(BlockPosition end) {
        List<BlockPosition> route = new ArrayList<>();
        for (BlockPosition cursor = end; cursor != null; cursor = parents.get(cursor)) {
            route.add(cursor);
        }
        Collections.reverse(route);
        return List.copyOf(route);
    }

    private void finish(BlockPosition end) {
        path = routeTo(end);
        detail = "Flight route planned with " + path.size() + " bounded waypoints; " + searchProgress();
        frontier.clear();
        spaces.clear();
    }

    private void fail(String reason) {
        failed = true;
        detail = reason + " " + searchProgress();
        frontier.clear();
    }

    private String searchProgress() {
        String nearest = closestPosition == null ? "none" : "(" + closestPosition.x() + ","
                + closestPosition.y() + "," + closestPosition.z() + "), distance "
                + Math.round(Math.sqrt(closestDistanceSquared) * 10.0) / 10.0;
        return "expanded=" + expandedNodes + ", discovered=" + costs.size() + "/" + maximumNodes
                + ", frontier=" + frontier.size() + ", nearest=" + nearest + ".";
    }

    private static long distanceSquared(BlockPosition left, BlockPosition right) {
        long x = (long) left.x() - right.x();
        long y = (long) left.y() - right.y();
        long z = (long) left.z() - right.z();
        return x * x + y * y + z * z;
    }

    private record Node(BlockPosition position, int cost, double estimatedTotal,
                        int remaining, long sequence) { }
}
