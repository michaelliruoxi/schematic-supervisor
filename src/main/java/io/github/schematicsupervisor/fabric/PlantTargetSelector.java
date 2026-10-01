package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.stream.IntStream;

/**
 * A bounded, stable-index planting sweep over sparse rows, finishing the visible patch first.
 * Nearby rows form one strip that is swept together; strips alternate direction.
 */
final class PlantTargetSelector {
    enum TargetState { COMPLETE, READY, PENDING, UNRECEIVED, INVALID }

    /** Rows at most this many blocks past a strip's first row are planted in the same pass. */
    static final int STRIP_SPAN = 3;
    /** How far past the first unfinished cell a move may aim along its strip. */
    static final int LOOK_AHEAD_BLOCKS = 4;

    private final List<BlockPosition> targets;
    private final int[] sweep;
    // Per target index: its strip, unique across floors, and its position along that strip's direction.
    private final int[] strip;
    private final long[] along;
    private int cursor;
    private Integer floor;
    private int fallback;
    private boolean fallbackReady;
    private int lookAhead;
    private int reachable;
    private int invalid;
    private boolean pending;
    private boolean complete;
    private double farthest;
    private int selected;
    // Kept across selections: each first unfinished cell gets one look-ahead move, never a chain of them.
    private int lookAheadUsedFor = -1;

    PlantTargetSelector(List<BlockPosition> targets) {
        this.targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        int[] sorted = IntStream.range(0, this.targets.size()).boxed().sorted(Comparator
                .comparingInt((Integer index) -> this.targets.get(index).y()).reversed()
                .thenComparingInt(index -> this.targets.get(index).x())
                .thenComparingInt(index -> this.targets.get(index).z())
                .thenComparingInt(Integer::intValue)).mapToInt(Integer::intValue).toArray();
        sweep = new int[sorted.length];
        strip = new int[sorted.length];
        along = new long[sorted.length];
        int filled = 0;
        int strips = 0;
        int ordinal = 0;
        for (int start = 0; start < sorted.length;) {
            BlockPosition first = this.targets.get(sorted[start]);
            if (start == 0 || first.y() != this.targets.get(sorted[start - 1]).y()) { ordinal = 0; }
            int end = start + 1;
            while (end < sorted.length && this.targets.get(sorted[end]).y() == first.y()
                    && (long) this.targets.get(sorted[end]).x() - first.x() <= STRIP_SPAN) { end++; }
            boolean forward = (ordinal & 1) == 0;
            List<Integer> members = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                int index = sorted[i];
                members.add(index);
                strip[index] = strips;
                along[index] = forward ? this.targets.get(index).z() : -(long) this.targets.get(index).z();
            }
            // Interleave the strip's rows so its cells are visited in order along the strip.
            members.sort(Comparator.comparingLong((Integer index) -> along[index])
                    .thenComparingInt(index -> this.targets.get(index).x())
                    .thenComparingInt(Integer::intValue));
            for (int index : members) { sweep[filled++] = index; }
            strips++;
            ordinal++;
            start = end;
        }
    }

    void begin() {
        cursor = 0;
        floor = null;
        fallback = -1;
        fallbackReady = false;
        lookAhead = -1;
        reachable = -1;
        invalid = -1;
        pending = false;
        complete = false;
        farthest = -1;
        selected = -1;
    }

    void scan(int budget, IntFunction<TargetState> state, IntPredicate canReach,
              IntToDoubleFunction distanceSquared) {
        if (budget < 1) { throw new IllegalArgumentException("selection budget must be positive"); }
        while (!complete && cursor < sweep.length && budget-- > 0) {
            int index = sweep[cursor++];
            if (floor != null && targets.get(index).y() != floor) { finish(); break; }
            TargetState status = Objects.requireNonNull(state.apply(index), "target state");
            if (status == TargetState.COMPLETE) { continue; }
            floor = targets.get(index).y();
            if (status == TargetState.INVALID) { invalid = index; finish(); break; }
            if (status == TargetState.PENDING) { pending = true; continue; }
            if (fallback < 0) {
                fallback = index;
                fallbackReady = status == TargetState.READY;
            } else if (status == TargetState.READY && fallbackReady) {
                considerLookAhead(index);
            }
            if (status != TargetState.READY || !canReach.test(index)) { continue; }
            double distance = distanceSquared.applyAsDouble(index);
            // Plant the outside of this visible patch first so new crops do not hide farther soil.
            if (Double.isFinite(distance) && distance >= 0 && distance > farthest) {
                reachable = index;
                farthest = distance;
            }
        }
        if (!complete && cursor == sweep.length) { finish(); }
    }

    /**
     * Moving to the first unfinished cell stops where it is barely in reach, which uncovers about
     * one new cell per row. Aiming a few cells further along the same strip brings the first cell
     * and the next ones into reach together.
     */
    private void considerLookAhead(int index) {
        if (strip[index] != strip[fallback]) { return; }
        long offset = along[index] - along[fallback];
        if (offset < 1 || offset > LOOK_AHEAD_BLOCKS) { return; }
        if (lookAhead < 0) {
            lookAhead = index;
            return;
        }
        long best = along[lookAhead] - along[fallback];
        long spread = Math.abs((long) targets.get(index).x() - targets.get(fallback).x());
        long bestSpread = Math.abs((long) targets.get(lookAhead).x() - targets.get(fallback).x());
        if (offset > best || (offset == best && spread < bestSpread)) { lookAhead = index; }
    }

    private void finish() {
        complete = true;
        if (invalid >= 0) {
            selected = invalid;
        } else if (reachable >= 0) {
            selected = reachable;
        } else if (lookAhead >= 0 && lookAheadUsedFor != fallback) {
            lookAheadUsedFor = fallback;
            selected = lookAhead;
        } else {
            selected = fallback;
        }
    }

    boolean complete() { return complete; }
    boolean waitingForPrediction() { return pending && fallback < 0 && invalid < 0; }
    int invalidIndex() { return invalid; }
    int selectedIndex() {
        if (!complete) { throw new IllegalStateException("target selection is still scanning"); }
        return selected;
    }

    /** The sweep as target indices, for tests. */
    int[] sweepOrder() { return sweep.clone(); }
}
