package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BlockState;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.SchematicPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Fixed-cell, bounded read policy; callers supply only client-thread received-world reads. */
final class SoilWatchpoints {
    static final int DISCOVERY_BUDGET = 128;
    static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    static final int ENVIRONMENT_CELLS = 9 * 9 * 2;

    interface Source {
        boolean received(BlockPosition position);
        boolean predictionPending(BlockPosition position);
        BlockState state(BlockPosition position);
        boolean water(BlockPosition position);
        Boolean rainAt(BlockPosition position);
    }

    private final SchematicPlan plan;
    private final RunContext context;
    private final String epoch;
    private final List<SoilWatchpointObservation.Point> points = new ArrayList<>();
    private int chunkCursor;
    private int targetCursor;
    private Long lastNanos;
    private Instant sampledAt;
    private boolean available;
    private String reason = "No fresh soil sample yet";

    SoilWatchpoints(SchematicPlan plan, RunContext context, String epoch, ChunkCoordinate preferredChunk) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.context = Objects.requireNonNull(context, "context");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        if (epoch.length() > 64) { throw new IllegalArgumentException("Epoch exceeds bounds"); }
        if (preferredChunk != null && plan.layout().contains(preferredChunk)) {
            chunkCursor = (preferredChunk.z() - plan.layout().origin().z()) * plan.layout().columns()
                    + preferredChunk.x() - plan.layout().origin().x();
        }
    }

    void tick(long nowNanos, Instant now, Source source) {
        if (lastNanos != null && nowNanos - lastNanos < SAMPLE_INTERVAL_NANOS) { return; }
        lastNanos = nowNanos;
        sampledAt = now;
        available = source != null;
        reason = source == null ? "Selected client world is unavailable" : "";
        for (int index = 0; index < points.size(); index++) {
            var point = points.get(index);
            points.set(index, append(point, sample(point.position(), now, source, false)));
        }
        if (source != null && points.size() < SoilWatchpointObservation.MAX_POINTS) {
            discover(now, source);
        }
    }

    SoilWatchpointObservation observation() {
        return new SoilWatchpointObservation(available, reason, plan.planId(), context, epoch, sampledAt, points);
    }

    void unavailable(Instant now) {
        available = false;
        reason = "Soil observation unavailable";
        sampledAt = now;
        for (int index = 0; index < points.size(); index++) {
            points.set(index, append(points.get(index),
                    unknown(now, "READ_FAILED", false, null, false, null)));
        }
    }

    private void discover(Instant now, Source source) {
        // Each iteration consumes a candidate or an empty/unreceived chunk; neither scan is unbounded.
        for (int attempt = 0; attempt < DISCOVERY_BUDGET
                && points.size() < SoilWatchpointObservation.MAX_POINTS; attempt++) {
            var chunk = plan.chunk(chunkCursor);
            var targets = chunk.tillTargets();
            if (targets.isEmpty() || targetCursor >= targets.size()) { nextChunk(); continue; }
            BlockPosition position = targets.get(targetCursor++);
            if (!plan.buildVolume().contains(position)) { continue; }
            if (!source.received(position)) { nextChunk(); continue; }
            if (points.stream().anyMatch(point -> point.position().equals(position))) { continue; }
            SoilWatchpointObservation.Sample candidate = sample(position, now, source, true);
            if (!candidate.fresh() || !candidate.actual().blockId().equals("minecraft:farmland")
                    || !candidate.above().isAir()) { continue; }
            points.add(new SoilWatchpointObservation.Point(position, candidate, candidate, candidate,
                    1, 0, false, List.of(candidate), false));
        }
    }

    private void nextChunk() {
        chunkCursor = (chunkCursor + 1) % plan.chunkCount();
        targetCursor = 0;
    }

    private static SoilWatchpointObservation.Sample sample(BlockPosition position, Instant now, Source source,
                                                           boolean selecting) {
        if (source == null) { return unknown(now, "CONTEXT_UNAVAILABLE", false, null, false, null); }
        try {
            BlockPosition above = offset(position, 0, 1, 0);
            boolean received = source.received(position);
            boolean aboveReceived = source.received(above);
            Boolean pending = received ? source.predictionPending(position) : null;
            Boolean abovePending = aboveReceived ? source.predictionPending(above) : null;
            if (!received || !aboveReceived) {
                return unknown(now, "UNLOADED", received, pending, aboveReceived, abovePending);
            }
            if (Boolean.TRUE.equals(pending) || Boolean.TRUE.equals(abovePending)) {
                return unknown(now, "PREDICTION_PENDING", received, pending, aboveReceived, abovePending);
            }
            BlockState state = source.state(position);
            BlockState aboveState = source.state(above);
            Integer moisture = state.blockId().equals("minecraft:farmland")
                    ? Integer.valueOf(state.properties().get("moisture")) : null;
            if (selecting && (!state.blockId().equals("minecraft:farmland") || !aboveState.isAir())) {
                return new SoilWatchpointObservation.Sample(now, "FRESH", true, false, true, false,
                        state, aboveState, moisture, false, null, null);
            }
            boolean complete = true;
            boolean water = false;
            // The pinned farmland hydration range: x/z +/-4, soil Y through Y+1.
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    for (int y = 0; y <= 1; y++) {
                        BlockPosition nearby = offset(position, x, y, z);
                        if (!source.received(nearby) || source.predictionPending(nearby)) { complete = false; }
                        else { water |= source.water(nearby); }
                    }
                }
            }
            Boolean rain = complete ? source.rainAt(above) : null;
            complete &= rain != null;
            return new SoilWatchpointObservation.Sample(now, "FRESH", true, false, true, false,
                    state, aboveState, moisture, complete, complete ? water : null, rain);
        } catch (RuntimeException exception) {
            return unknown(now, "READ_FAILED", false, null, false, null);
        }
    }

    private static SoilWatchpointObservation.Sample unknown(Instant now, String status,
            boolean received, Boolean pending, boolean aboveReceived, Boolean abovePending) {
        return new SoilWatchpointObservation.Sample(now, status, received, pending, aboveReceived, abovePending,
                null, null, null, false, null, null);
    }

    private static SoilWatchpointObservation.Point append(SoilWatchpointObservation.Point point,
                                                         SoilWatchpointObservation.Sample sample) {
        boolean changed = !sameFacts(point.latest(), sample);
        List<SoilWatchpointObservation.Sample> history = new ArrayList<>(point.history());
        boolean truncated = point.historyTruncated();
        if (changed) {
            history.add(sample);
            if (history.size() > SoilWatchpointObservation.MAX_HISTORY) { history.removeFirst(); truncated = true; }
        }
        return new SoilWatchpointObservation.Point(point.position(), point.firstFresh(),
                sample.fresh() ? sample : point.lastFresh(), sample,
                point.freshSamples() + (sample.fresh() ? 1 : 0), point.changes() + (changed ? 1 : 0),
                point.continuityUnknown() || !sample.fresh()
                        || java.time.Duration.between(point.latest().at(), sample.at()).toMillis() > 2_000,
                history, truncated);
    }

    private static boolean sameFacts(SoilWatchpointObservation.Sample left, SoilWatchpointObservation.Sample right) {
        return left.status().equals(right.status()) && left.received() == right.received()
                && Objects.equals(left.predictionPending(), right.predictionPending())
                && left.aboveReceived() == right.aboveReceived()
                && Objects.equals(left.abovePredictionPending(), right.abovePredictionPending())
                && Objects.equals(left.actual(), right.actual()) && Objects.equals(left.above(), right.above())
                && left.environmentComplete() == right.environmentComplete()
                && Objects.equals(left.nearbyWater(), right.nearbyWater())
                && Objects.equals(left.rainAtAbove(), right.rainAtAbove());
    }

    private static BlockPosition offset(BlockPosition position, int x, int y, int z) {
        return new BlockPosition(Math.addExact(position.x(), x), Math.addExact(position.y(), y),
                Math.addExact(position.z(), z));
    }
}
