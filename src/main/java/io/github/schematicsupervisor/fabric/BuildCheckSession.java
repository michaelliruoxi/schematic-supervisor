package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.BuildCheck;
import io.github.schematicsupervisor.core.LayerBuildSchedule;
import io.github.schematicsupervisor.core.OrdinaryPlacement;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.VerificationScope;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Reads the received build chunks on the client thread without moving the player, then compares
 * them with the plan on a worker thread. Chunks the client has not received are left for the
 * builder to check when the schedule reaches them.
 */
final class BuildCheckSession {
    /** Client-thread world reads; tests supply their own. */
    interface WorldReader {
        boolean chunkReceived(int chunkX, int chunkZ);

        BlockState blockState(int x, int y, int z);

        /** False lets the capture skip the per-cell prediction lookup. */
        boolean predictionsPending();

        boolean predictionPending(int x, int y, int z);
    }

    enum Status { CAPTURING, EVALUATING, COMPLETE, FAILED }

    /** What the builder clears by itself: moss and similar blocks at placements, stems in open cells. */
    static final BuildCheck.Clearing BUILDER_CLEARING = new BuildCheck.Clearing() {
        @Override
        public boolean replacesAtPlacement(OrdinaryPlacement placement, BlockState actual) {
            return MossClearingPolicy.allowsReplacement(placement, actual.blockId());
        }

        @Override
        public boolean sweepsOpenCell(BlockState actual) {
            return StemClearingSweep.isStem(actual.blockId());
        }
    };

    // Bounds each tick's share of the capture so a slow machine does not drop frames.
    private static final long SLICE_NANOS = 4_000_000L;

    private final SchematicPlan plan;
    private final LayerBuildSchedule schedule;
    private final BuildCheck.Clearing clearing;
    private final BuildCheck.Supports supports;
    private final Executor executor;
    private final BuildCheck.Snapshot snapshot;
    private final List<VerificationScanLayout.ChunkScan> scans;
    private final long totalCells;
    private final long startedNanos;
    private long capturedCells;
    private int chunkCursor;
    private boolean chunkStarted;
    private int x;
    private int y;
    private int z;
    private CompletableFuture<BuildCheck.Result> evaluation;
    private BuildCheck.Result result;
    private String failure = "";
    private long finishedNanos;

    BuildCheckSession(SchematicPlan plan, LayerBuildSchedule schedule, BuildCheck.Clearing clearing,
                      BuildCheck.Supports supports, Executor executor, long startedNanos) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.clearing = Objects.requireNonNull(clearing, "clearing");
        this.supports = Objects.requireNonNull(supports, "supports");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.startedNanos = startedNanos;
        snapshot = new BuildCheck.Snapshot(plan.buildVolume(), plan.layout());
        scans = VerificationScanLayout.forPlan(plan, VerificationScope.fullPlan());
        totalCells = scans.stream().mapToLong(VerificationScanLayout.ChunkScan::blockCount).sum();
    }

    /** Captures up to {@code budget} cells, then starts the comparison once every chunk is read. */
    void tick(WorldReader world, int budget, LongSupplier nanoTime) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(nanoTime, "nanoTime");
        if (status() != Status.CAPTURING) {
            return;
        }
        try {
            capture(world, budget, nanoTime);
            if (chunkCursor >= scans.size()) {
                BuildCheck.Snapshot captured = snapshot;
                evaluation = CompletableFuture.supplyAsync(
                        () -> BuildCheck.evaluate(plan, schedule, captured, clearing, supports), executor);
            }
        } catch (RuntimeException exception) {
            fail(exception, nanoTime.getAsLong());
        }
    }

    /** Collects a finished comparison; call once per tick after {@link #tick}. */
    Status poll(long nowNanos) {
        if (evaluation != null && evaluation.isDone() && result == null && failure.isEmpty()) {
            try {
                result = evaluation.join();
                finishedNanos = nowNanos;
            } catch (CompletionException | CancellationException exception) {
                fail(exception.getCause() == null ? exception : exception.getCause(), nowNanos);
            }
        }
        return status();
    }

    Status status() {
        if (!failure.isEmpty()) { return Status.FAILED; }
        if (result != null) { return Status.COMPLETE; }
        return evaluation == null ? Status.CAPTURING : Status.EVALUATING;
    }

    /** Share of cells read so far; the comparison itself takes a fraction of a second. */
    double progress() {
        if (status() != Status.CAPTURING) { return 1.0; }
        return totalCells == 0 ? 0.0 : Math.min(1.0, (double) capturedCells / totalCells);
    }

    BuildCheck.Result result() {
        return result;
    }

    String failure() {
        return failure;
    }

    long elapsedMillis(long nowNanos) {
        long end = status() == Status.COMPLETE || status() == Status.FAILED ? finishedNanos : nowNanos;
        return Math.max(0, (end - startedNanos) / 1_000_000L);
    }

    void cancel() {
        if (evaluation != null) {
            evaluation.cancel(true);
        }
    }

    private void capture(WorldReader world, int budget, LongSupplier nanoTime) {
        long deadline = nanoTime.getAsLong() + SLICE_NANOS;
        boolean predictions = world.predictionsPending();
        int remaining = Math.max(1, budget);
        while (chunkCursor < scans.size() && remaining > 0) {
            VerificationScanLayout.ChunkScan scan = scans.get(chunkCursor);
            if (!chunkStarted) {
                if (!scan.intersectsVolume()) {
                    snapshot.markReceived(chunkCursor);
                    chunkCursor++;
                    continue;
                }
                if (!world.chunkReceived(scan.chunk().x(), scan.chunk().z())) {
                    // Not received: leave it unchecked instead of travelling to it.
                    capturedCells += scan.blockCount();
                    chunkCursor++;
                    continue;
                }
                chunkStarted = true;
                x = scan.minX();
                y = scan.minY();
                z = scan.minZ();
            } else if (!world.chunkReceived(scan.chunk().x(), scan.chunk().z())) {
                // Unloaded between ticks; never combine states from two copies of a chunk.
                capturedCells += remainingCells(scan);
                chunkStarted = false;
                chunkCursor++;
                continue;
            }
            while (remaining > 0) {
                snapshot.put(x, y, z, predictions && world.predictionPending(x, y, z)
                        ? null : Objects.requireNonNull(world.blockState(x, y, z), "captured block state"));
                capturedCells++;
                remaining--;
                if (!advance(scan)) {
                    snapshot.markReceived(chunkCursor);
                    chunkStarted = false;
                    chunkCursor++;
                    break;
                }
                if ((remaining & 1023) == 0 && nanoTime.getAsLong() >= deadline) {
                    return;
                }
            }
        }
    }

    /** Steps Z, then X, then Y; false once the chunk's last cell has been read. */
    private boolean advance(VerificationScanLayout.ChunkScan scan) {
        if (z < scan.maxZ()) { z++; return true; }
        z = scan.minZ();
        if (x < scan.maxX()) { x++; return true; }
        x = scan.minX();
        if (y < scan.maxY()) { y++; return true; }
        return false;
    }

    private long remainingCells(VerificationScanLayout.ChunkScan scan) {
        long width = (long) scan.maxX() - scan.minX() + 1;
        long depth = (long) scan.maxZ() - scan.minZ() + 1;
        long done = ((long) y - scan.minY()) * width * depth + ((long) x - scan.minX()) * depth + (z - scan.minZ());
        return scan.blockCount() - done;
    }

    private void fail(Throwable exception, long nowNanos) {
        String message = exception.getMessage();
        failure = message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
        finishedNanos = nowNanos;
        cancel();
    }
}
