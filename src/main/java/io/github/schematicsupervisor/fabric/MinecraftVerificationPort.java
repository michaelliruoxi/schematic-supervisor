package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.BlockObservation;
import io.github.schematicsupervisor.core.BlockPosition;
import io.github.schematicsupervisor.core.BuildVolume;
import io.github.schematicsupervisor.core.ChunkCoordinate;
import io.github.schematicsupervisor.core.SchematicPlan;
import io.github.schematicsupervisor.core.SchematicVerification;
import io.github.schematicsupervisor.core.SupervisorPorts;
import io.github.schematicsupervisor.core.VerificationResult;
import io.github.schematicsupervisor.core.VerificationScope;
import io.github.schematicsupervisor.core.VerificationTaskSnapshot;
import io.github.schematicsupervisor.core.VerificationTaskStatus;
import io.github.schematicsupervisor.fabric.mixin.ClientWorldPendingUpdatesAccessor;
import io.github.schematicsupervisor.fabric.mixin.PendingBlockUpdatesAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Captures a bounded world snapshot on the client thread, then performs the CPU-heavy comparison
 * away from the client tick.
 */
final class MinecraftVerificationPort implements SupervisorPorts.Verification, AutoCloseable {
    private static final VerificationTaskSnapshot IDLE = new VerificationTaskSnapshot(
            VerificationTaskStatus.IDLE,
            java.util.Optional.empty(),
            ""
    );

    private final MinecraftClient client;
    private final int blocksPerTick;
    private final Supplier<? extends Collection<BlockPosition>> temporaryScaffolding;
    private final Executor verificationExecutor;
    private final MinecraftFlightNavigation approachNavigation;

    private ScanSession scanSession;
    private ClientWorld sourceWorld;
    private ChunkCoordinate approachChunk;
    private CompletableFuture<VerificationResult> verificationFuture;
    private VerificationTaskSnapshot terminalSnapshot;

    MinecraftVerificationPort(MinecraftClient client, int blocksPerTick) {
        this(client, blocksPerTick, List::of, ForkJoinPool.commonPool());
    }

    MinecraftVerificationPort(
            MinecraftClient client,
            int blocksPerTick,
            Supplier<? extends Collection<BlockPosition>> temporaryScaffolding,
            Executor verificationExecutor
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.approachNavigation = new MinecraftFlightNavigation(client);
        if (blocksPerTick < 1) {
            throw new IllegalArgumentException("blocksPerTick must be positive");
        }
        this.blocksPerTick = blocksPerTick;
        this.temporaryScaffolding = Objects.requireNonNull(
                temporaryScaffolding,
                "temporaryScaffolding"
        );
        this.verificationExecutor = Objects.requireNonNull(
                verificationExecutor,
                "verificationExecutor"
        );
    }

    @Override
    public void beginVerification(SchematicPlan plan, VerificationScope scope) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scope, "scope");
        cancelVerification();
        if (client.world == null) {
            terminalSnapshot = VerificationTaskSnapshot.failed("Client world is unavailable");
            return;
        }
        try {
            sourceWorld = client.world;
            scanSession = new ScanSession(plan, scope);
            terminalSnapshot = null;
        } catch (RuntimeException exception) {
            terminalSnapshot = VerificationTaskSnapshot.failed(detail(exception));
        }
    }

    /**
     * Must be called once from the client-tick callback. No world object is read off-thread.
     */
    void tick() {
        if (scanSession == null || terminalSnapshot != null) {
            return;
        }
        ClientWorld world = client.world;
        if (world == null || world != sourceWorld) {
            fail("Client world changed or became unavailable during verification");
            return;
        }
        try {
            if (approachChunk != null && ClientChunkAvailability.isLoaded(
                    world, approachChunk.x(), approachChunk.z())) {
                stopApproach();
            }
            scanSession.capture(world, blocksPerTick, approachNavigation.active());
            if (!scanSession.complete()) {
                approachMissingChunk();
                return;
            }
            stopApproach();
            ScanSession completed = scanSession;
            scanSession = null;
            BlockObservation snapshot = completed.snapshot(
                    temporaryScaffolding.get()
            );
            verificationFuture = CompletableFuture.supplyAsync(
                    () -> SchematicVerification.verify(
                            completed.plan(),
                            completed.scope(),
                            snapshot
                    ),
                    verificationExecutor
            );
        } catch (RuntimeException exception) {
            fail(detail(exception));
        }
    }

    @Override
    public VerificationTaskSnapshot pollVerification() {
        if (terminalSnapshot != null) {
            return terminalSnapshot;
        }
        if (sourceWorld != null && client.world != sourceWorld) {
            fail("Client world changed or became unavailable during verification");
            return terminalSnapshot;
        }
        CompletableFuture<VerificationResult> future = verificationFuture;
        if (future == null || !future.isDone()) {
            return scanSession == null && future == null
                    ? IDLE
                    : new VerificationTaskSnapshot(VerificationTaskStatus.RUNNING,
                            java.util.Optional.empty(), statusDetail());
        }
        verificationFuture = null;
        try {
            terminalSnapshot = VerificationTaskSnapshot.succeeded(future.join());
        } catch (CompletionException | java.util.concurrent.CancellationException exception) {
            terminalSnapshot = VerificationTaskSnapshot.failed(detail(exception));
        }
        return terminalSnapshot;
    }

    @Override
    public void cancelVerification() {
        stopApproach();
        scanSession = null;
        sourceWorld = null;
        if (verificationFuture != null) {
            verificationFuture.cancel(true);
            verificationFuture = null;
        }
        terminalSnapshot = null;
    }

    @Override
    public void close() {
        cancelVerification();
    }

    String statusDetail() {
        if (approachNavigation.active()) {
            return "Verification: " + approachNavigation.detail();
        }
        return scanSession == null ? "" : scanSession.waitDetail();
    }

    private void approachMissingChunk() {
        ChunkCoordinate waiting = scanSession.waitingChunk();
        if (waiting == null) {
            return;
        }
        if (!waiting.equals(approachChunk)) {
            stopApproach();
            approachChunk = waiting;
            approachNavigation.beginApproachChunk(waiting.x(), waiting.z());
        } else {
            approachNavigation.tick();
        }
        if (approachNavigation.failed()) {
            fail("Verification could not obtain received chunk data: " + approachNavigation.detail());
        }
    }

    private void stopApproach() {
        approachNavigation.stop();
        approachChunk = null;
    }

    private void fail(String detail) {
        stopApproach();
        scanSession = null;
        if (verificationFuture != null) {
            verificationFuture.cancel(true);
            verificationFuture = null;
        }
        terminalSnapshot = VerificationTaskSnapshot.failed(detail);
    }

    private static String detail(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }

    interface CaptureObservation extends BlockObservation {
        boolean predictionPending(BlockPosition position);
    }

    static final class ScanSession {
        private static final long CHUNK_WAIT_NANOS = TimeUnit.SECONDS.toNanos(15);
        private static final long PREDICTION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(15);
        private final SchematicPlan plan;
        private final VerificationScope scope;
        private final BuildVolume volume;
        private final List<VerificationScanLayout.ChunkScan> scans;
        private final io.github.schematicsupervisor.core.BlockState[] states;
        private final Map<ChunkCoordinate, Boolean> chunkLoaded = new HashMap<>();
        private final IdentityHashMap<net.minecraft.block.BlockState,
                io.github.schematicsupervisor.core.BlockState> convertedStates =
                new IdentityHashMap<>();

        private int chunkCursor;
        private boolean chunkInitialized;
        private int x;
        private int y;
        private int z;
        private Long unavailableSinceNanos;
        private Long predictionSinceNanos;
        private String waitDetail = "";

        ScanSession(SchematicPlan plan, VerificationScope scope) {
            this.plan = plan;
            this.scope = scope;
            volume = plan.buildVolume();
            scans = VerificationScanLayout.forPlan(plan, scope);
            io.github.schematicsupervisor.core.PlanLimits.requireVolume(volume);
            states = new io.github.schematicsupervisor.core.BlockState[
                    Math.toIntExact(volume.blockCount())
            ];
        }

        private SchematicPlan plan() {
            return plan;
        }

        private VerificationScope scope() {
            return scope;
        }

        boolean complete() {
            return chunkCursor >= scans.size();
        }

        private void capture(ClientWorld world, int budget, boolean approaching) {
            capture(new CaptureObservation() {
                @Override
                public boolean isChunkLoaded(ChunkCoordinate chunk) {
                    return ClientChunkAvailability.isLoaded(world, chunk.x(), chunk.z());
                }

                @Override
                public io.github.schematicsupervisor.core.BlockState blockState(BlockPosition position) {
                    return convertedStates.computeIfAbsent(
                            world.getBlockState(new BlockPos(position.x(), position.y(), position.z())),
                            MinecraftBlockStates::toCore);
                }

                @Override
                public boolean predictionPending(BlockPosition position) {
                    var manager = ((ClientWorldPendingUpdatesAccessor) world).supervisor$getPendingUpdateManager();
                    return ((PendingBlockUpdatesAccessor) manager).supervisor$getPendingBlockUpdates()
                            .containsKey(BlockPos.asLong(position.x(), position.y(), position.z()));
                }

                @Override
                public List<BlockPosition> temporaryScaffolding(VerificationScope ignored) {
                    return List.of();
                }
            }, budget, System.nanoTime(), approaching);
        }

        void capture(CaptureObservation observation, int budget, long nowNanos) {
            capture(observation, budget, nowNanos, false);
        }

        void capture(CaptureObservation observation, int budget, long nowNanos, boolean approaching) {
            int remaining = budget;
            while (remaining > 0 && !complete()) {
                VerificationScanLayout.ChunkScan scan = scans.get(chunkCursor);
                if (!observation.isChunkLoaded(scan.chunk())) {
                    chunkLoaded.put(scan.chunk(), false);
                    // A chunk can unload between capture ticks. Recapture its whole slice
                    // when data returns instead of combining states from two client chunks.
                    chunkInitialized = false;
                    predictionSinceNanos = null;
                    if (unavailableSinceNanos == null || approaching) {
                        unavailableSinceNanos = nowNanos;
                    }
                    waitDetail = "Waiting for received chunk data during verification: "
                            + scan.chunk().x() + "," + scan.chunk().z();
                    if (nowNanos - unavailableSinceNanos >= CHUNK_WAIT_NANOS) {
                        throw new IllegalStateException(waitDetail + " (timed out after 15 seconds)");
                    }
                    return;
                }
                unavailableSinceNanos = null;
                waitDetail = "";
                if (!chunkInitialized) {
                    initializeChunk(scan);
                    if (!chunkInitialized) {
                        continue;
                    }
                }
                BlockPosition position = new BlockPosition(x, y, z);
                if (observation.predictionPending(position)) {
                    if (predictionSinceNanos == null) { predictionSinceNanos = nowNanos; }
                    waitDetail = "Waiting for server acknowledgement during verification at "
                            + position.x() + "," + position.y() + "," + position.z();
                    if (nowNanos - predictionSinceNanos >= PREDICTION_WAIT_NANOS) {
                        throw new IllegalStateException(waitDetail + " (timed out after 15 seconds)");
                    }
                    return;
                }
                predictionSinceNanos = null;
                io.github.schematicsupervisor.core.BlockState coreState = observation.blockState(position);
                states[indexOf(volume, x, y, z)] = coreState;
                advance(scan);
                remaining--;
            }
        }

        String waitDetail() {
            return waitDetail;
        }

        ChunkCoordinate waitingChunk() {
            return unavailableSinceNanos == null || complete() ? null : scans.get(chunkCursor).chunk();
        }

        private void initializeChunk(VerificationScanLayout.ChunkScan scan) {
            chunkLoaded.put(scan.chunk(), true);
            if (!scan.intersectsVolume()) {
                finishChunk();
                return;
            }
            x = scan.minX();
            y = scan.minY();
            z = scan.minZ();
            chunkInitialized = true;
        }

        private void advance(VerificationScanLayout.ChunkScan scan) {
            if (z < scan.maxZ()) {
                z++;
                return;
            }
            z = scan.minZ();
            if (x < scan.maxX()) {
                x++;
                return;
            }
            x = scan.minX();
            if (y < scan.maxY()) {
                y++;
                return;
            }
            finishChunk();
        }

        private void finishChunk() {
            chunkCursor++;
            chunkInitialized = false;
        }

        BlockObservation snapshot(
                Collection<BlockPosition> currentScaffolding
        ) {
            if (!complete()) {
                throw new IllegalStateException("Verification capture is incomplete");
            }
            Objects.requireNonNull(currentScaffolding, "temporary scaffolding");
            Set<ChunkCoordinate> requestedChunks = new HashSet<>();
            for (VerificationScanLayout.ChunkScan scan : scans) {
                requestedChunks.add(scan.chunk());
            }
            ArrayList<BlockPosition> scopedScaffolding = new ArrayList<>();
            for (BlockPosition position : currentScaffolding) {
                Objects.requireNonNull(position, "temporary scaffolding position");
                if (volume.contains(position)
                        && requestedChunks.contains(ChunkCoordinate.containing(position))) {
                    scopedScaffolding.add(position);
                }
            }
            return new SnapshotBlockObservation(
                    volume,
                    states,
                    chunkLoaded,
                    scopedScaffolding
            );
        }
    }

    private static final class SnapshotBlockObservation implements BlockObservation {
        private final BuildVolume volume;
        private final io.github.schematicsupervisor.core.BlockState[] states;
        private final Map<ChunkCoordinate, Boolean> chunkLoaded;
        private final List<BlockPosition> temporaryScaffolding;

        private SnapshotBlockObservation(
                BuildVolume volume,
                io.github.schematicsupervisor.core.BlockState[] states,
                Map<ChunkCoordinate, Boolean> chunkLoaded,
                Collection<BlockPosition> temporaryScaffolding
        ) {
            this.volume = volume;
            this.states = states.clone();
            this.chunkLoaded = Map.copyOf(chunkLoaded);
            this.temporaryScaffolding = List.copyOf(temporaryScaffolding);
        }

        @Override
        public boolean isChunkLoaded(ChunkCoordinate chunk) {
            return chunkLoaded.getOrDefault(chunk, false);
        }

        @Override
        public io.github.schematicsupervisor.core.BlockState blockState(
                BlockPosition position
        ) {
            if (!volume.contains(position)) {
                throw new IllegalArgumentException("Position is outside the captured volume");
            }
            io.github.schematicsupervisor.core.BlockState state = states[indexOf(
                    volume,
                    position.x(),
                    position.y(),
                    position.z()
            )];
            if (state == null) {
                throw new IllegalStateException(
                        "No captured state for loaded position " + position
                );
            }
            return state;
        }

        @Override
        public List<BlockPosition> temporaryScaffolding(VerificationScope ignored) {
            return temporaryScaffolding;
        }
    }

    private static int indexOf(BuildVolume volume, int x, int y, int z) {
        long sizeX = (long) volume.maxX() - volume.minX() + 1;
        long sizeZ = (long) volume.maxZ() - volume.minZ() + 1;
        long index = ((long) y - volume.minY()) * sizeX * sizeZ
                + ((long) x - volume.minX()) * sizeZ
                + ((long) z - volume.minZ());
        return Math.toIntExact(index);
    }
}
